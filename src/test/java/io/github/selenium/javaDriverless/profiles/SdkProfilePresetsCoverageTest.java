package io.github.selenium.javaDriverless.profiles;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Test;

import io.github.selenium.javaDriverless.types.BrowserFingerprintProfile;

/** Catálogo público de presets + contrato PhantomPass + helpers legados bet365. */
class SdkProfilePresetsCoverageTest {

    @Test
    void supportedCatalogExposesTheFiveStablePresets() {
        List<SdkProfilePreset> presets = SdkProfilePresets.supportedPresets();

        assertEquals(5, presets.size());
        assertTrue(presets.contains(SdkProfilePreset.WINDOWS));
        assertTrue(presets.contains(SdkProfilePreset.LINUX));
        assertTrue(presets.contains(SdkProfilePreset.DOCKER));
        assertTrue(presets.contains(SdkProfilePreset.PHANTOMPASS));
        assertTrue(presets.contains(SdkProfilePreset.SELENIUMLIKE));
    }

    @Test
    void runtimeFactoriesProduceProfilesWithTheRightPreset() {
        assertEquals("windows", SdkProfilePresets.windows("w1", "Win BR", "BR").preset);
        assertEquals("linux", SdkProfilePresets.linux("l1", "Linux BR", "BR").preset);
        assertEquals("docker", SdkProfilePresets.docker("d1", "Docker BR", "BR").preset);
        assertEquals("seleniumlike",
            SdkProfilePresets.seleniumLike("s1", "Selenium Like").preset);

        SdkProfile runtime = SdkProfilePresets.defaultRuntime("r1", "Runtime", "BR");
        assertNotNull(runtime.preset);
        assertTrue(List.of("windows", "linux", "docker").contains(runtime.preset),
            "preset=" + runtime.preset);
    }

    @Test
    void factoriesPropagateIdNameAndCountryIntoFingerprint() {
        SdkProfile profile = SdkProfilePresets.windows("id-7", "Meu Perfil", "PT");

        assertEquals("id-7", profile.id);
        assertEquals("Meu Perfil", profile.name);
        // país vira locale/timezone do fingerprint gerado
        String locale = profile.fingerprint.locale;
        assertTrue(locale.toLowerCase().contains("pt"), "locale=" + locale);
    }

    @Test
    void phantomPassRequiresRuntimeBasePreset() {
        assertThrows(IllegalArgumentException.class,
            () -> SdkProfilePresets.phantomPass(SdkProfilePreset.SELENIUMLIKE, "p1", "P", "BR"));
        assertThrows(IllegalArgumentException.class,
            () -> SdkProfilePresets.phantomPass(null, "p1", "P", "BR"));
    }

    @Test
    void phantomPassBuildsEditableContractOverRuntimeBase() {
        PhantomPassProfile contract =
            SdkProfilePresets.phantomPass(SdkProfilePreset.WINDOWS, "pp-1", "PP", "BR");

        assertEquals(SdkProfilePreset.WINDOWS, contract.runtimeBase);
        assertEquals("phantompass", contract.profile.preset);
        assertEquals("pp-1", contract.profile.id);
        assertTrue(contract.profile.metadata.tags.contains("preset:phantompass"));
        assertTrue(contract.profile.metadata.tags.contains("runtime-base:windows"));
        assertTrue(contract.profile.runtimeSurfaces.contains("phantompass-configurable"));
        assertEquals("BR", contract.location.countryCode);
    }

    @Test
    void phantomPassNormalizesNullCountryToEmpty() {
        PhantomPassProfile contract =
            SdkProfilePresets.phantomPass(SdkProfilePreset.LINUX, "pp-2", "PP", null);

        assertEquals("", contract.location.countryCode);
    }

    @SuppressWarnings("deprecation")
    @Test
    void legacyBet365HelpersStayUsable() {
        SdkProfile legacy = SdkProfilePresets.bet365Br();

        assertEquals("bet365-br", legacy.id);
        assertEquals("legacy-compatibility", legacy.metadata.securityGuarantee);
        assertEquals(1440, legacy.browser.windowWidth);
        assertEquals(1000, legacy.browser.windowHeight);

        ChromeOptionsLike options = ChromeOptionsLike.wrap(SdkProfilePresets.bet365BrContainerOptions());
        assertTrue(options.hasArgumentStartingWith("--lang="));
        assertNotNull(options.fingerprint());
    }

    @SuppressWarnings("deprecation")
    @Test
    void legacyContainerOptionsAcceptCustomFingerprint() {
        BrowserFingerprintProfile custom = BrowserFingerprintProfile.brLinux();
        custom.name = "custom-fp";

        var options = SdkProfilePresets.bet365BrContainerOptions(custom);
        assertEquals("custom-fp", options.getFingerprintProfile().name);
    }

    /** fachada mínima para asserts legíveis sobre ChromeOptions sem expor imports extras. */
    private static final class ChromeOptionsLike {
        private final io.github.selenium.javaDriverless.types.ChromeOptions delegate;

        private ChromeOptionsLike(io.github.selenium.javaDriverless.types.ChromeOptions delegate) {
            this.delegate = delegate;
        }

        static ChromeOptionsLike wrap(io.github.selenium.javaDriverless.types.ChromeOptions options) {
            return new ChromeOptionsLike(options);
        }

        boolean hasArgumentStartingWith(String prefix) {
            return delegate.getArguments().stream().anyMatch(a -> a.startsWith(prefix));
        }

        BrowserFingerprintProfile fingerprint() {
            return delegate.getFingerprintProfile();
        }
    }
}
