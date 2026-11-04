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

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicReference;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.NullNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/**
 * &lt;select&gt; nativo por teclado físico (Home + ArrowDown) — zero JS injetado.
 * Usa WebElement real + CDP fake (dispatch por comando/args) para exercitar o caminho completo.
 */
class SelectTest {

    private static final com.fasterxml.jackson.databind.ObjectMapper MAPPER =
        new com.fasterxml.jackson.databind.ObjectMapper();

    private Target target;
    private WebElement element;
    private final AtomicReference<String> axSelectedText = new AtomicReference<>("");

    private static ObjectNode json(String json) {
        try {
            return (ObjectNode) MAPPER.readTree(json);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    @BeforeEach
    void setUp() {
        target = mock(Target.class);
        axSelectedText.set("");

        // CDP fake: responde por comando + identificador do nó
        when(target.executeCdpCmd(any(), anyMap(), any())).thenAnswer(inv -> {
            String cmd = inv.getArgument(0);
            Map<String, Object> args = inv.getArgument(1);
            Integer nodeId = args != null ? (Integer) args.get("nodeId") : null;
            Integer backendNodeId = args != null ? (Integer) args.get("backendNodeId") : null;
            String objectId = args != null ? (String) args.get("objectId") : null;

            switch (cmd) {
                case "DOM.describeNode":
                    if (nodeId != null && nodeId == 55) {
                        return completed(json("{\"node\":{\"nodeName\":\"select\"}}"));
                    }
                    if (nodeId != null && nodeId == 301) {
                        return completed(json("{\"node\":{\"nodeName\":\"option\","
                            + "\"children\":[{\"nodeType\":3,\"nodeValue\":\"Brazil\"}]}}"));
                    }
                    if (nodeId != null && nodeId == 302) {
                        return completed(json("{\"node\":{\"nodeName\":\"option\","
                            + "\"children\":[{\"nodeType\":3,\"nodeValue\":\"Argentina\"}]}}"));
                    }
                    // resolução querySelectorAll -> backendNodeIds
                    if (nodeId != null && nodeId == 101) {
                        return completed(json("{\"node\":{\"backendNodeId\":901}}"));
                    }
                    if (nodeId != null && nodeId == 102) {
                        return completed(json("{\"node\":{\"backendNodeId\":902}}"));
                    }
                    return completed(json("{\"node\":{}}"));
                case "DOM.querySelectorAll":
                    return completed(json("{\"nodeIds\":[101,102]}"));
                case "DOM.resolveNode":
                    if (backendNodeId != null && backendNodeId == 901) {
                        return completed(json("{\"object\":{\"objectId\":\"opt-1\"}}"));
                    }
                    if (backendNodeId != null && backendNodeId == 902) {
                        return completed(json("{\"object\":{\"objectId\":\"opt-2\"}}"));
                    }
                    return completed(json("{\"object\":{}}"));
                case "DOM.requestNode":
                    if ("opt-1".equals(objectId)) {
                        return completed(json("{\"nodeId\":301}"));
                    }
                    if ("opt-2".equals(objectId)) {
                        return completed(json("{\"nodeId\":302}"));
                    }
                    return completed(json("{\"nodeId\":0}"));
                case "DOM.getAttributes":
                    if (nodeId != null && nodeId == 301) {
                        return completed(json("{\"attributes\":[\"value\",\"br\"]}"));
                    }
                    if (nodeId != null && nodeId == 302) {
                        return completed(json("{\"attributes\":[\"value\",\"ar\"]}"));
                    }
                    if (nodeId != null && nodeId == 55) {
                        return completed(json("{\"attributes\":[]}"));
                    }
                    return completed(json("{\"attributes\":[]}"));
                case "Accessibility.enable":
                case "CSS.enable":
                    return completed(NullNode.getInstance());
                case "Accessibility.getPartialAXTree":
                    String selected = axSelectedText.get();
                    return completed(json("{\"nodes\":[{\"value\":{\"value\":\"" + selected + "\"}}]}"));
                default:
                    return completed(NullNode.getInstance());
            }
        });

        element = new WebElement(target, null, null, null, 55, null, null, false);
    }

    private static <T> CompletableFuture<T> completed(T value) {
        return CompletableFuture.completedFuture(value);
    }

    @Test
    void constructorRejectsNonSelectElements() {
        WebElement div = new WebElement(target, null, null, null, 66, null, null, false);
        // nodeId 66 cai no default do describeNode -> nodeName ausente -> ""
        IllegalArgumentException thrown = assertThrows(IllegalArgumentException.class,
            () -> new Select(div));
        assertTrue(thrown.getMessage().contains("não é um"));
    }

    @Test
    void constructorAcceptsLowercasedSelectTag() {
        new Select(element);
    }

    @Test
    void selectByValueFocusesSelectAndNavigatesWithPhysicalKeys() {
        new Select(element).selectByValue("ar");

        verify(target).executeCdpCmd(eq("DOM.focus"), argThat(args ->
            Integer.valueOf(55).equals(args.get("nodeId"))), any());

        verify(target, times(1)).executeCdpCmd(eq("Input.dispatchKeyEvent"),
            argThat(args -> "keyDown".equals(args.get("type")) && "Home".equals(args.get("key"))), any());
        verify(target, times(1)).executeCdpCmd(eq("Input.dispatchKeyEvent"),
            argThat(args -> "keyUp".equals(args.get("type")) && "Home".equals(args.get("key"))), any());
        verify(target, times(1)).executeCdpCmd(eq("Input.dispatchKeyEvent"),
            argThat(args -> "keyDown".equals(args.get("type")) && "ArrowDown".equals(args.get("key"))), any());
        verify(target, times(1)).executeCdpCmd(eq("Input.dispatchKeyEvent"),
            argThat(args -> "keyUp".equals(args.get("type")) && "ArrowDown".equals(args.get("key"))), any());
    }

    @Test
    void selectByIndexZeroOnlyPressesHome() {
        new Select(element).selectByIndex(0);

        verify(target, times(1)).executeCdpCmd(eq("Input.dispatchKeyEvent"),
            argThat(args -> "Home".equals(args.get("key")) && "keyDown".equals(args.get("type"))), any());
        verify(target, org.mockito.Mockito.never()).executeCdpCmd(eq("Input.dispatchKeyEvent"),
            argThat(args -> "ArrowDown".equals(args.get("key"))), any());
    }

    @Test
    void selectByIndexRejectsOutOfRange() {
        Select select = new Select(element);

        RuntimeException tooBig = assertThrows(RuntimeException.class, () -> select.selectByIndex(2));
        assertTrue(tooBig.getMessage().contains("0..1"));

        RuntimeException negative = assertThrows(RuntimeException.class, () -> select.selectByIndex(-1));
        assertTrue(negative.getMessage().contains("fora do range"));
    }

    @Test
    void selectByVisibleTextMatchesTrimmedText() {
        new Select(element).selectByVisibleText("  Argentina ");

        verify(target, times(1)).executeCdpCmd(eq("DOM.focus"), anyMap(), any());
    }

    @Test
    void selectByValueAndTextThrowWhenOptionMissing() {
        Select select = new Select(element);

        RuntimeException byValue = assertThrows(RuntimeException.class,
            () -> select.selectByValue("cl"));
        assertTrue(byValue.getMessage().contains("cl"));

        RuntimeException byText = assertThrows(RuntimeException.class,
            () -> select.selectByVisibleText("Chile"));
        assertTrue(byText.getMessage().contains("Chile"));
    }

    @Test
    void deselectOperationsAreUnsupportedInStealthMode() {
        Select select = new Select(element);

        assertThrows(UnsupportedOperationException.class, select::deselectAll);
        assertThrows(UnsupportedOperationException.class, () -> select.deselectByValue("br"));
    }

    @Test
    void optionsCountCountsNativeOptions() {
        assertEquals(2, new Select(element).getOptionsCount());
    }

    @Test
    void isMultipleReflectsMultipleAttribute() {
        // atributos de 55 voltam vazios -> multiple null -> false
        assertFalse(new Select(element).isMultiple());
    }

    @Test
    void getSelectedTextReadsAxValue() {
        axSelectedText.set("Argentina");
        assertEquals("Argentina", new Select(element).getSelectedText());
    }

    @Test
    void getSelectedTextIsNullWhenAxValueEmpty() {
        assertNull(new Select(element).getSelectedText());
    }

    @Test
    void getSelectedValueMapsSelectedTextToOptionValue() {
        axSelectedText.set("Brazil");
        assertEquals("br", new Select(element).getSelectedValue());
    }

    @Test
    void getSelectedValueIsNullWhenNothingSelected() {
        assertNull(new Select(element).getSelectedValue());
    }

    @Test
    void keyEventsCarryVirtualKeyCodes() {
        new Select(element).selectByIndex(0);

        ArgumentCaptor<Map<String, Object>> captor = ArgumentCaptor.forClass(Map.class);
        verify(target, times(2)).executeCdpCmd(eq("Input.dispatchKeyEvent"), captor.capture(), any());

        Map<String, Object> homeDown = captor.getAllValues().get(0);
        assertEquals(36, homeDown.get("windowsVirtualKeyCode"));
        assertEquals(36, homeDown.get("nativeVirtualKeyCode"));
    }

    @Test
    void selectByValueOnFirstOptionSendsOnlyHome() {
        new Select(element).selectByValue("br");

        verify(target, org.mockito.Mockito.never()).executeCdpCmd(eq("Input.dispatchKeyEvent"),
            argThat(args -> "ArrowDown".equals(args.get("key"))), any());
    }
}
