package io.github.selenium.javaDriverless.egress;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.InetSocketAddress;
import java.nio.file.Path;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.KeyStore;
import java.time.Duration;
import java.util.Base64;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLException;
import javax.net.ssl.SSLServerSocket;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.TrustManagerFactory;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.Test;

import io.github.selenium.javaDriverless.types.EgressInfo;
import io.github.selenium.javaDriverless.types.ProxyConfig;
import io.github.selenium.javaDriverless.types.ProxyConfig.ProxyType;

/**
 * Resolve de egress ATRAVÉS de um proxy HTTP fake local: primeiro 407 (desafia o
 * Authenticator interno), depois 200 com JSON de IP-geo. Credenciais são placeholders.
 */
class HttpEgressResolverProxyAuthTest {

    @TempDir
    Path tempDir;

    private HttpServer server;
    private final AtomicInteger challenges = new AtomicInteger();

    private void startChallengeServer() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", new HttpHandler() {
            @Override
            public void handle(HttpExchange exchange) {
                try {
                    byte[] body;
                    if (challenges.incrementAndGet() == 1) {
                        // primeiro contato: desafio de auth de proxy (407)
                        exchange.getResponseHeaders().add("Proxy-Authenticate", "Basic realm=\"egress\"");
                        body = "denied".getBytes(StandardCharsets.UTF_8);
                        exchange.sendResponseHeaders(407, body.length);
                    } else {
                        String json = "{\"status\":\"success\",\"countryCode\":\"BR\","
                            + "\"timezone\":\"America/Sao_Paulo\",\"lat\":-23.5,\"lon\":-46.6,"
                            + "\"query\":\"203.0.113.9\",\"mobile\":true}";
                        body = json.getBytes(StandardCharsets.UTF_8);
                        exchange.sendResponseHeaders(200, body.length);
                    }
                    try (OutputStream out = exchange.getResponseBody()) {
                        out.write(body);
                    }
                } catch (Exception ignored) {
                    // conexção abortada pelo cliente: nada a fazer
                }
            }
        });
        server.start();
    }

    @AfterEach
    void tearDown() {
        if (server != null) {
            server.stop(0);
        }
    }

    @Test
    void resolveThroughAuthenticatedProxyAnswersChallengeAndParsesGeo() throws Exception {
        startChallengeServer();
        int port = server.getAddress().getPort();
        ProxyConfig proxy =
            new ProxyConfig("127.0.0.1", port, ProxyType.HTTP, "proxyuser", "proxypass");

        HttpEgressResolver resolver = new HttpEgressResolver(
            "http://ip-api.test/json/%s?fields=status,countryCode,timezone,lat,lon,query,mobile",
            Duration.ofSeconds(5), true);
        resolver.allowInsecureHttpProxyAuth = true;

        EgressInfo info = resolver.resolve(proxy);

        assertNotNull(info, "deveria completar após responder ao 407 do proxy fake");
        assertEquals(2, challenges.get(), "servidor deve ter visto 407 + requisição autenticada");
        assertEquals("BR", info.countryCode);
        assertEquals("America/Sao_Paulo", info.timezone);
        assertEquals("203.0.113.9", info.publicIp);
        assertEquals(true, info.mobile);
        assertEquals(-23.5, info.latitude, 1e-9);
        assertEquals(-46.6, info.longitude, 1e-9);
    }

    @Test
    void readHttpBodySupportsContentLength() throws Exception {
        String response = "HTTP/1.1 200 OK\r\ncontent-length: 5\r\n\r\nhello";
        assertEquals("hello", HttpEgressResolver.readHttpBody(
                new ByteArrayInputStream(response.getBytes(StandardCharsets.US_ASCII))));
    }

    @Test
    void readHttpBodyDecodesChunkedAndIgnoresTrailers() throws Exception {
        String response = "HTTP/1.1 200 OK\r\nTransfer-Encoding: chunked\r\n\r\n"
                + "2;ext=yes\r\nhi\r\n6\r\n there\r\n0\r\nX-Trailer: ignored\r\n\r\n";
        assertEquals("hi there", HttpEgressResolver.readHttpBody(
                new ByteArrayInputStream(response.getBytes(StandardCharsets.US_ASCII))));
    }

    @Test
    void readHttpBodyReadsUntilEofAndReturnsNullForErrorStatus() throws Exception {
        assertEquals("until eof", HttpEgressResolver.readHttpBody(new ByteArrayInputStream(
                "HTTP/1.1 200 OK\r\nX-Test: yes\r\n\r\nuntil eof".getBytes(StandardCharsets.US_ASCII))));
        assertNull(HttpEgressResolver.readHttpBody(new ByteArrayInputStream(
                "HTTP/1.1 404 Missing\r\n\r\nignored".getBytes(StandardCharsets.US_ASCII))));
    }

    @Test
    void readHttpBodyRejectsBodyOver256KiB() {
        byte[] body = new byte[256 * 1024 + 1];
        String header = "HTTP/1.1 200 OK\r\nContent-Length: " + body.length + "\r\n\r\n";
        byte[] prefix = header.getBytes(StandardCharsets.US_ASCII);
        byte[] response = new byte[prefix.length + body.length];
        System.arraycopy(prefix, 0, response, 0, prefix.length);
        System.arraycopy(body, 0, response, prefix.length, body.length);
        assertThrows(java.io.IOException.class, () -> HttpEgressResolver.readHttpBody(
                new ByteArrayInputStream(response)));
    }

    @Test
    void authenticatedTunnelSendsBasicCredentialsAndReturnsNullOn407() throws Exception {
        try (ServerSocket proxyServer = new ServerSocket()) {
            proxyServer.bind(new InetSocketAddress("127.0.0.1", 0));
            AtomicReference<String> receivedHeaders = new AtomicReference<>();
            Thread proxy = daemon(() -> {
                try (Socket socket = proxyServer.accept()) {
                    receivedHeaders.set(readHeaders(socket.getInputStream()));
                    socket.getOutputStream().write("HTTP/1.1 407 Proxy Authentication Required\r\n\r\n"
                            .getBytes(StandardCharsets.US_ASCII));
                } catch (Exception e) {
                    throw new RuntimeException(e);
                }
            });
            proxy.start();
            ProxyConfig config = new ProxyConfig("127.0.0.1", proxyServer.getLocalPort(), ProxyType.HTTP,
                    "proxyuser", "proxypass");
            HttpEgressResolver resolver = new HttpEgressResolver("https://ipwho.is/%s",
                    Duration.ofSeconds(3), true);
            resolver.allowInsecureHttpProxyAuth = true;

            assertNull(resolver.resolve(config));
            proxy.join(3000);
            assertTrue(!proxy.isAlive(), "fake proxy should finish");
            assertTrue(receivedHeaders.get().startsWith("CONNECT ipwho.is:443 HTTP/1.1\r\n"));
            assertTrue(receivedHeaders.get().contains("Proxy-Authorization: Basic "
                    + Base64.getEncoder().encodeToString("proxyuser:proxypass".getBytes(StandardCharsets.UTF_8))));
        }
    }

    @Test
    void authenticatedTunnelPerformsTlsAndParsesChunkedIpWhoIsResponse() throws Exception {
        TunnelResult result = runTlsTunnel("ipwho.is", true);
        assertEquals("PT", result.info.countryCode);
        assertEquals("Europe/Lisbon", result.info.timezone);
        assertEquals("198.51.100.7", result.info.publicIp);
        assertTrue(result.request.startsWith("GET / HTTP/1.1\r\n"));
        assertTrue(result.request.toLowerCase().contains("\r\nhost: ipwho.is\r\n"));
    }

    @Test
    void authenticatedTunnelRejectsCertificateForWrongHostname() throws Exception {
        assertThrows(SSLException.class, () -> runTlsTunnel("outro.example", false));
    }

    @Test
    void authenticatedTunnelEnforcesAbsoluteTimeoutDuringSlowContinuousResponse() {
        assertThrows(java.io.IOException.class,
                () -> runTlsTunnel("ipwho.is", false, Duration.ofMillis(500), true));
    }

    private TunnelResult runTlsTunnel(String certificateHost, boolean expectSuccess) throws Exception {
        return runTlsTunnel(certificateHost, expectSuccess, Duration.ofSeconds(3), false);
    }

    private TunnelResult runTlsTunnel(String certificateHost, boolean expectSuccess, Duration timeout,
            boolean slowResponse) throws Exception {
        Path keyStoreFile = tempDir.resolve("test-keystore.p12");
        String keytool = locateKeytool();
        Assumptions.assumeTrue(keytool != null, "keytool unavailable");
        Process process = new ProcessBuilder(keytool, "-genkeypair", "-alias", "test", "-keyalg", "RSA",
                "-keysize", "2048", "-storetype", "PKCS12", "-keystore", keyStoreFile.toString(),
                "-storepass", "changeit", "-keypass", "changeit", "-dname", "CN=" + certificateHost,
                "-ext", "SAN=dns:" + certificateHost, "-validity", "1", "-noprompt")
                .redirectErrorStream(true).redirectOutput(tempDir.resolve("keytool.log").toFile()).start();
        boolean keytoolFinished = process.waitFor(8, TimeUnit.SECONDS);
        if (!keytoolFinished) {
            process.destroyForcibly();
        }
        Assumptions.assumeTrue(keytoolFinished, "keytool timed out");
        assertEquals(0, process.exitValue(), "keytool should create test certificate");

        KeyStore keyStore = KeyStore.getInstance("PKCS12");
        try (InputStream in = Files.newInputStream(keyStoreFile)) {
            keyStore.load(in, "changeit".toCharArray());
        }
        KeyManagerFactory kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
        kmf.init(keyStore, "changeit".toCharArray());
        TrustManagerFactory tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
        tmf.init(keyStore);
        SSLContext serverContext = SSLContext.getInstance("TLS");
        serverContext.init(kmf.getKeyManagers(), null, null);
        SSLContext clientContext = SSLContext.getInstance("TLS");
        clientContext.init(null, tmf.getTrustManagers(), null);

        try (SSLServerSocket tlsServer = (SSLServerSocket) serverContext.getServerSocketFactory()
                .createServerSocket(0, 5, java.net.InetAddress.getLoopbackAddress());
             ServerSocket proxyServer = new ServerSocket()) {
            tlsServer.setSoTimeout(4000);
            proxyServer.bind(new InetSocketAddress("127.0.0.1", 0));
            AtomicReference<String> request = new AtomicReference<>();
            Thread tlsPeer = daemon(() -> {
                try (SSLSocket socket = (SSLSocket) tlsServer.accept()) {
                    request.set(readHeaders(socket.getInputStream()));
                    byte[] json = ("{\"ip\":\"198.51.100.7\",\"success\":true,"
                            + "\"country_code\":\"PT\",\"latitude\":38.7,\"longitude\":-9.1,"
                            + "\"timezone\":{\"id\":\"Europe/Lisbon\"}}").getBytes(StandardCharsets.UTF_8);
                    OutputStream out = socket.getOutputStream();
                    out.write(("HTTP/1.1 200 OK\r\nContent-Type: application/json\r\n"
                            + "Transfer-Encoding: chunked\r\n\r\n").getBytes(StandardCharsets.US_ASCII));
                    if (slowResponse) {
                        for (byte value : json) {
                            out.write(("1\r\n" + (char) value + "\r\n").getBytes(StandardCharsets.ISO_8859_1));
                            out.flush();
                            Thread.sleep(100);
                        }
                    } else {
                        out.write((Integer.toHexString(json.length) + "\r\n").getBytes(StandardCharsets.US_ASCII));
                        out.write(json);
                        out.write("\r\n".getBytes(StandardCharsets.US_ASCII));
                    }
                    out.write("0\r\n\r\n".getBytes(StandardCharsets.US_ASCII));
                    out.flush();
                } catch (Exception e) {
                    if (expectSuccess) {
                        throw new RuntimeException(e);
                    }
                }
            });
            Thread tunnel = daemon(() -> {
                try (Socket client = proxyServer.accept()) {
                    readHeaders(client.getInputStream());
                    client.getOutputStream().write("HTTP/1.1 200 Connection established\r\n\r\n"
                            .getBytes(StandardCharsets.US_ASCII));
                    client.getOutputStream().flush();
                    try (Socket target = new Socket("127.0.0.1", tlsServer.getLocalPort())) {
                        Thread upstream = daemon(() -> copy(client, target));
                        upstream.start();
                        copy(target, client);
                        upstream.join(3000);
                    }
                } catch (Exception e) {
                    if (expectSuccess) {
                        throw new RuntimeException(e);
                    }
                }
            });
            tlsPeer.start();
            tunnel.start();
            ProxyConfig config = new ProxyConfig("127.0.0.1", proxyServer.getLocalPort(), ProxyType.HTTP,
                    "proxyuser", "proxypass");
            HttpEgressResolver resolver = new HttpEgressResolver("https://ipwho.is/%s",
                    timeout, true, clientContext.getSocketFactory());
            resolver.allowInsecureHttpProxyAuth = true;
            EgressInfo info = resolver.resolve(config);
            tlsPeer.join(4000);
            tunnel.join(4000);
            if (!expectSuccess) {
                return new TunnelResult(info, request.get());
            }
            assertTrue(!tlsPeer.isAlive() && !tunnel.isAlive(), "fake TLS peers should finish");
            return new TunnelResult(info, request.get());
        }
    }

    @Test
    void httpAuthenticatedProxyIsRejectedBeforeConnectingWhenInsecureGateIsOff() throws Exception {
        try (ServerSocket proxyServer = new ServerSocket()) {
            proxyServer.bind(new InetSocketAddress("127.0.0.1", 0));
            proxyServer.setSoTimeout(300);
            ProxyConfig config = new ProxyConfig("127.0.0.1", proxyServer.getLocalPort(), ProxyType.HTTP,
                    "proxyuser", "proxypass");
            HttpEgressResolver resolver = new HttpEgressResolver("https://ipwho.is/%s",
                    Duration.ofSeconds(1), true);

            assertThrows(IllegalStateException.class, () -> resolver.resolve(config));
            assertThrows(java.net.SocketTimeoutException.class, proxyServer::accept,
                    "fake proxy must not receive a connection");
        }
    }

    @Test
    void httpsAuthenticatedProxyStartsTlsBeforeSendingConnectOrCredentials() throws Exception {
        try (ServerSocket proxyServer = new ServerSocket()) {
            proxyServer.bind(new InetSocketAddress("127.0.0.1", 0));
            proxyServer.setSoTimeout(2000);
            AtomicReference<byte[]> received = new AtomicReference<>();
            Thread proxy = daemon(() -> {
                try (Socket socket = proxyServer.accept()) {
                    socket.setSoTimeout(2000);
                    ByteArrayOutputStream bytes = new ByteArrayOutputStream();
                    byte[] buffer = new byte[1024];
                    try {
                        int count;
                        while ((count = socket.getInputStream().read(buffer)) != -1) {
                            bytes.write(buffer, 0, count);
                        }
                    } catch (java.net.SocketTimeoutException ignored) {
                        // The client will close after its TLS handshake times out.
                    }
                    received.set(bytes.toByteArray());
                } catch (Exception e) {
                    throw new RuntimeException(e);
                }
            });
            proxy.start();
            ProxyConfig config = new ProxyConfig("127.0.0.1", proxyServer.getLocalPort(), ProxyType.HTTPS,
                    "proxyuser", "proxypass");
            HttpEgressResolver resolver = new HttpEgressResolver("https://ipwho.is/%s",
                    Duration.ofMillis(600), true);

            assertThrows(Exception.class, () -> resolver.resolve(config));
            proxy.join(3000);
            assertTrue(!proxy.isAlive(), "fake proxy should finish");
            byte[] bytes = received.get();
            assertNotNull(bytes);
            assertTrue(bytes.length > 0);
            assertEquals(0x16, Byte.toUnsignedInt(bytes[0]), "first proxy byte should be a TLS handshake record");
            String plaintext = new String(bytes, StandardCharsets.ISO_8859_1);
            assertTrue(!plaintext.contains("Proxy-Authorization") && !plaintext.contains("CONNECT"),
                    "proxy must not receive plaintext CONNECT or credentials");
        }
    }

    @Test
    void httpsAuthenticatedProxyRejectsHttpEndpointBeforeConnecting() throws Exception {
        try (ServerSocket proxyServer = new ServerSocket()) {
            proxyServer.bind(new InetSocketAddress("127.0.0.1", 0));
            proxyServer.setSoTimeout(300);
            ProxyConfig config = new ProxyConfig("127.0.0.1", proxyServer.getLocalPort(), ProxyType.HTTPS,
                    "proxyuser", "proxypass");
            HttpEgressResolver resolver = new HttpEgressResolver("http://ipwho.is/%s",
                    Duration.ofSeconds(1), true);

            assertThrows(IllegalStateException.class, () -> resolver.resolve(config));
            assertThrows(java.net.SocketTimeoutException.class, proxyServer::accept,
                    "fake proxy must not receive a connection");
        }
    }

    private static String locateKeytool() {
        String javaHome = System.getenv("JAVA_HOME");
        Path candidate = javaHome == null ? null : Path.of(javaHome, "bin", isWindows() ? "keytool.exe" : "keytool");
        if (candidate != null && Files.isExecutable(candidate)) {
            return candidate.toString();
        }
        String command = ProcessHandle.current().info().command().orElse(null);
        if (command == null) {
            return null;
        }
        Path java = Path.of(command);
        Path sibling = java.getParent().resolve(isWindows() ? "keytool.exe" : "keytool");
        return Files.isExecutable(sibling) ? sibling.toString() : null;
    }

    private static boolean isWindows() {
        return System.getProperty("os.name", "").toLowerCase().contains("win");
    }

    private static Thread daemon(Runnable task) {
        Thread thread = new Thread(task);
        thread.setDaemon(true);
        return thread;
    }

    private static String readHeaders(InputStream in) throws Exception {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        int matched = 0;
        while (bytes.size() < 16 * 1024) {
            int value = in.read();
            if (value < 0) {
                break;
            }
            bytes.write(value);
            if ((matched == 0 && value == '\r') || (matched == 1 && value == '\n')
                    || (matched == 2 && value == '\r') || (matched == 3 && value == '\n')) {
                if (++matched == 4) {
                    return bytes.toString(StandardCharsets.US_ASCII);
                }
            } else {
                matched = value == '\r' ? 1 : 0;
            }
        }
        throw new java.io.IOException("test peer did not receive complete headers");
    }

    private static void copy(Socket from, Socket to) {
        try {
            from.getInputStream().transferTo(to.getOutputStream());
            to.shutdownOutput();
        } catch (Exception ignored) {
            // Peer teardown ends the opposite direction of the local tunnel.
        }
    }

    private static final class TunnelResult {
        final EgressInfo info;
        final String request;

        TunnelResult(EgressInfo info, String request) {
            this.info = info;
            this.request = request;
        }
    }

    @Test
    void resolveReturnsNullWhenProxyHostIsBlank() throws Exception {
        assertNotNull(new HttpEgressResolver(), "construtor default existe para o provider padrão");
        HttpEgressResolver resolver = new HttpEgressResolver(
            "http://ip-api.test/json/%s", Duration.ofSeconds(1), true);

        assertNull(resolver.resolve(null));
        assertNull(resolver.resolve(new ProxyConfig("", 8080, ProxyType.HTTP)));
    }

    @Test
    void non2xxAnswerYieldsNullInsteadOfBlowingUp() throws Exception {
        startChallengeServer();
        server.stop(0); // troca o handler por um que só devolve erro
        challenges.set(0);
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            byte[] body = "fora do ar".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(500, body.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(body);
            }
        });
        server.start();

        HttpEgressResolver resolver = new HttpEgressResolver(
            "http://127.0.0.1:" + server.getAddress().getPort() + "/json/%s",
            Duration.ofSeconds(3), false);

        assertNull(resolver.resolve(new ProxyConfig("127.0.0.1", 1, ProxyType.HTTP)));
    }

    @Test
    void socksProxyAndThroughProxyDisabledGoStraightToTheEndpoint() throws Exception {
        // SOCKS não é tunelável: a consulta vai direto ao endpoint (caminho do host)
        startChallengeServer();
        server.stop(0);
        challenges.set(0);
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        AtomicInteger requests = new AtomicInteger();
        server.createContext("/", exchange -> {
            requests.incrementAndGet();
            byte[] body = ("{\"status\":\"success\",\"countryCode\":\"US\","
                + "\"timezone\":\"America/New_York\",\"query\":\"198.51.100.7\",\"mobile\":false}")
                .getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, body.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(body);
            }
        });
        server.start();

        String endpoint = "http://127.0.0.1:" + server.getAddress().getPort() + "/json/%s";
        HttpEgressResolver socks = new HttpEgressResolver(endpoint, Duration.ofSeconds(3), true);
        EgressInfo viaSocks = socks.resolve(new ProxyConfig("203.0.113.1", 1080, ProxyType.SOCKS5));

        assertNotNull(viaSocks);
        assertEquals("US", viaSocks.countryCode);
        // a URI carrega o host do proxy no lugar do %s (consulta do host de entrada)
        org.assertj.core.api.Assertions.assertThat(requests.get()).isEqualTo(1);

        HttpEgressResolver direto = new HttpEgressResolver(endpoint, Duration.ofSeconds(3), false);
        assertNotNull(direto.resolve(new ProxyConfig("203.0.113.2", 8080, ProxyType.HTTP)));
    }
}
