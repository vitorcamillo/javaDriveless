package io.github.selenium.javaDriverless.support;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;

/** Comportamento de polling/timeout/timeout-exceção do WebDriverWait. */
class WebDriverWaitTest {

    @Test
    void untilReturnsImmediatelyWhenConditionIsTrue() {
        WebDriverWait wait = new WebDriverWait(new Object(), Duration.ofSeconds(2))
            .pollingEvery(Duration.ofMillis(10));

        Boolean result = wait.until(d -> Boolean.TRUE);

        assertEquals(Boolean.TRUE, result);
    }

    @Test
    void untilReturnsNonNullNonFalseValue() {
        WebDriverWait wait = new WebDriverWait(new Object(), Duration.ofSeconds(2))
            .pollingEvery(Duration.ofMillis(10));

        String result = wait.until(d -> "element-found");

        assertEquals("element-found", result);
    }

    @Test
    void untilKeepsPollingUntilConditionTurnsTrue() {
        WebDriverWait wait = new WebDriverWait(new Object(), Duration.ofSeconds(5))
            .pollingEvery(Duration.ofMillis(5));
        AtomicInteger attempts = new AtomicInteger();

        Boolean result = wait.until(d -> attempts.incrementAndGet() >= 3);

        assertEquals(Boolean.TRUE, result);
        assertEquals(3, attempts.get());
    }

    @Test
    void untilTreatsFalseAndNullAsNotReady() {
        // false/null são reavaliados até o timeout; ao esgotar, lança com o último resultado
        assertThrows(RuntimeException.class,
            () -> new WebDriverWait(new Object(), Duration.ofMillis(50))
                .pollingEvery(Duration.ofMillis(5))
                .until(d -> Boolean.FALSE));

        assertThrows(RuntimeException.class,
            () -> new WebDriverWait(new Object(), Duration.ofMillis(50))
                .pollingEvery(Duration.ofMillis(5))
                .until(d -> (Object) null));
    }

    @Test
    void untilThrowsOnTimeoutWithLastResultInMessage() {
        WebDriverWait wait = new WebDriverWait(new Object(), Duration.ofMillis(60))
            .pollingEvery(Duration.ofMillis(5));

        RuntimeException thrown = assertThrows(RuntimeException.class,
            () -> wait.until(d -> null));

        assertTrue(thrown.getMessage().contains("Timeout"));
        assertTrue(thrown.getMessage().contains("null"));
    }

    @Test
    void untilSwallowsConditionExceptionsByDefaultAndReportsLastOnTimeout() {
        WebDriverWait wait = new WebDriverWait(new Object(), Duration.ofMillis(60))
            .pollingEvery(Duration.ofMillis(5));

        RuntimeException thrown = assertThrows(RuntimeException.class,
            () -> wait.until(d -> {
                throw new IllegalStateException("element not found yet");
            }));

        assertEquals("element not found yet", thrown.getCause().getMessage());
    }

    @Test
    void ignoringKeepsExceptionsSwallowed() {
        WebDriverWait wait = new WebDriverWait(new Object(), Duration.ofSeconds(2))
            .pollingEvery(Duration.ofMillis(5))
            .ignoring(IllegalStateException.class);

        AtomicInteger attempts = new AtomicInteger();
        Boolean result = wait.until(d -> {
            if (attempts.incrementAndGet() < 2) {
                throw new IllegalStateException("transient");
            }
            return Boolean.TRUE;
        });

        assertEquals(Boolean.TRUE, result);
    }

    @Test
    void untilThrowsRightAwayWhenConditionFailsAndExceptionsNotIgnoredIsOutOfScope() {
        // ignoreExceptions=false não é exposto por API pública além do padrão; garantir que o
        // caminho de interrupção continua propagando estado de interrupção corretamente.
        Thread current = Thread.currentThread();
        WebDriverWait wait = new WebDriverWait(new Object(), Duration.ofSeconds(5))
            .pollingEvery(Duration.ofMillis(10));

        Thread killer = new Thread(() -> current.interrupt());
        killer.start();

        assertThrows(RuntimeException.class, () -> wait.until(d -> Boolean.FALSE));
        // limpa o flag de interrupção para não vazar para outros testes do fork
        Thread.interrupted();
    }
}
