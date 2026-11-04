package io.github.selenium.javaDriverless.fpmapping;

import org.junit.jupiter.api.Test;
import java.util.Map;
import java.util.List;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
class FingerprintMappingValidationTest extends FingerprintMappingValidationSupport {
    @Test
    void rejectsRedirectOutsideContractedOrigin() {
        String body = "WebGL Fingerprinting\nUnmasked Renderer\nANGLE\n" + "ready ".repeat(50);
        Map<String, Object> result = FingerprintMappingValidation.validate(
                "browserleaks-webgl", "WebGL Browser Report - BrowserLeaks",
                "https://example.invalid/phishing", body,
                "<html><body>WebGL Fingerprinting</body></html>", "", Map.of(),
                domEvidence("browserleaks-webgl"));
        assertEquals("contract-broken", result.get("status"));
        assertEquals(List.of("unexpected-origin"), result.get("navigationBlockers"));
    }
    @Test
    void rejectsTitleThatDoesNotMatchContract() {
        String body = "WebGL Fingerprinting\nUnmasked Renderer\nANGLE\n" + "ready ".repeat(50);
        Map<String, Object> result = FingerprintMappingValidation.validate(
                "browserleaks-webgl", "Unrelated landing page", "https://browserleaks.com/webgl",
                body,
                "<html><body>WebGL Fingerprinting</body></html>", "", Map.of(),
                domEvidence("browserleaks-webgl"));
        assertEquals("contract-broken", result.get("status"));
        assertEquals(List.of("unexpected-title"), result.get("navigationBlockers"));
    }
    @Test
    void htmlOrBodyTermsAloneDoNotSatisfyRequiredDom() {
        String marketing = "WebGL Fingerprinting Unmasked Renderer Vendor Extensions Parameters "
                + "marketing ".repeat(40);
        Map<String, Object> result = FingerprintMappingValidation.validate(
                "browserleaks-webgl", "WebGL Browser Report - BrowserLeaks",
                "https://browserleaks.com/webgl", marketing,
                "<html><script>" + marketing + "</script><body>marketing only</body></html>",
                "", Map.of(), Map.of());
        assertEquals("contract-broken", result.get("status"));
        assertEquals(0, result.get("presentCount"));
    }
    @Test
    void browserLeaksGeoRejectsStaticLabelsWithoutNumericResults() {
        String body = "Geolocation API Permissions API Origin Permissions Global Permissions "
                + "Your browser supports Geolocation API Geolocation Data Latitude Longitude Accuracy Reverse Geocoding "
                + "ready ".repeat(50);
        Map<String, Object> evidence = new java.util.LinkedHashMap<>(domEvidence("browserleaks-geo"));
        evidence.put("browserleaks-geo-latitude", matchedNode("Latitude"));
        evidence.put("browserleaks-geo-longitude", matchedNode("Longitude"));
        evidence.put("browserleaks-geo-accuracy", matchedNode("Accuracy"));
        Map<String, Object> result = FingerprintMappingValidation.validate(
                "browserleaks-geo", "Geolocation API - BrowserLeaks", "https://browserleaks.com/geo",
                body, body, "", Map.of(), evidence);
        assertEquals("contract-broken", result.get("status"));
        @SuppressWarnings("unchecked")
        List<String> missing = (List<String>) result.get("missing");
        assertEquals(List.of("browserleaks-geo-latitude", "browserleaks-geo-longitude",
                "browserleaks-geo-accuracy"), missing);
    }
    @Test
    void browserLeaksGeoAcceptsNumericDomResults() {
        String body = "Geolocation API Permissions API Origin Permissions Global Permissions "
                + "Your browser supports Geolocation API Geolocation Data Latitude Longitude Accuracy Reverse Geocoding "
                + "ready ".repeat(50);
        Map<String, Object> evidence = new java.util.LinkedHashMap<>(domEvidence("browserleaks-geo"));
        evidence.put("browserleaks-geo-latitude", matchedNode("40.7128"));
        evidence.put("browserleaks-geo-longitude", matchedNode("-74.006"));
        evidence.put("browserleaks-geo-accuracy", matchedNode("100"));
        Map<String, Object> result = FingerprintMappingValidation.validate(
                "browserleaks-geo", "Geolocation API - BrowserLeaks", "https://browserleaks.com/geo",
                body, body, "", Map.of(), evidence);
        assertEquals("pass", result.get("status"));
    }


