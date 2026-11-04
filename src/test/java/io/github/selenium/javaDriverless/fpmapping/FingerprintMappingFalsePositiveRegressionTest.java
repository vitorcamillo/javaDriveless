package io.github.selenium.javaDriverless.fpmapping;

import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FingerprintMappingFalsePositiveRegressionTest extends FingerprintMappingFalsePositiveSupport {

    @Test
    void unresolvedExternalSectionCannotHideAMissingExpectedOracle() {
        String status = FingerprintMappingStatus.statusFor(
                java.util.List.of("browserscan-dns-unresolved"),
                java.util.List.of(),
                java.util.List.of("expected-missing:webRtc.publicIp"),
                java.util.List.of(),
                java.util.List.of());

        assertEquals("expected-missing", status);
    }

    @Test
    void browserScanRejectsUnknownWebRtcAndDnsEvenWithASafeGrade() {
        String body = """
                Browser Fingerprint & Trust Score Analyzer
                Scan complete
                Risk assessment complete
                98
                A+
                STATUS
                Low Risk
                Powered by BrowserScan Trust Algorithm
                IDENTITY
                IP ADDRESS
                203.0.113.10
                ASN
                AS64500 Example Network
                LOCATION
                Example City, BR
                BROWSER
                Chrome 150
                OS
                Linux
                DEVICE
                Desktop
                RISK BOARD
                PROTOCOL FINGERPRINTS
                TLS JA3
                sanitized-ja3
                TLS VERSION
                TLSv1.3
                HTTP VERSION
                HTTP/2
                TCP OS GUESS
                Linux
                WEBRTC
                Status: UNKNOWN
                IP:
                Region:
                DNS
                Status: UNKNOWN
                Servers:
                CONSISTENCY MATRIX
                Timezone vs IP
                Language vs Locale
                HARDWARE SURFACE
                Canvas Hash
                WebGL Renderer
                SOFTWARE SURFACE
                Fonts Hash
                Timezone
                Languages
                filler filler filler filler filler filler filler filler filler filler filler filler
                """;

        Map<String, Object> result = FingerprintMappingValidation.validate(
                "browserscan", "BrowserScan", "https://browserscan.org/",
                body, body, "", browserScanExpected(false), domEvidence("browserscan"));

        assertEquals("site-unresolved", result.get("status"));
        assertEquals(java.util.List.of(
                "browserscan-webrtc-unresolved", "browserscan-dns-unresolved"),
                result.get("blockers"));

        String dnsResolved = body.replace(
                "DNS\nStatus: UNKNOWN\nServers:",
                "DNS\nStatus: SAFE\nServers: 1.1.1.1");
        Map<String, Object> protectedResult = FingerprintMappingValidation.validate(
                "browserscan", "BrowserScan", "https://browserscan.org/",
                dnsResolved, dnsResolved, "", browserScanExpected(true),
                domEvidence("browserscan"));
        assertEquals("privacy-policy-conflict", protectedResult.get("status"),
                protectedResult.toString());
        assertEquals(java.util.List.of("browserscan-webrtc-privacy-policy-conflict"),
                protectedResult.get("blockers"));

        Map<String, String> independentlyValidatedDns = new LinkedHashMap<>(browserScanExpected(true));
        Map<String, Object> crossSiteResult = FingerprintMappingValidation.validate(
                "browserscan", "BrowserScan", "https://browserscan.org/",
                body, body, "", independentlyValidatedDns, domEvidence("browserscan"));
        assertEquals("privacy-policy-conflict", crossSiteResult.get("status"),
                crossSiteResult.toString());
        assertEquals(java.util.List.of(
                        "browserscan-webrtc-privacy-policy-conflict", "browserscan-dns-unresolved"),
                crossSiteResult.get("blockers"));
    }

    @Test
    void browserScanDoesNotLetAnUnresolvedSectionHideAnIdentityMismatch() {
        String body = browserScanUnknownBody().replace(
                "IP ADDRESS\n203.0.113.10", "IP ADDRESS\n198.51.100.77");

        Map<String, Object> result = FingerprintMappingValidation.validate(
                "browserscan", "BrowserScan", "https://browserscan.org/",
                body, body, "", browserScanExpected(false), domEvidence("browserscan"));

        assertEquals("value-mismatch", result.get("status"), result.toString());
    }

    @Test
    void browserScanRejectsSafeWebRtcWithAnUnexpectedIp() {
        String body = browserScanUnknownBody()
                .replace("WEBRTC\nStatus: UNKNOWN\nIP:\nRegion:",
                        "WEBRTC\nStatus: SAFE\nIP: 198.51.100.77\nRegion: US")
                .replace("DNS\nStatus: UNKNOWN\nServers:",
                        "DNS\nStatus: SAFE\nServers: 1.1.1.1");

        Map<String, Object> result = FingerprintMappingValidation.validate(
                "browserscan", "BrowserScan", "https://browserscan.org/",
                body, body, "", browserScanExpected(false), domEvidence("browserscan"));

        assertEquals("value-mismatch", result.get("status"), result.toString());
    }

    @Test
    void browserScanRejectsStaleBrowserOsAndLocationCards() {
        String clean = browserScanUnknownBody()
                .replace("WEBRTC\nStatus: UNKNOWN\nIP:\nRegion:",
                        "WEBRTC\nStatus: SAFE\nIP: 203.0.113.10\nRegion: BR")
                .replace("DNS\nStatus: UNKNOWN\nServers:",
                        "DNS\nStatus: SAFE\nServers: 1.1.1.1");

        for (String stale : java.util.List.of(
                clean.replace("BROWSER\nChrome 150", "BROWSER\nChrome 149"),
                clean.replace("OS\nLinux", "OS\nWindows"),
                clean.replace("LOCATION\nExample City, BR", "LOCATION\nExample City, US"))) {
            Map<String, Object> result = FingerprintMappingValidation.validate(
                    "browserscan", "BrowserScan", "https://browserscan.org/",
                    stale, stale, "", browserScanExpected(false), domEvidence("browserscan"));
            assertEquals("value-mismatch", result.get("status"), result.toString());
        }
    }

    @Test
    void browserScanRejectsMultilineLeakAndModerateRisk() {
        String leak = browserScanUnknownBody()
                .replace("WEBRTC\nStatus: UNKNOWN\nIP:\nRegion:",
                        "WEBRTC\nStatus\nLEAK\nIP\n198.51.100.77\nRegion\nUS")
                .replace("DNS\nStatus: UNKNOWN\nServers:",
                        "DNS\nStatus: SAFE\nServers: 1.1.1.1");
        Map<String, Object> leaked = FingerprintMappingValidation.validate(
                "browserscan", "BrowserScan", "https://browserscan.org/",
                leak, leak, "", browserScanExpected(false), domEvidence("browserscan"));
        assertEquals("forbidden-signal", leaked.get("status"), leaked.toString());

        String moderate = browserScanUnknownBody()
                .replace("98\nA+\nSTATUS\nLow Risk", "82\nB\nSTATUS\nModerate Risk");
        Map<String, Object> moderateResult = FingerprintMappingValidation.validate(
                "browserscan", "BrowserScan", "https://browserscan.org/",
                moderate, moderate, "", browserScanExpected(false), domEvidence("browserscan"));
        assertEquals("forbidden-signal", moderateResult.get("status"), moderateResult.toString());
    }

    @Test
    void browserScanRejectsSafeDnsStatusWithoutAResolverResult() {
        String body = FingerprintMappingSiteBodies.browserscanBody()
                .replace("Status\nSAFE\nServers\n1.1.1.1", "Status\nSAFE\nServers\n");

        Map<String, Object> result = FingerprintMappingValidation.validate(
                "browserscan", "BrowserScan", "https://browserscan.org/",
                body, body, "", Map.of(
                        "webRtc.publicIp", "203.0.113.210",
                        "browserscan.dns.Status", "SAFE"), domEvidence("browserscan"));

        assertEquals("forbidden-signal", result.get("status"), result.toString());
        @SuppressWarnings("unchecked")
        Map<String, Object> expected = (Map<String, Object>) result.get("expected");
        assertTrue(((java.util.List<?>) expected.get("mismatches")).contains(
                "actual-missing:browserscan.dns.Servers"));
    }

    @Test
    void browserScanExtractsInlineWebRtcAndDnsReportValues() {
        String body = """
                WEBRTC
                Status: SAFE
                IP: 203.0.113.10
                Region: BR
                DNS
                Status: SAFE
                Servers: 1.1.1.1
                CONSISTENCY MATRIX
                """;

        Map<String, Object> extracted = FingerprintMappingPixelScanExtractor.extract("browserscan", body);

        assertEquals(Map.of(
                "Status", "SAFE",
                "IP", "203.0.113.10",
                "Region", "BR"), extracted.get("webrtc"));
        assertEquals(Map.of(
                "Status", "SAFE",
                "Servers", "1.1.1.1"), extracted.get("dns"));
    }

    @Test
    void browserScanDoesNotAcceptAnArbitraryDnsPlaceholderAsAResolver() {
        String body = browserScanUnknownBody()
                .replace("WEBRTC\nStatus: UNKNOWN\nIP:\nRegion:",
                        "WEBRTC\nStatus: SAFE\nIP: 203.0.113.10\nRegion: BR")
                .replace("DNS\nStatus: UNKNOWN\nServers:",
                        "DNS\nStatus: SAFE\nServers: resolver-present");

        Map<String, Object> result = FingerprintMappingValidation.validate(
                "browserscan", "BrowserScan", "https://browserscan.org/",
                body, body, "", browserScanExpected(false), domEvidence("browserscan"));

        assertEquals("value-mismatch", result.get("status"), result.toString());
        @SuppressWarnings("unchecked")
        Map<String, Object> expected = (Map<String, Object>) result.get("expected");
        assertTrue(((java.util.List<?>) expected.get("mismatches")).contains(
                "actual-missing:browserscan.dns.Servers"));
    }

    @Test
    void browserScanRejectsInvalidOrNonPublicDnsAddressLiterals() {
        for (String address : java.util.List.of(":", "::", "::1", "127.0.0.1", "10.0.0.53")) {
            String body = browserScanUnknownBody()
                    .replace("WEBRTC\nStatus: UNKNOWN\nIP:\nRegion:",
                            "WEBRTC\nStatus: SAFE\nIP: 203.0.113.10\nRegion: BR")
                    .replace("DNS\nStatus: UNKNOWN\nServers:",
                            "DNS\nStatus: SAFE\nServers: " + address);

            Map<String, Object> result = FingerprintMappingValidation.validate(
                    "browserscan", "BrowserScan", "https://browserscan.org/",
                    body, body, "", browserScanExpected(false), domEvidence("browserscan"));

            assertEquals("value-mismatch", result.get("status"), address + " -> " + result);
        }
    }

    @Test
    void browserLeaksIpDoesNotInferTransportOsFromClientHintPlatform() {
        String body = """
                What Is My IP Address
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
                OS\tAndroid
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
        assertEquals(java.util.List.of(), result.get("blockers"));
        assertEquals("Android", ((Map<?, ?>) ((Map<?, ?>) result.get("extracted")).get("tcp")).get("os"));

        Map<String, String> matchingTransport = new java.util.LinkedHashMap<>(expected);
        matchingTransport.put("run.transportOs", "Android");
        Map<String, Object> matched = FingerprintMappingValidation.validate(
                "browserleaks-ip", "IP Address - BrowserLeaks", "https://browserleaks.com/ip",
                body, body, "", matchingTransport, domEvidence("browserleaks-ip"));
        assertEquals("pass", matched.get("status"), matched.toString());

        Map<String, String> mismatchingTransport = new java.util.LinkedHashMap<>(expected);
        mismatchingTransport.put("run.transportOs", "Linux");
        Map<String, Object> mismatched = FingerprintMappingValidation.validate(
                "browserleaks-ip", "IP Address - BrowserLeaks", "https://browserleaks.com/ip",
                body, body, "", mismatchingTransport, domEvidence("browserleaks-ip"));
        assertEquals("value-mismatch", mismatched.get("status"), mismatched.toString());
        assertTrue(String.valueOf(((Map<?, ?>) mismatched.get("expected")).get("mismatches"))
                .contains("browserleaks-ip.tcp.os"));
    }

}
