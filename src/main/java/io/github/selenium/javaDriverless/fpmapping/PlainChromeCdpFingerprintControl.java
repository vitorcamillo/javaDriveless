package io.github.selenium.javaDriverless.fpmapping;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import io.github.selenium.javaDriverless.DevToolsActivePortWaiter;
import io.github.selenium.javaDriverless.FingerprintSitePermissionPolicy;
import io.github.selenium.javaDriverless.types.BaseTarget;
import io.github.selenium.javaDriverless.types.BrowserFingerprintProfile;
import io.github.selenium.javaDriverless.types.ChromeOptions;
import io.github.selenium.javaDriverless.types.Target;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

public class PlainChromeCdpFingerprintControl extends PlainChromeCdpSupport {
    @FunctionalInterface
    interface CdpJsonEvaluator {
        Map<String, Object> evaluate(String expression) throws Exception;
    }

    public static void main(String[] args) throws Exception {
        FingerprintMappingCatalog.Args parsed = FingerprintMappingCatalog.Args.parse(args);
        String session = parsed.session() == null ? FingerprintMappingCatalog.defaultSession() : parsed.session();
        String label = parsed.label().equals("java-driverless") ? "plain-chrome-cdp" : parsed.label();
        Path sessionDir = FingerprintMappingCatalog.siteDirectory(
                Path.of("captures", "fingerprint-comparison"), session);
        Path root = FingerprintMappingCatalog.siteDirectory(sessionDir, label);
        Files.createDirectories(root);

        List<Map<String, String>> sites = FingerprintMappingCatalog.loadSites(parsed.catalog(), parsed.sites());
        Map<String, String> expectedProfile = FingerprintExpectedProfile.load(parsed);
        JSON.writeValue(root.resolve("expected-profile.json").toFile(),
                FingerprintExpectedProfile.persistable(expectedProfile));

        Path userDataDir = Files.createTempDirectory("plain_chrome_cdp_");
        Process chrome = startChrome(0, userDataDir);
        int port;
        Target target = null;
        BaseTarget baseTarget = null;
        List<Map<String, Object>> manifest = new ArrayList<>();
        try {
            // Porta 0: o Chrome binda a porta e a publica em DevToolsActivePort; pré-escolher deixava uma janela
            // para outro processo local ocupá-la.
            port = DevToolsActivePortWaiter.await(userDataDir, 0, chrome, 45f);
            System.out.println("Plain Chrome CDP port: " + port);
            String targetId = waitForPageTarget(port);
            baseTarget = new BaseTarget("127.0.0.1:" + port, false, 45.0f, 1 << 27);
            baseTarget.init().join();
            target = new Target("127.0.0.1:" + port, targetId, null, null, false, 45.0f,
                    "page", false, 1 << 27);
            target.init().join();
            target.executeCdpCmd("Page.enable", null, null).join();
            target.executeCdpCmd("Runtime.enable", null, null).join();
            for (Map<String, String> site : sites) {
                manifest.add(captureSite(baseTarget, target, root, site, expectedProfile,
                        plainLocationProfile(parsed, site.get("id"))));
            }
        } finally {
            if (target != null) {
                try {
                    target.close().join();
                } catch (Exception ignored) {
                }
            }
            if (baseTarget != null) {
                try {
                    baseTarget.close().join();
                } catch (Exception ignored) {
                }
            }
            terminateProcessTree(chrome);
            deleteTree(userDataDir);
            JSON.writeValue(root.resolve("MANIFEST.json").toFile(), manifest);
            System.out.println("Artifacts: " + root.toAbsolutePath());
        }
    }

