package io.github.selenium.javaDriverless.input;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.Map;
import java.util.concurrent.CompletableFuture;

import com.fasterxml.jackson.databind.node.NullNode;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import io.github.selenium.javaDriverless.cdp.CDPCommandExecutor;

/** Eventos físicos de mouse via Input.dispatchMouseEvent: estado de botões, cliques, scroll. */
class PointerInteractionTest {

    private CDPCommandExecutor executor;
    private Pointer pointer;

    @BeforeEach
    void setUp() {
        executor = mock(CDPCommandExecutor.class);
        when(executor.executeCdpCmd(any(), anyMap(), any()))
            .thenReturn(CompletableFuture.completedFuture(NullNode.getInstance()));
        pointer = new Pointer(executor);
        pointer.setClickJitter(0); // determinístico para asserts de coordenada
    }

    private void verifyMouseEvent(String type, Integer x, Integer y, String button, Integer buttons) {
        verify(executor).executeCdpCmd(eq("Input.dispatchMouseEvent"), argThat(args -> {
            boolean ok = type.equals(args.get("type"))
                && "mouse".equals(args.get("pointerType"));
            if (x != null) {
                ok &= Integer.valueOf(x).equals(args.get("x"));
            }
            if (y != null) {
                ok &= Integer.valueOf(y).equals(args.get("y"));
            }
            if (button != null) {
                ok &= button.equals(args.get("button"));
            }
            if (buttons != null) {
                ok &= Integer.valueOf(buttons).equals(args.get("buttons"));
            }
            return ok;
        }), any());
    }

    @Test
    void locationStartsAtDefaultAndIsTracked() {
        assertArrayEquals(new int[]{100, 0}, pointer.getLocation());

        pointer.moveTo(30, 40, 0.01, 1, 1).join();

        assertArrayEquals(new int[]{30, 40}, pointer.getLocation());
        // caminho humanizado emite pelo menos um mouseMoved durante o trajeto
        assertTrue(capturedMouseMoves().size() >= 1);
    }

    @SuppressWarnings("unchecked")
    private java.util.List<Map<String, Object>> capturedMouseMoves() {
        org.mockito.ArgumentCaptor<Map<String, Object>> captor =
            org.mockito.ArgumentCaptor.forClass(Map.class);
        verify(executor, org.mockito.Mockito.atLeastOnce())
            .executeCdpCmd(eq("Input.dispatchMouseEvent"), captor.capture(), any());
        return captor.getAllValues().stream()
            .filter(args -> "mouseMoved".equals(args.get("type")))
            .collect(java.util.stream.Collectors.toList());
    }

    @Test
    void downAndUpTrackPressedButtonsMask() {
        pointer.down(5, 6, Pointer.MouseButton.LEFT, 1).join();
        verifyMouseEvent("mousePressed", 5, 6, "left", Pointer.Buttons.LEFT);

        pointer.up(5, 6, Pointer.MouseButton.LEFT, 1).join();
        verifyMouseEvent("mouseReleased", 5, 6, "left", Pointer.Buttons.NONE);
    }

    @Test
    void parameterlessDownUpUseCurrentLocationAndLeftButton() {
        pointer.moveTo(10, 20, 0.01, 1, 1).join();

        pointer.down().join();
        pointer.up().join();

        verifyMouseEvent("mousePressed", 10, 20, "left", Pointer.Buttons.LEFT);
        verifyMouseEvent("mouseReleased", 10, 20, "left", Pointer.Buttons.NONE);
    }

    @Test
    void clickPressesAndReleasesAtCoordinates() {
        pointer.click(50, 60, 0.01, Pointer.MouseButton.LEFT).join();

        verifyMouseEvent("mousePressed", 50, 60, "left", Pointer.Buttons.LEFT);
        verifyMouseEvent("mouseReleased", 50, 60, "left", Pointer.Buttons.NONE);
    }

    @Test
    void clickJitterLandsWithinConfiguredRadius() {
        pointer.setClickJitter(5);
        assertEquals(5, pointer.getClickJitter());

        pointer.click(100, 100, 0.01, Pointer.MouseButton.LEFT).join();

        verify(executor, org.mockito.Mockito.atLeast(2)).executeCdpCmd(
            eq("Input.dispatchMouseEvent"), argThat(args -> {
                int x = (Integer) args.get("x");
                int y = (Integer) args.get("y");
                return Math.abs(x - 100) <= 5 && Math.abs(y - 100) <= 5;
            }), any());
    }

    @Test
    void negativeJitterIsClampedToZero() {
        pointer.setClickJitter(-3);
        assertEquals(0, pointer.getClickJitter());

        pointer.click(7, 8, 0.01, Pointer.MouseButton.LEFT).join();
        verifyMouseEvent("mousePressed", 7, 8, null, null);
    }

