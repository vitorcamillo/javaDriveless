package io.github.selenium.javaDriverless;

import io.github.selenium.javaDriverless.types.ProxyConfig;
import io.github.selenium.javaDriverless.utils.IpLiterals;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.StandardSocketOptions;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Collections;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.Semaphore;
import java.util.concurrent.SynchronousQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import javax.net.ssl.SSLParameters;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.SSLSocketFactory;

/**
 * Local HTTP proxy bridge for authenticated upstream proxies.
 *
 * <p>Chrome connects to 127.0.0.1 without proxy credentials. The bridge forwards
 * traffic to the authenticated upstream proxy and injects Proxy-Authorization
 * outside of the browser process. This avoids Chrome extension auth surfaces and
 * keeps the default proxy path untouched.</p>
 */
public final class AuthenticatedProxyBridge implements AutoCloseable {
    private static final Logger LOG = LoggerFactory.getLogger(AuthenticatedProxyBridge.class);
    private static final String ALLOW_INSECURE_HTTP_PROXY_AUTH =
            "JAVA_DRIVERLESS_ALLOW_INSECURE_HTTP_PROXY_AUTH";
    /** RFC 1123: labels de 1-63, letras/dígitos com hífen interno, sem label vazio; ponto final opcional. */
    private static final Pattern CONNECT_HOST = Pattern.compile(
            "[A-Za-z0-9]([A-Za-z0-9-]{0,61}[A-Za-z0-9])?(\\.[A-Za-z0-9]([A-Za-z0-9-]{0,61}[A-Za-z0-9])?)*\\.?");
    private static final int BUFFER_SIZE = 16 * 1024;
    private static final int SOCKET_TIMEOUT_MS = 60_000;
    private static final int CONNECT_TIMEOUT_MS = 10_000;
    static final int MAX_HEADER_BYTES = 64 * 1024;
    static final int MAX_ACTIVE_CONNECTIONS = 64;
    static final int MAX_WORKER_THREADS = MAX_ACTIVE_CONNECTIONS * 2;

    private final ProxyConfig upstream;
    private final UpstreamTransport upstreamTransport;
    private final ServerSocket serverSocket;
    private final ThreadPoolExecutor executor;
    private final Semaphore connectionSlots = new Semaphore(MAX_ACTIVE_CONNECTIONS);
    private final Set<Socket> openSockets = Collections.newSetFromMap(new ConcurrentHashMap<>());
    private final String proxyAuthorization;
    private final Thread acceptor;
    private volatile boolean running = true;
    private volatile ProxyBridgeClientAuthorizer.ProcessIdentity ownerIdentity;

    enum UpstreamTransport {
        PLAINTEXT,
        TLS
    }

    private AuthenticatedProxyBridge(
            ProxyConfig upstream,
            UpstreamTransport upstreamTransport,
            ServerSocket serverSocket) {
        this.upstream = upstream;
        this.upstreamTransport = upstreamTransport;
        this.serverSocket = serverSocket;
        this.executor = new ThreadPoolExecutor(0, MAX_WORKER_THREADS, 60L, TimeUnit.SECONDS,
                new SynchronousQueue<>(), r -> {
            Thread thread = new Thread(r, "java-driverless-proxy-bridge");
            thread.setDaemon(true);
            return thread;
        });
        this.acceptor = new Thread(this::acceptLoop, "java-driverless-proxy-bridge-accept");
        this.acceptor.setDaemon(true);
        String credentials = upstream.getUsername() + ":" + upstream.getPassword();
        this.proxyAuthorization = "Basic " + Base64.getEncoder()
                .encodeToString(credentials.getBytes(StandardCharsets.UTF_8));
    }

    public static AuthenticatedProxyBridge start(ProxyConfig upstream) throws IOException {
        return start(
                upstream,
                ProxyBridgeClientAuthorizer.processGateAvailable(),
                insecureHttpProxyAuthExplicitlyEnabled());
    }

    /** Indica se o gate seguro por processo está disponível neste ambiente. */
    public static boolean isSupportedOnCurrentPlatform() {
        return ProxyBridgeClientAuthorizer.processGateAvailable();
    }

