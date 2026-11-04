package io.github.selenium.javaDriverless.types;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertFalse;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BiConsumer;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.java_websocket.WebSocket;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import io.github.selenium.javaDriverless.cdp.FakeCdpServer;
import io.github.selenium.javaDriverless.cdp.exceptions.CDPException;

class TargetScriptArgsTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private FakeCdpServer server;
    private Target target;
    private volatile String scriptMode = "ok";
    private final AtomicInteger globalLookups = new AtomicInteger();
    private final AtomicInteger scriptEvals = new AtomicInteger();
    private final AtomicReference<JsonNode> lastCall = new AtomicReference<>();
    private final AtomicReference<String> lastExpression = new AtomicReference<>("");

    @BeforeEach
    void setUp() throws InterruptedException {
        server = new FakeCdpServer();
        server.startAndAwaitReady();
        globalLookups.set(0);
        scriptEvals.set(0);
        lastCall.set(null);
        lastExpression.set("");

        BiConsumer<WebSocket, JsonNode> handler = (conn, req) -> {
            int id = req.path("id").asInt();
            String method = req.path("method").asText("");
            JsonNode params = req.path("params");
            String expression = params.path("expression").asText("");
            if ("Page.enable".equals(method)) {
                conn.send("{\"id\":" + id + ",\"result\":{}}");
            } else if ("Runtime.evaluate".equals(method) && "globalThis".equals(expression)) {
                globalLookups.incrementAndGet();
                conn.send("{\"id\":" + id + ",\"result\":{\"result\":{\"objectId\":\"glob-1\"}}}");
            } else if ("Runtime.evaluate".equals(method)) {
                lastExpression.set(expression);
                if ("syntaxError".equals(scriptMode)) {
                    conn.send("{\"id\":" + id + ",\"result\":{\"result\":{},"
                        + "\"exceptionDetails\":{\"text\":\"SyntaxError: invalid\"}}}");
                } else if ("staleIsolatedThenOk".equals(scriptMode)
                        && scriptEvals.getAndIncrement() == 0) {
                    conn.send("{\"id\":" + id + ",\"error\":{\"code\":-32000,"
                        + "\"message\":\"Cannot find context with specified id\"}}");
                } else if ("staleThenOk".equals(scriptMode)
                        && globalLookups.get() == 1) {
                    conn.send("{\"id\":" + id + ",\"error\":{\"code\":-32000,"
                        + "\"message\":\"Cannot find context with specified id\"}}");
                } else {
                    conn.send("{\"id\":" + id
                        + ",\"result\":{\"result\":{\"value\":\"evaluated\"}}}");
                }
            } else if ("Runtime.callFunctionOn".equals(method)) {
                lastCall.set(params.deepCopy());
                conn.send("{\"id\":" + id
                    + ",\"result\":{\"result\":{\"value\":\"called\"}}}");
            } else {
                conn.send("{\"id\":" + id + ",\"result\":{}}");
            }
        };
        server.setCustomHandler(handler);
        target = new Target("127.0.0.1:" + server.port(), "tid-script-args", null, null,
            false, 5f, null, false, 10 * 1024 * 1024);
        target.init().join();
    }

    @AfterEach
    void tearDown() {
        try {
            if (target.getSocket() != null) target.getSocket().close();
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
    void executeScriptPassesArgumentsAsJsonArray() throws Exception {
        assertEquals("evaluated", target.executeScript("return arguments[0]",
            new Object[] {"PROBE-123"}, false).join());
        String expectedLiteral = MAPPER.writeValueAsString(
            MAPPER.writeValueAsString(new Object[] {"PROBE-123"}));
        assertTrue(lastExpression()
            .contains(".apply(globalThis, JSON.parse(" + expectedLiteral + "))"));
    }

    @Test
    void executeScriptEscapesArgumentTextAsJson() throws Exception {
        String arg = "quote\" </script> ); alert(1); //";
        String expectedLiteral = MAPPER.writeValueAsString(
            MAPPER.writeValueAsString(new Object[] {arg}));
        target.executeScript("return arguments[0]", new Object[] {arg}, false).join();
        assertTrue(lastExpression()
            .contains(".apply(globalThis, JSON.parse(" + expectedLiteral + "))"));
    }

    @Test
    void executeScriptParsesArgumentsAsJsonToKeepProtoKeyAsOwnProperty() throws Exception {
        Map<String, Object> protoKey = new HashMap<>();
        protoKey.put("__proto__", Map.of("admin", true));

        assertEquals("evaluated", target.executeScript("return arguments[0]",
            new Object[] {protoKey}, false).join());

        String expression = lastExpression();
        String expectedLiteral = MAPPER.writeValueAsString(
            MAPPER.writeValueAsString(new Object[] {protoKey}));
        // em literal JavaScript a chave "__proto__" mudaria Object.prototype;
        // via JSON.parse ela permanece propriedade própria do objeto recebido
        assertTrue(expression
            .contains(".apply(globalThis, JSON.parse(" + expectedLiteral + "))"));
        assertFalse(expression.contains(".apply(globalThis, [{\"__proto__\""));
    }

    @Test
    void webElementArgumentsAreRejected() {
        WebElement element = new WebElement(target, null, null, null, null, 1, null, false);
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
            () -> target.executeScript("return arguments[0]", new Object[] {element}, false));
        assertEquals("Argumento WebElement não suportado em executeScript da página; use WebElement.executeScript",
            error.getMessage());
    }

    @Test
    void rawScriptPassesWebElementObjectIdAsCallArgument() {
        WebElement element = new WebElement(target, null, null, "obj-9", null, null, null, false);

        assertEquals("called", target.executeRawScript("function(el){return el.id}", false,
            null, element).join());

        assertEquals(1, lastCall.get().path("arguments").size());
        assertEquals("obj-9", lastCall.get().path("arguments").path(0).path("objectId").asText());
        assertFalse(lastCall.get().path("arguments").path(0).has("value"));
    }

    @Test
    void rawScriptRejectsWebElementWithoutResolvedObjectId() {
        WebElement element = new WebElement(target, null, null, null, null, 1, null, false);

        Exception error = assertThrows(Exception.class,
            () -> target.executeRawScript("function(el){return el.id}", false, null, element).join());
        Throwable rootCause = error;
        while (rootCause.getCause() != null) rootCause = rootCause.getCause();
        assertTrue(rootCause instanceof IllegalArgumentException);
        assertEquals("WebElement sem objectId resolvido; use WebElement.executeRawScript",
            rootCause.getMessage());
    }

    @Test
    void executeScriptWithEmptyArgumentsUsesDirectExpression() {
        target.executeScript("return 1", new Object[0], false).join();

        assertEquals("(function(){return 1})()", lastExpression());
    }

    @Test
    void rawAndAsyncScriptsPassCallArgumentsAndGlobalObjectId() {
        assertEquals("called", target.executeRawScript("function(x) { return x; }", false,
            null, "x").join());
        assertEquals("x", lastCall.get().path("arguments").path(0).path("value").asText());
        assertEquals("glob-1", lastCall.get().path("objectId").asText());

        assertEquals("called", target.executeAsyncScript("return arguments[0]", 2,
            null, "x").join());
        assertEquals("x", lastCall.get().path("arguments").path(0).path("value").asText());
        assertEquals("glob-1", lastCall.get().path("objectId").asText());
    }

    @Test
    void rawScriptWithoutArgumentsOmitsArgumentsField() {
        target.executeRawScript("function() { return 1; }", false, null).join();

        assertFalse(lastCall.get().has("arguments"));
    }

    @Test
    void evalAsyncSurfacesJavascriptErrorWithoutRetryingUntilTimeout() {
        scriptMode = "syntaxError";
        long started = System.nanoTime();
        RuntimeException error = assertThrows(RuntimeException.class,
            () -> target.evalAsync("invalid(", 3f, false).join());
        long elapsedMillis = (System.nanoTime() - started) / 1_000_000;
        assertTrue(error.getMessage().contains("JavaScript error: SyntaxError"));
        assertTrue(elapsedMillis < 1000, "evalAsync levou " + elapsedMillis + " ms");
    }

    @Test
    void evalAsyncRetriesStaleContextAndRefreshesGlobalThis() {
        scriptMode = "staleThenOk";
        assertEquals("evaluated", target.evalAsync("1", 3f, false).join());
        assertEquals(2, globalLookups.get());
    }

    @Test
    void evalAsyncStaleRetryWithIsolatedContextEvictsIsolatedCacheKey() {
        target.isolatedContextId = "ctx-isolated";
        target.globalThis.put("ctx-isolated", new HashMap<>(Map.of("objectId", "stale-glob")));
        target.globalThis.put("default", new HashMap<>(Map.of("objectId", "default-glob")));
        scriptMode = "staleIsolatedThenOk";

        assertEquals("evaluated", target.evalAsync("1", 3f, true).join());

        // retry com contexto isolado deve evictar apenas "ctx-isolated"; a entrada
        // "default" do cache não pode ser removida por engano
        assertEquals("glob-1",
            ((Map<?, ?>) target.globalThis.get("ctx-isolated")).get("objectId"));
        assertEquals("default-glob",
            ((Map<?, ?>) target.globalThis.get("default")).get("objectId"));
        assertEquals(1, globalLookups.get());
    }

    @Test
    void protocolClassifiesKnownStaleMessagesAndHidesUnknownOnes() {
        assertEquals(CDPException.Kind.STALE_CONTEXT,
            CDPException.fromProtocolError(-32000, "Execution context was destroyed.").getKind());
        assertEquals(CDPException.Kind.COMMAND_REJECTED,
            CDPException.fromProtocolError(-32000, "some other Chrome message").getKind());
    }

    private String lastExpression() {
        return lastExpression.get();
    }
}
