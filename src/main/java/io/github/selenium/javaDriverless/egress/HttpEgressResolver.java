package io.github.selenium.javaDriverless.egress;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import io.github.selenium.javaDriverless.types.EgressInfo;
import io.github.selenium.javaDriverless.types.EgressResolver;
import io.github.selenium.javaDriverless.types.ProxyConfig;
import io.github.selenium.javaDriverless.utils.IpLiterals;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Authenticator;
import java.net.InetSocketAddress;
import java.net.PasswordAuthentication;
import java.net.ProxySelector;
import java.net.Socket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;
import java.util.Locale;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;
import javax.net.ssl.SSLParameters;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.SSLSocketFactory;

/**
 * {@link EgressResolver} via provider HTTP de IP-geo. Usa só o {@code HttpClient} do JDK e Jackson
 * (nenhuma dependência nova). Por default consulta <b>ipwho.is</b> por HTTPS (sem chave) ATRAVÉS do proxy.
 * Templates do ip-api.com também continuam aceitos,
 * de modo que o provider enxergue o IP de SAÍDA real (o mesmo que o site alvo vê) — não o IP de
 * entrada do proxy.
 *
 * <p>Para proxies SOCKS (que o {@code HttpClient} não tuneliza) ou quando {@code throughProxy} é
 * desligado, consulta diretamente o geo do <i>host</i> do proxy (aproximação de entrada).
 *
 * <p>Falha de forma segura: timeout, status != 2xx ou resposta inválida → retorna {@code null}, e a
 * engine mantém os defaults coerentes por locale. Não é um circuit breaker completo; para alto
 * volume, envolva numa camada com cache/breaker no consumidor.
 */
public final class HttpEgressResolver implements EgressResolver {

    private static final ScheduledExecutorService TIMEOUTS = Executors.newSingleThreadScheduledExecutor(task -> {
        Thread thread = new Thread(task, "egress-resolver-timeouts");
        thread.setDaemon(true);
        return thread;
    });

    /** Endpoint padrão HTTPS do ipwho.is; %s = IP a consultar; vazio = IP de saída via proxy. */
    static final String DEFAULT_ENDPOINT = "https://ipwho.is/%s";

    /** RFC 1123: labels de 1-63, letras/dígitos com hífen interno, sem label vazio; ponto final opcional. */
    private static final Pattern DIRECT_HOST = Pattern.compile(
            "[A-Za-z0-9]([A-Za-z0-9-]{0,61}[A-Za-z0-9])?(\\.[A-Za-z0-9]([A-Za-z0-9-]{0,61}[A-Za-z0-9])?)*\\.?");

    private final String endpointTemplate;
    private final Duration timeout;
    private final boolean throughProxy;
    boolean allowInsecureHttpProxyAuth = allowInsecureHttpProxyAuthFromEnvironment();
    private final ObjectMapper mapper = new ObjectMapper();
    SSLSocketFactory tlsFactory = (SSLSocketFactory) SSLSocketFactory.getDefault();

    public HttpEgressResolver() {
        this(DEFAULT_ENDPOINT, Duration.ofSeconds(5), true);
    }

    /**
     * @param endpointTemplate URL com um {@code %s} para o IP (vazio = IP de saída via proxy)
     * @param timeout          timeout de conexão e de resposta
     * @param throughProxy     true: consulta via proxy (IP de saída real, HTTP/HTTPS); false: consulta
     *                         o host do proxy diretamente (entrada)
     */
    public HttpEgressResolver(String endpointTemplate, Duration timeout, boolean throughProxy) {
        this.endpointTemplate = endpointTemplate;
        this.timeout = timeout;
        this.throughProxy = throughProxy;
    }

    HttpEgressResolver(String endpointTemplate, Duration timeout, boolean throughProxy,
            SSLSocketFactory tlsFactory) {
        this(endpointTemplate, timeout, throughProxy);
        this.tlsFactory = tlsFactory;
    }

