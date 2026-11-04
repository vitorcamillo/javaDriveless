package io.github.selenium.javaDriverless.cdp;

import java.net.InetSocketAddress;
import java.util.UUID;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.java_websocket.WebSocket;
import org.java_websocket.server.WebSocketServer;

/**
 * Servidor CDP falso para testes de transporte: responde comandos por id e empurra eventos.
 * Nada de Chrome real — valida o protocolo (CDPSocket/Target) de ponta a ponta.
 */
public class FakeCdpServer extends WebSocketServer {

    private final com.fasterxml.jackson.databind.ObjectMapper mapper =
        new com.fasterxml.jackson.databind.ObjectMapper();

    /** handler customizado por teste: recebe a requisição parseada e envia a resposta cru. */
    private volatile java.util.function.BiConsumer<org.java_websocket.WebSocket, JsonNode> customHandler;

    public void setCustomHandler(
            java.util.function.BiConsumer<org.java_websocket.WebSocket, JsonNode> handler) {
        this.customHandler = handler;
    }

    /** sinaliza o fim do bind real (onStart) ou erro de servidor (ex.: porta ocupada). */
    private final java.util.concurrent.CountDownLatch started =
        new java.util.concurrent.CountDownLatch(1);

    /** erro do servidor (conn == null no onError), ex.: bind falhou. */
    private volatile Exception startupError;

    public FakeCdpServer() {
        // porta 0 = efêmera escolhida pelo SO no bind; getPort() devolve a porta real depois
        this(0);
    }

    public FakeCdpServer(int port) {
        super(new InetSocketAddress("127.0.0.1", port));
    }

    /** espera o onStart do bind real (sem sonda TCP): elimina corrida de porta entre testes. */
    public void startAndAwaitReady() throws InterruptedException {
        start();
        if (!started.await(5, java.util.concurrent.TimeUnit.SECONDS)) {
            throw new IllegalStateException("servidor CDP fake não ficou pronto");
        }
        if (startupError != null) {
            throw new IllegalStateException("servidor CDP fake falhou no bind", startupError);
        }
    }

    public int port() {
        return getPort();
    }

    public String pageWsUrl() {
        return "ws://127.0.0.1:" + port() + "/devtools/page/" + UUID.randomUUID();
    }

    @Override
    public void onMessage(WebSocket conn, String message) {
        try {
            JsonNode req = mapper.readTree(message);
            if (!req.has("id")) {
                return; // evento do cliente -> ignorar
            }
            if (customHandler != null) {
                customHandler.accept(conn, req);
                return;
            }
            int id = req.get("id").asInt();
            String method = req.path("method").asText("");

            switch (method) {
                case "Test.noReply" -> { /* silêncio proposital: caminho de timeout */ }
                case "Test.error" -> conn.send(json(id, "{\"id\":" + id
                    + ",\"error\":{\"code\":-32000,\"message\":\"boom\"}}"));
                case "Test.fireEvent" -> {
                    conn.send("{\"method\":\"Test.event\",\"params\":{\"fired\":true,\"seq\":1}}");
                    conn.send("{\"method\":\"Test.sessionEvent\","
                        + "\"sessionId\":\"sess-1\",\"params\":{\"v\":7}}");
                    conn.send(json(id, "{\"id\":" + id + ",\"result\":{\"ok\":true}}"));
                }
                case "Test.echo" -> {
                    ObjectNode result = mapper.createObjectNode();
                    result.put("method", method);
                    result.set("params", req.get("params"));
                    conn.send(mapper.writeValueAsString(
                        mapper.createObjectNode().put("id", id).set("result", result)));
                }
                default -> conn.send(json(id, "{\"id\":" + id + ",\"result\":{}}"));
            }
        } catch (Exception ignored) {
            // payload inválido em teste de transporte: descarta
        }
    }

    private static String json(int id, String body) {
        return body.replace("\"id\":" + id, "\"id\":" + id);
    }

    @Override
    public void onOpen(WebSocket conn, org.java_websocket.handshake.ClientHandshake handshake) {
    }

    @Override
    public void onClose(WebSocket conn, int code, String reason, boolean remote) {
    }

    @Override
    public void onError(WebSocket conn, Exception ex) {
        if (conn == null) {
            // erro do servidor (ex.: bind): libera a espera e propaga em startAndAwaitReady()
            startupError = ex;
            started.countDown();
        }
        // erro de conexão individual: ignorar
    }

    @Override
    public void onStart() {
        started.countDown(); // bind concluído
    }
}
