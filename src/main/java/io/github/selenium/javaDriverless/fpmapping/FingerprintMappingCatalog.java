package io.github.selenium.javaDriverless.fpmapping;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.selenium.javaDriverless.profiles.SdkProfileCatalog;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

final class FingerprintMappingCatalog {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String[][] FALLBACK_SITES = {
            {"pixelscan", "https://pixelscan.net/fingerprint-check"},
            {"browserscan", "https://browserscan.org/"},
            {"iphey", "https://iphey.com/"},
            {"printleaks", "https://www.printleaks.com/"},
            {"creepjs", "https://abrahamjuliot.github.io/creepjs/"},
            {"browserleaks-javascript", "https://browserleaks.com/javascript"},
            {"browserleaks-client-hints", "https://browserleaks.com/client-hints"},
            {"browserleaks-webgl", "https://browserleaks.com/webgl"},
            {"browserleaks-webrtc", "https://browserleaks.com/webrtc"},
            {"ipleak", "https://ipleak.net"},
            {"browserleaks-ip", "https://browserleaks.com/ip"},
            {"browserleaks-dns", "https://browserleaks.com/dns"},
            {"browserleaks-canvas", "https://browserleaks.com/canvas"},
            {"whatsmybrowser", "https://www.whatsmybrowser.org"},
            {"browserleaks-fonts", "https://browserleaks.com/fonts"},
            {"browserleaks-geo", "https://browserleaks.com/geo"},
            {"browserleaks-tls", "https://browserleaks.com/tls"},
            {"trustmyip-proxy", "https://trustmyip.com/proxy-checker"},
            {"trustmyip-timezone", "https://trustmyip.com/timezone-dissonance"},
            {"trustmyip-canvas", "https://trustmyip.com/canvas"},
            {"checkup-000r", "https://checkup.000r.ovh/"},
            {"browsercheck", "https://browsercheck.net/"},
            {"antidetect-timezone", "https://antidetect.net/timezone-checker.html"},
            {"coveryourtracks", "https://coveryourtracks.eff.org/"},
            {"amiunique", "https://amiunique.org/fingerprint"},
            {"sannysoft", "https://bot.sannysoft.com"},
            {"nowsecure", "https://nowsecure.nl"},
            {"webgl-report", "https://webglreport.com"}
    };

    private FingerprintMappingCatalog() {
    }

    static String defaultSession() {
        return DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss")
                .withZone(ZoneOffset.UTC)
                .format(Instant.now());
    }

    @SuppressWarnings("unchecked")
    static List<Map<String, String>> loadSites(Path catalog, Set<String> selected) {
        List<Map<String, String>> sites = new ArrayList<>();
        try {
            if (catalog != null && Files.exists(catalog)) {
                Map<String, Object> data = JSON.readValue(catalog.toFile(), Map.class);
                for (Map<String, Object> item : (List<Map<String, Object>>) data.getOrDefault("tests", List.of())) {
                    String id = String.valueOf(item.getOrDefault("id", ""));
                    String url = String.valueOf(item.getOrDefault("url", ""));
                    if (!id.isBlank()) {
                        try {
                            SdkProfileCatalog.normalizeId(id);
                        } catch (IllegalArgumentException e) {
                            throw new IllegalArgumentException("Unsafe fingerprint catalog id: " + id, e);
                        }
                    }
                    if (!id.isBlank() && !url.isBlank() && (selected.isEmpty() || selected.contains(id))) {
                        sites.add(Map.of(
                                "id", id,
                                "url", url,
                                "read", csv(item.get("read")),
                                "waitMs", runValue(item.get("run"), "waitMs"),
                                "runType", runValue(item.get("run"), "type"),
                                "runSelector", runValue(item.get("run"), "selector"),
                                "runLabel", runValue(item.get("run"), "label")
                        ));
                    }
                }
            }
        } catch (IllegalArgumentException e) {
            throw e;
        } catch (Exception e) {
            System.out.println("Catalog load failed, using fallback: " + e.getMessage());
        }
        if (!sites.isEmpty()) {
            return sites;
        }
        for (String[] site : FALLBACK_SITES) {
            if (selected.isEmpty() || selected.contains(site[0])) {
                sites.add(Map.of(
                        "id", site[0], "url", site[1], "read", "", "waitMs", "",
                        "runType", "", "runSelector", "", "runLabel", ""));
            }
        }
        return sites;
    }

    static Path siteDirectory(Path root, String id) {
        Path base = root.toAbsolutePath().normalize();
        Path dir = base.resolve(id).normalize();
        if (!dir.startsWith(base) || dir.equals(base)) {
            throw new IllegalArgumentException("Capture id escapes artifact root: " + id);
        }
        rejectSymbolicLinkComponents(dir, id);
        return dir;
    }

