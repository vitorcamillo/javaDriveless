package io.github.selenium.javaDriverless.input;

import java.util.concurrent.CompletableFuture;

import io.github.selenium.javaDriverless.utils.Delays;

final class PointerTiming {

    private PointerTiming() {
    }

    static CompletableFuture<Void> sleepSeconds(double seconds) {
        return Delays.seconds(seconds);
    }

    static void sleepMillis(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
