package io.github.selenium.javaDriverless;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.selenium.javaDriverless.types.BrowserFingerprintProfile;
import io.github.selenium.javaDriverless.types.ChromeOptions;
import io.github.selenium.javaDriverless.profiles.PhantomPassProfile;
import io.github.selenium.javaDriverless.profiles.ProfileChromeOptionsFactory;
import io.github.selenium.javaDriverless.profiles.SdkProfilePreset;
import io.github.selenium.javaDriverless.profiles.SdkProfilePresets;
import io.github.selenium.javaDriverless.utils.UserAgentResolver;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class MinimalCdpCapabilityContractTest {

    @Test
    void phantomPassNativeDeniedPermissionStillPassesTheMinimalCapabilityGate() {
        PhantomPassProfile contract = SdkProfilePresets.phantomPass(
                SdkProfilePreset.LINUX, "permission-gate", "Permission gate", "BR");
        contract.permissions.geolocation = "deny";
        contract.permissions.notifications = "denied";
        contract.permissions.extra.put("camera", "denied");
        ChromeOptions options = ProfileChromeOptionsFactory.fromPhantomPass(contract);

        assertThatCode(() -> MinimalCdpCapabilityContract.requireSupported(
                options.getFingerprintProfile(), false, Map.of()))
                .doesNotThrowAnyException();
    }

    @Test
    void chromeStartupRejectsIgnoredCanvasModeBeforePreparingArguments() {
        BrowserFingerprintProfile profile = supportedMinimalProfile();
        profile.canvas.mode = "noise";
        ChromeOptions options = new ChromeOptions();
        List<String> argumentsBeforeValidation = List.copyOf(options.getArguments());
        ChromeFingerprintSupport support = new ChromeFingerprintSupport(
                options,
                profile,
                new ObjectMapper(),
                LoggerFactory.getLogger(MinimalCdpCapabilityContractTest.class),
                () -> UserAgentResolver.buildForProfile(profile));

        assertThatThrownBy(support::configureHeadlessFingerprintArgs)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("minimal-cdp")
                .hasMessageContaining("canvas.mode=noise")
                .hasMessageContaining("legacy-spoof");
        assertThat(options.getArguments()).containsExactlyElementsOf(argumentsBeforeValidation);
    }

    @Test
    void linuxProcessBaselineProvidesTimezoneAndDarkModeToEveryRenderer() {
        BrowserFingerprintProfile profile = supportedMinimalProfile();
        profile.timezoneId = "Asia/Tokyo";
        profile.colorScheme = "dark";
        ChromeOptions options = new ChromeOptions();

        ChromeMinimalNativeProcessBaseline.configure(options, profile, "Linux");

        assertThat(options.getEnv()).containsEntry("TZ", "Asia/Tokyo");
        assertThat(options.getArguments()).contains("--force-dark-mode");
        assertThat(ChromeMinimalNativeProcessBaseline.coversRendererNew(profile, "Linux"))
                .isTrue();
    }

    @Test
    void linuxProcessBaselineRejectsMediaThatCannotBeInheritedByNoopener() {
        BrowserFingerprintProfile profile = supportedMinimalProfile();
        profile.timezoneId = "Europe/Berlin";
        profile.prefersReducedMotion = "reduce";

        assertThatThrownBy(() -> ChromeMinimalNativeProcessBaseline.configure(
                new ChromeOptions(), profile, "Linux"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("prefersReducedMotion=reduce")
                .hasMessageContaining("legacy-spoof");
    }

    @Test
    void linuxProcessBaselinePinsTheProvisionedDeviceScaleFactor() {
        BrowserFingerprintProfile profile = supportedMinimalProfile();
        profile.timezoneId = "America/Toronto";
        profile.screen.mode = "spoof";
        profile.screen.deviceScaleFactor = 1.25;
        ChromeOptions options = new ChromeOptions();

        ChromeMinimalNativeProcessBaseline.configure(options, profile, "Linux");

        assertThat(options.getArguments()).contains("--force-device-scale-factor=1.25");
    }

    @ParameterizedTest(name = "minimal-cdp rejeita {0}")
    @MethodSource("unsupportedModes")
    void rejectsEverySurfaceModeWithoutANativePath(
            String expectedField, boolean headless, Consumer<BrowserFingerprintProfile> configureUnsupported) {
        BrowserFingerprintProfile profile = supportedMinimalProfile();
        configureUnsupported.accept(profile);

        assertThatThrownBy(() -> MinimalCdpCapabilityContract.requireSupported(profile, headless, Map.of()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("minimal-cdp")
                .hasMessageContaining(expectedField)
                .hasMessageContaining("legacy-spoof");
    }

    private static Stream<Arguments> unsupportedModes() {
        return Stream.of(
                unsupported("webgl.mode=spoof", profile -> profile.webgl.mode = "spoof"),
                unsupported("audio.mode=noise", profile -> profile.audio.mode = "noise"),
                unsupported("clientRects.mode=noise", profile -> profile.clientRects.mode = "noise"),
                unsupported("storage.mode=spoof", profile -> profile.storage.mode = "spoof"),
                unsupported("fonts.mode=auto", profile -> profile.fonts.mode = "auto"),
                unsupported("connection.mode=spoof", profile -> profile.connection.mode = "spoof"),
                unsupported("mediaDevices.mode=spoof", profile -> profile.mediaDevices.mode = "spoof"),
                unsupported("speech.mode=spoof", profile -> profile.speech.mode = "spoof"),
                unsupported("maxTouchPoints=1", profile -> profile.maxTouchPoints = 1),
                Arguments.of("screen.mode=spoof", false,
                        (Consumer<BrowserFingerprintProfile>) profile -> profile.screen.mode = "spoof"),
                unsupported("webRtc.mode=altered", profile -> profile.webRtc.disableUdp = false),
                unsupported("webRtc.mode=real", profile -> profile.webRtc.mode = "real"),
                unsupported("ports.mode=protect", profile -> profile.ports.mode = "protect"));
    }

    @Test
    void acceptsTheDocumentedVisibleCombinationAndRejectsPureHeadless() {
        BrowserFingerprintProfile headless = supportedMinimalProfile();
        headless.screen.mode = "spoof";
        headless.speech.mode = "auto";
        headless.webRtc.mode = "altered";
        headless.webRtc.disableUdp = true;

        BrowserFingerprintProfile headful = supportedMinimalProfile();
        headful.screen.mode = "real";
        headful.speech.mode = "real";
        headful.webRtc.mode = "real";
        headful.webRtc.disableUdp = false;

        assertThatThrownBy(() -> MinimalCdpCapabilityContract.requireSupported(
                headless, true, Map.of()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("--headless")
                .hasMessageContaining("HeadlessChrome")
                .hasMessageContaining("scripts/run-visible-xvfb.sh");
        assertThatCode(() -> MinimalCdpCapabilityContract.requireSupported(headful, false, Map.of()))
                .doesNotThrowAnyException();
    }

    @Test
    void headfulSpoofRequiresAnExactNativeScreenProvisioningMarker() {
        BrowserFingerprintProfile profile = supportedMinimalProfile();
        profile.screen.mode = "spoof";

        assertThatThrownBy(() -> MinimalCdpCapabilityContract.requireSupported(profile, false, Map.of()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("screen.mode=spoof")
                .hasMessageContaining("JAVA_DRIVERLESS_NATIVE_SCREEN_PROVISIONED=1");
        assertThatThrownBy(() -> MinimalCdpCapabilityContract.requireSupported(
                profile, false, Map.of("JAVA_DRIVERLESS_NATIVE_SCREEN_PROVISIONED", "true")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("JAVA_DRIVERLESS_NATIVE_SCREEN_PROVISIONED=1");

        assertThatThrownBy(() -> MinimalCdpCapabilityContract.requireSupported(
                profile, false, Map.of("JAVA_DRIVERLESS_NATIVE_SCREEN_PROVISIONED", "1")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("JAVA_DRIVERLESS_NATIVE_SCREEN_WIDTH");

        assertThatCode(() -> MinimalCdpCapabilityContract.requireSupported(
                profile, false, nativeScreenEnvironment(profile)))
                .doesNotThrowAnyException();
        assertThat(profile.screen.mode).isEqualTo("spoof");
    }

    @Test
    void headfulSpoofRejectsEveryNativeScreenHandshakeMismatch() {
        BrowserFingerprintProfile profile = supportedMinimalProfile();
        profile.screen.mode = "spoof";
        Map<String, String> exact = nativeScreenEnvironment(profile);
        Map<String, String> mismatches = Map.of(
                "JAVA_DRIVERLESS_NATIVE_SCREEN_WIDTH", "1364",
                "JAVA_DRIVERLESS_NATIVE_SCREEN_HEIGHT", "899",
                "JAVA_DRIVERLESS_NATIVE_SCREEN_AVAIL_WIDTH", "1364",
                "JAVA_DRIVERLESS_NATIVE_SCREEN_AVAIL_HEIGHT", "859",
                "JAVA_DRIVERLESS_NATIVE_SCREEN_COLOR_DEPTH", "16",
                "JAVA_DRIVERLESS_NATIVE_SCREEN_PIXEL_DEPTH", "16",
                "JAVA_DRIVERLESS_NATIVE_SCREEN_DPR", "2");

        mismatches.forEach((field, mismatch) -> {
            Map<String, String> environment = new HashMap<>(exact);
            environment.put(field, mismatch);
            assertThatThrownBy(() -> MinimalCdpCapabilityContract.requireSupported(
                    profile, false, environment))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining(field)
                    .hasMessageContaining("não corresponde ao perfil");
        });
    }

    @Test
    void rejectsMinimalIdentityFieldsWithoutANativeTransport() {
        BrowserFingerprintProfile nativeBaseline = BrowserFingerprintProfile.nativeDefaults();
        int nativeHardwareConcurrency = nativeBaseline.hardwareConcurrency;
        BrowserFingerprintProfile cores = supportedMinimalProfile();
        cores.hardwareConcurrency = nativeHardwareConcurrency == Integer.MAX_VALUE
                ? nativeHardwareConcurrency - 1 : nativeHardwareConcurrency + 1;
        assertThatThrownBy(() -> MinimalCdpCapabilityContract.requireSupported(cores, false, Map.of()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("hardwareConcurrency=" + cores.hardwareConcurrency)
                .hasMessageContaining("baseline nativo=" + nativeHardwareConcurrency)
                .hasMessageContaining("workers");

        int nativeDeviceMemory = nativeBaseline.deviceMemory;

        BrowserFingerprintProfile memory = supportedMinimalProfile();
        memory.deviceMemory = nativeDeviceMemory == Integer.MAX_VALUE
                ? nativeDeviceMemory - 1 : nativeDeviceMemory + 1;
        assertThatThrownBy(() -> MinimalCdpCapabilityContract.requireSupported(memory, false, Map.of()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("deviceMemory=" + memory.deviceMemory)
                .hasMessageContaining("baseline nativo=" + nativeDeviceMemory);

        BrowserFingerprintProfile vendor = supportedMinimalProfile();
        vendor.vendor = "Chromium Vendor";
        assertThatThrownBy(() -> MinimalCdpCapabilityContract.requireSupported(vendor, false, Map.of()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("vendor=Chromium Vendor")
                .hasMessageContaining("Google Inc.");

        BrowserFingerprintProfile doNotTrack = supportedMinimalProfile();
        doNotTrack.doNotTrack = true;
        assertThatThrownBy(() -> MinimalCdpCapabilityContract.requireSupported(
                doNotTrack, false, Map.of()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("doNotTrack=true");
    }

    @Test
    void geolocationOverrideRequiresBothCoordinatesWhileNativeModesRequireNone() {
        BrowserFingerprintProfile override = supportedMinimalProfile();
        override.geolocation.mode = "override";
        assertThatThrownBy(() -> MinimalCdpCapabilityContract.requireSupported(
                override, false, Map.of()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("geolocation.mode=override")
                .hasMessageContaining("latitude e longitude");
        override.geolocation.latitude = -23.5505;
        assertThatThrownBy(() -> MinimalCdpCapabilityContract.requireSupported(
                override, false, Map.of()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("latitude e longitude");
        override.geolocation.longitude = -46.6333;
        assertThatCode(() -> MinimalCdpCapabilityContract.requireSupported(
                override, false, Map.of())).doesNotThrowAnyException();

        for (String mode : List.of("real", "prompt")) {
            BrowserFingerprintProfile nativeLocation = supportedMinimalProfile();
            nativeLocation.geolocation.mode = mode;
            nativeLocation.geolocation.latitude = -23.5505;
            nativeLocation.geolocation.longitude = -46.6333;
            assertThatThrownBy(() -> MinimalCdpCapabilityContract.requireSupported(
                    nativeLocation, false, Map.of()))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("geolocation.mode=" + mode)
                    .hasMessageContaining("coordenadas ausentes");
        }

        BrowserFingerprintProfile unsupported = supportedMinimalProfile();
        unsupported.geolocation.mode = "deny";
        assertThatThrownBy(() -> MinimalCdpCapabilityContract.requireSupported(
                unsupported, false, Map.of()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("geolocation.mode=deny");
    }

    @Test
    void minimalHeadlessFailsBeforeConsideringAGlBackendOptIn() {
        BrowserFingerprintProfile profile = supportedMinimalProfile();

        assertThatThrownBy(() -> MinimalCdpCapabilityContract.requireSupported(
                profile, true, Map.of("JAVA_DRIVERLESS_HEADLESS_GL", "swiftshader")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("--headless")
                .hasMessageContaining("Xvfb");
    }

    @Test
    void legacyPolicyKeepsHistoricalSurfaceModesIntact() {
        BrowserFingerprintProfile legacy = supportedMinimalProfile();
        legacy.runtimePolicy = BrowserFingerprintProfile.RUNTIME_POLICY_LEGACY_SPOOF;
        legacy.webgl.mode = "spoof_noise";
        legacy.canvas.mode = "noise";
        legacy.audio.mode = "noise";
        legacy.clientRects.mode = "noise";
        legacy.storage.mode = "spoof";
        legacy.fonts.mode = "subset";
        legacy.connection.mode = "spoof";
        legacy.mediaDevices.mode = "spoof";
        legacy.speech.mode = "spoof";
        legacy.maxTouchPoints = 5;
        legacy.screen.mode = "spoof";
        legacy.webRtc.mode = "altered";
        legacy.webRtc.disableUdp = false;
        legacy.ports.mode = "protect";

        assertThatCode(() -> MinimalCdpCapabilityContract.requireSupported(legacy, false, Map.of()))
                .doesNotThrowAnyException();
    }

    @Test
    void creepJsMinimalProfileContainsOnlyNativeCapabilityModes() throws Exception {
        BrowserFingerprintProfile profile = BrowserFingerprintProfile.fromJson(
                Path.of("docs/site-profiles/linux-docker-server-creepjs-xvfb.json"));
        BrowserFingerprintProfile nativeBaseline = BrowserFingerprintProfile.nativeDefaults();
        profile.hardwareConcurrency = nativeBaseline.hardwareConcurrency;
        profile.deviceMemory = nativeBaseline.deviceMemory;

        assertThat(profile.ports.mode).isEqualTo("real");
        assertThatThrownBy(() -> MinimalCdpCapabilityContract.requireSupported(
                profile, true, Map.of()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("--headless")
                .hasMessageContaining("Xvfb");
        assertThatCode(() -> MinimalCdpCapabilityContract.requireSupported(
                profile, false, nativeScreenEnvironment(profile)))
                .doesNotThrowAnyException();
        assertThat(profile.screen.mode).isEqualTo("spoof");
    }

    @Test
    void gateRejectsNullProfileUnknownScreenModeBlankVendorAndNonNumericDpr() {
        assertThatThrownBy(() -> MinimalCdpCapabilityContract.requireSupported(
                null, false, Map.of()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("must not be null");

        BrowserFingerprintProfile screenEstranho = supportedMinimalProfile();
        screenEstranho.screen.mode = "emulated";
        assertThatThrownBy(() -> MinimalCdpCapabilityContract.requireSupported(
                screenEstranho, false, Map.of()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("screen.mode=emulated");

        BrowserFingerprintProfile vendorBranco = supportedMinimalProfile();
        vendorBranco.vendor = "  ";
        assertThatThrownBy(() -> MinimalCdpCapabilityContract.requireSupported(
                vendorBranco, false, Map.of()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("vendor=<vazio>");

        BrowserFingerprintProfile speechNula = supportedMinimalProfile();
        speechNula.speech.mode = null;
        assertThatThrownBy(() -> MinimalCdpCapabilityContract.requireSupported(
                speechNula, false, Map.of()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("speech.mode=");

        BrowserFingerprintProfile spoof = supportedMinimalProfile();
        spoof.screen.mode = "spoof";
        Map<String, String> dprInvalido = new HashMap<>(nativeScreenEnvironment(spoof));
        dprInvalido.put("JAVA_DRIVERLESS_NATIVE_SCREEN_DPR", "não-numérico");
        assertThatThrownBy(() -> MinimalCdpCapabilityContract.requireSupported(
                spoof, false, dprInvalido))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("JAVA_DRIVERLESS_NATIVE_SCREEN_DPR")
                .hasMessageContaining("não corresponde ao perfil");
    }

    private static Map<String, String> nativeScreenEnvironment(BrowserFingerprintProfile profile) {
        return Map.of(
                "JAVA_DRIVERLESS_NATIVE_SCREEN_PROVISIONED", "1",
                "JAVA_DRIVERLESS_NATIVE_SCREEN_WIDTH", Integer.toString(profile.screen.width),
                "JAVA_DRIVERLESS_NATIVE_SCREEN_HEIGHT", Integer.toString(profile.screen.height),
                "JAVA_DRIVERLESS_NATIVE_SCREEN_AVAIL_WIDTH", Integer.toString(profile.screen.availWidth),
                "JAVA_DRIVERLESS_NATIVE_SCREEN_AVAIL_HEIGHT", Integer.toString(profile.screen.availHeight),
                "JAVA_DRIVERLESS_NATIVE_SCREEN_COLOR_DEPTH", Integer.toString(profile.screen.colorDepth),
                "JAVA_DRIVERLESS_NATIVE_SCREEN_PIXEL_DEPTH", Integer.toString(profile.screen.pixelDepth),
                "JAVA_DRIVERLESS_NATIVE_SCREEN_DPR", Double.toString(profile.screen.deviceScaleFactor));
    }

    private static Arguments unsupported(
            String expectedField, Consumer<BrowserFingerprintProfile> configureUnsupported) {
        return Arguments.of(expectedField, false, configureUnsupported);
    }

    private static BrowserFingerprintProfile supportedMinimalProfile() {
        BrowserFingerprintProfile profile = BrowserFingerprintProfile.nativeDefaults();
        profile.runtimePolicy = BrowserFingerprintProfile.RUNTIME_POLICY_MINIMAL_CDP;
        profile.webgl.mode = "real";
        profile.webRtc.mode = "altered";
        profile.webRtc.disableUdp = true;
        profile.mediaDevices.mode = "real";
        profile.speech.mode = "auto";
        profile.audio.mode = "real";
        profile.canvas.mode = "real";
        profile.clientRects.mode = "real";
        profile.storage.mode = "real";
        profile.fonts.mode = "real";
        profile.ports.mode = "real";
        profile.connection.mode = "real";
        profile.maxTouchPoints = 0;
        return profile;
    }
}
