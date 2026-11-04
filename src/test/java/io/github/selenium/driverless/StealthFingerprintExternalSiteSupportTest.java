package io.github.selenium.driverless;

import org.junit.jupiter.api.Test;
import org.opentest4j.TestAbortedException;

import io.github.selenium.javaDriverless.types.BrowserFingerprintProfile;
import io.github.selenium.javaDriverless.types.ChromeOptions;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class StealthFingerprintExternalSiteSupportTest {

    @Test
    void releaseGateAcceptsOnlyAnExplicitPass() {
        org.assertj.core.api.Assertions.assertThatCode(
                () -> StealthFingerprintExternalSiteSupport.requireReleasePassStatus(
                        "iphey", Map.of("status", "pass")))
                .doesNotThrowAnyException();
    }

    @Test
    void releaseGateRejectsPrivacyPolicyConflict() {
        assertThatThrownBy(() -> StealthFingerprintExternalSiteSupport.requireReleasePassStatus(
                "iphey", Map.of("status", "privacy-policy-conflict")))
                .isInstanceOf(AssertionError.class)
                .hasMessageContaining("official release contract did not pass");
    }

    @Test
    void pixelScanPollingDistinguishesFinalVerdictsFromTheLoadingState() {
        assertThat(StealthFingerprintExternalSiteSupport.isPixelScanFinalBody(
                "Your Browser Fingerprint is scanning… Collecting Data…")).isFalse();
        assertThat(StealthFingerprintExternalSiteSupport.isPixelScanFinalBody(
                "Your Browser Fingerprint is consistent No proxy detected")).isTrue();
        assertThat(StealthFingerprintExternalSiteSupport.isPixelScanFinalBody(
                "Your Browser Fingerprint is inconsistent Proxy detected")).isTrue();
    }

    @Test
    void pixelScanDifferentialAcceptsOnlyTheSameRawChromeSignals() {
        var raw = new StealthFingerprintBrowserIdentityChecks.PixelScanState(
                "inconsistent", "detected", "clean", "clean");
        Map<String, Object> profiled = Map.of(
                "status", "forbidden-signal",
                "missing", java.util.List.of(),
                "blockers", java.util.List.of(),
                "matchedCount", 33,
                "decisiveExpectedMismatches", java.util.List.of(
                        "expected-mismatch:pixelscan.verdict policy=contains "
                                + "expected=Your Browser Fingerprint is consistent "
                                + "actual=Your Browser Fingerprint is inconsistent",
                        "expected-mismatch:pixelscan.fingerprintCard policy=contains "
                                + "expected=No masking detected actual=Masking detected"),
                "extracted", Map.of(
                        "verdict", "Your Browser Fingerprint is inconsistent",
                        "fingerprintCard", "Fingerprint masking detected",
                        "botCard", "No automated behavior detected",
                        "proxyCard", "No proxy detected"));

        org.assertj.core.api.Assertions.assertThatCode(() ->
                StealthFingerprintBrowserIdentityChecks.assertPixelScanDifferentialResult(raw, profiled))
                .doesNotThrowAnyException();
    }

    @Test
    void pixelScanDifferentialRejectsAnySignalAddedByTheProfile() {
        var raw = new StealthFingerprintBrowserIdentityChecks.PixelScanState(
                "inconsistent", "detected", "clean", "clean");
        Map<String, Object> profiled = Map.of(
                "status", "forbidden-signal",
                "missing", java.util.List.of(),
                "blockers", java.util.List.of(),
                "matchedCount", 33,
                "decisiveExpectedMismatches", java.util.List.of(
                        "expected-mismatch:pixelscan.verdict policy=contains "
                                + "expected=Your Browser Fingerprint is consistent "
                                + "actual=Your Browser Fingerprint is inconsistent",
                        "expected-mismatch:pixelscan.fingerprintCard policy=contains "
                                + "expected=No masking detected actual=Masking detected"),
                "extracted", Map.of(
                        "verdict", "Your Browser Fingerprint is inconsistent",
                        "fingerprintCard", "Fingerprint masking detected",
                        "botCard", "Automated behavior detected",
                        "proxyCard", "No proxy detected"));

        assertThatThrownBy(() ->
                StealthFingerprintBrowserIdentityChecks.assertPixelScanDifferentialResult(raw, profiled))
                .isInstanceOf(AssertionError.class)
                .hasMessageContaining("must not add a detector signal");
    }

    @Test
    void pixelScanDifferentialAcceptsProxyReputationAlreadyPresentInRawChrome() {
        var raw = new StealthFingerprintBrowserIdentityChecks.PixelScanState(
                "inconsistent", "detected", "clean", "detected");
        Map<String, Object> profiled = Map.of(
                "status", "forbidden-signal",
                "missing", java.util.List.of(),
                "blockers", java.util.List.of(),
                "matchedCount", 32,
                "decisiveExpectedMismatches", java.util.List.of(
                        "expected-mismatch:pixelscan.verdict policy=contains expected=clean actual=red",
                        "expected-mismatch:pixelscan.fingerprintCard policy=contains expected=clean actual=red",
                        "expected-mismatch:pixelscan.proxyCard policy=contains expected=clean actual=red"),
                "extracted", Map.of(
                        "verdict", "Your Browser Fingerprint is inconsistent",
                        "fingerprintCard", "Fingerprint masking detected",
                        "botCard", "No automated behavior detected",
                        "proxyCard", "Proxy detected"));

        org.assertj.core.api.Assertions.assertThatCode(() ->
                StealthFingerprintBrowserIdentityChecks.assertPixelScanDifferentialResult(raw, profiled))
                .doesNotThrowAnyException();
    }

    @Test
    void pixelScanDifferentialRejectsProxySignalAbsentFromRawChrome() {
        var raw = new StealthFingerprintBrowserIdentityChecks.PixelScanState(
                "inconsistent", "detected", "clean", "clean");
        Map<String, Object> profiled = Map.of(
                "status", "forbidden-signal",
                "missing", java.util.List.of(),
                "blockers", java.util.List.of(),
                "matchedCount", 32,
                "decisiveExpectedMismatches", java.util.List.of(
                        "expected-mismatch:pixelscan.verdict policy=contains expected=clean actual=red",
                        "expected-mismatch:pixelscan.fingerprintCard policy=contains expected=clean actual=red",
                        "expected-mismatch:pixelscan.proxyCard policy=contains expected=clean actual=red"),
                "extracted", Map.of(
                        "verdict", "Your Browser Fingerprint is inconsistent",
                        "fingerprintCard", "Fingerprint masking detected",
                        "botCard", "No automated behavior detected",
                        "proxyCard", "Proxy detected"));

        assertThatThrownBy(() ->
                StealthFingerprintBrowserIdentityChecks.assertPixelScanDifferentialResult(raw, profiled))
                .isInstanceOf(AssertionError.class)
                .hasMessageContaining("must not add a detector signal");
    }

    @Test
    void pixelScanCoverageFailureReportsOnlyMismatchKeys() {
        var raw = new StealthFingerprintBrowserIdentityChecks.PixelScanState(
                "inconsistent", "detected", "clean", "clean");
        Map<String, Object> profiled = Map.of(
                "status", "forbidden-signal",
                "missing", java.util.List.of(),
                "blockers", java.util.List.of(),
                "matchedCount", 32,
                "decisiveExpectedMismatches", java.util.List.of(
                        "expected-mismatch:pixelscan.verdict policy=contains expected=clean actual=red",
                        "expected-mismatch:pixelscan.fingerprintCard policy=contains expected=clean actual=red",
                        "expected-mismatch:pixelscan.location.City policy=contains expected=SecretCity actual=OtherCity"),
                "extracted", Map.of(
                        "verdict", "Your Browser Fingerprint is inconsistent",
                        "fingerprintCard", "Fingerprint masking detected",
                        "botCard", "No automated behavior detected",
                        "proxyCard", "No proxy detected"));

        assertThatThrownBy(() ->
                StealthFingerprintBrowserIdentityChecks.assertPixelScanDifferentialResult(raw, profiled))
                .isInstanceOf(AssertionError.class)
                .hasMessageContaining("pixelscan.location.City")
                .hasMessageNotContaining("SecretCity")
                .hasMessageNotContaining("OtherCity");
    }

    @Test
    void browserCheckDifferentialAcceptsOnlyReputationSharedWithRawChrome() {
        Map<String, Object> raw = differentialResult(
                "forbidden-signal", java.util.List.of(),
                java.util.List.of("browsercheck-suspicious", "browsercheck-proxy-detected"),
                Map.of("publicIp", "203.0.113.10", "realness", "Suspicious / Manipulated",
                        "proxyDetected", "Yes", "automationDetected", "No"));
        Map<String, Object> profiled = differentialResult(
                "forbidden-signal", java.util.List.of(
                        "expected-mismatch:browsercheck.realness policy=equals",
                        "expected-mismatch:browsercheck.proxyDetected policy=equals"),
                java.util.List.of("browsercheck-suspicious", "browsercheck-proxy-detected"),
                Map.of("publicIp", "203.0.113.10", "realness", "Suspicious / Manipulated",
                        "proxyDetected", "Yes", "automationDetected", "No"));

        org.assertj.core.api.Assertions.assertThatCode(() ->
                StealthFingerprintBrowserIdentityChecks.assertBrowserCheckDifferentialResult(
                        raw, profiled)).doesNotThrowAnyException();

        ((Map<String, Object>) profiled.get("extracted")).put("automationDetected", "Yes");
        assertThatThrownBy(() ->
                StealthFingerprintBrowserIdentityChecks.assertBrowserCheckDifferentialResult(
                        raw, profiled)).isInstanceOf(AssertionError.class);
    }

    @Test
    void ipheyDifferentialAcceptsSharedReputationAndProtectedWebRtcOnly() {
        Map<String, Object> raw = differentialResult(
                "forbidden-signal", java.util.List.of(), java.util.List.of("iphey-unreliable"),
                Map.of("verdict", "Your Digital Identity Looks Unreliable",
                        "bot", Map.of("hasWebdriver", "false")));
        Map<String, Object> profiled = differentialResult(
                "privacy-policy-conflict", java.util.List.of(
                        "expected-mismatch:iphey.verdict policy=equals",
                        "expected-mismatch:iphey.network.WebRTC IP policy=ipInAllowedSet"),
                java.util.List.of("iphey-unreliable", "iphey-more-login"),
                Map.of("verdict", "Your Digital Identity Looks Unreliable",
                        "bot", Map.of("hasWebdriver", "false"),
                        "network", Map.of("WebRTC IP", "")));

        org.assertj.core.api.Assertions.assertThatCode(() ->
                StealthFingerprintBrowserIdentityChecks.assertIpheyDifferentialResult(
                        raw, profiled, true)).doesNotThrowAnyException();
        assertThatThrownBy(() ->
                StealthFingerprintBrowserIdentityChecks.assertIpheyDifferentialResult(
                        raw, profiled, false)).isInstanceOf(AssertionError.class);
    }

    private static Map<String, Object> differentialResult(
            String status, java.util.List<String> mismatches,
            java.util.List<String> forbiddenHits, Map<String, Object> extracted) {
        Map<String, Object> result = new java.util.LinkedHashMap<>();
        result.put("status", status);
        result.put("missing", java.util.List.of());
        result.put("blockers", java.util.List.of());
        result.put("decisiveMissing", java.util.List.of());
        result.put("navigationBlockers", java.util.List.of());
        result.put("decisiveExpectedMismatches", mismatches);
        result.put("forbidden", Map.of("hits", forbiddenHits));
        result.put("expected", Map.of("missing", java.util.List.of()));
        result.put("extracted", new java.util.LinkedHashMap<>(extracted));
        return result;
    }

    @Test
    void browserScanPolicyEvidenceRequiresIndependentDnsAndOnlyKnownBlockers() {
        BrowserFingerprintProfile profile = BrowserFingerprintProfile.brLinux();
        profile.webRtc.disableUdp = true;
        profile.webRtc.publicIp = "203.0.113.10";
        Map<String, Object> result = new java.util.LinkedHashMap<>();
        result.put("status", "privacy-policy-conflict");
        result.put("matchedCount", 8);
        result.put("missing", java.util.List.of());
        result.put("decisiveMissing", java.util.List.of());
        result.put("decisiveExpectedMismatches", java.util.List.of());
        result.put("navigationBlockers", java.util.List.of());
        result.put("blockers", java.util.List.of(
                "browserscan-webrtc-privacy-policy-conflict",
                "browserscan-dns-unresolved"));
        result.put("forbidden", Map.of("hits", java.util.List.of()));
        result.put("expected", Map.of(
                "missing", java.util.List.of(), "mismatches", java.util.List.of(
                        "expected-mismatch:browserscan.dns.Status policy=equals "
                                + "expected=SAFE actual=UNKNOWN",
                        "actual-missing:browserscan.dns.Servers")));
        result.put("extracted", Map.of(
                "webrtc", Map.of("Status", "UNKNOWN"),
                "identity", Map.of("IP ADDRESS", profile.webRtc.publicIp)));
        var dnsEvidence = new StealthFingerprintExternalSiteSupport.BrowserLeaksDnsEvidence(
                profile.webRtc.publicIp);

        assertThat(StealthFingerprintExternalSiteSupport.isBrowserScanProtectedPolicyEvidence(
                "browserscan", profile, result, dnsEvidence, true)).isTrue();
        assertThat(StealthFingerprintExternalSiteSupport.isBrowserScanProtectedPolicyEvidence(
                "browserscan", profile, result, dnsEvidence, false)).isFalse();
        assertThat(StealthFingerprintExternalSiteSupport.isBrowserScanProtectedPolicyEvidence(
                "browserscan", profile, result, null, true)).isFalse();
        StealthFingerprintExternalSiteSupport.resetCrossSiteEvidence();
        assertThat(StealthFingerprintExternalSiteSupport.isBrowserScanProtectedPolicyEvidence(
                "browserscan", profile, result)).isFalse();

        result.put("blockers", java.util.List.of(
                "browserscan-webrtc-privacy-policy-conflict",
                "browserscan-dns-unresolved", "unexpected"));
        assertThat(StealthFingerprintExternalSiteSupport.isBrowserScanProtectedPolicyEvidence(
                "browserscan", profile, result, dnsEvidence, true)).isFalse();

        result.put("blockers", java.util.List.of(
                "browserscan-webrtc-privacy-policy-conflict",
                "browserscan-dns-unresolved"));
        result.put("expected", Map.of(
                "missing", java.util.List.of(), "mismatches", java.util.List.of(
                        "expected-mismatch:browserscan.identity.OS policy=contains "
                                + "expected=Linux actual=Windows")));
        assertThat(StealthFingerprintExternalSiteSupport.isBrowserScanProtectedPolicyEvidence(
                "browserscan", profile, result, dnsEvidence, true)).isFalse();
    }

    @Test
    @SuppressWarnings("unchecked")
    void browserScanPolicyEvidenceAcceptsRealValidatorDnsUnknownShape() throws Exception {
        BrowserFingerprintProfile profile = BrowserFingerprintProfile.brLinux();
        profile.webRtc.disableUdp = true;
        profile.webRtc.publicIp = "203.0.113.10";
        String body = """
                Browser Fingerprint & Trust Score Analyzer
                Risk assessment complete
                Scan complete
                98
                A
                STATUS
                Low Risk
                Powered by BrowserScan Trust Algorithm
                IDENTITY
                Digital fingerprint snapshot
                IP ADDRESS
                203.0.113.10
                ASN
                AS64500 Example
                LOCATION
                Campinas, BR
                BROWSER
                Chrome 150
                OS
                Linux
                DEVICE
                Desktop
                RISK BOARD
                WebRTC Check
                Status:
                UNKNOWN
                Proxy Detection
                Clean
                Timezone Consistency
                PASS
                PROTOCOL FINGERPRINTS
                TLS JA3
                0123456789abcdef0123456789abcdef
                TLS VERSION
                TLS 1.3
                HTTP VERSION
                h2
                TCP OS GUESS
                Linux
                WEBRTC
                Status
                UNKNOWN
                IP

                Region

                DNS
                Status
                UNKNOWN
                Servers:

                CONSISTENCY MATRIX
                Timezone vs IP
                Language vs Locale
                HARDWARE SURFACE
                CANVAS HASH
                01234567
                WEBGL RENDERER
                ANGLE
                SOFTWARE SURFACE
                FONTS HASH
                89abcdef
                TIMEZONE
                America/Sao_Paulo
                LANGUAGES
                pt-BR, pt
                """;
        Map<String, String> expected = new java.util.LinkedHashMap<>();
        expected.put("webRtc.disableUdp", "true");
        expected.put("webRtc.publicIp", profile.webRtc.publicIp);
        expected.put("webRtc.allowedIps", profile.webRtc.publicIp);
        expected.put("run.profileCountry", "BR");
        expected.put("browserMajor", "150");
        expected.put("uaPlatform", "Linux");
        expected.put("browserscan.webrtc.Status", "SAFE");
        expected.put("browserscan.dns.Status", "SAFE");
        Class<?> validation = Class.forName("io.github.selenium.javaDriverless.fpmapping.FingerprintMappingValidation");
        var method = validation.getDeclaredMethod("validate", String.class, String.class,
                String.class, String.class, String.class, String.class, Map.class);
        method.setAccessible(true);
        Map<String, Object> result = (Map<String, Object>) method.invoke(null,
                "browserscan", "BrowserScan", "https://browserscan.org/", body, body, "", expected);

        assertThat(result.get("status")).isEqualTo("privacy-policy-conflict");
        assertThat((java.util.Collection<?>) result.get("decisiveExpectedMismatches")).isEmpty();
        assertThat((java.util.List<String>) ((Map<?, ?>) result.get("expected")).get("mismatches"))
                .containsExactlyInAnyOrder(
                        "expected-mismatch:browserscan.dns.Status policy=equals "
                                + "expected=SAFE actual=UNKNOWN",
                        "actual-missing:browserscan.dns.Servers");
        var dnsEvidence = new StealthFingerprintExternalSiteSupport.BrowserLeaksDnsEvidence(
                profile.webRtc.publicIp);
        assertThat(StealthFingerprintExternalSiteSupport.isBrowserScanProtectedPolicyEvidence(
                "browserscan", profile, result, dnsEvidence, true)).isTrue();
    }

    @Test
    void degradedTimezoneEndpointRequiresExactBrowserCheckEvidenceAndShape() {
        BrowserFingerprintProfile profile = BrowserFingerprintProfile.brLinux();
        profile.timezoneId = "America/Sao_Paulo";
        profile.webRtc.publicIp = "203.0.113.10";
        Map<String, Object> result = new java.util.LinkedHashMap<>();
        result.put("status", "site-unresolved");
        result.put("missing", java.util.List.of());
        result.put("decisiveMissing", java.util.List.of());
        result.put("decisiveExpectedMismatches", java.util.List.of());
        result.put("navigationBlockers", java.util.List.of());
        result.put("blockers", java.util.List.of("trustmyip-timezone-unable-to-verify"));
        result.put("forbidden", Map.of("hits", java.util.List.of()));
        result.put("extracted", Map.of(
                "browserTimezone", "America/Sao_Paulo (UTC-3:00)",
                "ipTimezone", "Not Available",
                "result", "UNABLE TO VERIFY"));

        var browserCheckEvidence = new StealthFingerprintExternalSiteSupport.BrowserCheckEvidence(
                profile.webRtc.publicIp, profile.timezoneId);
        assertThat(StealthFingerprintExternalSiteSupport
                .isTimezoneEndpointDegradedWithIndependentEvidence(
                        "trustmyip-timezone", profile, result, browserCheckEvidence, true)).isTrue();
        assertThat(StealthFingerprintExternalSiteSupport
                .isTimezoneEndpointDegradedWithIndependentEvidence(
                        "trustmyip-timezone", profile, result, browserCheckEvidence, false)).isFalse();

        result.put("decisiveExpectedMismatches", java.util.List.of("unexpected"));
        assertThat(StealthFingerprintExternalSiteSupport
                .isTimezoneEndpointDegradedWithIndependentEvidence(
                        "trustmyip-timezone", profile, result, browserCheckEvidence, true)).isFalse();
    }

    @Test
    void externalProxyIsAbsentUnlessExplicitlyConfigured() {
        ChromeOptions options = new ChromeOptions();

        StealthFingerprintBrowserIdentityChecks.configureExternalProxy(options, null, "  ");

        assertThat(options.getSingleProxy()).isNull();
        assertThat(options.getProxyConfig()).isNull();
    }

    @Test
    void externalProxyPropertyOverridesEnvironmentAndIsValidated() {
        ChromeOptions options = new ChromeOptions();

        StealthFingerprintBrowserIdentityChecks.configureExternalProxy(
                options,
                "http://127.0.0.1:8080",
                "socks5://127.0.0.2:1080");

        assertThat(options.getSingleProxy()).isEqualTo("http://127.0.0.1:8080/");
        assertThat(options.getProxyConfig().getHost()).isEqualTo("127.0.0.1");
        assertThat(options.getProxyConfig().getPort()).isEqualTo(8080);
    }

    @Test
    void externalProxyFallsBackToEnvironment() {
        ChromeOptions options = new ChromeOptions();

        StealthFingerprintBrowserIdentityChecks.configureExternalProxy(
                options, null, "socks5://127.0.0.2:1080");

        assertThat(options.getSingleProxy()).isEqualTo("socks5://127.0.0.2:1080/");
    }

    @Test
    void externalProxyRejectsSchemesThatCouldLeakDns() {
        ChromeOptions options = new ChromeOptions();

        assertThatThrownBy(() -> StealthFingerprintBrowserIdentityChecks.configureExternalProxy(
                options, "socks4://user:password@127.0.0.1:1080", null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("External proxy configuration is invalid")
                .hasMessageNotContaining("user")
                .hasMessageNotContaining("password");
    }

    @Test
    void externalDnsPolicyUsesTheExplicitDohResolver() {
        ChromeOptions options = new ChromeOptions();

        StealthFingerprintBrowserIdentityChecks.configureExternalDns(
                options,
                "secure-doh",
                null,
                "https://cloudflare-dns.com/dns-query",
                null);

        assertThat(options.getArguments())
                .contains("--dns-over-https-mode=secure")
                .contains("--dns-over-https-templates=https://cloudflare-dns.com/dns-query");
    }

    @Test
    void rawPixelScanBaselineUsesTheSameConfiguredTransportWithoutFingerprintProfile() {
        ChromeOptions options = StealthFingerprintBrowserIdentityChecks.rawExternalTransportOptions(
                "http://127.0.0.1:8080", null,
                "secure-doh", null,
                "https://cloudflare-dns.com/dns-query", null);

        assertThat(options.getSingleProxy()).isEqualTo("http://127.0.0.1:8080/");
        assertThat(options.getArguments())
                .contains("--dns-over-https-mode=secure")
                .contains("--dns-over-https-templates=https://cloudflare-dns.com/dns-query")
                .doesNotContain(
                        "--disable-blink-features=AutomationControlled",
                        "--lang=en-US",
                        "--accept-lang=en-US,en",
                        "--disable-accelerated-2d-canvas",
                        "--ignore-gpu-blocklist",
                        "--enable-webgl",
                        "--disable-features=TranslateUI,InterestCohort");
        assertThat(options.getPrefs())
                .containsKeys("dns_over_https", "net")
                .doesNotContainKeys(
                        "credentials_enable_service", "devtools", "download_bubble",
                        "in_product_help", "profile");
        assertThat(options.isFingerprintEnabled()).isFalse();
    }

    @Test
    void releaseFailureSummaryNeverIncludesExtractedValuesOrPublicIp() {
        Map<String, Object> result = Map.of(
                "status", "site-unresolved",
                "coverageLevel", "runtime-exact",
                "missing", java.util.List.of("field"),
                "blockers", java.util.List.of("blocker"),
                "decisiveExpectedMismatches", java.util.List.of("mismatch"),
                "forbidden", Map.of("hits", java.util.List.of("signal")),
                "extracted", Map.of("publicIp", "203.0.113.10", "token", "not-for-logs"));

        assertThat(StealthFingerprintExternalSiteSupport.redactedResultSummary(result))
                .isEqualTo("{status=site-unresolved, coverageLevel=runtime-exact, "
                        + "missingCount=1, missingFields=[field], blockerCount=1, mismatchCount=1, "
                        + "mismatchFields=[redacted], forbiddenCount=1}")
                .doesNotContain("203.0.113.10", "not-for-logs", "publicIp", "extracted");
    }

    @Test
    void redactedSummaryMayNameMismatchFieldsButNeverExpectedOrActualValues() {
        Map<String, Object> result = Map.of(
                "status", "value-mismatch",
                "coverageLevel", "resolver-policy-validated",
                "missing", java.util.List.of(),
                "blockers", java.util.List.of(),
                "decisiveExpectedMismatches", java.util.List.of(
                        "expected-mismatch:browserleaks-dns.serverIsps policy=allMatchRegex "
                                + "expected=Secret ISP actual=Observed ISP",
                        "actual-missing:browserleaks-dns.serverLocations"),
                "forbidden", Map.of("hits", java.util.List.of()));

        assertThat(StealthFingerprintExternalSiteSupport.redactedResultSummary(result))
                .contains("mismatchFields=[browserleaks-dns.serverIsps, "
                        + "browserleaks-dns.serverLocations]")
                .doesNotContain("Secret ISP", "Observed ISP", "expected=", "actual=");
    }

    @Test
    void redactedSummaryNamesOnlySafeMissingContractIds() {
        Map<String, Object> result = Map.of(
                "status", "contract-broken",
                "coverageLevel", "structural-only",
                "missing", java.util.List.of("ipleak-browser-default", "secret value=203.0.113.10"),
                "blockers", java.util.List.of(),
                "decisiveExpectedMismatches", java.util.List.of(),
                "forbidden", Map.of("hits", java.util.List.of()));

        assertThat(StealthFingerprintExternalSiteSupport.redactedResultSummary(result))
                .contains("missingFields=[ipleak-browser-default, redacted]")
                .doesNotContain("203.0.113.10", "secret value");
    }

    @Test
    void expectedProfileUsesFullBrowserVersionForHighEntropyClientHints() {
        BrowserFingerprintProfile profile = BrowserFingerprintProfile.brLinux();
        profile.browserVersion = "150.0.7871.46";

        Map<String, String> expected = StealthFingerprintExternalSiteSupport.expectedProfile(profile);

        assertThat(expected)
                .containsEntry("browserVersion", "150.0.7871.46")
                .containsEntry("browserMajor", "150");
        assertThat(expected.get("userAgent")).contains("Chrome/150.0.0.0");
    }

    @Test
    void siteUnresolvedFailsByDefault() {
        Map<String, Object> result = Map.of(
                "status", "site-unresolved",
                "reason", "required detector field was not mapped");

        assertThatThrownBy(() -> StealthFingerprintExternalSiteSupport.handleSiteUnresolved(
                "browserleaks-webgl", result, false))
                .isInstanceOf(AssertionError.class)
                .hasMessageContaining("browserleaks-webgl")
                .hasMessageContaining("site-unresolved");
    }

    @Test
    void siteUnresolvedSkipsOnlyWhenExplicitlyAllowed() {
        Map<String, Object> result = Map.of("status", "site-unresolved");

        assertThatThrownBy(() -> StealthFingerprintExternalSiteSupport.handleSiteUnresolved(
                "browserleaks-webgl", result, true))
                .isInstanceOf(TestAbortedException.class)
                .hasMessageContaining("explicitly enabled");
    }

    @Test
    void siteUnresolvedCanNeverSkipAnIndependentMismatch() {
        Map<String, Object> result = Map.of(
                "status", "site-unresolved",
                "decisiveExpectedMismatches", java.util.List.of("identity-ip"));

        assertThatThrownBy(() -> StealthFingerprintExternalSiteSupport.handleSiteUnresolved(
                "browserscan", result, true))
                .isInstanceOf(AssertionError.class)
                .hasMessageContaining("independent mismatch");
    }

    @Test
    void skipConfigurationAcceptsOnlyExplicitTrueValuesAndPropertyOverridesEnvironment() {
        assertThat(StealthFingerprintExternalSiteSupport.allowSiteUnresolvedSkip(null, null)).isFalse();
        assertThat(StealthFingerprintExternalSiteSupport.allowSiteUnresolvedSkip("false", "1")).isFalse();
        assertThat(StealthFingerprintExternalSiteSupport.allowSiteUnresolvedSkip("yes", null)).isFalse();
        assertThat(StealthFingerprintExternalSiteSupport.allowSiteUnresolvedSkip(null, "yes")).isFalse();
        assertThat(StealthFingerprintExternalSiteSupport.allowSiteUnresolvedSkip("true", null)).isTrue();
        assertThat(StealthFingerprintExternalSiteSupport.allowSiteUnresolvedSkip(null, "1")).isTrue();
    }
}
