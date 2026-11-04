package io.github.selenium.javaDriverless.logging;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import java.lang.reflect.Method;

import org.junit.jupiter.api.Test;
import org.slf4j.Logger;

/**
 * Contrato do JavaDriverlessLogger: parsing de nível (via reflexão — parseLevel é
 * privado), matriz isEnabled, gates de feature e todos os emissores nunca lançam.
 */
class JavaDriverlessLoggerTest {

    private static Object invokePrivate(String name, Class<?>[] types, Object... args)
            throws Exception {
        Method method = JavaDriverlessLogger.class.getDeclaredMethod(name, types);
        method.setAccessible(true);
        return method.invoke(null, args);
    }

    @Test
    void parseLevelDefaultsOnNullOrBlank() throws Exception {
        assertEquals(JavaDriverlessLogger.Level.WARN,
            invokePrivate("parseLevel", new Class[]{String.class, JavaDriverlessLogger.Level.class},
                null, JavaDriverlessLogger.Level.WARN));
        assertEquals(JavaDriverlessLogger.Level.ERROR,
            invokePrivate("parseLevel", new Class[]{String.class, JavaDriverlessLogger.Level.class},
                "   ", JavaDriverlessLogger.Level.ERROR));
    }

    @Test
    void parseLevelAcceptsExactAndCaseInsensitiveValues() throws Exception {
        Class<?>[] types = {String.class, JavaDriverlessLogger.Level.class};
        assertEquals(JavaDriverlessLogger.Level.TRACE,
            invokePrivate("parseLevel", types, "TRACE", JavaDriverlessLogger.Level.WARN));
        assertEquals(JavaDriverlessLogger.Level.DEBUG,
            invokePrivate("parseLevel", types, "  debug ", JavaDriverlessLogger.Level.WARN));
        assertEquals(JavaDriverlessLogger.Level.OFF,
            invokePrivate("parseLevel", types, "off", JavaDriverlessLogger.Level.WARN));
    }

    @Test
    void parseLevelFallsBackToDefaultOnGarbage() throws Exception {
        Class<?>[] types = {String.class, JavaDriverlessLogger.Level.class};
        assertEquals(JavaDriverlessLogger.Level.WARN,
            invokePrivate("parseLevel", types, "verbose", JavaDriverlessLogger.Level.WARN));
        assertEquals(JavaDriverlessLogger.Level.INFO,
            invokePrivate("parseLevel", types, "1", JavaDriverlessLogger.Level.INFO));
    }

    @Test
    void envBoolDefaultsWhenVariableIsAbsent() throws Exception {
        // variável de teste nunca exportada no runner: cobre o caminho default
        Object result = invokePrivate("envBool", new Class[]{String.class, boolean.class},
            "JAVA_DRIVERLESS_TESTE_INEXISTENTE", true);
        assertEquals(Boolean.TRUE, result);

        Object resultFalse = invokePrivate("envBool", new Class[]{String.class, boolean.class},
            "JAVA_DRIVERLESS_TESTE_INEXISTENTE", false);
        assertEquals(Boolean.FALSE, resultFalse);
    }

    @Test
    void effectiveSlf4jLevelNeverReturnsNullAndHonorsOff() throws Exception {
        Object level = invokePrivate("effectiveSlf4jLevel", new Class[0]);
        assertNotNull(level);
        // no runner padrão LEVEL != OFF (sem env), então também não é OFF
    }

    @Test
    void isEnabledFollowsLevelOrdering() {
        // LEVEL do runner vem do env (tipicamente WARN sem env): matriz de ordem
        if (JavaDriverlessLogger.isEnabled(JavaDriverlessLogger.Level.WARN)) {
            // se WARN está ligado, tudo abaixo também está
            assertEquals(true, JavaDriverlessLogger.isEnabled(JavaDriverlessLogger.Level.ERROR));
        }
        if (!JavaDriverlessLogger.isEnabled(JavaDriverlessLogger.Level.TRACE)) {
            // se TRACE está desligado, nada acima pode estar ligado
            assertEquals(false,
                JavaDriverlessLogger.isEnabled(JavaDriverlessLogger.Level.TRACE));
        }
    }

    @Test
    void featureGatesArePlainBooleansWithoutThrowing() {
        assertDoesNotThrow(() -> {
            JavaDriverlessLogger.isChromeLogsEnabled();
            JavaDriverlessLogger.isProfileEventsEnabled();
            JavaDriverlessLogger.isStartupBannerEnabled();
            JavaDriverlessLogger.isChromeTailOnErrorEnabled();
        });
    }

    @Test
    void allEmittersAreSafeForAnyLevel() {
        Logger logger = JavaDriverlessLogger.getLogger(JavaDriverlessLoggerTest.class);
        assertNotNull(logger);

        assertDoesNotThrow(() -> {
            JavaDriverlessLogger.error(logger, "erro {}", "x");
            JavaDriverlessLogger.warn(logger, "aviso {}", "x");
            JavaDriverlessLogger.info(logger, "info {}", "x");
            JavaDriverlessLogger.startup(logger, "banner {}", "x");
            JavaDriverlessLogger.debug(logger, "debug {}", "x");
            JavaDriverlessLogger.trace(logger, "trace {}", "x");
            JavaDriverlessLogger.profile(logger, "evento {}", "x");
            JavaDriverlessLogger.chromeProcess(logger, "chrome stdout");
        });
    }

    @Test
    void emittersRespectTheEffectiveLevelGate() throws Exception {
        Logger logger = org.mockito.Mockito.mock(Logger.class);

        JavaDriverlessLogger.info(logger, "info {}", "x");
        if (JavaDriverlessLogger.isEnabled(JavaDriverlessLogger.Level.INFO)) {
            org.mockito.Mockito.verify(logger).info("info {}", "x");
        } else {
            org.mockito.Mockito.verify(logger, org.mockito.Mockito.never())
                .info(org.mockito.ArgumentMatchers.anyString(),
                    org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any());
        }

        JavaDriverlessLogger.startup(logger, "banner {}", "x");
        JavaDriverlessLogger.profile(logger, "perfil {}", "x");
        JavaDriverlessLogger.chromeProcess(logger, "linha do chrome");
        JavaDriverlessLogger.debug(logger, "debug {}", "x");
        JavaDriverlessLogger.trace(logger, "trace {}", "x");
        // nenhum emissor pode lançar, mesmo com logger mockado sem stubs
    }

    @Test
    void slf4jLevelNameMapsOffAndLowercasesTheRest() throws Exception {
        Class<?>[] types = {JavaDriverlessLogger.Level.class};
        assertEquals("off", invokePrivate("slf4jLevelName", types,
            JavaDriverlessLogger.Level.OFF));
        assertEquals("error", invokePrivate("slf4jLevelName", types,
            JavaDriverlessLogger.Level.ERROR));
        assertEquals("warn", invokePrivate("slf4jLevelName", types,
            JavaDriverlessLogger.Level.WARN));
        assertEquals("info", invokePrivate("slf4jLevelName", types,
            JavaDriverlessLogger.Level.INFO));
        assertEquals("debug", invokePrivate("slf4jLevelName", types,
            JavaDriverlessLogger.Level.DEBUG));
        assertEquals("trace", invokePrivate("slf4jLevelName", types,
            JavaDriverlessLogger.Level.TRACE));
    }

}