    // NOFOLLOW no arquivo final nao basta: um componente diretorio symlink
    // (ex.: sessao pre-plantada) levaria createDirectories/escritas para fora.
    private static void rejectSymbolicLinkComponents(Path dir, String id) {
        Path current = dir.getRoot();
        if (current == null) {
            return;
        }
        for (Path segment : dir) {
            current = current.resolve(segment);
            if (Files.isSymbolicLink(current)) {
                throw new IllegalArgumentException("Capture id escapes artifact root: " + id);
            }
        }
    }

    @SuppressWarnings("unchecked")
    private static String csv(Object value) {
        if (value instanceof List<?> list) {
            return String.join(",", list.stream().map(String::valueOf).toList());
        }
        return "";
    }

    @SuppressWarnings("unchecked")
    private static String runValue(Object run, String key) {
        if (run instanceof Map<?, ?> map && map.get(key) != null) {
            return String.valueOf(map.get(key));
        }
        return "";
    }

    record Args(String label, String session, Path catalog, Set<String> sites, String proxy,
                String dnsPolicy, String dohTemplate, String profileCountry, String locale,
                String timezone, String languages, String geo, Path expectedProfile, Path fingerprintProfile,
                Map<String, Path> siteFingerprintProfiles, Map<String, String> expected) {
        static Args parse(String[] values) {
            String label = "java-driverless";
            String session = null;
            Path catalog = Path.of("docs", "FINGERPRINT_TEST_CATALOG.json");
            Set<String> sites = new HashSet<>();
            Map<String, String> expected = new LinkedHashMap<>();
            String proxy = null;
            String dnsPolicy = null;
            String dohTemplate = null;
            String profileCountry = null;
            String locale = null;
            String timezone = null;
            String languages = null;
            String geo = null;
            Path expectedProfile = null;
            Path fingerprintProfile = null;
            Map<String, Path> siteFingerprintProfiles = new LinkedHashMap<>();
            for (int i = 0; i < values.length; i++) {
                String value = values[i];
                if ("--session".equals(value) && i + 1 < values.length) {
                    session = values[++i];
                } else if ("--catalog".equals(value) && i + 1 < values.length) {
                    catalog = Path.of(values[++i]);
                } else if ("--fingerprint-profile".equals(value) && i + 1 < values.length) {
                    fingerprintProfile = Path.of(values[++i]);
                } else if ("--site-fingerprint-profile".equals(value) && i + 1 < values.length) {
                    putSiteFingerprintProfiles(siteFingerprintProfiles, values[++i]);
                } else if ("--expected-profile".equals(value) && i + 1 < values.length) {
                    expectedProfile = Path.of(values[++i]);
                } else if ("--expected".equals(value) && i + 1 < values.length) {
                    putExpected(expected, values[++i]);
                } else if ("--proxy".equals(value) && i + 1 < values.length) {
                    proxy = values[++i];
                } else if ("--dns-policy".equals(value) && i + 1 < values.length) {
                    dnsPolicy = values[++i];
                } else if ("--doh-template".equals(value) && i + 1 < values.length) {
                    dohTemplate = values[++i];
                } else if ("--profile-country".equals(value) && i + 1 < values.length) {
                    profileCountry = values[++i];
                } else if ("--locale".equals(value) && i + 1 < values.length) {
                    locale = values[++i];
                } else if ("--timezone".equals(value) && i + 1 < values.length) {
                    timezone = values[++i];
                } else if ("--languages".equals(value) && i + 1 < values.length) {
                    languages = values[++i];
                } else if ("--geo".equals(value) && i + 1 < values.length) {
                    geo = values[++i];
                } else if ("--sites".equals(value) && i + 1 < values.length) {
                    for (String site : values[++i].split(",")) {
                        if ("all".equalsIgnoreCase(site.trim())) {
                            sites.clear();
                            continue;
                        }
                        if (!site.isBlank()) {
                            sites.add(site.trim());
                        }
                    }
                } else if (!value.startsWith("--")) {
                    label = value;
                }
            }
            return new Args(label, session, catalog, sites, proxy, dnsPolicy, dohTemplate,
                    profileCountry, locale, timezone, languages, geo, expectedProfile, fingerprintProfile,
                    siteFingerprintProfiles, expected);
        }

        private static void putExpected(Map<String, String> expected, String value) {
            String[] parts = value.split("=", 2);
            if (parts.length == 2 && !parts[0].isBlank()) {
                expected.put(parts[0].trim(), parts[1].trim());
            }
        }

        private static void putSiteFingerprintProfiles(Map<String, Path> profiles, String value) {
            for (String entry : value.split(",")) {
                String[] parts = entry.split("=", 2);
                if (parts.length == 2 && !parts[0].isBlank() && !parts[1].isBlank()) {
                    profiles.put(parts[0].trim(), Path.of(parts[1].trim()));
                }
            }
        }
    }
}
