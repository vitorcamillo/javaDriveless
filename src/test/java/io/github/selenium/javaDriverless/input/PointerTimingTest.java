package io.github.selenium.javaDriverless.input;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/** Timing de eventos de ponteiro: sleeps não derrubam a thread e respeitam interrupção. */
class PointerTimingTest {

    @Test
    void sleepMillisCompletesWithoutInterruption() {
        long start = System.nanoTime();
        PointerTiming.sleepMillis(20);
        long elapsedMs = (System.nanoTime() - start) / 1_000_000;

        assertTrue(elapsedMs >= 15, "elapsed=" + elapsedMs);
        assertFalse(Thread.currentThread().isInterrupted());
    }

    @Test
    void sleepMillisSwallowsInterruptionAndRestoresFlag() {
        Thread.currentThread().interrupt();

        long start = System.nanoTime();
        PointerTiming.sleepMillis(60 * 1000); // 1 minuto: se ignorasse a interrupção, travaria
        long elapsedMs = (System.nanoTime() - start) / 1_000_000;

        assertTrue(elapsedMs < 5_000, "deveria sair imediatamente ao ser interrompido");
        assertTrue(Thread.currentThread().isInterrupted(), "flag de interrupção deve ser restaurada");

        // limpa o flag para não vazar para outros testes do mesmo fork
        Thread.interrupted();
    }

    @Test
    void sleepSecondsCompletesAsynchronously() {
        long start = System.nanoTime();
        PointerTiming.sleepSeconds(0.01).join();
        long elapsedMs = (System.nanoTime() - start) / 1_000_000;

        assertTrue(elapsedMs >= 5, "elapsed=" + elapsedMs);
        assertFalse(Thread.currentThread().isInterrupted());
    }
}
