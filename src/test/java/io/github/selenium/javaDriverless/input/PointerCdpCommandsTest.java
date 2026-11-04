package io.github.selenium.javaDriverless.input;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyFloat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

import com.fasterxml.jackson.databind.JsonNode;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import io.github.selenium.javaDriverless.cdp.CDPCommandExecutor;

/**
 * Pointer REAL sobre executor CDP mockado: cada gesto (clique, duplo, contexto,
 * arrasto, scroll, movimento humanizado) deve despachar a sequência correta de
 * Input.dispatchMouseEvent com jitter desligado para coordenadas determinísticas.
 */
class PointerCdpCommandsTest {

    private CDPCommandExecutor executor;
    private Pointer pointer;

    @BeforeEach
    void setUp() {
        executor = mock(CDPCommandExecutor.class);
        when(executor.executeCdpCmd(any(), any(), any()))
            .thenReturn(CompletableFuture.completedFuture(null));
        pointer = new Pointer(executor);
        pointer.setClickJitter(0);
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> allDispatched() {
        var captured = (org.mockito.ArgumentCaptor<Map<String, Object>>)
            (org.mockito.ArgumentCaptor<?>) org.mockito.ArgumentCaptor.forClass(Map.class);
        verify(executor, org.mockito.Mockito.atLeastOnce())
            .executeCdpCmd(eq("Input.dispatchMouseEvent"), captured.capture(), isNull());
        return captured.getAllValues();
    }

    private Map<String, Object> dispatched(int indice) {
        return allDispatched().get(indice);
    }

    @Test
    void startsAtHumanizedOriginAndTracksLocation() {
        assertThat(pointer.getLocation()).containsExactly(100, 0);
    }

    @Test
    void clickJitterClampsNegativeRadiusToZero() {
        pointer.setClickJitter(-5);
        assertThat(pointer.getClickJitter()).isZero();
    }

    @Test
    void downAndUpDispatchPressedAndReleasedEvents() throws Exception {
        pointer.down(10, 20, Pointer.MouseButton.LEFT, 1).get(5, java.util.concurrent.TimeUnit.SECONDS);
        pointer.up(10, 20, Pointer.MouseButton.LEFT, 1).get(5, java.util.concurrent.TimeUnit.SECONDS);

        Map<String, Object> down = dispatched(0);
        assertThat(down)
            .containsEntry("type", "mousePressed")
            .containsEntry("x", 10)
            .containsEntry("y", 20)
            .containsEntry("button", "left")
            .containsEntry("clickCount", 1);

        Map<String, Object> up = dispatched(1);
        assertThat(up)
            .containsEntry("type", "mouseReleased")
            .containsEntry("button", "left");
    }

    @Test
    void clickHumanizedMovesThenPressesAndReleasesAtSameLandingPoint() throws Exception {
        pointer.click(100, 50, 0.01, Pointer.MouseButton.LEFT)
            .get(15, java.util.concurrent.TimeUnit.SECONDS);

        List<Map<String, Object>> eventos = allDispatched();

        assertThat(eventos).isNotEmpty();
        // primeiro evento é o início do movimento humanizado
        assertThat(eventos.get(0)).containsEntry("type", "mouseMoved");
        // down e up no mesmo ponto de pouso (jitter 0 → alvo exato)
        Map<String, Object> down = eventos.stream()
            .filter(e -> "mousePressed".equals(e.get("type"))).findFirst().orElseThrow();
        Map<String, Object> up = eventos.stream()
            .filter(e -> "mouseReleased".equals(e.get("type"))).findFirst().orElseThrow();
        assertThat(down).containsEntry("x", 100).containsEntry("y", 50);
        assertThat(up).containsEntry("x", 100).containsEntry("y", 50);
        // o ponteiro termina na coordenada do clique
        assertThat(pointer.getLocation()).containsExactly(100, 50);
    }

    @Test
    void clickAtCurrentPositionSkipsHumanizedTravelWhenAlreadyThere() throws Exception {
        pointer.click(30, 40, 0.01, Pointer.MouseButton.LEFT)
            .get(15, java.util.concurrent.TimeUnit.SECONDS);
        int eventosPrimeiroClique = allDispatched().size();

        // segundo clique no MESMO ponto: moveTo é no-op
        pointer.click(30, 40, 0.01, Pointer.MouseButton.LEFT)
            .get(15, java.util.concurrent.TimeUnit.SECONDS);

        List<Map<String, Object>> eventos = allDispatched();
        List<Map<String, Object>> segundo = eventos.subList(eventosPrimeiroClique, eventos.size());
        assertThat(segundo).noneSatisfy(e -> assertThat(e).containsEntry("type", "mouseMoved"));
    }

    @Test
    void doubleClickEmitsTwoPressReleasePairsWithCountTwoOnSecond() throws Exception {
        pointer.doubleClick(10, 10, 0.01).get(15, java.util.concurrent.TimeUnit.SECONDS);

        List<Map<String, Object>> eventos = allDispatched();
        long presses = eventos.stream().filter(e -> "mousePressed".equals(e.get("type"))).count();
        long releases = eventos.stream().filter(e -> "mouseReleased".equals(e.get("type"))).count();
        assertThat(presses).isEqualTo(2);
        assertThat(releases).isEqualTo(2);

        Map<String, Object> segundoPress = eventos.stream()
            .filter(e -> "mousePressed".equals(e.get("type")))
            .reduce((a, b) -> b).orElseThrow();
        assertThat(segundoPress).containsEntry("clickCount", 2);
    }

    @Test
    void contextClickUsesRightButton() throws Exception {
        pointer.contextClick(15, 25).get(15, java.util.concurrent.TimeUnit.SECONDS);

        List<Map<String, Object>> eventos = allDispatched();
        Map<String, Object> press = eventos.stream()
            .filter(e -> "mousePressed".equals(e.get("type"))).findFirst().orElseThrow();
        assertThat(press).containsEntry("button", "right").containsEntry("x", 15).containsEntry("y", 25);
    }

    @Test
    void scrollDispatchesWheelWithDeltas() throws Exception {
        pointer.scroll(5, -120).get(5, java.util.concurrent.TimeUnit.SECONDS);

        Map<String, Object> wheel = dispatched(0);
        assertThat(wheel)
            .containsEntry("type", "mouseWheel")
            .containsEntry("deltaX", 5)
            .containsEntry("deltaY", -120);
    }

    @Test
    void moveToSamePointIsNoOpWithoutCdpTraffic() throws Exception {
        pointer.moveTo(7, 8).get(15, java.util.concurrent.TimeUnit.SECONDS);
        int eventosAposPrimeira = allDispatched().size();
        assertThat(eventosAposPrimeira).isPositive();

        pointer.moveTo(7, 8).get(15, java.util.concurrent.TimeUnit.SECONDS);

        assertThat(allDispatched().size()).isEqualTo(eventosAposPrimeira);
        assertThat(pointer.getLocation()).containsExactly(7, 8);
    }

    @Test
    void dragAndDropPressesAtSourceReleasesAtTarget() throws Exception {
        pointer.dragAndDrop(0, 0, 60, 80, 0.2).get(20, java.util.concurrent.TimeUnit.SECONDS);

        List<Map<String, Object>> eventos = allDispatched();
        Map<String, Object> press = eventos.stream()
            .filter(e -> "mousePressed".equals(e.get("type"))).findFirst().orElseThrow();
        Map<String, Object> release = eventos.stream()
            .filter(e -> "mouseReleased".equals(e.get("type"))).reduce((a, b) -> b).orElseThrow();
        assertThat(press).containsEntry("x", 0).containsEntry("y", 0);
        assertThat(release).containsEntry("x", 60).containsEntry("y", 80);
        assertThat(pointer.getLocation()).containsExactly(60, 80);
    }

    @Test
    void typedConstructorPropagatesPointerTypeToEvents() throws Exception {
        Pointer pen = new Pointer(executor, Pointer.PointerType.PEN);
        pen.setClickJitter(0);

        pen.scroll(0, 10).get(5, java.util.concurrent.TimeUnit.SECONDS);

        Map<String, Object> wheel = dispatched(0);
        assertThat(wheel).containsEntry("pointerType", "pen");
    }
}
