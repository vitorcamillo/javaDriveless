package io.github.selenium.javaDriverless.profiles;

import io.github.selenium.javaDriverless.types.BrowserFingerprintProfile;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SdkProfileGeneratorTest {
    @Test
    void generatesTheSupportedCountryRuntimePresets() {
        SdkProfile brLinux = SdkProfileGenerator.fromPreset(
                new SdkProfilePresetRequest("br-linux", "BR Linux", "BR", "linux"));
        SdkProfile usWindows = SdkProfileGenerator.fromPreset(
                new SdkProfilePresetRequest("us-windows", "US Windows", "US", "windows"));
        SdkProfile docker = SdkProfileGenerator.fromPreset(
                new SdkProfilePresetRequest("docker", "Docker", "DE", "docker"));

        assertThat(brLinux.fingerprint.locale).isEqualTo("pt-BR");
        assertThat(brLinux.fingerprint.uaPlatform).isEqualTo("Linux");
        assertThat(usWindows.fingerprint.locale).isEqualTo("en-US");
        assertThat(usWindows.fingerprint.uaPlatform).isEqualTo("Windows");
        assertThat(docker.fingerprint.locale).isEqualTo("de-DE");
        assertThat(docker.fingerprint.uaPlatform).isEqualTo("Linux");
    }

    @Test
    void generatesHeadlessMobileAndCleanBaselineProfiles() {
        SdkProfilePresetRequest headlessRequest = new SdkProfilePresetRequest("headless", "Headless", "US", "linux-headless");
        headlessRequest.headless = true;
        SdkProfile headless = SdkProfileGenerator.fromLegacyPreset(headlessRequest);

        SdkProfilePresetRequest mobileRequest = new SdkProfilePresetRequest("android", "Android", "BR", "android");
        mobileRequest.mobile = true;
        SdkProfile mobile = SdkProfileGenerator.fromLegacyPreset(mobileRequest);

        SdkProfilePresetRequest cleanRequest = new SdkProfilePresetRequest("clean", "Clean", "US", "windows");
        cleanRequest.cleanBaseline = true;
        SdkProfile clean = SdkProfileGenerator.fromLegacyPreset(cleanRequest);

        assertThat(headless.browser.headless).isTrue();
        assertThat(headless.fingerprint.screen.width).isEqualTo(1366);
        assertThat(mobile.fingerprint.mobile).isTrue();
        assertThat(mobile.fingerprint.model).isEqualTo("SM-A135M");
        assertThat(clean.fingerprint.name).isEqualTo("clean-baseline");
    }

    @Test
    void deterministicDiversityPersistsSeedAndClearsWhenDisabled() {
        SdkProfilePresetRequest diverseRequest = new SdkProfilePresetRequest("diverse", "Diverse", "US", "windows");
        diverseRequest.diversityEnabled = true;
        diverseRequest.seed = 42L;
        SdkProfile diverse = SdkProfileGenerator.fromLegacyPreset(diverseRequest);

        SdkProfilePresetRequest cleanRequest = new SdkProfilePresetRequest("no-diversity", "No Diversity", "US", "windows");
        cleanRequest.diversityEnabled = false;
        SdkProfile noDiversity = SdkProfileGenerator.fromLegacyPreset(cleanRequest);

        assertThat(diverse.metadata.seeds).containsEntry("diversity", 42L);
        assertThat(diverse.fingerprint.canvas.mode).isEqualTo("noise");
        assertThat(diverse.fingerprint.webgl.mode).isEqualTo("spoof_noise");
        assertThat(diverse.fingerprint.audio.noise).isGreaterThan(0);
        assertThat(noDiversity.fingerprint.canvas.mode).isEqualTo("real");
        assertThat(noDiversity.fingerprint.canvas.noise).isZero();
        assertThat(noDiversity.fingerprint.audio.noise).isZero();
        assertThat(noDiversity.fingerprint.clientRects.noise).isZero();
    }

    @Test
    void automaticDiversitySeedUsesSha256InsteadOfJavaHashCode() {
        assertThat("Aa".hashCode()).isEqualTo("BB".hashCode());

        SdkProfilePresetRequest firstRequest = new SdkProfilePresetRequest("Aa", "First", "US", "windows");
        firstRequest.diversityEnabled = true;
        SdkProfilePresetRequest secondRequest = new SdkProfilePresetRequest("BB", "Second", "US", "windows");
        secondRequest.diversityEnabled = true;

        SdkProfile first = SdkProfileGenerator.fromLegacyPreset(firstRequest);
        SdkProfile second = SdkProfileGenerator.fromLegacyPreset(secondRequest);
        SdkProfile repeated = SdkProfileGenerator.fromLegacyPreset(firstRequest);

        assertThat(first.metadata.seeds.get("diversity")).isEqualTo(120659287887428483L);
        assertThat(second.metadata.seeds.get("diversity")).isEqualTo(8964534017140580854L);
        assertThat(first.metadata.seeds.get("diversity")).isNotEqualTo(second.metadata.seeds.get("diversity"));
        assertThat(first.fingerprint.noiseSeed).isNotEqualTo(second.fingerprint.noiseSeed);
        assertThat(repeated.metadata.seeds.get("diversity")).isEqualTo(first.metadata.seeds.get("diversity"));
        assertThat(repeated.fingerprint.noiseSeed).isEqualTo(first.fingerprint.noiseSeed);
    }

    @Test
    void publicRandomFingerprintProfilesReceiveStableDistinctNoiseSeeds() {
        BrowserFingerprintProfile first = BrowserFingerprintProfile.randomProfile("linux", 101L);
        BrowserFingerprintProfile repeated = BrowserFingerprintProfile.randomProfile("linux", 101L);
        BrowserFingerprintProfile second = BrowserFingerprintProfile.randomProfile("linux", 202L);

        assertThat(first.noiseSeed).isEqualTo(repeated.noiseSeed);
        assertThat(first.noiseSeed).isNotEqualTo(second.noiseSeed);
        assertThat(first.noiseSeed).isNotEqualTo(1337);
        assertThat(first.webgl.mode).isEqualTo("spoof_noise");
    }

    @Test
    void directNormalizerAlsoUsesCollisionResistantSeed() {
        SdkProfile first = new SdkProfile("Aa", "First");
        SdkProfile second = new SdkProfile("BB", "Second");

        SdkProfileNormalizer.normalize(first);
        SdkProfileNormalizer.normalize(second);

        assertThat(first.metadata.seeds.get("diversity"))
                .isNotEqualTo(second.metadata.seeds.get("diversity"));
        assertThat(first.fingerprint.noiseSeed).isNotEqualTo(second.fingerprint.noiseSeed);
    }

    @Test
    void normalizerRejectsMissingProfileIdWithAStableValidationError() {
        assertThatThrownBy(() -> SdkProfileNormalizer.normalize(new SdkProfile()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("id");
    }

    @Test
    void detectsStaticFingerprintConflictsBeforeLaunch() {
        SdkProfile profile = new SdkProfile("conflict", "Conflict");
        profile.fingerprint.uaPlatform = "Windows";
        profile.fingerprint.platform = "MacIntel";
        profile.fingerprint.locale = "pt-BR";
        profile.fingerprint.languages = List.of("en-US", "en");
        profile.fingerprint.webgl.renderer = "ANGLE (Apple, ANGLE Metal Renderer: Apple M3, Unspecified Version)";

        SdkProfileNormalizationResult result = SdkProfileNormalizer.normalize(profile, false, null);

        assertThat(result.status).isEqualTo("conflict");
        assertThat(result.conflicts).anyMatch(message -> message.contains("uaPlatform=Windows"));
        assertThat(result.conflicts).anyMatch(message -> message.contains("locale"));
        assertThat(result.conflicts).anyMatch(message -> message.contains("Apple WebGL"));
    }

    @Test
    void generatedPresetNormalizesToReady() {
        SdkProfile profile = SdkProfileGenerator.fromPreset(
                new SdkProfilePresetRequest("ready", "Ready", "BR", "linux"));

        SdkProfileNormalizationResult result = SdkProfileNormalizer.normalize(profile, false, null);

        assertThat(result.isReady()).isTrue();
        assertThat(result.conflicts).isEmpty();
    }

    @Test
    void generatedVisibleMinimalCdpPresetUsesOnlyNativeSurfaceModes() {
        SdkProfilePresetRequest request = new SdkProfilePresetRequest(
                "native-linux", "Native Linux", "BR", "linux");
        request.runtimePolicy = BrowserFingerprintProfile.RUNTIME_POLICY_MINIMAL_CDP;

        SdkProfile profile = SdkProfileGenerator.fromPreset(request);
        BrowserFingerprintProfile nativeBaseline = BrowserFingerprintProfile.nativeDefaults();

        assertThat(profile.fingerprint.runtimePolicy)
                .isEqualTo(BrowserFingerprintProfile.RUNTIME_POLICY_MINIMAL_CDP);
        assertThat(profile.fingerprint.hardwareConcurrency)
                .isEqualTo(nativeBaseline.hardwareConcurrency);
        assertThat(profile.fingerprint.deviceMemory).isEqualTo(nativeBaseline.deviceMemory);
        assertThat(profile.fingerprint.vendor).isEqualTo(nativeBaseline.vendor);
        assertThat(profile.fingerprint.doNotTrack).isEqualTo(nativeBaseline.doNotTrack);
        assertThat(profile.browser.headless).isFalse();
        assertThat(profile.fingerprint.browserVersion).isEmpty();
        assertThat(profile.fingerprint.platformVersion).isEmpty();
        assertThat(profile.fingerprint.screen.mode).isEqualTo("real");
        assertThat(profile.fingerprint.webRtc.mode).isEqualTo("altered");
        assertThat(profile.fingerprint.webRtc.disableUdp).isTrue();
        assertThat(List.of(
                profile.fingerprint.webgl.mode,
                profile.fingerprint.mediaDevices.mode,
                profile.fingerprint.audio.mode,
                profile.fingerprint.canvas.mode,
                profile.fingerprint.clientRects.mode,
                profile.fingerprint.storage.mode,
                profile.fingerprint.fonts.mode,
                profile.fingerprint.ports.mode,
                profile.fingerprint.connection.mode))
                .containsOnly("real");
        assertThat(profile.fingerprint.speech.mode).isEqualTo("auto");
        assertThat(profile.fingerprint.maxTouchPoints).isZero();
    }

    @Test
    void generatedMinimalCdpDesktopUsesTheRealOperatingSystemScreen() {
        SdkProfilePresetRequest request = new SdkProfilePresetRequest(
                "native-windows", "Native Windows", "US", "windows");
        request.runtimePolicy = BrowserFingerprintProfile.RUNTIME_POLICY_MINIMAL_CDP;

        SdkProfile profile = SdkProfileGenerator.fromPreset(request);

        assertThat(profile.fingerprint.screen.mode).isEqualTo("real");
    }

    @Test
    void minimalCdpRejectsHeadlessPresetsAndDirectsServersToHeadfulXvfb() {
        SdkProfilePresetRequest explicitHeadless = new SdkProfilePresetRequest(
                "native-headless", "Native Headless", "BR", "linux");
        explicitHeadless.headless = true;
        explicitHeadless.runtimePolicy = BrowserFingerprintProfile.RUNTIME_POLICY_MINIMAL_CDP;

        assertThatThrownBy(() -> SdkProfileGenerator.fromPreset(explicitHeadless))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("minimal-cdp")
                .hasMessageContaining("Chrome visível")
                .hasMessageContaining("Xvfb");

        SdkProfilePresetRequest headlessAlias = new SdkProfilePresetRequest(
                "native-headless-alias", "Native Headless Alias", "BR", "linux-headless");
        headlessAlias.runtimePolicy = BrowserFingerprintProfile.RUNTIME_POLICY_MINIMAL_CDP;

        assertThatThrownBy(() -> SdkProfileGenerator.fromPreset(headlessAlias))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Perfil não suportado")
                .hasMessageContaining("docker");
    }

    @Test
    void minimalCdpRejectsDetectableNoiseDiversityInsteadOfIgnoringIt() {
        SdkProfilePresetRequest request = new SdkProfilePresetRequest(
                "native-diverse", "Native Diverse", "BR", "linux");
        request.runtimePolicy = BrowserFingerprintProfile.RUNTIME_POLICY_MINIMAL_CDP;
        request.diversityEnabled = true;

        assertThatThrownBy(() -> SdkProfileGenerator.fromPreset(request))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("minimal-cdp")
                .hasMessageContaining("diversityEnabled")
                .hasMessageContaining("wrappers JavaScript detectáveis");
    }
}
