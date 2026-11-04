package io.github.selenium.javaDriverless.types;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

import com.fasterxml.jackson.databind.node.ObjectNode;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** Geometria do elemento a partir do box model CDP (bounds, quads, viewport, zoom, rolagem). */
class WebElementGeometryTest {

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

    private WebElement elementWithBox(String borderJson, String extra) {
        when(target.executeCdpCmd(eq("DOM.getBoxModel"), anyMap(), any()))
            .thenReturn(CompletableFuture.completedFuture(
                json("{\"model\":{\"border\":" + borderJson + (extra != null ? "," + extra : "") + "}}")));
        return new WebElement(target, null, null, "obj-1", 42, null, null, false);
    }

    @Test
    void getRectDerivesFromBorderQuad() {
        // retângulo axis-aligned: x=10,y=20,w=30,h=40
        WebElement element = elementWithBox("[10,20,40,20,40,60,10,60]", null);

        Map<String, Object> rect = element.getRect().join();

        assertEquals(10.0, (Double) rect.get("x"), 1e-9);
        assertEquals(20.0, (Double) rect.get("y"), 1e-9);
        assertEquals(30.0, (Double) rect.get("width"), 1e-9);
        assertEquals(40.0, (Double) rect.get("height"), 1e-9);
    }

    @Test
    void getSizeAndQuadsMatchBoxModel() {
        WebElement element = elementWithBox("[0,0,100,0,100,50,0,50]", null);

        double[] size = element.getSize().join();
        assertEquals(100.0, size[0], 1e-9);
        assertEquals(50.0, size[1], 1e-9);

        double[][] quads = element.getQuads().join();
        assertEquals(0.0, quads[0][0], 1e-9); // TL
        assertEquals(0.0, quads[0][1], 1e-9);
        assertEquals(100.0, quads[1][0], 1e-9); // TR
        assertEquals(50.0, quads[2][1], 1e-9); // BR
        assertEquals(0.0, quads[3][0], 1e-9); // BL
    }

    @Test
    void isVisibleRequiresPositiveArea() {
        assertTrue(elementWithBox("[0,0,10,0,10,10,0,10]", null).isVisible().join());
        assertFalse(elementWithBox("[0,0,0,0,0,0,0,0]", null).isVisible().join());
    }

    @Test
    void isVisibleFallsBackToFalseOnBoxModelError() {
        when(target.executeCdpCmd(eq("DOM.getBoxModel"), anyMap(), any()))
            .thenReturn(CompletableFuture.failedFuture(new IllegalStateException("no layout")));
        WebElement element = new WebElement(target, null, null, "obj-1", 42, null, null, false);

        assertFalse(element.isVisible().join());
    }

    @Test
    void getLocationAddsScrollOffsetFromLayoutMetrics() {
        WebElement element = elementWithBox("[10,20,40,20,40,60,10,60]", null);
        when(target.executeCdpCmd(eq("Page.getLayoutMetrics"), anyMap(), any()))
            .thenReturn(CompletableFuture.completedFuture(json(
                "{\"cssVisualViewport\":{\"pageX\":5,\"pageY\":7}}")));

        double[] location = element.getLocation().join();

        assertEquals(15.0, location[0], 1e-9);
        assertEquals(27.0, location[1], 1e-9);
    }

    @Test
    void getLocationFallsBackToLegacyVisualViewportWhenCssFieldsMissing() {
        WebElement element = elementWithBox("[0,0,10,0,10,10,0,10]", null);
        when(target.executeCdpCmd(eq("Page.getLayoutMetrics"), anyMap(), any()))
            .thenReturn(CompletableFuture.completedFuture(json(
                "{\"visualViewport\":{\"pageX\":3,\"pageY\":4}}")));

        double[] location = element.getLocation().join();

        assertEquals(3.0, location[0], 1e-9);
        assertEquals(4.0, location[1], 1e-9);
    }

    @Test
    void getLocationReturnsOriginOnError() {
        when(target.executeCdpCmd(eq("DOM.getBoxModel"), anyMap(), any()))
            .thenReturn(CompletableFuture.failedFuture(new IllegalStateException("gone")));
        WebElement element = new WebElement(target, null, null, "obj-1", 42, null, null, false);

        double[] location = element.getLocation().join();
        assertEquals(0.0, location[0], 1e-9);
        assertEquals(0.0, location[1], 1e-9);
    }

    @Test
    @SuppressWarnings("unchecked")
    void getCssMetricsComputesZoomFromBorderVsActualWidth() {
        // border width real = 200; width reportado (device px) = 100 -> zoom 2.0
        WebElement element = elementWithBox("[0,0,200,0,200,100,0,100]",
            "\"width\":100,\"height\":50");

        Object[] metrics = element.getCssMetrics().join();

        Map<String, Object> model = (Map<String, Object>) metrics[0];
        double zoom = (Double) metrics[1];
        assertTrue(model.containsKey("border"));
        assertEquals(2.0, zoom, 1e-9);
    }

    @Test
    void pVisibleClipsElementAgainstViewport() {
        // elemento 100x100 em (0,0); viewport 50x50 -> 25% visível, área 2500
        WebElement element = elementWithBox("[0,0,100,0,100,100,0,100]", null);
        when(target.executeCdpCmd(eq("Page.getLayoutMetrics"), anyMap(), any()))
            .thenReturn(CompletableFuture.completedFuture(json(
                "{\"cssLayoutViewport\":{\"clientWidth\":50,\"clientHeight\":50}}")));

        Object[] visible = element.pVisible(null).join();

        assertEquals(0.25, (Double) visible[0], 1e-9);
        assertEquals(2500.0, (Double) visible[1], 1e-9);
    }

