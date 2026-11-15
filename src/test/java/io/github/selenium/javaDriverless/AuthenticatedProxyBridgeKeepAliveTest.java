package io.github.selenium.javaDriverless;

import io.github.selenium.javaDriverless.types.ProxyConfig;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Regressão: com keep-alive só a primeira requisição HTTP levava Proxy-Authorization e as
 * seguintes chegavam ao upstream sem credencial (407). A ponte agora injeta a credencial em
 * cada requisição e reusa a mesma conexão upstream enquanto ambas as pontas mantêm keep-alive.
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

    private static String roundTrip(String request, int expectedRequests) throws Exception {
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
                    assertThat(requests).hasSize(expectedRequests);
                    return all.toString(StandardCharsets.ISO_8859_1);
                }
            }
        }
    }

    @Test
    void keepAliveRequestIsClosedAfterFirstAuthenticatedResponse() throws Exception {
        String response = roundTrip("GET http://example.test/a HTTP/1.1\r\nHost: example.test\r\n"
                + "Connection: close\r\n\r\n", 1);
        assertThat(response).startsWith("HTTP/1.1 200 OK").contains("\r\nConnection: close\r\n")
                .doesNotContainIgnoringCase("keep-alive").endsWith("ok");
    }

    @Test
    void postBodyIsForwardedBeforeResponse() throws Exception {
        String response = roundTrip("POST http://example.test/p HTTP/1.1\r\nHost: example.test\r\n"
                + "Content-Length: 5\r\nConnection: close\r\n\r\nhello", 1);
        assertThat(response).startsWith("HTTP/1.1 200 OK").endsWith("okhello");
    }

    @Test
    void keepAliveReusesConnectionAndAuthenticatesEveryRequest() throws Exception {
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
                    // Primeira requisição com keep-alive: resposta completa, conexão segue aberta.
                    client.getOutputStream().write(("GET http://example.test/a HTTP/1.1\r\nHost: example.test\r\n"
                            + "Connection: keep-alive\r\n\r\n").getBytes(StandardCharsets.ISO_8859_1));
                    client.getOutputStream().flush();
                    InputStream clientIn = client.getInputStream();
                    String firstHeaders = readHeaders(clientIn);
                    assertThat(firstHeaders).startsWith("HTTP/1.1 200 OK");
                    byte[] firstBody = clientIn.readNBytes(contentLength(firstHeaders));
                    assertThat(new String(firstBody, StandardCharsets.ISO_8859_1)).isEqualTo("ok");
                    // Segunda requisição no MESMO socket cliente, agora com Connection: close.
                    client.getOutputStream().write(("GET http://example.test/b HTTP/1.1\r\nHost: example.test\r\n"
                            + "Connection: close\r\n\r\n").getBytes(StandardCharsets.ISO_8859_1));
                    client.getOutputStream().flush();
                    ByteArrayOutputStream second = new ByteArrayOutputStream();
                    clientIn.transferTo(second);
                    assertThat(second.toString(StandardCharsets.ISO_8859_1))
                            .startsWith("HTTP/1.1 200 OK").endsWith("ok");
                }
            }
            // Dois pedidos no único accept do upstream: a ponte reusou a conexão upstream.
            assertThat(requests).hasSize(2);
            assertThat(requests).allMatch(r -> r.toLowerCase(Locale.ROOT).contains("\r\nproxy-authorization:"));
        }
    }

    @Test
    void copyMessageBodyRejectsNegativeChunk() {
        byte[] headers = "HTTP/1.1 200 OK\r\nTransfer-Encoding: chunked\r\n\r\n"
                .getBytes(StandardCharsets.ISO_8859_1);
        InputStream body = new ByteArrayInputStream("-5\r\nhello\r\n0\r\n\r\n".getBytes(StandardCharsets.ISO_8859_1));
        assertThatThrownBy(() -> AuthenticatedProxyBridge.copyMessageBody(
                body, new ByteArrayOutputStream(), headers, true))
                .isInstanceOf(IOException.class);
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

    /** Upstream roteirizado: um pedido, resposta crua e socket aberto até a ponte encerrar. */
    private static Thread startScriptedUpstream(ServerSocket server, List<String> requests, String rawResponse) {
        Thread thread = new Thread(() -> {
            try (Socket socket = server.accept()) {
                socket.setSoTimeout(5_000);
                InputStream in = socket.getInputStream();
                String headers = readHeaders(in);
                if (!headers.isEmpty()) {
                    requests.add(headers);
                    socket.getOutputStream().write(rawResponse.getBytes(StandardCharsets.ISO_8859_1));
                    socket.getOutputStream().flush();
                    // Mantém o socket aberto lendo até EOF: só a ponte fechando encerra a leitura.
                    while (in.read() != -1) {
                        // Consome até a ponte encerrar a conexão.
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

    /** Ponte apontando para o upstream dado; devolve tudo que o cliente lê até EOF. */
    private static String scriptedRoundTrip(String request, ServerSocket upstream, List<String> requests,
            int expectedRequests) throws Exception {
        ProxyConfig config = new ProxyConfig("127.0.0.1", upstream.getLocalPort(),
                ProxyConfig.ProxyType.HTTP, "ka-user", "ka-pass");
        try (AuthenticatedProxyBridge bridge = AuthenticatedProxyBridge.start(config, true, true)) {
            bridge.bindOwner(ProcessHandle.current().pid());
            int port = Integer.parseInt(bridge.asChromeProxyServer().substring("http://127.0.0.1:".length()));
            try (Socket client = new Socket("127.0.0.1", port)) {
                client.setSoTimeout(5_000);
                client.getOutputStream().write(request.getBytes(StandardCharsets.ISO_8859_1));
                client.getOutputStream().flush();
                ByteArrayOutputStream all = new ByteArrayOutputStream();
                client.getInputStream().transferTo(all);
                assertThat(requests).hasSize(expectedRequests);
                assertThat(requests).allMatch(r -> r.toLowerCase(Locale.ROOT).contains("proxy-authorization:"));
                return all.toString(StandardCharsets.ISO_8859_1);
            }
        }
    }

    private static String scriptedUpstream(String request, String rawResponse, int expectedRequests) throws Exception {
        try (ServerSocket upstream = new ServerSocket(0)) {
            upstream.setSoTimeout(5_000);
            List<String> requests = new CopyOnWriteArrayList<>();
            startScriptedUpstream(upstream, requests, rawResponse);
            return scriptedRoundTrip(request, upstream, requests, expectedRequests);
        }
    }

    @Test
    void copyMessageBodyCopiesChunkExtensionsAndTrailers() throws Exception {
        byte[] headers = "HTTP/1.1 200 OK\r\nTransfer-Encoding: chunked\r\n\r\n"
                .getBytes(StandardCharsets.ISO_8859_1);
        byte[] body = "5;ext=1\r\nhello\r\n0\r\nX-T: 1\r\n\r\n".getBytes(StandardCharsets.ISO_8859_1);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        AuthenticatedProxyBridge.copyMessageBody(new ByteArrayInputStream(body), out, headers, true);
        assertThat(out.toByteArray()).isEqualTo(body);
    }

    @Test
    void copyMessageBodyRejectsTooManyTrailers() {
        byte[] headers = "HTTP/1.1 200 OK\r\nTransfer-Encoding: chunked\r\n\r\n"
                .getBytes(StandardCharsets.ISO_8859_1);
        StringBuilder body = new StringBuilder("0\r\n");
        for (int i = 0; i < 101; i++) {
            body.append("X: 1\r\n");
        }
        body.append("\r\n");
        assertThatThrownBy(() -> AuthenticatedProxyBridge.copyMessageBody(
                new ByteArrayInputStream(body.toString().getBytes(StandardCharsets.ISO_8859_1)),
                new ByteArrayOutputStream(), headers, true))
                .isInstanceOf(IOException.class).hasMessageContaining("Trailers em excesso");
    }

    @Test
    void http10RequestWithoutKeepAliveIsClosedAfterResponse() throws Exception {
        String response = roundTrip("GET http://example.test/a HTTP/1.0\r\nHost: example.test\r\n\r\n", 1);
        assertThat(response).startsWith("HTTP/1.1 200 OK").contains("\r\nConnection: close\r\n").endsWith("ok");
    }

    @Test
    void upstreamConnectionCloseClosesClientEvenWithKeepAlive() throws Exception {
        String response = scriptedUpstream("GET http://example.test/a HTTP/1.1\r\nHost: example.test\r\n"
                + "Connection: keep-alive\r\n\r\n",
                "HTTP/1.1 200 OK\r\nContent-Length: 2\r\nConnection: close\r\n\r\nok", 1);
        assertThat(response).startsWith("HTTP/1.1 200 OK").contains("\r\nConnection: close\r\n").endsWith("ok");
    }

    @Test
    void informationalResponsesAreForwardedBeforeFinal() throws Exception {
        String response = scriptedUpstream("GET http://example.test/a HTTP/1.1\r\nHost: example.test\r\n"
                + "Connection: close\r\n\r\n",
                "HTTP/1.1 100 Continue\r\n\r\n"
                        + "HTTP/1.1 200 OK\r\nContent-Length: 2\r\nConnection: close\r\n\r\nok", 1);
        assertThat(response).startsWith("HTTP/1.1 100 Continue\r\n\r\nHTTP/1.1 200 OK").endsWith("ok");
    }

    @Test
    void tooManyInformationalResponsesAbortWithoutFinal() throws Exception {
        String response = scriptedUpstream("GET http://example.test/a HTTP/1.1\r\nHost: example.test\r\n"
                + "Connection: close\r\n\r\n",
                "HTTP/1.1 100 Continue\r\n\r\n".repeat(17)
                        + "HTTP/1.1 200 OK\r\nContent-Length: 2\r\nConnection: close\r\n\r\nok", 1);
        assertThat(response).doesNotContain("HTTP/1.1 200");
    }

    private static int contentLength(String headers) {
        for (String line : headers.split("\r\n")) {
            if (line.toLowerCase(Locale.ROOT).startsWith("content-length:")) {
                return Integer.parseInt(line.substring(line.indexOf(':') + 1).trim());
            }
        }
        throw new AssertionError("Content-Length ausente na resposta");
    }
}