    private static Map<String, Object> captureSite(BaseTarget baseTarget, Target target, Path root,
                                                   Map<String, String> site,
                                                   Map<String, String> expectedProfile,
                                                   BrowserFingerprintProfile locationProfile) {
        String id = site.get("id");
        String url = site.get("url");
        Path dir = FingerprintMappingCatalog.siteDirectory(root, id);
        Map<String, Object> meta = new LinkedHashMap<>();
        meta.put("id", id);
        meta.put("requestedUrl", FingerprintMappingCaptureSupport.sanitizeArtifactUrl(url));
        meta.put("catalogRead", site.getOrDefault("read", ""));
        meta.put("startedAt", Instant.now().toString());
        try {
            Files.createDirectories(dir);
            System.out.println("Capturing plain-chrome-cdp " + id + " -> "
                    + FingerprintMappingCaptureSupport.sanitizeArtifactUrl(url));
            prepareRequiredPermissionsAndOverrides(baseTarget, target, locationProfile, id, url);
            CompletableFuture<JsonNode> load = target.waitForCdp("Page.loadEventFired", 45.0f);
            target.executeCdpCmd("Page.navigate", Map.of("url", url), 15.0f).join();
            try {
                load.get(45, TimeUnit.SECONDS);
            } catch (Exception ignored) {
            }
            Thread.sleep(initialWaitMs(id, site));
            Map<String, Object> runtimeEvidence = prepareSiteRuntime(target, locationProfile, id);
            if (!runtimeEvidence.isEmpty()) {
                meta.put("siteRuntimeEvidence", runtimeEvidence);
            }
            triggerIfNeeded(target, site);
            Thread.sleep(postActionSettleMs(id, site));
            requireActionCompletion(target, id);

            meta.put("finalUrl", evalString(target, "location.href"));
            meta.put("title", evalString(target, "document.title"));
            meta.put("navigator", evalJsonMap(target, FingerprintMappingScripts.NAVIGATOR_SCRIPT));
            meta.put("rawSignals", evalJsonMap(target, FingerprintMappingScripts.SIGNAL_SCRIPT));
            meta.put("detectionHints", evalJsonMap(target, FingerprintMappingScripts.DETECTION_HINT_SCRIPT));

            String page = evalString(target, "document.documentElement ? document.documentElement.outerHTML : ''");
            String body = evalString(target, "document.body ? document.body.innerText : ''");
            Map<String, Object> domEvidence = captureDomEvidence(id,
                    expression -> evalJsonMap(target, expression));
            meta.put("domEvidence", domEvidence);
            Files.writeString(dir.resolve("page.html"), page, StandardCharsets.UTF_8);
            Files.writeString(dir.resolve("body.txt"), body, StandardCharsets.UTF_8);

            Map<String, Object> validation = FingerprintMappingValidation.validate(
                    id,
                    String.valueOf(meta.getOrDefault("title", "")),
                    String.valueOf(meta.getOrDefault("finalUrl", "")),
                    body,
                    page,
                    site.getOrDefault("read", ""),
                    expectedProfile,
                    domEvidence
            );
            meta.put("validation", validation);
            captureScreenshot(target, dir.resolve("screen.png"));
            meta.put("captureStatus", "ok");
            meta.put("status", "pass".equals(validation.get("status")) ? "ok" : "validation-" + validation.get("status"));
        } catch (Exception e) {
            meta.put("status", "error");
            meta.put("error", e.toString());
        }
        meta.put("finishedAt", Instant.now().toString());
        try {
            Files.createDirectories(dir);
            JSON.writeValue(dir.resolve("metadata.json").toFile(), meta);
        } catch (Exception ignored) {
        }
        return meta;
    }

    static Map<String, Object> captureDomEvidence(String id, CdpJsonEvaluator evaluator) throws Exception {
        return evaluator.evaluate(FingerprintMappingDom.captureScript(id));
    }

    private static BrowserFingerprintProfile plainLocationProfile(FingerprintMappingCatalog.Args parsed, String id)
            throws Exception {
        ChromeOptions options = new ChromeOptions();
        Path siteProfile = parsed.siteFingerprintProfiles().getOrDefault(id, parsed.fingerprintProfile());
        if (siteProfile != null) {
            options.setFingerprintProfile(BrowserFingerprintProfile.fromJson(siteProfile));
        }
        FingerprintMappingLocationProfile.apply(options, parsed);
        return options.getFingerprintProfile();
    }

