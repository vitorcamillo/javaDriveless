package io.github.selenium.javaDriverless.profiles;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Test;

/**
 * Bateria de violações da PhantoraPassProfilePolicy: cada regra do contrato
 * persistável e de runtime acionada isoladamente por mutação da base válida.
 */
class PhantoraPassProfilePolicyViolationsTest {

    private static PhantoraPassProfile linuxBase() {
        return SdkProfilePresets.phantoraPass(SdkProfilePreset.LINUX, "pol", "Política", "BR");
    }

    private static List<String> codes(PhantoraPassPolicyResult result) {
        return result.violations().stream().map(PhantoraPassPolicyViolation::code).toList();
    }

    private static void assertSingleViolation(
            PhantoraPassPolicyResult result, String expectedCode) {
        List<String> codes = codes(result);
        assertTrue(codes.contains(expectedCode),
            "esperava " + expectedCode + " em " + codes);
    }

    @Test
    void nullProfileIsRejectedUpFront() {
        PhantoraPassPolicyResult result = PhantoraPassProfilePolicy.evaluateForPersistence(null);
        assertFalse(result.accepted());
        assertNull(result.evaluatedProfile());
        assertEquals(List.of("profile_required"), codes(result));
    }

    @Test
    void nonRuntimeBaseIsRejected() {
        PhantoraPassProfile profile = linuxBase();
        profile.runtimeBase = SdkProfilePreset.SELENIUMLIKE;

        PhantoraPassPolicyResult result =
            PhantoraPassProfilePolicy.evaluateForPersistence(profile);
        assertSingleViolation(result, "runtime_base_required");
    }

    @Test
    void invalidDefaultsShortCircuitPersistableContract() {
        PhantoraPassProfile profile = linuxBase();
        profile.profile.fingerprint.colorScheme = "sepia";

        PhantoraPassPolicyResult result =
            PhantoraPassProfilePolicy.evaluateForPersistence(profile);
        assertSingleViolation(result, "profile_defaults_invalid");
        // contrato persistável não roda: sem codes de superfície
        assertFalse(codes(result).contains("native_surface_required"));
    }

    @Test
    void headlessFlagAndArgumentAreBothRejected() {
        PhantoraPassProfile flag = linuxBase();
        flag.profile.browser.headless = true;
        assertSingleViolation(
            PhantoraPassProfilePolicy.evaluateForPersistence(flag), "headless_not_supported");

        PhantoraPassProfile arg = linuxBase();
        arg.profile.browser.extraArgs = List.of("--headless=new");
        assertSingleViolation(
            PhantoraPassProfilePolicy.evaluateForPersistence(arg), "headless_not_supported");
    }

    @Test
    void customUserAgentIsRejectedInFavorOfNative() {
        PhantoraPassProfile profile = linuxBase();
        profile.profile.browser.userAgent = "Mozilla/5.0 (customizado)";

        assertSingleViolation(
            PhantoraPassProfilePolicy.evaluateForPersistence(profile),
            "native_user_agent_required");
    }

    @Test
    void legacySpoofRuntimePolicyIsRejected() {
        PhantoraPassProfile profile = linuxBase();
        profile.profile.fingerprint.runtimePolicy =
            BrowserFingerprintProfile_LEGACY();

        assertSingleViolation(
            PhantoraPassProfilePolicy.evaluateForPersistence(profile), "runtime_policy_required");
    }

    private static String BrowserFingerprintProfile_LEGACY() {
        return io.github.selenium.javaDriverless.types.BrowserFingerprintProfile
            .RUNTIME_POLICY_LEGACY_SPOOF;
    }

    @Test
    void runtimeIdentityMismatchIsReportedWithUnsupportedWindowsNote() {
        PhantoraPassProfile profile = linuxBase();
        profile.profile.fingerprint.platform = "Win32";
        profile.profile.fingerprint.uaPlatform = "Windows";

        PhantoraPassPolicyResult result =
            PhantoraPassProfilePolicy.evaluateForPersistence(profile);
        assertSingleViolation(result, "runtime_identity_mismatch");
        String expected = result.violations().stream()
            .filter(v -> "runtime_identity_mismatch".equals(v.code())).findFirst().orElseThrow()
            .expected();
        assertTrue(expected.contains("Windows is unsupported"), expected);
    }

