package io.github.selenium.javaDriverless;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

class ManagedTempProfilesTest {

    private static final long DEAD_PID = 999_999_999L;

    @TempDir
    Path tmp;

    private Path profile(String name, String marker) throws Exception {
        Path dir = Files.createDirectory(tmp.resolve(name));
        Files.writeString(dir.resolve("Local State"), "{}");
        if (marker != null) {
            Files.writeString(dir.resolve(ManagedTempProfiles.MARKER), marker);
        }
        return dir;
    }

    private static String selfMarker(long startMillis) {
        return ProcessHandle.current().pid() + " " + startMillis;
    }

    private static long selfStart() {
        return ProcessHandle.current().info().startInstant().map(Instant::toEpochMilli).orElse(-1L);
    }

    @Test
    void sweepDeletesOnlyMarkedProfilesOfDeadOwners() throws Exception {
        Path dead = profile("selenium_driverless_dead", DEAD_PID + " 123");
        Path alive = profile("selenium_driverless_alive", selfMarker(selfStart()));
        Path noMarker = profile("selenium_driverless_nomarker", null);
        Path invalid = profile("selenium_driverless_invalid", "nao-e-pid");
        Path otherPrefix = profile("outro_dead", DEAD_PID + " 123");

        assertThat(ManagedTempProfiles.sweepOrphans(tmp)).isEqualTo(1);

        assertThat(dead).doesNotExist();
        assertThat(alive).exists();
        assertThat(noMarker).exists();
        assertThat(invalid).exists();
        assertThat(otherPrefix).exists();
    }

    @Test
    void reusedPidWithDifferentStartIsTreatedAsDead() throws Exception {
        long start = selfStart();
        assumeTrue(start >= 0, "plataforma sem startInstant");
        Path reused = profile("selenium_driverless_reused", selfMarker(start - 60_000));
        assertThat(ManagedTempProfiles.sweepOrphans(tmp)).isEqualTo(1);
        assertThat(reused).doesNotExist();
    }

    @Test
    void sweepNeverFollowsSymlinkedProfiles() throws Exception {
        Path target = profile("alvo_fora", DEAD_PID + " 123");
        Path link;
        try {
            link = Files.createSymbolicLink(tmp.resolve("selenium_driverless_link"), target);
        } catch (UnsupportedOperationException | java.io.IOException e) {
            assumeTrue(false, "symlink indisponível");
            return;
        }
        assertThat(ManagedTempProfiles.sweepOrphans(tmp)).isZero();
        assertThat(link).exists();
        assertThat(target.resolve("Local State")).exists();
    }

    @Test
    void createWritesOwnerMarkerAndIsNotSweptWhileOwned() throws Exception {
        Path dir = ManagedTempProfiles.create();
        try {
            assertThat(dir.getFileName().toString()).startsWith(ManagedTempProfiles.PREFIX);
            assertThat(Files.readString(dir.resolve(ManagedTempProfiles.MARKER)))
                    .startsWith(ProcessHandle.current().pid() + " ");
            assertThat(ManagedTempProfiles.sweepOrphans(dir.getParent())).isGreaterThanOrEqualTo(0);
            assertThat(dir).exists();
        } finally {
            ProfileStartupFiles.deleteDirectory(dir);
            ManagedTempProfiles.release(dir);
        }
    }
}
