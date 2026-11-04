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

class SdkProfileStorageIsolationTest {
    @TempDir
    Path tempDir;

    @Test
    void differentCatalogRootsContendOnSamePhysicalStorageLease() throws Exception {
        Path shared = tempDir.resolve("cross-catalog-shared-storage");
        SdkProfileCatalog firstCatalog = SdkProfileCatalog.at(tempDir.resolve("catalog-one"));
        SdkProfile firstProfile = firstCatalog.create("profile-a", "Profile A");
        firstProfile.storage.userDataDir = shared.toString();
        firstCatalog.save(firstProfile);
        SdkProfileCatalog secondCatalog = SdkProfileCatalog.at(tempDir.resolve("catalog-two"));
        SdkProfile secondProfile = secondCatalog.create("profile-b", "Profile B");
        secondProfile.storage.userDataDir = shared.toString();
        secondCatalog.save(secondProfile);
        var first = JavaDriverlessProfiles.at(firstCatalog.getRoot()).options("profile-a");
        var second = JavaDriverlessProfiles.at(secondCatalog.getRoot()).options("profile-b");

        assertThat(shared).isDirectory();
        assertThat(shared.resolve(".javadriveless.storage.lease")).isRegularFile();
        try (var ignored = first.acquireCatalogLaunchLease()) {
            secondProfile.name = "Blocked Cross-Catalog Update";
            assertThatThrownBy(() -> secondCatalog.save(secondProfile))
                    .isInstanceOf(ProfileCatalogException.class)
                    .hasMessageContaining("is leased");
            assertThatThrownBy(second::acquireCatalogLaunchLease)
                    .isInstanceOf(ProfileCatalogException.class)
                    .hasMessageContaining("is leased");
        }

        assertThat(secondCatalog.save(secondProfile).name).isEqualTo("Blocked Cross-Catalog Update");
    }

    @Test
    void nestedStorageLeasesContendAcrossCatalogRootsInBothDirections() throws Exception {
        Path parentStorage = tempDir.resolve("cross-catalog-nested-storage");
        Path nestedStorage = parentStorage.resolve("nested-profile");
        Path firstRoot = tempDir.resolve("nested-catalog-one");
        Path secondRoot = tempDir.resolve("nested-catalog-two");
        SdkProfileCatalog firstCatalog = SdkProfileCatalog.at(firstRoot);
        SdkProfile firstProfile = firstCatalog.create("profile-a", "Profile A");
        firstProfile.storage.userDataDir = parentStorage.toString();
        firstCatalog.save(firstProfile);
        SdkProfileCatalog secondCatalog = SdkProfileCatalog.at(secondRoot);
        SdkProfile secondProfile = secondCatalog.create("profile-b", "Profile B");
        secondProfile.storage.userDataDir = nestedStorage.toString();
        secondCatalog.save(secondProfile);

        var firstParentOptions = JavaDriverlessProfiles.at(firstRoot).options("profile-a");
        var firstNestedOptions = JavaDriverlessProfiles.at(secondRoot).options("profile-b");
        try (var ignored = firstParentOptions.acquireCatalogLaunchLease()) {
            assertThatThrownBy(firstNestedOptions::acquireCatalogLaunchLease)
                    .isInstanceOf(ProfileCatalogException.class)
                    .hasMessageContaining("is leased");
        }

        var secondParentOptions = JavaDriverlessProfiles.at(firstRoot).options("profile-a");
        var secondNestedOptions = JavaDriverlessProfiles.at(secondRoot).options("profile-b");
        try (var ignored = secondNestedOptions.acquireCatalogLaunchLease()) {
            assertThatThrownBy(secondParentOptions::acquireCatalogLaunchLease)
                    .isInstanceOf(ProfileCatalogException.class)
                    .hasMessageContaining("is leased");
        }
    }

    @Test
    void catalogOptionsUserDataOverrideIsRejectedBeforeBrowserSpawn() throws Exception {
        Path catalogRoot = tempDir.resolve("mutated-options-catalog");
        SdkProfileCatalog catalog = SdkProfileCatalog.at(catalogRoot);
        SdkProfile profile = catalog.create("profile-a", "Profile A");
        profile.storage.userDataDir = tempDir.resolve("claimed-storage").toString();
        catalog.save(profile);
        var options = JavaDriverlessProfiles.at(catalogRoot).options("profile-a");
        Path unclaimedStorage = tempDir.resolve("unclaimed-storage");
        options.addArgument("--user-data-dir=" + unclaimedStorage);
        Path launched = tempDir.resolve("mutated-options-launched");
        Path fakeChrome = tempDir.resolve("mutated-options-chrome.sh");
        Files.writeString(fakeChrome, "#!/bin/sh\ntouch '" + launched + "'\n");
        assertThat(fakeChrome.toFile().setExecutable(true)).isTrue();
        options.setBinaryLocation(fakeChrome.toString());

        assertThatThrownBy(() -> Chrome.create(options, 0.2f, false, 1 << 20).join())
                .hasRootCauseInstanceOf(ProfileCatalogException.class)
                .hasRootCauseMessage("Catalog profile launch ownership changed: profile-a");
        assertThat(launched).doesNotExist();
    }

    @Test
    void startupFailureReleasesLeaseBeforeAnotherCatalogMutation() throws Exception {
        Path catalogRoot = tempDir.resolve("failure-release-catalog");
        SdkProfileCatalog catalog = SdkProfileCatalog.at(catalogRoot);
        SdkProfile profile = catalog.create("profile-a", "Profile A");
        profile.storage.userDataDir = tempDir.resolve("failure-release-storage").toString();
        catalog.save(profile);
        var options = JavaDriverlessProfiles.at(catalogRoot).options("profile-a");
        options.setBinaryLocation(tempDir.resolve("missing-chrome").toString());

        assertThatThrownBy(() -> Chrome.create(options, 0.2f, false, 1 << 20).join())
                .hasRootCauseInstanceOf(java.io.IOException.class);

        profile.name = "Updated After Failure";
        assertThat(catalog.save(profile).name).isEqualTo("Updated After Failure");
    }

