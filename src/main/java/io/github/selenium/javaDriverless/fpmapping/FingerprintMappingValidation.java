package io.github.selenium.javaDriverless.fpmapping;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

final class FingerprintMappingValidation {
    private FingerprintMappingValidation() {
    }

    static Map<String, Object> validate(String id, String title, String finalUrl, String body, String page, String readCsv,
                                        Map<String, String> expectedProfile) {
        return validate(id, title, finalUrl, body, page, readCsv, expectedProfile,
                FingerprintMappingDom.visibleLineFallback(id, body));
    }

    static Map<String, Object> validate(String id, String title, String finalUrl, String body, String page, String readCsv,
                                        Map<String, String> expectedProfile, Map<String, Object> domEvidence) {
        String safeBody = body == null ? "" : body;
        String text = FingerprintMappingText.normalize(safeBody);
        FingerprintSiteContracts.Site contract = FingerprintSiteContracts.site(id);
        List<String> read = FingerprintMappingTerms.requiredFields(readCsv, contract);
        List<String> present = new ArrayList<>();
        List<String> missing = new ArrayList<>();
        Map<String, List<String>> evidence = new LinkedHashMap<>();

        for (String field : read) {
            List<String> terms = FingerprintMappingTerms.termsFor(id, field, contract);
            Object rawEvidence = domEvidence == null ? null : domEvidence.get(field);
            String nodeText = evidenceText(rawEvidence);
            String matchPolicy = contract == null ? "terms" : contract.requiredDom.stream()
                    .filter(entry -> field.equals(entry.id))
                    .map(entry -> entry.matchPolicy == null ? "terms" : entry.matchPolicy)
                    .findFirst().orElse("terms");
            List<String> hits;
            if ("nonEmpty".equals(matchPolicy)) {
                hits = nodeText.isBlank() ? List.of() : List.of("non-empty-node");
            } else if ("numeric".equals(matchPolicy)) {
                hits = nodeText.trim().matches("-?(?:\\d+(?:\\.\\d+)?|\\.\\d+)")
                        ? List.of("numeric-node") : List.of();
            } else if ("hex32".equals(matchPolicy)) {
                hits = nodeText.trim().matches("(?i)^(?:[✔✓]\\s*)?[a-f0-9]{32}$")
                        ? List.of("hex32-node") : List.of();
            } else if ("hex8".equals(matchPolicy)) {
                hits = nodeText.trim().matches("(?i)^(?:[✔✓]\\s*)?[a-f0-9]{8}$")
                        ? List.of("hex8-node") : List.of();
            } else if ("canvasHash".equals(matchPolicy)) {
                hits = nodeText.trim().matches("(?i)^CVS-[0-9A-F]{8}-[0-9A-F]{1,4}$")
                        ? List.of("canvas-hash-node") : List.of();
            } else if ("fontMetricsReport".equals(matchPolicy)) {
                hits = nodeText.trim().matches("(?i)^\\d+ fonts and \\d+ unique metrics found$")
                        ? List.of("font-metrics-report-node") : List.of();
            } else {
                hits = terms.stream().filter(term -> FingerprintMappingText.contains(
                        FingerprintMappingText.normalize(nodeText), term)).limit(5).toList();
            }
            if (hits.isEmpty()) {
                missing.add(field);
            } else {
                present.add(field);
                evidence.put(field, hits);
            }
        }

        Map<String, Object> extracted = FingerprintMappingPixelScanExtractor.extract(id, safeBody);
        if ("creepjs".equals(id)) {
            FingerprintMappingPixelScanExtractor.augmentCreepJsFromPage(page, extracted);
        }
        Map<String, Object> expectedCheck = FingerprintMappingExpectations.validateExpectedProfile(
                id, extracted, expectedProfile, contract);
        @SuppressWarnings("unchecked")
        List<String> expectedMissing = (List<String>) expectedCheck.getOrDefault("missing", List.of());
        @SuppressWarnings("unchecked")
        List<String> expectedMismatches = (List<String>) expectedCheck.getOrDefault("mismatches", List.of());
        applyProtectedWebRtcAbsence(
                id, expectedProfile, missing, expectedMismatches, expectedCheck);
        applyBrowserScanProtectedWebRtcAbsence(
                id, expectedProfile, extracted, expectedMismatches, expectedCheck);
        Map<String, Object> forbiddenCheck = FingerprintMappingExpectations.validateForbiddenSignals(
                contract, FingerprintMappingText.normalize(safeBody));
        @SuppressWarnings("unchecked")
        List<String> forbiddenHits = (List<String>) forbiddenCheck.getOrDefault("hits", List.of());

        List<String> navigationBlockers = navigationBlockers(contract, title, finalUrl);
        List<String> blockers = FingerprintMappingStatus.blockersFor(
                id, safeBody, page == null ? "" : page, text, expectedProfile);
        blockers.addAll(navigationBlockers);
        List<String> decisiveExpectedMismatches = expectedMismatches.stream()
                .filter(mismatch -> !isMismatchCausedByUnresolvedSite(id, blockers, mismatch))
                .toList();
        List<String> decisiveMissing = missing.stream()
                .filter(field -> !isMissingCausedByUnresolvedSite(id, blockers, field))
                .toList();
        String status = FingerprintMappingStatus.statusFor(
                blockers, decisiveMissing, expectedMissing, decisiveExpectedMismatches, forbiddenHits);
        if (isIpheyPrivacyPolicyConflict(id, safeBody, expectedProfile, blockers, missing,
                expectedMissing, expectedMismatches, forbiddenHits)) {
            status = "privacy-policy-conflict";
        }
        if (isBrowserScanPrivacyPolicyConflict(id, expectedProfile, blockers, missing,
                expectedMissing, decisiveExpectedMismatches, forbiddenHits)) {
            status = "privacy-policy-conflict";
        }

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("status", status);
        out.put("requiredCount", read.size());
        out.put("presentCount", present.size());
        out.put("missingCount", missing.size());
        out.put("matchedCount", ((Map<?, ?>) expectedCheck.getOrDefault("matched", Map.of())).size());
        String coverageLevel = coverageLevel(id, expectedCheck, expectedProfile);
        out.put("coverageLevel", coverageLevel);
        if ("browserleaks-dns".equals(id)) {
            boolean fullyAttributed = "resolver-policy-validated".equals(coverageLevel);
            boolean partiallyAttributed = "resolver-policy-partially-validated".equals(coverageLevel);
            out.put("resolverAttribution", fullyAttributed ? "validated"
                    : partiallyAttributed ? "partial" : "unattributed");
            out.put("provesLeakAbsence", fullyAttributed && "pass".equals(status));
        }
        out.put("present", present);
        out.put("missing", missing);
        out.put("blockers", blockers);
        out.put("decisiveMissing", decisiveMissing);
        out.put("decisiveExpectedMismatches", decisiveExpectedMismatches);
        out.put("navigationBlockers", navigationBlockers);
        out.put("evidence", evidence);
        out.put("domEvidence", domEvidence == null ? Map.of() : domEvidence);
        out.put("expected", expectedCheck);
        out.put("forbidden", forbiddenCheck);
        out.put("extracted", extracted);
        out.put("bodyLength", body == null ? 0 : body.length());
        out.put("htmlLength", page == null ? 0 : page.length());
        return out;
    }

