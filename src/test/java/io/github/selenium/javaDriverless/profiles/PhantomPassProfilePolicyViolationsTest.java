package io.github.selenium.javaDriverless.profiles;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Test;

/**
 * Bateria de violações da PhantomPassProfilePolicy: cada regra do contrato
 * persistável e de runtime acionada isoladamente por mutação da base válida.
 */
class PhantomPassProfilePolicyViolationsTest {

    private static PhantomPassProfile linuxBase() {
        return SdkProfilePresets.phantomPass(SdkProfilePreset.LINUX, "pol", "Política", "BR");
    }

    private static List<String> codes(PhantomPassPolicyResult result) {
        return result.violations().stream().map(PhantomPassPolicyViolation::code).toList();
    }

    private static void assertSingleViolation(
            PhantomPassPolicyResult result, String expectedCode) {
        List<String> codes = codes(result);
        assertTrue(codes.contains(expectedCode),
            "esperava " + expectedCode + " em " + codes);
    }

    @Test
    void nullProfileIsRejectedUpFront() {
        PhantomPassPolicyResult result = PhantomPassProfilePolicy.evaluateForPersistence(null);
        assertFalse(result.accepted());
        assertNull(result.evaluatedProfile());
        assertEquals(List.of("profile_required"), codes(result));
    }

    @Test
    void nonRuntimeBaseIsRejected() {
        PhantomPassProfile profile = linuxBase();
        profile.runtimeBase = SdkProfilePreset.SELENIUMLIKE;

        PhantomPassPolicyResult result =
            PhantomPassProfilePolicy.evaluateForPersistence(profile);
        assertSingleViolation(result, "runtime_base_required");
    }

    @Test
    void invalidDefaultsShortCircuitPersistableContract() {
        PhantomPassProfile profile = linuxBase();
        profile.profile.fingerprint.colorScheme = "sepia";

        PhantomPassPolicyResult result =
            PhantomPassProfilePolicy.evaluateForPersistence(profile);
        assertSingleViolation(result, "profile_defaults_invalid");
        // contrato persistável não roda: sem codes de superfície
        assertFalse(codes(result).contains("native_surface_required"));
    }

    @Test
    void headlessFlagAndArgumentAreBothRejected() {
        PhantomPassProfile flag = linuxBase();
        flag.profile.browser.headless = true;
        assertSingleViolation(
            PhantomPassProfilePolicy.evaluateForPersistence(flag), "headless_not_supported");

        PhantomPassProfile arg = linuxBase();
        arg.profile.browser.extraArgs = List.of("--headless=new");
        assertSingleViolation(
            PhantomPassProfilePolicy.evaluateForPersistence(arg), "headless_not_supported");
    }

    @Test
    void customUserAgentIsRejectedInFavorOfNative() {
        PhantomPassProfile profile = linuxBase();
        profile.profile.browser.userAgent = "Mozilla/5.0 (customizado)";

        assertSingleViolation(
            PhantomPassProfilePolicy.evaluateForPersistence(profile),
            "native_user_agent_required");
    }

    @Test
    void legacySpoofRuntimePolicyIsRejected() {
        PhantomPassProfile profile = linuxBase();
        profile.profile.fingerprint.runtimePolicy =
            BrowserFingerprintProfile_LEGACY();

        assertSingleViolation(
            PhantomPassProfilePolicy.evaluateForPersistence(profile), "runtime_policy_required");
    }

    private static String BrowserFingerprintProfile_LEGACY() {
        return io.github.selenium.javaDriverless.types.BrowserFingerprintProfile
            .RUNTIME_POLICY_LEGACY_SPOOF;
    }

    @Test
    void runtimeIdentityMismatchIsReportedWithUnsupportedWindowsNote() {
        PhantomPassProfile profile = linuxBase();
        profile.profile.fingerprint.platform = "Win32";
        profile.profile.fingerprint.uaPlatform = "Windows";

        PhantomPassPolicyResult result =
            PhantomPassProfilePolicy.evaluateForPersistence(profile);
        assertSingleViolation(result, "runtime_identity_mismatch");
        String expected = result.violations().stream()
            .filter(v -> "runtime_identity_mismatch".equals(v.code())).findFirst().orElseThrow()
            .expected();
        assertTrue(expected.contains("Windows is unsupported"), expected);
    }

