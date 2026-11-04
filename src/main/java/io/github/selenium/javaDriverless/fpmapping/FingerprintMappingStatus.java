package io.github.selenium.javaDriverless.fpmapping;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

final class FingerprintMappingStatus {
    private FingerprintMappingStatus() {
    }

    static String statusFor(List<String> blockers, List<String> missing,
                            List<String> expectedMissing, List<String> expectedMismatches,
                            List<String> forbiddenHits) {
        if (blockers.stream().anyMatch("browser-or-access-error"::equals)) {
            return "browser-error";
        }
        if (blockers.stream().anyMatch("body-too-short"::equals)) {
            return "capture-error";
        }
        if (!missing.isEmpty()) {
            return "contract-broken";
        }
        if (blockers.stream().anyMatch(blocker -> !isDetectionBlocker(blocker)
                && !isExternalSiteUnresolved(blocker) && !isPrivacyPolicyBlocker(blocker))) {
            return "contract-broken";
        }
        if (!forbiddenHits.isEmpty() || blockers.stream().anyMatch(FingerprintMappingStatus::isDetectionBlocker)) {
            return "forbidden-signal";
        }
        boolean unresolvedOnly = !blockers.isEmpty()
                && blockers.stream().anyMatch(FingerprintMappingStatus::isExternalSiteUnresolved)
                && blockers.stream().allMatch(blocker -> isExternalSiteUnresolved(blocker)
                        || isPrivacyPolicyBlocker(blocker));
        if (!expectedMissing.isEmpty()) {
            return "expected-missing";
        }
        if (!expectedMismatches.isEmpty()) {
            return "value-mismatch";
        }
        if (unresolvedOnly) {
            return "site-unresolved";
        }
        return "pass";
    }

    private static boolean isExternalSiteUnresolved(String blocker) {
        return "ip-timezone-not-rendered".equals(blocker)
                || "trustmyip-timezone-unable-to-verify".equals(blocker)
                || "browserscan-webrtc-unresolved".equals(blocker)
                || "browserscan-dns-unresolved".equals(blocker);
    }

    private static boolean isPrivacyPolicyBlocker(String blocker) {
        return "browserscan-webrtc-privacy-policy-conflict".equals(blocker);
    }

    private static boolean isDetectionBlocker(String blocker) {
        return "creepjs-detection-nonzero".equals(blocker)
                || "creepjs-like-headless-risk".equals(blocker)
                || "creepjs-lies-detected".equals(blocker)
                || "creepjs-canvas-rgba-noise".equals(blocker)
                || "creepjs-webrtc-unsafe-candidate".equals(blocker)
                || "browserscan-unsafe-grade".equals(blocker)
                || "trustmyip-proxy-negative-result".equals(blocker);
    }

