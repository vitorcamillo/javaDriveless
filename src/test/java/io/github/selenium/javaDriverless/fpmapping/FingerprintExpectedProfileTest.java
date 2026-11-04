package io.github.selenium.javaDriverless.fpmapping;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.selenium.javaDriverless.types.BrowserFingerprintProfile;
import io.github.selenium.javaDriverless.types.FingerprintProfileExpectedValues;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FingerprintExpectedProfileTest {
    private static final ObjectMapper JSON = new ObjectMapper();

    @TempDir
    Path tempDir;

    @Test
    void loadUnwrapsThePersistableValuesObject() throws Exception {
        Path expectedProfile = tempDir.resolve("expected-profile.json");
        JSON.writeValue(expectedProfile.toFile(), Map.of(
                "format", "flat-key-value",
                "values", Map.of(
                        "webRtc.publicIp", "203.0.113.7",
                        "run.timezone", "Europe/London")));
        FingerprintMappingCatalog.Args args = FingerprintMappingCatalog.Args.parse(new String[]{
                "java-driverless", "--expected-profile", expectedProfile.toString()
        });

        Map<String, String> expected = FingerprintExpectedProfile.load(args);

        assertEquals("203.0.113.7", expected.get("webRtc.publicIp"));
        assertEquals("Europe/London", expected.get("run.timezone"));
        assertFalse(expected.containsKey("values.webRtc.publicIp"));
        assertFalse(expected.containsKey("format"));
    }

    @Test
    void activeFingerprintDerivesTheCompleteCrossSiteOracleWithoutOverridingConfiguredValues() {
        BrowserFingerprintProfile profile = BrowserFingerprintProfile.brLinux();
        profile.browserVersion = "150.0.7871.46";
        profile.locale = "en-US";
        profile.timezoneId = "America/New_York";
        profile.languages = java.util.List.of("en-US", "en");
        profile.screen.width = 1365;
        profile.screen.height = 900;
        profile.screen.availWidth = 1365;
        profile.screen.availHeight = 860;
        profile.webRtc.publicIp = "203.0.113.7";
        profile.webRtc.allowedIps = java.util.List.of("203.0.113.7");

        Map<String, String> derived = FingerprintProfileExpectedValues.derive(profile);
        Map<String, String> merged = FingerprintMappingCapture.expectedProfileForActiveProfile(
                Map.of("locale", "configured-locale"), profile);

        assertEquals("150", derived.get("browserMajor"));
        assertEquals("150.0.7871.46", derived.get("browserVersion"));
        assertTrue(derived.get("userAgent").contains("Chrome/150.0.0.0"));
        assertEquals("en-US,en;q=0.9", derived.get("http.acceptLanguage"));
        assertEquals("America/New_York", derived.get("run.timezone"));
        assertEquals("1365x900", derived.get("screen.resolution"));
        assertEquals("203.0.113.7", derived.get("webRtc.publicIp"));
        assertEquals(profile.webgl.unmaskedRenderer, derived.get("webgl.unmaskedRenderer"));
        assertEquals(profile.webgl.unmaskedVendor, derived.get("creepjs.webgl.gpuVendor"));
        assertEquals(profile.webgl.unmaskedRenderer, derived.get("creepjs.webgl.gpuRenderer"));
        assertEquals(profile.webgl.unmaskedVendor,
                derived.get("pixelscan.hardware.WebGL Vendor"));
        assertEquals("WebGL 1.0", derived.get("pixelscan.hardware.WebGL Version"));
        assertEquals(profile.webgl.unmaskedRenderer,
                derived.get("pixelscan.hardware.WebGL Renderer"));
        assertEquals("configured-locale", merged.get("locale"));
        assertEquals("150", merged.get("browserMajor"));
        assertEquals("203.0.113.7", merged.get("webRtc.publicIp"));
        assertEquals("SAFE", merged.get("browserscan.webrtc.Status"));
        assertEquals("SAFE", merged.get("browserscan.dns.Status"));
        assertEquals("Found\\s+[1-9]\\d*\\s+Servers?",
                merged.get("browserleaks-dns.testResultsPattern"));
        assertEquals("^[1-9]\\d*$", merged.get("browserleaks-dns.positiveCountPattern"));
        assertEquals("matched", merged.get("browserleaks-dns.countConsistency"));
        assertEquals("^0\\s+hours?\\b", merged.get("trustmyip-timezone.offsetPattern"));
        assertEquals("(?i)^MATCH\\s*[-—–]\\s*No\\s+Dissonance$",
                merged.get("trustmyip-timezone.resultPattern"));
        assertEquals("Your Digital Identity Looks Trustworthy", merged.get("iphey.verdict"));
        assertEquals("^(?:true|false)$", merged.get("iphey.bot.booleanPattern"));
        assertNull(merged.get("iphey.bot.hasCDP"));
        assertEquals("false", merged.get("iphey.bot.hasWebdriver"));
        assertNull(merged.get("iphey.bot.hasUserAgent"));
        assertNull(merged.get("iphey.bot.hasNavigator"));
        assertEquals("Good (Authentic)", merged.get("browsercheck.realness"));
        assertEquals("No", merged.get("browsercheck.proxyDetected"));
        assertEquals("No", merged.get("browsercheck.automationDetected"));
    }

    @Test
    void persistedCaptureOracleContainsValuesDerivedFromTheActiveProfile() throws Exception {
        BrowserFingerprintProfile profile = BrowserFingerprintProfile.brLinux();
        profile.timezoneId = "America/New_York";
        profile.locale = "en-US";
        Path artifact = tempDir.resolve("active-expected-profile.json");

        Map<String, String> expected = FingerprintMappingCapture.persistActiveExpectedProfile(
                artifact, Map.of(), profile);
        @SuppressWarnings("unchecked")
        Map<String, Object> persisted = JSON.readValue(artifact.toFile(), Map.class);
        @SuppressWarnings("unchecked")
        Map<String, String> values = (Map<String, String>) persisted.get("values");

        assertEquals("America/New_York", expected.get("run.timezone"));
        assertEquals("America/New_York", values.get("run.timezone"));
        assertEquals(expected.get("browserVersion"), values.get("browserVersion"));
        assertFalse(values.isEmpty());
    }

    @Test
    void realWebglModeDoesNotPretendTheConfiguredGpuIsAnObservedValue() {
        BrowserFingerprintProfile profile = BrowserFingerprintProfile.brLinux();
        profile.webgl.mode = "real";
        profile.webgl.vendor = "configured-but-not-enforced";
        profile.webgl.renderer = "configured-but-not-enforced";
        profile.webgl.unmaskedVendor = "configured-but-not-enforced";
        profile.webgl.unmaskedRenderer = "configured-but-not-enforced";

        Map<String, String> derived = FingerprintProfileExpectedValues.derive(profile);

        assertFalse(derived.containsKey("browserleaks-webgl.renderer"));
        assertFalse(derived.containsKey("webgl-report.vendor"));
        assertFalse(derived.containsKey("webgl-report.renderer"));
        assertFalse(derived.containsKey("creepjs.webgl.gpuVendor"));
        assertFalse(derived.containsKey("creepjs.webgl.gpuRenderer"));
        assertFalse(derived.containsKey("creepjs.worker.gpuVendor"));
        assertFalse(derived.containsKey("creepjs.worker.gpuRenderer"));
        assertFalse(derived.containsKey("webgl.unmaskedRenderer"));
        assertFalse(derived.containsKey("pixelscan.hardware.WebGL Vendor"));
        assertFalse(derived.containsKey("pixelscan.hardware.WebGL Version"));
        assertFalse(derived.containsKey("pixelscan.hardware.WebGL Renderer"));
    }

    @Test
    void minimalCdpDoesNotInventNativeChromeGreaseBrands() {
        BrowserFingerprintProfile profile = BrowserFingerprintProfile.brLinux();
        profile.runtimePolicy = BrowserFingerprintProfile.RUNTIME_POLICY_MINIMAL_CDP;
        profile.browserVersion = "150.0.7871.114";
        profile.platformVersion = "6.99.123-synthetic";

        Map<String, String> derived = FingerprintProfileExpectedValues.derive(profile);

        assertEquals("150.0.7871.114", derived.get("browserVersion"));
        assertFalse(derived.containsKey("platformVersion"));
        for (String key : java.util.List.of(
                "clientHints.brands", "clientHints.fullVersionList",
                "clientHints.uaHeader", "clientHints.fullVersionListHeader")) {
            assertFalse(derived.containsKey(key), key);
        }
    }

    @Test
    void legacySpoofKeepsConfiguredPlatformVersionInTheOracle() {
        BrowserFingerprintProfile profile = BrowserFingerprintProfile.usWindows11();
        profile.runtimePolicy = BrowserFingerprintProfile.RUNTIME_POLICY_LEGACY_SPOOF;
        profile.platformVersion = "15.0.0";

        Map<String, String> derived = FingerprintProfileExpectedValues.derive(profile);

        assertEquals("15.0.0", derived.get("platformVersion"));
    }

    @Test
    void realScreenModeDoesNotPretendConfiguredDimensionsAreObserved() {
        BrowserFingerprintProfile profile = BrowserFingerprintProfile.brLinux();
        profile.screen.mode = "real";
        profile.screen.width = 1111;
        profile.screen.height = 777;
        profile.screen.availWidth = 1099;
        profile.screen.availHeight = 733;
        profile.screen.deviceScaleFactor = 1.25;

        Map<String, String> derived = FingerprintProfileExpectedValues.derive(profile);

        for (String key : java.util.List.of(
                "screen.resolution", "viewport.width", "viewport.height", "deviceScaleFactor",
                "pixelscan.screen.Resolution", "pixelscan.screen.Available screen size")) {
            assertFalse(derived.containsKey(key), key);
        }
    }

    @Test
    void spoofScreenModeDerivesOnlyDimensionsGuaranteedByTheProfile() {
        BrowserFingerprintProfile profile = BrowserFingerprintProfile.brLinux();
        profile.screen.mode = "spoof";
        profile.screen.width = 1365;
        profile.screen.height = 900;
        profile.screen.availWidth = 1365;
        profile.screen.availHeight = 860;
        profile.screen.deviceScaleFactor = 1.25;

        Map<String, String> derived = FingerprintProfileExpectedValues.derive(profile);

        assertEquals("1365x900", derived.get("screen.resolution"));
        assertEquals("1365", derived.get("viewport.width"));
        assertFalse(derived.containsKey("viewport.height"));
        assertEquals("1.25", derived.get("deviceScaleFactor"));
        assertEquals("1365x900", derived.get("pixelscan.screen.Resolution"));
        assertEquals("1365x860", derived.get("pixelscan.screen.Available screen size"));
    }

    @Test
    void realConnectionModeDoesNotPretendConfiguredNetworkHintsAreObserved() {
        BrowserFingerprintProfile profile = BrowserFingerprintProfile.brLinux();
        profile.connection.mode = "real";
        profile.connection.rtt = 777;
        profile.connection.downlink = 0.25;
        profile.connection.effectiveType = "slow-2g";

        Map<String, String> derived = FingerprintProfileExpectedValues.derive(profile);

        assertFalse(derived.containsKey("connection.rtt"));
        assertFalse(derived.containsKey("connection.downlink"));
        assertFalse(derived.containsKey("connection.effectiveType"));
    }

    @Test
    void spoofConnectionModeDerivesConfiguredNetworkHints() {
        BrowserFingerprintProfile profile = BrowserFingerprintProfile.brLinux();
        profile.connection.mode = "spoof";
        profile.connection.rtt = 75;
        profile.connection.downlink = 5.5;
        profile.connection.effectiveType = "3g";

        Map<String, String> derived = FingerprintProfileExpectedValues.derive(profile);

        assertEquals("75", derived.get("connection.rtt"));
        assertEquals("5.5", derived.get("connection.downlink"));
        assertEquals("3g", derived.get("connection.effectiveType"));
    }

    @Test
    void onlyGeolocationOverrideDerivesConfiguredCoordinates() {
        for (String passThroughMode : java.util.List.of("real", "prompt")) {
            BrowserFingerprintProfile profile = BrowserFingerprintProfile.brLinux();
            profile.geolocation.mode = passThroughMode;
            profile.geolocation.latitude = 1.25;
            profile.geolocation.longitude = -2.5;

            Map<String, String> derived = FingerprintProfileExpectedValues.derive(profile);

            assertFalse(derived.containsKey("geolocation.latitude"), passThroughMode);
            assertFalse(derived.containsKey("geolocation.longitude"), passThroughMode);
            assertFalse(derived.containsKey("pixelscan.location.Latitude"), passThroughMode);
            assertFalse(derived.containsKey("pixelscan.location.Longitude"), passThroughMode);
        }

        BrowserFingerprintProfile overridden = BrowserFingerprintProfile.brLinux();
        overridden.geolocation.mode = "override";
        overridden.geolocation.latitude = 1.25;
        overridden.geolocation.longitude = -2.5;
        Map<String, String> derived = FingerprintProfileExpectedValues.derive(overridden);

        assertEquals("1.25", derived.get("geolocation.latitude"));
        assertEquals("-2.5", derived.get("geolocation.longitude"));
        assertEquals("1.25", derived.get("pixelscan.location.Latitude"));
        assertEquals("-2.5", derived.get("pixelscan.location.Longitude"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void optionalNumericDistanceSkipsOnlyWhenTheProfileDoesNotConfigureCoordinates() {
        FingerprintSiteContracts.Site contract = new FingerprintSiteContracts.Site();
        FingerprintSiteContracts.ExpectedValue latitude = new FingerprintSiteContracts.ExpectedValue();
        latitude.key = "site.latitude";
        latitude.expectedKey = "geolocation.latitude";
        latitude.policy = "withinDistanceIfConfigured";
        latitude.distance = 0.25;
        FingerprintSiteContracts.ExpectedValue longitude = new FingerprintSiteContracts.ExpectedValue();
        longitude.key = "site.longitude";
        longitude.expectedKey = "geolocation.longitude";
        longitude.policy = "withinToleranceIfConfigured";
        longitude.tolerance = 0.5;
        contract.expectedValues = java.util.List.of(latitude, longitude);

        Map<String, Object> skipped = FingerprintMappingExpectations.validateExpectedProfile(
                "site", Map.of("latitude", "40.7", "longitude", "-74.1"), Map.of(), contract);
        assertEquals(java.util.List.of(), skipped.get("missing"));
        assertEquals(java.util.List.of(), skipped.get("mismatches"));
        assertEquals(java.util.List.of(
                "site.latitude source=geolocation.latitude reason=expected-not-configured",
                "site.longitude source=geolocation.longitude reason=expected-not-configured"),
                skipped.get("skipped"));

        Map<String, Object> matched = FingerprintMappingExpectations.validateExpectedProfile(
                "site", Map.of("latitude", "40.7", "longitude", "-74.1"),
                Map.of("geolocation.latitude", "40.8", "geolocation.longitude", "-74.4"), contract);
        assertEquals(Map.of("site.latitude", "40.7", "site.longitude", "-74.1"),
                matched.get("matched"));

        Map<String, Object> mismatch = FingerprintMappingExpectations.validateExpectedProfile(
                "site", Map.of("latitude", "41.2", "longitude", "-75.1"),
                Map.of("geolocation.latitude", "40.8", "geolocation.longitude", "-74.4"), contract);
        assertEquals(2, ((java.util.List<String>) mismatch.get("mismatches")).size());
    }

    @Test
    void siteContractsMakePassThroughSurfaceValuesOptionalWithoutWeakeningSpoofs() {
        assertEquals("equalsIfConfigured",
                expected("pixelscan", "pixelscan.screen.Resolution").policy);
        assertEquals("containsIfConfigured",
                expected("pixelscan", "pixelscan.hardware.WebGL Vendor").policy);
        assertEquals("equalsIfConfigured",
                expected("iphey", "iphey.hardware.GPU").policy);
        assertEquals("equalsIfConfigured",
                expected("browserleaks-webgl", "browserleaks-webgl.renderer").policy);
        assertEquals("equalsIfConfigured",
                expected("webgl-report", "webgl-report.vendor").policy);
        assertEquals("equalsIfConfigured",
                expected("webgl-report", "webgl-report.renderer").policy);
        assertEquals("withinDistanceIfConfigured",
                expected("pixelscan", "pixelscan.location.Latitude").policy);
        assertEquals("withinToleranceIfConfigured",
                expected("iphey", "iphey.location.Latitude").policy);
        assertEquals("equalsIfConfigured",
                expected("browserleaks-client-hints",
                        "browserleaks-client-hints.headers.RTT").policy);
        assertEquals("equalsIfConfigured",
                expected("browserleaks-javascript",
                        "browserleaks-javascript.clientHints.brands").policy);
        assertEquals("equalsIfConfigured",
                expected("browserleaks-javascript",
                        "browserleaks-javascript.clientHints.fullVersionList").policy);
        assertEquals("equalsIfConfigured",
                expected("browserleaks-javascript",
                        "browserleaks-javascript.clientHints.platformVersion").policy);
        assertEquals("equalsIfConfigured",
                expected("browserleaks-client-hints",
                        "browserleaks-client-hints.javascript.platformVersion").policy);
        assertEquals("equalsIfConfigured",
                expected("browserleaks-client-hints",
                        "browserleaks-client-hints.headers.Sec-CH-UA-Platform-Version").policy);
        assertEquals("present",
                expected("browserleaks-client-hints",
                        "browserleaks-client-hints.headers.Sec-CH-Viewport-Height").policy);
    }

    @Test
    void proxyCredentialsAreNotAddedToExpectedValues() {
        FingerprintMappingCatalog.Args args = FingerprintMappingCatalog.Args.parse(new String[]{
                "java-driverless", "--proxy", "http://capture-user:capture-password@127.0.0.1:8080"
        });

        Map<String, String> expected = FingerprintExpectedProfile.load(args);

        assertFalse(expected.containsKey("run.proxy"));
        assertFalse(expected.values().stream().anyMatch(value -> value.contains("capture-password")));
    }

    @Test
    void persistenceDropsSensitiveRunMetadataAlreadyPresentInInput() throws Exception {
        Map<String, String> expected = new LinkedHashMap<>();
        expected.put("browser.userAgent", "expected-user-agent");
        expected.put("run.proxy", "http://capture-user:capture-password@127.0.0.1:8080");
        expected.put("run.authorization", "Bearer capture-token");
        expected.put("proxy.password", "capture-password-two");
        expected.put("custom.endpoint", "socks5://user:password@example.invalid:1080");
        expected.put("proxy.credentials", "opaque-proxy-credential");
        expected.put("run.proxyPassword", "opaque-proxy-password");
        expected.put("sessionCookie", "opaque-session-cookie");
        expected.put("privateKey", "opaque-private-key");
        expected.put("custom.userinfo", "https://capture-user@example.invalid/path");
        expected.put("trustmyip-proxy.result", "No Proxy or VPN Detected");

        Map<String, Object> persisted = FingerprintExpectedProfile.persistable(expected);
        String json = JSON.writeValueAsString(persisted);

        assertTrue(json.contains("expected-user-agent"));
        assertFalse(json.contains("capture-user"));
        assertFalse(json.contains("capture-password"));
        assertFalse(json.contains("capture-token"));
        assertFalse(json.contains("capture-password-two"));
        assertFalse(json.contains("user:password"));
        assertFalse(json.contains("opaque-proxy-credential"));
        assertFalse(json.contains("opaque-proxy-password"));
        assertFalse(json.contains("opaque-session-cookie"));
        assertFalse(json.contains("opaque-private-key"));
        assertFalse(json.contains("capture-user"));
        assertTrue(json.contains("trustmyip-proxy.result"));
    }

    @Test
    void explicitMissingExpectedProfileFailsClosed() {
        FingerprintMappingCatalog.Args args = FingerprintMappingCatalog.Args.parse(new String[]{
                "java-driverless", "--expected-profile", tempDir.resolve("missing.json").toString()
        });

        org.junit.jupiter.api.Assertions.assertThrows(
                IllegalArgumentException.class, () -> FingerprintExpectedProfile.load(args));
    }

    @Test
    void siteSpecificRootOracleIsAnExplicitRedactedIndex() throws Exception {
        Path artifact = tempDir.resolve("expected-profile.json");
        FingerprintMappingCapture.persistSiteExpectedProfileIndex(artifact, Map.of(
                "site-a", Map.of("run.timezone", "Europe/London", "proxy.password", "secret-a"),
                "site-b", Map.of("run.timezone", "Asia/Tokyo")));

        @SuppressWarnings("unchecked")
        Map<String, Object> persisted = JSON.readValue(artifact.toFile(), Map.class);
        assertEquals("site-profile-index", persisted.get("format"));
        assertTrue(((Map<?, ?>) persisted.get("sites")).containsKey("site-a"));
        assertFalse(JSON.writeValueAsString(persisted).contains("secret-a"));
    }

    @Test
    void siteSpecificRootIndexCannotBeSilentlyLoadedAsAFlatOracle() throws Exception {
        Path artifact = tempDir.resolve("expected-profile-index.json");
        FingerprintMappingCapture.persistSiteExpectedProfileIndex(artifact, Map.of(
                "site-a", Map.of("run.timezone", "Europe/London")));
        FingerprintMappingCatalog.Args args = FingerprintMappingCatalog.Args.parse(new String[]{
                "java-driverless", "--expected-profile", artifact.toString()
        });

        IllegalArgumentException error = org.junit.jupiter.api.Assertions.assertThrows(
                IllegalArgumentException.class, () -> FingerprintExpectedProfile.load(args));
        assertTrue(error.getMessage().contains("site-profile-index"));
    }

    @Test
    void malformedExpectedProfileJsonIsRejectedAsLoadFailure() throws Exception {
        Path artifact = tempDir.resolve("quebrado.json");
        org.junit.jupiter.api.Assertions.assertTrue(artifact.getParent() != null);
        java.nio.file.Files.writeString(artifact, "{não é json");

        FingerprintMappingCatalog.Args args = FingerprintMappingCatalog.Args.parse(new String[]{
                "java-driverless", "--expected-profile", artifact.toString()});

        IllegalArgumentException error = org.junit.jupiter.api.Assertions.assertThrows(
                IllegalArgumentException.class, () -> FingerprintExpectedProfile.load(args));
        assertTrue(error.getMessage().contains("Could not load expected profile"));
    }

    @Test
    void cliFlagsFeedTheExpectedOracleWithoutOverridingConfiguredValues() {
        FingerprintMappingCatalog.Args args = FingerprintMappingCatalog.Args.parse(new String[]{
                "java-driverless", "--profile-country", "BR", "--locale", "pt-BR",
                "--timezone", "America/Manaus", "--languages", "pt-BR,en",
                "--geo", "-3.123456789,-60.98765"});

        Map<String, String> expected = FingerprintExpectedProfile.load(args);

        assertEquals("BR", expected.get("run.profileCountry"));
        assertEquals("pt-BR", expected.get("run.locale"));
        assertEquals("America/Manaus", expected.get("run.timezone"));
        assertEquals("America/Manaus", expected.get("pixelscan.dateTime.Timezone from JS"));
        assertEquals("pt-BR", expected.get("pixelscan.language.Languages from Javascript"));
        assertEquals("pt-BR,en", expected.get("run.languages"));
        assertTrue(expected.get("http.acceptLanguage").startsWith("pt-BR"));
        // coordenadas aparadas em 4 casas decimais
        assertEquals("-3.1234", expected.get("pixelscan.location.Latitude"));
        assertEquals("-60.9876", expected.get("pixelscan.location.Longitude"));
        assertEquals("-3.123456789,-60.98765", expected.get("run.geo"));
    }

    @Test
    void flattenedJsonListsBecomeCommaJoinedValuesDerivingTheHttpHeader() throws Exception {
        Path artifact = tempDir.resolve("lista.json");
        JSON.writeValue(artifact.toFile(), Map.of("run", Map.of(
                "languages", java.util.List.of("en-US", "en", "pt-BR"))));

        FingerprintMappingCatalog.Args args = FingerprintMappingCatalog.Args.parse(new String[]{
                "java-driverless", "--expected-profile", artifact.toString()});

        Map<String, String> expected = FingerprintExpectedProfile.load(args);
        assertEquals("en-US,en,pt-BR", expected.get("run.languages"));
        assertTrue(expected.get("http.acceptLanguage").startsWith("en-US"));
    }

    private static FingerprintSiteContracts.ExpectedValue expected(String siteId, String key) {
        return FingerprintSiteContracts.site(siteId).expectedValues.stream()
                .filter(value -> key.equals(value.key))
                .findFirst()
                .orElseThrow();
    }
}
