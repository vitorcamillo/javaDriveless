package io.github.selenium.javaDriverless.fpmapping;

import com.fasterxml.jackson.databind.ObjectMapper;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import io.github.selenium.javaDriverless.types.FingerprintCdpOverrides;

final class FingerprintExpectedProfile {
    private static final ObjectMapper JSON = new ObjectMapper();

    private FingerprintExpectedProfile() {
    }

    @SuppressWarnings("unchecked")
    static Map<String, String> load(FingerprintMappingCatalog.Args args) {
        Map<String, String> expected = new LinkedHashMap<>();
        if (args.expectedProfile() != null) {
            if (!Files.isRegularFile(args.expectedProfile())) {
                throw new IllegalArgumentException(
                        "Expected profile is not a readable regular file: " + args.expectedProfile());
            }
            try {
                Object data = JSON.readValue(args.expectedProfile().toFile(), Object.class);
                if (data instanceof Map<?, ?> root) {
                    if ("site-profile-index".equals(root.get("format"))) {
                        throw new IllegalArgumentException(
                                "Expected profile uses site-profile-index; select a site-specific expected-profile.json");
                    }
                    if (root.get("values") instanceof Map<?, ?> values) {
                        data = values;
                    }
                }
                flatten("", data, expected);
            } catch (IllegalArgumentException e) {
                throw e;
            } catch (Exception e) {
                throw new IllegalArgumentException("Could not load expected profile " + args.expectedProfile(), e);
            }
        }
        expected.putAll(args.expected());
        if (args.profileCountry() != null) {
            expected.putIfAbsent("run.profileCountry", args.profileCountry());
        }
        if (args.locale() != null) {
            expected.putIfAbsent("run.locale", args.locale());
        }
        if (args.timezone() != null) {
            expected.putIfAbsent("pixelscan.dateTime.Timezone from JS", args.timezone());
            expected.putIfAbsent("run.timezone", args.timezone());
        }
        if (args.languages() != null) {
            expected.putIfAbsent("pixelscan.language.Languages from Javascript", args.languages().split(",")[0]);
            expected.putIfAbsent("run.languages", args.languages());
        }
        String languages = expected.getOrDefault("run.languages", expected.get("languages"));
        if (languages != null && !languages.isBlank()) {
            expected.putIfAbsent("http.acceptLanguage", FingerprintCdpOverrides.acceptLanguageHttpHeader(
                    Arrays.stream(languages.split(",")).map(String::trim)
                            .filter(s -> !s.isEmpty()).toList()));
        }
        if (args.geo() != null) {
            String[] parts = args.geo().split(",", 2);
            expected.putIfAbsent("pixelscan.location.Latitude", trimCoordinate(parts[0]));
            if (parts.length > 1) {
                expected.putIfAbsent("pixelscan.location.Longitude", trimCoordinate(parts[1]));
            }
            expected.putIfAbsent("run.geo", args.geo());
        }
        expected.entrySet().removeIf(entry -> isSensitiveEntry(entry.getKey(), entry.getValue()));
        return expected;
    }

    static Map<String, Object> persistable(Map<String, String> expected) {
        Map<String, String> safeValues = new LinkedHashMap<>();
        expected.forEach((key, value) -> {
            if (!isSensitiveEntry(key, value)) {
                safeValues.put(key, value);
            }
        });
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("format", "flat-key-value");
        out.put("matchSemantics", "non-present policies require a literal expected value; use contract policy 'present' for presence-only checks");
        out.put("values", safeValues);
        return out;
    }

    private static boolean isSensitiveEntry(String key, String value) {
        if (key == null) return containsCredentialMaterial(value);
        String normalized = key.toLowerCase(Locale.ROOT);
        String compact = normalized.replaceAll("[^a-z0-9]", "");
        boolean sensitiveKey = normalized.equals("run.proxy")
                || List.of("password", "passwd", "credential", "authorization", "cookie",
                        "secret", "token", "apikey", "privatekey")
                .stream().anyMatch(compact::contains);
        return sensitiveKey || containsCredentialMaterial(value);
    }

    private static boolean containsCredentialMaterial(String value) {
        if (value == null || value.isBlank()) return false;
        String clean = value.trim();
        return clean.matches("(?is).*(?:https?|socks4|socks5)://[^/\\s@]+@.*")
                || clean.matches("(?is)^(?:bearer|basic)\\s+\\S+.*")
                || clean.matches("(?is).*-----BEGIN(?: [A-Z]+)? PRIVATE KEY-----.*");
    }

    private static void flatten(String prefix, Object value, Map<String, String> out) {
        if (value instanceof Map<?, ?> map) {
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                String key = String.valueOf(entry.getKey());
                flatten(prefix.isBlank() ? key : prefix + "." + key, entry.getValue(), out);
            }
            return;
        }
        if (value instanceof Iterable<?> list) {
            StringBuilder joined = new StringBuilder();
            for (Object item : list) {
                if (!joined.isEmpty()) {
                    joined.append(",");
                }
                joined.append(String.valueOf(item));
            }
            out.put(prefix, joined.toString());
            return;
        }
        if (value != null && !prefix.isBlank()) {
            out.put(prefix, String.valueOf(value));
        }
    }

    private static String trimCoordinate(String coordinate) {
        String value = coordinate == null ? "" : coordinate.trim();
        int dot = value.indexOf('.');
        if (dot < 0 || value.length() <= dot + 5) {
            return value;
        }
        return value.substring(0, dot + 5);
    }
}
