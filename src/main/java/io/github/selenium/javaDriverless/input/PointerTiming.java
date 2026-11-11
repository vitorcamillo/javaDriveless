package io.github.selenium.javaDriverless.input;

import java.util.concurrent.CompletableFuture;

final class PointerTiming {

    private PointerTiming() {
    }

    static CompletableFuture<Void> sleepSeconds(double seconds) {
        // delayedExecutor: a espera não ocupa thread do ForkJoinPool comum.
        return CompletableFuture.runAsync(() -> { }, CompletableFuture.delayedExecutor(
                (long) (seconds * 1000), java.util.concurrent.TimeUnit.MILLISECONDS));
    }

    static void sleepMillis(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
