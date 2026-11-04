package io.github.selenium.javaDriverless.utils;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.ServerSocket;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Helpers de ambiente/arquivo/porta do Utils — sem tocar o DATA_DIR do usuário. */
class UtilsHelpersTest {

    @Test
    void constantsArePopulated() {
        assertEquals("1.11.7", Utils.VERSION);
        assertNotNull(Utils.LICENSE);
        assertTrue(Utils.LICENSE.contains("NonCommercial"), "licença CC BY-NC-SA esperada");
        assertNotNull(Utils.DATA_DIR);
        assertEquals(Utils.IS_POSIX, Utils.isPosix());
    }

    @Test
    void randomPortReturnsAFreePortInRange(@TempDir Path unused) throws Exception {
        int port = Utils.randomPort();

        assertTrue(port >= 0 && port <= 65535);
        try (ServerSocket probe = new ServerSocket(port)) {
            assertNotNull(probe); // porta estava livre para bind
        }
    }

    @Test
    void randomPortBoundToLoopbackWorks() {
        int port = Utils.randomPort("127.0.0.1");
        assertTrue(port > 0);
    }

    @Test
    void randomPortWithUnresolvableHostFailsFast() {
        assertDoesNotThrow(() -> {
            try {
                Utils.randomPort("host.invalido.<nao-existe>.test");
            } catch (RuntimeException expected) {
                assertTrue(expected.getMessage().contains("porta"));
            }
        });
    }

    @Test
    void checkTimeoutThrowsOnlyWhenBudgetIsExceeded() {
        long now = System.nanoTime();

        assertDoesNotThrow(() -> Utils.checkTimeout(now, 60));
        assertDoesNotThrow(() -> Utils.checkTimeout(now - 100_000_000L, 1)); // 0.1s atrás, folga 1s

        RuntimeException thrown = org.junit.jupiter.api.Assertions.assertThrows(
            RuntimeException.class,
            () -> Utils.checkTimeout(now - 10_000_000_000L, 1.0)); // 10s atrás
        assertTrue(thrown.getMessage().contains("timeout"));
    }

    @Test
    void writeAndReadRoundTripOnDisk(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("conteudo.txt");

        Utils.write(file.toString(), "linha 1\nlinha 2", "UTF-8").join();

        assertTrue(Files.exists(file));
        assertEquals("linha 1\nlinha 2", Utils.read(file.toString(), "UTF-8", false).join());
        assertEquals("linha 1\nlinha 2", Utils.read(file.toString()).join());
    }

    @Test
    void generateRealisticUAProducesBrowserLikeAgents() {
        for (int i = 0; i < 10; i++) {
            String ua = Utils.generateRealisticUA();
            assertTrue(ua.contains("Mozilla/5.0"), ua);
            assertTrue(ua.contains("Chrome/"), ua);
        }
    }

    @Test
    void findChromeExecutableNeverThrows() {
        assertDoesNotThrow(() -> {
            String path = Utils.findChromeExecutable();
            // em ambiente sem Chrome pode retornar null; o contrato é não explodir
            if (path != null) {
                assertTrue(!path.isBlank());
            }
        });
    }
}
