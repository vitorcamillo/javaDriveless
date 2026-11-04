package io.github.selenium.javaDriverless;

import io.github.selenium.javaDriverless.types.ProxyConfig;
import org.junit.jupiter.api.Test;

import java.io.OutputStream;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.net.InetAddress;
import java.nio.charset.StandardCharsets;
import javax.net.ssl.SSLParameters;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.ThreadPoolExecutor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AuthenticatedProxyBridgeSecurityTest {
    @Test
    void unavailableProcessGateFailsExplicitlyBeforeBridgeStartup() {
        ProxyConfig config = new ProxyConfig(
                "127.0.0.1", 9, ProxyConfig.ProxyType.HTTP, "user", "pass");

        assertThatThrownBy(() -> AuthenticatedProxyBridge.start(config, false, true))
                .isInstanceOf(UnsupportedOperationException.class)
                .hasMessageContaining("/proc")
                .hasMessageContaining("processo proprietário");
    }

    @Test
    void processGateCapabilityCheckIsTestableWithoutDependingOnHostOs() {
        ProxyBridgeClientAuthorizer.requireProcessGateAvailable(true);

        assertThatThrownBy(() -> ProxyBridgeClientAuthorizer.requireProcessGateAvailable(false))
                .isInstanceOf(UnsupportedOperationException.class)
                .hasMessageContaining("/proc");
    }

    @Test
    void httpsUpstreamSelectsTlsWithHostnameVerification() {
        ProxyConfig config = new ProxyConfig(
                "proxy.example", 443, ProxyConfig.ProxyType.HTTPS, "user", "pass");
        SSLParameters parameters = new SSLParameters();

        assertThat(AuthenticatedProxyBridge.validateAndSelectTransport(config, false))
                .isEqualTo(AuthenticatedProxyBridge.UpstreamTransport.TLS);
        assertThat(AuthenticatedProxyBridge.enableHttpsEndpointIdentification(parameters)
                .getEndpointIdentificationAlgorithm()).isEqualTo("HTTPS");
    }

    @Test
    void authenticatedHttpRequiresExplicitOptInBeforeAnySocketIsOpened() {
        ProxyConfig config = new ProxyConfig(
                "sensitive-host", 8080, ProxyConfig.ProxyType.HTTP,
                "sensitive-user", "sensitive-password");

        assertThatThrownBy(() -> AuthenticatedProxyBridge.start(config, true, false))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("JAVA_DRIVERLESS_ALLOW_INSECURE_HTTP_PROXY_AUTH=true")
                .hasMessageNotContaining("sensitive-host")
                .hasMessageNotContaining("sensitive-user")
                .hasMessageNotContaining("sensitive-password");
    }

    @Test
    void oversizedOrIncompleteProxyHeadersAreRejected() {
        byte[] oversized = new byte[AuthenticatedProxyBridge.MAX_HEADER_BYTES + 1];
        java.util.Arrays.fill(oversized, (byte) 'A');

        assertThatThrownBy(() -> AuthenticatedProxyBridge.readHeaders(new ByteArrayInputStream(oversized)))
                .isInstanceOf(IOException.class)
                .hasMessageContaining("excedem");
        assertThatThrownBy(() -> AuthenticatedProxyBridge.readHeaders(
                new ByteArrayInputStream("GET / HTTP/1.1\r\n".getBytes(StandardCharsets.ISO_8859_1))))
                .isInstanceOf(IOException.class)
                .hasMessageContaining("incompletos");
    }

    @Test
    void processIdentityIncludesStartInstantToRejectPidReuse() {
        var identity = ProxyBridgeClientAuthorizer.captureIdentity(ProcessHandle.current().pid());
        assertThat(identity).isPresent();
        assertThat(ProxyBridgeClientAuthorizer.isCurrentIdentity(identity.orElseThrow())).isTrue();

        var reusedPid = new ProxyBridgeClientAuthorizer.ProcessIdentity(
                identity.orElseThrow().pid(),
                identity.orElseThrow().startInstant().minus(1, ChronoUnit.SECONDS));
        assertThat(ProxyBridgeClientAuthorizer.isCurrentIdentity(reusedPid)).isFalse();
    }

    @Test
    void everyDescendantIdentityIsRevalidatedBeforeDescriptorInspection() {
        var current = new ProxyBridgeClientAuthorizer.ProcessIdentity(
                101L, Instant.parse("2026-07-11T12:00:00Z"));
        var stale = new ProxyBridgeClientAuthorizer.ProcessIdentity(
                202L, Instant.parse("2026-07-11T12:00:01Z"));
        List<ProxyBridgeClientAuthorizer.ProcessIdentity> validated = new ArrayList<>();
        AtomicInteger staleDescriptorReads = new AtomicInteger();

        boolean owned = ProxyBridgeClientAuthorizer.identitiesOwnSocket(
                List.of(stale, current),
                "socket:[42]",
                identity -> {
                    validated.add(identity);
                    return identity.equals(current);
                },
                (identity, socketLink) -> {
                    if (identity.equals(stale)) {
                        staleDescriptorReads.incrementAndGet();
                    }
                    return identity.equals(current) && socketLink.equals("socket:[42]");
                });

        assertThat(owned).isTrue();
        assertThat(validated).containsExactly(stale, current, current);
        assertThat(staleDescriptorReads).hasValue(0);
    }

    @Test
    void processSocketLookupRequiresTheCompleteTcpTuple() throws Exception {
        InetAddress chromeAddress = InetAddress.getByName("127.0.0.1");
        InetAddress bridgeAddress = InetAddress.getByName("127.0.0.1");

        assertThat(ProxyBridgeClientAuthorizer.socketTupleMatches(
                "0100007F:C350", "0100007F:2329",
                chromeAddress, 50_000, bridgeAddress, 9_001)).isTrue();
        assertThat(ProxyBridgeClientAuthorizer.socketTupleMatches(
                "0200007F:C350", "0100007F:2329",
                chromeAddress, 50_000, bridgeAddress, 9_001)).isFalse();
    }

    @Test
    void unboundLocalClientCannotReachAuthenticatedUpstream() throws Exception {
        try (ServerSocket upstream = new ServerSocket(0)) {
            upstream.setSoTimeout(500);
            ProxyConfig config = new ProxyConfig(
                    "127.0.0.1",
                    upstream.getLocalPort(),
                    ProxyConfig.ProxyType.HTTP,
                    "user",
                    "pass");

            try (AuthenticatedProxyBridge bridge = AuthenticatedProxyBridge.start(config, true, true);
                 Socket client = new Socket("127.0.0.1",
                         Integer.parseInt(bridge.asChromeProxyServer().split(":")[2]))) {
                OutputStream output = client.getOutputStream();
                output.write("CONNECT example.com:443 HTTP/1.1\r\nHost: example.com:443\r\n\r\n"
                        .getBytes(StandardCharsets.ISO_8859_1));
                output.flush();

                assertThatThrownBy(upstream::accept)
                        .isInstanceOf(SocketTimeoutException.class);
            }
        }
    }

    @Test
    void ownerBoundLocalClientCanReachUpstream() throws Exception {
        try (ServerSocket upstream = new ServerSocket(0)) {
            upstream.setSoTimeout(2_000);
            ProxyConfig config = new ProxyConfig(
                    "127.0.0.1", upstream.getLocalPort(), ProxyConfig.ProxyType.HTTP, "user", "pass");

            try (AuthenticatedProxyBridge bridge = AuthenticatedProxyBridge.start(config, true, true)) {
                bridge.bindOwner(ProcessHandle.current().pid());
                try (Socket client = new Socket("127.0.0.1", bridgePort(bridge))) {
                    client.getOutputStream().write(
                            "GET http://example.test/ HTTP/1.1\r\nHost: example.test\r\n\r\n"
                                    .getBytes(StandardCharsets.ISO_8859_1));
                    client.getOutputStream().flush();
                    try (Socket accepted = upstream.accept()) {
                        assertThat(accepted.isConnected()).isTrue();
                    }
                }
            }
        }
    }

    @Test
    void workerPoolHasExplicitThreadAndConnectionLimits() throws Exception {
        ProxyConfig config = new ProxyConfig(
                "127.0.0.1", 9, ProxyConfig.ProxyType.HTTP, "user", "pass");
        try (AuthenticatedProxyBridge bridge = AuthenticatedProxyBridge.start(config, true, true)) {
            java.lang.reflect.Field executorField = AuthenticatedProxyBridge.class.getDeclaredField("executor");
            executorField.setAccessible(true);
            ThreadPoolExecutor executor = (ThreadPoolExecutor) executorField.get(bridge);

            assertThat(executor.getMaximumPoolSize()).isEqualTo(AuthenticatedProxyBridge.MAX_WORKER_THREADS);
            assertThat(AuthenticatedProxyBridge.MAX_ACTIVE_CONNECTIONS).isPositive().isLessThanOrEqualTo(64);
        }
    }

    @Test
    void connectTargetValidationAcceptsOnlyWellFormedHostPort() {
        assertThat(AuthenticatedProxyBridge.isValidConnectTarget("example.com:443")).isTrue();
        assertThat(AuthenticatedProxyBridge.isValidConnectTarget("1.2.3.4:1")).isTrue();
        assertThat(AuthenticatedProxyBridge.isValidConnectTarget("host:65535")).isTrue();
        assertThat(AuthenticatedProxyBridge.isValidConnectTarget("[::1]:443")).isTrue();
        assertThat(AuthenticatedProxyBridge.isValidConnectTarget("[2001:db8::1]:8080")).isTrue();
        assertThat(AuthenticatedProxyBridge.isValidConnectTarget("[::ffff:192.168.0.1]:443")).isTrue();

        assertThat(AuthenticatedProxyBridge.isValidConnectTarget(null)).isFalse();
        assertThat(AuthenticatedProxyBridge.isValidConnectTarget("")).isFalse();
        assertThat(AuthenticatedProxyBridge.isValidConnectTarget("host")).isFalse();
        assertThat(AuthenticatedProxyBridge.isValidConnectTarget("host:")).isFalse();
        assertThat(AuthenticatedProxyBridge.isValidConnectTarget(":443")).isFalse();
        // isValidPort exige 1..65535, então porta 0 é inválida.
        assertThat(AuthenticatedProxyBridge.isValidConnectTarget("host:0")).isFalse();
        assertThat(AuthenticatedProxyBridge.isValidConnectTarget("a:b:c")).isFalse();
        assertThat(AuthenticatedProxyBridge.isValidConnectTarget("host:65536")).isFalse();
        assertThat(AuthenticatedProxyBridge.isValidConnectTarget("host:123456")).isFalse();
        assertThat(AuthenticatedProxyBridge.isValidConnectTarget("host:12a")).isFalse();
        assertThat(AuthenticatedProxyBridge.isValidConnectTarget("host:-1")).isFalse();
        assertThat(AuthenticatedProxyBridge.isValidConnectTarget("ho st:443")).isFalse();
        assertThat(AuthenticatedProxyBridge.isValidConnectTarget("host:443\r")).isFalse();
        assertThat(AuthenticatedProxyBridge.isValidConnectTarget("host:443\n")).isFalse();
        assertThat(AuthenticatedProxyBridge.isValidConnectTarget("user@host:443")).isFalse();
        assertThat(AuthenticatedProxyBridge.isValidConnectTarget("host/x:443")).isFalse();
        assertThat(AuthenticatedProxyBridge.isValidConnectTarget("[]:443")).isFalse();
        assertThat(AuthenticatedProxyBridge.isValidConnectTarget("[:443")).isFalse();
        assertThat(AuthenticatedProxyBridge.isValidConnectTarget("[::1]443")).isFalse();
        assertThat(AuthenticatedProxyBridge.isValidConnectTarget("[::1]:")).isFalse();
        assertThat(AuthenticatedProxyBridge.isValidConnectTarget("[::1]:99999")).isFalse();
        assertThat(AuthenticatedProxyBridge.isValidConnectTarget("ho[st:443")).isFalse();
        assertThat(AuthenticatedProxyBridge.isValidConnectTarget("host]:443")).isFalse();
        // Conteúdo do colchete precisa ser literal IPv6 real, não pseudo-host.
        assertThat(AuthenticatedProxyBridge.isValidConnectTarget("[xyz]:443")).isFalse();
        assertThat(AuthenticatedProxyBridge.isValidConnectTarget("[gggg::1]:443")).isFalse();
        assertThat(AuthenticatedProxyBridge.isValidConnectTarget("[1:2:3:4:5:6:7:8:9]:443")).isFalse();
        assertThat(AuthenticatedProxyBridge.isValidConnectTarget("[::1::2]:443")).isFalse();
        // Host puro também precisa de sintaxe real: label vazio, IPv4 inválido ou
        // IPv6 incompleto sem compressão não chegam ao upstream.
        assertThat(AuthenticatedProxyBridge.isValidConnectTarget("..:443")).isFalse();
        assertThat(AuthenticatedProxyBridge.isValidConnectTarget("a..b:443")).isFalse();
        assertThat(AuthenticatedProxyBridge.isValidConnectTarget("999.1.1.1:443")).isFalse();
        assertThat(AuthenticatedProxyBridge.isValidConnectTarget("1.2.3:443")).isFalse();
        assertThat(AuthenticatedProxyBridge.isValidConnectTarget("[1:2:3]:443")).isFalse();
        assertThat(AuthenticatedProxyBridge.isValidConnectTarget("[1.2.3.4]:443")).isFalse();
    }

    @Test
    void malformedConnectRequestLineIsRejectedWithoutUpstreamContact() throws Exception {
        try (ServerSocket upstream = new ServerSocket(0)) {
            upstream.setSoTimeout(400);
            ProxyConfig config = new ProxyConfig(
                    "127.0.0.1", upstream.getLocalPort(), ProxyConfig.ProxyType.HTTP, "user", "pass");

            try (AuthenticatedProxyBridge bridge = AuthenticatedProxyBridge.start(config, true, true)) {
                bridge.bindOwner(ProcessHandle.current().pid());
                int port = bridgePort(bridge);

                // Sem destino: antes lançava ArrayIndexOutOfBoundsException na task.
                assertThat(sendRawRequest(port, "CONNECT  \r\n\r\n")).startsWith("HTTP/1.1 400");
                assertThatThrownBy(upstream::accept).isInstanceOf(SocketTimeoutException.class);

                // Porta fora da faixa válida também cai no 400.
                assertThat(sendRawRequest(port, "CONNECT example.com:99999 HTTP/1.1\r\n\r\n"))
                        .startsWith("HTTP/1.1 400");
                assertThatThrownBy(upstream::accept).isInstanceOf(SocketTimeoutException.class);

                // CONNECT sem espaço/alvo também vai ao handleConnect e cai no 400.
                assertThat(sendRawRequest(port, "CONNECT\r\n\r\n")).startsWith("HTTP/1.1 400");
                assertThatThrownBy(upstream::accept).isInstanceOf(SocketTimeoutException.class);

                // Versão fora de HTTP/1.x (HTTP/lixo) não chega ao upstream.
                assertThat(sendRawRequest(port, "CONNECT example.com:443 HTTP/lixo\r\n\r\n"))
                        .startsWith("HTTP/1.1 400");
                assertThatThrownBy(upstream::accept).isInstanceOf(SocketTimeoutException.class);

                // Pseudo-IPv6 no alvo ([xyz]:443) não chega ao upstream.
                assertThat(sendRawRequest(port, "CONNECT [xyz]:443 HTTP/1.1\r\n\r\n"))
                        .startsWith("HTTP/1.1 400");
                assertThatThrownBy(upstream::accept).isInstanceOf(SocketTimeoutException.class);

                // Host puro sem sintaxe real (.., label vazio) também cai no 400.
                assertThat(sendRawRequest(port, "CONNECT ..:443 HTTP/1.1\r\n\r\n"))
                        .startsWith("HTTP/1.1 400");
                assertThatThrownBy(upstream::accept).isInstanceOf(SocketTimeoutException.class);

                // Fluxo válido segue intacto após os 400.
                upstream.setSoTimeout(2_000);
                try (Socket client = new Socket("127.0.0.1", port)) {
                    client.setSoTimeout(2_000);
                    client.getOutputStream().write(
                            "CONNECT example.com:443 HTTP/1.1\r\nHost: example.com:443\r\n\r\n"
                                    .getBytes(StandardCharsets.ISO_8859_1));
                    client.getOutputStream().flush();
                    Socket accepted = upstream.accept();
                    try {
                        accepted.setSoTimeout(2_000);
                        byte[] relayed = AuthenticatedProxyBridge.readHeaders(accepted.getInputStream());
                        assertThat(new String(relayed, StandardCharsets.ISO_8859_1))
                                .startsWith("CONNECT example.com:443");
                        accepted.getOutputStream().write(
                                "HTTP/1.1 200 Connection Established\r\n\r\n"
                                        .getBytes(StandardCharsets.ISO_8859_1));
                        accepted.getOutputStream().flush();
                        assertThat(readHttpHeaders(client.getInputStream())).startsWith("HTTP/1.1 200");
                    } finally {
                        accepted.close();
                    }
                }
            }
        }
    }

    private int bridgePort(AuthenticatedProxyBridge bridge) {
        return Integer.parseInt(bridge.asChromeProxyServer().substring("http://127.0.0.1:".length()));
    }

    private String sendRawRequest(int port, String raw) throws IOException {
        try (Socket socket = new Socket("127.0.0.1", port)) {
            socket.setSoTimeout(2_000);
            socket.getOutputStream().write(raw.getBytes(StandardCharsets.ISO_8859_1));
            socket.getOutputStream().flush();
            return readHttpHeaders(socket.getInputStream());
        }
    }

    private String readHttpHeaders(InputStream input) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        int previous3 = -1;
        int previous2 = -1;
        int previous1 = -1;
        int current;
        while ((current = input.read()) != -1) {
            out.write(current);
            if (previous3 == '\r' && previous2 == '\n' && previous1 == '\r' && current == '\n') {
                break;
            }
            previous3 = previous2;
            previous2 = previous1;
            previous1 = current;
        }
        return out.toString(StandardCharsets.ISO_8859_1);
    }
}
