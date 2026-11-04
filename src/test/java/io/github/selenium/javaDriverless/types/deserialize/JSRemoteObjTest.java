package io.github.selenium.javaDriverless.types.deserialize;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.NullNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import io.github.selenium.javaDriverless.types.Target;
import io.github.selenium.javaDriverless.types.TypesExceptions;

/**
 * Cobertura do parse/conversão de dados retornados pelo navegador (Runtime.callFunctionOn)
 * — superfície onde payload do CDP vira objetos Java. Payload de teste é dado, nunca instrução.
 */
class JSRemoteObjTest {

    private static final com.fasterxml.jackson.databind.ObjectMapper MAPPER =
        new com.fasterxml.jackson.databind.ObjectMapper();

    private Target target;
    private JSRemoteObj remoteObj;

    @BeforeEach
    void setUp() {
        target = mock(Target.class);
        // objId "inj.<contextId>" — formato que getContextId() espera
        remoteObj = new JSRemoteObj("inj.42", target, 7, null);
    }

    private static ObjectNode resultNode(String json) {
        try {
            return (ObjectNode) MAPPER.readTree(json);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private void stubCallFunctionOn(JsonNode response) {
        when(target.executeCdpCmd(eq("Runtime.callFunctionOn"), anyMap(), any()))
            .thenReturn(CompletableFuture.completedFuture(response));
    }

    @Test
    void getContextIdParsesSuffixOfObjId() {
        assertEquals(42, remoteObj.getContextId());
        assertNull(new JSRemoteObj("sem-ponto", target, null, null).getContextId());
    }

    @Test
    void gettersExposeIdentityFields() {
        assertEquals("inj.42", remoteObj.getObjId());
        assertEquals(target, remoteObj.getTarget());
        assertEquals(7, remoteObj.getFrameId().join());
    }

    @Test
    void isolatedExecIdIsCachedWhenProvided() {
        JSRemoteObj withIsolated = new JSRemoteObj("inj.42", target, 7, 99);
        assertEquals(99, withIsolated.getIsolatedExecId().join());
    }

    @Test
    @SuppressWarnings("unchecked")
    void isolatedExecIdCreatesWorldViaCdpWhenAbsent() {
        when(target.executeCdpCmd(eq("Page.createIsolatedWorld"), anyMap(), isNull()))
            .thenReturn(CompletableFuture.completedFuture(resultNode("{\"executionContextId\":77}")));

        Integer execId = remoteObj.getIsolatedExecId().join();

        assertEquals(77, execId);
        verify(target).executeCdpCmd(eq("Page.createIsolatedWorld"), argThat(args ->
            Integer.valueOf(7).equals(args.get("frameId"))
                && Boolean.TRUE.equals(args.get("grantUniveralAccess"))), isNull());
    }

    @Test
    void execRawUsesObjectIdFromObjIdByDefault() {
        stubCallFunctionOn(resultNode(
            "{\"result\":{\"deepSerializedValue\":{\"type\":\"number\",\"value\":13},\"subtype\":null}}"));

        Object out = remoteObj.execRaw("(function(){return 1})", null, false, "deep", 2, 5f, null, false)
            .join();

        assertEquals(13, out);
        verify(target).executeCdpCmd(eq("Runtime.callFunctionOn"), argThat(args ->
            "inj.42".equals(args.get("objectId"))
                && args.get("executionContextId") == null), any());
    }

    @Test
    void execRawPrefersExplicitExecutionContextId() {
        stubCallFunctionOn(resultNode(
            "{\"result\":{\"deepSerializedValue\":{\"type\":\"string\",\"value\":\"ok\"}}}"));

        Object out = remoteObj.execRaw("s", null, false, null, null, 5f, 123, false).join();

        assertEquals("ok", out);
        verify(target).executeCdpCmd(eq("Runtime.callFunctionOn"), argThat(args ->
            Integer.valueOf(123).equals(args.get("executionContextId"))
                && args.get("objectId") == null), any());
    }

    @Test
    @SuppressWarnings("unchecked")
    void execRawWithUniqueContextRunsInIsolatedWorld() {
        when(target.executeCdpCmd(eq("Page.createIsolatedWorld"), anyMap(), isNull()))
            .thenReturn(CompletableFuture.completedFuture(resultNode("{\"executionContextId\":31}")));
        stubCallFunctionOn(resultNode(
            "{\"result\":{\"deepSerializedValue\":{\"type\":\"boolean\",\"value\":true}}}"));

        Object out = remoteObj.execRaw("s", null, false, null, null, 5f, null, true).join();

        assertEquals(Boolean.TRUE, out);
        verify(target).executeCdpCmd(eq("Runtime.callFunctionOn"), argThat(args ->
            Integer.valueOf(31).equals(args.get("executionContextId"))), any());
    }

    @Test
    @SuppressWarnings("unchecked")
    void execRawPassesRemoteObjArgsAsObjectIdReferences() {
        stubCallFunctionOn(resultNode(
            "{\"result\":{\"deepSerializedValue\":{\"type\":\"undefined\"}}}"));

        JSRemoteObj other = new JSRemoteObj("inj.9", target, null, null);
        JSRemoteObj anonymous = new JSRemoteObj(null, target, null, null);

        remoteObj.execRaw("s", new Object[]{other, anonymous, "txt"}, false, null, null, 5f, null, false)
            .join();

        verify(target).executeCdpCmd(eq("Runtime.callFunctionOn"), argThat(args -> {
            List<Map<String, Object>> arguments = (List<Map<String, Object>>) args.get("arguments");
            return arguments.size() == 3
                && "inj.9".equals(arguments.get(0).get("objectId"))
                && arguments.get(1).containsKey("value")
                && "txt".equals(arguments.get(2).get("value"));
        }), any());
    }

    @Test
    void execRawSurfacesJsExceptionDetailsAsJSEvalException() {
        stubCallFunctionOn(resultNode("{\"exceptionDetails\":{"
            + "\"exceptionId\":3,\"text\":\"boom\",\"lineNumber\":7,\"columnNumber\":9,"
            + "\"exception\":{\"type\":\"object\",\"className\":\"TypeError\","
            + "\"description\":\"TypeError: boom at <anonymous>:7:9\","
            + "\"objectId\":\"err-1\"}}}"));

        CompletableFuture<Object> future =
            remoteObj.execRaw("s", null, false, null, null, 5f, null, false);

        Exception thrown = assertThrows(Exception.class, () -> future.join());
        TypesExceptions.JSEvalException eval =
            assertInstanceOf(TypesExceptions.JSEvalException.class, thrown.getCause());
        assertEquals("boom", eval.getText());
        assertEquals(3, eval.getExcId());
        assertEquals(7, eval.getLineNumber());
        assertEquals("TypeError", eval.getClassName());
        assertEquals("err-1", eval.getObjId());
    }

    @Test
    void parseDeepHandlesPrimitivesCollectionsNodesAndUnserializable() {
        // array com number + object com campo string + bigint não serializável
        stubCallFunctionOn(resultNode("{\"result\":{"
            + "\"objectId\":\"res-1\","
            + "\"deepSerializedValue\":{"
            + "\"type\":\"array\",\"value\":["
            + "{\"type\":\"number\",\"value\":1},"
            + "{\"type\":\"object\",\"value\":{\"k\":{\"type\":\"string\",\"value\":\"v\"}}},"
            + "{\"type\":\"bigint\",\"value\":\"12345678901234567890\"}]}}}"));

        Object out = remoteObj.execRaw("s", null, false, "deep", 4, 5f, null, false).join();

        List<?> list = assertInstanceOf(List.class, out);
        assertEquals(1, list.get(0));
        Map<?, ?> map = assertInstanceOf(Map.class, list.get(1));
        assertEquals("v", map.get("k"));
        assertEquals("JSUnserializable(type=bigint, value=\"12345678901234567890\")", list.get(2));
    }

    @Test
    void parseDeepReturnsNodeDescriptorWithIdentityAtTopLevel() {
        stubCallFunctionOn(resultNode("{\"result\":{"
            + "\"objectId\":\"node-obj-1\",\"className\":\"HTMLInputElement\","
            + "\"deepSerializedValue\":{\"type\":\"node\",\"value\":{\"backendNodeId\":55}}}}"));

        Object out = remoteObj.execRaw("s", null, false, "deep", 2, 5f, null, false).join();

        Map<?, ?> node = assertInstanceOf(Map.class, out);
        assertEquals("node", node.get("type"));
        assertEquals(55, node.get("backendNodeId"));
        assertEquals("node-obj-1", node.get("objId"));
        assertEquals("HTMLInputElement", node.get("className"));
        assertEquals(42, node.get("contextId"));
    }

    @Test
    void parseDeepOfNodeInsideArrayParsesWithoutNpe() {
        // Regressão do bug do Map.of (rejeita nulls): node dentro de array tem objId/className
        // null no caminho recursivo e agora parseia normalmente.
        stubCallFunctionOn(resultNode("{\"result\":{"
            + "\"deepSerializedValue\":{\"type\":\"array\",\"value\":["
            + "{\"type\":\"node\",\"value\":{\"backendNodeId\":55}}]}}}"));

        Object out = remoteObj.execRaw("s", null, false, "deep", 4, 5f, null, false).join();

        List<?> list = assertInstanceOf(List.class, out);
        Map<?, ?> node = assertInstanceOf(Map.class, list.get(0));
        assertEquals("node", node.get("type"));
        assertEquals(55, node.get("backendNodeId"));
    }

    @Test
    void parseDeepReturnsNullForUndefinedAndNullTypes() {
        stubCallFunctionOn(resultNode(
            "{\"result\":{\"deepSerializedValue\":{\"type\":\"undefined\"}}}"));
        assertNull(remoteObj.execRaw("s", null, false, "deep", 2, 5f, null, false).join());

        stubCallFunctionOn(resultNode(
            "{\"result\":{\"deepSerializedValue\":{\"type\":\"null\"}}}"));
        assertNull(remoteObj.execRaw("s", null, false, "deep", 2, 5f, null, false).join());
    }

    @Test
    void parseDeepFallsBackToPlainValueWhenDeepSerializedValueMissing() {
        stubCallFunctionOn(resultNode(
            "{\"result\":{\"value\":\"plain-value\"}}"));
        assertEquals("plain-value",
            remoteObj.execRaw("s", null, false, null, null, 5f, null, false).join());

        stubCallFunctionOn(resultNode("{\"result\":{}}"));
        assertNull(remoteObj.execRaw("s", null, false, null, null, 5f, null, false).join());
    }

    @Test
    void parseDeepReturnsEmptyCollectionsForNullArrayAndObjectValues() {
        stubCallFunctionOn(resultNode(
            "{\"result\":{\"deepSerializedValue\":{\"type\":\"array\",\"value\":null}}}"));
        assertEquals(List.of(),
            remoteObj.execRaw("s", null, false, "deep", 2, 5f, null, false).join());

        stubCallFunctionOn(resultNode(
            "{\"result\":{\"deepSerializedValue\":{\"type\":\"object\",\"value\":null}}}"));
        assertEquals(Map.of(),
            remoteObj.execRaw("s", null, false, "deep", 2, 5f, null, false).join());
    }

    @Test
    void execWithObjIdWrapsScriptShiftingFirstArgument() {
        stubCallFunctionOn(resultNode("{\"result\":{\"value\":null}}"));

        remoteObj.exec("return obj.foo", new Object[]{"x"}, 2, null, 5f, false).join();

        verify(target).executeCdpCmd(eq("Runtime.callFunctionOn"), argThat(args -> {
            String fn = String.valueOf(args.get("functionDeclaration"));
            return fn.startsWith("(function(...arguments){ const obj = arguments.shift(); return obj.foo})");
        }), any());
    }

    @Test
    void execWithoutObjIdWrapsScriptUsingThis() {
        JSRemoteObj anonymous = new JSRemoteObj(null, target, null, null);
        stubCallFunctionOn(resultNode("{\"result\":{\"value\":null}}"));

        anonymous.exec("return this.foo", new Object[0], 2, null, 5f, false).join();

        verify(target).executeCdpCmd(eq("Runtime.callFunctionOn"), argThat(args -> {
            String fn = String.valueOf(args.get("functionDeclaration"));
            return fn.startsWith("(function(...arguments){ const obj = this; return this.foo})");
        }), any());
    }

    @Test
    void equalsComparesOnlyObjIdPrefixAndHashCodeMatches() {
        JSRemoteObj a = new JSRemoteObj("inj.1", target, null, null);
        JSRemoteObj b = new JSRemoteObj("inj.2", target, null, null);
        JSRemoteObj anonymous = new JSRemoteObj(null, target, null, null);

        // Regressão: hashCode agora deriva do mesmo prefixo usado pelo equals
        assertEquals(a, b);
        assertEquals(a.hashCode(), b.hashCode());
        assertFalse(a.equals(anonymous));
        assertFalse(a.equals(null));
        assertFalse(a.equals("not-a-remote-obj"));
        assertTrue(a.equals(a));

        // contrato HashMap: objetos iguais caem no mesmo bucket
        Map<JSRemoteObj, String> map = new HashMap<>();
        map.put(a, "v");
        assertEquals("v", map.get(b));
    }

    @Test
    void toStringIncludesObjIdAndContext() {
        String repr = remoteObj.toString();
        assertTrue(repr.contains("inj.42"));
        assertTrue(repr.contains("42"));
    }

    @Test
    void execRawPrefersExplicitExecutionContextEvenWithUniqueContextRequested() {
        // executionContextId + uniqueContext=true: contexto explícito ganha (aviso emitido)
        stubCallFunctionOn(resultNode("{\"result\":{\"value\":1}}"));

        remoteObj.execRaw("function(){return 1}", new Object[0], false, null, 2, 5f, 33, true).join();

        verify(target).executeCdpCmd(eq("Runtime.callFunctionOn"), argThat(args ->
            Integer.valueOf(33).equals(args.get("executionContextId"))
                && !args.containsKey("objectId")), any());
    }

    @Test
    void integerBeyondLongRangeIsPreservedViaNodeToString() {
        // inteiro fora do range de long chega como BigIntegerNode: cai no caminho genérico
        stubCallFunctionOn(resultNode(
            "{\"result\":{\"value\":123456789012345678901234567890}}"));

        Object value = remoteObj.execRaw("function(){return big}", new Object[0], false, null, 2, 5f, null, false)
            .join();

        assertEquals("123456789012345678901234567890", String.valueOf(value));
    }

    @Test
    void anonymousObjectHashCodeFallsBackToClassIdentity() {
        JSRemoteObj anonymous = new JSRemoteObj(null, target, null, null);
        JSRemoteObj otherAnonymous = new JSRemoteObj(null, mock(Target.class), null, null);

        assertEquals(anonymous.hashCode(), otherAnonymous.hashCode(),
            "sem objId, hashCode é a identidade da classe");
    }
}