    static AuthenticatedProxyBridge start(
            ProxyConfig upstream,
            boolean processGateAvailable,
            boolean allowInsecureHttpProxyAuth) throws IOException {
        UpstreamTransport transport = validateAndSelectTransport(upstream, allowInsecureHttpProxyAuth);
        ProxyBridgeClientAuthorizer.requireProcessGateAvailable(processGateAvailable);

        // All policy validation intentionally happens before opening the listening socket.
        ServerSocket serverSocket = new ServerSocket(0, 50,
                InetAddress.getByAddress(new byte[] {127, 0, 0, 1}));
        AuthenticatedProxyBridge bridge = new AuthenticatedProxyBridge(upstream, transport, serverSocket);
        bridge.acceptor.start();
        return bridge;
    }

    static UpstreamTransport validateAndSelectTransport(
            ProxyConfig upstream,
            boolean allowInsecureHttpProxyAuth) {
        if (upstream == null || !upstream.hasAuthentication()) {
            throw new IllegalArgumentException("Proxy autenticado é obrigatório para iniciar a ponte local");
        }
        if (upstream.getType() == ProxyConfig.ProxyType.HTTPS) {
            return UpstreamTransport.TLS;
        }
        if (upstream.getType() != ProxyConfig.ProxyType.HTTP) {
            throw new IllegalArgumentException("Ponte local suporta somente upstream HTTP ou HTTPS");
        }
        if (!allowInsecureHttpProxyAuth) {
            throw new IllegalStateException(
                    "Proxy HTTP autenticado envia credenciais sem TLS; habilite explicitamente "
                            + ALLOW_INSECURE_HTTP_PROXY_AUTH + "=true");
        }
        return UpstreamTransport.PLAINTEXT;
    }

    static SSLParameters enableHttpsEndpointIdentification(SSLParameters parameters) {
        parameters.setEndpointIdentificationAlgorithm("HTTPS");
        return parameters;
    }

    private static boolean insecureHttpProxyAuthExplicitlyEnabled() {
        String configured = System.getenv(ALLOW_INSECURE_HTTP_PROXY_AUTH);
        return configured != null && configured.trim().equalsIgnoreCase("true");
    }

    public String asChromeProxyServer() {
        return "http://127.0.0.1:" + serverSocket.getLocalPort();
    }

    /**
     * Vincula a ponte ao processo Chrome que pode utilizar o proxy.
     * A ponte permanece fail-closed até receber um PID vivo.
     *
     * @param ownerPid PID do processo Chrome proprietário
     */
    public synchronized void bindOwner(long ownerPid) {
        ProxyBridgeClientAuthorizer.ProcessIdentity identity =
                ProxyBridgeClientAuthorizer.captureIdentity(ownerPid).orElseThrow(() ->
                        new IllegalArgumentException(
                                "PID proprietário deve identificar processo vivo com startInstant disponível"));
        if (ownerIdentity != null && !ownerIdentity.equals(identity)) {
            throw new IllegalStateException("Ponte de proxy já vinculada a outro processo");
        }
        if (!ProxyBridgeClientAuthorizer.isCurrentIdentity(identity)) {
            throw new IllegalArgumentException("PID proprietário da ponte deve identificar um processo vivo");
        }
        ownerIdentity = identity;
        // ProcessHandle.current().onExit() lança IllegalStateException no Java 21: o futuro nunca
        // poderia completar enquanto o próprio JVM ainda executa este callback. Em produção o
        // owner normalmente é o Chrome filho; para o processo atual, o try-with-resources/shutdown
        // do JVM já governa o lifecycle da ponte.
        if (ownerPid != ProcessHandle.current().pid()) {
            ProcessHandle.of(ownerPid).ifPresent(handle -> handle.onExit().thenRun(this::close));
        }
    }

    private void acceptLoop() {
        while (running) {
            try {
                Socket client = serverSocket.accept();
                client.setSoTimeout(SOCKET_TIMEOUT_MS);
                if (!connectionSlots.tryAcquire()) {
                    closeQuietly(client);
                    continue;
                }
                openSockets.add(client);
                try {
                    executor.execute(() -> handleClient(client));
                } catch (RejectedExecutionException saturated) {
                    openSockets.remove(client);
                    connectionSlots.release();
                    closeQuietly(client);
                }
            } catch (IOException e) {
                if (running) {
                    // Keep accepting future connections; the individual failure belongs to one socket.
                }
            }
        }
    }

