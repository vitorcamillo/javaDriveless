package io.github.selenium.javaDriverless;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ProfileLegacyMigrationTest {
    @TempDir
    Path root;

    @Test
    void legacyUserDataDirectoryIsMovedToTheCollisionSafeKey() throws Exception {
        Path legacy = Files.createDirectories(root.resolve("Meu Bot"));
        Files.writeString(legacy.resolve("Cookies"), "persistent-session");
        Path target = ProfileKey.resolveUnder(root, "Meu Bot");

        Path migrated = ProfileKey.migrateLegacyDirectory(root, "Meu Bot", target);

        assertThat(migrated).isEqualTo(target);
        assertThat(target.resolve("Cookies")).hasContent("persistent-session");
        assertThat(legacy).doesNotExist();
    }

    @Test
    void identifiedLegacyPidFileIsMigratedBeforeItIsLoaded() throws Exception {
        String logicalName = java.util.Base64.getUrlEncoder().withoutPadding()
                .encodeToString("Meu Bot".getBytes(java.nio.charset.StandardCharsets.UTF_8));
        Files.writeString(root.resolve("Meu_Bot.pid"),
                "424242\n0\nprofileNameBase64=" + logicalName);
        ProfileManager manager = new ProfileManager(root.toString());

        assertThat(manager.loadPid("Meu Bot")).contains(424242L);
        assertThat(manager.getPidFilePath("Meu Bot")).exists();
        assertThat(root.resolve("Meu_Bot.pid")).doesNotExist();
        assertThat(manager.listActiveProfiles()).containsExactly("Meu Bot");
    }

    @Test
    void unidentifiedLegacyPidIsNotClaimedFromAnAmbiguousSanitizedName() throws Exception {
        Path legacy = root.resolve("Meu_Bot.pid");
        Files.writeString(legacy, "424242\n0");
        ProfileManager manager = new ProfileManager(root.toString());

        assertThat(manager.loadPid("Meu Bot")).isEmpty();
        assertThat(legacy).exists();
    }

    @Test
    void orphanCleanupDeletesEncodedPidPathWithoutHashingItAgain() throws Exception {
        ProfileManager manager = new ProfileManager(root.toString());
        manager.savePid("Meu Bot", 999_999_999L);
        Path encoded = manager.getPidFilePath("Meu Bot");

        manager.cleanupOrphanedPidFiles();

        assertThat(encoded).doesNotExist();
    }

    @Test
    void legacyMigrationRejectsSymlinkedAncestor() throws Exception {
        Path outside = Files.createTempDirectory("jdl-legacy-outside-");
        try {
            Files.createDirectories(outside.resolve("child"));
            Files.createSymbolicLink(root.resolve("tenant"), outside);
            Path target = ProfileKey.resolveUnder(root, "tenant/child");

            assertThatThrownBy(() -> ProfileKey.migrateLegacyDirectory(root, "tenant/child", target))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("simbólico");
            assertThat(outside.resolve("child")).exists();
        } finally {
            try (var paths = Files.walk(outside)) {
                for (Path path : paths.sorted(java.util.Comparator.reverseOrder()).toList()) {
                    Files.deleteIfExists(path);
                }
            }
        }
    }
}