    @SuppressWarnings("unchecked")
    private static void applyBrowserScanProtectedWebRtcAbsence(
            String id, Map<String, String> expectedProfile, Map<String, Object> extracted,
            List<String> expectedMismatches, Map<String, Object> expectedCheck) {
        if (!"browserscan".equals(id)
                || expectedProfile == null
                || !Boolean.parseBoolean(expectedProfile.get("webRtc.disableUdp"))) {
            return;
        }
        Object raw = extracted.get("webrtc");
        if (!(raw instanceof Map<?, ?> webrtc)) {
            return;
        }
        Object status = webrtc.get("Status");
        Object ip = webrtc.get("IP");
        if (!"UNKNOWN".equalsIgnoreCase(status == null ? "" : status.toString())
                || !(ip == null ? "" : ip.toString()).isBlank()) {
            return;
        }
        expectedMismatches.removeIf(mismatch -> mismatch.startsWith(
                "expected-mismatch:browserscan.webrtc.Status ")
                || mismatch.equals("actual-missing:browserscan.webrtc.IP"));
        Object rawMatched = expectedCheck.get("matched");
        if (rawMatched instanceof Map<?, ?> map) {
            Map<String, String> matched = (Map<String, String>) map;
            matched.put("browserscan.webrtc.Status", "blocked-by-profile");
            matched.put("browserscan.webrtc.IP", "blocked-by-profile");
        }
    }