    static List<String> blockersFor(String id, String body, String page, String text,
                                    java.util.Map<String, String> expectedProfile) {
        List<String> blockers = new ArrayList<>();
        if ((body == null || body.trim().length() < 250) && !isShortButComplete(id, text)) {
            blockers.add("body-too-short");
        }
        if (FingerprintMappingText.contains(text, "this site can't be reached")
                || FingerprintMappingText.contains(text, "err_")
                || FingerprintMappingText.contains(text, "checking your browser before accessing")) {
            blockers.add("browser-or-access-error");
        }
        if ("coveryourtracks".equals(id)
                && (!FingerprintMappingText.contains(text, "fingerprint is")
                && !FingerprintMappingText.contains(text, "tracking protection")
                && !FingerprintMappingText.contains(text, "how well is your browser protected"))
                && FingerprintMappingText.contains(text, "testing your browser")) {
            blockers.add("report-not-rendered");
        }
        if ("checkup-000r".equals(id)
                && FingerprintMappingText.contains(text, "start webrtc test")
                && !FingerprintMappingText.contains(text, "canvas fingerprint")
                && !FingerprintMappingText.contains(text, "webgl renderer")
                && !FingerprintMappingText.contains(text, "fonts detected")) {
            blockers.add("only-placeholder-sections-rendered");
        }
        if ("antidetect-timezone".equals(id)
                && FingerprintMappingText.contains(text, "unable to detect")
                && FingerprintMappingText.contains(text, "unable to fetch ip location")) {
            blockers.add("ip-timezone-not-rendered");
        }
        if ("trustmyip-timezone".equals(id)
                && FingerprintMappingText.contains(text, "temporal security analysis")
                && (FingerprintMappingText.contains(text, "current timezone detecting")
                || FingerprintMappingText.contains(text, "ip location time --:--:--")
                || FingerprintMappingText.contains(text, "local system time --:--:--"))) {
            blockers.add("trustmyip-timezone-not-resolved");
        }
        if ("trustmyip-timezone".equals(id)
                && FingerprintMappingText.contains(text, "temporal forensic audit log")
                && (FingerprintMappingText.contains(text, "ip timezone not available")
                || FingerprintMappingText.contains(text, "dissonance result unable to verify")
                || FingerprintMappingText.contains(text, "geoip ip timezone not available"))) {
            blockers.add("trustmyip-timezone-unable-to-verify");
        }
        if ("printleaks".equals(id)) {
            PrintLeaksCollectors collectors = printLeaksCollectors(body);
            if (!collectors.valid()) {
                blockers.add(collectors.errors() != null && collectors.errors() > 0
                        ? "collector-errors" : "collector-summary-invalid");
            }
        }
        if ("creepjs".equals(id) && hasNonZeroCreepHeadlessOrStealthPercentage(body)) {
            blockers.add("creepjs-detection-nonzero");
        }
        if ("creepjs".equals(id) && hasRiskyCreepLikeHeadlessSignal(page)) {
            blockers.add("creepjs-like-headless-risk");
        }
        if ("creepjs".equals(id)
                && !FingerprintMappingPixelScanExtractor.creepJsLiedSections(page).isEmpty()) {
            blockers.add("creepjs-lies-detected");
        }
        if ("creepjs".equals(id) && hasNonZeroCreepCanvasRgbaNoise(body)) {
            blockers.add("creepjs-canvas-rgba-noise");
        }
        if ("creepjs".equals(id)) {
            FingerprintMappingCreepWebGl.Snapshot webgl = FingerprintMappingCreepWebGl.parse(body);
            if (!webgl.complete()) {
                blockers.add("creepjs-webgl-unavailable");
            } else if (!webgl.realmCoherent()) {
                blockers.add("creepjs-webgl-realm-mismatch");
            }
        }
        if ("creepjs".equals(id) && hasUnsafeCreepWebRtcCandidate(body, expectedProfile)) {
            blockers.add("creepjs-webrtc-unsafe-candidate");
        }
        if ("browserscan".equals(id) && browserScanHasUnsafeGrade(body)) {
            blockers.add("browserscan-unsafe-grade");
        }
        if ("browserscan".equals(id) && browserScanWebRtcIsUnknown(body)) {
            blockers.add(Boolean.parseBoolean(expectedProfile == null
                    ? "false" : expectedProfile.get("webRtc.disableUdp"))
                    ? "browserscan-webrtc-privacy-policy-conflict"
                    : "browserscan-webrtc-unresolved");
        }
        if ("browserscan".equals(id) && browserScanDnsIsUnknown(body)) {
            blockers.add("browserscan-dns-unresolved");
        }
        if ("browserleaks-ip".equals(id) && browserLeaksIpHasDuplicatedLanguageQuality(body)) {
            blockers.add("browserleaks-ip-accept-language-invalid");
        }
        if ("trustmyip-proxy".equals(id) && (FingerprintMappingText.contains(text, "vpn likely")
                || FingerprintMappingText.contains(text, "webrtc shows different ip")
                || FingerprintMappingText.contains(text, "leak!"))) {
            blockers.add("trustmyip-proxy-negative-result");
        }
        if ("sannysoft".equals(id) && !FingerprintMappingText.contains(text, "passed")
                && !FingerprintMappingText.contains(text, "failed")) {
            blockers.add("sannysoft-results-not-rendered");
        }
        if ("browserleaks-webgl".equals(id) && valueAfterLabel(body, "Unmasked Renderer").isBlank()) {
            blockers.add("webgl-renderer-missing");
        }
        if ("browserleaks-webrtc".equals(id) && !webrtcResultRendered(body)) {
            blockers.add("webrtc-result-not-rendered");
        }
        return blockers;
    }

