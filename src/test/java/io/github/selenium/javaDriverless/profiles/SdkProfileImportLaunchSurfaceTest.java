package io.github.selenium.javaDriverless.profiles;

import io.github.selenium.javaDriverless.types.ProxyConfig;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class SdkProfileImportLaunchSurfaceTest {
    @TempDir
    Path tempDir;

    @Test
    void untrustedArchiveImportStripsLaunchSurfaceAndVerifyWarnsWithoutValues() {
        SdkProfileCatalog source = SdkProfileCatalog.at(tempDir.resolve("archive-source"));
        source.save(profileWithLaunchSurface("unsafe", "Unsafe"));
        Path archive = tempDir.resolve("profiles.zip");
        source.exportArchive(archive);

        SdkProfileCatalog target = SdkProfileCatalog.at(tempDir.resolve("archive-target"));
        SdkProfile imported = target.importArchive(archive, false).get(0);

        assertLaunchSurfaceIsEmpty(imported);
        assertLaunchSurfaceIsEmpty(target.load("unsafe").orElseThrow());
        SdkProfileArchiveReport report = source.verifyArchive(archive);
        assertThat(report.valid).isTrue();
        assertThat(report.warnings).isNotEmpty().allSatisfy(warning ->
                assertThat(warning).doesNotContain("/tmp/evil.sh"));
    }

    @Test
    void trustedArchiveImportPreservesLaunchSurface() {
        SdkProfileCatalog source = SdkProfileCatalog.at(tempDir.resolve("trusted-archive-source"));
        source.save(profileWithLaunchSurface("trusted-archive", "Trusted Archive"));
        Path archive = tempDir.resolve("trusted-profiles.zip");
        source.exportArchive(archive);

        SdkProfileCatalog target = SdkProfileCatalog.at(tempDir.resolve("trusted-archive-target"));
        SdkProfile imported = target.importArchive(archive, false,
                SdkProfileCatalogWorkflows.ImportTrust.TRUSTED).get(0);

        assertLaunchSurfaceIsPresent(imported);
        assertLaunchSurfaceIsPresent(target.load("trusted-archive").orElseThrow());
    }

    @Test
    void profileImportDefaultsToUntrustedAndCanPreserveTrustedFields() {
        SdkProfileCatalog source = SdkProfileCatalog.at(tempDir.resolve("profile-source"));
        source.save(profileWithLaunchSurface("profile", "Profile"));
        Path file = tempDir.resolve("profile.json");
        source.exportProfile("profile", file);

        SdkProfileCatalog untrustedTarget = SdkProfileCatalog.at(tempDir.resolve("profile-untrusted"));
        assertThat(untrustedTarget.importProfile(file).browser.binaryPath).isNull();

        SdkProfileCatalog trustedTarget = SdkProfileCatalog.at(tempDir.resolve("profile-trusted"));
        assertLaunchSurfaceIsPresent(trustedTarget.importProfile(file,
                SdkProfileCatalogWorkflows.ImportTrust.TRUSTED));
    }

    @Test
    void snapshotRestoreDefaultsToUntrustedAndCanPreserveTrustedFields() {
        SdkProfileCatalog source = SdkProfileCatalog.at(tempDir.resolve("snapshot-source"));
        source.save(profileWithLaunchSurface("snapshot", "Snapshot"));
        Path file = tempDir.resolve("snapshot.json");
        source.snapshot(file);

        SdkProfileCatalog untrustedTarget = SdkProfileCatalog.at(tempDir.resolve("snapshot-untrusted"));
        SdkProfile untrusted = untrustedTarget.restoreSnapshot(file, false).get(0);
        assertThat(untrusted.extensionPaths).isEmpty();

        SdkProfileCatalog trustedTarget = SdkProfileCatalog.at(tempDir.resolve("snapshot-trusted"));
        assertLaunchSurfaceIsPresent(trustedTarget.restoreSnapshot(file, false,
                SdkProfileCatalogWorkflows.ImportTrust.TRUSTED).get(0));
    }

    @Test
    void profileWithoutLaunchSurfaceRetainsProxyAndNameOnImport() {
        SdkProfile sourceProfile = new SdkProfile("ordinary", "Ordinary Profile");
        sourceProfile.proxy = new SdkProfileProxy("127.0.0.1", 8080, ProxyConfig.ProxyType.HTTP);
        SdkProfileCatalog source = SdkProfileCatalog.at(tempDir.resolve("ordinary-source"));
        source.save(sourceProfile);
        Path file = tempDir.resolve("ordinary.json");
        source.exportProfile("ordinary", file);

        SdkProfileCatalog target = SdkProfileCatalog.at(tempDir.resolve("ordinary-target"));
        SdkProfile imported = target.importProfile(file);

        assertThat(imported.name).isEqualTo("Ordinary Profile");
        assertThat(imported.proxy).isNotNull();
        assertThat(imported.proxy.port).isEqualTo(8080);
        assertLaunchSurfaceIsEmpty(imported);
    }

    @Test
    void verifyArchiveWarnsIndependentlyForEachLaunchSurfaceField() {
        assertArchiveWarningForSingleField("binaryPath", "browser.binaryPath");
        assertArchiveWarningForSingleField("extensionPaths", "extensionPaths");
        assertArchiveWarningForSingleField("preferences", "resourcePolicy.preferences");
    }

    @Test
    void verifyArchiveHasNoWarningsWhenLaunchSurfaceIsAbsent() {
        SdkProfileCatalog source = SdkProfileCatalog.at(tempDir.resolve("clean-verify-source"));
        source.save(new SdkProfile("clean", "Clean"));
        Path archive = tempDir.resolve("clean-verify.zip");
        source.exportArchive(archive);

        assertThat(source.verifyArchive(archive).warnings).isEmpty();
    }

    @Test
    void untrustedArchiveImportStripsEachLaunchSurfaceFieldIndependently() {
        assertImportedSingleFieldIsStripped("binaryPath");
        assertImportedSingleFieldIsStripped("extraArgs");
        assertImportedSingleFieldIsStripped("extensionPaths");
        assertImportedSingleFieldIsStripped("preferences");
    }

    @Test
    void defaultImportMethodsReturnImportedProfiles() {
        SdkProfileCatalog archiveSource = SdkProfileCatalog.at(tempDir.resolve("returns-archive-source"));
        archiveSource.save(new SdkProfile("archive-return", "Archive Return"));
        Path archive = tempDir.resolve("returns.zip");
        archiveSource.exportArchive(archive);
        SdkProfileCatalog archiveTarget = SdkProfileCatalog.at(tempDir.resolve("returns-archive-target"));
        assertThat(archiveTarget.importArchive(archive, false))
                .extracting(profile -> profile.id).containsExactly("archive-return");

        SdkProfileCatalog profileSource = SdkProfileCatalog.at(tempDir.resolve("returns-profile-source"));
        profileSource.save(new SdkProfile("profile-return", "Profile Return"));
        Path profile = tempDir.resolve("returns-profile.json");
        profileSource.exportProfile("profile-return", profile);
        SdkProfileCatalog profileTarget = SdkProfileCatalog.at(tempDir.resolve("returns-profile-target"));
        assertThat(profileTarget.importProfile(profile).id).isEqualTo("profile-return");

        SdkProfileCatalog snapshotSource = SdkProfileCatalog.at(tempDir.resolve("returns-snapshot-source"));
        snapshotSource.save(new SdkProfile("snapshot-return", "Snapshot Return"));
        Path snapshot = tempDir.resolve("returns-snapshot.json");
        snapshotSource.snapshot(snapshot);
        SdkProfileCatalog snapshotTarget = SdkProfileCatalog.at(tempDir.resolve("returns-snapshot-target"));
        assertThat(snapshotTarget.restoreSnapshot(snapshot, false))
                .extracting(restored -> restored.id).containsExactly("snapshot-return");
    }

    private void assertArchiveWarningForSingleField(String field, String expectedWarning) {
        SdkProfileCatalog source = SdkProfileCatalog.at(tempDir.resolve("verify-" + field));
        source.save(profileWithOnlyField("verify-" + field, field));
        Path archive = tempDir.resolve("verify-" + field + ".zip");
        source.exportArchive(archive);

        assertThat(source.verifyArchive(archive).warnings).singleElement()
                .satisfies(warning -> assertThat(warning).contains(expectedWarning));
    }

    private void assertImportedSingleFieldIsStripped(String field) {
        String id = "import-" + field;
        SdkProfileCatalog source = SdkProfileCatalog.at(tempDir.resolve(id + "-source"));
        source.save(profileWithOnlyField(id, field));
        Path archive = tempDir.resolve(id + ".zip");
        source.exportArchive(archive);
        SdkProfileCatalog target = SdkProfileCatalog.at(tempDir.resolve(id + "-target"));

        SdkProfile imported = target.importArchive(archive, false).get(0);
        assertThat(imported.browser.binaryPath).isNull();
        assertThat(imported.browser.extraArgs).isEmpty();
        assertThat(imported.extensionPaths).isEmpty();
        assertThat(imported.resourcePolicy.preferences).isEmpty();
    }

    private static SdkProfile profileWithOnlyField(String id, String field) {
        SdkProfile profile = new SdkProfile(id, id);
        switch (field) {
            case "binaryPath" -> profile.browser.binaryPath = "/tmp/evil.sh";
            // bypass do achado: --load-extension em extraArgs repovoa extensões removidas
            case "extraArgs" -> profile.browser.extraArgs.add("--load-extension=/tmp/ext");
            case "extensionPaths" -> profile.extensionPaths.add("/tmp/ext");
            case "preferences" -> profile.resourcePolicy.preferences.put("download.default_directory", "/tmp");
            default -> throw new IllegalArgumentException("Unknown field: " + field);
        }
        return profile;
    }

    private static SdkProfile profileWithLaunchSurface(String id, String name) {
        SdkProfile profile = new SdkProfile(id, name);
        profile.browser.binaryPath = "/tmp/evil.sh";
        profile.browser.extraArgs.add("--load-extension=/tmp/ext");
        profile.extensionPaths.add("/tmp/ext");
        profile.resourcePolicy.preferences.put("download.default_directory", "/tmp");
        return profile;
    }

    private static void assertLaunchSurfaceIsEmpty(SdkProfile profile) {
        assertThat(profile.browser.binaryPath).isNull();
        assertThat(profile.browser.extraArgs).isEmpty();
        assertThat(profile.extensionPaths).isEmpty();
        assertThat(profile.resourcePolicy.preferences).isEmpty();
    }

    private static void assertLaunchSurfaceIsPresent(SdkProfile profile) {
        assertThat(profile.browser.binaryPath).isEqualTo("/tmp/evil.sh");
        assertThat(profile.browser.extraArgs).containsExactly("--load-extension=/tmp/ext");
        assertThat(profile.extensionPaths).containsExactly("/tmp/ext");
        assertThat(profile.resourcePolicy.preferences)
                .containsExactly(Map.entry("download.default_directory", "/tmp"));
    }
}
