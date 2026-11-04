package io.github.selenium.javaDriverless.fpmapping;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.RepeatedTest;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTimeout;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PlainChromeCdpFingerprintControlTest {
    @Test
    void cdpActionScriptUsesTheSelectorLoadedFromTheCatalog() {
        String script = PlainChromeCdpFingerprintControl.actionScript(Map.of(
                "id", "coveryourtracks",
                "runSelector", "#kcarterlink"));

        assertTrue(script.contains("const selector = \"#kcarterlink\""));
    }

    @Test
    void requiredCatalogActionFailsClosedWhenCdpReportsNoMatch() {
        assertThatThrownBy(() -> PlainChromeCdpFingerprintControl.requireActionTriggered(
                Map.of("id", "coveryourtracks", "runType", "click"), false))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("required action was not found");
    }

    @Test
    void placeholderReportAndLandingPageFailTheirCompletionGate() {
        assertThatThrownBy(() -> PlainChromeCdpFingerprintControl.requireActionCompleted(
                "trustmyip-timezone", false))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("did not produce a completed report");
        assertThatThrownBy(() -> PlainChromeCdpFingerprintControl.requireActionCompleted(
                "coveryourtracks", false))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("did not render the completed results");
    }

    @Test
    void coverYourTracksCompletionUsesThePopulatedResultsContract() {
        String script = PlainChromeCdpFingerprintControl.actionCompletionScript("coveryourtracks");

        assertTrue(script.contains("/results"));
        assertTrue(script.contains("fingerprintTable"));
        assertTrue(script.contains("bits of identifying information"));
        assertTrue(script.contains("characteristic"));
        assertFalse(script.contains("!document.getElementById('kcarterlink')"));
    }

    @Test
    void coverYourTracksUsesShortLandingWaitBeforeItsLongPostClickPoll() {
        assertEquals(3000, PlainChromeCdpFingerprintControl.initialWaitMs(
                "coveryourtracks", Map.of("waitMs", "90000")));
    }

    @Test
    void capturesTheSiteDomContractThroughTheCdpEvaluator() throws Exception {
        AtomicReference<String> evaluatedScript = new AtomicReference<>();
        Map<String, Object> expected = Map.of(
                "webgl-renderer", Map.of("matched", true, "text", "ANGLE"));

        Map<String, Object> actual = PlainChromeCdpFingerprintControl.captureDomEvidence(
                "browserleaks-webgl",
                script -> {
                    evaluatedScript.set(script);
                    return expected;
                });

        assertEquals(FingerprintMappingDom.captureScript("browserleaks-webgl"), evaluatedScript.get());
        assertSame(expected, actual);
    }

    @Test
    void deletesNestedProfileTreeIdempotently(@TempDir Path tempDir) throws Exception {
        Path profile = Files.createDirectories(tempDir.resolve("profile/Default/Cache"));
        Files.writeString(profile.resolve("volatile-entry"), "data");

        PlainChromeCdpFingerprintControl.deleteTree(tempDir.resolve("profile"));
        PlainChromeCdpFingerprintControl.deleteTree(tempDir.resolve("profile"));

        assertFalse(Files.exists(tempDir.resolve("profile")));
    }

    @RepeatedTest(3)
    void terminatesTheChromeLikeProcessTreeBeforeProfileCleanup(@TempDir Path tempDir) throws Exception {
        Process process = new ProcessBuilder("/bin/sh", "-c", "sleep 60 & wait").start();
        ProcessHandle descendant = waitForDescendant(process, Duration.ofSeconds(3));
        Path profile = Files.createDirectories(tempDir.resolve("profile/Default"));
        Files.writeString(profile.resolve("volatile-entry"), "data");
        try {
            assertTimeout(Duration.ofSeconds(8),
                    () -> PlainChromeCdpFingerprintControl.terminateProcessTree(process));

            assertFalse(process.isAlive());
            assertFalse(descendant.isAlive());
            PlainChromeCdpFingerprintControl.deleteTree(tempDir.resolve("profile"));
            assertFalse(Files.exists(tempDir.resolve("profile")));
        } finally {
            descendant.destroyForcibly();
            process.destroyForcibly();
        }
    }

    private static ProcessHandle waitForDescendant(Process process, Duration timeout) throws Exception {
        long deadline = System.nanoTime() + timeout.toNanos();
        while (System.nanoTime() < deadline) {
            ProcessHandle descendant = process.descendants().findFirst().orElse(null);
            if (descendant != null) {
                return descendant;
            }
            Thread.sleep(20);
        }
        throw new AssertionError("Processo filho de controle não iniciou");
    }
}
