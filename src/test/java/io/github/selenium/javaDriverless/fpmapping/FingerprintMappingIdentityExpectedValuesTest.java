package io.github.selenium.javaDriverless.fpmapping;

import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

class FingerprintMappingIdentityExpectedValuesTest {
    private static final String UA =
            "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) "
                    + "Chrome/150.0.0.0 Safari/537.36";

    @Test
    void browserLeaksJavaScriptComparesProfileValues() {
        Map<String, Object> result = validate(
                "browserleaks-javascript", browserLeaksJavaScriptBody("8"), expected(),
                "JavaScript Browser Information - BrowserLeaks", "https://browserleaks.com/javascript");

        assertEquals("pass", result.get("status"), result.toString());
        assertEquals(19, result.get("matchedCount"));
    }

    @Test
    void browserLeaksJavaScriptRejectsHardwareMismatch() {
        Map<String, Object> result = validate(
                "browserleaks-javascript", browserLeaksJavaScriptBody("16"), expected(),
                "JavaScript Browser Information - BrowserLeaks", "https://browserleaks.com/javascript");

        assertEquals("value-mismatch", result.get("status"), result.toString());
    }

    @Test
    void browserLeaksClientHintsComparesJavaScriptAndHttpSurfaces() {
        Map<String, Object> result = validate(
                "browserleaks-client-hints", clientHintsBody("Linux"), expected(),
                "Client Hints - BrowserLeaks", "https://browserleaks.com/client-hints");

        assertEquals("pass", result.get("status"), result.toString());
        assertEquals(41, result.get("matchedCount"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void browserLeaksClientHintExtractorHandlesLiveTableTabsAndWrappedJson() {
        String body = """
                Your Web Browser
                HTTP User-Agent\t%s
                Client Hints JavaScript API
                API Support\tTrue
                brands\t[{"brand":"Not(A:Brand","version":"99"},
                {"brand":"Google Chrome","version":"150"},
                {"brand":"Chromium","version":"150"}]
                mobile\tfalse
                platform\tLinux
                platformVersion\t
                architecture\tx86
                bitness\t64
                wow64\tfalse
                model\t
                uaFullVersion\t150.0.0.0
                fullVersionList\t[{"brand":"Not(A:Brand","version":"99.0.0.0"},
                {"brand":"Google Chrome","version":"150.0.0.0"},
                {"brand":"Chromium","version":"150.0.0.0"}]
                formFactors\t["Desktop"]
                Client Hints HTTP Headers
                Sec-CH-UA\t"Not(A:Brand";v="99"
                """.formatted(UA);

        Map<String, Object> extracted = FingerprintMappingPixelScanExtractor.extract(
                "browserleaks-client-hints", body);
        Map<String, String> javascript = (Map<String, String>) extracted.get("javascript");

        assertEquals("[{\"brand\":\"Not(A:Brand\",\"version\":\"99\"},{\"brand\":\"Google Chrome\",\"version\":\"150\"},{\"brand\":\"Chromium\",\"version\":\"150\"}]",
                javascript.get("brands"));
        assertEquals("false", javascript.get("mobile"));
        assertEquals("150.0.0.0", javascript.get("uaFullVersion"));
        assertEquals("[\"Desktop\"]", javascript.get("formFactors"));
    }

    @Test
    void browserLeaksClientHintsRejectsHttpPlatformMismatch() {
        Map<String, Object> result = validate(
                "browserleaks-client-hints", clientHintsBody("Windows"), expected(),
                "Client Hints - BrowserLeaks", "https://browserleaks.com/client-hints");

        assertEquals("value-mismatch", result.get("status"), result.toString());
    }

    @Test
    void browserLeaksClientHintsRejectsLegacyDeviceMemoryLeak() {
        String body = clientHintsBody("Linux").replace(
                "Device-Memory\n8\nSec-CH-Viewport-Width",
                "Device-Memory\n32\nSec-CH-Viewport-Width");

        Map<String, Object> result = validate(
                "browserleaks-client-hints", body, expected(),
                "Client Hints - BrowserLeaks", "https://browserleaks.com/client-hints");

        assertEquals("value-mismatch", result.get("status"), result.toString());
    }

    @Test
    void browserLeaksClientHintsRejectsHostColorSchemeLeak() {
        String body = clientHintsBody("Linux").replace(
                "Sec-CH-Prefers-Color-Scheme\nlight",
                "Sec-CH-Prefers-Color-Scheme\ndark");

        Map<String, Object> result = validate(
                "browserleaks-client-hints", body, expected(),
                "Client Hints - BrowserLeaks", "https://browserleaks.com/client-hints");

        assertEquals("value-mismatch", result.get("status"), result.toString());
    }

    @Test
    void whatsMyBrowserComparesVersionOsIpLanguageScreenAndUa() {
        Map<String, Object> result = validate(
                "whatsmybrowser", whatsMyBrowserBody("203.0.113.10"), expected(),
                "What's My Browser", "https://www.whatsmybrowser.org/");

        assertEquals("pass", result.get("status"), result.toString());
        assertEquals(6, result.get("matchedCount"));
    }

    @Test
    void whatsMyBrowserWithoutExpectedPublicIpDoesNotPassStructurally() {
        Map<String, String> noEgressIp = new LinkedHashMap<>(expected());
        noEgressIp.remove("webRtc.publicIp");

        Map<String, Object> result = validate(
                "whatsmybrowser", whatsMyBrowserBody("203.0.113.10"), noEgressIp,
                "What's My Browser", "https://www.whatsmybrowser.org/");

        assertEquals("expected-missing", result.get("status"), result.toString());
    }

    private static Map<String, Object> validate(
            String id, String body, Map<String, String> expected, String title, String url) {
        return FingerprintMappingValidation.validate(
                id, title, url, body, body, "", expected, domEvidence(id));
    }

    private static Map<String, String> expected() {
        return Map.ofEntries(
                Map.entry("userAgent", UA),
                Map.entry("browserVersion", "150.0.0.0"),
                Map.entry("browserMajor", "150"),
                Map.entry("clientHints.brands", "[{\"brand\":\"Not(A:Brand\",\"version\":\"99\"},{\"brand\":\"Google Chrome\",\"version\":\"150\"},{\"brand\":\"Chromium\",\"version\":\"150\"}]"),
                Map.entry("clientHints.fullVersionList", "[{\"brand\":\"Not(A:Brand\",\"version\":\"99.0.0.0\"},{\"brand\":\"Google Chrome\",\"version\":\"150.0.0.0\"},{\"brand\":\"Chromium\",\"version\":\"150.0.0.0\"}]"),
                Map.entry("clientHints.uaHeader", "\"Not(A:Brand\";v=\"99\", \"Google Chrome\";v=\"150\", \"Chromium\";v=\"150\""),
                Map.entry("clientHints.fullVersionListHeader", "\"Not(A:Brand\";v=\"99.0.0.0\", \"Google Chrome\";v=\"150.0.0.0\", \"Chromium\";v=\"150.0.0.0\""),
                Map.entry("clientHints.formFactors", "[\"Desktop\"]"),
                Map.entry("clientHints.formFactorsHeader", "\"Desktop\""),
                Map.entry("clientHints.notReceived", "not received"),
                Map.entry("locale", "en-US"),
                Map.entry("run.timezone", "America/Sao_Paulo"),
                Map.entry("platform", "Linux x86_64"),
                Map.entry("uaPlatform", "Linux"),
                Map.entry("architecture", "x86"),
                Map.entry("bitness", "64"),
                Map.entry("mobile", "false"),
                Map.entry("platformVersion", "empty"),
                Map.entry("model", "empty"),
                Map.entry("wow64", "false"),
                Map.entry("hardwareConcurrency", "8"),
                Map.entry("deviceMemory", "8"),
                Map.entry("colorScheme", "light"),
                Map.entry("prefersReducedMotion", "no-preference"),
                Map.entry("prefersReducedTransparency", "no-preference"),
                Map.entry("prefersContrast", "no-preference"),
                Map.entry("forcedColors", "none"),
                Map.entry("connection.rtt", "50"),
                Map.entry("connection.downlink", "10"),
                Map.entry("connection.effectiveType", "4g"),
                Map.entry("viewport.width", "1365"),
                Map.entry("viewport.height", "765"),
                Map.entry("deviceScaleFactor", "1"),
                Map.entry("screen.resolution", "1365x900"),
                Map.entry("webRtc.publicIp", "203.0.113.10"));
    }

    private static Map<String, Object> domEvidence(String id) {
        FingerprintSiteContracts.Site contract = FingerprintSiteContracts.site(id);
        Map<String, Object> evidence = new LinkedHashMap<>();
        for (FingerprintSiteContracts.RequiredDom field : contract.requiredDom) {
            evidence.put(field.id, Map.of(
                    "matched", true,
                    "tag", "div",
                    "selector", field.selectors.isEmpty() ? "#fixture" : field.selectors.get(0),
                    "text", field.terms.get(0)));
        }
        return evidence;
    }

    private static String browserLeaksJavaScriptBody(String hardwareConcurrency) {
        return """
                JavaScript Browser Information
                JavaScript Detection
                JavaScript Enabled True
                Screen Object
                Screen Resolution
                1365×900 16:9 24-bit TrueColor
                Date/Time
                Internationalization API
                locale
                en-US
                timeZone
                America/Sao_Paulo
                hourCycle
                h23
                calendar
                gregory
                numberingSystem
                latn
                Navigator Object
                userAgent
                %s
                platform
                Linux x86_64
                hardwareConcurrency
                %s
                deviceMemory
                8
                language
                en-US
                languages
                ["en-US","en"]
                webdriver
                false
                maxTouchPoints
                0
                navigator.userAgentData (Client Hints)
                brands
                [{"brand":"Not(A:Brand","version":"99"},
                {"brand":"Google Chrome","version":"150"},
                {"brand":"Chromium","version":"150"}]
                mobile
                false
                platform
                Linux
                platformVersion
                empty
                architecture
                x86
                bitness
                64
                wow64
                false
                model
                empty
                uaFullVersion
                150.0.0.0
                fullVersionList
                [{"brand":"Not(A:Brand","version":"99.0.0.0"},
                {"brand":"Google Chrome","version":"150.0.0.0"},
                {"brand":"Chromium","version":"150.0.0.0"}]
                formFactors
                ["Desktop"]
                navigator.plugins
                plugins PDF Viewer
                mimeTypes application/pdf
                report report report report report report report report report report
                """.formatted(UA, hardwareConcurrency);
    }

    private static String clientHintsBody(String httpPlatform) {
        return """
                Client Hints
                Your Web Browser
                HTTP User-Agent
                %s
                Client Hints JavaScript API
                API Support True
                brands
                [{"brand":"Not(A:Brand","version":"99"},
                {"brand":"Google Chrome","version":"150"},
                {"brand":"Chromium","version":"150"}]
                mobile
                false
                platform
                Linux
                platformVersion
                empty
                architecture
                x86
                bitness
                64
                wow64
                false
                model
                empty
                uaFullVersion
                150.0.0.0
                fullVersionList
                [{"brand":"Not(A:Brand","version":"99.0.0.0"},
                {"brand":"Google Chrome","version":"150.0.0.0"},
                {"brand":"Chromium","version":"150.0.0.0"}]
                formFactors
                ["Desktop"]
                Client Hints HTTP Headers
                Sec-CH-UA
                "Not(A:Brand";v="99", "Google Chrome";v="150", "Chromium";v="150"
                Sec-CH-UA-Platform
                "%s"
                Sec-CH-UA-Mobile
                ?0
                Sec-CH-UA-Full-Version
                "150.0.0.0"
                Sec-CH-UA-Full-Version-List
                "Not(A:Brand";v="99.0.0.0", "Google Chrome";v="150.0.0.0", "Chromium";v="150.0.0.0"
                Sec-CH-UA-Platform-Version
                ""
                Sec-CH-UA-Arch
                "x86"
                Sec-CH-UA-Bitness
                "64"
                Sec-CH-UA-WoW64
                ?0
                Sec-CH-UA-Model
                ""
                Sec-CH-UA-Form-Factors
                "Desktop"
                Sec-CH-Lang
                not received
                Sec-CH-Save-Data
                not received
                Sec-CH-Width
                not received
                Sec-CH-Device-Memory
                8
                Device-Memory
                8
                Sec-CH-Viewport-Width
                1365
                Viewport-Width
                1365
                Sec-CH-Viewport-Height
                765
                Sec-CH-DPR
                1
                DPR
                1
                Sec-CH-RTT
                not received
                RTT
                50
                Sec-CH-Downlink
                not received
                Downlink
                10
                Sec-CH-ECT
                not received
                ECT
                4g
                Sec-CH-Prefers-Color-Scheme
                light
                Sec-CH-Prefers-Reduced-Motion
                no-preference
                Sec-CH-Prefers-Reduced-Transparency
                no-preference
                Sec-CH-Prefers-Contrast
                not received
                Sec-CH-Forced-Colors
                not received
                report report report report report report report report report report
                """.formatted(UA, httpPlatform);
    }

    private static String whatsMyBrowserBody(String publicIp) {
        return """
                What's My Browser
                You’re using Chrome 150.
                Browser details
                Operating system
                Linux
                IP address
                %s
                Javascript enabled
                Yes
                Cookies enabled
                Yes
                Websockets supported
                Yes
                WebGL supported
                Yes
                Language
                en-US
                Browser size
                1350 x 765
                Screen size
                1365 x 900
                Your full user agent string is:
                %s
                What browser am I using?
                You’re using Chrome 150.
                report report report report report report report report report report report
                """.formatted(publicIp, UA);
    }
}
