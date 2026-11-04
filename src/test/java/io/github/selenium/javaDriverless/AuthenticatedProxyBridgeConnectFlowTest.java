package io.github.selenium.javaDriverless;

import io.github.selenium.javaDriverless.types.ProxyConfig;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Fluxo CONNECT de ponta a ponta sobre a ponte local de proxy autenticado:
 * injeção de Proxy-Authorization, túnel bidirecional e caminhos de falha.
 */
class AuthenticatedProxyBridgeConnectFlowTest {

    private static int bridgePort(AuthenticatedProxyBridge bridge) {
        return Integer.parseInt(bridge.asChromeProxyServer()
            .substring("http://127.0.0.1:".length()));
    }

    private static String readUntilHeaders(InputStream input) throws Exception {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        int state = 0;
        while (state < 4) {
            int b = input.read();
            if (b == -1) {
                throw new java.io.EOFException("conexão fechou antes dos cabeçalhos");
            }
            buffer.write(b);
            state = switch (b) {
                case '\r' -> state == 0 || state == 2 ? state + 1 : (state == 3 ? 4 : 1);
                case '\n' -> state == 1 || state == 3 ? state + 1 : 0;
                default -> 0;
            };
        }
        return buffer.toString(StandardCharsets.ISO_8859_1);
    }

    @Test
    void connectTunnelInjectsAuthorizationAndPipesBothWays() throws Exception {
        try (ServerSocket upstream = new ServerSocket(0)) {
            upstream.setSoTimeout(5_000);
            ProxyConfig config = new ProxyConfig("127.0.0.1", upstream.getLocalPort(),
                ProxyConfig.ProxyType.HTTP, "tunnel-user", "tunnel-pass");

            java.util.concurrent.CompletableFuture<String> requisicao =
                new java.util.concurrent.CompletableFuture<>();
            Thread eco = new Thread(() -> {
                try (Socket aceito = upstream.accept()) {
                    aceito.setSoTimeout(5_000);
                    String headers = readUntilHeaders(aceito.getInputStream());
                    requisicao.complete(headers);
                    aceito.getOutputStream().write(
                        "HTTP/1.1 200 Connection established\r\n\r\n"
                            .getBytes(StandardCharsets.ISO_8859_1));
                    aceito.getOutputStream().flush();
                    int b;
                    InputStream entrada = aceito.getInputStream();
                    while ((b = entrada.read()) != -1) {
                        aceito.getOutputStream().write(b);
                        aceito.getOutputStream().flush();
                    }
                } catch (Exception ignorado) {
                    requisicao.complete("falhou: " + ignorado);
                }
            });
            eco.setDaemon(true);
            eco.start();

            try (AuthenticatedProxyBridge bridge = AuthenticatedProxyBridge.start(config, true, true)) {
                bridge.bindOwner(ProcessHandle.current().pid());
                try (Socket cliente = new Socket("127.0.0.1", bridgePort(bridge))) {
                    cliente.setSoTimeout(5_000);
                    OutputStream saida = cliente.getOutputStream();
                    saida.write("CONNECT alvo.test:443 HTTP/1.1\r\nHost: alvo.test:443\r\n\r\n"
                        .getBytes(StandardCharsets.ISO_8859_1));
                    saida.flush();

                    String resposta = readUntilHeaders(cliente.getInputStream());
                    assertThat(resposta).contains("200");

                    String headersVistos = requisicao.get(5, TimeUnit.SECONDS);
                    assertThat(headersVistos).contains("CONNECT alvo.test:443");
                    // credenciais injetadas pela ponte, não pelo cliente
                    assertThat(headersVistos).contains("Proxy-Authorization: Basic ");
                    assertThat(headersVistos).doesNotContain("tunnel-pass"); // vai em Base64

                    // túnel: bytes do cliente atravessam até o upstream e voltam
                    saida.write("ping-tunel".getBytes(StandardCharsets.ISO_8859_1));
                    saida.flush();
                    byte[] eco2 = cliente.getInputStream().readNBytes("ping-tunel".length());
                    assertThat(new String(eco2, StandardCharsets.ISO_8859_1)).isEqualTo("ping-tunel");
                }
            }
        }
    }