    @Test
    void doubleClickUsesClickCountTwo() {
        pointer.doubleClick(10, 10, 0.01).join();

        // 2 cliques (down+up cada) + movimentos do caminho entre eles
        verify(executor, org.mockito.Mockito.atLeast(4)).executeCdpCmd(
            eq("Input.dispatchMouseEvent"),
            argThat(args -> args.containsKey("clickCount")), any());
    }

    @Test
    void contextClickUsesRightButton() {
        pointer.contextClick(15, 25).join();

        verifyMouseEvent("mousePressed", 15, 25, "right", Pointer.Buttons.RIGHT);
        verifyMouseEvent("mouseReleased", 15, 25, "right", Pointer.Buttons.NONE);
    }

    @Test
    void scrollDispatchesMouseEventWheel() {
        pointer.scroll(0, -120).join();

        verify(executor).executeCdpCmd(eq("Input.dispatchMouseEvent"), argThat(args ->
            "mouseWheel".equals(args.get("type"))
                && Integer.valueOf(-120).equals(args.get("deltaY"))), any());
    }

    @Test
    void dragAndDropMovesPressesAndReleases() {
        pointer.dragAndDrop(0, 0, 40, 40, 0.05).join();

        verify(executor, org.mockito.Mockito.atLeastOnce())
            .executeCdpCmd(eq("Input.dispatchMouseEvent"),
                argThat(args -> "mousePressed".equals(args.get("type"))), any());
        verify(executor, org.mockito.Mockito.atLeastOnce())
            .executeCdpCmd(eq("Input.dispatchMouseEvent"),
                argThat(args -> "mouseReleased".equals(args.get("type"))), any());
        assertArrayEquals(new int[]{40, 40}, pointer.getLocation());
    }

    @Test
    void penPointerTypeIsPropagatedToEvents() {
        Pointer pen = new Pointer(executor, Pointer.PointerType.PEN);
        pen.setClickJitter(0);
        pen.down(1, 2, Pointer.MouseButton.LEFT, 1).join();

        verify(executor).executeCdpCmd(eq("Input.dispatchMouseEvent"), argThat(args ->
            "pen".equals(args.get("pointerType"))), any());
    }

    @Test
    void makeRandClickTimeoutStaysWithinHumanBounds() {
        for (int i = 0; i < 50; i++) {
            double t = Pointer.makeRandClickTimeout();
            assertTrue(t > 0 && t < 1.0, "t=" + t);
        }
    }

    @Test
    void clickWithoutArgumentsUsesTheCurrentLocation() {
        pointer.moveTo(30, 40, 0.01, 2, 5).join();
        pointer.click().join();

        verifyMouseEvent("mousePressed", 30, 40, "left", 1);
        verifyMouseEvent("mouseReleased", 30, 40, "left", 0);
    }

    @Test
    void clickWithHumanizedMoveTravelsBeforePressing() {
        pointer.click(50, 60, true, 0.01, 2, 5).join();

        verifyMouseEvent("mousePressed", 50, 60, "left", 1);
        assertArrayEquals(new int[]{50, 60}, pointer.getLocation());
    }

    @Test
    void clickWithoutMovePressesInPlace() {
        pointer.moveTo(5, 6, 0.01, 2, 5).join();
        pointer.click(99, 100, false, 0.01, 2, 5).join();

        verifyMouseEvent("mousePressed", 99, 100, "left", 1);
        assertArrayEquals(new int[]{99, 100}, pointer.getLocation());
    }

    @Test
    void doubleClickEmitsSecondPressWithClickCountTwo() {
        pointer.doubleClick(10, 20).join();

        verify(executor).executeCdpCmd(eq("Input.dispatchMouseEvent"), argThat(args ->
            "mousePressed".equals(args.get("type"))
                && Integer.valueOf(2).equals(args.get("clickCount"))), any());
    }

    @Test
    void doubleClickWithoutArgumentsUsesCurrentLocation() {
        pointer.moveTo(11, 12, 0.01, 2, 5).join();
        pointer.doubleClick().join();

        verify(executor).executeCdpCmd(eq("Input.dispatchMouseEvent"), argThat(args ->
            "mousePressed".equals(args.get("type"))
                && Integer.valueOf(11).equals(args.get("x"))
                && Integer.valueOf(2).equals(args.get("clickCount"))), any());
    }

    @Test
    void contextClickEmitsRightButtonPress() {
        pointer.contextClick(7, 8).join();

        verifyMouseEvent("mousePressed", 7, 8, "right", 2);
    }

    @Test
    void contextClickWithoutArgumentsUsesCurrentLocation() {
        pointer.moveTo(3, 4, 0.01, 2, 5).join();
        pointer.contextClick().join();

        verifyMouseEvent("mousePressed", 3, 4, "right", 2);
    }
}
