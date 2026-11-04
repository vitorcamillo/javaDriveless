package io.github.selenium.javaDriverless;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.attribute.PosixFilePermissions;
import java.nio.file.Path;
import java.util.Base64;
import java.util.Map;
import java.util.Optional;
import java.time.Instant;
import java.util.concurrent.CompletableFuture;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Ciclo de vida dos arquivos de PID do ProfileManager: gravação/leitura, migração
 * legada, listagem, limpeza de órfãos e encerramento de processo real (dummy).
 */
class ProfileManagerLifecycleTest {

    @TempDir
    Path tempDir;

    private static String encodedName(String name) {
        return Base64.getUrlEncoder().withoutPadding()
            .encodeToString(name.getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }

    @Test
    void pidRoundTripThroughSanitizedFileNames() throws Exception {
        ProfileManager manager = new ProfileManager(tempDir.resolve("perfis").toString());

        manager.savePid("Bot Bet/365", 4242L);

        Path pidFile = manager.getPidFilePath("Bot Bet/365");
        assertTrue(Files.exists(pidFile));
        assertTrue(pidFile.toString().endsWith(".pid"));
        assertEquals(Optional.of(4242L), manager.loadPid("Bot Bet/365"));
        assertFalse(manager.isProcessRunning(999_999_999L),
            "PID inexistente nunca aparece como rodando");

        manager.deletePidFile("Bot Bet/365");
        assertEquals(Optional.empty(), manager.loadPid("Bot Bet/365"));
        manager.deletePidFile("Bot Bet/365"); // idempotente
    }

    @Test
    void loadPidToleratesMissingEmptyAndGarbageFiles() throws Exception {
        ProfileManager manager = new ProfileManager(tempDir.resolve("perfis").toString());

        assertEquals(Optional.empty(), manager.loadPid("nunca-salvo"));

        Path lixo = manager.getPidFilePath("lixo");
        Files.createDirectories(lixo.getParent());
        Files.writeString(lixo, "não-é-número\n");
        assertEquals(Optional.empty(), manager.loadPid("lixo"));

        Files.write(manager.getPidFilePath("vazio"), new byte[0]);
        assertEquals(Optional.empty(), manager.loadPid("vazio"));
    }

    @Test
    void constructorFailsClosedWhenProfilesDirectoryCannotBeCreated(@TempDir Path arquivoRaiz)
        throws Exception {
        Path arquivo = arquivoRaiz.resolve("arquivo");
        Files.writeString(arquivo, "bloqueia o caminho");

        RuntimeException thrown = assertThrows(RuntimeException.class,
            () -> new ProfileManager(arquivo.resolve("sub").toString()));
        assertTrue(thrown.getMessage().contains("Erro ao criar diretório de profiles"));
    }

    @Test
    void listActiveProfilesPrefersTheLogicalNameAndFallsBackToFileName() throws Exception {
        ProfileManager manager = new ProfileManager(tempDir.resolve("perfis").toString());
        manager.savePid("Perfil Válido", 111L);

        // arquivo legado sem linha de nome lógico: cai para o nome do arquivo
        Path legado = manager.getPidFilePath("legado").getParent()
            .resolve("perfil-legado.pid");
        Files.writeString(legado, "222\n123456789\n");

        var perfis = manager.listActiveProfiles();

        assertTrue(perfis.contains("Perfil Válido"));
        assertTrue(perfis.contains("perfil-legado"));
    }

    @Test
    void cleanupOrphanedPidFilesRemovesOnlyDeadProcesses() throws Exception {
        ProfileManager manager = new ProfileManager(tempDir.resolve("perfis").toString());
        Process vivo = new ProcessBuilder("/bin/sh", "-c", "sleep 30").start();
        try {
            manager.savePid("vivo", vivo.pid());
            manager.savePid("morto", 999_999_999L);

            manager.cleanupOrphanedPidFiles();

            assertTrue(Files.exists(manager.getPidFilePath("vivo")),
                "processo vivo não pode ser limpo");
            assertFalse(Files.exists(manager.getPidFilePath("morto")),
                "PID de processo morto é órfão e deve ser removido");
        } finally {
            vivo.destroyForcibly();
        }
    }

    @Test
    void killProcessTerminatesADummyProcess() throws Exception {
        ProfileManager manager = new ProfileManager(tempDir.resolve("perfis").toString());
        Process dummy = new ProcessBuilder("/bin/sh", "-c", "sleep 30").start();
        try {
            assertTrue(manager.isProcessRunning(dummy.pid()));
            assertTrue(manager.killProcess(dummy.pid()));
            assertFalse(manager.isProcessRunning(dummy.pid()),
                "processo precisa estar morto após o kill");
        } finally {
            dummy.destroyForcibly();
        }
    }

    @Test
    void killProcessForcesTerminationWhenTheProcessIgnoresGracefulShutdown()
        throws Exception {
        ProfileManager manager = new ProfileManager(tempDir.resolve("perfis").toString());
        // trap '' TERM: ignora SIGTERM gracioso — força o caminho destroyForcibly
        Process teimoso = new ProcessBuilder("/bin/sh", "-c", "trap '' TERM; sleep 30").start();
        try {
            assertTrue(manager.killProcess(teimoso.pid()));
            assertFalse(manager.isProcessRunning(teimoso.pid()),
                "SIGKILL forçado precisa encerrar o processo teimoso");
        } finally {
            teimoso.destroyForcibly();
        }
    }

    @Test
    void killProcessReturnsFalseForUnknownAndDeadPids() throws Exception {
        ProfileManager manager = new ProfileManager(tempDir.resolve("perfis").toString());

        assertFalse(manager.killProcess(999_999_999L));

        Process encerrado = new ProcessBuilder("/bin/true").start();
        encerrado.onExit().get(5, java.util.concurrent.TimeUnit.SECONDS);
        assertFalse(manager.killProcess(encerrado.pid()));
    }

    @Test
    void killProcessByProfileCoversMissingDeadAndAliveCases() throws Exception {
        ProfileManager manager = new ProfileManager(tempDir.resolve("perfis").toString());

        // sem PID salvo
        assertFalse(manager.killProcessByProfile("sem-pid"));

        // PID de processo morto: limpa o arquivo
        manager.savePid("morto", 999_999_999L);
        assertFalse(manager.killProcessByProfile("morto"));
        assertFalse(Files.exists(manager.getPidFilePath("morto")));

        // processo vivo: mata e limpa
        Process dummy = new ProcessBuilder("/bin/sh", "-c", "sleep 30").start();
        try {
            manager.savePid("vivo", dummy.pid());
            assertTrue(manager.killProcessByProfile("vivo"));
            assertFalse(Files.exists(manager.getPidFilePath("vivo")));
        } finally {
            dummy.destroyForcibly();
        }
    }

    @Test
    void killProcessByProfileRefusesPidWhoseStartInstantDoesNotMatch() throws Exception {
        ProfileManager manager = new ProfileManager(tempDir.resolve("perfis").toString());
        Process dummy = new ProcessBuilder("/bin/sh", "-c", "sleep 30").start();
        try {
            manager.savePid("reciclado", dummy.pid());
            Path pidFile = manager.getPidFilePath("reciclado");
            var startInstant = ProcessHandle.of(dummy.pid()).flatMap(handle -> handle.info().startInstant());
            assertTrue(startInstant.isPresent(), "o SO deve expor o instante de início do processo");

            var lines = Files.readAllLines(pidFile);
            long differentStart = startInstant.get().toEpochMilli() - 60_000;
            lines.removeIf(line -> line.startsWith("start="));
            lines.add("start=" + differentStart);
            Files.write(pidFile, lines);

            assertFalse(manager.killProcessByProfile("reciclado"));
            assertTrue(dummy.isAlive(), "processo com instante de início diferente deve permanecer vivo");
            assertFalse(Files.exists(pidFile), "arquivo PID sem identidade válida deve ser removido");
        } finally {
            dummy.destroyForcibly();
        }
    }

    @Test
    void killProcessByProfileTerminatesTheHandleWhoseIdentityWasValidated() throws Exception {
        ProfileManager manager = new ProfileManager(tempDir.resolve("perfis").toString());
        long pid = 4242L;
        Instant start = Instant.parse("2026-10-31T12:00:00Z");
        Files.writeString(manager.getPidFilePath("mesmo-handle"),
                pid + "\n" + System.currentTimeMillis() + "\nstart=" + start.toEpochMilli());

        ProcessHandle validated = mock(ProcessHandle.class);
        ProcessHandle.Info info = mock(ProcessHandle.Info.class);
        when(validated.pid()).thenReturn(pid);
        when(validated.isAlive()).thenReturn(true);
        when(validated.info()).thenReturn(info);
        when(info.startInstant()).thenReturn(Optional.of(start));
        when(validated.destroy()).thenReturn(true);
        when(validated.onExit()).thenReturn(CompletableFuture.completedFuture(validated));

        assertTrue(manager.killProcessByProfile("mesmo-handle", pid, Optional.of(validated)));

        verify(validated).destroy();
    }

    @Test
    void cleanupOldProcessRefusesLegacyPidFileWithoutStartInstant() throws Exception {
        ProfileManager manager = new ProfileManager(tempDir.resolve("perfis").toString());
        Process dummy = new ProcessBuilder("/bin/sh", "-c", "sleep 30").start();
        try {
            Files.writeString(manager.getPidFilePath("legado"), dummy.pid() + "\n"
                + System.currentTimeMillis() + "\nprofileNameBase64=" + encodedName("legado") + "\n");

            assertFalse(manager.cleanupOldProcess("legado"));
            assertTrue(dummy.isAlive(), "arquivo legado não comprova a identidade do processo");
        } finally {
            dummy.destroyForcibly();
        }
    }

    @Test
    void savePidRecordsProcessStartInstant() throws Exception {
        ProfileManager manager = new ProfileManager(tempDir.resolve("perfis").toString());
        Process dummy = new ProcessBuilder("/bin/sh", "-c", "sleep 30").start();
        try {
            manager.savePid("com-instant", dummy.pid());

            assertTrue(Files.readAllLines(manager.getPidFilePath("com-instant")).stream()
                .anyMatch(line -> line.startsWith("start=")));
        } finally {
            dummy.destroyForcibly();
        }
    }

    @Test
    void cleanupOldProcessHandlesTheThreeLifecycleStates() throws Exception {
        ProfileManager manager = new ProfileManager(tempDir.resolve("perfis").toString());

        assertFalse(manager.cleanupOldProcess("nunca-existiu"));

        manager.savePid("falecido", 999_999_999L);
        assertFalse(manager.cleanupOldProcess("falecido"));
        assertFalse(Files.exists(manager.getPidFilePath("falecido")));

        Process dummy = new ProcessBuilder("/bin/sh", "-c", "sleep 30").start();
        try {
            manager.savePid("velho", dummy.pid());
            assertTrue(manager.cleanupOldProcess("velho"));
        } finally {
            dummy.destroyForcibly();
        }
    }

    @Test
    void profileInfoReportsPidAndRunningState() throws Exception {
        ProfileManager manager = new ProfileManager(tempDir.resolve("perfis").toString());

        Map<String, Object> semProcesso = manager.getProfileInfo("vazio");
        assertNull(semProcesso.get("pid"));
        assertEquals(false, semProcesso.get("isRunning"));
        assertEquals("vazio", semProcesso.get("profileName"));

        Process dummy = new ProcessBuilder("/bin/sh", "-c", "sleep 30").start();
        try {
            manager.savePid("rodando", dummy.pid());
            Map<String, Object> info = manager.getProfileInfo("rodando");
            assertEquals(dummy.pid(), info.get("pid"));
            assertEquals(true, info.get("isRunning"));
            // o comando do processo dummy aparece quando o SO expõe
            Object comando = info.get("command");
            if (comando != null) {
                assertTrue(comando.toString().contains("sh"));
            }
        } finally {
            dummy.destroyForcibly();
        }
    }

    @Test
    void legacyPidFileWithMatchingLogicalNameIsMigratedInPlace() throws Exception {
        Path perfis = tempDir.resolve("perfis");
        Files.createDirectories(perfis);
        // nome antigo sanitizado many-to-one: espaços viram underscore
        Path legado = perfis.resolve("Bot_Bet_365.pid");
        Files.writeString(legado, "4242\n1699999999999\nprofileNameBase64="
            + encodedName("Bot Bet 365") + "\n");

        ProfileManager manager = new ProfileManager(perfis.toString());
        Path destino = manager.getPidFilePath("Bot Bet 365");

        assertFalse(Files.exists(legado), "arquivo legado é movido");
        assertTrue(Files.exists(destino), "target novo recebe o arquivo");
        assertEquals(Optional.of(4242L), manager.loadPid("Bot Bet 365"));
    }

    @Test
    void legacyPidFileWithoutMatchingLogicalNameIsLeftAlone() throws Exception {
        Path perfis = tempDir.resolve("perfis");
        Files.createDirectories(perfis);
        Path legado = perfis.resolve("Outro_Bot.pid");
        Files.writeString(legado, "4242\n1699999999999\nprofileNameBase64="
            + encodedName("Nome Diferente") + "\n");

        ProfileManager manager = new ProfileManager(perfis.toString());
        manager.getPidFilePath("Outro Bot");

        assertTrue(Files.exists(legado), "sem prova de dono, o legado não é tocado");
    }

    @Test
    void savePidWritesOwnerOnlyPidFile() throws Exception {
        assumeTrue(FileSystems.getDefault().supportedFileAttributeViews().contains("posix"));
        ProfileManager manager = new ProfileManager(tempDir.resolve("perfis").toString());

        manager.savePid("perfil-a", 4242L);

        Path p = manager.getPidFilePath("perfil-a");
        assertEquals(PosixFilePermissions.fromString("rw-------"), Files.getPosixFilePermissions(p));
    }

    @Test
    void legacyPidFileAsSymbolicLinkFailsClosed() throws Exception {
        Path perfis = tempDir.resolve("perfis");
        Files.createDirectories(perfis);
        Path real = perfis.resolve("alvo-real.pid");
        Files.writeString(real, "4242\n1699999999999\n");
        Path legado = perfis.resolve("Bot_Link.pid");
        Files.createSymbolicLink(legado, real);

        ProfileManager manager = new ProfileManager(perfis.toString());

        assertThrows(IllegalStateException.class, () -> manager.getPidFilePath("Bot Link"));
    }
}