    @Override
    public EgressInfo resolve(ProxyConfig proxy) throws Exception {
        if (proxy == null || proxy.getHost() == null || proxy.getHost().isBlank()) {
            return null;
        }
        boolean viaProxy = throughProxy && tunnelable(proxy.getType());
        if (!viaProxy && !isValidDirectHost(proxy.getHost())) {
            return null;
        }
        String ipPath = viaProxy ? "" : proxy.getHost();
        final URI uri;
        try {
            uri = URI.create(String.format(endpointTemplate, ipPath));
        } catch (IllegalArgumentException malformed) {
            // IllegalFormatException estende IllegalArgumentException: template ou host
            // inválido falha de forma segura em vez de lançar.
            return null;
        }

        if (viaProxy && proxy.hasAuthentication()) {
            if (proxy.getType() == ProxyConfig.ProxyType.HTTP && !allowInsecureHttpProxyAuth) {
                throw new IllegalStateException("Proxy HTTP autenticado envia credenciais sem TLS; habilite explicitamente "
                        + "JAVA_DRIVERLESS_ALLOW_INSECURE_HTTP_PROXY_AUTH=true");
            }
            if (proxy.getType() == ProxyConfig.ProxyType.HTTPS
                    && !"https".equalsIgnoreCase(uri.getScheme())) {
                throw new IllegalStateException("Proxy HTTPS autenticado exige endpoint https; "
                        + "HttpClient não faz TLS até o proxy");
            }
        }

        if (viaProxy && proxy.hasAuthentication() && "https".equalsIgnoreCase(uri.getScheme())) {
            String body = fetchViaAuthenticatedTunnel(uri, proxy);
            return body == null ? null : parseResponse(body, mapper);
        }

        HttpClient client = buildClient(viaProxy ? proxy : null);

        HttpRequest request = HttpRequest.newBuilder(uri)
                .timeout(timeout)
                .header("User-Agent", "Mozilla/5.0")
                .GET()
                .build();
        HttpResponse<InputStream> response = client.send(request, HttpResponse.BodyHandlers.ofInputStream());
        try (InputStream body = response.body()) {
            if (response.statusCode() / 100 != 2) {
                return null;
            }
            // O timeout do HttpClient só cobre os cabeçalhos: o corpo ganha prazo e teto próprios.
            var expired = new java.util.concurrent.atomic.AtomicBoolean();
            var closer = TIMEOUTS.schedule(() -> {
                expired.set(true);
                try { body.close(); } catch (IOException ignored) { }
            }, timeout.toMillis(), TimeUnit.MILLISECONDS);
            try {
                byte[] bytes = body.readNBytes(256 * 1024 + 1);
                ensureBodyLimit(0, bytes.length);
                return expired.get() ? null : parseResponse(new String(bytes, StandardCharsets.UTF_8), mapper);
            } catch (IOException e) {
                if (expired.get()) return null;
                throw e;
            } finally {
                closer.cancel(false);
            }
        }
    }