    @Test
    void upstreamRefusalOfConnectReachesTheClient() throws Exception {
        try (ServerSocket upstream = new ServerSocket(0)) {
            upstream.setSoTimeout(5_000);
            ProxyConfig config = new ProxyConfig("127.0.0.1", upstream.getLocalPort(),
                ProxyConfig.ProxyType.HTTP, "u", "p");

            Thread recusa = new Thread(() -> {
                try (Socket aceito = upstream.accept()) {
                    aceito.setSoTimeout(5_000);
                    readUntilHeaders(aceito.getInputStream());
                    aceito.getOutputStream().write(
                        "HTTP/1.1 407 Proxy Authentication Required\r\n\r\n"
                            .getBytes(StandardCharsets.ISO_8859_1));
                    aceito.getOutputStream().flush();
                } catch (Exception ignorado) {
                }
            });
            recusa.setDaemon(true);
            recusa.start();

            try (AuthenticatedProxyBridge bridge = AuthenticatedProxyBridge.start(config, true, true)) {
                bridge.bindOwner(ProcessHandle.current().pid());
                try (Socket cliente = new Socket("127.0.0.1", bridgePort(bridge))) {
                    cliente.setSoTimeout(5_000);
                    cliente.getOutputStream().write(
                        "CONNECT alvo.test:443 HTTP/1.1\r\nHost: alvo.test:443\r\n\r\n"
                            .getBytes(StandardCharsets.ISO_8859_1));
                    cliente.getOutputStream().flush();

                    String resposta = readUntilHeaders(cliente.getInputStream());
                    assertThat(resposta).contains("407");
                }
            }
        }
    }

    @Test
    void clientThatDisconnectsBeforeHeadersDoesNotDisturbTheBridge() throws Exception {
        ProxyConfig config = new ProxyConfig("127.0.0.1", 9,
            ProxyConfig.ProxyType.HTTP, "u", "p");
        try (AuthenticatedProxyBridge bridge = AuthenticatedProxyBridge.start(config, true, true)) {
            bridge.bindOwner(ProcessHandle.current().pid());
            try (Socket mudo = new Socket("127.0.0.1", bridgePort(bridge))) {
                // fecha sem mandar nada: worker da ponte descarta sem explosão
            }
            // a ponte continua viva e aceitando novas conexões
            try (Socket posterior = new Socket("127.0.0.1", bridgePort(bridge))) {
                assertThat(posterior.isConnected()).isTrue();
            }
        }
    }

    @Test
    void bindOwnerRejectsDeadPidsAndRebinding() throws Exception {
        ProxyConfig config = new ProxyConfig("127.0.0.1", 9,
            ProxyConfig.ProxyType.HTTP, "u", "p");
        try (AuthenticatedProxyBridge bridge = AuthenticatedProxyBridge.start(config, true, true)) {
            assertThatThrownBy(() -> bridge.bindOwner(999_999_999L))
                .isInstanceOf(IllegalArgumentException.class);

            Process dummy = new ProcessBuilder("/bin/sh", "-c", "sleep 30").start();
            try {
                bridge.bindOwner(dummy.pid());
                assertThatThrownBy(() -> bridge.bindOwner(ProcessHandle.current().pid()))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("já vinculada");
            } finally {
                dummy.destroyForcibly();
            }
        }
    }

    @Test
    void platformSupportProbeAndTransportValidation() {
        assertThat(AuthenticatedProxyBridge.isSupportedOnCurrentPlatform())
            .isEqualTo(ProxyBridgeClientAuthorizer.processGateAvailable());

        // sem autenticação: recusado antes de qualquer socket
        assertThatThrownBy(() -> AuthenticatedProxyBridge.validateAndSelectTransport(
            new ProxyConfig("127.0.0.1", 8080, ProxyConfig.ProxyType.HTTP), true))
            .isInstanceOf(IllegalArgumentException.class);

        // tipo não-HTTP(S)
        assertThatThrownBy(() -> AuthenticatedProxyBridge.validateAndSelectTransport(
            new ProxyConfig("127.0.0.1", 1080, ProxyConfig.ProxyType.SOCKS5, "u", "p"), true))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("somente upstream HTTP ou HTTPS");

        // HTTP autenticado sem opt-in explícito
        assertThatThrownBy(() -> AuthenticatedProxyBridge.validateAndSelectTransport(
            new ProxyConfig("127.0.0.1", 8080, ProxyConfig.ProxyType.HTTP, "u", "p"), false))
            .isInstanceOf(IllegalStateException.class);

        // HTTPS autenticado → TLS, independente do opt-in
        assertThat(AuthenticatedProxyBridge.validateAndSelectTransport(
            new ProxyConfig("127.0.0.1", 443, ProxyConfig.ProxyType.HTTPS, "u", "p"), false))
            .isEqualTo(AuthenticatedProxyBridge.UpstreamTransport.TLS);
    }
}
