package io.github.selenium.javaDriverless.fpmapping;

import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FingerprintMappingSecondarySitesValidationTest extends FingerprintMappingValidationSupport {
@Test
    void browserLeaksWebrtcDoesNotAcceptLabelsWithoutResult() {
        String body = "WebRTC Leak Test\nLocal IP Address\nPublic IP Address\n" + "report ".repeat(50);
        Map<String, Object> result = FingerprintMappingValidation.validate(
                "browserleaks-webrtc", "WebRTC Leak Test - BrowserLeaks",
                "https://browserleaks.com/webrtc", body, body, "", Map.of(),
                domEvidence("browserleaks-webrtc"));

        assertEquals("contract-broken", result.get("status"));
    }

    @Test
    void sannysoftDoesNotAcceptAnEmptyResultsTable() {
        String body = "Intoli.com tests + additions\nWebDriver\nChrome\nPermissions\n" + "test ".repeat(60);
        Map<String, Object> result = FingerprintMappingValidation.validate(
                "sannysoft", "Antibot", "https://bot.sannysoft.com", body, body,
                "", Map.of(), domEvidence("sannysoft"));

        assertEquals("contract-broken", result.get("status"));
    }

    @Test
    void phase8ContractsCatchSecondarySiteStates() {
        Map<String, Object> checkup = FingerprintMappingValidation.validate(
                "checkup-000r", "000rWeb", "https://checkup.000r.ovh/",
                FingerprintMappingSiteBodies.checkup000rBody(), FingerprintMappingSiteBodies.checkup000rBody(), "", Map.of());
        assertEquals("pass", checkup.get("status"));
        assertEquals(10, checkup.get("presentCount"));

        Map<String, Object> browsercheckClean = FingerprintMappingValidation.validate(
                "browsercheck", "Browser Check", "https://browsercheck.net/",
                FingerprintMappingSiteBodies.browsercheckBody("Good (Authentic)", "No risks detected", "Bot Detection\nNo"),
                FingerprintMappingSiteBodies.browsercheckBody("Good (Authentic)", "No risks detected", "Bot Detection\nNo"), "",
                browserCheckExpected());
        assertEquals("pass", browsercheckClean.get("status"));
        assertEquals(12, browsercheckClean.get("presentCount"));

        Map<String, Object> browsercheckRed = FingerprintMappingValidation.validate(
                "browsercheck", "Browser Check", "https://browsercheck.net/",
                FingerprintMappingSiteBodies.browsercheckBody("Suspicious / Manipulated", "1 privacy risks detected",
                        "Automation/Bot Detected\nNavigator.webdriver flag is enabled.\nFix:Use a stealth driver"),
                FingerprintMappingSiteBodies.browsercheckBody("Suspicious / Manipulated", "1 privacy risks detected",
                        "Automation/Bot Detected\nNavigator.webdriver flag is enabled.\nFix:Use a stealth driver"), "", Map.of());
        assertEquals("forbidden-signal", browsercheckRed.get("status"));

        Map<String, Object> browsercheckTimezoneMismatch = FingerprintMappingValidation.validate(
                "browsercheck", "Browser Check", "https://browsercheck.net/",
                FingerprintMappingSiteBodies.browsercheckBody("Good (Authentic)", "No risks detected", "Bot Detection\nNo")
                        .replace("System Timezone\nAmerica/Sao_Paulo", "System Timezone\nAmerica/New_York"),
                FingerprintMappingSiteBodies.browsercheckBody("Good (Authentic)", "No risks detected", "Bot Detection\nNo"), "",
                browserCheckExpected());
        assertEquals("value-mismatch", browsercheckTimezoneMismatch.get("status"));

        Map<String, Object> timezone = FingerprintMappingValidation.validate(
                "antidetect-timezone", "Antidetect", "https://antidetect.net/timezone-checker.html",
                FingerprintMappingSiteBodies.antidetectTimezoneBody(), FingerprintMappingSiteBodies.antidetectTimezoneBody(), "",
                Map.of("run.timezone", "America/Sao_Paulo"));
        assertEquals("site-unresolved", timezone.get("status"), timezone.toString());

        Map<String, Object> trustmyipTimezone = FingerprintMappingValidation.validate(
                "trustmyip-timezone", "TrustMyIP", "https://trustmyip.com/timezone-dissonance",
                FingerprintMappingSiteBodies.trustmyipTimezoneUnresolvedBody(),
                FingerprintMappingSiteBodies.trustmyipTimezoneUnresolvedBody(), "",
                trustMyIpExpected("America/Sao_Paulo"));
        assertEquals("site-unresolved", trustmyipTimezone.get("status"));
        assertEquals(5, trustmyipTimezone.get("presentCount"));

        Map<String, Object> trustmyipTimezoneMatch = FingerprintMappingValidation.validate(
                "trustmyip-timezone", "TrustMyIP", "https://trustmyip.com/timezone-dissonance",
                FingerprintMappingSiteBodies.trustmyipTimezoneMatchBody(),
                FingerprintMappingSiteBodies.trustmyipTimezoneMatchBody(), "",
                trustMyIpExpected("Europe/Berlin"));
        assertEquals("pass", trustmyipTimezoneMatch.get("status"));
        assertEquals(5, trustmyipTimezoneMatch.get("presentCount"));
        assertEquals("expected-values", trustmyipTimezoneMatch.get("coverageLevel"));
        assertEquals(4, trustmyipTimezoneMatch.get("matchedCount"));

        String nowsecureBody = "NOWSECURE\nBY NODRIVER";
        String nowsecureHtml = """
                <html><head><title>nowsecure.nl</title>
                <script src="https://challenges.cloudflare.com/turnstile/v0/api.js"></script>
                </head><body><h2>NOWSECURE</h2><h3>by <span>nodriver</span></h3>
                <div class="cf-turnstile" data-sitekey="3x00000000000000000000FF"></div>
                </body></html>
                """;
        Map<String, Object> nowsecure = FingerprintMappingValidation.validate(
                "nowsecure", "nowsecure.nl", "https://nowsecure.nl",
                nowsecureBody, nowsecureHtml, "", Map.of());
        assertEquals("pass", nowsecure.get("status"));
        assertEquals(2, nowsecure.get("presentCount"));

        String webglReportBody = """
                WebGL Report
                WebGL 1 WebGL 2
                This browser supports WebGL 1
                Context Name: webgl
                GL Version: WebGL 1.0 (OpenGL ES 2.0 Chromium)
                Shading Language Version: WebGL GLSL ES 1.0
                Vendor: WebKit
                Renderer: WebKit WebGL
                Unmasked Vendor: Google Inc. (Intel)
                Unmasked Renderer: ANGLE (Intel, Mesa Intel UHD Graphics, OpenGL 4.6)
                Max Vertex Texture Image Units: 32
                Max Texture Size: 8192
                Best float precision: [-2127, 2127] (23)
                float/int precision: highp/highp
                Supported Extensions:
                WEBGL_debug_renderer_info
                """;
        Map<String, Object> webglReport = FingerprintMappingValidation.validate(
                "webgl-report", "WebGL Report", "https://webglreport.com/",
                webglReportBody, webglReportBody, "", Map.of(
                        "webgl-report.vendor", "Google Inc. (Intel)",
                        "webgl-report.renderer", "ANGLE (Intel, Mesa Intel UHD Graphics, OpenGL 4.6)"));
        assertEquals("pass", webglReport.get("status"));
        assertEquals(8, webglReport.get("presentCount"));

        Map<String, Object> coveryourtracksStuck = FingerprintMappingValidation.validate(
                "coveryourtracks", "Cover Your Tracks", "https://coveryourtracks.eff.org/",
                FingerprintMappingSiteBodies.coveryourtracksStuckBody(), FingerprintMappingSiteBodies.coveryourtracksStuckBody(), "", Map.of());
        assertEquals("contract-broken", coveryourtracksStuck.get("status"));

        Map<String, Object> amiunique = FingerprintMappingValidation.validate(
                "amiunique", "AmIUnique", "https://amiunique.org/fingerprint",
                FingerprintMappingSiteBodies.amiuniqueBody(), FingerprintMappingSiteBodies.amiuniqueBody(), "", Map.of());
        assertEquals("pass", amiunique.get("status"));
        assertEquals(12, amiunique.get("presentCount"));
    }

    private static Map<String, String> browserCheckExpected() {
        return Map.of(
                "browsercheck.realness", "Good (Authentic)",
                "browsercheck.proxyDetected", "No",
                "browsercheck.automationDetected", "No",
                "webRtc.allowedIps", "203.0.113.10",
                "run.timezone", "America/Sao_Paulo");
    }

    @Test
    void browserCheckRejectsPublicIpOutsideTheMeasuredEgressAllowlist() {
        String body = FingerprintMappingSiteBodies.browsercheckBody(
                "Good (Authentic)", "No risks detected", "Bot Detection\nNo")
                .replace("203.0.113.10", "198.51.100.20");

        Map<String, Object> result = FingerprintMappingValidation.validate(
                "browsercheck", "Browser Check", "https://browsercheck.net/",
                body, body, "", browserCheckExpected());

        assertEquals("value-mismatch", result.get("status"), result.toString());
    }

    @Test
    void browserCheckRejectsEitherIpOrSystemTimezoneMismatch() {
        String matching = FingerprintMappingSiteBodies.browsercheckBody(
                "Good (Authentic)", "No risks detected", "Bot Detection\nNo");
        String ipMismatch = matching.replaceFirst(
                "IP Timezone\\s+America/Sao_Paulo", "IP Timezone\nAmerica/New_York");
        String systemMismatch = matching.replaceFirst(
                "System Timezone\\s+America/Sao_Paulo", "System Timezone\nAmerica/New_York");

        Map<String, Object> ipResult = FingerprintMappingValidation.validate(
                "browsercheck", "Browser Check", "https://browsercheck.net/",
                ipMismatch, ipMismatch, "", browserCheckExpected());
        Map<String, Object> systemResult = FingerprintMappingValidation.validate(
                "browsercheck", "Browser Check", "https://browsercheck.net/",
                systemMismatch, systemMismatch, "", browserCheckExpected());

        assertEquals("value-mismatch", ipResult.get("status"), ipResult.toString());
        assertEquals("value-mismatch", systemResult.get("status"), systemResult.toString());
    }

    @Test
    void browserCheckRejectsProxyDetectedYesWithoutAnyOtherRedSignal() {
        String body = FingerprintMappingSiteBodies.browsercheckBody(
                "Good (Authentic)", "No risks detected", "Bot Detection\nNo")
                .replace("Proxy Detected\nNo", "Proxy Detected\nYes");

        Map<String, Object> result = FingerprintMappingValidation.validate(
                "browsercheck", "Browser Check", "https://browsercheck.net/",
                body, body, "", browserCheckExpected());

        assertEquals("forbidden-signal", result.get("status"), result.toString());
    }

    @Test
    void browserCheckAutomationLabelWithNoIsNotAForbiddenSignal() {
        String body = FingerprintMappingSiteBodies.browsercheckBody(
                "Good (Authentic)", "No risks detected", "Automation/Bot Detected\nNo");

        Map<String, Object> result = FingerprintMappingValidation.validate(
                "browsercheck", "Browser Check", "https://browsercheck.net/",
                body, body, "", browserCheckExpected());

        assertEquals("pass", result.get("status"), result.toString());
    }

    @Test
    void browserCheckAutomationYesIsRejectedFromStructuredVerdict() {
        String body = FingerprintMappingSiteBodies.browsercheckBody(
                "Good (Authentic)", "No risks detected", "Automation/Bot Detected\nYes");

        Map<String, Object> result = FingerprintMappingValidation.validate(
                "browsercheck", "Browser Check", "https://browsercheck.net/",
                body, body, "", browserCheckExpected());

        assertEquals("forbidden-signal", result.get("status"), result.toString());
    }

    @Test
    void browserCheckRejectsUnknownRealnessAndProxyVerdicts() {
        String matching = FingerprintMappingSiteBodies.browsercheckBody(
                "Good (Authentic)", "No risks detected", "Bot Detection\nNo");
        String unknownRealness = matching.replace("Good (Authentic)", "Unknown");
        String unknownProxy = matching.replace("Proxy Detected\nNo", "Proxy Detected\nUnknown");

        Map<String, Object> realnessResult = FingerprintMappingValidation.validate(
                "browsercheck", "Browser Check", "https://browsercheck.net/",
                unknownRealness, unknownRealness, "", browserCheckExpected());
        Map<String, Object> proxyResult = FingerprintMappingValidation.validate(
                "browsercheck", "Browser Check", "https://browsercheck.net/",
                unknownProxy, unknownProxy, "", browserCheckExpected());

        assertEquals("value-mismatch", realnessResult.get("status"), realnessResult.toString());
        assertEquals("value-mismatch", proxyResult.get("status"), proxyResult.toString());
    }

    @Test
    void antidetectTimezoneRequiresBothLiveTimezonesToMatchTheProfile() {
        String body = """
                Timezone Consistency Mismatch & Check IP
                Browser Timezone
                America/New_York
                UTC -4
                IP Location
                Clifton, United States
                America/New_York
                Perfect Match! Your browser timezone matches your IP location. No red flags detected.
                What This Tool Tests
                Browser timezone
                System time offset
                IP geolocation timezone
                UTC offset consistency
                Why Timezone Consistency Matters
                Proxy/VPN masking errors
                How It Works
                checks your public IP timezone
                """;
        Map<String, Object> result = FingerprintMappingValidation.validate(
                "antidetect-timezone", "Antidetect Timezone",
                "https://antidetect.net/timezone-checker.html", body, body, "",
                Map.of("run.timezone", "America/New_York"), domEvidence("antidetect-timezone"));

        assertEquals("pass", result.get("status"));
        assertEquals("expected-values", result.get("coverageLevel"));
        assertEquals(2, result.get("matchedCount"));

        Map<String, Object> mismatch = FingerprintMappingValidation.validate(
                "antidetect-timezone", "Antidetect Timezone",
                "https://antidetect.net/timezone-checker.html",
                body.replaceFirst("America/New_York", "America/Chicago"), body, "",
                Map.of("run.timezone", "America/New_York"), domEvidence("antidetect-timezone"));
        Map<String, Object> ipMismatch = FingerprintMappingValidation.validate(
                "antidetect-timezone", "Antidetect Timezone",
                "https://antidetect.net/timezone-checker.html",
                body.replace("Clifton, United States\nAmerica/New_York",
                        "Clifton, United States\nAmerica/Chicago"), body, "",
                Map.of("run.timezone", "America/New_York"), domEvidence("antidetect-timezone"));

        assertEquals("value-mismatch", mismatch.get("status"));
        assertEquals("value-mismatch", ipMismatch.get("status"));
    }

    @Test
    void antidetectTimezoneRejectsANegativeRenderedStatus() {
        String body = """
                Timezone Consistency Mismatch & Check IP
                Browser Timezone
                America/New_York
                UTC -4
                IP Location
                Clifton, United States
                America/New_York
                Timezone Mismatch Detected - strong indicator of VPN/proxy usage
                What This Tool Tests
                Browser timezone
                System time offset
                IP geolocation timezone
                UTC offset consistency
                Why Timezone Consistency Matters
                Proxy/VPN masking errors
                How It Works
                checks your public IP timezone
                """;
        Map<String, Object> result = FingerprintMappingValidation.validate(
                "antidetect-timezone", "Antidetect Timezone",
                "https://antidetect.net/timezone-checker.html", body, body, "",
                Map.of("run.timezone", "America/New_York"), domEvidence("antidetect-timezone"));

        assertEquals("forbidden-signal", result.get("status"));
    }

    @Test
    void trustMyIpTimezoneRejectsEitherTimezoneMismatch() {
        String matching = FingerprintMappingSiteBodies.trustmyipTimezoneMatchBody();
        Map<String, String> expected = trustMyIpExpected("Europe/Berlin");

        Map<String, Object> ipMismatch = FingerprintMappingValidation.validate(
                "trustmyip-timezone", "TrustMyIP Timezone",
                "https://trustmyip.com/timezone-dissonance",
                matching.replaceFirst("Europe/Berlin", "America/New_York"), matching, "",
                expected, domEvidence("trustmyip-timezone"));
        Map<String, Object> browserMismatch = FingerprintMappingValidation.validate(
                "trustmyip-timezone", "TrustMyIP Timezone",
                "https://trustmyip.com/timezone-dissonance",
                matching.replaceFirst(
                        "BROWSER TIMEZONE\\s+Europe/Berlin", "BROWSER TIMEZONE\nAmerica/New_York"),
                matching, "", expected, domEvidence("trustmyip-timezone"));

        assertEquals("value-mismatch", ipMismatch.get("status"));
        assertEquals("value-mismatch", browserMismatch.get("status"));
    }

    @Test
    void trustMyIpTimezoneRejectsRenderedDissonanceEvenWhenTimezonesMatch() {
        String body = FingerprintMappingSiteBodies.trustmyipTimezoneMatchBody()
                .replace("0 hours (Match)", "1 hour (Mismatch)")
                .replace("MATCH - No Dissonance", "MISMATCH - Minor Dissonance")
                .replace("[PASS] All temporal markers are synchronized. No timezone dissonance detected.",
                        "[WARNING] Minor timezone dissonance detected.");

        Map<String, Object> result = FingerprintMappingValidation.validate(
                "trustmyip-timezone", "TrustMyIP Timezone",
                "https://trustmyip.com/timezone-dissonance", body, body, "",
                trustMyIpExpected("Europe/Berlin"), domEvidence("trustmyip-timezone"));

        assertEquals("forbidden-signal", result.get("status"));
    }

    @Test
    void trustMyIpTimezoneDoesNotMatchTenHoursAsZeroHours() {
        String body = FingerprintMappingSiteBodies.trustmyipTimezoneMatchBody()
                .replace("0 hours (Match)", "10 hours (Mismatch)");

        Map<String, Object> result = FingerprintMappingValidation.validate(
                "trustmyip-timezone", "TrustMyIP Timezone",
                "https://trustmyip.com/timezone-dissonance", body, body, "",
                trustMyIpExpected("Europe/Berlin"), domEvidence("trustmyip-timezone"));

        assertEquals("value-mismatch", result.get("status"), result.toString());
    }

    @Test
    void trustMyIpTimezoneRejectsNegativeTextThatMerelyContainsNoDissonance() {
        String body = FingerprintMappingSiteBodies.trustmyipTimezoneMatchBody()
                .replace("MATCH - No Dissonance",
                        "WARNING - No Dissonance guarantee could be established");

        Map<String, Object> result = FingerprintMappingValidation.validate(
                "trustmyip-timezone", "TrustMyIP Timezone",
                "https://trustmyip.com/timezone-dissonance", body, body, "",
                trustMyIpExpected("Europe/Berlin"), domEvidence("trustmyip-timezone"));

        assertEquals("value-mismatch", result.get("status"), result.toString());
    }

    private static Map<String, String> trustMyIpExpected(String timezone) {
        return Map.of(
                "run.timezone", timezone,
                "trustmyip-timezone.offsetPattern", "^0\\s+hours?\\b",
                "trustmyip-timezone.resultPattern", "(?i)^MATCH\\s*[-—–]\\s*No\\s+Dissonance$");
    }

}
