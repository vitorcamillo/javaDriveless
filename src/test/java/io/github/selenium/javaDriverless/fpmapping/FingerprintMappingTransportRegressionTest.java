package io.github.selenium.javaDriverless.fpmapping;

import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FingerprintMappingTransportRegressionTest extends FingerprintMappingFalsePositiveSupport {
    @Test
    void browserLeaksIpMatchesEgressTimezoneAndPlatformFromTheActiveProfile() {
        String body = """
                What Is My IP Address?
                IP Address\t203.0.113.10
                Hostname\texample.invalid
                IP Address Location
                Country\tBrazil
                City\tExample City
                ISP\tExample Network
                Timezone\tAmerica/Sao_Paulo (BRT)
                IPv6 Leak Test
                IPv6 Address\tNot detected
                WebRTC Leak Test
                Local IP Address\t-
                Public IP Address\t203.0.113.10
                DNS Leak Test
                Run DNS Leak Test
                TCP/IP Fingerprint
                OS\tLinux 6.x
                JA4T\tsanitized-ja4t
                TLS Fingerprint
                JA3 Hash\tsanitized-ja3
                HTTP Headers
                User-Agent\tMozilla/5.0 (X11; Linux x86_64) Chrome/150.0.0.0
                Sec-CH-UA-Platform\t"Linux"
                Accept-Language\tpt-BR,pt;q=0.9
                report report report report report report report report report report report report
                """;
        Map<String, String> expected = Map.of(
                "webRtc.publicIp", "203.0.113.10",
                "run.timezone", "America/Sao_Paulo",
                "uaPlatform", "Linux",
                "http.acceptLanguage", "pt-BR,pt;q=0.9");

        Map<String, Object> result = FingerprintMappingValidation.validate(
                "browserleaks-ip", "IP Address - BrowserLeaks", "https://browserleaks.com/ip",
                body, body, "", expected, domEvidence("browserleaks-ip"));

        assertEquals("pass", result.get("status"), result.toString());
        assertEquals("expected-values", result.get("coverageLevel"));
        assertEquals(5, result.get("matchedCount"));
    }

    @Test
    void browserLeaksIpRejectsDuplicatedAcceptLanguageQualityValues() {
        String body = """
                What Is My IP Address?
                IP Address\t203.0.113.10
                Hostname\texample.invalid
                IP Address Location
                Country\tBrazil
                City\tExample City
                ISP\tExample Network
                Timezone\tAmerica/Sao_Paulo
                IPv6 Leak Test
                IPv6 Address\tNot detected
                WebRTC Leak Test
                Local IP Address\t-
                Public IP Address\t203.0.113.10
                DNS Leak Test
                Run DNS Leak Test
                TCP/IP Fingerprint
                OS\tLinux
                JA4T\tsanitized-ja4t
                TLS Fingerprint
                JA3 Hash\tsanitized-ja3
                HTTP Headers
                User-Agent\tMozilla/5.0 (X11; Linux x86_64) Chrome/150.0.0.0
                Sec-CH-UA-Platform\t"Linux"
                Accept-Language\tpt-BR,pt;q=0.9;q=0.9
                report report report report report report report report report report report report
                """;

        Map<String, Object> result = FingerprintMappingValidation.validate(
                "browserleaks-ip", "IP Address - BrowserLeaks", "https://browserleaks.com/ip",
                body, body, "", Map.of(), domEvidence("browserleaks-ip"));

        assertEquals("contract-broken", result.get("status"));
        assertEquals(java.util.List.of("browserleaks-ip-accept-language-invalid"), result.get("blockers"));
    }

    @Test
    void ipheyPromotionalCardsWithoutDetailedReportDoNotPass() {
        String body = """
                Your Digital Identity Looks Trustworthy
                BROWSER
                HARDWARE
                LOCATION
                IP ADDRESS
                SOFTWARE
                Fingerprint Check
                IP Check
                VPN Checker
                Bot Check
                DNS Leak Test
                marketing marketing marketing marketing marketing marketing marketing marketing
                marketing marketing marketing marketing marketing marketing marketing marketing
                """;

        Map<String, Object> result = FingerprintMappingValidation.validate(
                "iphey", "Iphey", "https://iphey.com/", body, body, "",
                FingerprintMappingIpheyFixtures.expected());

        assertEquals("contract-broken", result.get("status"));
        assertTrue(((java.util.List<?>) result.get("missing")).size() > 5, result.toString());
    }

    @Test
    void ipheyDetailedReportMatchesProfileAndEgressValues() {
        String body = FingerprintMappingIpheyFixtures.detailedBody(
                "America/Sao_Paulo", FingerprintMappingIpheyFixtures.GPU,
                FingerprintMappingIpheyFixtures.USER_AGENT, FingerprintMappingIpheyFixtures.PUBLIC_IP);

        Map<String, Object> result = FingerprintMappingValidation.validate(
                "iphey", "Iphey", "https://iphey.com/", body, body, "",
                FingerprintMappingIpheyFixtures.expected(), FingerprintMappingIpheyFixtures.domEvidence());

        assertEquals("pass", result.get("status"), result.toString());
        assertEquals("expected-values", result.get("coverageLevel"));
        assertEquals(15, result.get("matchedCount"));
    }

    @Test
    void ipheyDetailedReportRejectsTimezoneGpuUaAndWebRtcMismatches() {
        String body = FingerprintMappingIpheyFixtures.detailedBody(
                "America/New_York", "ANGLE (NVIDIA, Physical GPU)",
                "Mozilla/5.0 (Windows NT 10.0) Chrome/150.0.0.0", "198.51.100.20");

        Map<String, Object> result = FingerprintMappingValidation.validate(
                "iphey", "Iphey", "https://iphey.com/", body, body, "",
                FingerprintMappingIpheyFixtures.expected(), FingerprintMappingIpheyFixtures.domEvidence());

        assertEquals("value-mismatch", result.get("status"), result.toString());
        @SuppressWarnings("unchecked")
        java.util.List<String> mismatches = (java.util.List<String>)
                ((Map<String, Object>) result.get("expected")).get("mismatches");
        assertTrue(mismatches.stream().anyMatch(value -> value.contains("iphey.browser.Timezone")));
        assertTrue(mismatches.stream().anyMatch(value -> value.contains("iphey.hardware.GPU")));
        assertTrue(mismatches.stream().anyMatch(value -> value.contains("iphey.network.User Agent")));
        assertTrue(mismatches.stream().anyMatch(value -> value.contains("iphey.network.WebRTC IP")));
    }

    @Test
    void printLeaksRejectsNonNumericCollectorErrors() {
        Map<String, Object> result = validatePrintLeaks("55", "55", "N/A");

        assertEquals("contract-broken", result.get("status"), result.toString());
        assertTrue(((java.util.List<?>) result.get("blockers")).contains("collector-summary-invalid"));
    }

    @Test
    void printLeaksRejectsIncompleteCollectorRunEvenWithZeroErrors() {
        Map<String, Object> result = validatePrintLeaks("55", "1", "0");

        assertEquals("contract-broken", result.get("status"), result.toString());
        assertTrue(((java.util.List<?>) result.get("blockers")).contains("collector-summary-invalid"));
    }

    @Test
    void printLeaksRejectsEmptyZeroCollectorRun() {
        Map<String, Object> result = validatePrintLeaks("0", "0", "0");

        assertEquals("contract-broken", result.get("status"), result.toString());
    }

    @Test
    void printLeaksAcceptsOnlyCompleteErrorFreeCollectorRun() {
        Map<String, Object> result = validatePrintLeaks("55", "55", "0");

        assertEquals("pass", result.get("status"), result.toString());
        assertEquals(3, result.get("matchedCount"));
    }

}