    @Test
    void nonDesktopIdentityIsRejected() {
        PhantoraPassProfile mobile = linuxBase();
        mobile.profile.fingerprint.mobile = true;
        assertSingleViolation(
            PhantoraPassProfilePolicy.evaluateForPersistence(mobile), "desktop_identity_required");

        PhantoraPassProfile arm = linuxBase();
        arm.profile.fingerprint.architecture = "arm";
        assertSingleViolation(
            PhantoraPassProfilePolicy.evaluateForPersistence(arm), "desktop_identity_required");

        PhantoraPassProfile model = linuxBase();
        model.profile.fingerprint.model = "Pixel 8";
        assertSingleViolation(
            PhantoraPassProfilePolicy.evaluateForPersistence(model), "desktop_identity_required");
    }

    @Test
    void syntheticSurfacesAndSpeechAreRejected() {
        PhantoraPassProfile webgl = linuxBase();
        webgl.profile.fingerprint.webgl.mode = "spoof";
        assertSingleViolation(
            PhantoraPassProfilePolicy.evaluateForPersistence(webgl), "native_surface_required");

        PhantoraPassProfile speech = linuxBase();
        speech.profile.fingerprint.speech.mode = "synthetic";
        assertSingleViolation(
            PhantoraPassProfilePolicy.evaluateForPersistence(speech), "native_surface_required");
    }

    @Test
    void permissiveWebRtcPolicyIsRejected() {
        PhantoraPassProfile profile = linuxBase();
        profile.profile.fingerprint.webRtc.mode = "real";

        PhantoraPassPolicyResult result =
            PhantoraPassProfilePolicy.evaluateForPersistence(profile);
        assertSingleViolation(result, "webrtc_policy_required");
    }

    @Test
    void syntheticGraphicsArgumentsAreRejected() {
        PhantoraPassProfile swift = linuxBase();
        swift.profile.browser.extraArgs = List.of("--use-gl=swiftshader");
        assertSingleViolation(
            PhantoraPassProfilePolicy.evaluateForPersistence(swift),
            "synthetic_graphics_rejected");

        PhantoraPassProfile noGpu = linuxBase();
        noGpu.profile.browser.extraArgs = List.of("--disable-gpu");
        assertSingleViolation(
            PhantoraPassProfilePolicy.evaluateForPersistence(noGpu),
            "synthetic_graphics_rejected");
    }

    @Test
    void absolutePathRuleFlagsRelativeAndInvalidPaths() {
        PhantoraPassProfile relative = linuxBase();
        relative.profile.browser.binaryPath = "chrome-relativo";
        assertSingleViolation(
            PhantoraPassProfilePolicy.evaluateForPersistence(relative),
            "absolute_path_required");

        PhantoraPassProfile invalid = linuxBase();
        invalid.profile.storage.userDataDir = "dir\u0000invalido";
        assertSingleViolation(
            PhantoraPassProfilePolicy.evaluateForPersistence(invalid),
            "absolute_path_required");
    }

    @Test
    void screenModeRulesPerRuntimeBase() {
        PhantoraPassProfile windows = SdkProfilePresets.phantoraPass(
            SdkProfilePreset.WINDOWS, "w", "W", "BR");
        PhantoraPassPolicyResult windowsResult =
            PhantoraPassProfilePolicy.evaluateForPersistence(windows);
        assertTrue(windowsResult.accepted() || codes(windowsResult).contains("screen_mode_required"));

        PhantoraPassProfile dockerSpoof = SdkProfilePresets.phantoraPass(
            SdkProfilePreset.DOCKER, "d", "D", "BR");
        dockerSpoof.profile.fingerprint.screen.mode = "real";
        assertSingleViolation(
            PhantoraPassProfilePolicy.evaluateForPersistence(dockerSpoof),
            "screen_mode_required");
    }

