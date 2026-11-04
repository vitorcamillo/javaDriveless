package io.github.selenium.javaDriverless.types;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
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
import java.util.concurrent.CompletableFuture;

import com.fasterxml.jackson.databind.node.NullNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** Resolução de identidade de nó (objId <-> nodeId <-> backendNodeId) via CDP nativo. */
class WebElementNodeTest {

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

    private void stub(String cmd, ObjectNode response) {
        when(target.executeCdpCmd(eq(cmd), anyMap(), any()))
            .thenReturn(CompletableFuture.completedFuture(response));
    }

    @Test
    void describeUsesDepthOneAndPierce() {
        stub("DOM.describeNode", json("{\"node\":{\"nodeName\":\"div\"}}"));
        WebElement element = new WebElement(target, null, null, "obj-1", 42, null, null, false);

        element.describe();

        verify(target).executeCdpCmd(eq("DOM.describeNode"), argThat(args ->
            Integer.valueOf(1).equals(args.get("depth"))
                && Boolean.TRUE.equals(args.get("pierce"))
                && Integer.valueOf(42).equals(args.get("nodeId"))), any());
    }

    @Test
    void getFrameIdAndDocumentUrlComeFromDescription() {
        stub("DOM.describeNode",
            json("{\"node\":{\"frameId\":\"F1\",\"documentURL\":\"https://example.test/page\"}}"));
        WebElement element = new WebElement(target, null, null, "obj-1", 42, null, null, false);

        assertEquals("F1", element.getFrameId().join());
        assertEquals("https://example.test/page", element.getDocumentUrl().join());
    }

    @Test
    void getBackendNodeIdUsesKnownValueWithoutCdp() {
        WebElement element = new WebElement(target, null, null, null, null, 77, null, false);

        assertEquals(77, element.getBackendNodeId().join());
        verify(target, never()).executeCdpCmd(any(), anyMap(), any());
    }

    @Test
    void getBackendNodeIdResolvesAndCachesFromDescription() {
        stub("DOM.describeNode", json("{\"node\":{\"backendNodeId\":88}}"));
        WebElement element = new WebElement(target, null, null, "obj-1", 42, null, null, false);

        assertEquals(88, element.getBackendNodeId().join());
        // segunda leitura usa o cache em memória, sem nova chamada CDP
        assertEquals(88, element.getBackendNodeId().join());
        verify(target, org.mockito.Mockito.times(1))
            .executeCdpCmd(eq("DOM.describeNode"), anyMap(), any());
    }

    @Test
    void getContextIdReadsInternalContextProperty() {
        stub("Runtime.getProperties", json("{\"internalProperties\":["
            + "{\"name\":\"[[ContextId]]\",\"value\":{\"value\":15}}]}"));
        WebElement element = new WebElement(target, null, null, "obj-1", null, null, null, false);

        assertEquals(15, element.getContextId().join());
    }

    @Test
    void getContextIdFallsBackToIsolatedExecIdThenZero() {
        stub("Runtime.getProperties", json("{\"internalProperties\":[]}"));

        WebElement withIsolated = new WebElement(target, null, 9, "obj-1", null, null, null, false);
        assertEquals(9, withIsolated.getContextId().join());

        WebElement plain = new WebElement(target, null, null, "obj-1", null, null, null, false);
        assertEquals(0, plain.getContextId().join());
    }

    @Test
    void getObjIdUsesCachedValueFirst() {
        WebElement element = new WebElement(target, null, null, "cached-obj", 42, null, null, false);

        assertEquals("cached-obj", element.getObjId().join());
        verify(target, never()).executeCdpCmd(any(), anyMap(), any());
    }

    @Test
    void getObjIdResolvesByBackendNodeIdWhenPresent() {
        stub("DOM.resolveNode",
            json("{\"object\":{\"objectId\":\"resolved-1\",\"className\":\"HTMLDivElement\"}}"));
        WebElement element = new WebElement(target, 3, null, null, null, 42, 7, false);

        assertEquals("resolved-1", element.getObjId().join());
        verify(target).executeCdpCmd(eq("DOM.resolveNode"), argThat(args ->
            Integer.valueOf(42).equals(args.get("backendNodeId"))
                && Integer.valueOf(7).equals(args.get("executionContextId"))), any());
        assertEquals("resolved-1", element.objId);
        assertEquals("HTMLDivElement", element.className);
    }

