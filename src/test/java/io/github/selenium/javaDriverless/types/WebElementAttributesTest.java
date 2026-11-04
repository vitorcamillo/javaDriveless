package io.github.selenium.javaDriverless.types;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.concurrent.CompletableFuture;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.NullNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Leitura de atributos/estado do elemento via primitivas CDP nativas (describeNode/getAttributes/
 * Accessibility/CSS). Payload do CDP é dado de teste; nenhum JS é injetado.
 */
class WebElementAttributesTest {

    private static final com.fasterxml.jackson.databind.ObjectMapper MAPPER =
        new com.fasterxml.jackson.databind.ObjectMapper();

    private Target target;
    private WebElement element;

    @BeforeEach
    void setUp() {
        target = mock(Target.class);
        // CSS.enable / Accessibility.enable são pré-requisito dos helpers de estilo/AX
        when(target.executeCdpCmd(eq("CSS.enable"), anyMap(), any()))
            .thenReturn(CompletableFuture.completedFuture(NullNode.getInstance()));
        when(target.executeCdpCmd(eq("Accessibility.enable"), anyMap(), any()))
            .thenReturn(CompletableFuture.completedFuture(NullNode.getInstance()));
        element = new WebElement(target, null, null, "obj-1", 42, null, null, false);
    }

