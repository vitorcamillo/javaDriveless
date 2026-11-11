package io.github.selenium.javaDriverless;

import io.github.selenium.javaDriverless.types.ProxyConfig;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Regressão: com keep-alive só a primeira requisição HTTP levava Proxy-Authorization e as
 * seguintes chegavam ao upstream sem credencial (407). A ponte agora força uma requisição
 * por conexão.
 */
class AuthenticatedProxyBridgeKeepAliveTest {

    private static String readHeaders(InputStream in) throws Exception {
        return new String(AuthenticatedProxyBridge.readHeaders(in), StandardCharsets.ISO_8859_1);
    }

    /** Upstream que mantém keep-alive, salvo se o pedido trouxer Connection: close. */
    private static Thread startUpstream(ServerSocket server, List<String> requests) {
        Thread thread = new Thread(() -> {
            try (Socket socket = server.accept()) {
                socket.setSoTimeout(5_000);
                InputStream in = socket.getInputStream();
                while (true) {
                    String headers = readHeaders(in);
                    if (headers.isEmpty()) {
                        return;
                    }
                    requests.add(headers);
                    String lower = headers.toLowerCase(Locale.ROOT);
                    int length = lower.contains("content-length: 5") ? 5 : 0;
                    byte[] body = in.readNBytes(length);
                    boolean auth = lower.contains("\r\nproxy-authorization: basic ");
                    String reply = auth ? "ok" + new String(body, StandardCharsets.ISO_8859_1) : "";
                    socket.getOutputStream().write(((auth ? "HTTP/1.1 200 OK" : "HTTP/1.1 407 Auth")
                            + "\r\nContent-Length: " + reply.length() + "\r\nConnection: keep-alive\r\n\r\n"
                            + reply).getBytes(StandardCharsets.ISO_8859_1));
                    socket.getOutputStream().flush();
                    if (lower.contains("\r\nconnection: close")) {
                        return;
                    }
                }
            } catch (Exception ignored) {
                // Fim do teste ou conexão encerrada pela ponte.
            }
        });
        thread.setDaemon(true);
        thread.start();
        return thread;
    }

    private static String roundTrip(String request) throws Exception {
        try (ServerSocket upstream = new ServerSocket(0)) {
            upstream.setSoTimeout(5_000);
            List<String> requests = new CopyOnWriteArrayList<>();
            startUpstream(upstream, requests);
            ProxyConfig config = new ProxyConfig("127.0.0.1", upstream.getLocalPort(),
                    ProxyConfig.ProxyType.HTTP, "ka-user", "ka-pass");
            try (AuthenticatedProxyBridge bridge = AuthenticatedProxyBridge.start(config, true, true)) {
                bridge.bindOwner(ProcessHandle.current().pid());
                int port = Integer.parseInt(bridge.asChromeProxyServer().substring("http://127.0.0.1:".length()));
                try (Socket client = new Socket("127.0.0.1", port)) {
                    client.setSoTimeout(5_000);
                    client.getOutputStream().write(request.getBytes(StandardCharsets.ISO_8859_1));
                    client.getOutputStream().flush();
                    // Lê até EOF: só termina se a ponte encerrar a conexão após a resposta.
                    ByteArrayOutputStream all = new ByteArrayOutputStream();
                    client.getInputStream().transferTo(all);
                    assertThat(requests).allMatch(r -> r.toLowerCase(Locale.ROOT).contains("proxy-authorization:"));
                    assertThat(requests).hasSize(1);
                    return all.toString(StandardCharsets.ISO_8859_1);
                }
            }
        }
    }

    @Test
    void keepAliveRequestIsClosedAfterFirstAuthenticatedResponse() throws Exception {
        String response = roundTrip("GET http://example.test/a HTTP/1.1\r\nHost: example.test\r\n"
                + "Proxy-Connection: keep-alive\r\nConnection: keep-alive\r\n\r\n");
        assertThat(response).startsWith("HTTP/1.1 200 OK").contains("\r\nConnection: close\r\n")
                .doesNotContainIgnoringCase("keep-alive").endsWith("ok");
    }

    @Test
    void postBodyIsForwardedBeforeResponse() throws Exception {
        String response = roundTrip("POST http://example.test/p HTTP/1.1\r\nHost: example.test\r\n"
                + "Content-Length: 5\r\n\r\nhello");
        assertThat(response).startsWith("HTTP/1.1 200 OK").endsWith("okhello");
    }

    @Test
    void forceConnectionCloseRewritesOnlyHopHeaders() {
        byte[] in = ("GET http://x.test/ HTTP/1.1\r\nHost: x.test\r\nConnection: keep-alive\r\n"
                + "Keep-Alive: timeout=5\r\nProxy-Connection: keep-alive\r\nAccept: */*\r\n\r\n")
                .getBytes(StandardCharsets.ISO_8859_1);
        String out = new String(AuthenticatedProxyBridge.forceConnectionClose(in, true), StandardCharsets.ISO_8859_1);
        assertThat(out).isEqualTo("GET http://x.test/ HTTP/1.1\r\nHost: x.test\r\nAccept: */*\r\n"
                + "Connection: close\r\nProxy-Connection: close\r\n\r\n");
    }
}
