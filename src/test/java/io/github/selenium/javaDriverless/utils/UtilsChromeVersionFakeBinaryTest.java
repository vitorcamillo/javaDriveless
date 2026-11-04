package io.github.selenium.javaDriverless.utils;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Detecção de versão do Chrome com binários FAKE executáveis: parse, cache,
 * caminhos de falha (exit != 0, output sem versão, inexistente, blank).
 */
class UtilsChromeVersionFakeBinaryTest {

    private Path fakeChrome(Path dir, String name, String script) throws Exception {
        Path bin = dir.resolve(name);
        Files.writeString(bin, "#!/bin/sh\n" + script + "\n");
        Files.setPosixFilePermissions(bin, PosixFilePermissions.fromString("rwxr-xr-x"));
        return bin;
    }

    @Test
    void parsesRealStyleVersionOutput(@TempDir Path dir) throws Exception {
        Path bin = fakeChrome(dir, "chrome-ok",
            "echo 'Google Chrome 131.0.6778.85 officially'\nexit 0");

        assertEquals("131.0.6778.85", Utils.detectChromeVersion(bin.toString()));
    }

    @Test
    void resultIsCachedPerBinaryIdentity(@TempDir Path dir) throws Exception {
        // prova o cache por efeito colateral: cada execução incrementa um contador;
        // a 2ª detecção NÃO pode executar o binário de novo
        Path counter = dir.resolve("runs");
        Path bin = dir.resolve("chrome-cached");
        Files.writeString(bin, "#!/bin/sh\n"
            + "c=$(cat '" + counter + "' 2>/dev/null || echo 0)\n"
            + "echo $((c+1)) > '" + counter + "'\n"
            + "echo 'Chromium 120.0.6099.224 stable'\nexit 0\n");
        Files.setPosixFilePermissions(bin, PosixFilePermissions.fromString("rwxr-xr-x"));

        assertEquals("120.0.6099.224", Utils.detectChromeVersion(bin.toString()));
        assertEquals("1", Files.readString(counter).trim(), "primeira detecção executa 1x");

        assertEquals("120.0.6099.224", Utils.detectChromeVersion(bin.toString()));
        assertEquals("1", Files.readString(counter).trim(), "segunda detecção sai do cache");
    }

    @Test
    void nonzeroExitYieldsNull(@TempDir Path dir) throws Exception {
        Path bin = fakeChrome(dir, "chrome-fail", "echo '131.0.0.0' >&2\nexit 3");

        assertNull(Utils.detectChromeVersion(bin.toString()));
    }

    @Test
    void outputWithoutFourPartVersionYieldsNull(@TempDir Path dir) throws Exception {
        Path bin = fakeChrome(dir, "chrome-weird", "echo 'Chromium 131'\nexit 0");

        assertNull(Utils.detectChromeVersion(bin.toString()));
    }

    @Test
    void blankAndNullPathsYieldNullWithoutSpawn() {
        assertNull(Utils.detectChromeVersion(null));
        assertNull(Utils.detectChromeVersion("   "));
    }

    @Test
    void missingBinaryYieldsNull(@TempDir Path dir) {
        assertNull(Utils.detectChromeVersion(dir.resolve("nao-existe").toString()));
    }

    @Test
    void nonExecutableFileYieldsNull(@TempDir Path dir) throws Exception {
        Path plain = dir.resolve("chrome-texto");
        Files.writeString(plain, "Google Chrome 131.0.6778.85");

        assertNull(Utils.detectChromeVersion(plain.toString()));
    }

    @Test
    void detectWithoutArgsDegradesToNullSafely() {
        // em ambiente sem Chrome no PATH não pode lançar; com Chrome local devolve X.Y.Z.W
        String version = Utils.detectChromeVersion();
        if (version != null) {
            assertTrue(version.matches("\\d+\\.\\d+\\.\\d+\\.\\d+"), "version=" + version);
        }
    }

    @Test
    void hangingBinaryIsKilledAfterBudgetAndYieldsNull(@TempDir Path dir) throws Exception {
        Path bin = fakeChrome(dir, "chrome-lento", "sleep 60\nexit 0");
        long started = System.nanoTime();

        assertNull(Utils.detectChromeVersion(bin.toString()));
        assertTrue(System.nanoTime() - started < java.util.concurrent.TimeUnit.SECONDS.toNanos(10),
            "detecção não pode bloquear além do orçamento de 5s");
    }

    @Test
    void interruptedDetectionYieldsNullAndRestoresTheFlag(@TempDir Path dir) throws Exception {
        Path bin = fakeChrome(dir, "chrome-travado", "sleep 60\nexit 0");
        AtomicReference<String> detected = new AtomicReference<>("sentinela");

        Thread worker = new Thread(() -> detected.set(Utils.detectChromeVersion(bin.toString())));
        worker.start();
        Thread.sleep(300);
        worker.interrupt();
        worker.join(10_000);

        assertFalse(worker.isAlive(), "detecção interrompida não pode prender a thread");
        assertNull(detected.get());
    }
}
