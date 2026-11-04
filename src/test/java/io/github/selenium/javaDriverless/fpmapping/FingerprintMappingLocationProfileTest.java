package io.github.selenium.javaDriverless.fpmapping;

import io.github.selenium.javaDriverless.types.BrowserFingerprintProfile;
import io.github.selenium.javaDriverless.types.ChromeOptions;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FingerprintMappingLocationProfileTest {
    @Test
    void countryPresetAppliesCoherentLocationProfile() {
        ChromeOptions options = new ChromeOptions();
        FingerprintMappingCatalog.Args args = FingerprintMappingCatalog.Args.parse(new String[]{
                "java-driverless", "--profile-country", "US"
        });

        FingerprintMappingLocationProfile.apply(options, args);

        BrowserFingerprintProfile profile = options.getFingerprintProfile();
        assertEquals("en-US", profile.locale);
        assertEquals("America/New_York", profile.timezoneId);
        assertEquals(java.util.List.of("en-US", "en"), profile.languages);
        assertEquals(40.7128, profile.geolocation.latitude);
        assertEquals(-74.0060, profile.geolocation.longitude);
        assertEquals("en_US.UTF-8", options.getEnv().get("LANG"));
        assertEquals("en-US:en", options.getEnv().get("LANGUAGE"));
        assertEquals(true, options.getArguments().contains("--lang=en-US"));
        assertEquals(true, options.getArguments().contains("--accept-lang=en-US,en"));
    }

    @Test
    void explicitValuesOverrideCountryPreset() {
        ChromeOptions options = new ChromeOptions();
        FingerprintMappingCatalog.Args args = FingerprintMappingCatalog.Args.parse(new String[]{
                "java-driverless", "--profile-country", "US", "--timezone", "America/Chicago",
                "--locale", "en-US", "--languages", "en-US,en,es", "--geo", "41.8781,-87.6298"
        });

        FingerprintMappingLocationProfile.apply(options, args);

        BrowserFingerprintProfile profile = options.getFingerprintProfile();
        assertEquals("America/Chicago", profile.timezoneId);
        assertEquals(java.util.List.of("en-US", "en", "es"), profile.languages);
        assertEquals(41.8781, profile.geolocation.latitude);
        assertEquals(-87.6298, profile.geolocation.longitude);
    }

    @Test
    void fingerprintProfileArgumentIsParsedForSiteSpecificSurfaceToggles() {
        FingerprintMappingCatalog.Args args = FingerprintMappingCatalog.Args.parse(new String[]{
                "java-driverless", "--fingerprint-profile", "docs/site-profiles/selenium-like.json"
        });

        assertEquals(Path.of("docs/site-profiles/selenium-like.json"), args.fingerprintProfile());
    }

    @Test
    void parsesPerSiteFingerprintProfilesAndAllSitesAlias() {
        FingerprintMappingCatalog.Args args = FingerprintMappingCatalog.Args.parse(new String[]{
                "java-driverless",
                "--sites", "all",
                "--site-fingerprint-profile",
                "iphey=docs/site-profiles/linux-docker-server-iphey-webrtc-real.json,creepjs=docs/site-profiles/linux-docker-server.json"
        });

        assertTrue(args.sites().isEmpty());
        assertEquals(Path.of("docs/site-profiles/linux-docker-server-iphey-webrtc-real.json"),
                args.siteFingerprintProfiles().get("iphey"));
        assertEquals(Path.of("docs/site-profiles/linux-docker-server.json"),
                args.siteFingerprintProfiles().get("creepjs"));
    }

    @Test
    void repeatedHeadlessEnableDoesNotDuplicateChromeArgument() {
        ChromeOptions options = new ChromeOptions();

        options.setHeadless(true);
        options.setHeadless(true);

        assertEquals(1, options.getArguments().stream()
                .filter("--headless=new"::equals)
                .count());
    }

    @Test
    void dockerServerFingerprintProfileIsReadyForHeadlessContainers() throws Exception {
        Path profilePath = Path.of("docs/site-profiles/linux-docker-server.json");

        assertTrue(Files.exists(profilePath));
        BrowserFingerprintProfile profile = BrowserFingerprintProfile.fromJson(profilePath);

        assertEquals("linux-docker-server", profile.name);
        assertEquals("", profile.browserVersion);
        assertEquals("en-US", profile.locale);
        assertEquals("America/Sao_Paulo", profile.timezoneId);
        assertEquals(java.util.List.of("en-US", "en"), profile.languages);
        assertEquals("spoof", profile.screen.mode);
        assertEquals(1365, profile.screen.width);
        assertEquals(900, profile.screen.height);
        assertEquals(860, profile.screen.availHeight);
        assertEquals("spoof", profile.webgl.mode);
        assertTrue(profile.webRtc.protectLocalIps);
        assertTrue(profile.webRtc.disableUdp);
        assertEquals("override", profile.geolocation.mode);
        assertEquals(-23.1857, profile.geolocation.latitude);
        assertEquals(-46.8978, profile.geolocation.longitude);
        assertEquals(100.0, profile.geolocation.accuracy);
        assertFalse(profile.ports.protectedPorts.isEmpty());
    }
}
