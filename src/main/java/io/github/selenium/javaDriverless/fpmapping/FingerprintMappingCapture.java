package io.github.selenium.javaDriverless.fpmapping;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import io.github.selenium.javaDriverless.JavaDriverless;
import io.github.selenium.javaDriverless.FingerprintSitePermissionPolicy;
import io.github.selenium.javaDriverless.types.ChromeOptions;
import io.github.selenium.javaDriverless.types.BrowserFingerprintProfile;
import io.github.selenium.javaDriverless.types.FingerprintProfileExpectedValues;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public class FingerprintMappingCapture extends FingerprintMappingCaptureSupport {
    private static final long PAGE_LOAD_TIMEOUT_MILLIS = 60_000L;

    public static void main(String[] args) throws Exception {
        FingerprintMappingCatalog.Args parsed = FingerprintMappingCatalog.Args.parse(args);
        String session = parsed.session() == null ? FingerprintMappingCatalog.defaultSession() : parsed.session();
        String label = parsed.label();
        // session/label vêm de argv: resolve em duas etapas com rejeição de escape da raiz
        Path sessionDir = FingerprintMappingCatalog.siteDirectory(
                Path.of("captures", "fingerprint-comparison"), session);
        Path root = FingerprintMappingCatalog.siteDirectory(sessionDir, label);
        Files.createDirectories(root);
        List<Map<String, String>> sites = FingerprintMappingCatalog.loadSites(parsed.catalog(), parsed.sites());
        Map<String, String> expectedProfile = FingerprintExpectedProfile.load(parsed);

        List<Map<String, Object>> manifest = new ArrayList<>();
        try {
            if (parsed.siteFingerprintProfiles().isEmpty()) {
                captureWithSingleSession(parsed, label, session, root, sites, expectedProfile, manifest);
            } else {
                captureWithSiteProfiles(parsed, label, session, root, sites, expectedProfile, manifest);
            }
        } finally {
            writeJson(root.resolve("MANIFEST.json"), manifest);
            System.out.println("Artifacts: " + root.toAbsolutePath());
        }
    }

    private static void captureWithSingleSession(FingerprintMappingCatalog.Args parsed, String label, String session,
                                                 Path root, List<Map<String, String>> sites,
                                                 Map<String, String> expectedProfile,
                                                 List<Map<String, Object>> manifest) throws Exception {
        ChromeOptions options = optionsFor(parsed, parsed.fingerprintProfile());
        BrowserFingerprintProfile activeProfile = options.getFingerprintProfile();
        Map<String, String> activeExpectedProfile = persistActiveExpectedProfile(
                root.resolve("expected-profile.json"), expectedProfile, activeProfile);
        JavaDriverless driver = null;
        try {
            driver = new JavaDriverless(profileName(label, session), options, true);
            driver.setPageLoadTimeout(PAGE_LOAD_TIMEOUT_MILLIS);
            FingerprintMappingProxy.applyAuth(driver, parsed.proxy());
            for (Map<String, String> site : sites) {
                manifest.add(captureSite(driver, root, site, activeExpectedProfile,
                        parsed.fingerprintProfile(), activeProfile));
            }
        } finally {
            if (driver != null) {
                driver.quit();
            }
        }
    }

    private static void captureWithSiteProfiles(FingerprintMappingCatalog.Args parsed, String label, String session,
                                                Path root, List<Map<String, String>> sites,
                                                Map<String, String> expectedProfile,
                                                List<Map<String, Object>> manifest) throws Exception {
        Map<String, Map<String, String>> expectedBySite = new LinkedHashMap<>();
        try {
            for (Map<String, String> site : sites) {
                String id = site.get("id");
                Path profile = parsed.siteFingerprintProfiles().getOrDefault(id, parsed.fingerprintProfile());
                ChromeOptions options = optionsFor(parsed, profile);
                BrowserFingerprintProfile activeProfile = options.getFingerprintProfile();
                Map<String, String> activeExpected = expectedProfileForActiveProfile(
                        expectedProfile, activeProfile);
                expectedBySite.put(id, activeExpected);
                JavaDriverless driver = null;
                try {
                    driver = new JavaDriverless(profileName(label + "-" + id, session), options, true);
                    driver.setPageLoadTimeout(PAGE_LOAD_TIMEOUT_MILLIS);
                    FingerprintMappingProxy.applyAuth(driver, parsed.proxy());
                    Map<String, Object> meta = captureSite(
                            driver, root, site, activeExpected, profile, activeProfile);
                    manifest.add(meta);
                } finally {
                    if (driver != null) {
                        driver.quit();
                    }
                }
            }
        } finally {
            persistSiteExpectedProfileIndex(root.resolve("expected-profile.json"), expectedBySite);
        }
    }

    private static ChromeOptions optionsFor(FingerprintMappingCatalog.Args parsed, Path fingerprintProfile)
            throws Exception {
        ChromeOptions options = new ChromeOptions();
        if (fingerprintProfile != null) {
            options.setFingerprintProfile(BrowserFingerprintProfile.fromJson(fingerprintProfile));
        }
        FingerprintMappingLocationProfile.apply(options, parsed);
        FingerprintMappingProxy.applyStartupProxy(options, parsed.proxy(), parsed.dnsPolicy(), parsed.dohTemplate());
        return options;
    }

    private static Map<String, Object> captureSite(JavaDriverless driver, Path root, Map<String, String> site,
                                                   Map<String, String> expectedProfile, Path fingerprintProfile,
                                                   BrowserFingerprintProfile activeProfile) {
        String id = site.get("id");
        String url = site.get("url");
        Path dir = FingerprintMappingCatalog.siteDirectory(root, id);
        Map<String, Object> meta = new LinkedHashMap<>();
        meta.put("id", id);
        meta.put("requestedUrl", sanitizeArtifactUrl(url));
        meta.put("catalogRead", site.getOrDefault("read", ""));
        if (fingerprintProfile != null) {
            meta.put("fingerprintProfile", fingerprintProfile.toString());
        }
        Map<String, String> activeExpectedProfile = expectedProfileForActiveProfile(
                expectedProfile, activeProfile);
        meta.put("startedAt", Instant.now().toString());
        try {
            Files.createDirectories(dir);
            writeJson(dir.resolve("expected-profile.json"),
                    FingerprintExpectedProfile.persistable(activeExpectedProfile));
            meta.put("expectedProfileArtifact", id + "/expected-profile.json");
            System.out.println("Capturing " + id + " -> " + sanitizeArtifactUrl(url));
            prepareRequiredPermissionsAndOverrides(driver, activeProfile, id, url);
            driver.get(url);
            driver.sleep(initialWaitSeconds(id, site));
            Map<String, Object> siteRuntimeEvidence = prepareSiteRuntime(driver, activeProfile, id);
            if (!siteRuntimeEvidence.isEmpty()) {
                meta.put("siteRuntimeEvidence", siteRuntimeEvidence);
            }
            FingerprintMappingSiteActions.triggerIfNeeded(driver, site);
            driver.sleep(afterActionWaitSeconds(id, site));
            FingerprintMappingSiteActions.requireCompleted(driver, id);
            requireFingerprintRuntimeHealthy(driver, id);

            String finalUrl = safeString(driver::getCurrentUrl);
            meta.put("finalUrl", sanitizeArtifactUrl(finalUrl));
            meta.put("title", safeString(driver::getTitle));
            meta.put("navigator", evaluateJson(driver, FingerprintMappingScripts.NAVIGATOR_SCRIPT));
            meta.put("rawSignals", evaluateJson(driver, FingerprintMappingScripts.SIGNAL_SCRIPT));
            meta.put("detectionHints", evaluateJson(driver, FingerprintMappingScripts.DETECTION_HINT_SCRIPT));

            String page = safeString(driver::getPageSource);
            String body = stringScript(driver, "document.body ? document.body.innerText : ''");
            Map<String, Object> domEvidence = evaluateJson(driver, FingerprintMappingDom.captureScript(id));
            meta.put("domEvidence", domEvidence);
            writeString(dir.resolve("page.html"), page);
            writeString(dir.resolve("body.txt"), body);
            captureScrollScreenshots(driver, dir, id);
            requireFingerprintRuntimeHealthy(driver, id);
            Map<String, Object> validation = FingerprintMappingValidation.validate(
                    id,
                    String.valueOf(meta.getOrDefault("title", "")),
                    finalUrl,
                    body,
                    page,
                    site.getOrDefault("read", ""),
                    activeExpectedProfile,
                    domEvidence
            );
            meta.put("validation", validation);

            meta.put("captureStatus", "ok");
            meta.put("status", "pass".equals(validation.get("status")) ? "ok" : "validation-" + validation.get("status"));
        } catch (Exception e) {
            meta.put("captureStatus", "error");
            meta.put("status", "error");
            meta.put("error", sanitizeArtifactText(e.toString()));
            if (driver != null && driver.getChrome().hasFingerprintRuntimeFailure()) {
                driver.getChrome().getFingerprintRuntimeFailure()
                        .filter(value -> !value.isBlank())
                        .map(FingerprintMappingCapture::sanitizeArtifactText)
                        .ifPresent(value -> meta.put("fingerprintRuntimeFailure", value));
            }
            persistFailureSnapshot(driver, dir, id, meta);
            try {
                Files.createDirectories(dir);
                writeJson(dir.resolve("metadata.json"), meta);
            } catch (Exception ignored) {
            }
        }
        meta.put("finishedAt", Instant.now().toString());
        try {
            Files.createDirectories(dir);
            writeJson(dir.resolve("metadata.json"), meta);
        } catch (Exception ignored) {
        }
        return meta;
    }

}
