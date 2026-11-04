package io.github.selenium.javaDriverless.profiles;

import io.github.selenium.javaDriverless.Chrome;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SdkProfileStorageCollisionTest {
    @TempDir
    Path tempDir;

    @Test
    void saveRejectsDifferentIdsUsingRealAndSymlinkAlias() throws Exception {
        SdkProfileCatalog catalog = SdkProfileCatalog.at(tempDir.resolve("catalog"));
        Path physicalStorage = Files.createDirectories(tempDir.resolve("physical-storage"));
        Path storageAlias = Files.createSymbolicLink(tempDir.resolve("storage-alias"), physicalStorage);

        SdkProfile first = new SdkProfile("profile-a", "Profile A");
        first.storage.userDataDir = physicalStorage.toString();
        catalog.save(first);

        SdkProfile second = new SdkProfile("profile-b", "Profile B");
        second.storage.userDataDir = storageAlias.toString();

        assertThatThrownBy(() -> catalog.save(second))
                .isInstanceOf(ProfileCatalogException.class)
                .hasMessageContaining("storage");
        assertThat(catalog.load("profile-b")).isEmpty();
    }

    @Test
    void standaloneLaunchPlanBlocksStorageWithSymlinkComponent() throws Exception {
        Path physicalStorage = Files.createDirectories(tempDir.resolve("launch-physical-storage"));
        Path storageAlias = Files.createSymbolicLink(tempDir.resolve("launch-storage-alias"), physicalStorage);
        SdkProfile profile = new SdkProfile("launch-profile", "Launch Profile");
        profile.storage.userDataDir = storageAlias.resolve("nested-profile").toString();

        ProfileLaunchPlan plan = ProfileLaunchPlanner.plan(profile);

        assertThat(plan.status).isEqualTo("blocked");
        assertThat(plan.errors).anyMatch(error -> error.contains("symbolic link"));
    }

    @Test
    void catalogAwareLaunchBlocksTwoIdsSharingPhysicalStorage() throws Exception {
        Path catalogRoot = Files.createDirectories(tempDir.resolve("compromised-catalog"));
        Path sharedStorage = Files.createDirectories(tempDir.resolve("shared-launch-storage"));
        SdkProfileCatalog serializer = SdkProfileCatalog.at(catalogRoot);
        SdkProfile first = new SdkProfile("profile-a", "Profile A");
        first.storage.userDataDir = sharedStorage.toString();
        SdkProfile second = new SdkProfile("profile-b", "Profile B");
        second.storage.userDataDir = sharedStorage.toString();
        Files.writeString(catalogRoot.resolve("profile-a.json"), serializer.toJson(first), StandardCharsets.UTF_8);
        Files.writeString(catalogRoot.resolve("profile-b.json"), serializer.toJson(second), StandardCharsets.UTF_8);

        ProfileLaunchPlan plan = JavaDriverlessProfiles.at(catalogRoot).plan("profile-b");

        assertThat(plan.status).isEqualTo("blocked");
        assertThat(plan.errors).anyMatch(error -> error.contains("collides with profile 'profile-a'"));
        assertThatThrownBy(() -> JavaDriverlessProfiles.at(catalogRoot).options("profile-b"))
                .isInstanceOf(ProfileCatalogException.class)
                .hasMessageContaining("blocked profile");
    }

    @Test
    void optionsFactoryRevalidatesStorageIfPathBecomesSymlinkAfterPlan() throws Exception {
        Path lateAlias = tempDir.resolve("late-storage-alias");
        SdkProfile profile = new SdkProfile("late-link", "Late Link");
        profile.storage.userDataDir = lateAlias.toString();
        ProfileLaunchPlan plan = ProfileLaunchPlanner.plan(profile);
        assertThat(plan.status).isNotEqualTo("blocked");

        Path physicalStorage = Files.createDirectories(tempDir.resolve("late-physical-storage"));
        Files.createSymbolicLink(lateAlias, physicalStorage);

        assertThatThrownBy(() -> ProfileChromeOptionsFactory.fromProfile(profile, plan))
                .isInstanceOf(ProfileCatalogException.class)
                .hasMessageContaining("symbolic link");
    }

    @Test
    void snapshotDryRunRejectsStorageOwnedByAnotherId() throws Exception {
        SdkProfileCatalog catalog = SdkProfileCatalog.at(tempDir.resolve("snapshot-catalog"));
        Path sharedStorage = Files.createDirectories(tempDir.resolve("snapshot-shared-storage"));
        SdkProfile existing = new SdkProfile("profile-a", "Profile A");
        existing.storage.downloadsDir = sharedStorage.toString();
        catalog.save(existing);

        SdkProfile incoming = new SdkProfile("profile-b", "Profile B");
        incoming.storage.userDataDir = sharedStorage.toString();
        Path snapshot = tempDir.resolve("conflicting-snapshot.json");
        Files.writeString(snapshot, "[" + catalog.toJson(incoming) + "]", StandardCharsets.UTF_8);

        assertThatThrownBy(() -> catalog.restoreSnapshot(snapshot, true))
                .isInstanceOf(ProfileCatalogException.class)
                .hasMessageContaining("collides with profile 'profile-a'");
        assertThat(catalog.load("profile-b")).isEmpty();
    }

    @Test
    void archiveDryRunRejectsStorageOwnedByAnotherId() throws Exception {
        Path sharedStorage = Files.createDirectories(tempDir.resolve("archive-shared-storage"));
        SdkProfileCatalog source = SdkProfileCatalog.at(tempDir.resolve("archive-source"));
        SdkProfile incoming = new SdkProfile("profile-b", "Profile B");
        incoming.storage.downloadsDir = sharedStorage.toString();
        source.save(incoming);
        Path archive = tempDir.resolve("conflicting-profiles.zip");
        source.exportArchive(archive);

        SdkProfileCatalog target = SdkProfileCatalog.at(tempDir.resolve("archive-target"));
        SdkProfile existing = new SdkProfile("profile-a", "Profile A");
        existing.storage.userDataDir = sharedStorage.toString();
        target.save(existing);

        assertThatThrownBy(() -> target.importArchive(archive, true))
                .isInstanceOf(ProfileCatalogException.class)
                .hasMessageContaining("collides with profile 'profile-a'");
        assertThat(target.load("profile-b")).isEmpty();
    }

    @Test
    void saveRejectsPortableCaseFoldStorageAlias() throws Exception {
        SdkProfileCatalog catalog = SdkProfileCatalog.at(tempDir.resolve("casefold-catalog"));
        Path upper = Files.createDirectories(tempDir.resolve("Runtime/Profile"));
        Path lower = Files.createDirectories(tempDir.resolve("runtime/profile"));
        SdkProfile first = new SdkProfile("profile-a", "Profile A");
        first.storage.userDataDir = upper.toString();
        catalog.save(first);

        SdkProfile second = new SdkProfile("profile-b", "Profile B");
        second.storage.userDataDir = lower.toString();

        assertThatThrownBy(() -> catalog.save(second))
                .isInstanceOf(ProfileCatalogException.class)
                .hasMessageContaining("collides with profile 'profile-a'");
    }

    @Test
    void savingSameProfileToItsOwnedStorageRemainsAllowed() throws Exception {
        SdkProfileCatalog catalog = SdkProfileCatalog.at(tempDir.resolve("self-save-catalog"));
        Path storage = Files.createDirectories(tempDir.resolve("self-owned-storage"));
        SdkProfile profile = new SdkProfile("profile-a", "Profile A");
        profile.storage.userDataDir = storage.toString();
        catalog.save(profile);

        profile.name = "Profile A Updated";

        assertThat(catalog.save(profile).name).isEqualTo("Profile A Updated");
        assertThat(catalog.select("profile-a").storage.userDataDir).isEqualTo(storage.toString());
    }

    @Test
    void staleCatalogOptionsCannotLaunchStorageNowOwnedByAnotherProfile() throws Exception {
        Path catalogRoot = tempDir.resolve("launch-token-catalog");
        Path storageX = tempDir.resolve("launch-token-storage-x");
        Path storageY = tempDir.resolve("launch-token-storage-y");
        SdkProfileCatalog catalog = SdkProfileCatalog.at(catalogRoot);
        SdkProfile first = catalog.create("profile-a", "Profile A");
        first.storage.userDataDir = storageX.toString();
        catalog.save(first);
        SdkProfile second = catalog.create("profile-b", "Profile B");

        var staleOptions = JavaDriverlessProfiles.at(catalogRoot).options("profile-a");
        assertThat(staleOptions.hasCatalogLaunchValidator()).isTrue();
        assertThatThrownBy(() -> staleOptions.bindCatalogLaunchValidator(ignored -> () -> { }))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("already bound");

        first.storage.userDataDir = storageY.toString();
        catalog.save(first);
        second.storage.userDataDir = storageX.toString();
        catalog.save(second);
        Path launched = tempDir.resolve("stale-options-launched");
        Path fakeChrome = tempDir.resolve("stale-options-chrome.sh");
        Files.writeString(fakeChrome, "#!/bin/sh\ntouch '" + launched + "'\n");
        assertThat(fakeChrome.toFile().setExecutable(true)).isTrue();
        staleOptions.setBinaryLocation(fakeChrome.toString());

        assertThatThrownBy(() -> Chrome.create(staleOptions, 0.2f, false, 1 << 20).join())
                .hasRootCauseInstanceOf(ProfileCatalogException.class)
                .hasRootCauseMessage("Catalog profile launch ownership changed: profile-a");
        assertThat(launched).doesNotExist();
    }

    @Test
    void staleCatalogOptionsAreRejectedAfterFingerprintOnlySave() {
        Path catalogRoot = tempDir.resolve("fingerprint-token-catalog");
        Path storage = tempDir.resolve("fingerprint-token-storage");
        SdkProfileCatalog catalog = SdkProfileCatalog.at(catalogRoot);
        SdkProfile profile = catalog.create("profile-a", "Profile A");
        profile.storage.userDataDir = storage.toString();
        catalog.save(profile);
        var staleOptions = JavaDriverlessProfiles.at(catalogRoot).options("profile-a");

        profile.browser.userAgent = "FullHashProof/2.0";
        profile.fingerprint.noiseSeed ^= 0x5a5a5a5a;
        catalog.save(profile);

        assertThat(catalog.select("profile-a").storage.userDataDir).isEqualTo(storage.toString());
        assertThatThrownBy(staleOptions::acquireCatalogLaunchLease)
                .isInstanceOf(ProfileCatalogException.class)
                .hasMessage("Catalog profile launch ownership changed: profile-a");
    }

    @Test
    void catalogOptionsRejectExternalStorageMutationBetweenAcquireAndSpawn() throws Exception {
        Path catalogRoot = tempDir.resolve("freeze-catalog");
        Path storageA = tempDir.resolve("freeze-storage-a");
        Path downloadsA = tempDir.resolve("freeze-downloads-a");
        Path storageB = tempDir.resolve("freeze-storage-b");
        Path downloadsB = tempDir.resolve("freeze-downloads-b");
        Path capturedArguments = tempDir.resolve("freeze-captured-arguments.txt");
        Path fakeChrome = tempDir.resolve("freeze-fake-chrome.sh");
        Files.writeString(fakeChrome, "#!/bin/sh\nprintf '%s\\n' \"$@\" > '"
                + capturedArguments + "'\n");
        assertThat(fakeChrome.toFile().setExecutable(true)).isTrue();

        SdkProfileCatalog catalog = SdkProfileCatalog.at(catalogRoot);
        SdkProfile profile = catalog.create("profile-a", "Profile A");
        profile.storage.userDataDir = storageA.toString();
        profile.storage.downloadsDir = downloadsA.toString();
        catalog.save(profile);
        var options = JavaDriverlessProfiles.at(catalogRoot).options("profile-a");
        options.setBinaryLocation(fakeChrome.toString());

        CountDownLatch acquired = new CountDownLatch(1);
        CountDownLatch allowSpawn = new CountDownLatch(1);
        AtomicReference<Throwable> startupFailure = new AtomicReference<>();
        Thread startup = new Thread(() -> {
            try (var ignored = options.acquireCatalogLaunchLease()) {
                acquired.countDown();
                if (!allowSpawn.await(5, TimeUnit.SECONDS)) {
                    throw new AssertionError("spawn latch timed out");
                }
                List<String> command = new java.util.ArrayList<>();
                command.add(options.getBinaryLocation());
                command.addAll(options.getArguments());
                Process process = new ProcessBuilder(command).start();
                if (!process.waitFor(5, TimeUnit.SECONDS)) {
                    process.destroyForcibly();
                    throw new AssertionError("fake browser did not stop");
                }
            } catch (Throwable failure) {
                startupFailure.set(failure);
            }
        }, "catalog-startup-owner");
        startup.start();

        assertThat(acquired.await(5, TimeUnit.SECONDS)).isTrue();
        try {
            assertThatThrownBy(() -> options.setUserDataDir(storageB.toString()))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("catalog launch");
            assertThatThrownBy(() -> options.setDownloadsDir(downloadsB.toString()))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("catalog launch");
        } finally {
            allowSpawn.countDown();
            startup.join(TimeUnit.SECONDS.toMillis(10));
        }

        assertThat(startup.isAlive()).isFalse();
        assertThat(startupFailure).hasNullValue();
        assertThat(options.getUserDataDir()).isEqualTo(storageA.toString());
        assertThat(options.getDownloadsDir()).isEqualTo(downloadsA.toString());
        assertThat(Files.readString(capturedArguments)).contains("--user-data-dir=" + storageA)
                .doesNotContain(storageB.toString());
    }

    @Test
    void secondCatalogLaunchLeaseForSameProfileIsRejected() throws Exception {
        Path catalogRoot = tempDir.resolve("same-profile-lease-catalog");
        SdkProfileCatalog catalog = SdkProfileCatalog.at(catalogRoot);
        SdkProfile profile = catalog.create("profile-a", "Profile A");
        profile.storage.userDataDir = tempDir.resolve("same-profile-storage").toString();
        catalog.save(profile);
        var first = JavaDriverlessProfiles.at(catalogRoot).options("profile-a");
        var second = JavaDriverlessProfiles.at(catalogRoot).options("profile-a");

        try (var ignored = first.acquireCatalogLaunchLease()) {
            assertThatThrownBy(second::acquireCatalogLaunchLease)
                    .isInstanceOf(ProfileCatalogException.class)
                    .hasMessageContaining("is leased");
        }
    }

    @Test
    void profileLeaseBlocksSecondNullStorageLaunchSaveAndDelete() {
        Path catalogRoot = tempDir.resolve("null-storage-lease-catalog");
        SdkProfileCatalog catalog = SdkProfileCatalog.at(catalogRoot);
        SdkProfile profile = catalog.create("profile-a", "Profile A");
        assertThat(profile.storage.userDataDir).isNull();
        assertThat(profile.storage.downloadsDir).isNull();
        var first = JavaDriverlessProfiles.at(catalogRoot).options("profile-a");
        var second = JavaDriverlessProfiles.at(catalogRoot).options("profile-a");

        try (var ignored = first.acquireCatalogLaunchLease()) {
            assertThatThrownBy(second::acquireCatalogLaunchLease)
                    .isInstanceOf(ProfileCatalogException.class)
                    .hasMessageContaining("is leased");
            profile.name = "Blocked Update";
            assertThatThrownBy(() -> catalog.save(profile))
                    .isInstanceOf(ProfileCatalogException.class)
                    .hasMessageContaining("is leased");
            assertThatThrownBy(() -> catalog.delete("profile-a"))
                    .isInstanceOf(ProfileCatalogException.class)
                    .hasMessageContaining("is leased");
        }

        assertThat(catalog.save(profile).name).isEqualTo("Blocked Update");
        assertThat(catalog.delete("profile-a")).isTrue();
    }

    @Test
    void sealedCatalogOptionsRejectOwnerMutationUntilLeaseClose() {
        Path catalogRoot = tempDir.resolve("sealed-options-catalog");
        SdkProfileCatalog catalog = SdkProfileCatalog.at(catalogRoot);
        catalog.create("profile-a", "Profile A");
        var options = JavaDriverlessProfiles.at(catalogRoot).options("profile-a");

        try (var ignored = options.acquireCatalogLaunchLease()) {
            options.setStartupUrl("about:blank#owner-before-seal");
            options.sealCatalogLaunchOptions();
            assertThatThrownBy(() -> options.setStartupUrl("about:blank#after-seal"))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("catalog launch");
        }

        options.setStartupUrl("about:blank#after-close");
        assertThat(options.getStartupUrl()).isEqualTo("about:blank#after-close");
    }

    @Test
    void nullStorageProfileLeaseContendsAcrossJvmAndRecoversAfterExit() throws Exception {
        Path catalogRoot = tempDir.resolve("cross-jvm-profile-catalog");
        SdkProfileCatalog catalog = SdkProfileCatalog.at(catalogRoot);
        SdkProfile profile = catalog.create("profile-a", "Profile A");
        Path ready = tempDir.resolve("profile-lease-holder.ready");
        Path javaBinary = Path.of(System.getProperty("java.home"), "bin", "java");
        String testClasspath = System.getProperty("surefire.test.class.path");

        Process child = new ProcessBuilder(
                javaBinary.toString(),
                "-cp", testClasspath,
                SdkProfileStorageIsolationTest.CatalogLeaseHolderMain.class.getName(),
                catalogRoot.toString(),
                "profile-a",
                ready.toString())
                .redirectErrorStream(true)
                .start();
        try {
            long readyDeadline = System.nanoTime() + Duration.ofSeconds(10).toNanos();
            while (!Files.exists(ready) && child.isAlive() && System.nanoTime() < readyDeadline) {
                Thread.sleep(10);
            }
            if (!Files.exists(ready)) {
                child.destroyForcibly();
                boolean stopped = child.waitFor(3, TimeUnit.SECONDS);
                String output = stopped
                        ? new String(child.getInputStream().readAllBytes(), StandardCharsets.UTF_8)
                        : "<child did not stop>";
                throw new AssertionError("Catalog lease holder JVM did not become ready: " + output);
            }

            var parentOptions = JavaDriverlessProfiles.at(catalogRoot).options("profile-a");
            assertThatThrownBy(parentOptions::acquireCatalogLaunchLease)
                    .isInstanceOf(ProfileCatalogException.class)
                    .hasMessageContaining("is leased");
            profile.name = "Blocked Cross-JVM Update";
            assertThatThrownBy(() -> catalog.save(profile))
                    .isInstanceOf(ProfileCatalogException.class)
                    .hasMessageContaining("is leased");
            assertThatThrownBy(() -> catalog.delete("profile-a"))
                    .isInstanceOf(ProfileCatalogException.class)
                    .hasMessageContaining("is leased");

            child.destroyForcibly();
            assertThat(child.waitFor(5, TimeUnit.SECONDS)).isTrue();
            try (var ignored = parentOptions.acquireCatalogLaunchLease()) {
                assertThat(child.isAlive()).isFalse();
            }
        } finally {
            if (child.isAlive()) {
                child.destroyForcibly();
                child.waitFor(3, TimeUnit.SECONDS);
            }
            child.getInputStream().close();
        }
    }

    @Test
    void catalogSaveIsBlockedUntilRunningLeaseIsReleased() throws Exception {
        Path catalogRoot = tempDir.resolve("save-lease-catalog");
        SdkProfileCatalog catalog = SdkProfileCatalog.at(catalogRoot);
        SdkProfile profile = catalog.create("profile-a", "Profile A");
        profile.storage.userDataDir = tempDir.resolve("save-lease-storage").toString();
        catalog.save(profile);
        var options = JavaDriverlessProfiles.at(catalogRoot).options("profile-a");

        try (var ignored = options.acquireCatalogLaunchLease()) {
            profile.name = "Blocked Update";
            assertThatThrownBy(() -> catalog.save(profile))
                    .isInstanceOf(ProfileCatalogException.class)
                    .hasMessageContaining("is leased");
        }

        assertThat(catalog.save(profile).name).isEqualTo("Blocked Update");
    }

}
