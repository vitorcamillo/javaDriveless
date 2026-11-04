package io.github.selenium.javaDriverless.types;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

import com.fasterxml.jackson.databind.node.ObjectNode;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** Execução de script no elemento + parse do resultado (deep serialization) em WebElement. */
class WebElementScriptExecutorTest {

    private static final com.fasterxml.jackson.databind.ObjectMapper MAPPER =
        new com.fasterxml.jackson.databind.ObjectMapper();

    private Target target;

    @BeforeEach
    void setUp() {
        target = mock(Target.class);
    }

    private static ObjectNode json(String json) {
        try {
            return (ObjectNode) MAPPER.readTree(json);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private void stubCall(ObjectNode response) {
        when(target.executeCdpCmd(eq("Runtime.callFunctionOn"), anyMap(), any()))
            .thenReturn(CompletableFuture.completedFuture(response));
    }

    @Test
    @SuppressWarnings("unchecked")
    void executeScriptUsesCachedObjIdWithoutResolveAndReturnsByValue() {
        stubCall(json("{\"result\":{\"value\":\"script-out\"}}"));
        WebElement element = new WebElement(target, null, null, "obj-9", null, null, null, false);

        Object out = element.executeScript("return 1", null, null, false, 5f).join();

        assertEquals("script-out", out);
        verify(target, never()).executeCdpCmd(eq("DOM.resolveNode"), anyMap(), any());
        verify(target).executeCdpCmd(eq("Runtime.callFunctionOn"), argThat(args -> {
            Map<String, Object> callArgs = (Map<String, Object>) args;
            String fn = String.valueOf(callArgs.get("functionDeclaration"));
            return "obj-9".equals(callArgs.get("objectId"))
                && Boolean.TRUE.equals(callArgs.get("returnByValue"))
                && fn.contains("const obj = this")
                && fn.contains("return 1");
        }), any());
    }

    @Test
    @SuppressWarnings("unchecked")
    void executeScriptWithSerializationDisablesReturnByValue() {
        stubCall(json("{\"result\":{\"deepSerializedValue\":{\"type\":\"string\",\"value\":\"deep\"}}}"));
        WebElement element = new WebElement(target, null, null, "obj-9", null, null, null, false);

        Object out = element.executeScript("return 2", null, "deep", false, 5f).join();

        assertEquals("deep", out);
        verify(target).executeCdpCmd(eq("Runtime.callFunctionOn"), argThat(args -> {
            Map<String, Object> callArgs = (Map<String, Object>) args;
            Map<String, Object> ser = (Map<String, Object>) callArgs.get("serializationOptions");
            return Boolean.FALSE.equals(callArgs.get("returnByValue"))
                && ser != null && "deep".equals(ser.get("serialization"));
        }), any());
    }

    @Test
    void executeScriptResolvesObjIdFromNodeIdWhenMissing() {
        when(target.executeCdpCmd(eq("DOM.resolveNode"), anyMap(), any()))
            .thenReturn(CompletableFuture.completedFuture(
                json("{\"object\":{\"objectId\":\"resolved-obj\"}}")));
        stubCall(json("{\"result\":{\"value\":7}}"));
        WebElement element = new WebElement(target, null, null, null, 42, null, null, false);

        Object out = element.executeScript("return 3", null, null, false, 5f).join();

        assertEquals(7, out);
        assertEquals("resolved-obj", element.objId);
        verify(target).executeCdpCmd(eq("DOM.resolveNode"), argThat(args ->
            Integer.valueOf(42).equals(args.get("nodeId"))), any());
    }

    @Test
    @SuppressWarnings("unchecked")
    void executeScriptConvertsPrimitiveAndNonPrimitiveArgsToValues() {
        stubCall(json("{\"result\":{\"value\":null}}"));
        WebElement element = new WebElement(target, null, null, "obj-9", null, null, null, false);

        // arg é um único objeto; um array vira um único valor convertido via toString
        element.executeScript("return 4", new int[]{1}, null, false, 5f).join();

        verify(target).executeCdpCmd(eq("Runtime.callFunctionOn"), argThat(args -> {
            List<Map<String, Object>> arguments =
                (List<Map<String, Object>>) args.get("arguments");
            return arguments.size() == 1
                && String.valueOf(arguments.get(0).get("value")).startsWith("[I@");
        }), any());
    }

    @Test
    void parseResultConvertsPlainArraysAndPrimitives() {
        stubCall(json("{\"result\":{\"value\":[1,\"two\",true,null]}}"));
        WebElement element = new WebElement(target, null, null, "obj-9", null, null, null, false);

        Object out = element.executeScript("return 5", null, null, false, 5f).join();

        List<?> list = assertInstanceOf(List.class, out);
        assertEquals(1, list.get(0));
        assertEquals("two", list.get(1));
        assertEquals(Boolean.TRUE, list.get(2));
        assertNull(list.get(3));
    }

    @Test
    void parseDeepSerializedNodeBecomesWebElement() {
        stubCall(json("{\"result\":{\"deepSerializedValue\":{"
            + "\"type\":\"node\",\"value\":{\"backendNodeId\":66}}}}"));
        WebElement element = new WebElement(target, null, null, "obj-9", null, null, null, false);

        Object out = element.executeScript("return 6", null, "deep", false, 5f).join();

        WebElement webElement = assertInstanceOf(WebElement.class, out);
        assertEquals(66, webElement.getBackendNodeId().join());
    }

    @Test
    void parseDeepSerializedArrayOfNodesAndPrimitives() {
        stubCall(json("{\"result\":{\"deepSerializedValue\":{"
            + "\"type\":\"array\",\"value\":["
            + "{\"type\":\"node\",\"value\":{\"backendNodeId\":71}},"
            + "{\"type\":\"number\",\"value\":5}]}}}"));
        WebElement element = new WebElement(target, null, null, "obj-9", null, null, null, false);

        Object out = element.executeScript("return 7", null, "deep", false, 5f).join();

        List<?> list = assertInstanceOf(List.class, out);
        WebElement first = assertInstance(WebElement.class, list.get(0));
        assertEquals(71, first.getBackendNodeId().join());
        assertEquals(5, list.get(1));
    }

    @Test
    void parseResultWithoutResultFieldReturnsNull() {
        stubCall(json("{}"));
        WebElement element = new WebElement(target, null, null, "obj-9", null, null, null, false);

        assertNull(element.executeScript("return 8", null, null, false, 5f).join());
    }

    @Test
    void executeScriptResolvesObjIdFromBackendNodeIdWhenNodeIdIsMissing() {
        when(target.executeCdpCmd(eq("DOM.resolveNode"), anyMap(), any()))
            .thenReturn(CompletableFuture.completedFuture(
                json("{\"object\":{\"objectId\":\"via-backend\"}}")));
        stubCall(json("{\"result\":{\"value\":1}}"));
        WebElement element = new WebElement(target, null, null, null, null, 77, null, false);

        element.executeScript("return 1", null, null, false, 5f).join();

        assertEquals("via-backend", element.objId);
        verify(target).executeCdpCmd(eq("DOM.resolveNode"), argThat(args ->
            Integer.valueOf(77).equals(args.get("backendNodeId"))), any());
    }

    @Test
    void elementWithoutAnyIdentifierSkipsNodeResolution() {
        stubCall(json("{}"));
        WebElement element = new WebElement(target, null, null, null, null, null, null, false);

        assertNull(element.executeScript("return 1", null, null, false, 5f).join());
        verify(target, never()).executeCdpCmd(eq("DOM.resolveNode"), anyMap(), any());
    }

    @Test
    @SuppressWarnings("unchecked")
    void conversionCoversLongsDoublesAndPlainNestedObjects() {
        stubCall(json("{\"result\":{\"value\":[9007199254740993,2.5,{\"k\":1}]}}"));
        WebElement element = new WebElement(target, null, null, "obj-9", null, null, null, false);

        List<Object> out = (List<Object>) element.executeScript("return 1", null, null, false, 5f).join();

        assertEquals(9007199254740993L, out.get(0));
        assertEquals(2.5, out.get(1));
        // objeto sem backendNodeId não pode virar WebElement: vira texto JSON
        assertEquals("{\"k\":1}", out.get(2));
    }

    @Test
    void elementBridgesPassTheElementAsFirstArgumentToTheTargetScriptOps() {
        WebElement element = new WebElement(target, null, null, "obj-9", null, null, null, false);
        java.util.List<Object[]> capturados = new java.util.ArrayList<>();
        // invocação expandida: (script, flag, serialização, elemento, arg) — um matcher por slot
        when(target.executeRawScript(any(), org.mockito.ArgumentMatchers.anyBoolean(),
                any(), any(), any()))
            .thenAnswer(inv -> {
                capturados.add(inv.getArguments());
                return CompletableFuture.completedFuture("raw");
            });
        when(target.executeAsyncScript(any(), org.mockito.ArgumentMatchers.anyInt(),
                any(), any(), any()))
            .thenAnswer(inv -> {
                capturados.add(inv.getArguments());
                return CompletableFuture.completedFuture("async");
            });
        when(target.evalAsync(any(), org.mockito.ArgumentMatchers.anyFloat(),
                org.mockito.ArgumentMatchers.anyBoolean(), any(), any()))
            .thenAnswer(inv -> {
                capturados.add(inv.getArguments());
                return CompletableFuture.completedFuture("eval");
            });

        assertEquals("raw", element.executeRawScript("fn", true, 1).join());
        assertEquals("async", element.executeAsyncScript("fn", 2, 1).join());
        assertEquals("async", element.evalAsync("fn", 2, 1).join()); // evalAsync do elemento usa callFunctionOn

        // o próprio elemento entra como primeiro argumento da função remota
        org.junit.jupiter.api.Assertions.assertTrue(java.util.Arrays.asList(capturados.get(0)).contains(element));
        org.junit.jupiter.api.Assertions.assertTrue(java.util.Arrays.asList(capturados.get(1)).contains(element));
        org.junit.jupiter.api.Assertions.assertTrue(java.util.Arrays.asList(capturados.get(2)).contains(element));
    }

    @Test
    void parseDeepSerializedWithoutTypeReturnsNull() {
        stubCall(json("{\"result\":{\"deepSerializedValue\":{\"value\":\"x\"}}}"));
        WebElement element = new WebElement(target, null, null, "obj-9", null, null, null, false);

        assertNull(element.executeScript("return 9", null, "deep", false, 5f).join());
    }

    private static <T> T assertInstance(Class<T> type, Object value) {
        assertTrue(type.isInstance(value), "expected " + type + " but was " + value);
        return type.cast(value);
    }
}