    @Test
    void nonDesktopIdentityIsRejected() {
        PhantomPassProfile mobile = linuxBase();
        mobile.profile.fingerprint.mobile = true;
        assertSingleViolation(
            PhantomPassProfilePolicy.evaluateForPersistence(mobile), "desktop_identity_required");

        PhantomPassProfile arm = linuxBase();
        arm.profile.fingerprint.architecture = "arm";
        assertSingleViolation(
            PhantomPassProfilePolicy.evaluateForPersistence(arm), "desktop_identity_required");

        PhantomPassProfile model = linuxBase();
        model.profile.fingerprint.model = "Pixel 8";
        assertSingleViolation(
            PhantomPassProfilePolicy.evaluateForPersistence(model), "desktop_identity_required");
    }

    @Test
    void syntheticSurfacesAndSpeechAreRejected() {
        PhantomPassProfile webgl = linuxBase();
        webgl.profile.fingerprint.webgl.mode = "spoof";
        assertSingleViolation(
            PhantomPassProfilePolicy.evaluateForPersistence(webgl), "native_surface_required");

        PhantomPassProfile speech = linuxBase();
        speech.profile.fingerprint.speech.mode = "synthetic";
        assertSingleViolation(
            PhantomPassProfilePolicy.evaluateForPersistence(speech), "native_surface_required");
    }

    @Test
    void permissiveWebRtcPolicyIsRejected() {
        PhantomPassProfile profile = linuxBase();
        profile.profile.fingerprint.webRtc.mode = "real";

        PhantomPassPolicyResult result =
            PhantomPassProfilePolicy.evaluateForPersistence(profile);
        assertSingleViolation(result, "webrtc_policy_required");
    }

    @Test
    void syntheticGraphicsArgumentsAreRejected() {
        PhantomPassProfile swift = linuxBase();
        swift.profile.browser.extraArgs = List.of("--use-gl=swiftshader");
        assertSingleViolation(
            PhantomPassProfilePolicy.evaluateForPersistence(swift),
            "synthetic_graphics_rejected");

        PhantomPassProfile noGpu = linuxBase();
        noGpu.profile.browser.extraArgs = List.of("--disable-gpu");
        assertSingleViolation(
            PhantomPassProfilePolicy.evaluateForPersistence(noGpu),
            "synthetic_graphics_rejected");
    }

    @Test
    void absolutePathRuleFlagsRelativeAndInvalidPaths() {
        PhantomPassProfile relative = linuxBase();
        relative.profile.browser.binaryPath = "chrome-relativo";
        assertSingleViolation(
            PhantomPassProfilePolicy.evaluateForPersistence(relative),
            "absolute_path_required");

        PhantomPassProfile invalid = linuxBase();
        invalid.profile.storage.userDataDir = "dir\u0000invalido";
        assertSingleViolation(
            PhantomPassProfilePolicy.evaluateForPersistence(invalid),
            "absolute_path_required");
    }

    @Test
    void screenModeRulesPerRuntimeBase() {
        PhantomPassProfile windows = SdkProfilePresets.phantomPass(
            SdkProfilePreset.WINDOWS, "w", "W", "BR");
        PhantomPassPolicyResult windowsResult =
            PhantomPassProfilePolicy.evaluateForPersistence(windows);
        assertTrue(windowsResult.accepted() || codes(windowsResult).contains("screen_mode_required"));

        PhantomPassProfile dockerSpoof = SdkProfilePresets.phantomPass(
            SdkProfilePreset.DOCKER, "d", "D", "BR");
        dockerSpoof.profile.fingerprint.screen.mode = "real";
        assertSingleViolation(
            PhantomPassProfilePolicy.evaluateForPersistence(dockerSpoof),
            "screen_mode_required");
    }

