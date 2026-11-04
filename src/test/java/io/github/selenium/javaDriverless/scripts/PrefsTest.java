package io.github.selenium.javaDriverless.scripts;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class PrefsTest {
    @TempDir
    Path tempDir;

    @Test
    void mergePreservesExistingProfileStateAndAppliesNestedOverridesAtomically() throws Exception {
        Path preferences = tempDir.resolve("Default/Preferences");
        Files.createDirectories(preferences.getParent());
        Prefs.writePrefs(Map.of(
                "profile", Map.of(
                        "name", "caller-profile",
                        "password_manager_enabled", true),
                "extensions", Map.of("existing", "preserved")), preferences).join();

        Prefs.mergeAndWritePrefs(Map.of(
                "profile", Map.of("password_manager_enabled", false),
                "download_bubble", Map.of("partial_view_enabled", false)), preferences).join();

        Map<String, Object> merged = Prefs.readPrefs(preferences).join();
        assertThat(merged).containsKey("extensions");
        assertThat(nested(merged, "extensions"))
                .containsEntry("existing", "preserved");
        assertThat(nested(merged, "profile"))
                .containsEntry("name", "caller-profile")
                .containsEntry("password_manager_enabled", false);
        assertThat(nested(merged, "download_bubble"))
                .containsEntry("partial_view_enabled", false);
        try (var files = Files.list(preferences.getParent())) {
            assertThat(files.map(path -> path.getFileName().toString()))
                    .noneMatch(name -> name.startsWith(".Preferences-"));
        }
    }

    @Test
    void replacingPathsRestoresOnlyManagedLeavesAndPreservesSiblings() throws Exception {
        Path preferences = tempDir.resolve("Default/Preferences");
        Files.createDirectories(preferences.getParent());
        Map<String, Object> baseline = Map.of("webrtc", Map.of(
                "custom_preserved", "yes",
                "ip_handling_policy", "default_public_interface_only",
                "multiple_routes_enabled", true));
        Prefs.writePrefs(baseline, preferences).join();

        Prefs.mergeAndWritePrefsReplacingPaths(Map.of("webrtc", Map.of(
                        "ip_handling_policy", "disable_non_proxied_udp",
                        "multiple_routes_enabled", false,
                        "nonproxied_udp_enabled", false)),
                preferences,
                Set.of(
                        "webrtc.ip_handling_policy",
                        "webrtc.multiple_routes_enabled",
                        "webrtc.nonproxied_udp_enabled"),
                Map.of()).join();

        assertThat(nested(Prefs.readPrefs(preferences).join(), "webrtc"))
                .containsEntry("custom_preserved", "yes")
                .containsEntry("ip_handling_policy", "disable_non_proxied_udp")
                .containsEntry("multiple_routes_enabled", false)
                .containsEntry("nonproxied_udp_enabled", false);

        Prefs.mergeAndWritePrefsReplacingPaths(
                Map.of(),
                preferences,
                Set.of(
                        "webrtc.ip_handling_policy",
                        "webrtc.multiple_routes_enabled",
                        "webrtc.nonproxied_udp_enabled"),
                baseline).join();

        assertThat(nested(Prefs.readPrefs(preferences).join(), "webrtc"))
                .containsExactlyInAnyOrderEntriesOf(nested(baseline, "webrtc"));
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> nested(Map<String, Object> root, String key) {
        return (Map<String, Object>) root.get(key);
    }

    @Test
    void concurrentMergesDoNotLoseOverrides() throws Exception {
        Path prefs = tempDir.resolve("Concurrent/Preferences");
        java.util.List<java.util.concurrent.CompletableFuture<Void>> writes = new java.util.ArrayList<>();
        for (int i = 0; i < 40; i++) {
            writes.add(Prefs.mergeAndWritePrefs(Map.of("k" + i, i), prefs));
        }
        java.util.concurrent.CompletableFuture.allOf(writes.toArray(new java.util.concurrent.CompletableFuture[0])).join();
        Map<String, Object> merged = Prefs.readPrefs(prefs).join();
        for (int i = 0; i < 40; i++) {
            assertThat(merged).containsEntry("k" + i, i);
        }
    }
}