    @Test
    void getObjIdResolvesByNodeIdAsFallback() {
        stub("DOM.resolveNode", json("{\"object\":{\"objectId\":\"by-node-id\"}}"));
        WebElement element = new WebElement(target, null, null, null, 33, null, null, false);

        assertEquals("by-node-id", element.getObjId().join());
        verify(target).executeCdpCmd(eq("DOM.resolveNode"), argThat(args ->
            Integer.valueOf(33).equals(args.get("nodeId"))), any());
    }

    @Test
    void getObjIdWithoutAnyIdFailsWithIllegalState() {
        WebElement element = new WebElement(target, null, null, null, null, null, null, false);

        Exception thrown = assertThrows(Exception.class, () -> element.getObjId().join());
        assertTrue(thrown.getCause() instanceof IllegalStateException);
    }

    @Test
    void getObjIdMapsMissingNodeErrorToStaleElementReference() {
        when(target.executeCdpCmd(eq("DOM.resolveNode"), anyMap(), any()))
            .thenReturn(CompletableFuture.failedFuture(
                new RuntimeException("No node with given id found")));
        WebElement element = new WebElement(target, null, null, null, 33, null, null, false);

        Exception thrown = assertThrows(Exception.class, () -> element.getObjId().join());
        assertTrue(thrown.getCause() instanceof WebElement.StaleElementReferenceException);
    }

    @Test
    void getNodeIdUsesCachedValueFirst() {
        WebElement element = new WebElement(target, null, null, null, 42, null, null, false);

        assertEquals(42, element.getNodeId().join());
        verify(target, never()).executeCdpCmd(any(), anyMap(), any());
    }

    @Test
    void getNodeIdRequestsNodeFromObjIdAndCaches() {
        when(target.executeCdpCmd(eq("DOM.resolveNode"), anyMap(), any()))
            .thenReturn(CompletableFuture.completedFuture(json("{\"object\":{\"objectId\":\"o-1\"}}")));
        stub("DOM.requestNode", json("{\"nodeId\":501}"));
        WebElement element = new WebElement(target, null, null, null, null, 90, null, false);

        assertEquals(501, element.getNodeId().join());
        assertEquals(501, element.getNodeId().join());
        verify(target, org.mockito.Mockito.times(1))
            .executeCdpCmd(eq("DOM.requestNode"), anyMap(), any());
    }

    @Test
    void treeNavigationBuildsChildrenFromBackendNodeIds() {
        stub("DOM.describeNode", json("{\"node\":{"
            + "\"parentNode\":{\"backendNodeId\":11},"
            + "\"children\":[{\"backendNodeId\":12},{\"backendNodeId\":13}],"
            + "\"shadowRoots\":[{\"backendNodeId\":14}],"
            + "\"contentDocument\":{\"backendNodeId\":15}}}"));
        WebElement element = new WebElement(target, 3, null, "obj-1", 42, null, null, false);

        WebElement parent = element.getParent().join();
        assertNotNull(parent);

        List<WebElement> children = element.getChildren().join();
        assertEquals(2, children.size());
        assertEquals(12, children.get(0).getBackendNodeId().join());
        assertEquals(13, children.get(1).getBackendNodeId().join());

        List<WebElement> shadowRoots = element.getShadowRoots().join();
        assertEquals(1, shadowRoots.size());
        assertEquals(14, shadowRoots.get(0).getBackendNodeId().join());
        assertEquals(14, element.getShadowRoot().join().getBackendNodeId().join());

        WebElement contentDoc = element.getContentDocument().join();
        assertNotNull(contentDoc);
        assertEquals(15, contentDoc.getBackendNodeId().join());
    }

    @Test
    void treeNavigationReturnsNullWhenFieldsAbsent() {
        stub("DOM.describeNode", json("{\"node\":{}}"));
        WebElement element = new WebElement(target, null, null, "obj-1", 42, null, null, false);

        assertNull(element.getParent().join());
        assertNull(element.getShadowRoot().join());
        assertNull(element.getContentDocument().join());
        assertEquals(List.of(), element.getChildren().join());
        assertEquals(List.of(), element.getShadowRoots().join());
    }
}