    private static boolean isMismatchCausedByUnresolvedSite(
            String id, List<String> blockers, String mismatch) {
        if ("antidetect-timezone".equals(id)
                && blockers.contains("ip-timezone-not-rendered")) {
            return mismatch.startsWith("expected-mismatch:antidetect-timezone.ipTimezone ");
        }
        if ("trustmyip-timezone".equals(id)
                && blockers.contains("trustmyip-timezone-unable-to-verify")) {
            return mismatch.startsWith("expected-mismatch:trustmyip-timezone.ipTimezone ")
                    || mismatch.startsWith("expected-mismatch:trustmyip-timezone.offsetDiff ")
                    || mismatch.startsWith("expected-mismatch:trustmyip-timezone.result ");
        }
        if (!"browserscan".equals(id)) return false;
        if (blockers.contains("browserscan-webrtc-unresolved")
                && (mismatch.startsWith("expected-mismatch:browserscan.webrtc.Status ")
                || mismatch.equals("actual-missing:browserscan.webrtc.IP"))) {
            return true;
        }
        return blockers.contains("browserscan-dns-unresolved")
                && (mismatch.startsWith("expected-mismatch:browserscan.dns.Status ")
                || mismatch.equals("actual-missing:browserscan.dns.Servers"));
    }

    private static boolean isMissingCausedByUnresolvedSite(
            String id, List<String> blockers, String field) {
        return "antidetect-timezone".equals(id)
                && blockers.contains("ip-timezone-not-rendered")
                && "antidetect-timezone-status".equals(field);
    }

    @SuppressWarnings("unchecked")
    private static void applyProtectedWebRtcAbsence(
            String id, Map<String, String> expectedProfile, List<String> missing,
            List<String> expectedMismatches, Map<String, Object> expectedCheck) {
        if (!"pixelscan".equals(id)
                || expectedProfile == null
                || !Boolean.parseBoolean(expectedProfile.get("webRtc.disableUdp"))) {
            return;
        }
        String absentValue = "actual-missing:pixelscan.location.WebRTC IP Address";
        if (!expectedMismatches.remove(absentValue)) {
            // A rendered value still follows the exact expected-IP contract. Never hide a leak.
            return;
        }
        missing.remove("pixelscan-webrtc-ip-address");
        Object rawMatched = expectedCheck.get("matched");
        if (rawMatched instanceof Map<?, ?> map) {
            ((Map<String, String>) map).put(
                    "pixelscan.location.WebRTC IP Address", "blocked-by-profile");
        }
    }

    private static boolean isIpheyPrivacyPolicyConflict(String id, String body,
                                                          Map<String, String> expectedProfile,
                                                          List<String> blockers, List<String> missing,
                                                          List<String> expectedMissing,
                                                          List<String> expectedMismatches,
                                                          List<String> forbiddenHits) {
        if (!"iphey".equals(id)
                || expectedProfile == null
                || !Boolean.parseBoolean(expectedProfile.get("webRtc.disableUdp"))
                || !blockers.isEmpty()
                || !missing.isEmpty()
                || !expectedMissing.isEmpty()
                || expectedMismatches.stream().anyMatch(
                        mismatch -> !isIpheyPrivacyConflictMismatch(mismatch))
                || forbiddenHits.isEmpty()
                || !Set.of("iphey-unreliable", "iphey-more-login").containsAll(forbiddenHits)) {
            return false;
        }
        return Pattern.compile("(?i)\\bwebrtc\\s+ip\\s*(?::|-)?\\s*null\\b")
                .matcher(body == null ? "" : body)
                .find();
    }

    private static boolean isIpheyPrivacyConflictMismatch(String mismatch) {
        if (mismatch.startsWith("expected-mismatch:iphey.network.WebRTC IP")) {
            return true;
        }
        return mismatch.startsWith("expected-mismatch:iphey.verdict ")
                && mismatch.contains("actual=Your Digital Identity Looks Unreliable");
    }

