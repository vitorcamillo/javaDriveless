package io.github.selenium.javaDriverless;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class JavaDriverlessProfileRootTest {
    @TempDir
    Path tempDir;

    @Test
    void configuredProfileRootKeepsTestsAwayFromProjectProfiles() {
        String property = JavaDriverless.PROFILES_DIR_PROPERTY;
        String previous = System.getProperty(property);
        try {
            System.setProperty(property, tempDir.toString());

            assertThat(JavaDriverless.resolveProfilesRoot()).isEqualTo(tempDir.toAbsolutePath().normalize());
        } finally {
            if (previous == null) {
                System.clearProperty(property);
            } else {
                System.setProperty(property, previous);
            }
        }
    }
}
