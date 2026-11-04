package io.github.selenium.javaDriverless.fpmapping;

import java.util.LinkedHashMap;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

final class FingerprintMappingPixelScanExtractor extends FingerprintMappingCreepJsExtractor {
    private FingerprintMappingPixelScanExtractor() {
    }

    static Map<String, Object> extract(String id, String body) {
        if ("browserleaks-webgl".equals(id)) {
            return Map.of("renderer", nextNonMarkerLine(body, "Unmasked Renderer"));
        }
        if ("webgl-report".equals(id)) {
            return Map.of(
                    "vendor", inlineValue(body, "Unmasked Vendor:"),
                    "renderer", inlineValue(body, "Unmasked Renderer:"));
        }
        if ("browserleaks-webrtc".equals(id)) {
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("localIp", nextNonEmptyLine(body, "Local IP Address"));
            result.put("publicIp", nextNonEmptyLine(body, "Public IP Address"));
            result.put("status", lineContaining(body, "No Leak"));
            return result;
        }
        if ("browserleaks-canvas".equals(id)) {
            return Map.of("fingerprint", browserLeaksCanvasHash(body));
        }
        if ("browserleaks-ip".equals(id)) {
            return extractBrowserLeaksIp(body);
        }
        if ("browserleaks-dns".equals(id)) {
            return extractBrowserLeaksDns(body);
        }
        if ("browserleaks-javascript".equals(id)) {
            return extractBrowserLeaksJavascript(body);
        }
        if ("browserleaks-client-hints".equals(id)) {
            return extractBrowserLeaksClientHints(body);
        }
        if ("whatsmybrowser".equals(id)) {
            return extractWhatsMyBrowser(body);
        }
        if ("trustmyip-proxy".equals(id)) {
            return Map.of("result", firstLineContainingAny(body,
                    "No Proxy or VPN Detected", "No VPN or Proxy", "VPN Likely", "Proxy Detected"));
        }
        if ("trustmyip-timezone".equals(id)) {
            return extractTrustMyIpTimezone(body);
        }
        if ("trustmyip-canvas".equals(id)) {
            return Map.of("hash", trustMyIpCanvasHash(body));
        }
        if ("sannysoft".equals(id)) {
            return Map.of("webdriver", nextNonEmptyLine(body, "WebDriver"));
        }
        if ("browserscan".equals(id)) {
            return extractBrowserScan(body);
        }
        if ("antidetect-timezone".equals(id)) {
            return extractAntidetectTimezone(body);
        }
        if ("iphey".equals(id)) {
            return extractIphey(body);
        }
        if ("printleaks".equals(id)) {
            return extractPrintLeaks(body);
        }
        if ("browsercheck".equals(id)) {
            return extractBrowserCheck(body);
        }
        if ("creepjs".equals(id)) {
            return extractCreepJs(body);
        }
        if (!"pixelscan".equals(id)) {
            return Map.of();
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("verdict", lineContaining(body, "Your Browser Fingerprint is"));
        out.put("browserCard", previousNonEmptyLine(body, "Browser"));
        out.put("locationCard", previousNonEmptyLine(body, "Location"));
        out.put("proxyCard", previousNonEmptyLine(body, "Proxy"));
        out.put("fingerprintCard", previousNonEmptyLine(body, "Fingerprint"));
        out.put("botCard", previousNonEmptyLine(body, "Bot check"));
        out.put("location", details(body, "Location", List.of(
                "IP Address", "WebRTC IP Address", "Country", "City", "ISP", "Latitude",
                "Longitude", "User Type by IP"
        )));
        out.put("dateTime", details(body, "Date & Time", List.of(
                "Timezone from JS", "Daylight Savings Time", "Time from JS", "Time from IP"
        )));
        out.put("screen", details(body, "Screen", List.of("Resolution", "Available screen size")));
        out.put("fonts", details(body, "Fonts", List.of("Font hash")));
        out.put("userAgent", details(body, "User-Agent", List.of(
                "HTTP", "JavaScript", "Platform", "DoNotTrack", "HardwareConcurency"
        )));
        Map<String, String> language = details(body, "Language", List.of(
                "Languages from Javascript", "Internationalization API", "Accept-Language header"
        ));
        String acceptLanguage = pixelScanAcceptLanguage(body);
        if (!acceptLanguage.isBlank()) {
            language.put("Accept-Language header", acceptLanguage);
        }
        out.put("language", language);
        out.put("hardware", details(body, "Hardware", List.of(
                "WebGL Vendor", "WebGL Version", "WebGL Renderer", "WebGL Hash",
                "Canvas Hash", "AudioContext Hash"
        )));
        return out;
    }

    private static Map<String, Object> extractBrowserScan(String body) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("score", lineAfter(body, "Powered by BrowserScan Trust Algorithm", -4));
        out.put("grade", lineAfter(body, "Powered by BrowserScan Trust Algorithm", -3));
        out.put("risk", lineAfter(body, "Powered by BrowserScan Trust Algorithm", -1));
        out.put("identity", details(body, "IDENTITY", List.of(
                "IP ADDRESS", "ASN", "LOCATION", "BROWSER", "OS", "DEVICE"
        )));
        out.put("protocol", details(body, "PROTOCOL FINGERPRINTS", List.of(
                "TLS JA3", "TLS VERSION", "HTTP VERSION", "TCP OS GUESS"
        )));
        out.put("webrtc", sectionDetails(body, "WEBRTC", "DNS", List.of("Status", "IP", "Region")));
        Map<String, String> dns = sectionDetails(
                body, "DNS", "CONSISTENCY MATRIX", List.of("Status", "Servers"));
        if (dns.containsKey("Servers")) {
            String servers = normalizedIpList(dns.get("Servers"));
            if (servers.isBlank()) dns.remove("Servers");
            else dns.put("Servers", servers);
        }
        out.put("dns", dns);
        return out;
    }

