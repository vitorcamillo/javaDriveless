package io.github.selenium.javaDriverless.types;

import io.github.selenium.javaDriverless.utils.UserAgentResolver;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/** Derives the cross-site expected-value oracle from a fingerprint profile. */
public final class FingerprintProfileExpectedValues {
    private FingerprintProfileExpectedValues() {
    }

    public static Map<String, String> derive(BrowserFingerprintProfile profile) {
        Map<String, String> expected = new LinkedHashMap<>();
        if (profile == null) {
            return expected;
        }
        profile.applyDefaults();
        UserAgentResolver.ensureBrowserVersion(profile);

        String userAgent = UserAgentResolver.buildForProfile(profile);
        String browserMajor = UserAgentResolver.extractChromeMajor(userAgent);
        expected.put("userAgent", userAgent);
        expected.put("browserMajor", browserMajor);
        String browserVersion = fullBrowserVersion(profile, userAgent);
        if (!browserVersion.isBlank()) {
            expected.put("browserVersion", browserVersion);
            if (!usesMinimalCdpPolicy(profile)) {
                expected.put("clientHints.brands", brandsJson(browserMajor));
                expected.put("clientHints.fullVersionList", fullVersionsJson(browserMajor, browserVersion));
                expected.put("clientHints.uaHeader", brandsHeader(browserMajor));
                expected.put("clientHints.fullVersionListHeader",
                        fullVersionsHeader(browserMajor, browserVersion));
            }
        }

        if (!usesMinimalCdpPolicy(profile)) {
            expected.put("platformVersion", blankAs(profile.platformVersion, "empty"));
        }
        expected.put("model", blankAs(profile.model, "empty"));
        expected.put("wow64", Boolean.toString(profile.wow64));
        if (profile.formFactors != null && !profile.formFactors.isEmpty()) {
            expected.put("clientHints.formFactors", profile.formFactors.stream()
                    .map(FingerprintProfileExpectedValues::jsonString)
                    .collect(Collectors.joining(",", "[", "]")));
            expected.put("clientHints.formFactorsHeader", profile.formFactors.stream()
                    .map(FingerprintProfileExpectedValues::jsonString)
                    .collect(Collectors.joining(", ")));
        }
        putIfText(expected, "locale", profile.locale);
        expected.put("http.acceptLanguage",
                FingerprintCdpOverrides.acceptLanguageHttpHeader(profile.languages));
        putIfText(expected, "architecture", profile.architecture);
        putIfText(expected, "bitness", profile.bitness);
        expected.put("mobile", Boolean.toString(profile.mobile));
        putIfText(expected, "platform", profile.platform);
        putIfText(expected, "run.timezone", profile.timezoneId);
        putIfText(expected, "uaPlatform", blankAs(profile.uaPlatform, profile.platform));
        expected.put("hardwareConcurrency", Integer.toString(profile.hardwareConcurrency));
        expected.put("deviceMemory", Integer.toString(profile.deviceMemory));
        putIfText(expected, "colorScheme", profile.colorScheme);
        putIfText(expected, "prefersReducedMotion", profile.prefersReducedMotion);
        putIfText(expected, "prefersReducedTransparency", profile.prefersReducedTransparency);
        putIfText(expected, "prefersContrast", profile.prefersContrast);
        putIfText(expected, "forcedColors", profile.forcedColors);
        expected.put("clientHints.notReceived", "not received");
        expected.put("pixelscan.verdict", "Your Browser Fingerprint is consistent");
        expected.put("pixelscan.browserCard", "Chrome " + browserMajor);
        expected.put("pixelscan.proxyCard", "No proxy detected");
        expected.put("pixelscan.fingerprintCard", "No masking detected");
        expected.put("pixelscan.botCard", "No automated behavior detected");
        expected.put("creepjs.canvas.renderingPattern", "^(?:0%\\s+rgba\\s+noise|none)$");
        expected.put("browserscan.webrtc.Status", "SAFE");
        expected.put("browserscan.dns.Status", "SAFE");
        expected.put("browserleaks-dns.testResultsPattern", "Found\\s+[1-9]\\d*\\s+Servers?");
        expected.put("browserleaks-dns.positiveCountPattern", "^[1-9]\\d*$");
        expected.put("browserleaks-dns.countConsistency", "matched");
        expected.put("trustmyip-timezone.offsetPattern", "^0\\s+hours?\\b");
        expected.put("trustmyip-timezone.resultPattern",
                "(?i)^MATCH\\s*[-—–]\\s*No\\s+Dissonance$");
        expected.put("iphey.verdict", "Your Digital Identity Looks Trustworthy");
        expected.put("iphey.bot.booleanPattern", "^(?:true|false)$");
        expected.put("iphey.bot.hasWebdriver", "false");
        expected.put("browsercheck.realness", "Good (Authentic)");
        expected.put("browsercheck.proxyDetected", "No");
        expected.put("browsercheck.automationDetected", "No");

        if (isOverridden(profile.screen == null ? null : profile.screen.mode)) {
            String resolution = profile.screen.width + "x" + profile.screen.height;
            expected.put("screen.resolution", resolution);
            expected.put("viewport.width", Integer.toString(profile.screen.width));
            expected.put("deviceScaleFactor", decimal(profile.screen.deviceScaleFactor));
            expected.put("pixelscan.screen.Resolution", resolution);
            expected.put("pixelscan.screen.Available screen size",
                    profile.screen.availWidth + "x" + profile.screen.availHeight);
        }
        if (profile.geolocation != null
                && "override".equalsIgnoreCase(profile.geolocation.mode)) {
            if (profile.geolocation.latitude != null) {
                expected.put("geolocation.latitude", Double.toString(profile.geolocation.latitude));
                expected.put("pixelscan.location.Latitude",
                        Double.toString(profile.geolocation.latitude));
            }
            if (profile.geolocation.longitude != null) {
                expected.put("geolocation.longitude", Double.toString(profile.geolocation.longitude));
                expected.put("pixelscan.location.Longitude",
                        Double.toString(profile.geolocation.longitude));
            }
        }
        if (isOverridden(profile.connection == null ? null : profile.connection.mode)) {
            expected.put("connection.rtt", Integer.toString(profile.connection.rtt));
            expected.put("connection.downlink", decimal(profile.connection.downlink));
            putIfText(expected, "connection.effectiveType", profile.connection.effectiveType);
        }
        addWebGl(expected, profile);
        addWebRtc(expected, profile);
        addPixelScanAliases(expected, profile, userAgent);
        return expected;
    }

