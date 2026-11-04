package io.github.selenium.driverless;

import io.github.selenium.javaDriverless.types.BrowserFingerprintProfile;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ExternalFingerprintProfilesTest {
    @Test
    void nativeDetectorProfileKeepsEgressButRemovesDetectableRenderingWrappers() {
        BrowserFingerprintProfile base = BrowserFingerprintProfile.brLinux();
        base.browserVersion = "150.0.7871.46";
        base.timezoneId = "America/Phoenix";
        base.geolocation.latitude = 33.4483;
        base.geolocation.longitude = -112.073;
        base.webRtc.publicIp = "20.168.93.148";
        base.webRtc.allowedIps = List.of("20.168.93.148");

        BrowserFingerprintProfile site = BrowserFingerprintProfile.brLinux();
        site.canvas.mode = "noise";
        site.audio.mode = "noise";
        site.clientRects.mode = "noise";
        site.webgl.mode = "spoof";
        site.ports.mode = "protect";

        BrowserFingerprintProfile resolved =
                ExternalFingerprintProfiles.nativeSiteProfile(base, site, "creepjs");

        assertThat(resolved.runtimePolicy).isEqualTo("minimal-cdp");
        assertThat(resolved.browserVersion).isEqualTo("150.0.7871.46");
        assertThat(resolved.timezoneId).isEqualTo("America/Phoenix");
        assertThat(resolved.geolocation.latitude).isEqualTo(33.4483);
        assertThat(resolved.webRtc.publicIp).isEqualTo("20.168.93.148");
        assertThat(resolved.webRtc.allowedIps).containsExactly("20.168.93.148");
        assertThat(resolved.canvas.mode).isEqualTo("real");
        assertThat(resolved.audio.mode).isEqualTo("real");
        assertThat(resolved.clientRects.mode).isEqualTo("real");
        assertThat(resolved.webgl.mode).isEqualTo("real");
        assertThat(resolved.storage.mode).isEqualTo("real");
        assertThat(resolved.ports.mode).isEqualTo("real");
    }

    @Test
    void proxiedIpheyAndPixelscanKeepUdpBlockedToPreventHostEgressLeak() {
        BrowserFingerprintProfile base = BrowserFingerprintProfile.brLinux();
        base.webRtc.mode = "altered";
        base.webRtc.disableUdp = true;
        base.webRtc.protectLocalIps = true;
        BrowserFingerprintProfile template = BrowserFingerprintProfile.brLinux();

        BrowserFingerprintProfile iphey =
                ExternalFingerprintProfiles.nativeSiteProfile(base, template.copy(), "iphey", true);
        BrowserFingerprintProfile pixelscan =
                ExternalFingerprintProfiles.nativeSiteProfile(base, template.copy(), "pixelscan", true);

        assertThat(iphey.webRtc.mode).isEqualTo("altered");
        assertThat(iphey.webRtc.disableUdp).isTrue();
        assertThat(iphey.webRtc.protectLocalIps).isTrue();
        assertThat(pixelscan.webRtc.mode).isEqualTo("altered");
        assertThat(pixelscan.webRtc.disableUdp).isTrue();
        assertThat(pixelscan.webRtc.protectLocalIps).isTrue();
    }

    @Test
    void siteEnvironmentNamesAreStableAndShellSafe() {
        assertThat(ExternalFingerprintProfiles.siteProfileEnvironmentName("browserleaks-ip"))
                .isEqualTo("JAVA_DRIVERLESS_EXTERNAL_FINGERPRINT_PROFILE_BROWSERLEAKS_IP");
    }
}