    private void handleClient(Socket client) {
        try (client) {
            if (!ProxyBridgeClientAuthorizer.authorize(client, ownerIdentity, serverSocket.getLocalPort())) {
                return;
            }
            InputStream clientIn = new BufferedInputStream(client.getInputStream(), BUFFER_SIZE);
            byte[] headerBytes = readHeaders(clientIn);
            if (headerBytes.length == 0) {
                return;
            }

            String headers = new String(headerBytes, StandardCharsets.ISO_8859_1);
            String requestLine = headers.lines().findFirst().orElse("");
            if (requestLine.trim().split("\\s+", 2)[0].equalsIgnoreCase("CONNECT")) {
                handleConnect(client, headerBytes, requestLine, clientIn);
            } else {
                handleHttpRequest(client, headerBytes, clientIn);
            }
        } catch (IOException ignored) {
            // Browser retries failed proxy sockets naturally.
        } catch (RuntimeException unexpected) {
            // Nada do request aqui: só a classe para diagnóstico.
            LOG.warn("Falha inesperada no handler da ponte: {}", unexpected.getClass().getSimpleName());
        } finally {
            openSockets.remove(client);
            connectionSlots.release();
        }
    }

    private void handleConnect(Socket client, byte[] ignoredOriginalHeaders, String requestLine, InputStream clientIn) throws IOException {
        // Parse estrito: nada de índice cego sobre o split.
        String[] tokens = requestLine.trim().split("\\s+");
        String motivo = connectRequestError(tokens);
        if (motivo != null) {
            // Sem eco da linha bruta: só o motivo curto.
            LOG.warn("CONNECT inválido: {}", motivo);
            byte[] denied = ("HTTP/1.1 400 Bad Request\r\nContent-Length: 0\r\n"
                    + "Connection: close\r\n\r\n").getBytes(StandardCharsets.ISO_8859_1);
            client.getOutputStream().write(denied);
            client.getOutputStream().flush();
            return;
        }
        String target = tokens[1];
        Socket upstreamSocket = connectUpstream();
        try (upstreamSocket) {
            OutputStream upstreamOut = upstreamSocket.getOutputStream();
            InputStream upstreamIn = new BufferedInputStream(upstreamSocket.getInputStream(), BUFFER_SIZE);
            String connectRequest = "CONNECT " + target + " HTTP/1.1\r\n"
                    + "Host: " + target + "\r\n"
                    + "Proxy-Authorization: " + proxyAuthorization + "\r\n"
                    + "Proxy-Connection: Keep-Alive\r\n"
                    + "\r\n";
            upstreamOut.write(connectRequest.getBytes(StandardCharsets.ISO_8859_1));
            upstreamOut.flush();

            byte[] responseHeaders = readHeaders(upstreamIn);
            client.getOutputStream().write(responseHeaders);
            client.getOutputStream().flush();
            if (!isSuccessResponse(responseHeaders)) {
                return;
            }

            pipeBothWays(client, upstreamSocket, clientIn, upstreamIn);
        } finally {
            openSockets.remove(upstreamSocket);
        }
    }

    // Retorna o motivo curto da rejeição, ou null se o CONNECT é válido.
    private static String connectRequestError(String[] tokens) {
        if (tokens.length != 3 || !tokens[0].equalsIgnoreCase("CONNECT")) {
            return "formato esperado CONNECT host:porta HTTP/1.x";
        }
        if (!isValidConnectTarget(tokens[1])) {
            return "alvo fora do formato host:porta";
        }
        if (!tokens[2].matches("HTTP/1\\.[01]")) {
            return "versão HTTP inválida";
        }
        return null;
    }

    // Aceita host:porta com [literal IPv6] validado ou hostname/IPv4 real; sem espaço/CR/LF/'/'/'@'.
    static boolean isValidConnectTarget(String target) {
        if (target == null || target.isEmpty()) {
            return false;
        }
        if (target.indexOf(' ') >= 0 || target.indexOf('\r') >= 0
                || target.indexOf('\n') >= 0 || target.indexOf('/') >= 0
                || target.indexOf('@') >= 0) {
            return false;
        }
        if (target.startsWith("[")) {
            int close = target.indexOf(']');
            if (close <= 1) {
                return false;
            }
            if (close + 1 >= target.length() || target.charAt(close + 1) != ':') {
                return false;
            }
            // Conteúdo do colchete precisa ser IPv6 real: [xyz] não chega ao upstream.
            if (!IpLiterals.isValidIpv6(target.substring(1, close))) {
                return false;
            }
            return isValidPort(target.substring(close + 2));
        }
        if (target.indexOf('[') >= 0 || target.indexOf(']') >= 0) {
            return false;
        }
        int colon = target.lastIndexOf(':');
        if (colon <= 0 || colon == target.length() - 1 || target.indexOf(':') != colon) {
            return false;
        }
        return isValidConnectHost(target.substring(0, colon)) && isValidPort(target.substring(colon + 1));
    }

