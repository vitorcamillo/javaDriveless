package io.github.selenium.javaDriverless.profiles;

import io.github.selenium.javaDriverless.types.BrowserFingerprintProfile;
import io.github.selenium.javaDriverless.types.BrowserSessionProfile;
import io.github.selenium.javaDriverless.types.ChromeOptions;
import io.github.selenium.javaDriverless.types.EgressInfo;
import io.github.selenium.javaDriverless.types.ProxyConfig;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PhantomPassProfileTest {
    @Test
    void appliesTheCompleteSaasContractWithoutSharingMutableState() {
        PhantomPassProfile contract = SdkProfilePresets.phantomPass(
                SdkProfilePreset.LINUX, "account-42", "Account 42", "BR");
        contract.profile.browser.binaryPath = "/opt/chrome/chrome";
        contract.profile.browser.windowWidth = 1440;
        contract.profile.browser.windowHeight = 900;
        contract.profile.browser.extraArgs = List.of("--disable-sync");
        contract.profile.storage.userDataDir = Path.of("target", "phantompass-profile-test", "data")
                .toAbsolutePath().toString();
        contract.profile.storage.downloadsDir = Path.of("target", "phantompass-profile-test", "downloads")
                .toAbsolutePath().toString();
        contract.profile.extensionPaths = List.of("/tmp/phantompass-extension");
        contract.profile.resourcePolicy.preferences = Map.of("profile.default_content_setting_values.popups", 2);
        contract.proxy = new ProxyConfig("127.0.0.1", 8899, ProxyConfig.ProxyType.HTTP, "user", "password");
        contract.location.countryCode = "US";
        contract.location.region = "NY";
        contract.location.city = "New York";
        contract.dns.policy = "secure-doh";
        contract.dns.dohTemplate = "https://cloudflare-dns.com/dns-query";
        contract.permissions.geolocation = "deny";
        contract.permissions.notifications = "denied";
        contract.permissions.extra.put("camera", "denied");
        contract.egressInfo = new EgressInfo();
        contract.egressInfo.countryCode = "US";
        contract.egressInfo.timezone = "America/New_York";
        contract.egressInfo.publicIp = "203.0.113.42";

        ChromeOptions options = ProfileChromeOptionsFactory.fromPhantomPass(contract);

        assertThat(options.getBinaryLocation()).isEqualTo("/opt/chrome/chrome");
        assertThat(options.isHeadless()).isFalse();
        assertThat(options.getArguments()).contains(
                "--window-size=1440,900", "--disable-sync");
        assertThat(options.getExtensionPaths()).containsExactly("/tmp/phantompass-extension");
        assertThat(options.getSingleProxy()).isEqualTo("http://user:password@127.0.0.1:8899/");
        assertThat(options.isAuthenticatedProxyBridgeEnabled()).isTrue();
        assertThat(options.getFingerprintProfile().runtimePolicy)
                .isEqualTo(BrowserFingerprintProfile.RUNTIME_POLICY_MINIMAL_CDP);
        assertThat(options.getFingerprintProfile().webRtc.disableUdp).isTrue();
        assertThat(options.getSessionProfile().location.countryCode).isEqualTo("US");
        assertThat(options.getSessionProfile().location.region).isEqualTo("NY");
        assertThat(options.getSessionProfile().location.city).isEqualTo("New York");
        assertThat(options.getSessionProfile().dns.policy).isEqualTo("secure-doh");
        assertThat(options.getSessionProfile().permissions.extra).containsEntry("camera", "denied");
        assertThat(options.getFingerprintProfile().geolocation.mode).isEqualTo("prompt");
        @SuppressWarnings("unchecked")
        Map<String, Object> profilePrefs = (Map<String, Object>) options.getPrefs().get("profile");
        @SuppressWarnings("unchecked")
        Map<String, Object> contentSettings =
                (Map<String, Object>) profilePrefs.get("default_content_setting_values");
        assertThat(contentSettings).containsEntry("geolocation", 2)
                .containsEntry("notifications", 2)
                .containsEntry("media_stream_camera", 2);
        assertThat(options.getEgressInfo()).isNotSameAs(contract.egressInfo);
        assertThat(options.getEgressInfo().publicIp).isEqualTo("203.0.113.42");

        contract.profile.fingerprint.locale = "mutated";
        contract.proxy.setHost("mutated.invalid");
        contract.location.countryCode = "DE";
        contract.egressInfo.publicIp = "198.51.100.99";
        assertThat(options.getFingerprintProfile().locale).isEqualTo("en-US");
        assertThat(options.getSingleProxy()).contains("127.0.0.1");
        assertThat(options.getSessionProfile().location.countryCode).isEqualTo("US");
        assertThat(options.getEgressInfo().publicIp).isEqualTo("203.0.113.42");
    }

    @Test
    void rejectsMissingOrNonRuntimeBase() {
        PhantomPassProfile missing = new PhantomPassProfile();
        missing.profile = new SdkProfile("missing", "Missing");
        assertThatThrownBy(() -> ProfileChromeOptionsFactory.fromPhantomPass(missing))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("runtimeBase")
                .hasMessageContaining("windows")
                .hasMessageContaining("linux")
                .hasMessageContaining("docker");

        PhantomPassProfile invalid = new PhantomPassProfile();
        invalid.runtimeBase = SdkProfilePreset.SELENIUMLIKE;
        invalid.profile = new SdkProfile("invalid", "Invalid");
        assertThatThrownBy(() -> ProfileChromeOptionsFactory.fromPhantomPass(invalid))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("runtimeBase");
    }

    @Test
    void rejectsHeadlessAndCrossOsOverridesBeforeBuildingOptions() {
        PhantomPassProfile headless = SdkProfilePresets.phantomPass(
                SdkProfilePreset.LINUX, "headless", "Headless", "BR");
        headless.profile.browser.headless = true;
        assertThatThrownBy(() -> ProfileChromeOptionsFactory.fromPhantomPass(headless))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Chrome visível");

        PhantomPassProfile crossOs = SdkProfilePresets.phantomPass(
                SdkProfilePreset.LINUX, "cross-os", "Cross OS", "BR");
        crossOs.profile.fingerprint.uaPlatform = "Windows";
        crossOs.profile.fingerprint.platform = "Win32";
        assertThatThrownBy(() -> ProfileChromeOptionsFactory.fromPhantomPass(crossOs))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("runtimeBase=linux")
                .hasMessageContaining("Windows");
    }

    @Test
    void dockerContractCarriesTheValidatedXvfbSurface() {
        PhantomPassProfile contract = SdkProfilePresets.phantomPass(
                SdkProfilePreset.DOCKER, "docker-account", "Docker Account", "BR");

        assertThat(contract.profile.preset).isEqualTo("phantompass");
        assertThat(contract.runtimeBase).isEqualTo(SdkProfilePreset.DOCKER);
        assertThat(contract.profile.browser.headless).isFalse();
        assertThat(contract.profile.fingerprint.screen.mode).isEqualTo("spoof");
        assertThat(contract.profile.runtimeSurfaces).contains("xvfb-visible", "phantompass-configurable");
    }

    @Test
    void rejectsInvalidPermissionNamesAndStatesInsteadOfIgnoringThem() {
        PhantomPassProfile invalidName = SdkProfilePresets.phantomPass(
                SdkProfilePreset.LINUX, "invalid-name", "Invalid name", "BR");
        invalidName.permissions.extra.put("camera.bad.pref", "denied");
        assertThatThrownBy(() -> ProfileChromeOptionsFactory.fromPhantomPass(invalidName))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("permissions.extra")
                .hasMessageNotContaining("camera.bad.pref");

        PhantomPassProfile invalidState = SdkProfilePresets.phantomPass(
                SdkProfilePreset.LINUX, "invalid-state", "Invalid state", "BR");
        invalidState.permissions.extra.put("camera", "sometimes");
        assertThatThrownBy(() -> ProfileChromeOptionsFactory.fromPhantomPass(invalidState))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Estado de permissão inválido")
                .hasMessageContaining("camera")
                .hasMessageNotContaining("sometimes");
    }

    @Test
    void applyDefaultsRejectsMissingProfileAndInvalidRuntimeBase() {
        PhantomPassProfile semPerfil = new PhantomPassProfile();
        assertThatThrownBy(semPerfil::applyDefaults)
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("profile");

        PhantomPassProfile baseInvalida = new PhantomPassProfile();
        baseInvalida.profile = new SdkProfile("p", "P");
        baseInvalida.runtimeBase = null; // sem runtime base: falha fechado
        assertThatThrownBy(baseInvalida::applyDefaults)
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("runtimeBase");
    }

    private static PhantomPassProfile contratoManual(String id) {
        PhantomPassProfile contract = new PhantomPassProfile();
        contract.runtimeBase = SdkProfilePreset.LINUX;
        contract.profile = new SdkProfile(id, id);
        return contract;
    }

    @Test
    void applyDefaultsRebuildsMissingSessionSectionsAndReturnsThis() {
        PhantomPassProfile contract = contratoManual("defaults");
        contract.location = null;
        contract.dns = null;
        contract.permissions = null;

        assertThat(contract.applyDefaults()).isSameAs(contract);
        assertThat(contract.location).isNotNull();
        assertThat(contract.dns).isNotNull();
        assertThat(contract.permissions).isNotNull();
        assertThat(contract.permissions.extra).isEmpty();
    }

    @Test
    void copyIsIndependentEvenWithNullSections() {
        PhantomPassProfile contract = contratoManual("copy-src");
        contract.proxy = new ProxyConfig("127.0.0.1", 8899, ProxyConfig.ProxyType.HTTP, "u", "p");
        contract.permissions.extra.put("camera", "denied");
        contract.egressInfo = new EgressInfo();
        contract.egressInfo.countryCode = "US";

        PhantomPassProfile copia = contract.copy();

        assertThat(copia.profile).isNotSameAs(contract.profile);
        assertThat(copia.proxy).isNotSameAs(contract.proxy);
        assertThat(copia.permissions.extra).containsEntry("camera", "denied");
        assertThat(copia.egressInfo.countryCode).isEqualTo("US");

        PhantomPassProfile minima = new PhantomPassProfile();
        PhantomPassProfile copiaMinima = minima.copy();
        assertThat(copiaMinima.profile).isNull();
        assertThat(copiaMinima.proxy).isNull();
        assertThat(copiaMinima.egressInfo).isNull();
        assertThat(copiaMinima.permissions.extra).isEmpty();
    }
}
