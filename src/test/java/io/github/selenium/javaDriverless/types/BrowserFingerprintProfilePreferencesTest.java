package io.github.selenium.javaDriverless.types;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Preferências/normalizações do BrowserFingerprintProfileDefaults: colorScheme,
 * prefers-*, formFactors, defaults de subsistemas e runtimePolicy — caminhos que
 * o teste de cgroup não alcança.
 */
class BrowserFingerprintProfilePreferencesTest {

    @TempDir
    Path emptyRoot;

    private BrowserFingerprintProfile apply(BrowserFingerprintProfile profile) {
        // cgroup vazio + sem selfCgroup: nenhum arquivo lido, foco nas preferências
        BrowserFingerprintProfileDefaults.applyTo(profile, 8, emptyRoot, null, java.util.Map.of());
        return profile;
    }

    @Test
    void runtimePolicyNormalizesAndRejectsUnknownValues() {
        BrowserFingerprintProfile blank = apply(new BrowserFingerprintProfile());
        assertEquals(BrowserFingerprintProfile.RUNTIME_POLICY_LEGACY_SPOOF, blank.runtimePolicy);

        BrowserFingerprintProfile normalized = new BrowserFingerprintProfile();
        normalized.runtimePolicy = "  MINIMAL-CDP ";
        assertEquals(BrowserFingerprintProfile.RUNTIME_POLICY_MINIMAL_CDP,
            apply(normalized).runtimePolicy);

        BrowserFingerprintProfile invalid = new BrowserFingerprintProfile();
        invalid.runtimePolicy = "turbo";
        assertThrows(IllegalArgumentException.class, () -> apply(invalid));
    }

    @Test
    void colorSchemeDefaultsToLightAndNormalizesCase() {
        assertEquals("light", apply(new BrowserFingerprintProfile()).colorScheme);

        BrowserFingerprintProfile dark = new BrowserFingerprintProfile();
        dark.colorScheme = "  DARK ";
        assertEquals("dark", apply(dark).colorScheme);

        BrowserFingerprintProfile invalid = new BrowserFingerprintProfile();
        invalid.colorScheme = "sepia";
        assertThrows(IllegalArgumentException.class, () -> apply(invalid));
    }

    @Test
    void mediaPreferencesDefaultToNoPreferenceAndValidate() {
        BrowserFingerprintProfile profile = apply(new BrowserFingerprintProfile());

        assertEquals("no-preference", profile.prefersReducedMotion);
        assertEquals("no-preference", profile.prefersReducedTransparency);
        assertEquals("no-preference", profile.prefersContrast);
        assertEquals("none", profile.forcedColors);

        BrowserFingerprintProfile reduce = new BrowserFingerprintProfile();
        reduce.prefersReducedMotion = " Reduce ";
        reduce.prefersReducedTransparency = "reduce";
        reduce.prefersContrast = "More";
        reduce.forcedColors = "ACTIVE";
        apply(reduce);
        assertEquals("reduce", reduce.prefersReducedMotion);
        assertEquals("more", reduce.prefersContrast);
        assertEquals("active", reduce.forcedColors);

        BrowserFingerprintProfile invalid = new BrowserFingerprintProfile();
        invalid.prefersContrast = "much";
        assertThrows(IllegalArgumentException.class, () -> apply(invalid));

        BrowserFingerprintProfile invalidForced = new BrowserFingerprintProfile();
        invalidForced.forcedColors = "sometimes";
        assertThrows(IllegalArgumentException.class, () -> apply(invalidForced));
    }

    @Test
    void formFactorsDeriveFromMobileAndNormalizeLists() {
        assertEquals(List.of("Desktop"),
            apply(new BrowserFingerprintProfile()).formFactors);

        BrowserFingerprintProfile mobile = new BrowserFingerprintProfile();
        mobile.mobile = true;
        assertEquals(List.of("Mobile"), apply(mobile).formFactors);

        BrowserFingerprintProfile messy = new BrowserFingerprintProfile();
        messy.formFactors = java.util.Arrays.asList(" Tablet ", null, "XR", "Tablet", "");
        apply(messy);
        assertEquals(List.of("Tablet", "XR"), messy.formFactors);

        BrowserFingerprintProfile invalid = new BrowserFingerprintProfile();
        invalid.formFactors = List.of("Holodeck");
        assertThrows(IllegalArgumentException.class, () -> apply(invalid));
    }

    @Test
    void missingSubsystemsAreInitializedWithDefaults() {
        BrowserFingerprintProfile profile = new BrowserFingerprintProfile();
        profile.languages = null;
        profile.screen = null;
        profile.webgl = null;
        profile.webRtc = null;
        profile.geolocation = null;
        profile.permissions = null;
        profile.mediaDevices = null;
        profile.speech = null;
        profile.audio = null;
        profile.canvas = null;
        profile.clientRects = null;
        profile.storage = null;
        profile.fonts = null;
        profile.ports = null;
        profile.connection = null;

        apply(profile);

        assertEquals(List.of("en-US", "en"), profile.languages);
        assertNotNull(profile.screen);
        assertNotNull(profile.webgl);
        assertNotNull(profile.webRtc);
        assertNotNull(profile.geolocation);
        assertNotNull(profile.permissions);
        assertNotNull(profile.mediaDevices);
        assertNotNull(profile.speech);
        assertNotNull(profile.audio);
        assertNotNull(profile.canvas);
        assertNotNull(profile.clientRects);
        assertNotNull(profile.storage);
        assertNotNull(profile.fonts);
        assertNotNull(profile.ports);
        assertNotNull(profile.connection);
        assertEquals("spoof", profile.storage.mode);
    }

    @Test
    void canvasScopeAndStorageModeValidateTheirDomains() {
        BrowserFingerprintProfile valid = new BrowserFingerprintProfile();
        valid.canvas.scope = "Detached";
        valid.storage.mode = "REAL";
        apply(valid);
        assertEquals("detached", valid.canvas.scope);
        assertEquals("real", valid.storage.mode);

        BrowserFingerprintProfile badCanvas = new BrowserFingerprintProfile();
        badCanvas.canvas = new BrowserFingerprintProfile.Canvas();
        badCanvas.canvas.scope = "windowed";
        assertThrows(IllegalArgumentException.class, () -> apply(badCanvas));

        BrowserFingerprintProfile badStorage = new BrowserFingerprintProfile();
        badStorage.storage = new BrowserFingerprintProfile.Storage();
        badStorage.storage.mode = "hybrid";
        assertThrows(IllegalArgumentException.class, () -> apply(badStorage));
    }

    @Test
    void minimalCdpOnEmptyCgroupKeepsConfiguredProcessors() {
        BrowserFingerprintProfile profile = new BrowserFingerprintProfile();
        profile.runtimePolicy = BrowserFingerprintProfile.RUNTIME_POLICY_MINIMAL_CDP;
        profile.hardwareConcurrency = 4;

        apply(profile);

        // sem arquivos cgroup no root fake: 4 configurado vs 8 do runtime -> min(4,8)
        assertEquals(4, profile.hardwareConcurrency);

        BrowserFingerprintProfile unbounded = new BrowserFingerprintProfile();
        unbounded.runtimePolicy = BrowserFingerprintProfile.RUNTIME_POLICY_MINIMAL_CDP;
        unbounded.hardwareConcurrency = 0; // não configurado: usa runtime e nunca menos que 1
        apply(unbounded);
        assertTrue(unbounded.hardwareConcurrency >= 1);
    }
}
