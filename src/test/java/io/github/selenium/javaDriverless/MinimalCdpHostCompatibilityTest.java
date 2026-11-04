package io.github.selenium.javaDriverless;

import io.github.selenium.javaDriverless.types.BrowserFingerprintProfile;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class MinimalCdpHostCompatibilityTest {
    private static final String LINUX_UA =
            "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 Chrome/150.0.0.0 Safari/537.36";
    private static final String WINDOWS_UA =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 Chrome/150.0.0.0 Safari/537.36";
    private static final String MAC_UA =
            "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 Chrome/150.0.0.0 Safari/537.36";
    private static final String ANDROID_UA =
            "Mozilla/5.0 (Linux; Android 13; Example) AppleWebKit/537.36 Chrome/150.0.0.0 Mobile Safari/537.36";
    private static final String LINUX_32_UA =
            "Mozilla/5.0 (X11; Linux i686) AppleWebKit/537.36 Chrome/150.0.0.0 Safari/537.36";
    private static final String WINDOWS_WOW64_UA =
            "Mozilla/5.0 (Windows NT 10.0; WOW64) AppleWebKit/537.36 Chrome/150.0.0.0 Safari/537.36";

    @Test
    void linuxAndDockerHostsAcceptOnlyLinuxDesktopIdentity() {
        BrowserFingerprintProfile profile = minimalProfile("Linux", "Linux x86_64");

        assertThatCode(() -> MinimalCdpHostCompatibility.requireCompatible(
                profile, LINUX_UA, "Linux", "x86_64"))
                .doesNotThrowAnyException();
        assertThatCode(() -> MinimalCdpHostCompatibility.requireCompatible(
                profile, LINUX_UA, "Docker", "amd64"))
                .doesNotThrowAnyException();
        assertThatCode(() -> MinimalCdpHostCompatibility.requireCompatible(
                profile, LINUX_UA, "GNU/Linux inside Docker", "x64"))
                .doesNotThrowAnyException();
    }

    @Test
    void windowsHostRequiresWindowsUaAndBothProfilePlatforms() {
        BrowserFingerprintProfile profile = minimalProfile("Windows", "Win32");
        assertThatCode(() -> MinimalCdpHostCompatibility.requireCompatible(
                profile, WINDOWS_UA, "Windows 11", "amd64"))
                .doesNotThrowAnyException();

        assertMismatch(profile, LINUX_UA, "Windows 11", "amd64", "host=windows", "ua=linux");
        assertMismatch(minimalProfile("Linux", "Win32"), WINDOWS_UA, "Windows 11", "amd64",
                "uaPlatform=linux");
        assertMismatch(minimalProfile("Windows", "Linux x86_64"), WINDOWS_UA, "Windows 11", "amd64",
                "platform=linux");
    }

    @Test
    void macHostKeepsAConservativeIntelDesktopContract() {
        BrowserFingerprintProfile profile = minimalProfile("macOS", "MacIntel");
        assertThatCode(() -> MinimalCdpHostCompatibility.requireCompatible(
                profile, MAC_UA, "Mac OS X", "x86_64"))
                .doesNotThrowAnyException();
        assertThatCode(() -> MinimalCdpHostCompatibility.requireCompatible(
                profile, MAC_UA, "Darwin", "amd64"))
                .doesNotThrowAnyException();

        assertMismatch(profile, WINDOWS_UA, "Mac OS X", "x86_64", "host=mac", "ua=windows");
        assertMismatch(minimalProfile("Windows", "MacIntel"), MAC_UA, "Mac OS X", "x86_64",
                "uaPlatform=windows");
        assertMismatch(minimalProfile("macOS", "Win32"), MAC_UA, "Mac OS X", "x86_64",
                "platform=windows");
    }

    @Test
    void minimalCdpRejectsUnsupportedHostArchitectures() {
        BrowserFingerprintProfile profile = minimalProfile("Linux", "Linux x86_64");

        for (String hostArch : List.of("aarch64", "arm64", "x86", "i386", "ppc64le", "unknown")) {
            assertMismatch(profile, LINUX_UA, "Linux", hostArch,
                    "somente host desktop x86_64", "hostArch=");
        }
        assertMismatch(profile, LINUX_UA, "Linux", "",
                "somente host desktop x86_64", "hostArch=<empty>");
    }

    @Test
    void minimalCdpRejectsNonNativeArchitectureAndBitness() {
        BrowserFingerprintProfile arm = minimalProfile("Linux", "Linux x86_64");
        arm.architecture = "arm";
        assertMismatch(arm, LINUX_UA, "Linux", "amd64",
                "architecture=x86", "observado architecture=arm", "bitness=64");

        BrowserFingerprintProfile bitness32 = minimalProfile("Windows", "Win32");
        bitness32.bitness = "32";
        assertMismatch(bitness32, WINDOWS_UA, "Windows 11", "amd64",
                "architecture=x86", "observado architecture=x86", "bitness=32");
    }

    @Test
    void minimalCdpRejectsNonNativeUaArchitectureMarkers() {
        assertMismatch(minimalProfile("Linux", "Linux x86_64"), LINUX_32_UA,
                "Linux", "amd64", "User-Agent nativo linux x86_64");
        assertMismatch(minimalProfile("Windows", "Win32"), WINDOWS_WOW64_UA,
                "Windows 11", "amd64", "User-Agent nativo windows x86_64");
    }

    @Test
    void minimalCdpRequiresCanonicalNativePlatformValues() {
        assertMismatch(minimalProfile("linux", "Linux x86_64"), LINUX_UA,
                "Linux", "amd64", "esperado uaPlatform=Linux", "observado uaPlatform=linux");
        assertMismatch(minimalProfile("Linux", "Linux i686"), LINUX_UA,
                "Linux", "amd64", "esperado uaPlatform=Linux", "platform=Linux i686");
        assertMismatch(minimalProfile("windows", "Win32"), WINDOWS_UA,
                "Windows 11", "amd64", "esperado uaPlatform=Windows");
        assertMismatch(minimalProfile("Windows", "Win64"), WINDOWS_UA,
                "Windows 11", "amd64", "esperado uaPlatform=Windows", "platform=Win64");
    }

    @Test
    void minimalCdpRejectsNonDesktopMetadata() {
        BrowserFingerprintProfile mobile = minimalProfile("Linux", "Linux x86_64");
        mobile.mobile = true;
        assertMismatch(mobile, LINUX_UA, "Linux", "amd64", "Android/mobile");

        BrowserFingerprintProfile model = minimalProfile("Linux", "Linux x86_64");
        model.model = "Desktop Model";
        assertMismatch(model, LINUX_UA, "Linux", "amd64",
                "model vazio", "model=Desktop Model");

        BrowserFingerprintProfile wow64 = minimalProfile("Windows", "Win32");
        wow64.wow64 = true;
        assertMismatch(wow64, WINDOWS_UA, "Windows 11", "amd64", "wow64=false");

        BrowserFingerprintProfile mobileFactor = minimalProfile("Linux", "Linux x86_64");
        mobileFactor.formFactors = List.of("Mobile");
        assertMismatch(mobileFactor, LINUX_UA, "Linux", "amd64",
                "formFactors=[Desktop]", "formFactors=[Mobile]");

        BrowserFingerprintProfile mixedFactors = minimalProfile("Linux", "Linux x86_64");
        mixedFactors.formFactors = List.of("Desktop", "XR");
        assertMismatch(mixedFactors, LINUX_UA, "Linux", "amd64",
                "formFactors=[Desktop]", "formFactors=[Desktop, XR]");
    }

    @Test
    void minimalCdpAcceptsImplicitDesktopFormFactorBeforeProfileNormalization() {
        BrowserFingerprintProfile empty = minimalProfile("Linux", "Linux x86_64");
        empty.formFactors = List.of();
        BrowserFingerprintProfile missing = minimalProfile("Linux", "Linux x86_64");
        missing.formFactors = null;

        assertThatCode(() -> MinimalCdpHostCompatibility.requireCompatible(
                empty, LINUX_UA, "Linux", "amd64"))
                .doesNotThrowAnyException();
        assertThatCode(() -> MinimalCdpHostCompatibility.requireCompatible(
                missing, LINUX_UA, "Linux", "amd64"))
                .doesNotThrowAnyException();
    }

    @Test
    void minimalCdpLeavesPlatformVersionAsNativePassThrough() {
        BrowserFingerprintProfile blank = minimalProfile("Linux", "Linux x86_64");
        blank.platformVersion = "";
        BrowserFingerprintProfile arbitrary = minimalProfile("Linux", "Linux x86_64");
        arbitrary.platformVersion = "not-validated-by-minimal-cdp";

        assertThatCode(() -> MinimalCdpHostCompatibility.requireCompatible(
                blank, LINUX_UA, "Linux", "amd64"))
                .doesNotThrowAnyException();
        assertThatCode(() -> MinimalCdpHostCompatibility.requireCompatible(
                arbitrary, LINUX_UA, "Linux", "amd64"))
                .doesNotThrowAnyException();
    }

    @Test
    void minimalCdpRejectsCrossOsAndAndroidProfiles() {
        assertMismatch(minimalProfile("Windows", "Win32"), WINDOWS_UA, "Linux", "amd64",
                "host=linux", "ua=windows");
        assertMismatch(minimalProfile("Linux", "Linux x86_64"), LINUX_UA, "Windows 11", "amd64",
                "host=windows", "ua=linux");

        BrowserFingerprintProfile android = minimalProfile("Android", "Linux armv8l");
        android.mobile = true;
        android.model = "Example";
        assertThatThrownBy(() -> MinimalCdpHostCompatibility.requireCompatible(
                android, ANDROID_UA, "Linux", "amd64"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("minimal-cdp")
                .hasMessageContaining("Android/mobile")
                .hasMessageContaining("legacy-spoof");
    }

    @Test
    void minimalCdpRejectsUnknownHostFamily() {
        assertThatThrownBy(() -> MinimalCdpHostCompatibility.requireCompatible(
                minimalProfile("Linux", "Linux x86_64"), LINUX_UA, "Plan 9", "amd64"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("minimal-cdp")
                .hasMessageContaining("host OS desconhecido")
                .hasMessageContaining("legacy-spoof");
    }

    @Test
    void legacyPolicyBypassesNativeHostIdentityContract() {
        BrowserFingerprintProfile crossOs = minimalProfile("Windows", "Win32");
        crossOs.runtimePolicy = BrowserFingerprintProfile.RUNTIME_POLICY_LEGACY_SPOOF;
        BrowserFingerprintProfile android = minimalProfile("Android", "Linux armv8l");
        android.runtimePolicy = BrowserFingerprintProfile.RUNTIME_POLICY_LEGACY_SPOOF;
        android.mobile = true;
        android.architecture = "arm";
        android.bitness = "32";
        android.formFactors = List.of("Mobile");

        assertThatCode(() -> MinimalCdpHostCompatibility.requireCompatible(
                crossOs, WINDOWS_UA, "Linux", "arm64"))
                .doesNotThrowAnyException();
        assertThatCode(() -> MinimalCdpHostCompatibility.requireCompatible(
                android, ANDROID_UA, "Linux", "arm64"))
                .doesNotThrowAnyException();
    }

    private static BrowserFingerprintProfile minimalProfile(String uaPlatform, String platform) {
        BrowserFingerprintProfile profile = new BrowserFingerprintProfile();
        profile.runtimePolicy = BrowserFingerprintProfile.RUNTIME_POLICY_MINIMAL_CDP;
        profile.uaPlatform = uaPlatform;
        profile.platform = platform;
        profile.architecture = "x86";
        profile.bitness = "64";
        profile.mobile = false;
        profile.model = "";
        profile.wow64 = false;
        profile.formFactors = List.of("Desktop");
        return profile;
    }

    private static void assertMismatch(BrowserFingerprintProfile profile,
                                       String userAgent,
                                       String hostOs,
                                       String hostArch,
                                       String... messageParts) {
        var assertion = assertThatThrownBy(() -> MinimalCdpHostCompatibility.requireCompatible(
                profile, userAgent, hostOs, hostArch))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("minimal-cdp")
                .hasMessageContaining("legacy-spoof");
        for (String part : messageParts) {
            assertion.hasMessageContaining(part);
        }
    }
}
