package io.github.selenium.javaDriverless.fpmapping;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import io.github.selenium.javaDriverless.JavaDriverless;
import io.github.selenium.javaDriverless.FingerprintSitePermissionPolicy;
import io.github.selenium.javaDriverless.types.ChromeOptions;
import io.github.selenium.javaDriverless.types.BrowserFingerprintProfile;
import io.github.selenium.javaDriverless.types.FingerprintProfileExpectedValues;

import io.github.selenium.javaDriverless.utils.OwnerOnlyFiles;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

abstract class FingerprintMappingCaptureSupport {
    static final ObjectMapper JSON = new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT);

    static void persistFailureSnapshot(JavaDriverless driver, Path dir, String id,
                                       Map<String, Object> meta) {
        if (driver == null) {
            return;
        }
        try {
            Files.createDirectories(dir);
            String finalUrl = safeString(driver::getCurrentUrl);
            String title = safeString(driver::getTitle);
            String page = safeString(driver::getPageSource);
            String body = safeString(() -> stringScript(
                    driver, "document.body ? document.body.innerText : ''"));
            meta.put("finalUrl", sanitizeArtifactUrl(finalUrl));
            meta.put("title", title);
            meta.put("failurePageState", Map.of(
                    "classification", classifyFailurePage(title, body),
                    "bodyLength", body.length(),
                    "htmlLength", page.length()));
            Map<String, Object> domEvidence = safeEvaluateJson(
                    driver, FingerprintMappingDom.captureScript(id));
            meta.put("domEvidence", domEvidence);
            writeString(dir.resolve("page.html"), page);
            writeString(dir.resolve("body.txt"), body);
        } catch (Exception ignored) {
            // Preserve the original capture error even if the diagnostic snapshot also fails.
        }
    }

    static String classifyFailurePage(String title, String body) {
        String titleText = FingerprintMappingText.normalize(title == null ? "" : title);
        String bodyText = FingerprintMappingText.normalize(body == null ? "" : body);
        String text = (titleText + " " + bodyText).trim();
        if (text.contains("err_")) {
            return "browser-error";
        }
        if (titleText.contains("bot verification")
                || bodyText.contains("checking your browser before accessing")
                || bodyText.contains("verify you are human")) {
            return "access-challenge";
        }
        return text.isBlank() ? "empty-document" : "unexpected-page";
    }

    static void requireFingerprintRuntimeHealthy(JavaDriverless driver, String id) {
        if (!driver.getChrome().hasFingerprintRuntimeFailure()) {
            return;
        }
        String diagnostic = driver.getChrome().getFingerprintRuntimeFailure()
                .filter(value -> !value.isBlank())
                .map(FingerprintMappingCapture::sanitizeArtifactText)
                .orElse("diagnóstico indisponível");
        throw new IllegalStateException(id + " teve falha assíncrona no fingerprint: " + diagnostic);
    }

    static String sanitizeArtifactText(String message) {
        String sanitized = (message == null ? "" : message)
                .replaceAll("(?i)(?:https?|wss?|file)://\\S+", "<url>")
                .replaceAll("(?im)\\b(authorization|proxy-authorization|cookie|set-cookie)"
                                + "\\s*:\\s*[^\\r\\n]*",
                        "$1=<redacted>")
                .replaceAll("(?i)\\b(x-api-key|api[_-]?key|password|passwd|secret|token)"
                                + "\\s*[:=]\\s*(?:\"[^\"]*\"|'[^']*'|[^\\s|,;]+)",
                        "$1=<redacted>")
                .replaceAll("[\\r\\n\\t]+", " ")
                .trim();
        return sanitized.length() <= 512 ? sanitized : sanitized.substring(0, 512) + "…";
    }

    static String sanitizeArtifactUrl(String value) {
        if (value == null || value.isBlank()) return "";
        try {
            java.net.URI uri = java.net.URI.create(value.trim());
            if (uri.getScheme() == null || uri.getHost() == null) {
                return value.contains("://") ? "<url>" : sanitizeArtifactText(value);
            }
            String host = uri.getHost().contains(":") ? "[" + uri.getHost() + "]" : uri.getHost();
            StringBuilder safe = new StringBuilder()
                    .append(uri.getScheme().toLowerCase())
                    .append("://")
                    .append(host.toLowerCase());
            if (uri.getPort() >= 0) safe.append(':').append(uri.getPort());
            String path = uri.getRawPath();
            safe.append(path == null || path.isBlank() ? "/" : path);
            String query = uri.getRawQuery();
            if (query != null && !query.isBlank()) {
                List<String> redacted = new ArrayList<>();
                for (String pair : query.split("&")) {
                    String name = pair.split("=", 2)[0];
                    if (!name.matches("[A-Za-z0-9._~-]{1,64}")) name = "param";
                    redacted.add(name + "=<redacted>");
                }
                safe.append('?').append(String.join("&", redacted));
            }
            return safe.toString();
        } catch (RuntimeException ignored) {
            return "<url>";
        }
    }
    static void prepareRequiredPermissionsAndOverrides(JavaDriverless driver,
                                                               BrowserFingerprintProfile profile,
                                                               String id, String url) {
        List<String> permissions = FingerprintSitePermissionPolicy.requiredPermissions(id);
        if (!permissions.isEmpty()) {
            String origin = FingerprintSitePermissionPolicy.originOf(url);
            for (String permission : permissions) {
                driver.getChrome().setPermission(origin, permission, "granted").join();
            }
        }
        if (permissions.contains("geolocation")) {
            requireGeoProfile(profile);
            driver.getChrome().setGeolocationOverride(profile.geolocation.latitude,
                    profile.geolocation.longitude, profile.geolocation.accuracy).join();
        }
    }

    @SuppressWarnings("unchecked")
    static Map<String, Object> prepareSiteRuntime(JavaDriverless driver,
                                                          BrowserFingerprintProfile profile, String id) {
        if ("creepjs".equals(id)) {
            Object value = driver.executeScript(creepDetectionSignalsScript());
            if (!(value instanceof Map<?, ?> signals) || signals.isEmpty()) {
                throw new IllegalStateException("CreepJS did not expose its detection signal map");
            }
            return (Map<String, Object>) signals;
        }
        if (!"browserleaks-geo".equals(id)) {
            return Map.of();
        }
        requireGeoProfile(profile);
        driver.getChrome().setGeolocationOverride(profile.geolocation.latitude,
                profile.geolocation.longitude, profile.geolocation.accuracy).join();
        Map<String, Object> watch = null;
        long deadline = System.nanoTime() + 15_000_000_000L;
        while (System.nanoTime() < deadline) {
            Object raw = driver.executeScript(geoRenderedScript(true));
            if (raw instanceof Map<?, ?> candidate
                    && "Active".equals(candidate.get("active"))
                    && Boolean.TRUE.equals(candidate.get("checked"))
                    && geoMatches(candidate, profile)) {
                watch = (Map<String, Object>) raw;
                break;
            }
            driver.sleep(0.2);
        }
        if (watch == null) {
            throw new IllegalStateException("BrowserLeaks Geo watchPosition did not render the profile coordinates");
        }
        Object stopped = driver.executeScript("""
                (() => {
                  const control = document.getElementById('geo-watch-button');
                  if (!control || !control.checked) return false;
                  control.click();
                  return true;
                })()
                """);
        if (!Boolean.TRUE.equals(stopped)) {
            throw new IllegalStateException("BrowserLeaks Geo watchPosition could not be stopped");
        }
        Object rawPosition = driver.executeScript("""
                (async () => {
                  const permission = await navigator.permissions.query({name: 'geolocation'});
                  const position = await new Promise(resolve => navigator.geolocation.getCurrentPosition(
                    value => resolve({ok:true, latitude:value.coords.latitude,
                                      longitude:value.coords.longitude, accuracy:value.coords.accuracy}),
                    error => resolve({ok:false, code:error.code, message:error.message}),
                    {enableHighAccuracy:true, timeout:15000, maximumAge:0}));
                  return {permission: permission.state, position};
                })()
                """);
        if (!(rawPosition instanceof Map<?, ?> result)
                || !"granted".equals(result.get("permission"))
                || !(result.get("position") instanceof Map<?, ?> position)
                || !Boolean.TRUE.equals(position.get("ok"))
                || !geoMatches(position, profile)) {
            throw new IllegalStateException("BrowserLeaks Geo getCurrentPosition did not match the profile");
        }
        Map<String, Object> evidence = new LinkedHashMap<>();
        evidence.put("permission", result.get("permission"));
        evidence.put("watchPosition", watch);
        evidence.put("getCurrentPosition", position);
        return evidence;
    }

    static String creepDetectionSignalsScript() {
        return """
                (() => {
                  const value = window.Fingerprint && window.Fingerprint.headless;
                  const fingerprint = window.Fingerprint;
                  return value ? {
                    likeHeadless:value.likeHeadless, headless:value.headless, stealth:value.stealth,
                    ratings:{likeHeadless:value.likeHeadlessRating,
                             headless:value.headlessRating, stealth:value.stealthRating},
                    lies:fingerprint && fingerprint.lies ? fingerprint.lies : null
                  } : null;
                })()
                """;
    }

    static String geoRenderedScript(boolean includeWatch) {
        return """
                (() => {
                  const value = id => {
                    const element = document.getElementById(id);
                    return element ? (element.dataset.raw || element.textContent || '').trim() : '';
                  };
                  return {active:%s, checked:%s, latitude:value('latitude'),
                          longitude:value('longitude'), accuracy:value('accuracy')};
                })()
                """.formatted(
                includeWatch ? "(document.getElementById('geo-watch-status')?.textContent || '').trim()" : "''",
                includeWatch ? "Boolean(document.getElementById('geo-watch-button')?.checked)" : "false");
    }

    static boolean geoMatches(Map<?, ?> values, BrowserFingerprintProfile profile) {
        return closeTo(values.get("latitude"), profile.geolocation.latitude)
                && closeTo(values.get("longitude"), profile.geolocation.longitude)
                && finite(values.get("accuracy"));
    }

    static boolean closeTo(Object value, double expected) {
        try {
            return Math.abs(Double.parseDouble(String.valueOf(value)) - expected) <= 0.000_001;
        } catch (RuntimeException ignored) {
            return false;
        }
    }

    static boolean finite(Object value) {
        try {
            return Double.isFinite(Double.parseDouble(String.valueOf(value)));
        } catch (RuntimeException ignored) {
            return false;
        }
    }

    static void requireGeoProfile(BrowserFingerprintProfile profile) {
        if (profile == null || profile.geolocation == null
                || profile.geolocation.latitude == null || profile.geolocation.longitude == null) {
            throw new IllegalArgumentException(
                    "browserleaks-geo requires --geo, --profile-country or a fingerprint profile with coordinates");
        }
    }

    static Map<String, String> expectedProfileForActiveProfile(Map<String, String> configured,
                                                                BrowserFingerprintProfile profile) {
        Map<String, String> expected = new LinkedHashMap<>(configured == null ? Map.of() : configured);
        FingerprintProfileExpectedValues.derive(profile).forEach(expected::putIfAbsent);
        return expected;
    }

    static Map<String, String> persistActiveExpectedProfile(Path path,
                                                             Map<String, String> configured,
                                                             BrowserFingerprintProfile profile) throws Exception {
        Map<String, String> expected = expectedProfileForActiveProfile(configured, profile);
        writeJson(path, FingerprintExpectedProfile.persistable(expected));
        return expected;
    }

    static void persistSiteExpectedProfileIndex(Path path,
                                                Map<String, Map<String, String>> expectedBySite)
            throws Exception {
        Map<String, Object> sites = new LinkedHashMap<>();
        if (expectedBySite != null) {
            expectedBySite.forEach((id, expected) -> sites.put(
                    id, FingerprintExpectedProfile.persistable(expected).get("values")));
        }
        Map<String, Object> index = new LinkedHashMap<>();
        index.put("format", "site-profile-index");
        index.put("sites", sites);
        writeJson(path, index);
    }

    static void captureScrollScreenshots(JavaDriverless driver, Path dir, String id) {
        int[] positions = {0, 900, 1800, 3200};
        for (int pos : positions) {
            try {
                driver.executeScript("window.scrollTo(0, " + pos + ")");
                driver.sleep(0.8);
                Path screenshot = dir.resolve("screen-" + pos + ".png");
                createOwnerOnly(screenshot);
                driver.screenshot(screenshot.toString());
            } catch (Exception ignored) {
            }
        }
        try {
            driver.executeScript("window.scrollTo(0, document.body.scrollHeight)");
            driver.sleep(0.8);
            Path screenshot = dir.resolve("screen-bottom.png");
            createOwnerOnly(screenshot);
            driver.screenshot(screenshot.toString());
        } catch (Exception ignored) {
        }
    }

    static double initialWaitSeconds(String id, Map<String, String> site) {
        if ("coveryourtracks".equals(id)) {
            return 3;
        }
        if ("browserleaks-dns".equals(id) || "browserleaks-geo".equals(id)) {
            return 5;
        }
        return Math.max(5, configuredWaitMs(site) / 1000.0);
    }

    static double afterActionWaitSeconds(String id, Map<String, String> site) {
        return switch (id) {
            case "coveryourtracks" -> 0;
            case "browserleaks-dns" -> 45;
            case "browserleaks-geo" -> 30;
            case "amiunique" -> 45;
            case "browserscan" -> 20;
            case "printleaks" -> 30;
            default -> Math.max(3, Math.min(10, configuredWaitMs(site) / 3000.0));
        };
    }

    static int configuredWaitMs(Map<String, String> site) {
        try {
            String value = site.getOrDefault("waitMs", "");
            return value.isBlank() ? 15000 : Integer.parseInt(value);
        } catch (Exception ignored) {
            return 15000;
        }
    }

    static String stringScript(JavaDriverless driver, String script) {
        Object value = driver.executeScript(script);
        return value == null ? "" : value.toString();
    }

    @SuppressWarnings("unchecked")
    static Map<String, Object> evaluateJson(JavaDriverless driver, String script) throws Exception {
        Object value = driver.executeScript(script);
        if (value == null) {
            return Map.of();
        }
        if (value instanceof Map<?, ?> map) {
            return (Map<String, Object>) map;
        }
        return JSON.readValue(value.toString(), Map.class);
    }

    static Map<String, Object> safeEvaluateJson(JavaDriverless driver, String script) {
        try {
            return evaluateJson(driver, script);
        } catch (Exception ignored) {
            return Map.of();
        }
    }

    static void createOwnerOnly(Path path) throws IOException {
        OwnerOnlyFiles.createOwnerOnly(path);
    }

    static void writeString(Path path, String content) throws Exception {
        OwnerOnlyFiles.writeString(path, content);
    }

    static void writeBytes(Path path, byte[] content) throws IOException {
        OwnerOnlyFiles.writeBytes(path, content);
    }

    /** Artefatos JSON (metadata.json, MANIFEST.json, perfis) seguem a mesma proteção 0600. */
    static void writeJson(Path path, Object value) throws Exception {
        writeString(path, JSON.writeValueAsString(value));
    }

    static String safeString(ThrowingStringSupplier supplier) {
        try {
            return supplier.get();
        } catch (Exception e) {
            return "";
        }
    }

    static String profileName(String label, String session) {
        String raw = "FingerprintMapping-" + label + "-" + session;
        return raw.replaceAll("[^A-Za-z0-9_.-]", "-");
    }

    @FunctionalInterface
    interface ThrowingStringSupplier {
        String get() throws Exception;
    }
}