    private static String normalizedIpList(String value) {
        if (value == null || value.isBlank()) return "";
        return java.util.Arrays.stream(value.split("[,\\s]+"))
                .map(token -> token.replaceAll("^[\\[(]+|[\\])]+$", ""))
                .filter(FingerprintMappingPixelScanExtractor::isIpLiteral)
                .distinct()
                .collect(java.util.stream.Collectors.joining(","));
    }

    private static Map<String, Object> extractAntidetectTimezone(String body) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("browserTimezone", nextNonEmptyLine(body, "Browser Timezone"));
        out.put("ipTimezone", lineAfter(body, "IP Location", 2));
        out.put("status", firstLineContainingAny(body,
                "Perfect Match!", "Minor Mismatch!", "Timezone Mismatch Detected"));
        return out;
    }

    private static Map<String, Object> extractTrustMyIpTimezone(String body) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("ipTimezone", nextNonEmptyLine(body, "IP TIMEZONE"));
        out.put("browserTimezone", nextNonEmptyLine(body, "BROWSER TIMEZONE"));
        out.put("offsetDiff", nextNonEmptyLine(body, "UTC OFFSET DIFF"));
        out.put("result", nextNonEmptyLine(body, "DISSONANCE RESULT"));
        return out;
    }

    private static Map<String, Object> extractBrowserLeaksIp(String body) {
        Map<String, Object> out = new LinkedHashMap<>();
        Map<String, String> identity = sectionDetails(body, "What Is My IP Address", "IP Address Location",
                List.of("IP Address", "Hostname"));
        out.put("ipAddress", identity.getOrDefault("IP Address", ""));
        out.put("hostname", identity.getOrDefault("Hostname", ""));

        Map<String, String> location = sectionDetails(body, "IP Address Location", "IPv6 Leak Test",
                List.of("Country", "City", "ISP", "Timezone"));
        out.put("location", location);
        out.put("tcp", lowerCaseKeys(sectionDetails(body, "TCP/IP Fingerprint", "TLS Fingerprint",
                List.of("OS", "JA4T"))));

        Map<String, String> headers = sectionDetails(body, "HTTP Headers", "__END_OF_REPORT__",
                List.of("User-Agent", "Sec-CH-UA-Platform", "Accept-Language"));
        Map<String, String> normalizedHeaders = new LinkedHashMap<>();
        normalizedHeaders.put("userAgent", headers.getOrDefault("User-Agent", ""));
        normalizedHeaders.put("platform", stripQuotes(headers.getOrDefault("Sec-CH-UA-Platform", "")));
        normalizedHeaders.put("acceptLanguage", headers.getOrDefault("Accept-Language", ""));
        out.put("headers", normalizedHeaders);
        return out;
    }

    private static Map<String, Object> extractBrowserLeaksDns(String body) {
        Map<String, Object> out = new LinkedHashMap<>();
        Map<String, String> identity = sectionDetails(body, "Your IP Address", "DNS Leak Test",
                List.of("IP Address", "ISP", "Location"));
        out.put("currentIp", identity.getOrDefault("IP Address", ""));
        out.put("isp", identity.getOrDefault("ISP", ""));
        out.put("location", identity.getOrDefault("Location", ""));

        Map<String, String> result = sectionDetails(body, "DNS Leak Test", "Your DNS Servers",
                List.of("Test Results"));
        String testResults = result.getOrDefault("Test Results", "");
        out.put("testResults", testResults);

        int reportedCount = firstPositiveOrZero(testResults,
                "(?i)\\bFound\\s+(\\d+)\\s+Servers?\\b");
        List<Map<String, String>> servers = browserLeaksDnsServers(body);
        int observedCount = servers.size();
        out.put("reportedServerCount", Integer.toString(reportedCount));
        out.put("observedServerCount", Integer.toString(observedCount));
        out.put("countConsistency", reportedCount > 0 && reportedCount == observedCount
                ? "matched" : "mismatch");
        out.put("servers", servers);
        out.put("serverIps", servers.stream().map(server -> server.get("ip"))
                .distinct().collect(java.util.stream.Collectors.joining("\n")));
        out.put("serverIsps", servers.stream().map(server -> server.get("isp"))
                .distinct().collect(java.util.stream.Collectors.joining("\n")));
        out.put("serverLocations", servers.stream().map(server -> server.get("location"))
                .distinct().collect(java.util.stream.Collectors.joining("\n")));
        return out;
    }

    private static List<Map<String, String>> browserLeaksDnsServers(String body) {
        List<String> lines = FingerprintMappingText.nonEmptyLines(body);
        int start = sectionHeadingIndex(lines, "Your DNS Servers", 0);
        if (start < 0) return List.of();

        Map<String, Map<String, String>> servers = new LinkedHashMap<>();
        for (int i = start + 1; i < lines.size(); i++) {
            String line = lines.get(i);
            if (line.regionMatches(true, 0, "Leave a Comment", 0, "Leave a Comment".length())
                    || line.regionMatches(true, 0, "BrowserLeaks ©", 0, "BrowserLeaks ©".length())) {
                break;
            }
            String[] columns = line.split("\\t", -1);
            if (columns.length < 3 || !isIpLiteral(columns[0])
                    || columns[1].isBlank() || columns[2].isBlank()) continue;
            Map<String, String> server = new LinkedHashMap<>();
            server.put("ip", columns[0].trim());
            server.put("isp", columns[1].trim());
            server.put("location", columns[2].trim());
            servers.putIfAbsent(columns[0].trim().toLowerCase(Locale.ROOT), server);
        }
        return new ArrayList<>(servers.values());
    }

    private static int firstPositiveOrZero(String value, String regex) {
        java.util.regex.Matcher matcher = java.util.regex.Pattern.compile(regex)
                .matcher(value == null ? "" : value);
        if (!matcher.find()) return 0;
        try {
            return Integer.parseInt(matcher.group(1));
        } catch (NumberFormatException ignored) {
            return 0;
        }
    }

    private static boolean isIpLiteral(String value) {
        String address = value == null ? "" : value.trim();
        if (address.matches("(?:\\d{1,3}\\.){3}\\d{1,3}")) {
            int[] octets = java.util.Arrays.stream(address.split("\\."))
                    .mapToInt(Integer::parseInt).toArray();
            if (java.util.Arrays.stream(octets).anyMatch(octet -> octet > 255)) return false;
            int first = octets[0];
            int second = octets[1];
            if (first == 0 || first == 10 || first == 127 || first >= 224) return false;
            if (first == 169 && second == 254) return false;
            if (first == 172 && second >= 16 && second <= 31) return false;
            if (first == 192 && second == 168) return false;
            if (first == 100 && second >= 64 && second <= 127) return false;
            if (first == 198 && (second == 18 || second == 19)) return false;
            if (first == 192 && second == 0 && octets[2] == 2) return false;
            if (first == 198 && second == 51 && octets[2] == 100) return false;
            return !(first == 203 && second == 0 && octets[2] == 113);
        }
        if (!address.contains(":") || address.contains("%")
                || !address.matches("(?i)[0-9a-f:.]+")) {
            return false;
        }
        try {
            java.net.InetAddress parsed = java.net.InetAddress.getByName(address);
            byte[] bytes = parsed.getAddress();
            if (bytes.length != 16 || parsed.isAnyLocalAddress() || parsed.isLoopbackAddress()
                    || parsed.isLinkLocalAddress() || parsed.isSiteLocalAddress()
                    || parsed.isMulticastAddress()) {
                return false;
            }
            int first = bytes[0] & 0xff;
            int second = bytes[1] & 0xff;
            if ((first & 0xfe) == 0xfc) return false;
            return !(first == 0x20 && second == 0x01
                    && (bytes[2] & 0xff) == 0x0d && (bytes[3] & 0xff) == 0xb8);
        } catch (java.net.UnknownHostException ignored) {
            return false;
        }
    }

    private static Map<String, Object> extractBrowserLeaksJavascript(String body) {
        Map<String, Object> out = new LinkedHashMap<>();
        Map<String, String> screen = sectionDetails(body, "Screen Object", "Date/Time",
                List.of("Screen Resolution"));
        out.put("screen", Map.of("resolution",
                canonicalResolution(screen.getOrDefault("Screen Resolution", ""))));
        out.put("intl", sectionDetails(body, "Internationalization API", "Navigator Object",
                List.of("locale", "timeZone", "hourCycle", "calendar", "numberingSystem")));
        out.put("navigator", sectionDetails(body, "Navigator Object",
                "navigator.userAgentData (Client Hints)", List.of(
                        "userAgent", "platform", "hardwareConcurrency", "deviceMemory",
                        "language", "languages", "webdriver", "maxTouchPoints")));
        out.put("clientHints", multiLineSectionDetails(body, "navigator.userAgentData (Client Hints)",
                "navigator.plugins", List.of(
                        "brands", "mobile", "platform", "platformVersion", "architecture", "bitness",
                        "wow64", "model", "uaFullVersion", "fullVersionList", "formFactors")));
        return out;
    }

    private static Map<String, Object> extractBrowserLeaksClientHints(String body) {
        Map<String, Object> out = new LinkedHashMap<>();
        Map<String, String> browser = sectionDetails(body, "Your Web Browser",
                "Client Hints JavaScript API", List.of("HTTP User-Agent"));
        out.put("httpUserAgent", browser.getOrDefault("HTTP User-Agent", ""));
        out.put("javascript", multiLineSectionDetails(body, "Client Hints JavaScript API",
                "Client Hints HTTP Headers", List.of(
                        "brands", "mobile", "platform", "platformVersion", "architecture", "bitness",
                        "wow64", "model", "uaFullVersion", "fullVersionList", "formFactors")));
        Map<String, String> headers = sectionDetails(body, "Client Hints HTTP Headers",
                "(*) Client Hints", List.of(
                        "Sec-CH-UA", "Sec-CH-UA-Platform", "Sec-CH-UA-Mobile",
                        "Sec-CH-UA-Full-Version", "Sec-CH-UA-Full-Version-List",
                        "Sec-CH-UA-Platform-Version", "Sec-CH-UA-Arch", "Sec-CH-UA-Bitness",
                        "Sec-CH-UA-WoW64", "Sec-CH-UA-Model", "Sec-CH-UA-Form-Factors",
                        "Sec-CH-Lang", "Lang", "Sec-CH-Save-Data", "Save-Data",
                        "Sec-CH-Width", "Width",
                        "Sec-CH-Device-Memory", "Device-Memory", "Sec-CH-Viewport-Width",
                        "Viewport-Width", "Sec-CH-Viewport-Height", "Viewport-Height",
                        "Sec-CH-DPR", "DPR", "Sec-CH-RTT", "RTT",
                        "Sec-CH-Downlink", "Downlink", "Sec-CH-ECT", "ECT",
                        "Sec-CH-Prefers-Color-Scheme", "Prefers-Color-Scheme",
                        "Sec-CH-Prefers-Reduced-Motion", "Prefers-Reduced-Motion",
                        "Sec-CH-Prefers-Reduced-Transparency", "Prefers-Reduced-Transparency",
                        "Sec-CH-Prefers-Contrast", "Prefers-Contrast",
                        "Sec-CH-Forced-Colors", "Forced-Colors"));
        headers.replaceAll((key, value) -> {
            boolean explicitlyEmpty = value != null && value.trim().equals("\"\"");
            boolean structuredList = "Sec-CH-UA".equals(key)
                    || "Sec-CH-UA-Full-Version-List".equals(key)
                    || "Sec-CH-UA-Form-Factors".equals(key);
            String clean = structuredList ? (value == null ? "" : value.trim()) : stripQuotes(value);
            if (explicitlyEmpty) return "empty";
            if ("Sec-CH-UA-Mobile".equals(key) || "Sec-CH-UA-WoW64".equals(key)) {
                if ("?0".equals(clean)) return "false";
                if ("?1".equals(clean)) return "true";
            }
            return clean;
        });
        out.put("headers", headers);
        return out;
    }

    private static Map<String, Object> extractWhatsMyBrowser(String body) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("browserMajor", firstRegexGroup(body,
                "(?i)you[’']re using chrome\\s+(\\d+)", 1));
        Map<String, String> details = sectionDetails(body, "Browser details",
                "What browser am I using?", List.of(
                        "Operating system", "IP address", "Javascript enabled", "Cookies enabled",
                        "Websockets supported", "WebGL supported", "Language", "Browser size",
                        "Screen size", "Your full user agent string is:"));
        Map<String, String> normalized = new LinkedHashMap<>();
        normalized.put("operatingSystem", details.getOrDefault("Operating system", ""));
        normalized.put("ipAddress", details.getOrDefault("IP address", ""));
        normalized.put("javascriptEnabled", details.getOrDefault("Javascript enabled", ""));
        normalized.put("cookiesEnabled", details.getOrDefault("Cookies enabled", ""));
        normalized.put("websocketsSupported", details.getOrDefault("Websockets supported", ""));
        normalized.put("webglSupported", details.getOrDefault("WebGL supported", ""));
        normalized.put("language", details.getOrDefault("Language", ""));
        normalized.put("browserSize", canonicalResolution(details.getOrDefault("Browser size", "")));
        normalized.put("screenSize", canonicalResolution(details.getOrDefault("Screen size", "")));
        normalized.put("userAgent", details.getOrDefault("Your full user agent string is:", ""));
        out.put("details", normalized);
        return out;
    }

    private static Map<String, String> lowerCaseKeys(Map<String, String> values) {
        Map<String, String> normalized = new LinkedHashMap<>();
        values.forEach((key, value) -> normalized.put(key.toLowerCase(Locale.ROOT), value));
        return normalized;
    }

    private static String stripQuotes(String value) {
        String clean = value == null ? "" : value.trim();
        if (clean.length() >= 2 && clean.startsWith("\"") && clean.endsWith("\"")) {
            return clean.substring(1, clean.length() - 1).trim();
        }
        return clean;
    }

    private static String canonicalResolution(String value) {
        java.util.regex.Matcher matcher = java.util.regex.Pattern
                .compile("(\\d+)\\s*[x×]\\s*(\\d+)", java.util.regex.Pattern.CASE_INSENSITIVE)
                .matcher(value == null ? "" : value);
        return matcher.find() ? matcher.group(1) + "x" + matcher.group(2) : "";
    }

    private static String firstRegexGroup(String body, String regex, int group) {
        java.util.regex.Matcher matcher = java.util.regex.Pattern.compile(regex).matcher(body == null ? "" : body);
        return matcher.find() ? matcher.group(group).trim() : "";
    }

    private static Map<String, Object> extractIphey(String body) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("verdict", ipheyVerdict(body));
        out.put("browser", bestSectionDetails(body, "BROWSER", "HARDWARE", List.of(
                "User Agent", "Name", "OS", "Languages", "Language Tag", "Timezone",
                "Timezone Offset", "Timezone Text")));
        out.put("hardware", bestSectionDetails(body, "HARDWARE", "LOCATION", List.of(
                "Audio", "WebGL", "Resolution", "GPU", "Device Memory",
                "Hardware Concurrency", "Architecture", "Media Devices", "Storage")));
        Map<String, String> location = bestSectionDetails(body, "LOCATION", "SOFTWARE", List.of(
                "IP", "Country Code", "Country", "State", "City", "Timezone",
                "Timezone Offset", "Geolocation", "Risk Score", "Risk", "ISP Risk",
                "ISP Risk Score", "Datacenter", "VPN", "Blacklisted External",
                "Amazon AWS", "Apple iCloud Private Relay", "Google"));
        String geolocation = location.getOrDefault("Geolocation", "");
        String[] coordinates = geolocation.split(",", 2);
        if (coordinates.length == 2) {
            location.put("Latitude", coordinates[0].trim());
            location.put("Longitude", coordinates[1].trim());
        }
        out.put("location", location);
        out.put("software", bestSectionDetails(body, "SOFTWARE", "NETWORK", List.of(
                "Timing Resolution", "Stack Overflow Counter", "Plugins", "Fonts", "Canvas")));
        out.put("network", bestSectionDetails(body, "NETWORK", "BOT", List.of(
                "User Agent", "Name", "OS", "Architecture", "Version", "WebRTC IP",
                "Accept Language")));
        out.put("bot", bestSectionDetails(body, "BOT", "__END_OF_REPORT__", List.of(
                "hasCDP", "hasWebdriver", "hasUserAgent", "hasNavigator")));
        return out;
    }

    private static String ipheyVerdict(String body) {
        List<String> lines = FingerprintMappingText.nonEmptyLines(body);
        for (int i = 0; i < lines.size(); i++) {
            String line = lines.get(i);
            if (!line.toLowerCase(Locale.ROOT).contains("your digital identity looks")) {
                continue;
            }
            if (line.trim().equalsIgnoreCase("Your Digital Identity Looks") && i + 1 < lines.size()) {
                String classification = lines.get(i + 1).trim();
                if (classification.equalsIgnoreCase("Trustworthy")
                        || classification.equalsIgnoreCase("Unreliable")) {
                    return line.trim() + " " + classification;
                }
            }
            return line;
        }
        return "";
    }

    private static Map<String, String> bestSectionDetails(
            String body, String section, String nextSection, List<String> labels) {
        List<String> lines = FingerprintMappingText.nonEmptyLines(body);
        Map<String, String> best = new LinkedHashMap<>();
        for (int start = 0; start < lines.size(); start++) {
            if (!normalizeSectionHeading(lines.get(start))
                    .equalsIgnoreCase(normalizeSectionHeading(section))) {
                continue;
            }
            int end = sectionHeadingIndex(lines, nextSection, start + 1);
            if (end < 0) end = lines.size();
            Map<String, String> candidate = new LinkedHashMap<>();
            for (String label : labels) {
                String value = sectionValue(lines, start + 1, end, label, labels);
                if (!value.isBlank()) candidate.put(label, value);
            }
            if (candidate.size() > best.size()) {
                best = candidate;
            }
        }
        return best;
    }

    private static Map<String, Object> extractPrintLeaks(String body) {
        Map<String, Object> out = new LinkedHashMap<>();
        Map<String, String> collectors = new LinkedHashMap<>();
        collectors.put("total", previousNonEmptyLine(body, "TOTAL COLLECTORS"));
        collectors.put("succeeded", previousNonEmptyLine(body, "SUCCEEDED"));
        collectors.put("errors", previousNonEmptyLine(body, "ERRORS"));
        out.put("collectors", collectors);
        return out;
    }

    private static Map<String, Object> extractBrowserCheck(String body) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("realness", nextNonEmptyLine(body, "Browser Fingerprint Realness"));
        out.put("publicIp", nextNonEmptyLine(body, "Public IP Address"));
        out.put("proxyDetected", nextNonEmptyLine(body, "Proxy Detected"));
        out.put("automationDetected", browserCheckAutomationVerdict(body));
        out.put("time", sectionDetails(body, "TIME & LOCATION", "SOFTWARE", List.of(
                "IP Timezone", "System Timezone")));
        return out;
    }

    private static String browserCheckAutomationVerdict(String body) {
        List<String> lines = FingerprintMappingText.nonEmptyLines(body);
        String verdict = "";
        for (int index = 0; index + 1 < lines.size(); index++) {
            String label = lines.get(index).trim();
            if (!label.equalsIgnoreCase("Automation/Bot Detected")
                    && !label.equalsIgnoreCase("Bot Detection")) {
                continue;
            }
            String candidate = lines.get(index + 1).trim();
            if (candidate.equalsIgnoreCase("Yes") || candidate.equalsIgnoreCase("No")) {
                verdict = candidate;
            }
        }
        return verdict;
    }


}
