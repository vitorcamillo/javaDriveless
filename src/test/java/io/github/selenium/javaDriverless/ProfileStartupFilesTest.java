package io.github.selenium.javaDriverless;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.ServerSocket;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class ProfileStartupFilesTest {
    @TempDir
    Path tempDir;

    @Test
    void preservesStartupFilesOwnedByLiveProcess() throws Exception {
        Path lock = tempDir.resolve("SingletonLock");
        Files.createSymbolicLink(lock, Path.of("localhost-" + ProcessHandle.current().pid()));
        Files.writeString(tempDir.resolve("DevToolsActivePort"), "9222\n/devtools/browser/test");

        ProfileStartupFiles.unlockStaleLocks(tempDir);

        assertThat(Files.exists(lock, LinkOption.NOFOLLOW_LINKS)).isTrue();
        assertThat(tempDir.resolve("DevToolsActivePort")).exists();
    }

    @Test
    void preservesAmbiguousLockInsteadOfDeletingConcurrentProfile() throws Exception {
        Path lock = tempDir.resolve("SingletonLock");
        Files.writeString(lock, "owner-unknown");

        ProfileStartupFiles.unlockStaleLocks(tempDir);

        assertThat(lock).exists();
    }

    @Test
    void preservesReachableDevToolsPortWithoutSingletonLock() throws Exception {
        try (ServerSocket devTools = new ServerSocket(0)) {
            Path portFile = tempDir.resolve("DevToolsActivePort");
            Files.writeString(portFile, devTools.getLocalPort() + "\n/devtools/browser/test");

            ProfileStartupFiles.unlockStaleLocks(tempDir);

            assertThat(portFile).exists();
        }
    }

    @Test
    void removesFilesWhoseRecordedOwnerIsDead() throws Exception {
        Path lock = tempDir.resolve("SingletonLock");
        Files.createSymbolicLink(lock, Path.of("localhost-" + Long.MAX_VALUE));
        Path cookie = tempDir.resolve("SingletonCookie");
        Files.writeString(cookie, "stale");
        Path portFile = tempDir.resolve("DevToolsActivePort");
        Files.writeString(portFile, "1\n/devtools/browser/stale");

        ProfileStartupFiles.unlockStaleLocks(tempDir, () -> {});

        assertThat(Files.exists(lock, LinkOption.NOFOLLOW_LINKS)).isFalse();
        assertThat(cookie).doesNotExist();
        assertThat(portFile).doesNotExist();
    }

    @Test
    void defaultPolicyPreservesEvenProvenStaleLockWithoutExplicitOptIn() throws Exception {
        Path lock = tempDir.resolve("SingletonLock");
        Files.createSymbolicLink(lock, Path.of("localhost-" + Long.MAX_VALUE));

        boolean available = ProfileStartupFiles.unlockStaleLocks(tempDir, false, () -> {});

        assertThat(available).isFalse();
        assertThat(Files.exists(lock, LinkOption.NOFOLLOW_LINKS)).isTrue();
    }

    @Test
    void abortsDeletionWhenLockIsReplacedAfterInitialInspection() throws Exception {
        Path lock = tempDir.resolve("SingletonLock");
        Files.createSymbolicLink(lock, Path.of("localhost-" + Long.MAX_VALUE));
        Path cookie = tempDir.resolve("SingletonCookie");
        Files.writeString(cookie, "stale");
        Path portFile = tempDir.resolve("DevToolsActivePort");
        Files.writeString(portFile, "1\n/devtools/browser/stale");
        Path replacementTarget = Path.of("localhost-" + (Long.MAX_VALUE - 1));

        ProfileStartupFiles.unlockStaleLocks(tempDir, () -> replaceSymbolicLink(lock, replacementTarget));

        assertThat(Files.readSymbolicLink(lock)).isEqualTo(replacementTarget);
        assertThat(cookie).exists();
        assertThat(portFile).exists();
    }

    private static void replaceSymbolicLink(Path lock, Path target) {
        try {
            Files.delete(lock);
            Files.createSymbolicLink(lock, target);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    @Test
    void deleteDirectoryIgnoresMissingTrees() {
        org.junit.jupiter.api.Assertions.assertDoesNotThrow(
            () -> ProfileStartupFiles.deleteDirectory(tempDir.resolve("nunca-existiu")));
    }

    @Test
    void unlockTreatsMissingOrNullProfilesAsAvailable() {
        assertThat(ProfileStartupFiles.unlockStaleLocks(null)).isTrue();
        assertThat(ProfileStartupFiles.unlockStaleLocks(tempDir.resolve("nao-existe"))).isTrue();
    }

    @Test
    void oversizedOrIlegibleSingletonLockIsTreatedAsAbsentAndPortOutOfRangeDoesNotBlock()
        throws Exception {
        // lock > 4096 bytes é rejeitado na leitura: vira snapshot vazio
        Files.writeString(tempDir.resolve("SingletonLock"), "x".repeat(5000));
        // porta fora do range nunca é tratada como DevTools acessível
        Files.writeString(tempDir.resolve("DevToolsActivePort"), "99999\n/devtools/browser/x");

        assertThat(ProfileStartupFiles.unlockStaleLocks(tempDir)).isTrue();
    }

    @Test
    void nonNumericDevToolsPortIsNotReachableEvidence() throws Exception {
        Files.writeString(tempDir.resolve("DevToolsActivePort"), "não-sou-porta\n");

        assertThat(ProfileStartupFiles.unlockStaleLocks(tempDir)).isTrue();
    }

    @Test
    void undeletableStartupFilesFailTheUnlockWithoutThrowing() throws Exception {
        Path lock = tempDir.resolve("SingletonLock");
        Files.createSymbolicLink(lock, Path.of("localhost-" + Long.MAX_VALUE));

        var original = Files.getPosixFilePermissions(tempDir);
        Files.setPosixFilePermissions(tempDir,
            java.nio.file.attribute.PosixFilePermissions.fromString("r-x------"));
        try {
            boolean disponivel = ProfileStartupFiles.unlockStaleLocks(tempDir);
            assertThat(disponivel).isFalse();
            assertThat(Files.exists(lock, LinkOption.NOFOLLOW_LINKS)).isTrue();
        } finally {
            Files.setPosixFilePermissions(tempDir, original);
        }
    }
}
