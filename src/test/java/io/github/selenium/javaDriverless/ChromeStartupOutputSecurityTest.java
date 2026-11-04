package io.github.selenium.javaDriverless;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ChromeStartupOutputSecurityTest {
    @Test
    void chromeProcessLogSummaryNeverCopiesRawOutput() {
        String raw = "Authorization: Bearer CHROME_OUTPUT_SECRET https://user:pass@example.invalid/?token=QUERY_SECRET";

        assertThat(ChromeStartupOutput.safeLogSummary(raw))
                .contains("redacted", "chars=")
                .doesNotContain("CHROME_OUTPUT_SECRET", "QUERY_SECRET", "Authorization", "user:pass");
    }
}