    // Host puro do alvo: IPv4 dotted-quad real ou hostname RFC 1123, sem label vazio.
    private static boolean isValidConnectHost(String host) {
        if (host.isEmpty() || host.length() > 253) {
            return false;
        }
        if (isIpv4Shaped(host)) {
            return IpLiterals.isValidIpv4(host);
        }
        return CONNECT_HOST.matcher(host).matches();
    }

    private static boolean isIpv4Shaped(String host) {
        boolean hasDot = false;
        for (int i = 0; i < host.length(); i++) {
            char c = host.charAt(i);
            if (c == '.') {
                hasDot = true;
            } else if (c < '0' || c > '9') {
                return false;
            }
        }
        return hasDot;
    }

    private static boolean isValidPort(String port) {
        if (port == null || port.isEmpty() || port.length() > 5) {
            return false;
        }
        for (int i = 0; i < port.length(); i++) {
            if (!Character.isDigit(port.charAt(i))) {
                return false;
            }
        }
        try {
            int value = Integer.parseInt(port);
            return value >= 1 && value <= 65535;
        } catch (NumberFormatException invalid) {
            return false;
        }
    }

    private void handleHttpRequest(Socket client, byte[] headerBytes, InputStream clientIn) throws IOException {
        Socket upstreamSocket = connectUpstream();
        try (upstreamSocket) {
            // Uma requisição por conexão: só o primeiro cabeçalho recebe Proxy-Authorization, então
            // keep-alive faria as requisições seguintes chegarem ao upstream sem credencial (407).
            // Repasse enquadrado (um corpo de pedido, uma resposta) mesmo que cliente ou upstream
            // ignorem Connection: close; Upgrade (101) vira túnel bruto como antes.
            OutputStream upstreamOut = upstreamSocket.getOutputStream();
            InputStream upstreamIn = new BufferedInputStream(upstreamSocket.getInputStream(), BUFFER_SIZE);
            if (hasHeader(headerBytes, "upgrade")) {
                upstreamOut.write(injectProxyAuthorization(headerBytes));
                upstreamOut.flush();
                pipeBothWays(client, upstreamSocket, clientIn, upstreamIn);
                return;
            }
            upstreamOut.write(injectProxyAuthorization(forceConnectionClose(headerBytes, false)));
            upstreamOut.flush();
            // Corpo do cliente (POST) segue em paralelo antes de esperar a resposta.
            executor.execute(() -> {
                try {
                    copyMessageBody(clientIn, upstreamOut, headerBytes, false);
                } catch (IOException | RuntimeException invalid) {
                    closeQuietly(upstreamSocket);
                }
            });
            OutputStream clientOut = client.getOutputStream();
            boolean head = new String(headerBytes, 0, Math.min(5, headerBytes.length), StandardCharsets.ISO_8859_1)
                    .equalsIgnoreCase("HEAD ");
            while (true) {
                byte[] response = readHeaders(upstreamIn);
                if (response.length == 0) {
                    return;
                }
                int status = statusCode(response);
                if (status >= 100 && status < 200) {
                    clientOut.write(response);
                    continue;
                }
                clientOut.write(forceConnectionClose(response, true));
                if (!head && status != 204 && status != 304) {
                    copyMessageBody(upstreamIn, clientOut, response, true);
                }
                clientOut.flush();
                return;
            }
        } catch (RejectedExecutionException saturated) {
            closeQuietly(client);
        } finally {
            openSockets.remove(upstreamSocket);
        }
    }

