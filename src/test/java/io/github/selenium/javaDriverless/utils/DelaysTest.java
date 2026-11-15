package io.github.selenium.javaDriverless.utils;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;

/** Pausas não bloqueantes do Delays: duram o esperado e aceitam valores inválidos. */
class DelaysTest {

    @Test
    void millisCompletesAfterDelay() {
        long start = System.nanoTime();
        Delays.millis(50).join();
        long elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);

        assertTrue(elapsedMs >= 40, "elapsed=" + elapsedMs);
    }

    @Test
    void millisNegativeCompletesWithoutException() {
        assertDoesNotThrow(() -> Delays.millis(-5).get(1, TimeUnit.SECONDS));
    }

    @Test
    void secondsNaNCompletesWithoutException() {
        assertDoesNotThrow(() -> Delays.seconds(Double.NaN).get(1, TimeUnit.SECONDS));
    }

    @Test
    void secondsNegativeCompletesWithoutException() {
        assertDoesNotThrow(() -> Delays.seconds(-1).get(1, TimeUnit.SECONDS));
    }

    @Test
    void secondsPositiveWaitsConvertedMillis() {
        long start = System.nanoTime();
        Delays.seconds(0.3).join();
        long elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);

        assertTrue(elapsedMs >= 250, "elapsed=" + elapsedMs);
    }

    @Test
    void secondsZeroAndNaNDoNotWait() {
        assertTrue(waitedUnder(Delays.seconds(0), 200));
        assertTrue(waitedUnder(Delays.seconds(Double.NaN), 200));
    }

    private static boolean waitedUnder(java.util.concurrent.CompletableFuture<Void> f, long maxMs) {
        long start = System.nanoTime();
        f.join();
        return TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start) < maxMs;
    }
}