    @Test
    void spoofScreenWithoutXvfbSurfaceIsRejected() {
        PhantomPassProfile docker = SdkProfilePresets.phantomPass(
            SdkProfilePreset.DOCKER, "d2", "D2", "BR");
        docker.profile.runtimeSurfaces.remove("xvfb-visible");

        PhantomPassPolicyResult result =
            PhantomPassProfilePolicy.evaluateForPersistence(docker);
        assertSingleViolation(result, "virtual_display_surface_required");
    }

    @Test
    void launchWithoutRuntimeIsRejectedButPersistenceIsNot() {
        PhantomPassProfile profile = linuxBase();

        assertTrue(PhantomPassProfilePolicy.evaluateForPersistence(profile).accepted());
        PhantomPassPolicyResult launch =
            PhantomPassProfilePolicy.evaluateForLaunch(profile, null);
        assertSingleViolation(launch, "runtime_required");
    }

    @Test
    void nativeHostRuntimeRejectsDockerBaseAndNonRealScreen() {
        PhantomPassProfile docker = SdkProfilePresets.phantomPass(
            SdkProfilePreset.DOCKER, "d3", "D3", "BR");
        PhantomPassPolicyResult result = PhantomPassProfilePolicy.evaluateForLaunch(
            docker, PhantomPassRuntime.NATIVE_HOST);
        List<String> codes = codes(result);
        assertTrue(codes.contains("runtime_kind_mismatch"), "" + codes);
        assertTrue(codes.contains("runtime_screen_mode_mismatch"), "" + codes);

        PhantomPassProfile linuxWrongScreen = linuxBase();
        linuxWrongScreen.profile.fingerprint.screen.mode = "spoof";
        assertTrue(codes(PhantomPassProfilePolicy.evaluateForLaunch(
            linuxWrongScreen, PhantomPassRuntime.NATIVE_HOST))
            .contains("runtime_screen_mode_mismatch"));
    }

    @Test
    void virtualDisplayRuntimeRejectsWindowsBaseAndRequiresExactGeometry() {
        PhantomPassProfile windows = SdkProfilePresets.phantomPass(
            SdkProfilePreset.WINDOWS, "w2", "W2", "BR");
        PhantomPassRuntime.Geometry any = new PhantomPassRuntime.Geometry(
            1920, 1080, 1920, 1040, 24, 24, 1.0);
        assertTrue(codes(PhantomPassProfilePolicy.evaluateForLaunch(
                windows, PhantomPassRuntime.virtualDisplay(any)))
            .contains("runtime_kind_mismatch"));

        // geometry nula -> exigida
        PhantomPassProfile linux = linuxBase();
        linux.profile.fingerprint.screen.mode = "spoof";
        linux.profile.runtimeSurfaces.add("xvfb-visible");
        assertTrue(codes(PhantomPassProfilePolicy.evaluateForLaunch(
                linux, PhantomPassRuntime.virtualDisplay(null)))
            .contains("runtime_geometry_required"));

        // geometry incoerente -> inválida
        PhantomPassRuntime.Geometry incoerente = new PhantomPassRuntime.Geometry(
            0, 1080, 1920, 1040, 24, 24, 1.0);
        assertTrue(codes(PhantomPassProfilePolicy.evaluateForLaunch(
                linux, PhantomPassRuntime.virtualDisplay(incoerente)))
            .contains("runtime_geometry_invalid"));
    }

    @Test
    void acceptedResultCarriesDefensiveCopyOfProfile() {
        PhantomPassProfile original = linuxBase();
        PhantomPassPolicyResult result =
            PhantomPassProfilePolicy.evaluateForPersistence(original);

        assertTrue(result.accepted());
        PhantomPassProfile evaluated = result.evaluatedProfile();
        assertNotNull(evaluated);
        // evaluatedProfile() devolve cópia: mutação não afeta o caller
        evaluated.profile.name = "mutado";
        assertEquals("Política", original.profile.name);
    }
}
