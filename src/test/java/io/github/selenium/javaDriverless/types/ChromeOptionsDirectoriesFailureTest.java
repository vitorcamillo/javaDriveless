package io.github.selenium.javaDriverless.types;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ChromeOptionsDirectoriesFailureTest {

    @Test
    void failureMessageAndCauseNeverCarryThePath(@TempDir Path temp) throws Exception {
        Path file = Files.writeString(temp.resolve("file.txt"), "x");
        Path target = file.resolve("sub");

        Throwable thrown = catchThrowable(
                () -> ChromeOptionsDirectories.ensureDirectory(target.toString()));

        assertThat(thrown).isInstanceOf(UncheckedIOException.class);
        for (Throwable t = thrown; t != null; t = t.getCause()) {
            assertThat(String.valueOf(t.getMessage()))
                    .doesNotContain(temp.toString(), "file.txt");
        }
        assertThat(thrown.getCause()).isNotNull();
    }

    @Test
    void invalidPathIsRejectedWithoutEchoingIt() {
        Throwable thrown = catchThrowable(
                () -> ChromeOptionsDirectories.ensureDirectory("/private/FAKE_PROFILE\0x"));

        assertThat(thrown).isInstanceOf(IllegalArgumentException.class)
                .hasMessageNotContaining("FAKE_PROFILE")
                .hasNoCause();
    }
}
