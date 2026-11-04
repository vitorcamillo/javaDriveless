package io.github.selenium.javaDriverless.types;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BiConsumer;

import com.fasterxml.jackson.databind.JsonNode;

import org.java_websocket.WebSocket;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import io.github.selenium.javaDriverless.cdp.FakeCdpServer;

/**
 * executeScript do Target REAL contra servidor CDP falso: wrapping IIFE,
 * returnByValue, serialização deep (WebElements e primitivos) e erros de JS.
 */
class TargetScriptCdpTest {

    private static final com.fasterxml.jackson.databind.ObjectMapper MAPPER =
        new com.fasterxml.jackson.databind.ObjectMapper();

    private FakeCdpServer server;
    private Target target;

    /** última expression recebida pelo Runtime.evaluate (para provar o wrapping). */
    private final AtomicReference<String> lastExpression = new AtomicReference<>();
    /** payloads de resposta por expressão recebida, em ordem. */
    private final ConcurrentLinkedQueue<String> responses = new ConcurrentLinkedQueue<>();
    /** opções recebidas na última chamada Runtime.evaluate. */
    private final AtomicReference<JsonNode> lastParams = new AtomicReference<>();

    private static JsonNode json(String raw) {
        try {
            return MAPPER.readTree(raw);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private final BiConsumer<WebSocket, JsonNode> handler = (conn, req) -> {
        int id = req.get("id").asInt();
        String method = req.path("method").asText("");

        if ("Runtime.evaluate".equals(method)) {
            lastExpression.set(req.path("params").path("expression").asText());
            lastParams.set(req.path("params"));
            String next = responses.poll();
            conn.send("{\"id\":" + id + ",\"result\":" + (next == null ? "{\"result\":{}}" : next) + "}");
            return;
        }
        conn.send("{\"id\":" + id + ",\"result\":{}}");
    };

    @BeforeEach
    void setUp() throws InterruptedException {
        server = new FakeCdpServer();
        server.startAndAwaitReady();
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
        }
        try {
            server.stop(0);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    @Test
    void plainExpressionIsSentVerbatimWithReturnByValue() throws Exception {
        responses.add("{\"result\":{\"value\":\"resultado\"}}");

        Object saida = target.executeScript("window.__flag", null, false)
            .get(5, java.util.concurrent.TimeUnit.SECONDS);

        assertEquals("resultado", saida);
        assertEquals("window.__flag", lastExpression.get());
        assertTrue(lastParams.get().path("returnByValue").asBoolean());
        assertFalse(lastParams.get().path("awaitPromise").asBoolean());
    }

    @Test
    void topLevelReturnGetsWrappedIntoIife() throws Exception {
        responses.add("{\"result\":{\"value\":7}}");

        Object saida = target.executeScript("return 7", null, false)
            .get(5, java.util.concurrent.TimeUnit.SECONDS);

        assertEquals(7, saida);
        assertEquals("(function(){return 7})()", lastExpression.get());
    }

    @Test
    void returnInsideNestedFunctionDoesNotGetWrapped() throws Exception {
        responses.add("{\"result\":{\"value\":\"ok\"}}");

        target.executeScript("function f() { return 1; } f()", null, false)
            .get(5, java.util.concurrent.TimeUnit.SECONDS);

        assertEquals("function f() { return 1; } f()", lastExpression.get());
    }

    @Test
    void returnMentionsInsideStringsAndCommentsAreIgnoredByWrapper() throws Exception {
        responses.add("{\"result\":{\"value\":\"ok\"}}");
        target.executeScript("var s = 'return falso'; // return comentário\n/* return bloco */ 1 + 1", null, false)
            .get(5, java.util.concurrent.TimeUnit.SECONDS);
        assertEquals("var s = 'return falso'; // return comentário\n/* return bloco */ 1 + 1", lastExpression.get());

        responses.add("{\"result\":{\"value\":\"ok\"}}");
        target.executeScript("var escapado = \"aspas \\\" return\"; 2", null, false)
            .get(5, java.util.concurrent.TimeUnit.SECONDS);
        assertEquals("var escapado = \"aspas \\\" return\"; 2", lastExpression.get());
    }

    @Test
    void javascriptExceptionDetailsSurfacesAsRuntimeException() {
        responses.add("{\"result\":{},\"exceptionDetails\":{\"text\":\"ReferenceError: x is not defined\"}}");

        RuntimeException thrown = assertThrows(RuntimeException.class,
            () -> target.executeScript("x()", null, false).join());

        assertTrue(thrown.getMessage().contains("JavaScript error"));
        assertTrue(thrown.getMessage().contains("ReferenceError"));
    }

    @Test
    void resultWithoutValueYieldsNull() throws Exception {
        responses.add("{\"result\":{}}");

        assertNull(target.executeScript("undefined", null, false)
            .get(5, java.util.concurrent.TimeUnit.SECONDS));
    }

    @Test
    void deepSerializationDisablesReturnByValueAndRequestsDeepOption() throws Exception {
        responses.add("{\"result\":{\"value\":\"deep-out\"}}");

        Object saida = target.executeScript("objeto", null, false, "deep")
            .get(5, java.util.concurrent.TimeUnit.SECONDS);

        assertEquals("deep-out", saida);
        assertFalse(lastParams.get().path("returnByValue").asBoolean());
        assertEquals("deep", lastParams.get().path("serializationOptions").path("serialization").asText());
        assertEquals(2, lastParams.get().path("serializationOptions").path("maxDepth").asInt());
    }

    @Test
    void deepSerializedSingleNodeBecomesWebElement() throws Exception {
        responses.add("{\"result\":{\"deepSerializedValue\":"
            + "{\"type\":\"node\",\"value\":{\"backendNodeId\":42}}}}");

        Object saida = target.executeScript("document.body", null, false, "deep")
            .get(5, java.util.concurrent.TimeUnit.SECONDS);

        assertInstanceOf(WebElement.class, saida);
    }

    @Test
    void deepSerializedArrayOfNodesBecomesListOfWebElements() throws Exception {
        responses.add("{\"result\":{\"deepSerializedValue\":{\"type\":\"array\",\"value\":["
            + "{\"type\":\"node\",\"value\":{\"backendNodeId\":1}},"
            + "{\"type\":\"node\",\"value\":{\"backendNodeId\":2}},"
            + "{\"type\":\"string\",\"value\":\"texto\"}]}}}");

        Object saida = target.executeScript("document.querySelectorAll('div')", null, false, "deep")
            .get(5, java.util.concurrent.TimeUnit.SECONDS);

        List<?> lista = assertInstanceOf(List.class, saida);
        assertEquals(3, lista.size());
        assertInstanceOf(WebElement.class, lista.get(0));
        assertInstanceOf(WebElement.class, lista.get(1));
        assertEquals("texto", lista.get(2));
    }

    @Test
    void deepSerializedPrimitivesAreUnwrappedByType() throws Exception {
        responses.add("{\"result\":{\"deepSerializedValue\":{\"type\":\"boolean\",\"value\":true}}}");
        assertEquals(true, target.executeScript("sim", null, false, "deep")
            .get(5, java.util.concurrent.TimeUnit.SECONDS));

        responses.add("{\"result\":{\"deepSerializedValue\":{\"type\":\"number\",\"value\":3}}}");
        assertEquals(3, target.executeScript("tres", null, false, "deep")
            .get(5, java.util.concurrent.TimeUnit.SECONDS));

        responses.add("{\"result\":{\"deepSerializedValue\":{\"type\":\"number\",\"value\":2.5}}}");
        assertEquals(2.5, target.executeScript("meio", null, false, "deep")
            .get(5, java.util.concurrent.TimeUnit.SECONDS));

        responses.add("{\"result\":{\"deepSerializedValue\":{\"type\":\"string\",\"value\":\"txt\"}}}");
        assertEquals("txt", target.executeScript("txt", null, false, "deep")
            .get(5, java.util.concurrent.TimeUnit.SECONDS));
    }

    @Test
    void deepSerializedUnknownShapesFallBackToNull() throws Exception {
        responses.add("{\"result\":{\"deepSerializedValue\":{\"type\":\"odd\"}}}");
        assertNull(target.executeScript("estranho", null, false, "deep")
            .get(5, java.util.concurrent.TimeUnit.SECONDS));

        responses.add("{\"result\":{\"deepSerializedValue\":{\"type\":\"node\",\"value\":{}}}}");
        assertNull(target.executeScript("semBackend", null, false, "deep")
            .get(5, java.util.concurrent.TimeUnit.SECONDS));
    }

    @Test
    void awaitPromiseFlagIsForwardedToRuntimeEvaluate() throws Exception {
        // resposta por conteúdo de expression: evaluate internos do socket não poluem o fluxo
        server.setCustomHandler((conn, req) -> {
            int id = req.get("id").asInt();
            String method = req.path("method").asText("");
            if ("Runtime.evaluate".equals(method)
                    && req.path("params").path("expression").asText().contains("Promise.resolve")) {
                lastParams.set(req.path("params"));
                conn.send("{\"id\":" + id + ",\"result\":{\"result\":{\"value\":\"promise\"}}}");
                return;
            }
            conn.send("{\"id\":" + id + ",\"result\":{\"result\":{\"objectId\":\"ctx\"}}}");
        });

        Object saida = target.executeScript("Promise.resolve('promise')", null, true)
            .get(5, java.util.concurrent.TimeUnit.SECONDS);

        assertEquals("promise", saida);
        assertTrue(lastParams.get().path("awaitPromise").asBoolean(),
            "awaitPromise=true deve ir para o Runtime.evaluate");
    }
}