    private static void prepareRequiredPermissionsAndOverrides(BaseTarget baseTarget, Target target,
                                                               BrowserFingerprintProfile profile,
                                                               String id, String url) {
        List<String> permissions = FingerprintSitePermissionPolicy.requiredPermissions(id);
        if (!permissions.isEmpty()) {
            String origin = FingerprintSitePermissionPolicy.originOf(url);
            for (String permission : permissions) {
                baseTarget.executeCdpCmd("Browser.setPermission", Map.of(
                        "origin", origin,
                        "permission", Map.of("name", permission),
                        "setting", "granted"), null).join();
            }
        }
        if (permissions.contains("geolocation")) {
            requireGeoProfile(profile);
            setGeolocationOverride(target, profile);
        }
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> prepareSiteRuntime(Target target, BrowserFingerprintProfile profile, String id)
            throws Exception {
        if ("creepjs".equals(id)) {
            Map<String, Object> signals = evalValueMap(target, """
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
                    """);
            if (signals.isEmpty()) {
                throw new IllegalStateException("Plain Chrome CreepJS did not expose its detection signal map");
            }
            return signals;
        }
        if (!"browserleaks-geo".equals(id)) {
            return Map.of();
        }
        requireGeoProfile(profile);
        setGeolocationOverride(target, profile);
        Map<String, Object> watch = null;
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(15);
        while (System.nanoTime() < deadline) {
            Map<String, Object> candidate = evalValueMap(target, """
                    (() => {
                      const value = id => {
                        const element = document.getElementById(id);
                        return element ? (element.dataset.raw || element.textContent || '').trim() : '';
                      };
                      return {active:(document.getElementById('geo-watch-status')?.textContent || '').trim(),
                              checked:Boolean(document.getElementById('geo-watch-button')?.checked),
                              latitude:value('latitude'), longitude:value('longitude'), accuracy:value('accuracy')};
                    })()
                    """);
            if ("Active".equals(candidate.get("active")) && Boolean.TRUE.equals(candidate.get("checked"))
                    && geoMatches(candidate, profile)) {
                watch = candidate;
                break;
            }
            Thread.sleep(200);
        }
        if (watch == null) {
            throw new IllegalStateException("Plain Chrome BrowserLeaks Geo watchPosition did not return coordinates");
        }
        boolean stopped = eval(target, """
                (() => {
                  const control = document.getElementById('geo-watch-button');
                  if (!control || !control.checked) return false;
                  control.click();
                  return true;
                })()
                """, 10.0f).path("result").path("value").asBoolean(false);
        if (!stopped) {
            throw new IllegalStateException("Plain Chrome BrowserLeaks Geo watchPosition could not be stopped");
        }
        Map<String, Object> oneShot = evalValueMap(target, """
                (async () => {
                  const permission = await navigator.permissions.query({name:'geolocation'});
                  const position = await new Promise(resolve => navigator.geolocation.getCurrentPosition(
                    value => resolve({ok:true, latitude:value.coords.latitude,
                                      longitude:value.coords.longitude, accuracy:value.coords.accuracy}),
                    error => resolve({ok:false, code:error.code, message:error.message}),
                    {enableHighAccuracy:true, timeout:15000, maximumAge:0}));
                  return {permission:permission.state, position};
                })()
                """);
        if (!"granted".equals(oneShot.get("permission"))
                || !(oneShot.get("position") instanceof Map<?, ?> position)
                || !Boolean.TRUE.equals(position.get("ok")) || !geoMatches(position, profile)) {
            throw new IllegalStateException("Plain Chrome BrowserLeaks Geo getCurrentPosition mismatch");
        }
        return Map.of("permission", oneShot.get("permission"), "watchPosition", watch,
                "getCurrentPosition", oneShot.get("position"));
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> evalValueMap(Target target, String expression) throws Exception {
        JsonNode value = eval(target, expression, 20.0f).path("result").path("value");
        return value.isObject() ? JSON.convertValue(value, Map.class) : Map.of();
    }

    private static void setGeolocationOverride(Target target, BrowserFingerprintProfile profile) {
        target.executeCdpCmd("Emulation.setGeolocationOverride", Map.of(
                "latitude", profile.geolocation.latitude,
                "longitude", profile.geolocation.longitude,
                "accuracy", profile.geolocation.accuracy == null ? 100.0 : profile.geolocation.accuracy), null).join();
    }

    private static boolean geoMatches(Map<?, ?> values, BrowserFingerprintProfile profile) {
        return closeTo(values.get("latitude"), profile.geolocation.latitude)
                && closeTo(values.get("longitude"), profile.geolocation.longitude)
                && finite(values.get("accuracy"));
    }

    private static boolean closeTo(Object value, double expected) {
        try {
            return Math.abs(Double.parseDouble(String.valueOf(value)) - expected) <= 0.000_001;
        } catch (RuntimeException ignored) {
            return false;
        }
    }

    private static boolean finite(Object value) {
        try {
            return Double.isFinite(Double.parseDouble(String.valueOf(value)));
        } catch (RuntimeException ignored) {
            return false;
        }
    }

    private static void requireGeoProfile(BrowserFingerprintProfile profile) {
        if (profile == null || profile.geolocation == null
                || profile.geolocation.latitude == null || profile.geolocation.longitude == null) {
            throw new IllegalArgumentException(
                    "plain browserleaks-geo requires --geo, --profile-country or a profile with coordinates");
        }
    }


}