    String fetchViaAuthenticatedTunnel(URI uri, ProxyConfig proxy) throws IOException {
        int timeoutMs = (int) Math.min(Integer.MAX_VALUE, timeout.toMillis());
        String host = uri.getHost();
        int port = uri.getPort() < 0 ? 443 : uri.getPort();
        String authorityHost = host.indexOf(':') >= 0 ? "[" + host + "]" : host;
        String authority = authorityHost + ":" + port;
        Socket raw = new Socket();
        SSLSocket proxySocket = null;
        var timeoutTask = TIMEOUTS.schedule(() -> {
            try {
                raw.close();
            } catch (IOException ignored) {
                // Closing the socket enforces the total deadline across all tunnel phases.
            }
        }, timeout.toMillis(), TimeUnit.MILLISECONDS);
        try {
            raw.connect(new InetSocketAddress(proxy.getHost(), proxy.getPort()), timeoutMs);
            raw.setSoTimeout(timeoutMs);
            Socket connectSocket = raw;
            if (proxy.getType() == ProxyConfig.ProxyType.HTTPS) {
                proxySocket = (SSLSocket) tlsFactory.createSocket(raw, proxy.getHost(), proxy.getPort(), true);
                SSLParameters proxyParameters = proxySocket.getSSLParameters();
                proxyParameters.setEndpointIdentificationAlgorithm("HTTPS");
                proxySocket.setSSLParameters(proxyParameters);
                proxySocket.setSoTimeout(timeoutMs);
                proxySocket.startHandshake();
                connectSocket = proxySocket;
            }
            String user = proxy.getUsername() == null ? "" : proxy.getUsername();
            String password = proxy.getPassword() == null ? "" : proxy.getPassword();
            String credentials = Base64.getEncoder().encodeToString(
                    (user + ":" + password).getBytes(StandardCharsets.UTF_8));
            OutputStream out = connectSocket.getOutputStream();
            out.write(("CONNECT " + authority + " HTTP/1.1\r\n"
                    + "Host: " + authority + "\r\n"
                    + "Proxy-Authorization: Basic " + credentials + "\r\n"
                    + "Proxy-Connection: keep-alive\r\n\r\n").getBytes(StandardCharsets.US_ASCII));
            out.flush();
            String connectHeaders = readHeaderBlock(connectSocket.getInputStream());
            String[] status = connectHeaders.substring(0, connectHeaders.indexOf("\r\n")).split(" ", 3);
            if (status.length < 2 || !status[0].matches("HTTP/1\\.[01]")
                    || !status[1].matches("[0-9]{3}")) {
                throw new IOException("Invalid CONNECT response status");
            }
            if (Integer.parseInt(status[1]) / 100 != 2) {
                return null;
            }

            SSLSocket tls = (SSLSocket) tlsFactory.createSocket(connectSocket, host, port, true);
            SSLParameters parameters = tls.getSSLParameters();
            parameters.setEndpointIdentificationAlgorithm("HTTPS");
            tls.setSSLParameters(parameters);
            tls.setSoTimeout(timeoutMs);
            tls.startHandshake();
            try (tls) {
                String path = uri.getRawPath();
                if (path == null || path.isEmpty()) {
                    path = "/";
                }
                if (uri.getRawQuery() != null) {
                    path += "?" + uri.getRawQuery();
                }
                String requestHost = authorityHost + (port == 443 ? "" : ":" + port);
                OutputStream tlsOut = tls.getOutputStream();
                tlsOut.write(("GET " + path + " HTTP/1.1\r\n"
                        + "Host: " + requestHost + "\r\n"
                        + "User-Agent: Mozilla/5.0\r\n"
                        + "Accept: application/json\r\n"
                        + "Accept-Encoding: identity\r\n"
                        + "Connection: close\r\n\r\n").getBytes(StandardCharsets.US_ASCII));
                tlsOut.flush();
                return readHttpBody(tls.getInputStream());
            }
        } finally {
            timeoutTask.cancel(false);
            if (proxySocket != null) {
                try {
                    proxySocket.close();
                } catch (IOException ignored) {
                    // Preserve the original connection error, if any.
                }
            }
            try {
                raw.close();
            } catch (IOException ignored) {
                // Preserve the original connection error, if any.
            }
        }
    }