    /** Copia exatamente um corpo HTTP/1.1 (chunked ou Content-Length); sem framing: resposta até EOF, pedido vazio. */
    static void copyMessageBody(InputStream in, OutputStream out, byte[] headerBytes, boolean response)
            throws IOException {
        String lower = new String(headerBytes, StandardCharsets.ISO_8859_1).toLowerCase(Locale.ROOT);
        if (Pattern.compile("\r\ntransfer-encoding:[^\r]*chunked").matcher(lower).find()) {
            copyChunked(in, out);
        } else {
            Matcher length = Pattern.compile("\r\ncontent-length:[ \t]*([0-9]{1,18})[ \t]*\r\n").matcher(lower);
            if (length.find()) {
                copyExactly(in, out, Long.parseLong(length.group(1)));
            } else if (lower.contains("\r\ncontent-length:")) {
                throw new IOException("Content-Length inválido");
            } else if (response) {
                in.transferTo(out);
            }
        }
        out.flush();
    }

    private static void copyChunked(InputStream in, OutputStream out) throws IOException {
        while (true) {
            String line = readLine(in);
            out.write((line + "\r\n").getBytes(StandardCharsets.ISO_8859_1));
            int semicolon = line.indexOf(';');
            String hex = (semicolon >= 0 ? line.substring(0, semicolon) : line).trim();
            if (hex.isEmpty() || hex.length() > 15) {
                throw new IOException("Chunk inválido");
            }
            long size = Long.parseLong(hex, 16);
            if (size == 0) {
                String trailer;
                do {
                    trailer = readLine(in);
                    out.write((trailer + "\r\n").getBytes(StandardCharsets.ISO_8859_1));
                } while (!trailer.isEmpty());
                return;
            }
            copyExactly(in, out, size + 2);
        }
    }

    private static String readLine(InputStream in) throws IOException {
        StringBuilder line = new StringBuilder();
        int c;
        while ((c = in.read()) != '\n') {
            if (c == -1 || line.length() > 8192) {
                throw new IOException("Linha HTTP incompleta");
            }
            if (c != '\r') {
                line.append((char) c);
            }
        }
        return line.toString();
    }

    private static void copyExactly(InputStream in, OutputStream out, long length) throws IOException {
        byte[] buffer = new byte[BUFFER_SIZE];
        long remaining = length;
        while (remaining > 0) {
            int read = in.read(buffer, 0, (int) Math.min(buffer.length, remaining));
            if (read == -1) {
                throw new IOException("Corpo HTTP truncado");
            }
            out.write(buffer, 0, read);
            remaining -= read;
        }
    }

    private static boolean hasHeader(byte[] headerBytes, String name) {
        return new String(headerBytes, StandardCharsets.ISO_8859_1).toLowerCase(Locale.ROOT)
                .contains("\r\n" + name + ":");
    }

    private static int statusCode(byte[] headers) {
        String[] parts = new String(headers, StandardCharsets.ISO_8859_1).split("\r\n", 2)[0].split(" ", 3);
        try {
            return parts.length >= 2 ? Integer.parseInt(parts[1]) : -1;
        } catch (NumberFormatException invalid) {
            return -1;
        }
    }

    /** Remove Connection/Proxy-Connection/Keep-Alive e força {@code Connection: close}. */
    static byte[] forceConnectionClose(byte[] headerBytes, boolean response) {
        String headers = new String(headerBytes, StandardCharsets.ISO_8859_1);
        int end = headers.indexOf("\r\n\r\n");
        if (end < 0) {
            return headerBytes;
        }
        StringBuilder out = new StringBuilder();
        for (String line : headers.substring(0, end).split("\r\n", -1)) {
            int colon = line.indexOf(':');
            String name = colon > 0 ? line.substring(0, colon).trim().toLowerCase(Locale.ROOT) : "";
            if (out.length() > 0 && (name.equals("connection") || name.equals("proxy-connection")
                    || name.equals("keep-alive"))) {
                continue;
            }
            out.append(line).append("\r\n");
        }
        out.append("Connection: close\r\n");
        if (response) {
            out.append("Proxy-Connection: close\r\n");
        }
        return out.append("\r\n").toString().getBytes(StandardCharsets.ISO_8859_1);
    }