    @Test
    void pVisibleReturnsZeroForDegenerateBox() {
        WebElement element = elementWithBox("[5,5,5,5,5,5,5,5]", null);

        Object[] visible = element.pVisible(null).join();

        assertEquals(0.0, (Double) visible[0], 1e-9);
    }

    @Test
    @SuppressWarnings("unchecked")
    void midLocationMapAppliesBiasWithinBounds() {
        WebElement element = elementWithBox("[0,0,100,0,100,100,0,100]", null);

        Map<String, Object> mid = element.getMidLocation(1f, 1f, 0.25f, 0.75f, 1f).join();

        assertEquals(25.0, (Double) mid.get("x"), 1e-9);
        assertEquals(75.0, (Double) mid.get("y"), 1e-9);
        assertEquals("interactable", mid.get("type"));
    }

    @Test
    void midLocationFromQuadsStaysInsideBox() {
        WebElement element = elementWithBox("[0,0,100,0,100,100,0,100]", null);

        double[] mid = element.getMidLocation().join();

        assertTrue(mid[0] >= 0 && mid[0] <= 100, "x=" + mid[0]);
        assertTrue(mid[1] >= 0 && mid[1] <= 100, "y=" + mid[1]);
    }

    @Test
    void scrollToPassesRectWhenProvided() {
        when(target.executeCdpCmd(eq("DOM.scrollIntoViewIfNeeded"), anyMap(), any()))
            .thenReturn(CompletableFuture.completedFuture(json("{}")));
        WebElement element = new WebElement(target, null, null, "obj-1", 42, null, null, false);
        Map<String, Object> rect = Map.of("x", 1, "y", 2, "width", 3, "height", 4);

        element.scrollTo(rect).join();

        verify(target).executeCdpCmd(eq("DOM.scrollIntoViewIfNeeded"), argThat(args ->
            rect.equals(args.get("rect"))), any());
    }

    @Test
    @SuppressWarnings("unchecked")
    void locationOnceScrolledIntoViewCombinesScrollAndLocation() {
        when(target.executeCdpCmd(eq("DOM.scrollIntoViewIfNeeded"), anyMap(), any()))
            .thenReturn(CompletableFuture.completedFuture(json("{}")));
        WebElement element = elementWithBox("[10,20,40,20,40,60,10,60]", null);
        when(target.executeCdpCmd(eq("Page.getLayoutMetrics"), anyMap(), any()))
            .thenReturn(CompletableFuture.completedFuture(json(
                "{\"cssVisualViewport\":{\"pageX\":0,\"pageY\":0}}")));

        Map<String, Object> scrolled = element.locationOnceScrolledIntoView().join();

        assertEquals(10.0, (Double) scrolled.get("x"), 1e-9);
        assertEquals(20.0, (Double) scrolled.get("y"), 1e-9);
    }

    @Test
    void isClickableAcceptsClickListeners() {
        WebElement element = mockElementForClickable(true, List.of(Map.of("type", "click")),
            "div", "auto");
        assertTrue(element.isClickable(1, null).join());
    }

    @Test
    void isClickableAcceptsInteractiveTags() {
        WebElement element = mockElementForClickable(true, List.of(), "button", "auto");
        assertTrue(element.isClickable(1, null).join());
    }

    @Test
    void isClickableAcceptsPointerCursor() {
        WebElement element = mockElementForClickable(true, List.of(), "div", "pointer");
        assertTrue(element.isClickable(1, null).join());
    }

    @Test
    void isClickableRejectsInvisibleElements() {
        WebElement element = mockElementForClickable(false, List.of(), "button", "pointer");
        assertFalse(element.isClickable(1, null).join());
    }

    @Test
    void isClickableSwallowsErrorsAsFalse() {
        // CALLS_REAL_METHODS deixa isClickable real rodar sobre isVisible que falha
        WebElement element = mock(WebElement.class,
            org.mockito.Mockito.withSettings().defaultAnswer(org.mockito.Mockito.CALLS_REAL_METHODS));
        when(element.isVisible())
            .thenReturn(CompletableFuture.failedFuture(new IllegalStateException("boom")));

        assertFalse(element.isClickable(1, null).join());
    }

    private WebElement mockElementForClickable(boolean visible,
                                               List<Map<String, Object>> listeners,
                                               String tagName, String cursor) {
        // CALLS_REAL_METHODS: isClickable real roda sobre os collaborators stubados
        WebElement element = mock(WebElement.class,
            org.mockito.Mockito.withSettings().defaultAnswer(org.mockito.Mockito.CALLS_REAL_METHODS));
        when(element.isVisible()).thenReturn(CompletableFuture.completedFuture(visible));
        when(element.getListeners(org.mockito.ArgumentMatchers.anyInt()))
            .thenReturn(CompletableFuture.completedFuture(listeners));
        when(element.getTagName()).thenReturn(CompletableFuture.completedFuture(tagName));
        when(element.getValueOfCssProperty(any()))
            .thenReturn(CompletableFuture.completedFuture(cursor));
        return element;
    }
}
