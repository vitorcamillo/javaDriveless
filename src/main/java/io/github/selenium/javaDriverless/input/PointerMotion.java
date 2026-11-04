package io.github.selenium.javaDriverless.input;

import io.github.selenium.javaDriverless.scripts.Geometry;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.BiFunction;
import java.util.function.Function;

final class PointerMotion {

    private PointerMotion() {
    }

    static List<int[]> humanizedPath(int[] from, int x, int y, double smoothSoft) {
        List<double[]> points = new ArrayList<>();
        points.add(new double[]{from[0], from[1]});
        points.add(new double[]{x, y});

        return Geometry.genCombinedPath(
            points,
            5,           // n_points_soft
            smoothSoft,  // smooth_soft
            100,         // n_points_distort
            0.4          // smooth_distort
        );
    }

    static CompletableFuture<int[]> movePath(double totalTime,
                                             Function<Double, int[]> posFromTimeCallback,
                                             double freqAssumption,
                                             BiFunction<Integer, Integer, CompletableFuture<Void>> moveToRaw) {
        long startNanos = System.nanoTime();
        long sleepMs = (long) ((1.0 / freqAssumption) * 1000);
        // thenCompose: exceção síncrona do 1º passo vira future falho (como no supplyAsync antigo).
        return CompletableFuture.<Void>completedFuture(null)
            .thenCompose(v -> step(startNanos, sleepMs, totalTime, posFromTimeCallback, moveToRaw, null));
    }

    /**
     * Passo recursivo assíncrono: move a cada frame de sleepMs até elapsed > totalTime
     * (mesma semântica de antes, mas sem supplyAsync/join/Thread.sleep — não prende
     * thread do ForkJoinPool comum; o atraso entre frames usa delayedExecutor).
     */
    private static CompletableFuture<int[]> step(long startNanos,
                                                 long sleepMs,
                                                 double totalTime,
                                                 Function<Double, int[]> posFromTimeCallback,
                                                 BiFunction<Integer, Integer, CompletableFuture<Void>> moveToRaw,
                                                 int[] lastPos) {
        double elapsed = (System.nanoTime() - startNanos) / 1_000_000_000.0;

        if (elapsed > totalTime) {
            return CompletableFuture.completedFuture(lastPos);
        }

        int[] pos = posFromTimeCallback.apply(elapsed);
        return moveToRaw.apply(pos[0], pos[1])
            .thenCompose(v -> CompletableFuture.runAsync(() -> {},
                CompletableFuture.delayedExecutor(sleepMs, TimeUnit.MILLISECONDS)))
            .thenCompose(v -> step(startNanos, sleepMs, totalTime, posFromTimeCallback, moveToRaw, pos));
    }
}