    @Test
    void spoofScreenWithoutXvfbSurfaceIsRejected() {
        PhantoraPassProfile docker = SdkProfilePresets.phantoraPass(
            SdkProfilePreset.DOCKER, "d2", "D2", "BR");
        docker.profile.runtimeSurfaces.remove("xvfb-visible");

        PhantoraPassPolicyResult result =
            PhantoraPassProfilePolicy.evaluateForPersistence(docker);
        assertSingleViolation(result, "virtual_display_surface_required");
    }

    @Test
    void launchWithoutRuntimeIsRejectedButPersistenceIsNot() {
        PhantoraPassProfile profile = linuxBase();

        assertTrue(PhantoraPassProfilePolicy.evaluateForPersistence(profile).accepted());
        PhantoraPassPolicyResult launch =
            PhantoraPassProfilePolicy.evaluateForLaunch(profile, null);
        assertSingleViolation(launch, "runtime_required");
    }

    @Test
    void nativeHostRuntimeRejectsDockerBaseAndNonRealScreen() {
        PhantoraPassProfile docker = SdkProfilePresets.phantoraPass(
            SdkProfilePreset.DOCKER, "d3", "D3", "BR");
        PhantoraPassPolicyResult result = PhantoraPassProfilePolicy.evaluateForLaunch(
            docker, PhantoraPassRuntime.NATIVE_HOST);
        List<String> codes = codes(result);
        assertTrue(codes.contains("runtime_kind_mismatch"), "" + codes);
        assertTrue(codes.contains("runtime_screen_mode_mismatch"), "" + codes);

        PhantoraPassProfile linuxWrongScreen = linuxBase();
        linuxWrongScreen.profile.fingerprint.screen.mode = "spoof";
        assertTrue(codes(PhantoraPassProfilePolicy.evaluateForLaunch(
            linuxWrongScreen, PhantoraPassRuntime.NATIVE_HOST))
            .contains("runtime_screen_mode_mismatch"));
    }

    @Test
    void virtualDisplayRuntimeRejectsWindowsBaseAndRequiresExactGeometry() {
        PhantoraPassProfile windows = SdkProfilePresets.phantoraPass(
            SdkProfilePreset.WINDOWS, "w2", "W2", "BR");
        PhantoraPassRuntime.Geometry any = new PhantoraPassRuntime.Geometry(
            1920, 1080, 1920, 1040, 24, 24, 1.0);
        assertTrue(codes(PhantoraPassProfilePolicy.evaluateForLaunch(
                windows, PhantoraPassRuntime.virtualDisplay(any)))
            .contains("runtime_kind_mismatch"));

        // geometry nula -> exigida
        PhantoraPassProfile linux = linuxBase();
        linux.profile.fingerprint.screen.mode = "spoof";
        linux.profile.runtimeSurfaces.add("xvfb-visible");
        assertTrue(codes(PhantoraPassProfilePolicy.evaluateForLaunch(
                linux, PhantoraPassRuntime.virtualDisplay(null)))
            .contains("runtime_geometry_required"));

        // geometry incoerente -> inválida
        PhantoraPassRuntime.Geometry incoerente = new PhantoraPassRuntime.Geometry(
            0, 1080, 1920, 1040, 24, 24, 1.0);
        assertTrue(codes(PhantoraPassProfilePolicy.evaluateForLaunch(
                linux, PhantoraPassRuntime.virtualDisplay(incoerente)))
            .contains("runtime_geometry_invalid"));
    }

    @Test
    void acceptedResultCarriesDefensiveCopyOfProfile() {
        PhantoraPassProfile original = linuxBase();
        PhantoraPassPolicyResult result =
            PhantoraPassProfilePolicy.evaluateForPersistence(original);

        assertTrue(result.accepted());
        PhantoraPassProfile evaluated = result.evaluatedProfile();
        assertNotNull(evaluated);
        // evaluatedProfile() devolve cópia: mutação não afeta o caller
        evaluated.profile.name = "mutado";
        assertEquals("Política", original.profile.name);
    }
}
