package io.github.selenium.javaDriverless;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;

import org.junit.jupiter.api.Test;

import io.github.selenium.javaDriverless.profiles.ProfileCatalogException;

/**
 * Resolução do target page inicial e sanitização de falhas de startup:
 * o texto bruto do erro nunca atravessa a fronteira (vira mensagem canônica).
 */
class ChromeStartupFailureSanitizationTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Test
    void picksTheFirstPageTargetWithARealId() throws Exception {
        String targets = "[{\"type\":\"iframe\",\"id\":\"x\"},"
            + "{\"type\":\"page\",\"id\":\"  \"},"
            + "{\"type\":\"page\",\"id\":\"page-1\"}]";

        assertEquals("page-1", ChromeSessionState.resolveInitialPageTargetId(
            MAPPER.readTree(targets), false, () -> {
                throw new AssertionError("criador local não deve ser chamado");
            }));
    }

    @Test
    void fallsBackToLocallyCreatedTargetWhenNoPageExists() throws Exception {
        assertEquals("criado-9", ChromeSessionState.resolveInitialPageTargetId(
            MAPPER.readTree("[]"), false,
            () -> {
                try {
                    return MAPPER.readTree("{\"targetId\":\"criado-9\"}");
                } catch (Exception e) {
                    throw new IllegalStateException(e);
                }
            }));
    }

    @Test
    void attachModeWithoutPageFailsClosed() {
        IllegalStateException thrown = assertThrows(IllegalStateException.class,
            () -> ChromeSessionState.resolveInitialPageTargetId(null, true, () -> null));

        assertEquals("Chrome externo sem target page disponível", thrown.getMessage());
    }

    @Test
    void localCreatorWithoutTargetIdFailsClosed() {
        IllegalStateException thrown = assertThrows(IllegalStateException.class,
            () -> ChromeSessionState.resolveInitialPageTargetId(
                null, false, () -> {
                    try {
                        return MAPPER.readTree("{\"targetId\":\"  \"}");
                    } catch (Exception e) {
                        throw new IllegalStateException(e);
                    }
                }));

        assertEquals("Chrome local não criou target page inicial", thrown.getMessage());
    }

    @Test
    void nullFailureSanitizesToNull() {
        assertNull(ChromeSessionState.sanitizedFailure(null));
    }

    @Test
    void eachFailureTypeKeepsItsClassAndSanitizesTheMessage() {
        Throwable io = ChromeSessionState.sanitizedFailure(
            new IOException("falha com token=abc123 no meio"));
        assertTrue(io instanceof IOException);
        // dupla redação: a regra token=... primeiro, depois a palavra "token" isolada
        assertEquals("falha com <redacted>=<redacted> no meio", io.getMessage());

        Throwable iae = ChromeSessionState.sanitizedFailure(
            new IllegalArgumentException("argumento inválido"));
        assertTrue(iae instanceof IllegalArgumentException);
        assertTrue(iae instanceof RuntimeException);

        Throwable ise = ChromeSessionState.sanitizedFailure(new IllegalStateException("estado ruim"));
        assertTrue(ise instanceof IllegalStateException);
        assertTrue(!(ise instanceof IllegalArgumentException));

        Throwable se = ChromeSessionState.sanitizedFailure(new SecurityException("permissão negada"));
        assertTrue(se instanceof SecurityException);

        Throwable ce = ChromeSessionState.sanitizedFailure(
            new CompletionException("futuro quebrou", new RuntimeException("causa")));
        assertTrue(ce instanceof CompletionException);

        Throwable re = ChromeSessionState.sanitizedFailure(new ArithmeticException("calc"));
        assertTrue(re instanceof RuntimeException);
        assertTrue(!(re instanceof IllegalStateException));

        Throwable checked = ChromeSessionState.sanitizedFailure(new Exception("checked puro"));
        assertTrue(!(checked instanceof RuntimeException));
    }

    @Test
    void profileCatalogFailuresStayInTheCatalogFamily() {
        Throwable sanitized = ChromeSessionState.sanitizedFailure(
            new ProfileCatalogException("catálogo ausente"));

        assertTrue(sanitized instanceof ProfileCatalogException);
    }

    @Test
    void causesAreSanitizedRecursivelyAndSelfCyclesTerminate() {
        IllegalStateException raiz = new IllegalStateException("causa raiz com secret=xyz");
        IOException comCausa = new IOException("wrapper", raiz);

        Throwable sanitized = ChromeSessionState.sanitizedFailure(comCausa);
        assertEquals("causa raiz com <redacted>=<redacted>", sanitized.getCause().getMessage());

        // causa apontando para si mesma não pode loopar infinito
        RuntimeException ciclica = new RuntimeException("ciclo") {
            @Override
            public Throwable getCause() {
                return this;
            }
        };
        Throwable sanitizedCiclica = ChromeSessionState.sanitizedFailure(ciclica);
        assertNull(sanitizedCiclica.getCause());
    }

    @Test
    void stackTraceComesFromTheOriginalFailure() {
        IOException original = new IOException("com stack");
        StackTraceElement[] expected = original.getStackTrace();

        Throwable sanitized = ChromeSessionState.sanitizedFailure(original);

        assertEquals(expected.length, sanitized.getStackTrace().length);
        assertSame(IOException.class, sanitized.getClass());
    }
}