    private Socket connectUpstream() throws IOException {
        Socket transportSocket = new Socket();
        Socket connectedSocket = transportSocket;
        openSockets.add(transportSocket);
        try {
            transportSocket.connect(
                    new InetSocketAddress(upstream.getHost(), upstream.getPort()),
                    CONNECT_TIMEOUT_MS);
            transportSocket.setSoTimeout(SOCKET_TIMEOUT_MS);
            transportSocket.setOption(StandardSocketOptions.TCP_NODELAY, true);

            if (upstreamTransport == UpstreamTransport.TLS) {
                SSLSocketFactory factory = (SSLSocketFactory) SSLSocketFactory.getDefault();
                SSLSocket tlsSocket = (SSLSocket) factory.createSocket(
                        transportSocket,
                        upstream.getHost(),
                        upstream.getPort(),
                        true);
                connectedSocket = tlsSocket;
                openSockets.add(tlsSocket);
                tlsSocket.setSoTimeout(SOCKET_TIMEOUT_MS);
                tlsSocket.setSSLParameters(
                        enableHttpsEndpointIdentification(tlsSocket.getSSLParameters()));
                tlsSocket.startHandshake();
                openSockets.remove(transportSocket);
            }

            return connectedSocket;
        } catch (IOException failure) {
            openSockets.remove(connectedSocket);
            openSockets.remove(transportSocket);
            closeQuietly(connectedSocket);
            if (connectedSocket != transportSocket) {
                closeQuietly(transportSocket);
            }
            throw failure;
        }
    }

    private byte[] injectProxyAuthorization(byte[] headerBytes) {
        String headers = new String(headerBytes, StandardCharsets.ISO_8859_1);
        if (headers.toLowerCase(Locale.ROOT).contains("\r\nproxy-authorization:")) {
            return headerBytes;
        }
        int end = headers.indexOf("\r\n\r\n");
        if (end < 0) {
            return headerBytes;
        }
        String patched = headers.substring(0, end)
                + "\r\nProxy-Authorization: " + proxyAuthorization
                + headers.substring(end);
        return patched.getBytes(StandardCharsets.ISO_8859_1);
    }

    static byte[] readHeaders(InputStream input) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        int previous3 = -1;
        int previous2 = -1;
        int previous1 = -1;
        int current;
        boolean complete = false;
        while ((current = input.read()) != -1) {
            if (out.size() >= MAX_HEADER_BYTES) {
                throw new IOException("Headers de proxy excedem " + MAX_HEADER_BYTES + " bytes");
            }
            out.write(current);
            if (previous3 == '\r' && previous2 == '\n' && previous1 == '\r' && current == '\n') {
                complete = true;
                break;
            }
            previous3 = previous2;
            previous2 = previous1;
            previous1 = current;
        }
        if (out.size() > 0 && !complete) {
            throw new IOException("Headers de proxy incompletos");
        }
        return out.toByteArray();
    }

    private static boolean isSuccessResponse(byte[] headers) {
        String firstLine = new String(headers, StandardCharsets.ISO_8859_1).lines().findFirst().orElse("");
        return firstLine.contains(" 200 ") || firstLine.endsWith(" 200");
    }

    private void pipeBothWays(Socket client, Socket upstreamSocket,
            InputStream clientIn, InputStream upstreamIn) throws IOException {
        try {
            OutputStream upstreamOut = upstreamSocket.getOutputStream();
            executor.execute(() -> pipe(clientIn, upstreamOut));
            pipe(upstreamIn, client.getOutputStream());
        } catch (RejectedExecutionException saturated) {
            closeQuietly(client);
            closeQuietly(upstreamSocket);
        }
    }

    private static void pipe(InputStream in, OutputStream out) {
        byte[] buffer = new byte[BUFFER_SIZE];
        try {
            int read;
            while ((read = in.read(buffer)) != -1) {
                out.write(buffer, 0, read);
                out.flush();
            }
        } catch (IOException ignored) {
            // Normal when either browser or upstream closes a tunnel.
        } finally {
            closeQuietly(out);
        }
    }

    private static void closeQuietly(Socket socket) {
        try {
            socket.close();
        } catch (IOException ignored) {
        }
    }

    private static void closeQuietly(OutputStream output) {
        try {
            output.close();
        } catch (IOException ignored) {
        }
    }

    @Override
    public void close() {
        running = false;
        try {
            serverSocket.close();
        } catch (IOException ignored) {
        }
        for (Socket socket : openSockets) {
            closeQuietly(socket);
        }
        openSockets.clear();
        executor.shutdownNow();
        try {
            executor.awaitTermination(2, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        try {
            acceptor.join(2_000);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
