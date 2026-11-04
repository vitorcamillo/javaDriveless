package io.github.selenium.javaDriverless.fpmapping;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class FingerprintMappingCaptureTimeoutContractTest {
    @Test
    void everyPublicCaptureSessionBoundsPageNavigationToOneMinute() throws Exception {
        String source = Files.readString(Path.of(
                "src/main/java/io/github/selenium/javaDriverless/fpmapping/FingerprintMappingCapture.java"));
        String configuration = "driver.setPageLoadTimeout(PAGE_LOAD_TIMEOUT_MILLIS);";

        assertThat(source).contains("private static final long PAGE_LOAD_TIMEOUT_MILLIS = 60_000L;");
        assertThat(source.split(java.util.regex.Pattern.quote(configuration), -1).length - 1)
                .isEqualTo(2);
    }
}