    private static boolean isBrowserScanPrivacyPolicyConflict(
            String id, Map<String, String> expectedProfile, List<String> blockers,
            List<String> missing, List<String> expectedMissing,
            List<String> expectedMismatches, List<String> forbiddenHits) {
        boolean browserScanDnsResolvedHere = blockers.equals(
                List.of("browserscan-webrtc-privacy-policy-conflict"));
        boolean browserScanDnsUnavailable = blockers.equals(List.of(
                "browserscan-webrtc-privacy-policy-conflict",
                "browserscan-dns-unresolved"));
        return "browserscan".equals(id)
                && expectedProfile != null
                && Boolean.parseBoolean(expectedProfile.get("webRtc.disableUdp"))
                && (browserScanDnsResolvedHere || browserScanDnsUnavailable)
                && missing.isEmpty()
                && expectedMissing.isEmpty()
                && expectedMismatches.isEmpty()
                && forbiddenHits.isEmpty();
    }

    private static String evidenceText(Object rawEvidence) {
        if (!(rawEvidence instanceof Map<?, ?> map) || !Boolean.TRUE.equals(map.get("matched"))) {
            return "";
        }
        Object text = map.get("text");
        return text == null ? "" : text.toString();
    }

    private static List<String> navigationBlockers(FingerprintSiteContracts.Site contract, String title,
                                                    String finalUrl) {
        List<String> blockers = new ArrayList<>();
        if (contract == null) {
            blockers.add("contract-missing");
            return blockers;
        }
        String origin = origin(finalUrl);
        if (contract.allowedOrigins != null && !contract.allowedOrigins.isEmpty()
                && contract.allowedOrigins.stream().noneMatch(value -> value.equalsIgnoreCase(origin))) {
            blockers.add(origin.isBlank() ? "final-url-missing" : "unexpected-origin");
        }
        String normalizedTitle = FingerprintMappingText.normalize(title == null ? "" : title);
        if (contract.titleTerms != null && !contract.titleTerms.isEmpty()
                && contract.titleTerms.stream().noneMatch(term -> FingerprintMappingText.contains(normalizedTitle, term))) {
            blockers.add(normalizedTitle.isBlank() ? "title-missing" : "unexpected-title");
        }
        return blockers;
    }

    private static String origin(String url) {
        try {
            java.net.URI uri = java.net.URI.create(url == null ? "" : url);
            if (uri.getScheme() == null || uri.getHost() == null) return "";
            int port = uri.getPort();
            return uri.getScheme().toLowerCase() + "://" + uri.getHost().toLowerCase()
                    + (port < 0 ? "" : ":" + port);
        } catch (RuntimeException ignored) {
            return "";
        }
    }

    private static String coverageLevel(String id, Map<String, Object> expectedCheck,
                                        Map<String, String> expectedProfile) {
        if ("browserleaks-dns".equals(id)) {
            long configuredDimensions = expectedProfile == null ? 0 : List.of(
                    "browserleaks-dns.allowedIpPattern",
                    "browserleaks-dns.allowedIspPattern",
                    "browserleaks-dns.allowedLocationPattern").stream()
                    .map(expectedProfile::get)
                    .filter(FingerprintMappingValidation::meaningfulResolverPattern)
                    .count();
            if (configuredDimensions == 3) return "resolver-policy-validated";
            if (configuredDimensions > 0) return "resolver-policy-partially-validated";
            return "resolver-observed-unattributed";
        }
        Object raw = expectedCheck.get("policies");
        if (!(raw instanceof Map<?, ?> policies) || policies.isEmpty()) return "structural-only";
        boolean presenceOnly = policies.values().stream().allMatch("present"::equals);
        return presenceOnly ? "structural-and-presence" : "expected-values";
    }

    private static boolean meaningfulResolverPattern(String value) {
        if (value == null || value.isBlank()) return false;
        String compact = value.replaceAll("\\s+", "");
        return !List.of(".*", "^.*$", ".+", "^.+$", "(?s).*", "(?s)^.*$").contains(compact);
    }
}
