package io.github.selenium.javaDriverless;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/** Exceção de etapa essencial de fingerprint no startup: estágio + causa preservados. */
class FingerprintStartupExceptionTest {

    @Test
    void carriesStageAndCause() {
        IllegalStateException cause = new IllegalStateException("override falhou");

        FingerprintStartupException ex = new FingerprintStartupException("webgl-spoof", cause);

        assertEquals("webgl-spoof", ex.getStage());
        assertSame(cause, ex.getCause());
        assertTrue(ex.getMessage().contains("webgl-spoof"));
        assertTrue(ex.getMessage().contains("sessão não iniciada"));
    }
}
