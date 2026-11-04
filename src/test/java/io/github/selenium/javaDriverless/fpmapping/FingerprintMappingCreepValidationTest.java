package io.github.selenium.javaDriverless.fpmapping;

import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FingerprintMappingCreepValidationTest extends FingerprintMappingValidationSupport {
@Test
    void creepjsContractAllowsZeroHeadlessButCatchesNonZeroHeadlessControls() {
        Map<String, Object> clean = FingerprintMappingValidation.validate(
                "creepjs", "CreepJS", "https://abrahamjuliot.github.io/creepjs/",
                FingerprintMappingSiteBodies.creepjsBody("0% like headless: clean", "0% headless: clean"),
                FingerprintMappingSiteBodies.creepjsBody("0% like headless: clean", "0% headless: clean"),
                "", creepExpectedProfile(), domEvidence("creepjs"));
        assertEquals("pass", clean.get("status"));

        Map<String, Object> red = FingerprintMappingValidation.validate(
                "creepjs", "CreepJS", "https://abrahamjuliot.github.io/creepjs/",
                FingerprintMappingSiteBodies.creepjsBody("38% like headless: red", "33% headless: red"),
                FingerprintMappingSiteBodies.creepjsBody("38% like headless: red", "33% headless: red"),
                "", creepExpectedProfile(), domEvidence("creepjs"));
        assertEquals("forbidden-signal", red.get("status"));

        @SuppressWarnings("unchecked")
        Map<String, Object> extracted = (Map<String, Object>) red.get("extracted");
        @SuppressWarnings("unchecked")
        Map<String, String> worker = (Map<String, String>) extracted.get("worker");
        assertEquals("ServiceWorkerGlobalScope", worker.get("scope"));
        assertEquals("ANGLE (Mesa, llvmpipe (LLVM 20.1.2 256 bits), OpenGL 4.5)",
                worker.get("gpuRenderer"));
        assertEquals("cores: 16, ram: 32", worker.get("device"));
    }

    @Test
    void creepjsRealWebglRequiresObservedGpuButNotAFabricatedExpectedGpu() {
        String body = FingerprintMappingSiteBodies.creepjsBody(
                "0% like headless: clean", "0% headless: clean");
        Map<String, Object> result = FingerprintMappingValidation.validate(
                "creepjs", "CreepJS", "https://abrahamjuliot.github.io/creepjs/",
                body, body, "",
                Map.of("creepjs.canvas.renderingPattern", "^(?:0%\\s+rgba\\s+noise|none)$"),
                domEvidence("creepjs"));

        assertEquals("pass", result.get("status"), result.toString());
        @SuppressWarnings("unchecked")
        Map<String, Object> expected = (Map<String, Object>) result.get("expected");
        @SuppressWarnings("unchecked")
        Map<String, String> policies = (Map<String, String>) expected.get("policies");
        assertEquals("present", policies.get("creepjs.webgl.gpuVendor"));
        assertEquals("present", policies.get("creepjs.webgl.gpuRenderer"));
        assertEquals("present", policies.get("creepjs.worker.gpuVendor"));
        assertEquals("present", policies.get("creepjs.worker.gpuRenderer"));
        assertEquals(24, result.get("matchedCount"));

        @SuppressWarnings("unchecked")
        Map<String, Object> extracted = (Map<String, Object>) result.get("extracted");
        @SuppressWarnings("unchecked")
        Map<String, String> webgl = (Map<String, String>) extracted.get("webgl");
        assertEquals("ecf168f0", webgl.get("hash"));
        assertEquals("469dac5a", webgl.get("imagesHash"));
        assertEquals("78", webgl.get("paramsCount"));
        assertEquals("Google Inc. (Mesa)", webgl.get("gpuVendor"));
        assertEquals("ANGLE (Mesa, llvmpipe (LLVM 20.1.2 256 bits), OpenGL 4.5)",
                webgl.get("gpuRenderer"));
    }

    @Test
    void creepjsMainWebglGpuMustEqualAConfiguredSpoof() {
        String body = FingerprintMappingSiteBodies.creepjsBody(
                "0% like headless: clean", "0% headless: clean");
        Map<String, String> matching = new java.util.LinkedHashMap<>(creepExpectedProfile());
        matching.put("creepjs.webgl.gpuVendor", "Google Inc. (Mesa)");
        matching.put("creepjs.webgl.gpuRenderer",
                "ANGLE (Mesa, llvmpipe (LLVM 20.1.2 256 bits), OpenGL 4.5)");

        Map<String, Object> pass = FingerprintMappingValidation.validate(
                "creepjs", "CreepJS", "https://abrahamjuliot.github.io/creepjs/",
                body, body, "", matching, domEvidence("creepjs"));
        Map<String, String> mismatching = new java.util.LinkedHashMap<>(matching);
        mismatching.put("creepjs.webgl.gpuRenderer", "ANGLE (Configured GPU)");
        Map<String, Object> mismatch = FingerprintMappingValidation.validate(
                "creepjs", "CreepJS", "https://abrahamjuliot.github.io/creepjs/",
                body, body, "", mismatching, domEvidence("creepjs"));

        assertEquals("pass", pass.get("status"), pass.toString());
        assertEquals("value-mismatch", mismatch.get("status"), mismatch.toString());
        assertTrue(((List<?>) mismatch.get("decisiveExpectedMismatches")).stream()
                .map(Object::toString)
                .anyMatch(value -> value.contains("creepjs.webgl.gpuRenderer")));
    }

    @Test
    void creepjsRejectsGpuMismatchBetweenMainAndWorkerWithoutConfiguredGpu() {
        String body = FingerprintMappingSiteBodies.creepjsBody(
                "0% like headless: clean", "0% headless: clean")
                .replace("""
                        gpu:
                        Google Inc. (Mesa)
                        ANGLE (Mesa, llvmpipe (LLVM 20.1.2 256 bits), OpenGL 4.5)
                        """, """
                        gpu:
                        Google Inc. (Google)
                        ANGLE (Google, Vulkan SwiftShader)
                        """);

        Map<String, Object> result = FingerprintMappingValidation.validate(
                "creepjs", "CreepJS", "https://abrahamjuliot.github.io/creepjs/",
                body, body, "",
                Map.of("creepjs.canvas.renderingPattern", "^(?:0%\\s+rgba\\s+noise|none)$"),
                domEvidence("creepjs"));

        assertEquals("contract-broken", result.get("status"), result.toString());
        assertTrue(((List<?>) result.get("blockers"))
                .contains("creepjs-webgl-realm-mismatch"));
    }

    @Test
    void creepjsRejectsBlockedWebglInsteadOfTreatingLabelsAsGpuValues() {
        String body = FingerprintMappingSiteBodies.creepjsBody(
                "0% like headless: clean", "0% headless: clean")
                .replace("Google Inc. (Mesa)\n"
                                + "ANGLE (Mesa, llvmpipe (LLVM 20.1.2 256 bits), OpenGL 4.5)",
                        "unsupported\nuserAgent:")
                .replace("WebGLecf168f0", """
                        WebGL
                        images: blocked
                        pixels: blocked
                        params (0): blocked
                        exts (0): blocked
                        gpu:
                        blocked
                        """);

        Map<String, Object> result = FingerprintMappingValidation.validate(
                "creepjs", "CreepJS", "https://abrahamjuliot.github.io/creepjs/",
                body, body, "",
                Map.of("creepjs.canvas.renderingPattern", "^(?:0%\\s+rgba\\s+noise|none)$"),
                domEvidence("creepjs"));

        assertEquals("contract-broken", result.get("status"), result.toString());
        @SuppressWarnings("unchecked")
        List<String> blockers = (List<String>) result.get("blockers");
        assertTrue(blockers.contains("creepjs-webgl-unavailable"), result.toString());
        @SuppressWarnings("unchecked")
        Map<String, Object> extracted = (Map<String, Object>) result.get("extracted");
        @SuppressWarnings("unchecked")
        Map<String, String> worker = (Map<String, String>) extracted.get("worker");
        assertFalse(worker.containsKey("gpuVendor"), worker.toString());
        assertFalse(worker.containsKey("gpuRenderer"), worker.toString());
    }

    @Test
    void creepjsRejectsUnavailableWorkerGpuEvenWhenMainWebglIsNotBlocked() {
        String body = FingerprintMappingSiteBodies.creepjsBody(
                "0% like headless: clean", "0% headless: clean")
                .replace("""
                        Worker
                        gpu:
                        Google Inc. (Mesa)
                        ANGLE (Mesa, llvmpipe (LLVM 20.1.2 256 bits), OpenGL 4.5)
                        """, """
                        Worker
                        gpu:
                        unsupported
                        userAgent:
                        """);

        Map<String, Object> result = FingerprintMappingValidation.validate(
                "creepjs", "CreepJS", "https://abrahamjuliot.github.io/creepjs/",
                body, body, "", creepExpectedProfile(), domEvidence("creepjs"));

        assertEquals("contract-broken", result.get("status"), result.toString());
        assertTrue(((List<?>) result.get("blockers")).contains("creepjs-webgl-unavailable"));
    }

    @Test
    void creepjsRejectsBlockedMainWebglEvenWhenWorkerGpuIsValid() {
        String body = FingerprintMappingSiteBodies.creepjsBody(
                "0% like headless: clean", "0% headless: clean")
                .replace("WebGLecf168f0", """
                        WebGLecf168f0
                        images: blocked
                        pixels: blocked
                        params (0): blocked
                        exts (0): blocked
                        gpu:
                        blocked
                        Screenf1fb85cd
                        """);

        Map<String, Object> result = FingerprintMappingValidation.validate(
                "creepjs", "CreepJS", "https://abrahamjuliot.github.io/creepjs/",
                body, body, "", creepExpectedProfile(), domEvidence("creepjs"));

        assertEquals("contract-broken", result.get("status"), result.toString());
        assertTrue(((List<?>) result.get("blockers")).contains("creepjs-webgl-unavailable"));
    }

    @Test
    void creepjsDoesNotTreatUnrelatedUnsupportedLabelsAsWebglFailure() {
        String body = FingerprintMappingSiteBodies.creepjsBody(
                "0% like headless: clean", "0% headless: clean")
                + "\nfoundation/ip:\nunsupported\napps: unsupported\nwebgpu: unsupported\n";

        Map<String, Object> result = FingerprintMappingValidation.validate(
                "creepjs", "CreepJS", "https://abrahamjuliot.github.io/creepjs/",
                body, body, "", creepExpectedProfile(), domEvidence("creepjs"));

        assertEquals("pass", result.get("status"), result.toString());
    }

    @Test
    void creepjsRejectsMissingMainWebglSection() {
        String body = FingerprintMappingSiteBodies.creepjsBody(
                "0% like headless: clean", "0% headless: clean")
                .replace("""
                        WebGLecf168f0
                        images:469dac5a
                        pixels:a8ee71dc
                        params (78): e6cd09ba
                        exts (67): 096fe525
                        gpu:
                        confidence: high
                        Google Inc. (Mesa)
                        ANGLE (Mesa, llvmpipe (LLVM 20.1.2 256 bits), OpenGL 4.5)
                        """, "");

        Map<String, Object> result = FingerprintMappingValidation.validate(
                "creepjs", "CreepJS", "https://abrahamjuliot.github.io/creepjs/",
                body, body, "", creepExpectedProfile(), domEvidence("creepjs"));

        assertEquals("contract-broken", result.get("status"), result.toString());
        assertTrue(((List<?>) result.get("blockers")).contains("creepjs-webgl-unavailable"));
    }

    @Test
    void creepjsRejectsEmptyMainWebglSection() {
        String body = FingerprintMappingSiteBodies.creepjsBody(
                "0% like headless: clean", "0% headless: clean")
                .replace("""
                        images:469dac5a
                        pixels:a8ee71dc
                        params (78): e6cd09ba
                        exts (67): 096fe525
                        gpu:
                        confidence: high
                        Google Inc. (Mesa)
                        ANGLE (Mesa, llvmpipe (LLVM 20.1.2 256 bits), OpenGL 4.5)
                        """, "");

        Map<String, Object> result = FingerprintMappingValidation.validate(
                "creepjs", "CreepJS", "https://abrahamjuliot.github.io/creepjs/",
                body, body, "", creepExpectedProfile(), domEvidence("creepjs"));

        assertEquals("contract-broken", result.get("status"), result.toString());
        assertTrue(((List<?>) result.get("blockers")).contains("creepjs-webgl-unavailable"));
    }

    @Test
    void creepjsRejectsPartialMainWebglGpu() {
        String body = FingerprintMappingSiteBodies.creepjsBody(
                "0% like headless: clean", "0% headless: clean")
                .replace("""
                        gpu:
                        confidence: high
                        Google Inc. (Mesa)
                        ANGLE (Mesa, llvmpipe (LLVM 20.1.2 256 bits), OpenGL 4.5)
                        """, """
                        gpu:
                        confidence: high
                        Google Inc. (Mesa)
                        """);

        Map<String, Object> result = FingerprintMappingValidation.validate(
                "creepjs", "CreepJS", "https://abrahamjuliot.github.io/creepjs/",
                body, body, "", creepExpectedProfile(), domEvidence("creepjs"));

        assertEquals("contract-broken", result.get("status"), result.toString());
        assertTrue(((List<?>) result.get("blockers")).contains("creepjs-webgl-unavailable"));
    }

    @Test
    void creepjsRejectsMissingMainWebglEvenWhenUnrelatedTextContainsWebgl() {
        String body = FingerprintMappingSiteBodies.creepjsBody(
                "0% like headless: clean", "0% headless: clean")
                .replace("""
                        WebGLecf168f0
                        images:469dac5a
                        pixels:a8ee71dc
                        params (78): e6cd09ba
                        exts (67): 096fe525
                        gpu:
                        confidence: high
                        Google Inc. (Mesa)
                        ANGLE (Mesa, llvmpipe (LLVM 20.1.2 256 bits), OpenGL 4.5)
                        """, "")
                + "\nWebGL API documentation\n";

        Map<String, Object> result = FingerprintMappingValidation.validate(
                "creepjs", "CreepJS", "https://abrahamjuliot.github.io/creepjs/",
                body, body, "", creepExpectedProfile(), Map.of());

        assertEquals("contract-broken", result.get("status"), result.toString());
        assertTrue(((List<?>) result.get("blockers")).contains("creepjs-webgl-unavailable"));
    }

}
