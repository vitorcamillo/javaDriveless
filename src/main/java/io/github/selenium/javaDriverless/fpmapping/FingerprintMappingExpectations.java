package io.github.selenium.javaDriverless.fpmapping;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.LinkedHashSet;
import java.util.regex.Pattern;

final class FingerprintMappingExpectations {
    private FingerprintMappingExpectations() {
    }

    static Map<String, Object> validateExpectedProfile(String id, Map<String, Object> extracted,
                                                       Map<String, String> expectedProfile,
                                                       FingerprintSiteContracts.Site contract) {
        List<FingerprintSiteContracts.ExpectedValue> expectedValues = expectedValues(id, contract);
        List<String> missing = new ArrayList<>();
        List<String> mismatches = new ArrayList<>();
        List<String> skipped = new ArrayList<>();
        Map<String, String> actual = new LinkedHashMap<>();
        Map<String, String> matched = new LinkedHashMap<>();
        Map<String, String> policies = new LinkedHashMap<>();
        flattenExtracted(id, extracted, actual);

        for (FingerprintSiteContracts.ExpectedValue expectation : expectedValues) {
            String key = expectation.key;
            String policy = cleanPolicy(expectation.policy);
            policies.put(key, policy);
            String expectedKey = expectation.expectedKey == null || expectation.expectedKey.isBlank()
                    ? key : expectation.expectedKey;
            String expected = expectedProfile == null ? null : expectedProfile.get(expectedKey);
            if ((expected == null || expected.isBlank())
                    && "webRtc.allowedIps".equals(expectedKey) && expectedProfile != null) {
                // Backward-compatible single-stack profiles remain exact one-address allowlists.
                expected = expectedProfile.get("webRtc.publicIp");
            }
            if (isOptionalNumeric(policy) && (expected == null || expected.isBlank())) {
                skipped.add(key + " source=" + expectedKey + " reason=expected-not-configured");
                continue;
            }
            if (isIfConfigured(policy) && (expected == null || expected.isBlank())) {
                policies.put(key, "present");
            } else if ("equalsIfConfigured".equals(policy)) {
                policies.put(key, "equals");
            } else if ("containsIfConfigured".equals(policy)) {
                policies.put(key, "contains");
            } else if ("allMatchRegexIfConfigured".equals(policy)) {
                policies.put(key, "allMatchRegex");
            }
            if (requiresExpectedValue(policy) && (expected == null || expected.isBlank())) {
                missing.add("expected-missing:" + key + " source=" + expectedKey);
                continue;
            }
            String value = actual.getOrDefault(key, "");
            if (value.isBlank()) {
                mismatches.add("actual-missing:" + key);
                continue;
            }
            if (matchesExpected(value, expected, expectation)) {
                matched.put(key, value);
            } else {
                mismatches.add("expected-mismatch:" + key + " policy=" + policy
                        + " expected=" + expected + " actual=" + value);
            }
        }

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("required", expectedValues.stream().map(value -> value.key).toList());
        out.put("policies", policies);
        out.put("missing", missing);
        out.put("mismatches", mismatches);
        out.put("skipped", skipped);
        out.put("matched", matched);
        return out;
    }

    static Map<String, Object> validateForbiddenSignals(FingerprintSiteContracts.Site contract, String text) {
        List<String> hits = new ArrayList<>();
        Map<String, List<String>> evidence = new LinkedHashMap<>();
        if (contract == null || contract.forbiddenSignals == null) {
            return Map.of("hits", hits, "evidence", evidence);
        }
        for (FingerprintSiteContracts.ForbiddenSignal signal : contract.forbiddenSignals) {
            List<String> signalHits = signal.terms.stream()
                    .filter(term -> matchesForbiddenSignal(text, term))
                    .limit(5)
                    .toList();
            if (!signalHits.isEmpty()) {
                hits.add(signal.id);
                evidence.put(signal.id, signalHits);
            }
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("hits", hits);
        out.put("evidence", evidence);
        return out;
    }

    private static boolean matchesForbiddenSignal(String text, String term) {
        if (term == null || term.isBlank()) {
            return false;
        }
        String normalizedTerm = FingerprintMappingText.normalize(term);
        int index = text.indexOf(normalizedTerm);
        while (index >= 0) {
            int prefixStart = Math.max(0, index - 3);
            if (!"no ".equals(text.substring(prefixStart, index))) {
                return true;
            }
            index = text.indexOf(normalizedTerm, index + normalizedTerm.length());
        }
        return false;
    }

    private static void flattenExtracted(String prefix, Object value, Map<String, String> out) {
        if (value instanceof Map<?, ?> map) {
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                flattenExtracted(prefix + "." + entry.getKey(), entry.getValue(), out);
            }
            return;
        }
        if (value != null) {
            out.put(prefix, String.valueOf(value));
        }
    }

