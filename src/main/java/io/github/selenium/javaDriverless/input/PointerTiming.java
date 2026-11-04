package io.github.selenium.javaDriverless.input;

import java.util.concurrent.CompletableFuture;

final class PointerTiming {

    private PointerTiming() {
    }

    static CompletableFuture<Void> sleepSeconds(double seconds) {
        return CompletableFuture.runAsync(() -> sleepMillis((long) (seconds * 1000)));
    }

    static void sleepMillis(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
