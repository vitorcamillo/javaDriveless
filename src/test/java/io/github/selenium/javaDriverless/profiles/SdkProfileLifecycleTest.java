package io.github.selenium.javaDriverless.profiles;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.nio.file.Files;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.Callable;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SdkProfileLifecycleTest {
    @TempDir
    Path tempDir;

    @Test
    void cloneDryRunReportsPlannedIdsWithoutWriting() {
        SdkProfileCatalog catalog = SdkProfileCatalog.at(tempDir);
        catalog.create("base", "Base");
        SdkProfileCloneRequest request = new SdkProfileCloneRequest("base", "clone", "Clone", 2);
        request.dryRun = true;
        request.randomize = true;
        request.seed = 900L;

        SdkProfileCloneResult result = catalog.cloneProfiles(request);

        assertThat(result.dryRun).isTrue();
        assertThat(result.items).extracting(item -> item.id).containsExactly("clone-1", "clone-2");
        assertThat(result.items).extracting(item -> item.seed).containsExactly(901L, 902L);
        assertThat(catalog.load("clone-1")).isEmpty();
    }

    @Test
    void clonePersistsDeterministicSeedAndDetectsConflict() {
        SdkProfileCatalog catalog = SdkProfileCatalog.at(tempDir);
        catalog.create("base", "Base");
        catalog.create("clone-1", "Existing");
        SdkProfileCloneRequest request = new SdkProfileCloneRequest("base", "clone", "Clone", 2);
        request.randomize = true;
        request.seed = 100L;

        SdkProfileCloneResult result = catalog.cloneProfiles(request);

        assertThat(result.items).extracting(item -> item.status).containsExactly("conflict", "planned");
        SdkProfile clone = catalog.select("clone-2");
        assertThat(clone.metadata.seeds).containsEntry("clone", 102L);
        assertThat(clone.fingerprint.canvas.mode).isEqualTo("noise");
    }

    @Test
    void cloneWithoutRandomizationSeparatesStorageAndNoiseDeterministically() {
        Path userData = tempDir.resolve("runtime/user-data/base");
        Path downloads = tempDir.resolve("runtime/downloads/base");
        SdkProfileCatalog catalog = SdkProfileCatalog.at(tempDir.resolve("first-catalog"));
        SdkProfile source = catalog.create("base", "Base");
        source.storage.userDataDir = userData.toString();
        source.storage.downloadsDir = downloads.toString();
        source.fingerprint.noiseSeed = 7331;
        catalog.save(source);

        SdkProfileCloneRequest request = new SdkProfileCloneRequest("base", "copy", "Copy", 2);
        request.randomize = false;
        SdkProfileCloneResult cloneResult = catalog.cloneProfiles(request);
        assertThat(cloneResult.dryRun).isFalse();
        assertThat(cloneResult.items).extracting(item -> item.status)
                .containsExactly("planned", "planned");
        assertThat(catalog.list()).extracting(profile -> profile.id)
                .contains("copy-1", "copy-2");

        SdkProfile first = catalog.select("copy-1");
        SdkProfile second = catalog.select("copy-2");
        assertThat(first.storage.userDataDir).isEqualTo(userData.resolveSibling("base-copy-1").toString());
        assertThat(second.storage.userDataDir).isEqualTo(userData.resolveSibling("base-copy-2").toString());
        assertThat(first.storage.downloadsDir).isEqualTo(downloads.resolveSibling("base-copy-1").toString());
        assertThat(second.storage.downloadsDir).isEqualTo(downloads.resolveSibling("base-copy-2").toString());
        assertThat(first.fingerprint.noiseSeed)
                .isNotEqualTo(source.fingerprint.noiseSeed)
                .isNotEqualTo(second.fingerprint.noiseSeed);
        assertThat(second.fingerprint.noiseSeed).isNotEqualTo(source.fingerprint.noiseSeed);
        assertThat(first.fingerprint.canvas.mode).isEqualTo(source.fingerprint.canvas.mode);
        assertThat(second.fingerprint.canvas.mode).isEqualTo(source.fingerprint.canvas.mode);

        SdkProfileCatalog repeatedCatalog = SdkProfileCatalog.at(tempDir.resolve("repeated-catalog"));
        repeatedCatalog.save(source);
        SdkProfileCloneResult collision = repeatedCatalog.cloneProfiles(request);
        assertThat(collision.items).extracting(item -> item.status)
                .containsExactly("storage_conflict", "storage_conflict");

        SdkProfileCatalog deterministicCatalog = SdkProfileCatalog.at(tempDir.resolve("deterministic-catalog"));
        SdkProfile repeatedSource = deterministicCatalog.create("base", "Base");
        repeatedSource.storage.userDataDir = tempDir.resolve("repeated-runtime/user-data/base").toString();
        repeatedSource.storage.downloadsDir = tempDir.resolve("repeated-runtime/downloads/base").toString();
        repeatedSource.fingerprint.noiseSeed = source.fingerprint.noiseSeed;
        deterministicCatalog.save(repeatedSource);
        SdkProfileCloneResult repeatedResult = deterministicCatalog.cloneProfiles(request);
        assertThat(repeatedResult.items).extracting(item -> item.status)
                .containsExactly("planned", "planned");
        assertThat(deterministicCatalog.select("copy-1").fingerprint.noiseSeed)
                .isEqualTo(first.fingerprint.noiseSeed);
        assertThat(deterministicCatalog.select("copy-2").fingerprint.noiseSeed)
                .isEqualTo(second.fingerprint.noiseSeed);
        assertThat(deterministicCatalog.select("copy-1").storage.userDataDir)
                .isNotEqualTo(first.storage.userDataDir);
    }

    @Test
    void cloneKeepsUnspecifiedStorageTemporary() {
        SdkProfileCatalog catalog = SdkProfileCatalog.at(tempDir.resolve("temporary-storage-catalog"));
        catalog.create("base", "Base");
        SdkProfileCloneRequest request = new SdkProfileCloneRequest("base", "copy", "Copy", 1);

        catalog.cloneProfiles(request);

        SdkProfile clone = catalog.select("copy-1");
        assertThat(clone.storage.userDataDir).isNull();
        assertThat(clone.storage.downloadsDir).isNull();
        assertThat(clone.fingerprint.noiseSeed).isNotEqualTo(catalog.select("base").fingerprint.noiseSeed);
    }

    @Test
    void cloneNoiseSeedDoesNotCollideWithAnyExistingCatalogProfile() {
        SdkProfileCatalog catalog = SdkProfileCatalog.at(tempDir.resolve("seed-collision-catalog"));
        catalog.create("base", "Base");
        SdkProfileCloneRequest request = new SdkProfileCloneRequest("base", "copy", "Copy", 1);
        catalog.cloneProfiles(request);
        int firstCandidate = catalog.select("copy-1").fingerprint.noiseSeed;
        catalog.delete("copy-1");

        SdkProfile occupied = catalog.create("occupied", "Occupied");
        occupied.fingerprint.noiseSeed = firstCandidate;
        catalog.save(occupied);
        catalog.cloneProfiles(request);

        assertThat(catalog.select("copy-1").fingerprint.noiseSeed)
                .isNotEqualTo(firstCandidate)
                .isNotEqualTo(catalog.select("base").fingerprint.noiseSeed);
    }

    @Test
    void cloneRefusesStoragePathAlreadyOwnedByAnotherProfile() {
        SdkProfileCatalog catalog = SdkProfileCatalog.at(tempDir.resolve("storage-owner-catalog"));
        Path runtime = tempDir.resolve("runtime-owner");
        SdkProfile source = catalog.create("base", "Base");
        source.storage.userDataDir = runtime.resolve("base").toString();
        source.storage.downloadsDir = runtime.resolve("downloads").toString();
        catalog.save(source);
        SdkProfile occupied = catalog.create("occupied", "Occupied");
        occupied.storage.userDataDir = runtime.resolve("base-copy-1").toString();
        catalog.save(occupied);

        SdkProfileCloneResult result = catalog.cloneProfiles(
                new SdkProfileCloneRequest("base", "copy", "Copy", 1));

        assertThat(result.items).singleElement().extracting(item -> item.status)
                .isEqualTo("storage_conflict");
        assertThat(catalog.load("copy-1")).isEmpty();
    }

    @Test
    void cloneRefusesResidualOrSymlinkStoragePath() throws Exception {
        SdkProfileCatalog catalog = SdkProfileCatalog.at(tempDir.resolve("storage-residual-catalog"));
        Path runtime = Files.createDirectories(tempDir.resolve("runtime-residual"));
        SdkProfile source = catalog.create("base", "Base");
        source.storage.userDataDir = runtime.resolve("base").toString();
        catalog.save(source);
        Path external = Files.createDirectories(tempDir.resolve("external-storage"));
        Files.createSymbolicLink(runtime.resolve("base-copy-1"), external);

        SdkProfileCloneResult result = catalog.cloneProfiles(
                new SdkProfileCloneRequest("base", "copy", "Copy", 1));

        assertThat(result.items).singleElement().extracting(item -> item.status)
                .isEqualTo("storage_conflict");
        assertThat(catalog.load("copy-1")).isEmpty();
    }

    @Test
    void concurrentCloneReservationsHaveSingleWinner() throws Exception {
        SdkProfileCatalog seed = SdkProfileCatalog.at(tempDir.resolve("concurrent-clone-catalog"));
        seed.create("base", "Base");
        SdkProfileCatalog first = SdkProfileCatalog.at(seed.getRoot());
        SdkProfileCatalog second = SdkProfileCatalog.at(seed.getRoot());
        SdkProfileCloneRequest request = new SdkProfileCloneRequest("base", "copy", "Copy", 1);
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        try (var executor = Executors.newFixedThreadPool(2)) {
            var one = executor.submit(() -> {
                ready.countDown();
                start.await();
                return first.cloneProfiles(request);
            });
            var two = executor.submit(() -> {
                ready.countDown();
                start.await();
                return second.cloneProfiles(request);
            });
            assertThat(ready.await(2, TimeUnit.SECONDS)).isTrue();
            start.countDown();
            var results = java.util.List.of(one.get(5, TimeUnit.SECONDS), two.get(5, TimeUnit.SECONDS));

            assertThat(results.stream()
                    .flatMap(result -> result.items.stream())
                    .filter(item -> "planned".equals(item.status)))
                    .hasSize(1);
            assertThat(results.stream()
                    .flatMap(result -> result.items.stream())
                    .filter(item -> "conflict".equals(item.status)))
                    .hasSize(1);
        }
    }

    @Test
    void concurrentCaseFoldCatalogIdsHaveSinglePortableWinner() throws Exception {
        Path root = tempDir.resolve("concurrent-case-catalog");
        SdkProfileCatalog first = SdkProfileCatalog.at(root);
        SdkProfileCatalog second = SdkProfileCatalog.at(root);
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        Callable<String> createUpper = () -> createAfterBarrier(first, "Foo", ready, start);
        Callable<String> createLower = () -> createAfterBarrier(second, "foo", ready, start);

        try (var executor = Executors.newFixedThreadPool(2)) {
            var upper = executor.submit(createUpper);
            var lower = executor.submit(createLower);
            assertThat(ready.await(2, TimeUnit.SECONDS)).isTrue();
            start.countDown();

            assertThat(java.util.List.of(upper.get(5, TimeUnit.SECONDS), lower.get(5, TimeUnit.SECONDS)))
                    .containsExactlyInAnyOrder("created", "conflict");
            assertThat(first.list()).hasSize(1);
        }
    }

    @Test
    void saveRefusesPortableCaseFoldOverwrite() {
        SdkProfileCatalog catalog = SdkProfileCatalog.at(tempDir.resolve("save-case-catalog"));
        catalog.create("Foo", "Upper");

        assertThatThrownBy(() -> catalog.save(new SdkProfile("foo", "Lower")))
                .isInstanceOf(ProfileCatalogException.class)
                .hasMessageContaining("maiúsculas/minúsculas");
        assertThat(catalog.select("Foo").name).isEqualTo("Upper");

        assertThatThrownBy(() -> catalog.load("foo"))
                .isInstanceOf(ProfileCatalogException.class)
                .hasMessageContaining("maiúsculas/minúsculas");
        assertThatThrownBy(() -> catalog.delete("foo"))
                .isInstanceOf(ProfileCatalogException.class)
                .hasMessageContaining("maiúsculas/minúsculas");
        assertThat(catalog.load("Foo")).isPresent();
    }

    @Test
    void listFiltersSortsAndPagesByMetadata() {
        SdkProfileCatalog catalog = SdkProfileCatalog.at(tempDir);
        SdkProfile first = catalog.create("br", "BR");
        first.metadata.folder = "work";
        first.metadata.tags.add("bank");
        first.metadata.pinned = true;
        first.metadata.mainWebsite = "example.com";
        catalog.save(first);
        SdkProfile second = catalog.create("us", "US");
        second.metadata.folder = "work";
        second.metadata.tags.add("shop");
        catalog.save(second);

        SdkProfileListQuery query = new SdkProfileListQuery();
        query.folder = "work";
        query.tags.add("bank");
        query.pinned = true;
        query.query = "example";

        assertThat(catalog.list(query)).extracting(profile -> profile.id).containsExactly("br");
    }

    @Test
    void bulkPatchSkipsLockedProfiles() {
        SdkProfileCatalog catalog = SdkProfileCatalog.at(tempDir);
        catalog.create("open", "Open");
        SdkProfile locked = catalog.create("locked", "Locked");
        locked.metadata.locked = true;
        catalog.save(locked);

        SdkProfileBulkPatchRequest request = new SdkProfileBulkPatchRequest();
        request.selectors.add("open");
        request.selectors.add("locked");
        request.addTags.add("done");
        request.folder = "updated";

        SdkProfileBulkPatchResult result = catalog.bulkPatch(request);

        assertThat(result.items).extracting(item -> item.status).containsExactly("updated", "locked");
        assertThat(catalog.select("open").metadata.tags).contains("done");
        assertThat(catalog.select("locked").metadata.folder).isEmpty();
    }

    @Test
    void trashRestoreAndPurgeStayInsideCatalog() {
        SdkProfileCatalog catalog = SdkProfileCatalog.at(tempDir);
        catalog.create("trash-me", "Trash Me");

        catalog.trash("trash-me");
        assertThat(catalog.select("trash-me").metadata.status).isEqualTo("deleted");

        catalog.restore("trash-me");
        assertThat(catalog.select("trash-me").metadata.status).isEqualTo("active");

        catalog.trash("trash-me");
        assertThat(catalog.purge("trash-me")).isTrue();
        assertThat(catalog.load("trash-me")).isEmpty();
    }

    @Test
    void snapshotAndArchiveRoundTripProfiles() {
        SdkProfileCatalog source = SdkProfileCatalog.at(tempDir.resolve("source"));
        SdkProfile profile = source.create("source-profile", "Source Profile");
        profile.metadata.notes = "public note";
        source.save(profile);

        Path snapshot = tempDir.resolve("snapshot.json");
        source.snapshot(snapshot);
        SdkProfileCatalog snapshotTarget = SdkProfileCatalog.at(tempDir.resolve("snapshot-target"));
        assertThat(snapshotTarget.restoreSnapshot(snapshot, false)).extracting(item -> item.id)
                .containsExactly("source-profile");

        Path archive = tempDir.resolve("profiles.zip");
        source.exportArchive(archive);
        SdkProfileArchiveReport report = source.verifyArchive(archive);
        assertThat(report.valid).isTrue();
        assertThat(report.profileIds).containsExactly("source-profile");

        SdkProfileCatalog archiveTarget = SdkProfileCatalog.at(tempDir.resolve("archive-target"));
        assertThat(archiveTarget.importArchive(archive, false)).extracting(item -> item.id)
                .containsExactly("source-profile");
        assertThat(archiveTarget.select("Source Profile").metadata.notes).isEqualTo("public note");
    }

    @Test
    void archiveVerificationRejectsMissingManifest() throws Exception {
        Path archive = tempDir.resolve("missing-manifest.zip");
        try (ZipOutputStream zip = new ZipOutputStream(java.nio.file.Files.newOutputStream(archive))) {
            zip.putNextEntry(new ZipEntry("profiles/sample.json"));
            zip.write("{\"id\":\"sample\",\"name\":\"Sample\"}".getBytes(java.nio.charset.StandardCharsets.UTF_8));
            zip.closeEntry();
        }

        SdkProfileArchiveReport report = SdkProfileCatalog.at(tempDir.resolve("catalog")).verifyArchive(archive);

        assertThat(report.valid).isFalse();
        assertThat(report.errors).contains("Missing archive manifest");
    }

    @Test
    void purgeRequiresTrashState() {
        SdkProfileCatalog catalog = SdkProfileCatalog.at(tempDir);
        catalog.create("active", "Active");

        assertThatThrownBy(() -> catalog.purge("active"))
                .isInstanceOf(ProfileCatalogException.class)
                .hasMessageContaining("not in trash");
    }

    private static String createAfterBarrier(SdkProfileCatalog catalog,
                                             String id,
                                             CountDownLatch ready,
                                             CountDownLatch start) throws Exception {
        ready.countDown();
        start.await();
        try {
            catalog.create(id, id);
            return "created";
        } catch (ProfileCatalogException expected) {
            return "conflict";
        }
    }
}
