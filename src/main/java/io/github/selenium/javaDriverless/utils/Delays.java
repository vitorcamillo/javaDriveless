package io.github.selenium.javaDriverless.utils;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

/** Pausas não bloqueantes: a espera não ocupa thread do ForkJoinPool comum. */
public final class Delays {
    private Delays() {}

    /** Futuro que completa após {@code millis}; negativo vira 0. */
    public static CompletableFuture<Void> millis(long millis) {
        return CompletableFuture.runAsync(() -> { }, CompletableFuture.delayedExecutor(
                Math.max(0, millis), TimeUnit.MILLISECONDS));
    }

    /** Idem em segundos; NaN vira 0 no cast e negativo vira 0 em {@link #millis}. */
    public static CompletableFuture<Void> seconds(double seconds) {
        return millis((long) (seconds * 1000));
    }
}
