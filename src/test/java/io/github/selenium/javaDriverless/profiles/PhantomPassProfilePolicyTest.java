package io.github.selenium.javaDriverless.profiles;

import io.github.selenium.javaDriverless.types.ChromeOptions;
import io.github.selenium.javaDriverless.types.EgressInfo;
import io.github.selenium.javaDriverless.types.ProxyConfig;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class PhantomPassProfilePolicyTest {
    @Test
    void acceptsAValidLinuxProfileForPersistenceAndNativeLaunchWithoutMutatingTheCaller() {
        PhantomPassProfile profile = SdkProfilePresets.phantomPass(
                SdkProfilePreset.LINUX, "policy-linux", "Policy Linux", "BR");
        String originalPreset = profile.profile.preset;

        PhantomPassPolicyResult persisted =
                PhantomPassProfilePolicy.evaluateForPersistence(profile);
        PhantomPassPolicyResult launch = PhantomPassProfilePolicy.evaluateForLaunch(
                profile, PhantomPassRuntime.NATIVE_HOST);

        assertThat(persisted.accepted()).isTrue();
        assertThat(persisted.violations()).isEmpty();
        assertThat(launch.accepted()).isTrue();
        assertThat(launch.evaluatedProfile()).isNotSameAs(profile);
        assertThat(profile.profile.preset).isEqualTo(originalPreset);
    }

    @Test
    void appliesToTheProvidedChromeOptionsSubclassWithLegacyFactoryEquivalence() {
        PhantomPassProfile profile = SdkProfilePresets.phantomPass(
                SdkProfilePreset.LINUX, "policy-apply", "Policy Apply", "BR");
        profile.profile.browser.binaryPath = "/opt/chrome/chrome";
        profile.profile.browser.extraArgs.add("--disable-sync");
        profile.profile.extensionPaths = List.of("relative-extension");

        ChromeOptions legacy = ProfileChromeOptionsFactory.fromPhantomPass(profile);
        ProductChromeOptions supplied = new ProductChromeOptions();
        ProductChromeOptions applied = ProfileChromeOptionsFactory.applyPhantomPass(
                profile, PhantomPassRuntime.NATIVE_HOST, supplied);

        assertThat(applied).isSameAs(supplied);
        assertThat(applied.getArguments()).containsExactlyElementsOf(legacy.getArguments());
        assertThat(applied.getFingerprintProfile().toJsonString())
                .isEqualTo(legacy.getFingerprintProfile().toJsonString());
        assertThat(applied.getSessionProfile().fingerprint.toJsonString())
                .isEqualTo(legacy.getSessionProfile().fingerprint.toJsonString());
        assertThat(applied.getExtensionPaths()).containsExactly(
                Path.of("relative-extension").toAbsolutePath().normalize().toString());
    }

    @Test
    void clearsTransportStateOwnedByAPreviouslyAppliedProfile() {
        PhantomPassProfile profile = SdkProfilePresets.phantomPass(
                SdkProfilePreset.LINUX, "policy-clear-transport", "Policy Clear Transport", "BR");
        ProductChromeOptions supplied = new ProductChromeOptions();
        supplied.setProxyConfig(new ProxyConfig(
                "old-proxy.invalid", 8080, ProxyConfig.ProxyType.HTTP, "old-user", "old-password"));
        supplied.enableAuthenticatedProxyBridge(true);
        EgressInfo oldEgress = new EgressInfo();
        oldEgress.publicIp = "192.0.2.10";
        supplied.setEgressInfo(oldEgress);
        supplied.setEgressResolver(proxy -> oldEgress);

        ProductChromeOptions applied = ProfileChromeOptionsFactory.applyPhantomPass(
                profile, PhantomPassRuntime.NATIVE_HOST, supplied);

        assertThat(applied.getProxyConfig()).isNull();
        assertThat(applied.isAuthenticatedProxyBridgeEnabled()).isFalse();
        assertThat(applied.getEgressInfo()).isNull();
        assertThat(applied.getEgressResolver()).isNull();
    }

    @Test
    void virtualDisplayAcceptsVariableGeometryOnlyWhenRuntimeMatchesExactly() {
        PhantomPassProfile profile = SdkProfilePresets.phantomPass(
                SdkProfilePreset.DOCKER, "policy-display", "Policy Display", "BR");
        var screen = profile.profile.fingerprint.screen;
        screen.width = 1600;
        screen.height = 1000;
        screen.availWidth = 1600;
        screen.availHeight = 940;
        screen.deviceScaleFactor = 1.25;
        PhantomPassRuntime.Geometry exact = new PhantomPassRuntime.Geometry(
                1600, 1000, 1600, 940, 24, 24, 1.25);

        assertThat(PhantomPassProfilePolicy.evaluateForPersistence(profile).accepted()).isTrue();
        assertThat(PhantomPassProfilePolicy.evaluateForLaunch(
                profile, PhantomPassRuntime.virtualDisplay(exact)).accepted()).isTrue();

        PhantomPassPolicyResult mismatch = PhantomPassProfilePolicy.evaluateForLaunch(
                profile,
                PhantomPassRuntime.virtualDisplay(new PhantomPassRuntime.Geometry(
                        1601, 1000, 1600, 940, 24, 24, 1.25)));
        assertThat(mismatch.violations()).containsExactly(new PhantomPassPolicyViolation(
                "runtime_geometry_mismatch", "fingerprint.screen", "exact virtual-display geometry"));
    }

    @Test
    void rejectsTheFourNonNativeLinuxMediaPreferencesForLinuxAndDocker() {
        for (SdkProfilePreset base : List.of(SdkProfilePreset.LINUX, SdkProfilePreset.DOCKER)) {
            assertNativeMediaViolation(base, "prefersReducedMotion", profile ->
                    profile.profile.fingerprint.prefersReducedMotion = "reduce", "no-preference");
            assertNativeMediaViolation(base, "prefersReducedTransparency", profile ->
                    profile.profile.fingerprint.prefersReducedTransparency = "reduce", "no-preference");
            assertNativeMediaViolation(base, "prefersContrast", profile ->
                    profile.profile.fingerprint.prefersContrast = "more", "no-preference");
            assertNativeMediaViolation(base, "forcedColors", profile ->
                    profile.profile.fingerprint.forcedColors = "active", "none");
        }
    }

    @Test
    void absolutePathViolationNeverEchoesThePersistedValue() {
        PhantomPassProfile profile = SdkProfilePresets.phantomPass(
                SdkProfilePreset.LINUX, "policy-path", "Policy Path", "BR");
        String sensitiveRelativePath = "relative/private-customer-token/chrome";
        profile.profile.browser.binaryPath = sensitiveRelativePath;

        PhantomPassPolicyResult result = PhantomPassProfilePolicy.evaluateForPersistence(profile);

        assertThat(result.violations()).containsExactly(new PhantomPassPolicyViolation(
                "absolute_path_required", "profile.browser.binaryPath", "absolute path"));
        assertThat(result.violations().toString()).doesNotContain(sensitiveRelativePath)
                .doesNotContain("private-customer-token");
    }

    private static void assertNativeMediaViolation(
            SdkProfilePreset base,
            String field,
            java.util.function.Consumer<PhantomPassProfile> mutation,
            String expected) {
        PhantomPassProfile profile = SdkProfilePresets.phantomPass(
                base, "policy-media-" + base.id() + "-" + field, "Policy Media", "BR");
        mutation.accept(profile);

        assertThat(PhantomPassProfilePolicy.evaluateForPersistence(profile).violations())
                .contains(new PhantomPassPolicyViolation(
                        "native_media_required", "profile.fingerprint." + field, expected));
    }

    private static final class ProductChromeOptions extends ChromeOptions {
    }
}
