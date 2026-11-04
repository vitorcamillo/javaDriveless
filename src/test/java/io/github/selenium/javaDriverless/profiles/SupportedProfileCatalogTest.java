package io.github.selenium.javaDriverless.profiles;

import io.github.selenium.javaDriverless.types.BrowserFingerprintProfile;
import io.github.selenium.javaDriverless.types.ChromeOptions;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SupportedProfileCatalogTest {
    @Test
    void advertisesExactlyFiveCanonicalProfiles() {
        assertThat(SdkProfilePreset.supportedIds()).containsExactly(
                "windows", "linux", "docker", "phantompass", "seleniumlike");
        assertThat(SdkProfilePresets.supportedPresets()).containsExactly(
                SdkProfilePreset.WINDOWS,
                SdkProfilePreset.LINUX,
                SdkProfilePreset.DOCKER,
                SdkProfilePreset.PHANTOMPASS,
                SdkProfilePreset.SELENIUMLIKE);
    }

    @Test
    void legacySeleniumLikeAliasResolvesWithoutBeingAdvertised() {
        assertThat(SdkProfilePreset.parse("selenium-like"))
                .isEqualTo(SdkProfilePreset.SELENIUMLIKE);
        assertThat(SdkProfilePreset.supportedIds()).doesNotContain("selenium-like");
    }

    @Test
    void defaultResolverSelectsOnlyOneOfTheThreeRuntimeProfiles() {
        assertThat(SdkProfilePreset.detectDefaultRuntime("Windows 11", Map.of(), false))
                .isEqualTo(SdkProfilePreset.WINDOWS);
        assertThat(SdkProfilePreset.detectDefaultRuntime("Linux", Map.of(), false))
                .isEqualTo(SdkProfilePreset.LINUX);
        assertThat(SdkProfilePreset.detectDefaultRuntime("Linux", Map.of(), true))
                .isEqualTo(SdkProfilePreset.DOCKER);
        assertThat(SdkProfilePreset.detectDefaultRuntime(
                "Linux", Map.of(SdkProfilePreset.PRESET_ENV, "docker"), false))
                .isEqualTo(SdkProfilePreset.DOCKER);

        assertThatThrownBy(() -> SdkProfilePreset.detectDefaultRuntime(
                "Linux", Map.of(SdkProfilePreset.PRESET_ENV, "phantompass"), false))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("seleção explícita");
        assertThatThrownBy(() -> SdkProfilePreset.detectDefaultRuntime("macOS", Map.of(), false))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("sem preset seguro");
    }

    @Test
    void rejectsRemovedPresetNamesInsteadOfFallingBackToWindows() {
        for (String removed : List.of(
                "macos", "android", "linux-headless", "bet365-br", "unknown")) {
            SdkProfilePresetRequest request = new SdkProfilePresetRequest(
                    "removed", "Removed", "US", removed);

            assertThatThrownBy(() -> SdkProfileGenerator.fromPreset(request))
                    .as(removed)
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("windows")
                    .hasMessageContaining("linux")
                    .hasMessageContaining("docker")
                    .hasMessageContaining("phantompass")
                    .hasMessageContaining("seleniumlike");
        }
    }

    @Test
    void safeRuntimePresetsAreVisibleMinimalAndWrapperFree() {
        SdkProfile windows = profile("windows", "US");
        SdkProfile linux = profile("linux", "BR");
        SdkProfile docker = profile("docker", "US");

        assertSafeNative(windows);
        assertSafeNative(linux);
        assertSafeNative(docker);
        assertThat(windows.preset).isEqualTo("windows");
        assertThat(windows.fingerprint.uaPlatform).isEqualTo("Windows");
        assertThat(windows.fingerprint.platform).isEqualTo("Win32");
        assertThat(linux.preset).isEqualTo("linux");
        assertThat(linux.fingerprint.uaPlatform).isEqualTo("Linux");
        assertThat(linux.fingerprint.platform).isEqualTo("Linux x86_64");
        assertThat(docker.preset).isEqualTo("docker");
        assertThat(docker.fingerprint.uaPlatform).isEqualTo("Linux");
        assertThat(docker.fingerprint.platform).isEqualTo("Linux x86_64");
        assertThat(docker.fingerprint.screen.mode).isEqualTo("spoof");
        assertThat(docker.fingerprint.screen.width).isEqualTo(1365);
        assertThat(docker.fingerprint.screen.height).isEqualTo(900);
        assertThat(docker.fingerprint.screen.availWidth).isEqualTo(1365);
        assertThat(docker.fingerprint.screen.availHeight).isEqualTo(860);
        assertThat(docker.runtimeSurfaces).contains("xvfb-visible");
        assertThat(docker.browser.extraArgs)
                .contains("--disable-dev-shm-usage")
                .doesNotContain("--no-sandbox", "--disable-setuid-sandbox");
    }

    @Test
    void unsafeFeatureCombinationsFailClosedForOfficialPresets() {
        SdkProfilePresetRequest request = new SdkProfilePresetRequest(
                "unsafe", "Unsafe", "BR", "linux");
        request.headless = true;

        assertThatThrownBy(() -> SdkProfileGenerator.fromPreset(request))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Chrome visível")
                .hasMessageContaining("Xvfb");

        request.headless = false;
        request.diversityEnabled = true;
        assertThatThrownBy(() -> SdkProfileGenerator.fromPreset(request))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("diversityEnabled");

        request.diversityEnabled = false;
        request.runtimePolicy = BrowserFingerprintProfile.RUNTIME_POLICY_LEGACY_SPOOF;
        assertThatThrownBy(() -> SdkProfileGenerator.fromPreset(request))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("minimal-cdp");

        SdkProfile mutated = SdkProfilePresets.docker("mutated", "Mutated", "BR");
        mutated.browser.headless = true;
        assertThatThrownBy(() -> ProfileChromeOptionsFactory.fromProfile(mutated))
                .isInstanceOf(ProfileCatalogException.class)
                .hasMessageContaining("minimal-cdp")
                .hasMessageContaining("Chrome visível")
                .hasMessageContaining("Xvfb");
    }

    @Test
    void seleniumLikeIsAnExplicitCompatibilityProfile() throws Exception {
        SdkProfile profile = profile("seleniumlike", "US");
        BrowserFingerprintProfile existingFixture = BrowserFingerprintProfile.fromJson(
                Path.of("docs/site-profiles/selenium-like.json"));

        assertThat(profile.preset).isEqualTo("seleniumlike");
        assertThat(profile.fingerprint.name).isEqualTo("selenium-like-minimal-surfaces");
        assertThat(profile.fingerprint.runtimePolicy)
                .isEqualTo(BrowserFingerprintProfile.RUNTIME_POLICY_LEGACY_SPOOF);
        assertThat(profile.fingerprint.uaPlatform).isEqualTo("Linux");
        assertThat(profile.fingerprint.webRtc.mode).isEqualTo("real");
        assertThat(profile.fingerprint.webRtc.disableUdp).isFalse();
        assertThat(profile.fingerprint.storage.mode).isEqualTo("spoof");
        assertThat(profile.metadata.recommended).isFalse();
        assertThat(profile.metadata.securityGuarantee).isEqualTo("compatibility-only");
        assertThat(profile.fingerprint.toJsonString()).isEqualTo(existingFixture.toJsonString());
    }

    @Test
    void phantomPassRequiresTheDedicatedConfigurableContract() {
        SdkProfilePresetRequest request = new SdkProfilePresetRequest(
                "saas", "SaaS", "BR", "phantompass");

        assertThatThrownBy(() -> SdkProfileGenerator.fromPreset(request))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("PhantomPassProfile")
                .hasMessageContaining("runtimeBase");
    }

    @Test
    void eachFactoryCallReturnsIndependentNestedState() {
        SdkProfile first = SdkProfilePresets.linux("first", "First", "BR");
        SdkProfile second = SdkProfilePresets.linux("second", "Second", "BR");

        first.fingerprint.screen.width = 1111;
        first.fingerprint.webRtc.allowedIps = List.of("203.0.113.10");
        first.browser.extraArgs = List.of("--first-only");
        first.metadata.tags.add("first-only");

        assertThat(second.fingerprint.screen.width).isNotEqualTo(1111);
        assertThat(second.fingerprint.webRtc.allowedIps).doesNotContain("203.0.113.10");
        assertThat(second.browser.extraArgs).doesNotContain("--first-only");
        assertThat(second.metadata.tags).doesNotContain("first-only");
    }

    @Test
    void phantomPassDockerContractKeepsConsumerListsMutable() {
        PhantomPassProfile contract = SdkProfilePresets.phantomPass(
                SdkProfilePreset.DOCKER, "editable", "Editable", "BR");

        contract.profile.browser.extraArgs.add("--disable-sync");
        contract.profile.fingerprint.formFactors.add("Desktop-Configured-By-SaaS");

        assertThat(contract.profile.browser.extraArgs).contains("--disable-sync");
        assertThat(contract.profile.fingerprint.formFactors)
                .contains("Desktop-Configured-By-SaaS");
    }

    @Test
    void effectiveManagedAndSyntheticArgumentsCannotBypassTheProfileContract() {
        ChromeOptions options = ProfileChromeOptionsFactory.fromProfile(
                SdkProfilePresets.linux("guarded", "Guarded", "BR"));

        options.addArgument("--remote-debugging-address=0.0.0.0");
        assertThatThrownBy(options::validateProfileLaunchArguments)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("managed")
                .hasMessageNotContaining("0.0.0.0");

        ChromeOptions graphics = ProfileChromeOptionsFactory.fromProfile(
                SdkProfilePresets.linux("graphics", "Graphics", "BR"));
        graphics.addArgument("--use-angle=swiftshader");
        assertThatThrownBy(graphics::applyMinimalCdpProcessArgumentPolicy)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("minimal-cdp")
                .hasMessageContaining("SwiftShader");
    }

    private static SdkProfile profile(String preset, String country) {
        return SdkProfileGenerator.fromPreset(
                new SdkProfilePresetRequest(preset + "-test", preset, country, preset));
    }

    private static void assertSafeNative(SdkProfile profile) {
        assertThat(profile.browser.headless).isFalse();
        assertThat(profile.fingerprint.runtimePolicy)
                .isEqualTo(BrowserFingerprintProfile.RUNTIME_POLICY_MINIMAL_CDP);
        assertThat(profile.fingerprint.mobile).isFalse();
        assertThat(profile.fingerprint.architecture).isEqualTo("x86");
        assertThat(profile.fingerprint.bitness).isEqualTo("64");
        assertThat(profile.fingerprint.formFactors).containsExactly("Desktop");
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
        assertThat(profile.fingerprint.webRtc.mode).isEqualTo("altered");
        assertThat(profile.fingerprint.webRtc.protectLocalIps).isTrue();
        assertThat(profile.fingerprint.webRtc.disableUdp).isTrue();
    }
}