    private static boolean isShortButComplete(String id, String text) {
        return "nowsecure".equals(id)
                && FingerprintMappingText.contains(text, "nowsecure")
                && FingerprintMappingText.contains(text, "nodriver");
    }

    private static PrintLeaksCollectors printLeaksCollectors(String body) {
        List<String> lines = FingerprintMappingText.nonEmptyLines(body);
        Integer total = integerBeforeLabel(lines, "TOTAL COLLECTORS");
        Integer succeeded = integerBeforeLabel(lines, "SUCCEEDED");
        Integer errors = integerBeforeLabel(lines, "ERRORS");
        boolean valid = total != null && total > 0
                && succeeded != null && succeeded.equals(total)
                && errors != null && errors == 0;
        return new PrintLeaksCollectors(total, succeeded, errors, valid);
    }

    private static Integer integerBeforeLabel(List<String> lines, String label) {
        int idx = FingerprintMappingText.indexOf(lines, label);
        if (idx <= 0) return null;
        try {
            String value = lines.get(idx - 1).trim();
            return value.matches("\\d+") ? Integer.parseInt(value) : null;
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

    private record PrintLeaksCollectors(Integer total, Integer succeeded, Integer errors, boolean valid) {
    }

    private static boolean hasNonZeroCreepHeadlessOrStealthPercentage(String body) {
        Matcher matcher = Pattern.compile("(?i)\\b(\\d+)\\s*%\\s*(?:headless|stealth)\\b")
                .matcher(body == null ? "" : body);
        while (matcher.find()) {
            if (Integer.parseInt(matcher.group(1)) > 0) return true;
        }
        return false;
    }

    private static boolean hasRiskyCreepLikeHeadlessSignal(String page) {
        String html = page == null ? "" : page;
        // Estes cinco sinais refletem o baseline nativo do Chrome Linux 150 headed/headless sem
        // instrumentacao: as quatro APIs ausentes abaixo e hasKnownBgColor (ActiveText vermelho,
        // que passou a ser o valor default do próprio Chromium, não uma assinatura de automação).
        // Os demais abaixo indicam superficie quebrada, incoerencia de perfil ou renderer inseguro.
        for (String key : List.of(
                "noChrome", "hasPermissionsBug", "noPlugins", "noMimeTypes",
                "notificationIsDenied", "uaDataIsBlank", "pdfIsDisabled",
                "noTaskbar", "hasVvpScreenRes", "hasSwiftShader")) {
            if (Pattern.compile("(?i)\\b" + Pattern.quote(key) + "\\s*:\\s*true\\b")
                    .matcher(html).find()) {
                return true;
            }
        }
        return false;
    }

    private static boolean hasNonZeroCreepCanvasRgbaNoise(String body) {
        Matcher matcher = Pattern.compile(
                "(?i)\\brendering:\\s*(\\d+)\\s*%\\s*rgba\\s+noise\\b")
                .matcher(body == null ? "" : body);
        return matcher.find() && Integer.parseInt(matcher.group(1)) > 0;
    }

    private static boolean hasUnsafeCreepWebRtcCandidate(String body,
                                                          java.util.Map<String, String> expectedProfile) {
        java.util.Set<String> allowed = new java.util.HashSet<>();
        if (expectedProfile != null) {
            for (String key : List.of("webRtc.publicIp", "webRtc.allowedIps", "egress.publicIp", "run.publicIp")) {
                String configured = expectedProfile.get(key);
                if (configured == null) continue;
                for (String value : configured.split(",")) {
                    String normalized = normalizeCandidateAddress(value);
                    if (!normalized.isBlank()) allowed.add(normalized);
                }
            }
        }
        Matcher matcher = Pattern.compile(
                "(?i)(?:a=)?candidate:[^\\s]+\\s+\\d+\\s+(?:udp|tcp)\\s+\\d+\\s+([^\\s]+)\\s+\\d+\\s+typ\\s+(host|srflx|prflx|relay)\\b")
                .matcher(body == null ? "" : body);
        while (matcher.find()) {
            String type = matcher.group(2).toLowerCase(Locale.ROOT);
            if ("relay".equals(type)) continue;
            String address = normalizeCandidateAddress(matcher.group(1));
            if (address.endsWith(".local") || isNeutralCandidateAddress(address)) continue;
            if (isIpLiteral(address) && !allowed.contains(address)) return true;
        }
        return false;
    }

    private static String normalizeCandidateAddress(String value) {
        String address = value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
        if (address.startsWith("[") && address.endsWith("]") && address.length() > 2) {
            address = address.substring(1, address.length() - 1);
        }
        int zone = address.indexOf('%');
        return zone > 0 ? address.substring(0, zone) : address;
    }

    private static boolean isNeutralCandidateAddress(String address) {
        return "0.0.0.0".equals(address) || "::".equals(address) || "0:0:0:0:0:0:0:0".equals(address);
    }

    private static boolean isIpLiteral(String address) {
        if (address.matches("(?:\\d{1,3}\\.){3}\\d{1,3}")) {
            for (String octet : address.split("\\.")) {
                if (Integer.parseInt(octet) > 255) return false;
            }
            return true;
        }
        return address.indexOf(':') >= 0 && address.matches("(?i)[0-9a-f:.]+");
    }

    private static boolean browserScanHasUnsafeGrade(String body) {
        List<String> lines = FingerprintMappingText.nonEmptyLines(body);
        int marker = FingerprintMappingText.indexOf(lines, "Powered by BrowserScan Trust Algorithm");
        if (marker < 3) return true;
        String grade = lines.get(marker - 3).trim().toUpperCase(Locale.ROOT);
        String risk = lines.get(marker - 1).trim().toUpperCase(Locale.ROOT);
        return !grade.matches("A\\+?") || !"LOW RISK".equals(risk);
    }

    private static boolean browserScanWebRtcIsUnknown(String body) {
        java.util.Map<String, Object> extracted = FingerprintMappingPixelScanExtractor.extract(
                "browserscan", body);
        return "UNKNOWN".equalsIgnoreCase(nestedString(extracted, "webrtc", "Status"))
                && nestedString(extracted, "webrtc", "IP").isBlank();
    }

    private static boolean browserScanDnsIsUnknown(String body) {
        java.util.Map<String, Object> extracted = FingerprintMappingPixelScanExtractor.extract(
                "browserscan", body);
        return "UNKNOWN".equalsIgnoreCase(nestedString(extracted, "dns", "Status"))
                && nestedString(extracted, "dns", "Servers").isBlank();
    }

    private static boolean browserLeaksIpHasDuplicatedLanguageQuality(String body) {
        java.util.Map<String, Object> extracted = FingerprintMappingPixelScanExtractor.extract(
                "browserleaks-ip", body);
        String acceptLanguage = nestedString(extracted, "headers", "acceptLanguage");
        return Pattern.compile("(?i);q=(?:0(?:\\.\\d+)?|1(?:\\.0+)?)\\s*;q=")
                .matcher(acceptLanguage)
                .find();
    }

    private static String nestedString(java.util.Map<String, Object> root, String section, String key) {
        Object rawSection = root.get(section);
        if (!(rawSection instanceof java.util.Map<?, ?> values)) return "";
        Object value = values.get(key);
        return value == null ? "" : value.toString().trim();
    }

    private static String valueAfterLabel(String body, String label) {
        List<String> lines = FingerprintMappingText.nonEmptyLines(body);
        int index = FingerprintMappingText.indexOf(lines, label);
        if (index < 0 || index + 1 >= lines.size()) return "";
        for (int i = index + 1; i < lines.size(); i++) {
            String value = lines.get(i).trim();
            if (value.equals("!") || value.equals("?")) continue;
            if (value.endsWith(":") || value.equalsIgnoreCase("Unmasked Vendor")) return "";
            return value;
        }
        return "";
    }

    private static boolean webrtcResultRendered(String body) {
        String text = body == null ? "" : body;
        if (Pattern.compile("(?i)\\bno leak\\b").matcher(text).find()) return true;
        return Pattern.compile("(?i)(?:local|public) ip address\\s*(?:\\R|:|\\t)\\s*(?:-|[0-9a-f:.]{3,})")
                .matcher(text).find();
    }
}
