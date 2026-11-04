package io.github.selenium.javaDriverless.input;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Caracterizacao do caminho humanizado de movimento do ponteiro.
 */
class PointerMotionPathTest {

    @Test
    void humanizedPathStartsAtSourceAndEndsAtTarget() {
        List<int[]> path = PointerMotion.humanizedPath(new int[]{12, 34}, 200, 150, 0.5);

        assertThat(path).isNotEmpty();
        assertThat(path.size()).isGreaterThanOrEqualTo(2);
        assertThat(path.get(0)).containsExactly(12, 34);
        assertThat(path.get(path.size() - 1)).containsExactly(200, 150);
    }

    @Test
    void humanizedPathIsMonotonicInDeduplicatedPoints() {
        List<int[]> path = PointerMotion.humanizedPath(new int[]{0, 0}, 400, 300, 0.5);

        // genCombinedPath descarta pontos consecutivos duplicados: nenhum par
        // adjacente pode se repetir integralmente.
        for (int i = 1; i < path.size(); i++) {
            int[] previous = path.get(i - 1);
            int[] current = path.get(i);
            assertThat(previous[0] == current[0] && previous[1] == current[1]).isFalse();
        }
    }

    @Test
    void movePathCompletesAsynchronouslyWithLastPosition() throws Exception {
        java.util.concurrent.atomic.AtomicInteger calls = new java.util.concurrent.atomic.AtomicInteger();
        java.util.function.BiFunction<Integer, Integer, java.util.concurrent.CompletableFuture<Void>> moveToRaw =
            (x, y) -> {
                calls.incrementAndGet();
                return java.util.concurrent.CompletableFuture.completedFuture(null);
            };
        java.util.concurrent.CompletableFuture<int[]> f = PointerMotion.movePath(
            0.08, e -> new int[]{(int) (e * 1000), 7}, 100.0, moveToRaw);
        int[] last = f.get(5, java.util.concurrent.TimeUnit.SECONDS);
        assertThat(last).isNotNull();
        assertThat(last[1]).isEqualTo(7);
        assertThat(calls.get()).isGreaterThanOrEqualTo(2);
    }

    @Test
    void movePathWithElapsedDurationReturnsNullWithoutMoving() throws Exception {
        java.util.concurrent.atomic.AtomicInteger calls = new java.util.concurrent.atomic.AtomicInteger();
        java.util.function.BiFunction<Integer, Integer, java.util.concurrent.CompletableFuture<Void>> moveToRaw =
            (x, y) -> {
                calls.incrementAndGet();
                return java.util.concurrent.CompletableFuture.completedFuture(null);
            };
        java.util.concurrent.CompletableFuture<int[]> f = PointerMotion.movePath(
            -1.0, e -> new int[]{(int) (e * 1000), 7}, 100.0, moveToRaw);
        assertThat(f.get(1, java.util.concurrent.TimeUnit.SECONDS)).isNull();
        assertThat(calls.get()).isEqualTo(0);
    }

    @Test
    void humanizedPathStaysNearTheBoundingBoxOfBothEndpoints() {
        // Comportamento atual pinado: a distorcao humana faz pequeno overshoot
        // alem dos endpoints (feature de naturalidade). O caminho precisa
        // permanecer na vizinhanca do segmento origem -> destino, sem saltar
        // para outra regiao da tela.
        List<int[]> path = PointerMotion.humanizedPath(new int[]{100, 100}, 300, 200, 0.5);
        int xMargin = 100 + (300 - 100) / 4;
        int yMargin = 100 + (200 - 100) / 4;

        assertThat(path).allSatisfy(point -> {
            assertThat(point[0]).isBetween(100 - xMargin, 300 + xMargin);
            assertThat(point[1]).isBetween(100 - yMargin, 200 + yMargin);
        });
    }
}
