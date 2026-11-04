package io.github.selenium.javaDriverless.types;

import static org.junit.jupiter.api.Assertions.assertEquals;
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

import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

import com.fasterxml.jackson.databind.node.ObjectNode;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** Operações de DOM do elemento via CDP nativo (outerHTML, listeners, atributos, arquivos). */
class WebElementDomTest {

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
    void getSourcePrefersNodeIdReference() {
        stub("DOM.getOuterHTML", json("{\"outerHTML\":\"<div>hi</div>\"}"));
        WebElement element = new WebElement(target, null, null, "obj-1", 42, null, null, false);

        assertEquals("<div>hi</div>", element.getSource().join());

        verify(target).executeCdpCmd(eq("DOM.getOuterHTML"), argThat(args ->
            Integer.valueOf(42).equals(args.get("nodeId"))), any());
    }

    @Test
    void getSourceFallsBackToObjectIdThenBackendNodeId() {
        stub("DOM.getOuterHTML", json("{\"outerHTML\":\"<a/>\"}"));

        WebElement byObjId = new WebElement(target, null, null, "obj-2", null, null, null, false);
        assertEquals("<a/>", byObjId.getSource().join());
        verify(target).executeCdpCmd(eq("DOM.getOuterHTML"), argThat(args ->
            "obj-2".equals(args.get("objectId"))), any());

        WebElement byBackend = new WebElement(target, null, null, null, null, 33, null, false);
        assertEquals("<a/>", byBackend.getSource().join());
        verify(target).executeCdpCmd(eq("DOM.getOuterHTML"), argThat(args ->
            Integer.valueOf(33).equals(args.get("backendNodeId"))), any());
    }

    @Test
    void getSourceMapsMissingNodeErrorToStaleReference() {
        when(target.executeCdpCmd(eq("DOM.getOuterHTML"), anyMap(), any()))
            .thenReturn(CompletableFuture.failedFuture(
                new RuntimeException("Could not find node with given id")));
        WebElement element = new WebElement(target, null, null, "obj-1", 42, null, null, false);

        Exception thrown = assertThrows(Exception.class, () -> element.getSource().join());
        assertTrue(thrown.getCause() instanceof WebElement.StaleElementReferenceException);
    }

    @Test
    void setSourceSendsOuterHTMLWithNodeReference() {
        stub("DOM.setOuterHTML", json("{}"));
        WebElement element = new WebElement(target, null, null, "obj-1", 42, null, null, false);

        element.setSource("<span>new</span>").join();

        verify(target).executeCdpCmd(eq("DOM.setOuterHTML"), argThat(args ->
            "<span>new</span>".equals(args.get("outerHTML"))
                && Integer.valueOf(42).equals(args.get("nodeId"))), any());
    }

    @Test
    @SuppressWarnings("unchecked")
    void getListenersParsesListenerObjects() {
        stub("DOMDebugger.getEventListeners",
            json("{\"listeners\":[{\"type\":\"click\",\"useCapture\":false,"
                + "\"handler\":{\"type\":\"function\"}}]}"));
        WebElement element = new WebElement(target, null, null, "obj-1", null, null, null, false);

        List<Map<String, Object>> listeners = element.getListeners(2).join();

        assertEquals(1, listeners.size());
        assertEquals("click", ((com.fasterxml.jackson.databind.JsonNode) listeners.get(0).get("type")).asText());
        verify(target).executeCdpCmd(eq("DOMDebugger.getEventListeners"), argThat(args ->
            "obj-1".equals(args.get("objectId"))
                && Integer.valueOf(2).equals(args.get("depth"))
                && Boolean.TRUE.equals(args.get("pierce"))), any());
    }

    @Test
    void removeUsesNativeRemoveNode() {
        stub("DOM.removeNode", json("{}"));
        WebElement element = new WebElement(target, null, null, "obj-1", 42, null, null, false);

        element.remove().join();

        verify(target).executeCdpCmd(eq("DOM.removeNode"), argThat(args ->
            Integer.valueOf(42).equals(args.get("nodeId"))), any());
    }

    @Test
    void highlightShowsAndHidesOverlay() {
        stub("Overlay.highlightNode", json("{}"));
        stub("Overlay.hideHighlight", json("{}"));
        WebElement element = new WebElement(target, null, null, "obj-1", 42, null, null, false);

        element.highlight(true).join();
        verify(target).executeCdpCmd(eq("Overlay.highlightNode"), argThat(args -> {
            Map<String, Object> config = (Map<String, Object>) args.get("highlightConfig");
            return Integer.valueOf(42).equals(args.get("nodeId")) && config != null;
        }), any());

        element.highlight(false).join();
        verify(target).executeCdpCmd(eq("Overlay.hideHighlight"), isNull(), any());
    }

    @Test
    void setFileDelegatesToSetFilesWithSingletonList() {
        stub("DOM.setFileInputFiles", json("{}"));
        WebElement element = new WebElement(target, null, null, "obj-1", 42, null, null, false);

        element.setFile("/tmp/report.pdf").join();

        verify(target).executeCdpCmd(eq("DOM.setFileInputFiles"), argThat(args ->
            List.of("/tmp/report.pdf").equals(args.get("files"))), any());
    }

    @Test
    void setFilesSendsAllPaths() {
        stub("DOM.setFileInputFiles", json("{}"));
        WebElement element = new WebElement(target, null, null, "obj-1", 42, null, null, false);

        element.setFiles(List.of("/a.png", "/b.png")).join();

        verify(target).executeCdpCmd(eq("DOM.setFileInputFiles"), argThat(args ->
            List.of("/a.png", "/b.png").equals(args.get("files"))), any());
    }

    @Test
    void getDomAttributesParsesPairsAndToleratesDanglingName() {
        stub("DOM.getAttributes", json("{\"attributes\":[\"id\",\"x\",\"data-orphan\"]}"));
        WebElement element = new WebElement(target, null, null, "obj-1", 42, null, null, false);

        Map<String, String> attrs = element.getDomAttributes().join();

        assertEquals("x", attrs.get("id"));
        assertEquals("", attrs.get("data-orphan"));
    }

    @Test
    void getAndSetDomAttributeRoundTrip() {
        stub("DOM.getAttributes", json("{\"attributes\":[\"role\",\"button\"]}"));
        stub("DOM.setAttributeValue", json("{}"));
        WebElement element = new WebElement(target, null, null, "obj-1", 42, null, null, false);

        assertEquals("button", element.getDomAttribute("role").join());
        assertNull(element.getDomAttribute("missing").join());

        element.setDomAttribute("aria-hidden", "true").join();
        verify(target).executeCdpCmd(eq("DOM.setAttributeValue"), argThat(args ->
            "aria-hidden".equals(args.get("name"))
                && "true".equals(args.get("value"))), any());
    }

    @Test
    void ariaRoleAndAccessibleNameMapToRoleAndAriaLabelAttributes() {
        stub("DOM.getAttributes",
            json("{\"attributes\":[\"role\",\"checkbox\",\"aria-label\",\"Aceitar termos\"]}"));
        WebElement element = new WebElement(target, null, null, "obj-1", 42, null, null, false);

        assertEquals("checkbox", element.getAriaRole().join());
        assertEquals("Aceitar termos", element.getAccessibleName().join());
    }
}