    static String readHttpBody(InputStream in) throws IOException {
        String headers = readHeaderBlock(in);
        String[] lines = headers.split("\\r\\n");
        String[] status = lines[0].split(" ", 3);
        if (status.length < 2 || !status[1].matches("[0-9]{3}")) {
            throw new IOException("Invalid HTTP response status");
        }
        if (Integer.parseInt(status[1]) / 100 != 2) {
            return null;
        }
        String transferEncoding = null;
        String contentLength = null;
        for (int i = 1; i < lines.length; i++) {
            int colon = lines[i].indexOf(':');
            if (colon <= 0) {
                continue;
            }
            String name = lines[i].substring(0, colon).trim().toLowerCase(Locale.ROOT);
            String value = lines[i].substring(colon + 1).trim();
            if ("transfer-encoding".equals(name)) {
                transferEncoding = value.toLowerCase(Locale.ROOT);
            } else if ("content-length".equals(name)) {
                contentLength = value;
            }
        }
        ByteArrayOutputStream body = new ByteArrayOutputStream();
        if (transferEncoding != null && transferEncoding.contains("chunked")) {
            while (true) {
                String chunkLine = readLine(in, 16 * 1024);
                int extension = chunkLine.indexOf(';');
                String sizeText = (extension < 0 ? chunkLine : chunkLine.substring(0, extension)).trim();
                final int size;
                try {
                    size = Integer.parseInt(sizeText, 16);
                } catch (NumberFormatException e) {
                    throw new IOException("Invalid HTTP chunk size");
                }
                if (size == 0) {
                    while (!readLine(in, 16 * 1024).isEmpty()) {
                        // Ignore trailers.
                    }
                    break;
                }
                ensureBodyLimit(body.size(), size);
                copyExactly(in, body, size);
                expectCrlf(in);
            }
        } else if (contentLength != null) {
            final int length;
            try {
                length = Integer.parseInt(contentLength);
            } catch (NumberFormatException e) {
                throw new IOException("Invalid Content-Length");
            }
            if (length < 0) {
                throw new IOException("Invalid Content-Length");
            }
            ensureBodyLimit(0, length);
            copyExactly(in, body, length);
        } else {
            byte[] buffer = new byte[4096];
            int count;
            while ((count = in.read(buffer)) != -1) {
                ensureBodyLimit(body.size(), count);
                body.write(buffer, 0, count);
            }
        }
        return body.toString(StandardCharsets.UTF_8);
    }

