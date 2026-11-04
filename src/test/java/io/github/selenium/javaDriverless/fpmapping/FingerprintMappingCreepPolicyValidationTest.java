package io.github.selenium.javaDriverless.fpmapping;

import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FingerprintMappingCreepPolicyValidationTest extends FingerprintMappingValidationSupport {
    @Test
    void creepjsIgnoresNonCreepWebglTextAfterTheScreenSection() {
        String body = FingerprintMappingSiteBodies.creepjsBody(
                "0% like headless: clean", "0% headless: clean")
                + """

                WebGL
                images: blocked
                pixels: blocked
                params (0): blocked
                exts (0): blocked
                gpu: blocked
                Screen
                """;

        Map<String, Object> result = FingerprintMappingValidation.validate(
                "creepjs", "CreepJS", "https://abrahamjuliot.github.io/creepjs/",
                body, body, "", creepExpectedProfile(), domEvidence("creepjs"));

        assertEquals("pass", result.get("status"), result.toString());
    }

    @Test
    void creepjsWorkerGpuParserSkipsConfidenceWithoutDuplicatingVendor() {
        String body = FingerprintMappingSiteBodies.creepjsBody(
                "0% like headless: clean", "0% headless: clean")
                .replace("gpu:\nGoogle Inc. (Mesa)",
                        "gpu:\nconfidence: high\nGoogle Inc. (Mesa)");

        Map<String, Object> result = FingerprintMappingValidation.validate(
                "creepjs", "CreepJS", "https://abrahamjuliot.github.io/creepjs/",
                body, body, "", creepExpectedProfile(), domEvidence("creepjs"));

        assertEquals("pass", result.get("status"), result.toString());
        @SuppressWarnings("unchecked")
        Map<String, Object> extracted = (Map<String, Object>) result.get("extracted");
        @SuppressWarnings("unchecked")
        Map<String, String> worker = (Map<String, String>) extracted.get("worker");
        assertEquals("Google Inc. (Mesa)", worker.get("gpuVendor"));
        assertEquals("ANGLE (Mesa, llvmpipe (LLVM 20.1.2 256 bits), OpenGL 4.5)",
                worker.get("gpuRenderer"));
    }

    @Test
    void creepjsRejectsAnyNonZeroPercentageNotOnlyEnumeratedSamples() {
        String body = FingerprintMappingSiteBodies.creepjsBody(
                "17% like headless: detected", "11% headless: detected");
        Map<String, Object> result = FingerprintMappingValidation.validate(
                "creepjs", "CreepJS", "https://abrahamjuliot.github.io/creepjs/",
                body, body, "", creepExpectedProfile(), domEvidence("creepjs"));

        assertEquals("forbidden-signal", result.get("status"));
    }

    @Test
    void creepjsMapsCanvasHashesAndRejectsDetectedRgbaNoise() {
        String clean = FingerprintMappingSiteBodies.creepjsBody(
                "0% like headless: clean", "0% headless: clean");
        Map<String, Object> accepted = FingerprintMappingValidation.validate(
                "creepjs", "CreepJS", "https://abrahamjuliot.github.io/creepjs/",
                clean, clean, "", creepExpectedProfile(), domEvidence("creepjs"));
        assertEquals("pass", accepted.get("status"), accepted.toString());
        @SuppressWarnings("unchecked")
        Map<String, Object> extracted = (Map<String, Object>) accepted.get("extracted");
        assertEquals(Map.of(
                "hash", "0123abcd",
                "dataHash", "89abcdef",
                "rendering", "none",
                "textMetrics", "0.014635898437500001",
                "textHash", "32f2431d",
                "emojiHash", "343e70fc",
                "paintGpuHash", "285e3cf1",
                "paintCpuHash", "285e3cf1",
                "combinedHash", "178c2768"), extracted.get("canvas"));
        assertEquals(java.util.List.of(), extracted.get("liedSections"));
        @SuppressWarnings("unchecked")
        Map<String, Object> expected = (Map<String, Object>) accepted.get("expected");
        @SuppressWarnings("unchecked")
        Map<String, String> policies = (Map<String, String>) expected.get("policies");
        @SuppressWarnings("unchecked")
        Map<String, String> matched = (Map<String, String>) expected.get("matched");
        Map.of(
                "creepjs.canvas.textHash", "32f2431d",
                "creepjs.canvas.emojiHash", "343e70fc",
                "creepjs.canvas.paintGpuHash", "285e3cf1",
                "creepjs.canvas.paintCpuHash", "285e3cf1",
                "creepjs.canvas.combinedHash", "178c2768")
                .forEach((key, value) -> {
                    assertEquals("present", policies.get(key), key);
                    assertEquals(value, matched.get(key), key);
                });

        String noisy = clean.replace("rendering:\n", "rendering: 16% rgba noise\n");
        Map<String, Object> rejected = FingerprintMappingValidation.validate(
                "creepjs", "CreepJS", "https://abrahamjuliot.github.io/creepjs/",
                noisy, noisy, "", creepExpectedProfile(), domEvidence("creepjs"));
        assertEquals("forbidden-signal", rejected.get("status"), rejected.toString());
        assertEquals(java.util.List.of("creepjs-canvas-rgba-noise"), rejected.get("blockers"));
    }

    @Test
    void creepjsRejectsLiesClassesAndExposesUniqueSectionsWithoutTreatingBoldFailAsALie() {
        String body = FingerprintMappingSiteBodies.creepjsBody(
                "0% like headless: clean", "0% headless: clean");
        String cleanPage = body + "<span class=\"bold-fail\">language mismatch</span>";
        Map<String, Object> boldFailOnly = FingerprintMappingValidation.validate(
                "creepjs", "CreepJS", "https://abrahamjuliot.github.io/creepjs/",
                body, cleanPage, "", creepExpectedProfile(), domEvidence("creepjs"));
        assertEquals("pass", boldFailOnly.get("status"), boldFailOnly.toString());

        String liedPage = cleanPage
                + "<strong>Canvas 2d</strong><span data-test=\"1\" class=\"lies hash\">deadbeef</span>"
                + "<strong>WebGL</strong><span class='hash extra lies'>cafebabe</span>"
                + "<strong>Canvas 2d</strong><span class=\"hash lies\">deadbeef</span>";
        Map<String, Object> rejected = FingerprintMappingValidation.validate(
                "creepjs", "CreepJS", "https://abrahamjuliot.github.io/creepjs/",
                body, liedPage, "", creepExpectedProfile(), domEvidence("creepjs"));

        assertEquals("forbidden-signal", rejected.get("status"), rejected.toString());
        assertEquals(java.util.List.of("creepjs-lies-detected"), rejected.get("blockers"));
        @SuppressWarnings("unchecked")
        Map<String, Object> extracted = (Map<String, Object>) rejected.get("extracted");
        assertEquals(java.util.List.of("Canvas 2d", "WebGL"), extracted.get("liedSections"));
    }

    @Test
    void creepjsAllowsCurrentLinuxChromeBaselineWithoutMaskingRiskSignals() {
        String body = FingerprintMappingSiteBodies.creepjsBody(
                "25% like headless: baseline", "0% headless: clean");
        String safePage = body + " noWebShare: true noContentIndex: true "
                + "noContactsManager: true noDownlinkMax: true hasKnownBgColor: true";
        Map<String, Object> baseline = FingerprintMappingValidation.validate(
                "creepjs", "CreepJS", "https://abrahamjuliot.github.io/creepjs/",
                body, safePage, "", creepExpectedProfile(), domEvidence("creepjs"));
        assertEquals("pass", baseline.get("status"));

        Map<String, Object> risky = FingerprintMappingValidation.validate(
                "creepjs", "CreepJS", "https://abrahamjuliot.github.io/creepjs/",
                body, safePage + " noPlugins: true", "", creepExpectedProfile(), domEvidence("creepjs"));
        assertEquals("forbidden-signal", risky.get("status"));
    }

    @Test
    void trustMyIpFailsWhenPositiveMarketingAndNegativeResultCoexist() {
        String body = "Proxy Checker\nNo Proxy or VPN Detected\nVPN Likely — WebRTC Shows Different IP\nLeak!\n"
                + "result ".repeat(50);
        Map<String, Object> result = FingerprintMappingValidation.validate(
                "trustmyip-proxy", "TrustMyIP Proxy Checker", "https://trustmyip.com/proxy-checker",
                body, body, "", Map.of(), domEvidence("trustmyip-proxy"));

        assertEquals("forbidden-signal", result.get("status"));
    }

    @Test
    void browserLeaksWebglRequiresARealRendererValue() {
        String body = "WebGL Browser Report\nUnmasked Renderer\nUnmasked Vendor\n" + "report ".repeat(50);
        Map<String, Object> result = FingerprintMappingValidation.validate(
                "browserleaks-webgl", "WebGL Browser Report - BrowserLeaks",
                "https://browserleaks.com/webgl", body, body, "",
                Map.of("browserleaks-webgl.renderer", "ANGLE (Intel, Test GPU)"),
                domEvidence("browserleaks-webgl"));

        assertEquals("contract-broken", result.get("status"));
    }

    @Test
    void browserLeaksWebglSkipsWarningMarkerAndMatchesExactRenderer() {
        String renderer = "ANGLE (Intel, Mesa Intel UHD Graphics, OpenGL 4.6)";
        String body = "WebGL Browser Report\nUnmasked Renderer\n!\n" + renderer + "\n" + "report ".repeat(50);
        Map<String, Object> result = FingerprintMappingValidation.validate(
                "browserleaks-webgl", "WebGL Browser Report - BrowserLeaks",
                "https://browserleaks.com/webgl", body, body, "",
                Map.of("browserleaks-webgl.renderer", renderer), domEvidence("browserleaks-webgl"));

        assertEquals("pass", result.get("status"));
    }

    @Test
    void webglReportMatchesInlineUnmaskedValuesExactly() {
        String vendor = "Google Inc. (Intel)";
        String renderer = "ANGLE (Intel, Mesa Intel UHD Graphics, OpenGL 4.6)";
        String body = """
                WebGL Report
                WebGL 1 WebGL 2
                Context Name: webgl
                GL Version: WebGL 1
                Shading Language Version: GLSL
                Vendor: WebKit
                Renderer: WebKit WebGL
                Unmasked Vendor: Google Inc. (Intel)
                Unmasked Renderer: ANGLE (Intel, Mesa Intel UHD Graphics, OpenGL 4.6)
                Max Vertex Texture Image Units: 32
                Max Texture Size: 16384
                Best Float Precision: highp
                Supported Extensions: 10
                """ + "report ".repeat(50);
        Map<String, Object> result = FingerprintMappingValidation.validate(
                "webgl-report", "WebGL Report", "https://webglreport.com/",
                body, body, "", Map.of(
                        "webgl-report.vendor", vendor,
                        "webgl-report.renderer", renderer), domEvidence("webgl-report"));

        assertEquals("pass", result.get("status"));
    }

    @Test
    void creepjsRejectsDirectWebrtcCandidates() {
        String body = FingerprintMappingSiteBodies.creepjsBody(
                "0% like headless: clean", "0% headless: clean")
                .replace("typ relay", "typ host");
        Map<String, Object> result = FingerprintMappingValidation.validate(
                "creepjs", "CreepJS", "https://abrahamjuliot.github.io/creepjs/",
                body, body, "", creepExpectedProfile(), domEvidence("creepjs"));

        assertEquals("forbidden-signal", result.get("status"));
    }

    @Test
    void creepjsAllowsMdnsHostCandidateBecauseItDoesNotExposeAnIp() {
        String body = FingerprintMappingSiteBodies.creepjsBody(
                "0% like headless: clean", "0% headless: clean")
                .replace("203.0.113.20 50000 typ relay", "a1b2c3d4.local 50000 typ host");
        Map<String, Object> result = FingerprintMappingValidation.validate(
                "creepjs", "CreepJS", "https://abrahamjuliot.github.io/creepjs/",
                body, body, "", creepExpectedProfile(), domEvidence("creepjs"));

        assertEquals("pass", result.get("status"));
    }

    @Test
    void creepjsAllowsOnlyExplicitlyExpectedPublicCandidate() {
        String body = FingerprintMappingSiteBodies.creepjsBody(
                "0% like headless: clean", "0% headless: clean")
                .replace("typ relay", "typ srflx");
        Map<String, String> expected = new java.util.LinkedHashMap<>(creepExpectedProfile());
        expected.put("webRtc.publicIp", "203.0.113.20");
        Map<String, Object> result = FingerprintMappingValidation.validate(
                "creepjs", "CreepJS", "https://abrahamjuliot.github.io/creepjs/",
                body, body, "", expected, domEvidence("creepjs"));

        assertEquals("pass", result.get("status"));
    }

}
