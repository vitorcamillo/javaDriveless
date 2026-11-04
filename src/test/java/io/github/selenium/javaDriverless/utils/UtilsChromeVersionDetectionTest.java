package io.github.selenium.javaDriverless.utils;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class UtilsChromeVersionDetectionTest {
    @Test
    void malformedExecutablePathReturnsNoVersionInsteadOfEscapingTheDiagnosticBoundary() {
        assertNull(Utils.detectChromeVersion("invalid\0chrome"));
    }

    @Test
    void transientFailureIsRetriedAndOnlySuccessfulDetectionIsCached(@TempDir Path tempDir)
            throws Exception {
        Assumptions.assumeFalse(System.getProperty("os.name", "").toLowerCase().contains("win"));
        Path counter = tempDir.resolve("counter");
        Path chrome = tempDir.resolve("chrome-probe");
        String quotedCounter = "'" + counter.toString().replace("'", "'\"'\"'") + "'";
        Files.writeString(chrome, """
                #!/bin/sh
                count_file=%s
                count=0
                if [ -f "$count_file" ]; then count=$(sed -n '1p' "$count_file"); fi
                count=$((count + 1))
                printf '%%s' "$count" > "$count_file"
                if [ "$count" -eq 1 ]; then exit 1; fi
                printf 'Chromium 150.0.7339.5'
                """.formatted(quotedCounter));
        assertTrue(chrome.toFile().setExecutable(true));

        assertNull(Utils.detectChromeVersion(chrome.toString()));
        assertEquals("150.0.7339.5", Utils.detectChromeVersion(chrome.toString()));
        assertEquals("150.0.7339.5", Utils.detectChromeVersion(chrome.toString()));
        assertEquals("2", Files.readString(counter));
    }
}
