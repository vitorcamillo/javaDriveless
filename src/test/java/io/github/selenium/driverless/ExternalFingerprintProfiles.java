package io.github.selenium.driverless;

import io.github.selenium.javaDriverless.types.BrowserFingerprintProfile;
import io.github.selenium.javaDriverless.utils.UserAgentResolver;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** Resolves the runtime and site-specific profiles used by the external detector gate. */
final class ExternalFingerprintProfiles {
    private static final Path DEFAULT_PROFILE =
            Path.of("docs/site-profiles/linux-docker-server.json");
    private static final Map<String, Path> NATIVE_SITE_PROFILES = Map.of(
            "browsercheck", Path.of("docs/site-profiles/linux-docker-server-creepjs-xvfb.json"),
            "creepjs", Path.of("docs/site-profiles/linux-docker-server-creepjs-xvfb.json"),
            "iphey", Path.of("docs/site-profiles/linux-docker-server-iphey-webrtc-real.json"),
            "pixelscan", Path.of("docs/site-profiles/linux-docker-server-pixelscan-native-rendering.json"));

    private ExternalFingerprintProfiles() {
    }

    static BrowserFingerprintProfile loadBase(String propertyValue, String environmentValue) {
        String configured = firstNonBlank(propertyValue, environmentValue);
        return load(configured == null ? DEFAULT_PROFILE : Path.of(configured));
    }

    static BrowserFingerprintProfile forSite(String id, BrowserFingerprintProfile base) {
        String property = System.getProperty("java.driverless.externalFingerprintProfile." + id);
        String environment = System.getenv(siteProfileEnvironmentName(id));
        String configured = firstNonBlank(property, environment);
        Path template = configured == null ? NATIVE_SITE_PROFILES.get(id) : Path.of(configured);
        boolean proxied = firstNonBlank(
                System.getProperty("java.driverless.externalProxy"),
                System.getenv("JAVA_DRIVERLESS_EXTERNAL_PROXY")) != null;
        return template == null ? base.copy() : nativeSiteProfile(base, load(template), id, proxied);
    }

    static BrowserFingerprintProfile nativeSiteProfile(
            BrowserFingerprintProfile base,
            BrowserFingerprintProfile site,
            String id) {
        return nativeSiteProfile(base, site, id, false);
    }

    static BrowserFingerprintProfile nativeSiteProfile(
            BrowserFingerprintProfile base,
            BrowserFingerprintProfile site,
            String id,
            boolean proxied) {
        BrowserFingerprintProfile nativeBaseline = BrowserFingerprintProfile.nativeDefaults();
        site.runtimePolicy = BrowserFingerprintProfile.RUNTIME_POLICY_MINIMAL_CDP;
        site.browserVersion = base.browserVersion;
        site.locale = base.locale;
        site.timezoneId = base.timezoneId;
        site.languages = List.copyOf(base.languages);
        site.hardwareConcurrency = nativeBaseline.hardwareConcurrency;
        site.deviceMemory = nativeBaseline.deviceMemory;
        site.vendor = nativeBaseline.vendor;
        site.doNotTrack = nativeBaseline.doNotTrack;

        copyLocation(base, site);
        copyEgress(base, site);

        site.webgl.mode = "real";
        site.mediaDevices.mode = "real";
        site.speech.mode = "real";
        site.audio.mode = "real";
        site.audio.noise = 0;
        site.canvas.mode = "real";
        site.canvas.noise = 0;
        site.clientRects.mode = "real";
        site.clientRects.noise = 0;
        site.storage.mode = "real";
        site.fonts.mode = "real";
        site.ports.mode = "real";
        site.connection.mode = "real";

        if (("iphey".equals(id) || "pixelscan".equals(id)) && !proxied) {
            site.webRtc.mode = "real";
            site.webRtc.protectLocalIps = false;
            site.webRtc.disableUdp = false;
        } else if (proxied) {
            site.webRtc.mode = base.webRtc.mode;
            site.webRtc.protectLocalIps = base.webRtc.protectLocalIps;
            site.webRtc.disableUdp = base.webRtc.disableUdp;
        }
        site.applyDefaults();
        return site;
    }

    static String siteProfileEnvironmentName(String id) {
        return "JAVA_DRIVERLESS_EXTERNAL_FINGERPRINT_PROFILE_"
                + id.toUpperCase(Locale.ROOT).replaceAll("[^A-Z0-9]", "_");
    }

    private static BrowserFingerprintProfile load(Path path) {
        try {
            BrowserFingerprintProfile profile = BrowserFingerprintProfile.fromJson(path);
            UserAgentResolver.ensureBrowserVersion(profile);
            return profile;
        } catch (IOException e) {
            throw new IllegalStateException("Could not load external fingerprint profile " + path, e);
        }
    }

    private static void copyLocation(BrowserFingerprintProfile base, BrowserFingerprintProfile site) {
        site.geolocation.mode = base.geolocation.mode;
        site.geolocation.latitude = base.geolocation.latitude;
        site.geolocation.longitude = base.geolocation.longitude;
        site.geolocation.accuracy = base.geolocation.accuracy;
        site.permissions.geolocation = base.permissions.geolocation;
    }

    private static void copyEgress(BrowserFingerprintProfile base, BrowserFingerprintProfile site) {
        site.webRtc.publicIp = base.webRtc.publicIp;
        site.webRtc.allowedIps = base.webRtc.allowedIps == null
                ? List.of() : List.copyOf(base.webRtc.allowedIps);
    }

    private static String firstNonBlank(String first, String second) {
        if (first != null && !first.isBlank()) {
            return first.trim();
        }
        return second == null || second.isBlank() ? null : second.trim();
    }
}