    private static List<FingerprintSiteContracts.ExpectedValue> expectedValues(String id,
                                                                               FingerprintSiteContracts.Site contract) {
        if (contract != null && contract.expectedValues != null && !contract.expectedValues.isEmpty()) {
            return contract.expectedValues;
        }
        return legacyExpectedKeys(id).stream().map(key -> {
            FingerprintSiteContracts.ExpectedValue value = new FingerprintSiteContracts.ExpectedValue();
            value.key = key;
            value.policy = "contains";
            return value;
        }).toList();
    }

    private static List<String> legacyExpectedKeys(String id) {
        return List.of();
    }

    private static boolean matchesExpected(String actual, String expected,
                                           FingerprintSiteContracts.ExpectedValue expectation) {
        String policy = cleanPolicy(expectation.policy);
        if ("present".equals(policy)) {
            return actual != null && !actual.isBlank();
        }
        String cleanExpected = expected == null ? "" : expected.trim();
        if ("equals".equals(policy)) {
            return FingerprintMappingText.normalize(actual).equals(FingerprintMappingText.normalize(cleanExpected));
        }
        if ("ipInAllowedSet".equals(policy)) {
            Set<String> actualIps = canonicalIpSet(actual);
            Set<String> expectedIps = canonicalIpSet(cleanExpected);
            return !actualIps.isEmpty() && !expectedIps.isEmpty()
                    && expectedIps.containsAll(actualIps);
        }
        if ("equalsIfConfigured".equals(policy)) {
            return cleanExpected.isBlank()
                    ? actual != null && !actual.isBlank()
                    : FingerprintMappingText.normalize(actual)
                            .equals(FingerprintMappingText.normalize(cleanExpected));
        }
        if ("containsIfConfigured".equals(policy) && cleanExpected.isBlank()) {
            return actual != null && !actual.isBlank();
        }
        if ("regex".equals(policy)) {
            return Pattern.compile(cleanExpected, Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE)
                    .matcher(actual)
                    .find();
        }
        if ("allMatchRegex".equals(policy) || "allMatchRegexIfConfigured".equals(policy)) {
            Pattern pattern = Pattern.compile(cleanExpected,
                    Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);
            List<String> values = Pattern.compile("\\R+").splitAsStream(actual == null ? "" : actual)
                    .map(String::trim).filter(value -> !value.isBlank()).toList();
            return !values.isEmpty() && values.stream().allMatch(value -> pattern.matcher(value).find());
        }
        if ("withinTolerance".equals(policy) || "withinDistance".equals(policy)
                || "withinToleranceIfConfigured".equals(policy)
                || "withinDistanceIfConfigured".equals(policy)) {
            double tolerance = expectation.distance != null ? expectation.distance
                    : expectation.tolerance != null ? expectation.tolerance : 0.0;
            return Math.abs(firstNumber(actual) - firstNumber(cleanExpected)) <= tolerance;
        }
        String actualNorm = FingerprintMappingText.normalize(actual);
        for (String option : cleanExpected.split("\\|")) {
            String optionNorm = FingerprintMappingText.normalize(option);
            if (!optionNorm.isBlank() && actualNorm.contains(optionNorm)) {
                return true;
            }
        }
        return false;
    }

    private static boolean requiresExpectedValue(String policy) {
        return !"present".equals(policy) && !isIfConfigured(policy);
    }

    private static boolean isIfConfigured(String policy) {
        return "equalsIfConfigured".equals(policy)
                || "containsIfConfigured".equals(policy)
                || "allMatchRegexIfConfigured".equals(policy)
                || isOptionalNumeric(policy);
    }

    private static boolean isOptionalNumeric(String policy) {
        return "withinToleranceIfConfigured".equals(policy)
                || "withinDistanceIfConfigured".equals(policy);
    }

    private static String cleanPolicy(String policy) {
        if (policy == null || policy.isBlank()) {
            return "contains";
        }
        return policy.trim();
    }

    private static double firstNumber(String value) {
        java.util.regex.Matcher matcher = Pattern.compile("-?\\d+(?:\\.\\d+)?").matcher(value == null ? "" : value);
        if (!matcher.find()) {
            return Double.NaN;
        }
        return Double.parseDouble(matcher.group());
    }

    private static Set<String> canonicalIpSet(String value) {
        Set<String> addresses = new LinkedHashSet<>();
        String candidates = (value == null ? "" : value)
                .replace('[', ' ').replace(']', ' ')
                .replace(',', ' ').replace(';', ' ');
        for (String candidate : candidates.split("\\s+")) {
            String literal = candidate.trim();
            if ((!literal.contains(".") && !literal.contains(":"))
                    || !literal.matches("[0-9A-Fa-f:.]+")) {
                continue;
            }
            try {
                addresses.add(java.net.InetAddress.getByName(literal).getHostAddress());
            } catch (java.net.UnknownHostException ignored) {
                // Malformed literals never match the measured allowlist.
            }
        }
        return addresses;
    }
}
