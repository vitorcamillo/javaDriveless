package io.github.selenium.javaDriverless.fpmapping;

import io.github.selenium.javaDriverless.types.BrowserFingerprintProfile;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.util.LinkedHashMap;
import java.util.Map;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DockerRuntimeProofTest {
    @TempDir
    Path tempDir;

    @Test
    void minimalCdpWithRealWebglRejectsSwiftShaderRenderer() {
        BrowserFingerprintProfile profile = profile(
                BrowserFingerprintProfile.RUNTIME_POLICY_MINIMAL_CDP, "real");

        assertThatThrownBy(() -> DockerRuntimeProof.verify(
                runtime("ANGLE (Google, Vulkan 1.3.0 (SwiftShader Device (Subzero)))", profile), profile))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("minimal-cdp")
                .hasMessageContaining("webgl.mode=real")
                .hasMessageContaining("SwiftShader");
    }

    @Test
    void minimalCdpWithRealWebglAcceptsNativeRenderer() {
        BrowserFingerprintProfile profile = profile(
                BrowserFingerprintProfile.RUNTIME_POLICY_MINIMAL_CDP, "real");

        assertThatCode(() -> DockerRuntimeProof.verify(
                runtime("ANGLE (NVIDIA Corporation, NVIDIA GeForce RTX 3060, OpenGL 4.6)", profile), profile))
                .doesNotThrowAnyException();
    }

    @Test
    void legacySpoofPreservesRealWebglSwiftShaderCompatibility() {
        BrowserFingerprintProfile profile = profile(
                BrowserFingerprintProfile.RUNTIME_POLICY_LEGACY_SPOOF, "real");

        assertThatCode(() -> DockerRuntimeProof.verify(
                runtime("ANGLE (Google, Vulkan 1.3.0 (SwiftShader Device (Subzero)))", profile), profile))
                .doesNotThrowAnyException();
    }

    @Test
    void canonicalProofRejectsAnIncompleteRuntimePayload() {
        BrowserFingerprintProfile profile = profile(
                BrowserFingerprintProfile.RUNTIME_POLICY_MINIMAL_CDP, "real");
        Map<String, Object> incomplete = runtime(
                "ANGLE (Mesa, llvmpipe, OpenGL 4.5)", profile);
        incomplete.remove("deviceMemory");

        assertThatThrownBy(() -> DockerRuntimeProof.verify(incomplete, profile))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("deviceMemory");
    }

    @Test
    void canonicalProofRejectsLiteralWebRtcIpCandidates() {
        BrowserFingerprintProfile profile = profile(
                BrowserFingerprintProfile.RUNTIME_POLICY_MINIMAL_CDP, "real");
        Map<String, Object> leaking = runtime("ANGLE (Mesa, llvmpipe, OpenGL 4.5)", profile);
        @SuppressWarnings("unchecked")
        Map<String, Object> webRtc = (Map<String, Object>) leaking.get("webrtc");
        webRtc.put("literalIpCandidateCount", 1);

        assertThatThrownBy(() -> DockerRuntimeProof.verify(leaking, profile))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("WebRTC")
                .hasMessageNotContaining("192.168.1.20");
    }

    @Test
    void dockerOptionsKeepSoftwareGlFallbackScopedToLegacyPolicy() throws Exception {
        BrowserFingerprintProfile legacy = profile(
                BrowserFingerprintProfile.RUNTIME_POLICY_LEGACY_SPOOF, "spoof");
        Path legacyPath = tempDir.resolve("legacy.json");
        legacy.toJson(legacyPath);

        BrowserFingerprintProfile minimal = profile(
                BrowserFingerprintProfile.RUNTIME_POLICY_MINIMAL_CDP, "real");
        Path minimalPath = tempDir.resolve("minimal.json");
        minimal.toJson(minimalPath);

        assertThatCode(() -> DockerRuntimeProof.dockerOptions(legacyPath))
                .doesNotThrowAnyException();
        var legacyArguments = DockerRuntimeProof.dockerOptions(legacyPath).getArguments();
        var minimalArguments = DockerRuntimeProof.dockerOptions(minimalPath).getArguments();

        org.assertj.core.api.Assertions.assertThat(legacyArguments)
                .contains("--use-gl=angle", "--use-angle=swiftshader", "--enable-unsafe-swiftshader");
        org.assertj.core.api.Assertions.assertThat(minimalArguments)
                .doesNotContain("--use-gl=angle", "--use-angle=swiftshader", "--enable-unsafe-swiftshader");
    }

    @Test
    void canonicalDockerOptionsAreVisibleMinimalAndUseNativeGraphics() {
        var options = DockerRuntimeProof.dockerOptions();
        options.applyMinimalCdpProcessArgumentPolicy();

        org.assertj.core.api.Assertions.assertThat(options.isHeadless()).isFalse();
        org.assertj.core.api.Assertions.assertThat(options.getFingerprintProfile().runtimePolicy)
                .isEqualTo(BrowserFingerprintProfile.RUNTIME_POLICY_MINIMAL_CDP);
        org.assertj.core.api.Assertions.assertThat(options.getFingerprintProfile().screen.mode)
                .isEqualTo("spoof");
        org.assertj.core.api.Assertions.assertThat(options.getFingerprintProfile().webgl.mode)
                .isEqualTo("real");
        org.assertj.core.api.Assertions.assertThat(options.getArguments())
                .contains("--disable-dev-shm-usage")
                .doesNotContain("--no-sandbox", "--disable-setuid-sandbox")
                .noneMatch(argument -> argument.startsWith("--headless"))
                .doesNotContain(
                        "--use-gl=angle",
                        "--use-angle=swiftshader",
                        "--enable-unsafe-swiftshader",
                        "--disable-blink-features=AutomationControlled");
    }

    @Test
    void canonicalProofRequiresSandboxedUnprivilegedBrowserProcess() {
        assertThatCode(() -> DockerRuntimeProof.verifySandbox(Map.of(
                "capEff", "0000000000000000",
                "noNewPrivs", "1",
                "seccomp", "2")))
                .doesNotThrowAnyException();

        assertThatThrownBy(() -> DockerRuntimeProof.verifySandbox(Map.of(
                "capEff", "0000000000000001",
                "noNewPrivs", "0",
                "seccomp", "0")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("sandbox");
    }

    @Test
    void canonicalProofRejectsRuntimePayloadThatIsNotAMap() {
        assertThatThrownBy(() -> DockerRuntimeProof.verify("não-sou-mapa", profile(
                BrowserFingerprintProfile.RUNTIME_POLICY_MINIMAL_CDP, "real")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("not a map");
    }

    @Test
    void canonicalProofRejectsHeadlessOrForeignUserAgents() {
        BrowserFingerprintProfile profile = profile(
                BrowserFingerprintProfile.RUNTIME_POLICY_MINIMAL_CDP, "real");

        Map<String, Object> headless = runtime("ANGLE (Mesa, llvmpipe)", profile);
        headless.put("userAgent", "Mozilla/5.0 HeadlessChrome/150.0.0.0");
        Map<String, Object> estranho = runtime("ANGLE (Mesa, llvmpipe)", profile);
        estranho.put("userAgent", "Mozilla/5.0 Gecko/20100101");

        assertThatThrownBy(() -> DockerRuntimeProof.verify(headless, profile))
                .hasMessageContaining("userAgent");
        assertThatThrownBy(() -> DockerRuntimeProof.verify(estranho, profile))
                .hasMessageContaining("userAgent");
    }

    @Test
    void canonicalProofRequiresWebdriverExactlyFalse() {
        BrowserFingerprintProfile profile = profile(
                BrowserFingerprintProfile.RUNTIME_POLICY_MINIMAL_CDP, "real");
        Map<String, Object> automatizado = runtime("ANGLE (Mesa, llvmpipe)", profile);
        automatizado.put("webdriver", true);

        assertThatThrownBy(() -> DockerRuntimeProof.verify(automatizado, profile))
                .hasMessageContaining("webdriver");
    }

    @Test
    void canonicalProofRejectsLocaleTimezoneAndHardwareDivergence() {
        BrowserFingerprintProfile profile = profile(
                BrowserFingerprintProfile.RUNTIME_POLICY_MINIMAL_CDP, "real");

        Map<String, Object> idiomaErrado = runtime("ANGLE (Mesa, llvmpipe)", profile);
        idiomaErrado.put("language", "de-DE");
        Map<String, Object> languagesErradas = runtime("ANGLE (Mesa, llvmpipe)", profile);
        languagesErradas.put("languages", java.util.List.of("de-DE"));
        Map<String, Object> núcleosErrados = runtime("ANGLE (Mesa, llvmpipe)", profile);
        núcleosErrados.put("hardwareConcurrency", 1);
        Map<String, Object> timezoneErrada = runtime("ANGLE (Mesa, llvmpipe)", profile);
        timezoneErrada.put("timezone", "Europe/Berlin");

        assertThatThrownBy(() -> DockerRuntimeProof.verify(idiomaErrado, profile))
                .hasMessageContaining("language");
        assertThatThrownBy(() -> DockerRuntimeProof.verify(languagesErradas, profile))
                .hasMessageContaining("languages");
        assertThatThrownBy(() -> DockerRuntimeProof.verify(núcleosErrados, profile))
                .hasMessageContaining("hardwareConcurrency");
        assertThatThrownBy(() -> DockerRuntimeProof.verify(timezoneErrada, profile))
                .hasMessageContaining("timezone");
    }

    @Test
    void canonicalProofRejectsScreenAndWebglPayloadsThatDoNotMatch() {
        BrowserFingerprintProfile profile = profile(
                BrowserFingerprintProfile.RUNTIME_POLICY_MINIMAL_CDP, "real");

        Map<String, Object> telaNãoMapa = runtime("ANGLE (Mesa, llvmpipe)", profile);
        telaNãoMapa.put("screen", "1365x900");
        Map<String, Object> tamanhoErrado = runtime("ANGLE (Mesa, llvmpipe)", profile);
        tamanhoErrado.put("screen", new LinkedHashMap<>(Map.of(
                "width", 800, "height", 600,
                "availWidth", 800, "availHeight", 560,
                "colorDepth", 24, "pixelDepth", 24)));
        Map<String, Object> availErrado = runtime("ANGLE (Mesa, llvmpipe)", profile);
        @SuppressWarnings("unchecked")
        Map<String, Object> screen = new LinkedHashMap<>(
                (Map<String, Object>) availErrado.get("screen"));
        screen.put("availWidth", 100);
        availErrado.put("screen", screen);
        Map<String, Object> webglNãoMapa = runtime("ANGLE (Mesa, llvmpipe)", profile);
        webglNãoMapa.put("webgl", "sem dados");
        Map<String, Object> vendorVazio = runtime("ANGLE (Mesa, llvmpipe)", profile);
        vendorVazio.put("webgl", Map.of("vendor", "  ", "renderer", "ANGLE"));
        Map<String, Object> rendererNulo = runtime("ANGLE (Mesa, llvmpipe)", profile);
        rendererNulo.put("webgl", new LinkedHashMap<>(Map.of("vendor", "Google Inc.", "renderer", "")));

        assertThatThrownBy(() -> DockerRuntimeProof.verify(telaNãoMapa, profile))
                .hasMessageContaining("screen payload");
        assertThatThrownBy(() -> DockerRuntimeProof.verify(tamanhoErrado, profile))
                .hasMessageContaining("screen size");
        assertThatThrownBy(() -> DockerRuntimeProof.verify(availErrado, profile))
                .hasMessageContaining("availWidth");
        assertThatThrownBy(() -> DockerRuntimeProof.verify(webglNãoMapa, profile))
                .hasMessageContaining("webgl payload");
        assertThatThrownBy(() -> DockerRuntimeProof.verify(vendorVazio, profile))
                .hasMessageContaining("vendor");
        assertThatThrownBy(() -> DockerRuntimeProof.verify(rendererNulo, profile))
                .hasMessageContaining("renderer");
    }

    @Test
    void minimalCdpWithSpoofScreenRequiresMatchingViewport() {
        BrowserFingerprintProfile profile = profile(
                BrowserFingerprintProfile.RUNTIME_POLICY_MINIMAL_CDP, "real");
        profile.screen.mode = "spoof";

        Map<String, Object> semViewport = runtime("ANGLE (NVIDIA, RTX 3060, OpenGL 4.6)", profile);
        semViewport.remove("viewport");
        Map<String, Object> viewportErrado = runtime("ANGLE (NVIDIA, RTX 3060, OpenGL 4.6)", profile);
        viewportErrado.put("viewport", Map.of(
                "outerWidth", 500, "outerHeight", 400, "devicePixelRatio", 1.0));
        Map<String, Object> dprErrado = runtime("ANGLE (NVIDIA, RTX 3060, OpenGL 4.6)", profile);
        dprErrado.put("viewport", Map.of(
                "outerWidth", profile.screen.width,
                "outerHeight", profile.screen.availHeight - 8,
                "devicePixelRatio", 3.0));
        Map<String, Object> coerente = runtime("ANGLE (NVIDIA, RTX 3060, OpenGL 4.6)", profile);

        assertThatThrownBy(() -> DockerRuntimeProof.verify(semViewport, profile))
                .hasMessageContaining("viewport");
        assertThatThrownBy(() -> DockerRuntimeProof.verify(viewportErrado, profile))
                .hasMessageContaining("outerWidth");
        assertThatThrownBy(() -> DockerRuntimeProof.verify(dprErrado, profile))
                .hasMessageContaining("devicePixelRatio");
        assertThatCode(() -> DockerRuntimeProof.verify(coerente, profile))
                .doesNotThrowAnyException();
    }

    @Test
    void canonicalProofRequiresSupportedWebrtcWhenUdpIsDisabled() {
        BrowserFingerprintProfile profile = profile(
                BrowserFingerprintProfile.RUNTIME_POLICY_MINIMAL_CDP, "real");
        profile.webRtc.disableUdp = true;

        Map<String, Object> semWebrtc = runtime("ANGLE (Mesa, llvmpipe)", profile);
        semWebrtc.remove("webrtc");
        Map<String, Object> webrtcSemSuporte = runtime("ANGLE (Mesa, llvmpipe)", profile);
        @SuppressWarnings("unchecked")
        Map<String, Object> webrtc = new LinkedHashMap<>(
                (Map<String, Object>) webrtcSemSuporte.get("webrtc"));
        webrtc.put("supported", false);
        webrtcSemSuporte.put("webrtc", webrtc);

        assertThatThrownBy(() -> DockerRuntimeProof.verify(semWebrtc, profile))
                .hasMessageContaining("WebRTC");
        assertThatThrownBy(() -> DockerRuntimeProof.verify(webrtcSemSuporte, profile))
                .hasMessageContaining("WebRTC");
    }

    @Test
    void sandboxContractFailsClosedWithoutEvidence() {
        assertThatThrownBy(() -> DockerRuntimeProof.verifySandbox(null))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("sandbox");
    }

    @Test
    void processSecurityReadsOwnProcStatus() throws Exception {
        Map<String, String> sandbox = DockerRuntimeProof.processSecurity(ProcessHandle.current().pid());

        org.assertj.core.api.Assertions.assertThat(sandbox).containsKeys("capEff", "noNewPrivs", "seccomp");
    }

    private static BrowserFingerprintProfile profile(String runtimePolicy, String webglMode) {
        BrowserFingerprintProfile profile = new BrowserFingerprintProfile();
        profile.runtimePolicy = runtimePolicy;
        profile.webgl.mode = webglMode;
        profile.locale = "en-US";
        profile.languages = java.util.List.of("en-US", "en");
        profile.timezoneId = "America/New_York";
        profile.screen.width = 1365;
        profile.screen.height = 900;
        profile.screen.availWidth = 1365;
        profile.screen.availHeight = 860;
        profile.screen.colorDepth = 24;
        profile.screen.pixelDepth = 24;
        profile.screen.deviceScaleFactor = 1.0;
        return profile;
    }

    private static Map<String, Object> runtime(
            String renderer,
            BrowserFingerprintProfile profile) {
        Map<String, Object> runtime = new LinkedHashMap<>();
        runtime.put("userAgent", "Mozilla/5.0 Chrome/150.0.0.0 Safari/537.36");
        runtime.put("webdriver", false);
        runtime.put("language", profile.locale);
        runtime.put("languages", profile.languages);
        runtime.put("hardwareConcurrency", profile.hardwareConcurrency);
        runtime.put("deviceMemory", profile.deviceMemory);
        runtime.put("timezone", profile.timezoneId);
        runtime.put("screen", Map.of(
                "width", profile.screen.width,
                "height", profile.screen.height,
                "availWidth", profile.screen.availWidth,
                "availHeight", profile.screen.availHeight,
                "colorDepth", profile.screen.colorDepth,
                "pixelDepth", profile.screen.pixelDepth));
        runtime.put("webgl", Map.of("vendor", "Google Inc.", "renderer", renderer));
        runtime.put("viewport", Map.of(
                "outerWidth", profile.screen.width,
                "outerHeight", profile.screen.availHeight - 8,
                "devicePixelRatio", profile.screen.deviceScaleFactor));
        runtime.put("webrtc", new LinkedHashMap<>(Map.of(
                "supported", true,
                "candidateCount", 0,
                "literalIpCandidateCount", 0,
                "candidateTypes", new java.util.ArrayList<>())));
        return runtime;
    }
}