    private static ObjectNode json(String json) {
        try {
            return (ObjectNode) MAPPER.readTree(json);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private void stub(String cmd, JsonNode response) {
        when(target.executeCdpCmd(eq(cmd), anyMap(), any()))
            .thenReturn(CompletableFuture.completedFuture(response));
    }

    @Test
    void getTextWalksTextNodesOfSubtree() {
        stub("DOM.describeNode", json("{\"node\":{\"nodeType\":1,\"children\":["
            + "{\"nodeType\":3,\"nodeValue\":\"Hello \"},"
            + "{\"nodeType\":1,\"children\":[{\"nodeType\":3,\"nodeValue\":\"World\"}]}]}}"));

        assertEquals("Hello World", element.getText().join());
    }

    @Test
    void getTextReturnsEmptyOnError() {
        when(target.executeCdpCmd(eq("DOM.describeNode"), anyMap(), any()))
            .thenReturn(CompletableFuture.failedFuture(new IllegalStateException("node gone")));

        assertEquals("", element.getText().join());
    }

    @Test
    void isDisplayedFalseWhenDisplayNoneOrHiddenOrZeroOpacity() {
        stub("CSS.getComputedStyleForNode", json("{\"computedStyle\":["
            + "{\"name\":\"display\",\"value\":\"none\"}]}"));
        assertFalse(element.isDisplayed().join());

        stub("CSS.getComputedStyleForNode", json("{\"computedStyle\":["
            + "{\"name\":\"visibility\",\"value\":\"hidden\"}]}"));
        assertFalse(element.isDisplayed().join());

        stub("CSS.getComputedStyleForNode", json("{\"computedStyle\":["
            + "{\"name\":\"opacity\",\"value\":\"0\"}]}"));
        assertFalse(element.isDisplayed().join());
    }

    @Test
    void isDisplayedRequiresPositiveBoxAreaWhenStyleAllows() {
        stub("CSS.getComputedStyleForNode", json("{\"computedStyle\":[{\"name\":\"color\",\"value\":\"red\"}]}"));
        stub("DOM.getBoxModel", json("{\"model\":{\"border\":[0,0,0,0,0,0,0,0]}}"));
        assertFalse(element.isDisplayed().join());

        stub("DOM.getBoxModel", json("{\"model\":{\"border\":[0,0,10,0,10,20,0,20]}}"));
        assertTrue(element.isDisplayed().join());
    }

    @Test
    void isDisplayedLooseIgnoresBoxAndOpacity() {
        stub("CSS.getComputedStyleForNode", json("{\"computedStyle\":["
            + "{\"name\":\"opacity\",\"value\":\"0\"},"
            + "{\"name\":\"color\",\"value\":\"red\"}]}"));

        assertTrue(element.isDisplayedLoose().join());
    }

    @Test
    void isDisplayedLooseStillRejectsDisplayNone() {
        stub("CSS.getComputedStyleForNode", json("{\"computedStyle\":["
            + "{\"name\":\"display\",\"value\":\"none\"}]}"));

        assertFalse(element.isDisplayedLoose().join());
    }

    @Test
    void isEnabledCombinesAxAndAttributeState() {
        // AX reporta disabled=true -> desabilitado
        stub("Accessibility.getPartialAXTree", json("{\"nodes\":[{\"properties\":["
            + "{\"name\":\"disabled\",\"value\":{\"value\":\"true\"}}]}]}"));
        assertFalse(element.isEnabled().join());

        // sem AX disabled e sem atributo disabled -> habilitado
        stub("Accessibility.getPartialAXTree", json("{\"nodes\":[{\"properties\":[]}]}"));
        stub("DOM.getAttributes", json("{\"attributes\":[\"id\",\"x\"]}"));
        assertTrue(element.isEnabled().join());
    }

    @Test
    void isSelectedReadsCheckedThenSelectedAxProps() {
        stub("Accessibility.getPartialAXTree", json("{\"nodes\":[{\"properties\":["
            + "{\"name\":\"checked\",\"value\":{\"value\":\"true\"}}]}]}"));
        assertTrue(element.isSelected().join());

        stub("Accessibility.getPartialAXTree", json("{\"nodes\":[{\"properties\":["
            + "{\"name\":\"selected\",\"value\":{\"value\":\"true\"}}]}]}"));
        assertTrue(element.isSelected().join());

        stub("Accessibility.getPartialAXTree", json("{\"nodes\":[{\"properties\":[]}]}"));
        assertFalse(element.isSelected().join());
    }

    @Test
    void getTagNameLowercasesNodeName() {
        stub("DOM.describeNode", json("{\"node\":{\"nodeName\":\"INPUT\"}}"));
        assertEquals("input", element.getTagName().join());
    }

    @Test
    void getAttributeReadsPairwiseAttributeArray() {
        stub("DOM.getAttributes", json("{\"attributes\":[\"id\",\"login\",\"data-x\",\"1\"]}"));

        assertEquals("login", element.getAttribute("id").join());
        assertEquals("1", element.getAttribute("data-x").join());
        assertNull(element.getAttribute("absent").join());
    }

    @Test
    void getPropertyMapsKnownNamesToNativeSources() {
        // value -> AX value
        stub("Accessibility.getPartialAXTree", json("{\"nodes\":[{\"value\":{\"value\":\"typed-text\"}}]}"));
        assertEquals("typed-text", element.getProperty("value").join());

        // textContent -> describeNode
        stub("DOM.describeNode", json("{\"node\":{\"nodeType\":3,\"nodeValue\":\"body text\"}}"));
        assertEquals("body text", element.getProperty("textContent").join());

        // tagName -> uppercase
        stub("DOM.describeNode", json("{\"node\":{\"nodeName\":\"div\"}}"));
        assertEquals("DIV", element.getProperty("tagName").join());

        // checked -> boolean do AX
        stub("Accessibility.getPartialAXTree", json("{\"nodes\":[{\"properties\":["
            + "{\"name\":\"checked\",\"value\":{\"value\":\"true\"}}]}]}"));
        assertEquals(Boolean.TRUE, element.getProperty("checked").join());

        // className -> atributo class
        stub("DOM.getAttributes", json("{\"attributes\":[\"class\",\"btn primary\"]}"));
        assertEquals("btn primary", element.getProperty("className").join());
    }

    @Test
    void getPropertyRejectsArbitraryLivePropertiesWithoutJsWithUnsupported() {
        CompletableFuture<Object> future = element.getProperty("offsetTop");

        Exception thrown = assertThrows(Exception.class, () -> future.join());
        assertTrue(thrown.getCause() instanceof UnsupportedOperationException);
        assertTrue(thrown.getCause().getMessage().contains("offsetTop"));
    }

    @Test
    void getValueOfCssPropertyReadsComputedStyle() {
        stub("CSS.getComputedStyleForNode", json("{\"computedStyle\":["
            + "{\"name\":\"color\",\"value\":\"rgb(255, 0, 0)\"},"
            + "{\"name\":\"cursor\",\"value\":\"pointer\"}]}"));

        assertEquals("rgb(255, 0, 0)", element.getValueOfCssProperty("color").join());
        assertEquals("pointer", element.getValueOfCssProperty("cursor").join());
        assertEquals("", element.getValueOfCssProperty("grid-template").join());
    }

    @Test
    void isFocusedReadsFocusedAxProp() {
        stub("Accessibility.getPartialAXTree", json("{\"nodes\":[{\"properties\":["
            + "{\"name\":\"focused\",\"value\":{\"value\":\"true\"}}]}]}"));
        assertTrue(element.isFocused().join());

        stub("Accessibility.getPartialAXTree", json("{\"nodes\":[{\"properties\":[]}]}"));
        assertFalse(element.isFocused().join());
    }

    @Test
    void axValueReadsControlValueFromAxTree() {
        stub("Accessibility.getPartialAXTree", json("{\"nodes\":["
            + "{\"value\":{\"type\":\"integer\",\"value\":3}},"
            + "{\"value\":{\"value\":\"second-node-value\"}}]}"));

        // primeiro nó com value textual vence
        assertEquals("second-node-value", WebElementAttributes.axValue(element).join());
    }

    @Test
    void cssEnableSuccessIsCachedPerTargetIdentity() {
        stub("CSS.getComputedStyleForNode", json("{\"computedStyle\":[]}"));

        assertEquals("", element.getValueOfCssProperty("color").join());
        assertEquals("", element.getValueOfCssProperty("color").join());

        // enable roda uma única vez por instância de target (cache por instância de Target)
        verify(target, times(1)).executeCdpCmd(eq("CSS.enable"), anyMap(), any());
    }

    @Test
    void cssEnableFailureIsNotCachedAndNextCallRetries() {
        // Regressão da corrida do computeIfAbsent+whenComplete: com falha SÍNCRONA o future
        // quebrado ficava permanentemente cacheado; agora putIfAbsent+remove condicional limpam.
        java.util.concurrent.atomic.AtomicInteger calls = new java.util.concurrent.atomic.AtomicInteger();
        when(target.executeCdpCmd(eq("CSS.enable"), anyMap(), any()))
            .thenAnswer(inv -> calls.incrementAndGet() == 1
                ? CompletableFuture.failedFuture(new IllegalStateException("transient"))
                : CompletableFuture.completedFuture(NullNode.getInstance()));
        stub("CSS.getComputedStyleForNode", json("{\"computedStyle\":[]}"));

        // 1ª chamada engole a falha do enable (exceptionally -> "")
        assertEquals("", element.getValueOfCssProperty("color").join());
        // 2ª chamada re-executa o enable (future falho não ficou no cache)
        assertEquals("", element.getValueOfCssProperty("color").join());

        verify(target, times(2)).executeCdpCmd(eq("CSS.enable"), anyMap(), any());
    }

    @Test
    void axEnableFailureIsNotCachedAndNextCallRetries() {
        java.util.concurrent.atomic.AtomicInteger calls = new java.util.concurrent.atomic.AtomicInteger();
        when(target.executeCdpCmd(eq("Accessibility.enable"), anyMap(), any()))
            .thenAnswer(inv -> calls.incrementAndGet() == 1
                ? CompletableFuture.failedFuture(new IllegalStateException("transient"))
                : CompletableFuture.completedFuture(NullNode.getInstance()));
        when(target.executeCdpCmd(eq("Accessibility.getPartialAXTree"), anyMap(), any()))
            .thenReturn(CompletableFuture.completedFuture(json("{\"nodes\":[]}")));

        // AX vazio -> nada selecionado; enable falho não fica cacheado entre as chamadas
        assertEquals(false, element.isSelected().join());
        assertEquals(false, element.isSelected().join());

        verify(target, times(2))
            .executeCdpCmd(eq("Accessibility.enable"), anyMap(), any());
    }

    @Test
    void operationsOnStaleElementFailFast() {
        WebElement stale = new WebElement(target, null, null, "obj-1", 42, null, null, false);
        stale.stale = true;

        assertThrows(WebElement.StaleElementReferenceException.class, () -> stale.getText().join());
    }

    @Test
    void describeNodeForTextReceivesDepthAndPierceFlags() {
        stub("DOM.describeNode", json("{\"node\":{\"nodeType\":1}}"));

        element.getText().join();

        verify(target).executeCdpCmd(eq("DOM.describeNode"), argThat(args ->
            Integer.valueOf(-1).equals(args.get("depth"))
                && Boolean.FALSE.equals(args.get("pierce"))), any());
    }
}
