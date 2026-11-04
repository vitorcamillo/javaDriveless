package io.github.selenium.javaDriverless.types;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.concurrent.CompletableFuture;
import java.util.function.BiConsumer;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import org.java_websocket.WebSocket;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import io.github.selenium.javaDriverless.cdp.FakeCdpServer;

/**
 * Caminhos de script assíncrono do Target REAL contra servidor CDP falso:
 * evalAsync (sucesso/erro JS/sem valor), executeAsyncScript e getAlert.
 */
class TargetAsyncScriptTest {

    private static final com.fasterxml.jackson.databind.ObjectMapper MAPPER =
        new com.fasterxml.jackson.databind.ObjectMapper();

    private FakeCdpServer server;
    private Target target;

    /** modo do dispatcher para cada expression recebida. */
    private String scriptMode = "ok";

    private static ObjectNode json(String json) {
        try {
            return (ObjectNode) MAPPER.readTree(json);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    @BeforeEach
    void setUp() throws InterruptedException {
        server = new FakeCdpServer();
        server.startAndAwaitReady();

        BiConsumer<WebSocket, JsonNode> handler = (conn, req) -> {
            int id = req.get("id").asInt();
            String method = req.path("method").asText("");
            String expression = req.path("params").path("expression").asText("");
            String function = req.path("params").path("functionDeclaration").asText("");

            if ("Page.enable".equals(method)) {
                conn.send("{\"id\":" + id + ",\"result\":{}}");
                return;
            }
            // formato real do Chrome: envelope.result = {"result": RemoteObject, "exceptionDetails"?}
            if ("Runtime.evaluate".equals(method) && "globalThis".equals(expression)) {
                conn.send("{\"id\":" + id + ",\"result\":{\"result\":{\"objectId\":\"glob-1\"}}}");
                return;
            }
            if ("Runtime.evaluate".equals(method)) {
                switch (scriptMode) {
                    case "ok" -> conn.send("{\"id\":" + id
                        + ",\"result\":{\"result\":{\"value\":\"evaluated\"}}}");
                    case "noValue" -> conn.send("{\"id\":" + id + ",\"result\":{\"result\":{}}}");
                    case "jsError" -> conn.send("{\"id\":" + id
                        + ",\"result\":{\"result\":{},\"exceptionDetails\":{\"text\":\"ReferenceError: x\"}}}");
                    default -> conn.send("{\"id\":" + id + ",\"result\":{\"result\":{}}}");
                }
                return;
            }
            if ("Runtime.callFunctionOn".equals(method) && !function.isEmpty()) {
                conn.send("{\"id\":" + id + ",\"result\":{\"result\":{\"value\":\"async-out\"}}}");
                return;
            }
            conn.send("{\"id\":" + id + ",\"result\":{}}");
        };
        server.setCustomHandler(handler);

        target = new Target("127.0.0.1:" + server.port(), "tid-script", null, null,
            false, 5f, null, false, 10 * 1024 * 1024);
        target.init().join();
    }

    @AfterEach
    void tearDown() {
        try {
            if (target.getSocket() != null) {
                target.getSocket().close();
            }
        } catch (Exception ignored) {
            // já fechado
        }
        try {
            server.stop(0);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    @Test
    void evalAsyncAwaitsPromiseAndUnwrapsValue() {
        Object out = target.evalAsync("return await fetch('/x')", 5f, false).join();

        assertEquals("evaluated", out);
    }

    @Test
    void evalAsyncSurfacesJavaScriptErrors() {
        scriptMode = "jsError";

        CompletableFuture<Object> future = target.evalAsync("jsError()", 1f, false);

        Exception thrown = assertThrows(Exception.class, () -> future.join());
        // o loop de retry captura o erro JS e esgota o orçamento: a superfície final é o
        // "JavaScript error" direto OU o timeout com o erro encadeado — nunca valor silencioso
        Throwable chain = thrown;
        boolean surfaced = false;
        while (chain != null) {
            String msg = chain.getMessage();
            if (msg != null && (msg.contains("JavaScript error")
                    || msg.contains("Não foi possível executar script"))) {
                surfaced = true;
                break;
            }
            chain = chain.getCause();
        }
        assertTrue(surfaced, "erro deveria ser surfaced: " + thrown);
    }

    @Test
    void evalAsyncWithoutValueReturnsNull() {
        scriptMode = "noValue";

        assertNull(target.evalAsync("undefined", 5f, false).join());
    }

    @Test
    void executeAsyncScriptWrapsInAsyncFunctionAndUnwrapsValue() {
        Object out = target.executeAsyncScript("return 1 + 1", 2, null).join();

        assertEquals("async-out", out);
    }

    @Test
    void getAlertDegradesToEmptyTextWhenNoDialogIsOpen() {
        Alert alert = target.getAlert(0.2f).join();

        assertNotNull(alert);
        // sem diálogo aberto, getText() degrada para vazio em vez de explodir
        assertEquals("", alert.getText().join());
    }

    @Test
    void getAlertReusesPageEnabledFlagAfterFirstCall() {
        target.getAlert(0.2f).join();
        Alert second = target.getAlert(0.2f).join();

        assertNotNull(second);
    }
}
