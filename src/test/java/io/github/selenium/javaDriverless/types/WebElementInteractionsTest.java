package io.github.selenium.javaDriverless.types;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.concurrent.CompletableFuture;

import com.fasterxml.jackson.databind.node.NullNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import io.github.selenium.javaDriverless.input.Pointer;

/** Interações físicas do elemento: clique por mid-location, clear/submit por teclado real. */
class WebElementInteractionsTest {

    private static final com.fasterxml.jackson.databind.ObjectMapper MAPPER =
        new com.fasterxml.jackson.databind.ObjectMapper();

    private Target target;
    private Pointer pointer;
    private WebElement element;

    @BeforeEach
    void setUp() {
        target = mock(Target.class);
        pointer = mock(Pointer.class);
        when(target.getPointer()).thenReturn(pointer);
        when(target.executeCdpCmd(any(), anyMap(), any()))
            .thenReturn(CompletableFuture.completedFuture(NullNode.getInstance()));
        when(pointer.click(anyInt(), anyInt(), org.mockito.ArgumentMatchers.anyBoolean(), anyDouble(), anyDouble(), anyDouble()))
            .thenReturn(CompletableFuture.completedFuture(null));
        when(pointer.moveTo(anyInt(), anyInt())).thenReturn(CompletableFuture.completedFuture(null));
        when(target.sendKeys(anyString())).thenReturn(CompletableFuture.completedFuture(null));

        // box model 10,20 -> 40,60 alimenta getMidLocation
        when(target.executeCdpCmd(eq("DOM.getBoxModel"), anyMap(), any()))
            .thenReturn(CompletableFuture.completedFuture(
                json("{\"model\":{\"border\":[10,20,40,20,40,60,10,60]}}")));

        element = new WebElement(target, null, null, "obj-1", 42, null, null, false);
    }

    private static ObjectNode json(String s) {
        try {
            return (ObjectNode) MAPPER.readTree(s);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    @Test
    void clickUsesPointerAtMidLocation() {
        element.click(true, 0.5, 2.0, 20.0).join();

        verify(pointer).click(org.mockito.ArgumentMatchers.intThat(x -> x >= 0 && x <= 40),
            org.mockito.ArgumentMatchers.intThat(y -> y >= 0 && y <= 60),
            eq(true), eq(0.5), eq(2.0), eq(20.0));
    }

    @Test
    void clearFocusesThenCtrlAAndDelete() {
        element.clear().join();

        verify(target).executeCdpCmd(eq("DOM.focus"),
            argThat(args -> Integer.valueOf(42).equals(args.get("nodeId"))), any());
        verify(target, org.mockito.Mockito.times(4))
            .executeCdpCmd(eq("Input.dispatchKeyEvent"), argThat(args -> {
                String type = (String) args.get("type");
                String key = (String) args.get("key");
                if ("keyDown".equals(type) && "a".equals(key)) {
                    return Integer.valueOf(2).equals(args.get("modifiers"));
                }
                return "a".equals(key) || "Delete".equals(key);
            }), any());
    }

    @Test
    void submitFocusesThenEnter() {
        element.submit().join();

        verify(target).executeCdpCmd(eq("DOM.focus"), anyMap(), any());
        verify(target, org.mockito.Mockito.times(2))
            .executeCdpCmd(eq("Input.dispatchKeyEvent"), argThat(args ->
                "Enter".equals(args.get("key"))), any());
    }

    @Test
    void moveToDrivesPointerToBiasMidLocation() {
        element.moveTo(1f).join();

        verify(pointer).moveTo(25, 40);
    }

    @Test
    void moveToWrapsFailuresWithOperationalMessage() {
        when(pointer.moveTo(anyInt(), anyInt()))
            .thenReturn(CompletableFuture.failedFuture(new IllegalStateException("sem pointer")));

        RuntimeException thrown = org.junit.jupiter.api.Assertions.assertThrows(
            RuntimeException.class, () -> element.moveTo(1f).join());
        assertTrue(thrown.getMessage().contains("Erro ao mover mouse"), thrown.getMessage());
    }

    @Test
    void writeDelegatesToTargetSendKeys() {
        element.write("texto").join();

        verify(target).sendKeys("texto");
    }

    @Test
    void staleElementRejectsInteraction() {
        element.stale = true;

        org.junit.jupiter.api.Assertions.assertThrows(
            WebElement.StaleElementReferenceException.class,
            () -> element.click().join());
    }

    @Test
    void clickWithoutArgumentsUsesTheDefaultHumanizedProfile() {
        element.click().join();

        verify(pointer).click(
            org.mockito.ArgumentMatchers.intThat(x -> x >= 0 && x <= 40),
            org.mockito.ArgumentMatchers.intThat(y -> y >= 0 && y <= 60),
            eq(true), eq(0.5), eq(2.0), eq(20.0));
    }

    @Test
    void elementIdentityComparesObjNodeAndBackendIds() {
        WebElement mesma = new WebElement(target, null, null, "obj-1", 42, null, null, false);
        WebElement outroObj = new WebElement(target, null, null, "obj-2", 42, null, null, false);
        WebElement outroNode = new WebElement(target, null, null, "obj-1", 43, null, null, false);
        WebElement comBackend = new WebElement(target, null, null, "obj-1", 42, 99, null, false);

        org.junit.jupiter.api.Assertions.assertEquals(element, mesma);
        org.junit.jupiter.api.Assertions.assertEquals(element.hashCode(), mesma.hashCode());
        org.junit.jupiter.api.Assertions.assertNotEquals(element, outroObj);
        org.junit.jupiter.api.Assertions.assertNotEquals(element, outroNode);
        org.junit.jupiter.api.Assertions.assertNotEquals(element, comBackend);
        org.junit.jupiter.api.Assertions.assertEquals(element, element);
        org.junit.jupiter.api.Assertions.assertNotEquals(element, null);
        org.junit.jupiter.api.Assertions.assertNotEquals(element, "não-sou-elemento");

        // contrato HashMap: iguais caem no mesmo bucket
        java.util.Map<WebElement, String> mapa = new java.util.HashMap<>();
        mapa.put(element, "v");
        org.junit.jupiter.api.Assertions.assertEquals("v", mapa.get(mesma));

        org.junit.jupiter.api.Assertions.assertTrue(
            element.toString().contains("obj-1"), element.toString());
    }
}