    @Test
    void browserLeaksCanvasExtractsTheRenderedSignatureNotTheNavigationLabel() {
        String hash = "67d3d2f9b8f477f2f930f7595f66c054";
        String body = "Canvas Fingerprint\n" + "navigation item\n".repeat(20)
                + "Canvas Fingerprinting\nCanvas Support Detection\nText API for Canvas\nCanvas toDataURL\n"
                + "Signature\n" + hash + "\n" + "report ".repeat(50);
        Map<String, Object> result = FingerprintMappingValidation.validate(
                "browserleaks-canvas", "Canvas Fingerprinting - BrowserLeaks",
                "https://browserleaks.com/canvas", body, body, "", Map.of(),
                domEvidence("browserleaks-canvas"));
        assertEquals("pass", result.get("status"));
        @SuppressWarnings("unchecked")
        Map<String, Object> extracted = (Map<String, Object>) result.get("extracted");
        assertEquals(hash, extracted.get("fingerprint"));
    }
    @Test
    void browserLeaksCanvasRejectsAStaticLabelInPlaceOfTheHashNode() {
        String body = "Canvas Fingerprinting\nCanvas Support Detection\nText API for Canvas\nCanvas toDataURL\n"
                + "Signature 67d3d2f9b8f477f2f930f7595f66c054\n" + "report ".repeat(50);
        Map<String, Object> evidence = new java.util.LinkedHashMap<>(domEvidence("browserleaks-canvas"));
        evidence.put("browserleaks-canvas-fingerprint", matchedNode("Canvas Fingerprint"));
        Map<String, Object> result = FingerprintMappingValidation.validate(
                "browserleaks-canvas", "Canvas Fingerprinting - BrowserLeaks",
                "https://browserleaks.com/canvas", body, body, "", Map.of(), evidence);
        assertEquals("contract-broken", result.get("status"));
    }
    @Test
    void trustMyIpCanvasExtractsItsVersionedCanvasHashFormat() {
        String body = "Canvas Fingerprint Test\nGPU Fingerprint Scanner Active\n"
                + "Your Canvas Fingerprint Hash CVS-E53FA041-BBC6\n" + "result ".repeat(50);
        Map<String, Object> result = FingerprintMappingValidation.validate(
                "trustmyip-canvas", "TrustMyIP Canvas Fingerprint Test",
                "https://trustmyip.com/canvas-fingerprint", body, body, "", Map.of(),
                domEvidence("trustmyip-canvas"));
        assertEquals("pass", result.get("status"));
        @SuppressWarnings("unchecked")
        Map<String, Object> extracted = (Map<String, Object>) result.get("extracted");
        assertEquals("CVS-E53FA041-BBC6", extracted.get("hash"));
    }
    @Test
    void browserLeaksFontsRequiresRenderedCountsAndBothHashFormats() {
        String body = "Font Fingerprinting\nFont Metrics\nFont Metrics Measurement\nUnicode Glyphs Measurement\n"
                + "124 fonts and 83 unique metrics found\n" + "report ".repeat(50);
        Map<String, Object> evidence = new java.util.LinkedHashMap<>(domEvidence("browserleaks-fonts"));
        evidence.put("browserleaks-fonts-metrics-hash",
                matchedNode("✔ 0123456789abcdef0123456789abcdef"));
        evidence.put("browserleaks-fonts-glyphs-hash", matchedNode("✔ 01234567"));
        Map<String, Object> pass = FingerprintMappingValidation.validate(
                "browserleaks-fonts", "Font Fingerprinting - BrowserLeaks",
                "https://browserleaks.com/fonts", body, body, "", Map.of(), evidence);
        assertEquals("pass", pass.get("status"));
        evidence.put("browserleaks-fonts-report", matchedNode("Report"));
        evidence.put("browserleaks-fonts-metrics-hash", matchedNode("not-a-hash"));
        Map<String, Object> broken = FingerprintMappingValidation.validate(
                "browserleaks-fonts", "Font Fingerprinting - BrowserLeaks",
                "https://browserleaks.com/fonts", body, body, "", Map.of(), evidence);
        assertEquals("contract-broken", broken.get("status"));
    }
    @Test
    void browserScanParsesScoreGradeAndRiskAroundTheStatusLabel() {
        Map<String, Object> extracted = FingerprintMappingPixelScanExtractor.extract(
                "browserscan", "98\nA+\nSTATUS\nLow Risk\nPowered by BrowserScan Trust Algorithm\n");
        assertEquals("98", extracted.get("score"));
        assertEquals("A+", extracted.get("grade"));
        assertEquals("Low Risk", extracted.get("risk"));
        assertEquals(List.of(), FingerprintMappingStatus.blockersFor(
                "browserscan",
                "98\nA+\nSTATUS\nLow Risk\nPowered by BrowserScan Trust Algorithm\n" + "safe ".repeat(60),
                "", "safe ".repeat(60), Map.of()));
    }
    @Test
    void pixelscanFailsWhenExpectedProfileIsMissing() {
        Map<String, Object> result = FingerprintMappingValidation.validate(
                "pixelscan", "Pixelscan", "https://pixelscan.net/fingerprint-check",
                FingerprintMappingPixelscanFixtures.pixelscanBody(), FingerprintMappingPixelscanFixtures.pixelscanBody(), FingerprintMappingPixelscanFixtures.pixelscanRead(), Map.of());
        assertEquals("expected-missing", result.get("status"));
    }
    @Test
    void pixelscanOptionalGeoLabelsFallBackToPresenceWhenNotConfigured() {
        Map<String, String> expected = FingerprintMappingPixelscanFixtures.expectedProfile();
        for (String key : List.of(
                "pixelscan.locationCard", "pixelscan.location.Country", "pixelscan.location.City",
                "pixelscan.location.ISP", "pixelscan.location.User Type by IP",
                "pixelscan.dateTime.Daylight Savings Time")) {
            expected.remove(key);
        }
        Map<String, Object> result = FingerprintMappingValidation.validate(
                "pixelscan", "Pixelscan", "https://pixelscan.net/fingerprint-check",
                FingerprintMappingPixelscanFixtures.pixelscanBody(),
                FingerprintMappingPixelscanFixtures.pixelscanBody(),
                FingerprintMappingPixelscanFixtures.pixelscanRead(), expected);
        assertEquals("pass", result.get("status"), result.toString());
    }
    @Test
    @SuppressWarnings("unchecked")
    void pixelscanExtractorPreservesTheCompleteWeightedAcceptLanguageHeader() {
        String body = FingerprintMappingPixelscanFixtures.pixelscanBody().replace(
                "Accept-Language header\npt-BR\nHardware",
                "Accept-Language header\nen-US\nen;q=0.9\nHardware");
        Map<String, Object> extracted = FingerprintMappingPixelScanExtractor.extract("pixelscan", body);
        Map<String, String> language = (Map<String, String>) extracted.get("language");
        assertEquals("en-US,en;q=0.9", language.get("Accept-Language header"));
    }
    @Test
    void pixelscanFailsWhenExpectedValueDoesNotMatch() {
        Map<String, String> expected = FingerprintMappingPixelscanFixtures.expectedProfile();
        expected.put("pixelscan.location.Country", "United States");
        Map<String, Object> result = FingerprintMappingValidation.validate(
                "pixelscan", "Pixelscan", "https://pixelscan.net/fingerprint-check",
                FingerprintMappingPixelscanFixtures.pixelscanBody(), FingerprintMappingPixelscanFixtures.pixelscanBody(), FingerprintMappingPixelscanFixtures.pixelscanRead(), expected);
        assertEquals("value-mismatch", result.get("status"));
    }
    @Test
    void wildcardCannotDowngradeExactPixelscanPoliciesToPresence() {
        Map<String, String> expected = new java.util.LinkedHashMap<>();
        FingerprintMappingPixelscanFixtures.expectedProfile().keySet()
                .forEach(key -> expected.put(key, "*"));
        Map<String, Object> result = FingerprintMappingValidation.validate(
                "pixelscan", "Pixelscan", "https://pixelscan.net/fingerprint-check",
                FingerprintMappingPixelscanFixtures.pixelscanBody(),
                FingerprintMappingPixelscanFixtures.pixelscanBody(),
                FingerprintMappingPixelscanFixtures.pixelscanRead(), expected);
        assertEquals("value-mismatch", result.get("status"));
    }
    @Test
    void pixelscanFailsWhenForbiddenSignalAppears() {
        Map<String, Object> result = FingerprintMappingValidation.validate(
                "pixelscan", "Pixelscan", "https://pixelscan.net/fingerprint-check",
                FingerprintMappingPixelscanFixtures.pixelscanBody().replace("No automated behavior detected", "Automated behavior detected"),
                FingerprintMappingPixelscanFixtures.pixelscanBody(), FingerprintMappingPixelscanFixtures.pixelscanRead(), FingerprintMappingPixelscanFixtures.expectedProfile());
        assertEquals("forbidden-signal", result.get("status"));
    }
    @Test
    void pixelscanDoesNotTreatNoProxyDetectedAsForbiddenProxySignal() {
        Map<String, Object> result = FingerprintMappingValidation.validate(
                "pixelscan", "Pixelscan", "https://pixelscan.net/fingerprint-check",
                FingerprintMappingPixelscanFixtures.pixelscanBody(), FingerprintMappingPixelscanFixtures.pixelscanBody(), FingerprintMappingPixelscanFixtures.pixelscanRead(), FingerprintMappingPixelscanFixtures.expectedProfile());
        assertEquals("pass", result.get("status"));
    }
    @Test
    void pixelscanFailsWhenProxyDetectedAppears() {
        Map<String, Object> result = FingerprintMappingValidation.validate(
                "pixelscan", "Pixelscan", "https://pixelscan.net/fingerprint-check",
                FingerprintMappingPixelscanFixtures.pixelscanBody().replace("No proxy detected", "Proxy detected"),
                FingerprintMappingPixelscanFixtures.pixelscanBody(), FingerprintMappingPixelscanFixtures.pixelscanRead(), FingerprintMappingPixelscanFixtures.expectedProfile());
        assertEquals("forbidden-signal", result.get("status"));
    }
    @Test
    void pixelscanAllowsWebRtcToBeAbsentOnlyWhenTheProfileBlocksUdp() {
        String protectedBody = FingerprintMappingPixelscanFixtures.pixelscanBody().replace(
                "WebRTC IP Address\n198.51.100.20, 2001:db8:0:0:0:0:0:1\n",
                "");
        Map<String, String> expected = FingerprintMappingPixelscanFixtures.expectedProfile();
        expected.put("webRtc.disableUdp", "true");
        Map<String, Object> result = FingerprintMappingValidation.validate(
                "pixelscan", "Pixelscan", "https://pixelscan.net/fingerprint-check",
                protectedBody, protectedBody, FingerprintMappingPixelscanFixtures.pixelscanRead(), expected);
        assertEquals("pass", result.get("status"), result.toString());
    }
    @Test
    void pixelscanStillRejectsAnUnexpectedWebRtcIpWhenUdpIsProtected() {
        String leakingBody = FingerprintMappingPixelscanFixtures.pixelscanBody().replace(
                "198.51.100.20, 2001:db8:0:0:0:0:0:1",
                "198.51.100.99");
        Map<String, String> expected = FingerprintMappingPixelscanFixtures.expectedProfile();
        expected.put("webRtc.disableUdp", "true");
        Map<String, Object> result = FingerprintMappingValidation.validate(
                "pixelscan", "Pixelscan", "https://pixelscan.net/fingerprint-check",
                leakingBody, leakingBody, FingerprintMappingPixelscanFixtures.pixelscanRead(), expected);
        assertEquals("value-mismatch", result.get("status"), result.toString());
    }
    @Test
    void pixelscanReportsContractBrokenWhenRequiredDomIsMissing() {
        Map<String, Object> result = FingerprintMappingValidation.validate(
                "pixelscan", "Pixelscan", "https://pixelscan.net/fingerprint-check",
                "Fingerprint Scan", "Fingerprint Scan", FingerprintMappingPixelscanFixtures.pixelscanRead(), FingerprintMappingPixelscanFixtures.expectedProfile());
        assertEquals("capture-error", result.get("status"));
    }
    @Test
    void pixelscanPassesWhenDomAndExpectedProfileMatch() {
        Map<String, Object> result = FingerprintMappingValidation.validate(
                "pixelscan", "Pixelscan", "https://pixelscan.net/fingerprint-check",
                FingerprintMappingPixelscanFixtures.pixelscanBody(), FingerprintMappingPixelscanFixtures.pixelscanBody(), FingerprintMappingPixelscanFixtures.pixelscanRead(), FingerprintMappingPixelscanFixtures.expectedProfile());
        assertEquals("pass", result.get("status"));
        assertEquals(34, result.get("requiredCount"));
        assertEquals(34, result.get("presentCount"));
        assertEquals(0, result.get("missingCount"));
        assertEquals(35, result.get("matchedCount"));
        assertEquals("expected-values", result.get("coverageLevel"));
        @SuppressWarnings("unchecked")
        Map<String, Object> expected = (Map<String, Object>) result.get("expected");
        @SuppressWarnings("unchecked")
        Map<String, String> matched = (Map<String, String>) expected.get("matched");
        assertEquals(35, matched.size());
    }
    @Test
    void browserscanContractUsesDataDrivenRequiredDomAndCatchesRiskSignals() {
        Map<String, Object> result = FingerprintMappingValidation.validate(
                "browserscan", "BrowserScan", "https://browserscan.org/",
                FingerprintMappingSiteBodies.browserscanBody(), FingerprintMappingSiteBodies.browserscanBody(),
                "old-generic-field", Map.of(
                        "webRtc.publicIp", "203.0.113.210",
                        "browserscan.dns.Status", "SAFE"), domEvidence("browserscan"));
        assertEquals("forbidden-signal", result.get("status"));
        assertEquals(16, result.get("requiredCount"));
        assertEquals(16, result.get("presentCount"));
        assertEquals("expected-values", result.get("coverageLevel"));
    }
    @Test
    void networkContractWithoutExpectedEgressFailsExplicitly() {
        String body = "IP Address report ready " + "network ".repeat(50);
        Map<String, Object> result = FingerprintMappingValidation.validate(
                "browserleaks-ip", "IP Address - BrowserLeaks", "https://browserleaks.com/ip",
                body, body, "", Map.of(), domEvidence("browserleaks-ip"));
        assertEquals("expected-missing", result.get("status"));
        assertEquals("expected-values", result.get("coverageLevel"));
    }
    @Test
    void browserLeaksDnsRequiresTheExpectedEgressAndACompletedResolverReport() {
        String body = browserLeaksDnsBody("203.0.113.10", 2, 2);
        Map<String, Object> result = FingerprintMappingValidation.validate(
                "browserleaks-dns", "DNS Leak Test - BrowserLeaks", "https://browserleaks.com/dns",
                body, body, "", browserLeaksDnsExpected("203.0.113.10"),
                domEvidence("browserleaks-dns"));
        assertEquals("pass", result.get("status"), result.toString());
        assertEquals("resolver-observed-unattributed", result.get("coverageLevel"));
        assertEquals(false, result.get("provesLeakAbsence"));
        assertEquals(10, result.get("matchedCount"));
        @SuppressWarnings("unchecked")
        Map<String, Object> extracted = (Map<String, Object>) result.get("extracted");
        assertEquals("2", extracted.get("reportedServerCount"));
        assertEquals("2", extracted.get("observedServerCount"));
        assertEquals("matched", extracted.get("countConsistency"));
        assertEquals(2, ((List<?>) extracted.get("servers")).size());
        Map<String, Object> fallbackDom = FingerprintMappingValidation.validate(
                "browserleaks-dns", "DNS Leak Test - BrowserLeaks", "https://browserleaks.com/dns",
                body, body, "", browserLeaksDnsExpected("203.0.113.10"));
        assertEquals("pass", fallbackDom.get("status"), fallbackDom.toString());
        assertEquals(6, fallbackDom.get("presentCount"));
    }
    @Test
    void browserLeaksDnsOnlyProvesLeakAbsenceWithAnIndependentResolverPolicy() {
        String body = browserLeaksDnsBody("203.0.113.10", 2, 2);
        Map<String, String> expected = new java.util.LinkedHashMap<>(
                browserLeaksDnsExpected("203.0.113.10"));
        expected.put("browserleaks-dns.allowedIpPattern",
                "^(?:1\\.1\\.1\\.1|2606:4700:4700::1111)$");
        expected.put("browserleaks-dns.allowedIspPattern", "^Example Resolver$");
        expected.put("browserleaks-dns.allowedLocationPattern", "^Example Country,");
        Map<String, Object> result = FingerprintMappingValidation.validate(
                "browserleaks-dns", "DNS Leak Test - BrowserLeaks", "https://browserleaks.com/dns",
                body, body, "", expected, domEvidence("browserleaks-dns"));
        assertEquals("pass", result.get("status"), result.toString());
        assertEquals("resolver-policy-validated", result.get("coverageLevel"));
        assertEquals(true, result.get("provesLeakAbsence"));
        expected.put("browserleaks-dns.allowedIspPattern", "^Approved Resolver$");
        Map<String, Object> rejected = FingerprintMappingValidation.validate(
                "browserleaks-dns", "DNS Leak Test - BrowserLeaks", "https://browserleaks.com/dns",
                body, body, "", expected, domEvidence("browserleaks-dns"));
        assertEquals("value-mismatch", rejected.get("status"), rejected.toString());
        assertEquals(false, rejected.get("provesLeakAbsence"));
    }
    @Test
    void browserLeaksDnsLocationOnlyPolicyIsExplicitlyPartial() {
        String body = browserLeaksDnsBody("203.0.113.10", 2, 2);
        Map<String, String> expected = new java.util.LinkedHashMap<>(
                browserLeaksDnsExpected("203.0.113.10"));
        expected.put("browserleaks-dns.allowedLocationPattern", "^Example Country,");
        Map<String, Object> result = FingerprintMappingValidation.validate(
                "browserleaks-dns", "DNS Leak Test - BrowserLeaks", "https://browserleaks.com/dns",
                body, body, "", expected, domEvidence("browserleaks-dns"));
        assertEquals("pass", result.get("status"), result.toString());
        assertEquals("resolver-policy-partially-validated", result.get("coverageLevel"));
        assertEquals("partial", result.get("resolverAttribution"));
        assertEquals(false, result.get("provesLeakAbsence"));
    }
    @Test
    void browserLeaksDnsRejectsAnUnexpectedPublicIp() {
        String body = browserLeaksDnsBody("198.51.100.77", 2, 2);
        Map<String, Object> result = FingerprintMappingValidation.validate(
                "browserleaks-dns", "DNS Leak Test - BrowserLeaks", "https://browserleaks.com/dns",
                body, body, "", browserLeaksDnsExpected("203.0.113.10"),
                domEvidence("browserleaks-dns"));
        assertEquals("value-mismatch", result.get("status"), result.toString());
    }
    @Test
    void browserLeaksDnsRejectsZeroOrInconsistentResolverCounts() {
        String zeroBody = browserLeaksDnsBody("203.0.113.10", 0, 0);
        Map<String, Object> zero = FingerprintMappingValidation.validate(
                "browserleaks-dns", "DNS Leak Test - BrowserLeaks", "https://browserleaks.com/dns",
                zeroBody, zeroBody, "", browserLeaksDnsExpected("203.0.113.10"),
                domEvidence("browserleaks-dns"));
        assertEquals("value-mismatch", zero.get("status"), zero.toString());
        String inconsistentBody = browserLeaksDnsBody("203.0.113.10", 3, 2);
        Map<String, Object> inconsistent = FingerprintMappingValidation.validate(
                "browserleaks-dns", "DNS Leak Test - BrowserLeaks", "https://browserleaks.com/dns",
                inconsistentBody, inconsistentBody, "", browserLeaksDnsExpected("203.0.113.10"),
                domEvidence("browserleaks-dns"));
        assertEquals("value-mismatch", inconsistent.get("status"), inconsistent.toString());
        String duplicatedBody = browserLeaksDnsBody("203.0.113.10", 2, 2)
                .replace("2606:4700:4700::1111", "1.1.1.1");
        Map<String, Object> duplicated = FingerprintMappingValidation.validate(
                "browserleaks-dns", "DNS Leak Test - BrowserLeaks", "https://browserleaks.com/dns",
                duplicatedBody, duplicatedBody, "", browserLeaksDnsExpected("203.0.113.10"),
                domEvidence("browserleaks-dns"));
        assertEquals("value-mismatch", duplicated.get("status"), duplicated.toString());
    }
    @Test
    void browserLeaksDnsDoesNotPassTheStaticRunButtonState() {
        String body = "DNS Leak Test Your IP Address IP Address ISP Location Test Results "
                + "Your DNS Servers IP Address : ISP : Location : Run DNS Leak Test "
                + "waiting ".repeat(40);
        Map<String, Object> result = FingerprintMappingValidation.validate(
                "browserleaks-dns", "DNS Leak Test - BrowserLeaks", "https://browserleaks.com/dns",
                body, body, "", browserLeaksDnsExpected("203.0.113.10"),
                domEvidence("browserleaks-dns"));
        assertEquals("forbidden-signal", result.get("status"), result.toString());
    }
    @Test
    void ipheyContractCatchesUnreliableVerdict() {
        String body = FingerprintMappingIpheyFixtures.detailedBody(
                        "America/Sao_Paulo", FingerprintMappingIpheyFixtures.GPU,
                        FingerprintMappingIpheyFixtures.USER_AGENT,
                        FingerprintMappingIpheyFixtures.PUBLIC_IP)
                .replace("Your Digital Identity Looks Trustworthy",
                        "Your Digital Identity Looks Unreliable")
                + "\nDetected the following processes on your device: Dolphin Anty";
        Map<String, Object> result = FingerprintMappingValidation.validate(
                "iphey", "Iphey", "https://iphey.com/", body, body, "",
                FingerprintMappingIpheyFixtures.expected(), FingerprintMappingIpheyFixtures.domEvidence());
        assertEquals("forbidden-signal", result.get("status"));
    }
    @Test
    void ipheyContractObservesUserAgentAndNavigatorSignalsWithoutFixingTheirBooleanValue() {
        String body = FingerprintMappingIpheyFixtures.detailedBody(
                        "America/Sao_Paulo", FingerprintMappingIpheyFixtures.GPU,
                        FingerprintMappingIpheyFixtures.USER_AGENT,
                        FingerprintMappingIpheyFixtures.PUBLIC_IP)
                .replace("hasUserAgent\ntrue", "hasUserAgent\nfalse")
                .replace("hasNavigator\ntrue", "hasNavigator\nfalse");
        Map<String, Object> result = FingerprintMappingValidation.validate(
                "iphey", "Iphey", "https://iphey.com/", body, body, "",
                FingerprintMappingIpheyFixtures.expected(), FingerprintMappingIpheyFixtures.domEvidence());
        assertEquals("pass", result.get("status"), result.toString());
    }
    @Test
    void ipheyContractReconstructsAndRequiresTheCompleteTrustworthyVerdict() {
        String splitVerdict = FingerprintMappingIpheyFixtures.detailedBody(
                        "America/Sao_Paulo", FingerprintMappingIpheyFixtures.GPU,
                        FingerprintMappingIpheyFixtures.USER_AGENT,
                        FingerprintMappingIpheyFixtures.PUBLIC_IP)
                .replace("Your Digital Identity Looks Trustworthy",
                        "Your Digital Identity Looks\nTrustworthy");
        Map<String, Object> splitResult = FingerprintMappingValidation.validate(
                "iphey", "Iphey", "https://iphey.com/", splitVerdict, splitVerdict, "",
                FingerprintMappingIpheyFixtures.expected(), FingerprintMappingIpheyFixtures.domEvidence());
        assertEquals("pass", splitResult.get("status"), splitResult.toString());

        String incompleteVerdict = splitVerdict.replace("\nTrustworthy", "");
        Map<String, Object> incompleteResult = FingerprintMappingValidation.validate(
                "iphey", "Iphey", "https://iphey.com/", incompleteVerdict, incompleteVerdict, "",
                FingerprintMappingIpheyFixtures.expected(), FingerprintMappingIpheyFixtures.domEvidence());
        assertEquals("value-mismatch", incompleteResult.get("status"), incompleteResult.toString());
    }
    @Test
    void ipheyContractObservesCdpWithoutTreatingItAsBotProof() {
        String body = FingerprintMappingIpheyFixtures.detailedBody(
                        "America/Sao_Paulo", FingerprintMappingIpheyFixtures.GPU,
                        FingerprintMappingIpheyFixtures.USER_AGENT,
                        FingerprintMappingIpheyFixtures.PUBLIC_IP)
                .replace("hasCDP\nfalse", "hasCDP\ntrue");
        Map<String, Object> result = FingerprintMappingValidation.validate(
                "iphey", "Iphey", "https://iphey.com/", body, body, "",
                FingerprintMappingIpheyFixtures.expected(), FingerprintMappingIpheyFixtures.domEvidence());
        assertEquals("pass", result.get("status"), result.toString());
    }
    @Test
    void ipheyContractStillRejectsWebdriverWhenCdpIsObserved() {
        String body = FingerprintMappingIpheyFixtures.detailedBody(
                        "America/Sao_Paulo", FingerprintMappingIpheyFixtures.GPU,
                        FingerprintMappingIpheyFixtures.USER_AGENT,
                        FingerprintMappingIpheyFixtures.PUBLIC_IP)
                .replace("hasCDP\nfalse", "hasCDP\ntrue")
                .replace("hasWebdriver\nfalse", "hasWebdriver\ntrue");
        Map<String, Object> result = FingerprintMappingValidation.validate(
                "iphey", "Iphey", "https://iphey.com/", body, body, "",
                FingerprintMappingIpheyFixtures.expected(), FingerprintMappingIpheyFixtures.domEvidence());
        assertEquals("forbidden-signal", result.get("status"), result.toString());
    }
    @Test
    void printleaksContractCatchesCollectorErrors() {
        Map<String, Object> result = FingerprintMappingValidation.validate(
                "printleaks", "PrintLeaks", "https://www.printleaks.com/",
                FingerprintMappingSiteBodies.printleaksBody(), FingerprintMappingSiteBodies.printleaksBody(), "", Map.of());
        assertEquals("contract-broken", result.get("status"));
    }
}