    @Test
    void nestedStoragePathsCollideAcrossProfiles() throws Exception {
        SdkProfileCatalog catalog = SdkProfileCatalog.at(tempDir.resolve("nested-catalog"));
        SdkProfile first = catalog.create("profile-a", "Profile A");
        first.storage.userDataDir = tempDir.resolve("nested-storage").toString();
        catalog.save(first);
        SdkProfile second = catalog.create("profile-b", "Profile B");
        second.storage.downloadsDir = tempDir.resolve("nested-storage/downloads").toString();

        assertThatThrownBy(() -> catalog.save(second))
                .isInstanceOf(ProfileCatalogException.class)
                .hasMessageContaining("collides with profile 'profile-a'");
    }

    @Test
    void filesystemLeaseCoordinatesAcrossJvmAndRecoversAfterAbruptExit() throws Exception {
        Path exact = tempDir.resolve("subprocess-storage");
        Path nested = exact.resolve("nested-profile");
        Path sibling = tempDir.resolve("subprocess-sibling-storage");
        Path ready = tempDir.resolve("lease-holder.ready");
        Path javaBinary = Path.of(System.getProperty("java.home"), "bin", "java");
        String testClasspath = System.getProperty("surefire.test.class.path");
        assertThat(javaBinary).isExecutable();
        assertThat(testClasspath).isNotBlank();

        Process child = new ProcessBuilder(
                javaBinary.toString(),
                "-cp", testClasspath,
                LeaseHolderMain.class.getName(),
                exact.toString(),
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
                throw new AssertionError("Lease holder JVM did not become ready: " + output);
            }
            assertThat(child.isAlive()).isTrue();

            assertThatThrownBy(() -> ProfileStorageLeaseManager.acquire(List.of(exact)))
                    .isInstanceOf(ProfileCatalogException.class)
                    .hasMessageContaining("is leased");
            assertThatThrownBy(() -> ProfileStorageLeaseManager.acquire(List.of(nested)))
                    .isInstanceOf(ProfileCatalogException.class)
                    .hasMessageContaining("is leased");
            try (var ignored = ProfileStorageLeaseManager.acquire(List.of(sibling))) {
                assertThat(sibling).isDirectory();
                assertThat(child.isAlive()).isTrue();
            }

            child.destroyForcibly();
            assertThat(child.waitFor(5, TimeUnit.SECONDS)).isTrue();
            try (var ignored = ProfileStorageLeaseManager.acquire(List.of(exact))) {
                assertThat(exact.resolve(".javadriveless.storage.lease")).isRegularFile();
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
    void compositeLeaseDoesNotDedupeCaseDistinctDirectories() throws Exception {
        Path upper = Files.createDirectories(tempDir.resolve("Case"));
        Path lower = Files.createDirectories(tempDir.resolve("case"));
        assertThat(upper.toRealPath()).isNotEqualTo(lower.toRealPath());

        try (var ignored = ProfileStorageLeaseManager.acquire(List.of(upper, lower))) {
            assertThatThrownBy(() -> {
                try (var unexpected = ProfileStorageLeaseManager.acquire(List.of(upper))) {
                    // A aquisicao inesperada precisa ser fechada para o RED nao contaminar o teste.
                }
            }).isInstanceOf(ProfileCatalogException.class).hasMessageContaining("is leased");
            assertThatThrownBy(() -> {
                try (var unexpected = ProfileStorageLeaseManager.acquire(List.of(lower))) {
                    // A aquisicao inesperada precisa ser fechada para o RED nao contaminar o teste.
                }
            }).isInstanceOf(ProfileCatalogException.class).hasMessageContaining("is leased");
        }
    }

    @Test
    void blockedNestedLeaseDoesNotCreateChildBeforeAncestorLock() throws Exception {
        Path parent = Files.createDirectories(tempDir.resolve("leased-parent"));
        Path child = parent.resolve("must-not-be-created");

        try (var ignored = ProfileStorageLeaseManager.acquire(List.of(parent))) {
            assertThat(child).doesNotExist();
            assertThatThrownBy(() -> ProfileStorageLeaseManager.acquire(List.of(child)))
                    .isInstanceOf(ProfileCatalogException.class)
                    .hasMessageContaining("is leased");
            assertThat(child).doesNotExist();
        }
    }

    public static final class LeaseHolderMain {
        private LeaseHolderMain() {
        }

        public static void main(String[] args) throws Exception {
            Path storage = Path.of(args[0]);
            Path ready = Path.of(args[1]);
            try (var ignored = ProfileStorageLeaseManager.acquire(List.of(storage))) {
                Files.writeString(ready, "ready", StandardCharsets.UTF_8);
                while (true) Thread.sleep(TimeUnit.MINUTES.toMillis(1));
            }
        }
    }

    public static final class CatalogLeaseHolderMain {
        private CatalogLeaseHolderMain() {
        }

        public static void main(String[] args) throws Exception {
            Path catalogRoot = Path.of(args[0]);
            String profileId = args[1];
            Path ready = Path.of(args[2]);
            var options = JavaDriverlessProfiles.at(catalogRoot).options(profileId);
            try (var ignored = options.acquireCatalogLaunchLease()) {
                Files.writeString(ready, "ready", StandardCharsets.UTF_8);
                while (true) Thread.sleep(TimeUnit.MINUTES.toMillis(1));
            }
        }
    }
}