    private static boolean usesMinimalCdpPolicy(BrowserFingerprintProfile profile) {
        return BrowserFingerprintProfile.RUNTIME_POLICY_MINIMAL_CDP.equalsIgnoreCase(
                profile.runtimePolicy == null ? "" : profile.runtimePolicy.trim());
    }

    private static void addWebGl(Map<String, String> expected, BrowserFingerprintProfile profile) {
        if (profile.webgl == null || !isOverridden(profile.webgl.mode)) {
            return;
        }
        String vendor = blankAs(profile.webgl.unmaskedVendor, profile.webgl.vendor);
        String renderer = blankAs(profile.webgl.unmaskedRenderer, profile.webgl.renderer);
        putIfText(expected, "browserleaks-webgl.renderer", renderer);
        putIfText(expected, "webgl-report.vendor", vendor);
        putIfText(expected, "webgl-report.renderer", renderer);
        putIfText(expected, "creepjs.webgl.gpuVendor", vendor);
        putIfText(expected, "creepjs.webgl.gpuRenderer", renderer);
        putIfText(expected, "creepjs.worker.gpuVendor", vendor);
        putIfText(expected, "creepjs.worker.gpuRenderer", renderer);
        putIfText(expected, "webgl.unmaskedRenderer", renderer);
    }

    private static void addWebRtc(Map<String, String> expected, BrowserFingerprintProfile profile) {
        if (profile.webRtc == null) {
            return;
        }
        putIfText(expected, "webRtc.mode", profile.webRtc.mode);
        expected.put("webRtc.disableUdp", Boolean.toString(profile.webRtc.disableUdp));
        putIfText(expected, "webRtc.publicIp", profile.webRtc.publicIp);
        putIfText(expected, "pixelscan.location.IP Address", profile.webRtc.publicIp);
        putIfText(expected, "pixelscan.location.WebRTC IP Address", profile.webRtc.publicIp);
        if (profile.webRtc.allowedIps != null && !profile.webRtc.allowedIps.isEmpty()) {
            expected.put("webRtc.allowedIps", String.join(",", profile.webRtc.allowedIps));
        }
    }