    private static String readHeaderBlock(InputStream in) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        int matched = 0;
        while (bytes.size() < 16 * 1024) {
            int value = in.read();
            if (value < 0) {
                throw new IOException("Unexpected end of HTTP headers");
            }
            bytes.write(value);
            if ((matched == 0 && value == '\r') || (matched == 1 && value == '\n')
                    || (matched == 2 && value == '\r') || (matched == 3 && value == '\n')) {
                matched++;
                if (matched == 4) {
                    return bytes.toString(StandardCharsets.US_ASCII);
                }
            } else {
                matched = value == '\r' ? 1 : 0;
            }
        }
        throw new IOException("HTTP headers exceed 16 KiB");
    }

    private static String readLine(InputStream in, int limit) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        int previous = -1;
        while (bytes.size() <= limit) {
            int value = in.read();
            if (value < 0) {
                throw new IOException("Unexpected end of HTTP response");
            }
            if (previous == '\r' && value == '\n') {
                byte[] line = bytes.toByteArray();
                return new String(line, 0, line.length - 1, StandardCharsets.US_ASCII);
            }
            bytes.write(value);
            previous = value;
        }
        throw new IOException("HTTP line exceeds limit");
    }

    private static void copyExactly(InputStream in, OutputStream out, int length) throws IOException {
        byte[] buffer = new byte[4096];
        int remaining = length;
        while (remaining > 0) {
            int count = in.read(buffer, 0, Math.min(buffer.length, remaining));
            if (count < 0) {
                throw new IOException("Unexpected end of HTTP body");
            }
            out.write(buffer, 0, count);
            remaining -= count;
        }
    }

    private static void expectCrlf(InputStream in) throws IOException {
        if (in.read() != '\r' || in.read() != '\n') {
            throw new IOException("Invalid HTTP chunk terminator");
        }
    }

    private static void ensureBodyLimit(int current, int addition) throws IOException {
        if (addition < 0 || current > 256 * 1024 - addition) {
            throw new IOException("HTTP body exceeds 256 KiB");
        }
    }

    private static boolean tunnelable(ProxyConfig.ProxyType type) {
        return type == ProxyConfig.ProxyType.HTTP || type == ProxyConfig.ProxyType.HTTPS;
    }

    private static boolean isValidDirectHost(String host) {
        // Host de entrada vira path no template; barra, espaço, '%' etc. dariam
        // ao proxy controle sobre a URI do provider, então só passa host/IP puro.
        if (host.startsWith("[") && host.endsWith("]")) {
            String inner = host.substring(1, host.length() - 1);
            return inner.contains(":") && IpLiterals.isValidIpv6(inner);
        }
        if (host.contains(":")) {
            return IpLiterals.isValidIpv6(host);
        }
        // Numérico pontuado (ex.: "999.1.1.1") não é hostname resolvível: só passa
        // como literal IPv4 válido, recusado antes de montar URI e contactar o provider.
        if (isDottedDigits(host)) {
            return IpLiterals.isValidIpv4(host);
        }
        return host.length() <= 253 && DIRECT_HOST.matcher(host).matches();
    }

    private static boolean isDottedDigits(String host) {
        if (host.indexOf('.') < 0) {
            return false;
        }
        for (int i = 0; i < host.length(); i++) {
            char c = host.charAt(i);
            if ((c < '0' || c > '9') && c != '.') {
                return false;
            }
        }
        return true;
    }

    private static boolean allowInsecureHttpProxyAuthFromEnvironment() {
        String configured = System.getenv("JAVA_DRIVERLESS_ALLOW_INSECURE_HTTP_PROXY_AUTH");
        return configured != null && "true".equalsIgnoreCase(configured.trim());
    }

    private HttpClient buildClient(ProxyConfig proxy) {
        HttpClient.Builder builder = HttpClient.newBuilder().connectTimeout(timeout);
        if (proxy != null) {
            builder.proxy(ProxySelector.of(new InetSocketAddress(proxy.getHost(), proxy.getPort())));
            if (proxy.hasAuthentication()) {
                String user = proxy.getUsername();
                char[] pass = proxy.getPassword() == null ? new char[0] : proxy.getPassword().toCharArray();
                builder.authenticator(new Authenticator() {
                    @Override
                    protected PasswordAuthentication getPasswordAuthentication() {
                        // Só envia credenciais para o PROXY (407), nunca para o servidor alvo.
                        if (getRequestorType() == RequestorType.PROXY) {
                            return new PasswordAuthentication(user, pass);
                        }
                        return null;
                    }
                });
            }
        }
        return builder.build();
    }

    /** Converte a resposta JSON do ip-api.com em {@link EgressInfo}. {@code null} se não-sucesso. */
    static EgressInfo parseIpApi(String json, ObjectMapper mapper) throws Exception {
        JsonNode n = mapper.readTree(json);
        if (!"success".equals(n.path("status").asText(""))) {
            return null;
        }
        EgressInfo info = new EgressInfo();
        info.countryCode = n.path("countryCode").asText("");
        info.timezone = n.path("timezone").asText("");
        if (n.hasNonNull("lat")) {
            info.latitude = n.get("lat").asDouble();
        }
        if (n.hasNonNull("lon")) {
            info.longitude = n.get("lon").asDouble();
        }
        info.publicIp = n.path("query").asText("");
        info.mobile = n.path("mobile").asBoolean(false);
        // IP-geo tem precisão de cidade; accuracy grosseira em metros (evita coordenada "GPS exata").
        info.accuracy = 50_000.0;
        return info;
    }

    /** Converte a resposta JSON do ipwho.is em {@link EgressInfo}. {@code null} se não-sucesso. */
    static EgressInfo parseIpWhoIs(String json, ObjectMapper mapper) throws Exception {
        JsonNode n = mapper.readTree(json);
        JsonNode success = n.path("success");
        if (!success.isBoolean() || !success.booleanValue()) {
            return null;
        }
        EgressInfo info = new EgressInfo();
        info.countryCode = n.path("country_code").asText("");
        info.timezone = n.path("timezone").path("id").asText("");
        if (n.hasNonNull("latitude")) {
            info.latitude = n.get("latitude").asDouble();
        }
        if (n.hasNonNull("longitude")) {
            info.longitude = n.get("longitude").asDouble();
        }
        info.publicIp = n.path("ip").asText("");
        info.mobile = false;
        info.accuracy = 50_000.0;
        return info;
    }

    /** Seleciona o formato antigo do ip-api ou o formato atual do ipwho.is. */
    static EgressInfo parseResponse(String json, ObjectMapper mapper) throws Exception {
        JsonNode n = mapper.readTree(json);
        return n.has("status") ? parseIpApi(json, mapper) : parseIpWhoIs(json, mapper);
    }
}