    private static void addPixelScanAliases(Map<String, String> expected,
                                            BrowserFingerprintProfile profile,
                                            String userAgent) {
        putIfText(expected, "pixelscan.dateTime.Timezone from JS", profile.timezoneId);
        if (isOverridden(profile.screen == null ? null : profile.screen.mode)) {
            expected.put("pixelscan.screen.Resolution", profile.screen.width + "x" + profile.screen.height);
            expected.put("pixelscan.screen.Available screen size",
                    profile.screen.availWidth + "x" + profile.screen.availHeight);
        }
        expected.put("pixelscan.userAgent.HTTP", userAgent);
        expected.put("pixelscan.userAgent.JavaScript", userAgent);
        putIfText(expected, "pixelscan.userAgent.Platform", profile.platform);
        expected.put("pixelscan.userAgent.DoNotTrack", profile.doNotTrack ? "1" : "null");
        expected.put("pixelscan.userAgent.HardwareConcurency",
                Integer.toString(profile.hardwareConcurrency));
        if (profile.languages != null && !profile.languages.isEmpty()) {
            putIfText(expected, "pixelscan.language.Languages from Javascript", profile.languages.get(0));
        }
        putIfText(expected, "pixelscan.language.Internationalization API", profile.locale);
        expected.put("pixelscan.language.Accept-Language header",
                FingerprintCdpOverrides.acceptLanguageHttpHeader(profile.languages));
        if (profile.webgl != null && isOverridden(profile.webgl.mode)) {
            putIfText(expected, "pixelscan.hardware.WebGL Vendor",
                    blankAs(profile.webgl.unmaskedVendor, profile.webgl.vendor));
            expected.put("pixelscan.hardware.WebGL Version", "WebGL 1.0");
            putIfText(expected, "pixelscan.hardware.WebGL Renderer",
                    blankAs(profile.webgl.unmaskedRenderer, profile.webgl.renderer));
        }
    }

    private static String fullBrowserVersion(BrowserFingerprintProfile profile, String userAgent) {
        if (profile.browserVersion != null && !profile.browserVersion.isBlank()) {
            return profile.browserVersion.trim();
        }
        Matcher matcher = Pattern.compile("Chrome/([^\\s]+)").matcher(userAgent);
        return matcher.find() ? matcher.group(1) : "";
    }

    private static String brandsJson(String major) {
        return "[{\"brand\":\"Not(A:Brand\",\"version\":\"99\"},"
                + "{\"brand\":\"Google Chrome\",\"version\":\"" + major + "\"},"
                + "{\"brand\":\"Chromium\",\"version\":\"" + major + "\"}]";
    }

    private static String fullVersionsJson(String major, String full) {
        return "[{\"brand\":\"Not(A:Brand\",\"version\":\"99.0.0.0\"},"
                + "{\"brand\":\"Google Chrome\",\"version\":\"" + full + "\"},"
                + "{\"brand\":\"Chromium\",\"version\":\"" + full + "\"}]";
    }

    private static String brandsHeader(String major) {
        return "\"Not(A:Brand\";v=\"99\", \"Google Chrome\";v=\"" + major
                + "\", \"Chromium\";v=\"" + major + "\"";
    }

    private static String fullVersionsHeader(String major, String full) {
        return "\"Not(A:Brand\";v=\"99.0.0.0\", \"Google Chrome\";v=\"" + full
                + "\", \"Chromium\";v=\"" + full + "\"";
    }

    private static String jsonString(String value) {
        return "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
    }

    private static void putIfText(Map<String, String> out, String key, String value) {
        if (value != null && !value.isBlank()) {
            out.put(key, value);
        }
    }

    private static boolean isOverridden(String mode) {
        return mode != null && !mode.isBlank() && !"real".equalsIgnoreCase(mode);
    }

    private static String blankAs(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }

    private static String decimal(double value) {
        return BigDecimal.valueOf(value).stripTrailingZeros().toPlainString();
    }
}
