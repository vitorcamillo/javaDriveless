package io.github.selenium.driverless;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.selenium.javaDriverless.FingerprintSitePermissionPolicy;
import io.github.selenium.javaDriverless.sync.SyncChrome;
import io.github.selenium.javaDriverless.types.BrowserFingerprintProfile;
import io.github.selenium.javaDriverless.types.FingerprintProfileExpectedValues;
import org.junit.jupiter.api.Assumptions;

import java.lang.reflect.Method;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

final class StealthFingerprintExternalSiteSupport extends StealthFingerprintExternalSiteActions {
    private static final String SITE_UNRESOLVED_SKIP_PROPERTY =
            "java.driverless.external.allowSiteUnresolvedSkip";
    private static final String SITE_UNRESOLVED_SKIP_ENV =
            "JAVA_DRIVERLESS_EXTERNAL_ALLOW_SITE_UNRESOLVED_SKIP";
    private static final AtomicReference<BrowserLeaksDnsEvidence> BROWSERLEAKS_DNS_EVIDENCE =
            new AtomicReference<>();
    private static final AtomicBoolean BROWSERLEAKS_WEBRTC_EVIDENCE = new AtomicBoolean();
    private static final AtomicReference<BrowserCheckEvidence> BROWSERCHECK_EVIDENCE =
            new AtomicReference<>();
    private StealthFingerprintExternalSiteSupport() {}

    static void resetCrossSiteEvidence() {
        BROWSERLEAKS_DNS_EVIDENCE.set(null);
        BROWSERLEAKS_WEBRTC_EVIDENCE.set(false);
        BROWSERCHECK_EVIDENCE.set(null);
    }

    static void open(SyncChrome driver, String url, double settleSeconds) {
        RuntimeException lastFailure = null;
        for (int attempt = 0; attempt < 2; attempt++) {
            try {
                try {
                    driver.getCurrentTarget().executeCdpCmd("Page.stopLoading", null, 5.0f);
                } catch (Exception ignored) {
                    // Best-effort cleanup between heavy detector pages.
                }
                driver.get(url, false);
                waitForCurrentUrl(driver, url);
                driver.sleep(settleSeconds);
                return;
            } catch (RuntimeException e) {
                lastFailure = e;
            }
        }
        throw lastFailure == null ? new IllegalStateException("Could not open " + url) : lastFailure;
    }

    static String currentTitle(SyncChrome driver) {
        return driver.getCurrentTarget().getTitleViaCdp();
    }

    static int waitForBodyTextLength(SyncChrome driver, int minimumLength) {
        long deadline = System.nanoTime() + 30_000_000_000L;
        int length = 0;
        while (System.nanoTime() < deadline) {
            try {
                length = ((Number) driver.executeScript("document.body ? document.body.innerText.length : 0")).intValue();
                if (length > minimumLength) {
                    return length;
                }
            } catch (Exception ignored) {
                // The page may still be initializing; retry until the deadline.
            }
            driver.sleep(0.2);
        }
        return length;
    }

    @SuppressWarnings("unchecked")
    static Map<String, Object> assertContract(SyncChrome driver, BrowserFingerprintProfile profile,
                                              String id, String url, double settleSeconds) {
        return captureContract(driver, profile, id, url, settleSeconds, true);
    }

    @SuppressWarnings("unchecked")
    static Map<String, Object> captureContract(
            SyncChrome driver, BrowserFingerprintProfile profile,
            String id, String url, double settleSeconds, boolean requirePass) {
        System.out.println("  [external-contract] " + id + " prepare");
        prepareRequiredPermissionsAndOverrides(driver, profile, id, url);
        try {
            System.out.println("  [external-contract] " + id + " navigate");
            open(driver, url, settleSeconds);
        } catch (RuntimeException | AssertionError failure) {
            if (isProvenUnavailable(String.valueOf(failure.getMessage()))) {
                Assumptions.assumeTrue(false, "External site unavailable: " + failure.getMessage());
            }
            throw failure;
        }
        System.out.println("  [external-contract] " + id + " inspect");
        reapplyRequiredTargetOverride(driver, profile, id);
        assertBrowserLeaksWatchPositionAndStop(driver, profile, id);
        assertRequiredPermissionAndSurface(driver, profile, id);
        triggerAction(driver, id);
        waitForRequiredAction(driver, id);
        assertRequiredActionCompleted(driver, id);
        assertRenderedSiteSpecificResult(driver, profile, id);
        System.out.println("  [external-contract] " + id + " body-ready");
        waitForBodyTextLength(driver, "nowsecure".equals(id) ? 10 : 250);
        System.out.println("  [external-contract] " + id + " runtime-ready");
        assertNoFingerprintRuntimeFailure(driver, id);
        try {
            if ("creepjs".equals(id)) {
                System.out.println("  [external-contract] " + id + " signals");
                Object headlessSignals = driver.executeScript("""
                        (() => {
                          const value = window.Fingerprint && window.Fingerprint.headless;
                          return value ? {
                            likeHeadless:value.likeHeadless,
                            headless:value.headless,
                            stealth:value.stealth,
                            ratings:{likeHeadless:value.likeHeadlessRating,
                                     headless:value.headlessRating, stealth:value.stealthRating}
                          } : null;
                        })()
                        """);
                System.out.println("  CreepJS detection signals = " + headlessSignals);
            }
            System.out.println("  [external-contract] " + id + " capture");
            String title = currentTitle(driver);
            String finalUrl = driver.getCurrentTarget().getUrlViaCdp();
            String body = String.valueOf(driver.executeScript("document.body ? document.body.innerText : ''"));
            String page = String.valueOf(driver.executeScript("document.documentElement ? document.documentElement.outerHTML : ''"));

            Class<?> domClass = Class.forName("io.github.selenium.javaDriverless.fpmapping.FingerprintMappingDom");
            Method captureScript = domClass.getDeclaredMethod("captureScript", String.class);
            captureScript.setAccessible(true);
            String script = String.valueOf(captureScript.invoke(null, id));
            Object rawEvidence = driver.executeScript(script);
            Map<String, Object> domEvidence = rawEvidence instanceof Map<?, ?> map
                    ? (Map<String, Object>) map : JSON.readValue(String.valueOf(rawEvidence), Map.class);
            assertNoFingerprintRuntimeFailure(driver, id);

            Class<?> validationClass = Class.forName("io.github.selenium.javaDriverless.fpmapping.FingerprintMappingValidation");
            Method validate = validationClass.getDeclaredMethod("validate", String.class, String.class,
                    String.class, String.class, String.class, String.class, Map.class, Map.class);
            validate.setAccessible(true);
            Map<String, String> expected = expectedProfile(profile);
            if ("pixelscan".equals(id) && !hasConfiguredExpectedProfile()) {
                Assumptions.assumeTrue(false,
                        "PixelScan requires -Djava.driverless.expectedProfile=<json> "
                                + "or JAVA_DRIVERLESS_EXPECTED_PROFILE; exact policies are not downgraded to presence");
            }
            Map<String, Object> result = (Map<String, Object>) validate.invoke(
                    null, id, title, finalUrl, body, page, "", expected, domEvidence);
            if ("browser-error".equals(result.get("status")) && isProvenUnavailable(body)) {
                Assumptions.assumeTrue(false,
                        "External site unavailable: " + redactedResultSummary(result));
            }
            boolean exactTimezoneDegradation = requirePass
                    && isTimezoneEndpointDegradedWithIndependentEvidence(
                            id, profile, result, BROWSERCHECK_EVIDENCE.get(),
                            externalProxyIsAbsent());
            if (!exactTimezoneDegradation) {
                handleSiteUnresolved(id, result, allowSiteUnresolvedSkip(
                        System.getProperty(SITE_UNRESOLVED_SKIP_PROPERTY),
                        System.getenv(SITE_UNRESOLVED_SKIP_ENV)));
            }
            if ("privacy-policy-conflict".equals(result.get("status"))) {
                assertThat(id).as("site with an explicit protected-WebRTC conflict")
                        .isIn("iphey", "browserscan");
                assertThat(profile).as(id + " protected WebRTC profile").isNotNull();
                assertThat(profile.webRtc).as(id + " protected WebRTC settings").isNotNull();
                assertThat(profile.webRtc.disableUdp)
                        .as(id + " conflict requires the fail-closed UDP policy").isTrue();
                if ("iphey".equals(id)) {
                    System.out.println("  Iphey privacy-policy-conflict: WebRTC UDP remains disabled; "
                            + "the site reports WebRTC IP null and may label this as more-login.");
                } else {
                    System.out.println("  BrowserScan privacy-policy-conflict: WebRTC UNKNOWN "
                            + "under disableUdp=true; DNS UNKNOWN still requires fully attributed "
                            + "BrowserLeaks DNS evidence from this run.");
                }
            }
            recordCrossSiteEvidence(id, profile, result);
            if (requirePass) requireReleaseEvidence(id, profile, result);
            if ("browserleaks-geo".equals(id)) {
                System.out.println("  browserleaks-geo coverageLevel=runtime-exact: permissao, "
                        + "watchPosition, getCurrentPosition e coordenadas validados no navegador real.");
            } else if (!"expected-values".equals(result.get("coverageLevel"))) {
                System.out.println("  " + id + " coverageLevel=" + result.get("coverageLevel")
                        + ": fluxo/DOM validado; este resultado isolado não prova ausência de vazamento de valores.");
            }
            return result;
        } catch (ReflectiveOperationException | java.io.IOException e) {
            throw new AssertionError("Could not execute official contract for " + id, e);
        }
    }

    static void requireReleasePassStatus(String id, Map<String, Object> result) {
        if (!"pass".equals(result.get("status"))) {
            throw new AssertionError(id + " official release contract did not pass: "
                    + redactedResultSummary(result));
        }
    }

    static void requireReleaseEvidence(
            String id, BrowserFingerprintProfile profile, Map<String, Object> result) {
        if ("pass".equals(result.get("status"))) return;
        if (isBrowserScanProtectedPolicyEvidence(
                id, profile, result, BROWSERLEAKS_DNS_EVIDENCE.get(),
                BROWSERLEAKS_WEBRTC_EVIDENCE.get())) {
            System.out.println("  [release-disposition] browserscan=externally-degraded: "
                    + "WebRTC UDP permaneceu bloqueado e "
                    + "o DNS foi validado pelo contrato BrowserLeaks nesta execucao.");
            return;
        }
        if (isTimezoneEndpointDegradedWithIndependentEvidence(
                id, profile, result, BROWSERCHECK_EVIDENCE.get(),
                externalProxyIsAbsent())) {
            System.out.println("  [release-disposition] " + id + "=externally-degraded: "
                    + "endpoint de timezone por IP indisponivel; "
                    + "BrowserCheck validou IP e timezones exatos nesta execucao.");
            return;
        }
        requireReleasePassStatus(id, result);
    }

    @SuppressWarnings("unchecked")
    static boolean isBrowserScanProtectedPolicyEvidence(
            String id, BrowserFingerprintProfile profile, Map<String, Object> result) {
        return isBrowserScanProtectedPolicyEvidence(
                id, profile, result, BROWSERLEAKS_DNS_EVIDENCE.get(),
                BROWSERLEAKS_WEBRTC_EVIDENCE.get());
    }

    @SuppressWarnings("unchecked")
    static boolean isBrowserScanProtectedPolicyEvidence(
            String id, BrowserFingerprintProfile profile, Map<String, Object> result,
            BrowserLeaksDnsEvidence dnsEvidence, boolean browserLeaksWebRtcValidated) {
        if (!"browserscan".equals(id)
                || !browserLeaksWebRtcValidated
                || profile == null || profile.webRtc == null || !profile.webRtc.disableUdp
                || !"privacy-policy-conflict".equals(result.get("status"))
                || !isEmpty(result.get("missing"))
                || !isEmpty(result.get("decisiveMissing"))
                || !isEmpty(result.get("decisiveExpectedMismatches"))
                || !isEmpty(result.get("navigationBlockers"))
                || ((Number) result.getOrDefault("matchedCount", 0)).intValue() < 8) {
            return false;
        }
        Object blockers = result.get("blockers");
        if (!(blockers instanceof java.util.Collection<?> collection)) {
            return false;
        }
        java.util.Set<?> blockerSet = java.util.Set.copyOf(collection);
        boolean dnsResolvedByBrowserScan = blockerSet.equals(java.util.Set.of(
                "browserscan-webrtc-privacy-policy-conflict"));
        boolean dnsUnavailableAtBrowserScan = blockerSet.equals(java.util.Set.of(
                "browserscan-webrtc-privacy-policy-conflict",
                "browserscan-dns-unresolved"));
        if (!dnsResolvedByBrowserScan && !dnsUnavailableAtBrowserScan) return false;
        Object forbidden = result.get("forbidden");
        if (!(forbidden instanceof Map<?, ?> forbiddenMap)
                || !isEmpty(forbiddenMap.get("hits"))) {
            return false;
        }
        Object expected = result.get("expected");
        if (!(expected instanceof Map<?, ?> expectedMap)
                || !isEmpty(expectedMap.get("missing"))
                || !browserScanExpectedMismatchesAreOnlyDnsUnavailable(
                        expectedMap.get("mismatches"), dnsUnavailableAtBrowserScan)) {
            return false;
        }
        Object extracted = result.get("extracted");
        if (!(extracted instanceof Map<?, ?> extractedMap)) return false;
        Object webrtc = extractedMap.get("webrtc");
        if (!(webrtc instanceof Map<?, ?> webrtcMap)
                || !"UNKNOWN".equalsIgnoreCase(String.valueOf(webrtcMap.get("Status")))
                || !(webrtcMap.get("IP") == null
                        || String.valueOf(webrtcMap.get("IP")).isBlank())) return false;
        if (dnsResolvedByBrowserScan) return true;
        Object identity = extractedMap.get("identity");
        return dnsEvidence != null
                && identity instanceof Map<?, ?> identityMap
                && dnsEvidence.publicIp().equals(String.valueOf(identityMap.get("IP ADDRESS")))
                && ipAllowed(profile, dnsEvidence.publicIp());
    }

    static boolean isTimezoneEndpointDegradedWithIndependentEvidence(
            String id, BrowserFingerprintProfile profile, Map<String, Object> result) {
        return isTimezoneEndpointDegradedWithIndependentEvidence(
                id, profile, result, BROWSERCHECK_EVIDENCE.get(), externalProxyIsAbsent());
    }

    static boolean isTimezoneEndpointDegradedWithIndependentEvidence(
            String id, BrowserFingerprintProfile profile, Map<String, Object> result,
            BrowserCheckEvidence browserCheckEvidence, boolean stableDirectRoute) {
        if (!"trustmyip-timezone".equals(id) && !"antidetect-timezone".equals(id)) {
            return false;
        }
        boolean evidencePresent = browserCheckEvidence != null;
        boolean profileReady = profile != null && profile.timezoneId != null
                && !profile.timezoneId.isBlank();
        boolean evidenceTimezone = evidencePresent && profileReady
                && profile.timezoneId.equals(browserCheckEvidence.timezone());
        boolean evidenceIp = evidencePresent && ipAllowed(profile, browserCheckEvidence.publicIp());
        boolean rawStatus = "site-unresolved".equals(result.get("status"));
        boolean decisiveClean = isEmpty(result.get("decisiveMissing"))
                && isEmpty(result.get("decisiveExpectedMismatches"))
                && isEmpty(result.get("navigationBlockers"));
        System.out.println("  [cross-site-evidence] " + id
                + " direct=" + stableDirectRoute
                + " evidence=" + evidencePresent
                + " profile=" + profileReady
                + " timezone=" + evidenceTimezone
                + " ip=" + evidenceIp
                + " status=" + rawStatus
                + " decisive=" + decisiveClean);
        if (!stableDirectRoute || !evidencePresent || !profileReady
                || !evidenceTimezone || !evidenceIp || !rawStatus || !decisiveClean) {
            return false;
        }
        Object forbidden = result.get("forbidden");
        if (!(forbidden instanceof Map<?, ?> forbiddenMap)
                || !isEmpty(forbiddenMap.get("hits"))) return false;
        Object extracted = result.get("extracted");
        if (!(extracted instanceof Map<?, ?> extractedMap)) return false;
        String browserTimezone = String.valueOf(extractedMap.get("browserTimezone"));
        if (!browserTimezone.startsWith(profile.timezoneId)) return false;

        if ("trustmyip-timezone".equals(id)) {
            return java.util.List.of("trustmyip-timezone-unable-to-verify")
                    .equals(result.get("blockers"))
                    && isEmpty(result.get("missing"))
                    && "Not Available".equals(extractedMap.get("ipTimezone"))
                    && "UNABLE TO VERIFY".equals(extractedMap.get("result"));
        }
        if ("antidetect-timezone".equals(id)) {
            return java.util.List.of("ip-timezone-not-rendered").equals(result.get("blockers"))
                    && java.util.List.of("antidetect-timezone-status").equals(result.get("missing"))
                    && "N/A".equals(extractedMap.get("ipTimezone"))
                    && String.valueOf(extractedMap.get("status")).isBlank();
        }
        return false;
    }

    @SuppressWarnings("unchecked")
    private static void recordCrossSiteEvidence(
            String id, BrowserFingerprintProfile profile, Map<String, Object> result) {
        if ("browserleaks-dns".equals(id)) {
            boolean pass = "pass".equals(result.get("status"));
            boolean policy = "resolver-policy-validated".equals(result.get("coverageLevel"));
            boolean attributed = "validated".equals(result.get("resolverAttribution"));
            boolean provesLeakAbsence = Boolean.TRUE.equals(result.get("provesLeakAbsence"));
            boolean clean = cleanResult(result);
            System.out.println("  [cross-site-evidence] browserleaks-dns pass=" + pass
                    + " policy=" + policy + " attributed=" + attributed
                    + " provesLeakAbsence=" + provesLeakAbsence + " clean=" + clean);
            Object extracted = result.get("extracted");
            if (pass && policy && attributed && provesLeakAbsence && clean
                    && extracted instanceof Map<?, ?> map) {
                String publicIp = String.valueOf(map.get("currentIp"));
                if (ipAllowed(profile, publicIp)) {
                    BROWSERLEAKS_DNS_EVIDENCE.set(new BrowserLeaksDnsEvidence(publicIp));
                }
            }
        }
        if ("browserleaks-webrtc".equals(id)) {
            boolean pass = "pass".equals(result.get("status"));
            boolean clean = cleanResult(result);
            Object extracted = result.get("extracted");
            if (extracted instanceof Map<?, ?> map) {
                String status = String.valueOf(map.get("status"));
                String localIp = String.valueOf(map.get("localIp"));
                String publicIp = String.valueOf(map.get("publicIp"));
                boolean noLeak = status.toLowerCase(java.util.Locale.ROOT).contains("no leak");
                boolean noCandidates = !looksLikeIp(localIp) && !looksLikeIp(publicIp);
                System.out.println("  [cross-site-evidence] browserleaks-webrtc pass=" + pass
                        + " clean=" + clean + " noLeak=" + noLeak
                        + " noIpCandidates=" + noCandidates);
                BROWSERLEAKS_WEBRTC_EVIDENCE.set(pass && clean && noLeak && noCandidates);
            }
        }
        if ("browsercheck".equals(id)
                && "pass".equals(result.get("status"))) {
            Object extracted = result.get("extracted");
            if (extracted instanceof Map<?, ?> map && map.get("time") instanceof Map<?, ?> time) {
                String publicIp = String.valueOf(map.get("publicIp"));
                String ipTimezone = String.valueOf(time.get("IP Timezone"));
                String systemTimezone = String.valueOf(time.get("System Timezone"));
                boolean clean = cleanResult(result);
                boolean publicIpAllowed = ipAllowed(profile, publicIp);
                boolean ipTimezoneMatches = timezoneMatches(profile.timezoneId, ipTimezone);
                boolean systemTimezoneMatches = timezoneMatches(profile.timezoneId, systemTimezone);
                System.out.println("  [cross-site-evidence] browsercheck clean=" + clean
                        + " ipAllowed=" + publicIpAllowed
                        + " ipTimezone=" + ipTimezoneMatches
                        + " systemTimezone=" + systemTimezoneMatches);
                if (clean && publicIpAllowed && ipTimezoneMatches && systemTimezoneMatches) {
                    BROWSERCHECK_EVIDENCE.set(
                            new BrowserCheckEvidence(publicIp, profile.timezoneId));
                }
            }
        }
    }

    @SuppressWarnings("unchecked")
    static void recordBrowserCheckDifferentialEvidence(
            BrowserFingerprintProfile profile, Map<String, Object> result) {
        Object extracted = result.get("extracted");
        if (!(extracted instanceof Map<?, ?> map) || !(map.get("time") instanceof Map<?, ?> time)) {
            return;
        }
        String publicIp = String.valueOf(map.get("publicIp"));
        String ipTimezone = String.valueOf(time.get("IP Timezone"));
        String systemTimezone = String.valueOf(time.get("System Timezone"));
        boolean coherent = ipAllowed(profile, publicIp)
                && timezoneMatches(profile.timezoneId, ipTimezone)
                && timezoneMatches(profile.timezoneId, systemTimezone);
        System.out.println("  [cross-site-evidence] browsercheck differentialCoherent=" + coherent);
        if (coherent) BROWSERCHECK_EVIDENCE.set(
                new BrowserCheckEvidence(publicIp, profile.timezoneId));
    }

    private static boolean cleanResult(Map<String, Object> result) {
        Object forbidden = result.get("forbidden");
        return isEmpty(result.get("missing"))
                && isEmpty(result.get("blockers"))
                && isEmpty(result.get("decisiveMissing"))
                && isEmpty(result.get("decisiveExpectedMismatches"))
                && isEmpty(result.get("navigationBlockers"))
                && forbidden instanceof Map<?, ?> map && isEmpty(map.get("hits"));
    }

    private static boolean ipAllowed(BrowserFingerprintProfile profile, String candidate) {
        if (profile == null || profile.webRtc == null || candidate == null || candidate.isBlank()) {
            return false;
        }
        if (candidate.equals(profile.webRtc.publicIp)) return true;
        return profile.webRtc.allowedIps != null && profile.webRtc.allowedIps.contains(candidate);
    }

    private static boolean looksLikeIp(String value) {
        String candidate = value == null ? "" : value.trim();
        return candidate.matches("(?:\\d{1,3}\\.){3}\\d{1,3}")
                || candidate.matches("(?i)[0-9a-f]{0,4}(?::[0-9a-f]{0,4}){2,7}");
    }

    private static boolean externalProxyIsAbsent() {
        String property = System.getProperty("java.driverless.externalProxy");
        String environment = System.getenv("JAVA_DRIVERLESS_EXTERNAL_PROXY");
        return (property == null || property.isBlank())
                && (environment == null || environment.isBlank());
    }

    private static boolean timezoneMatches(String expected, String observed) {
        if (expected == null || observed == null) return false;
        String normalized = observed.trim();
        return normalized.equals(expected)
                || normalized.startsWith(expected + " ")
                || normalized.startsWith(expected + "(");
    }

    static record BrowserLeaksDnsEvidence(String publicIp) {}
    static record BrowserCheckEvidence(String publicIp, String timezone) {}

    private static boolean browserScanExpectedMismatchesAreOnlyDnsUnavailable(
            Object value, boolean dnsUnavailable) {
        if (!(value instanceof java.util.Collection<?> mismatches)) return false;
        if (!dnsUnavailable) return mismatches.isEmpty();
        return !mismatches.isEmpty() && mismatches.stream()
                .map(String::valueOf)
                .allMatch(mismatch -> mismatch.startsWith(
                                "expected-mismatch:browserscan.dns.Status ")
                        || mismatch.equals("actual-missing:browserscan.dns.Servers"));
    }

    private static boolean isEmpty(Object value) {
        return value instanceof java.util.Collection<?> collection && collection.isEmpty();
    }

    static void handleSiteUnresolved(String id, Map<String, Object> result,
                                     boolean skipExplicitlyAllowed) {
        if (!"site-unresolved".equals(result.get("status"))) {
            return;
        }
        if (hasEntries(result.get("decisiveMissing"))
                || hasEntries(result.get("decisiveExpectedMismatches"))) {
            throw new AssertionError(id + " site-unresolved contains an independent mismatch: "
                    + redactedResultSummary(result));
        }
        if (skipExplicitlyAllowed) {
            Assumptions.assumeTrue(false,
                    "site-unresolved skip explicitly enabled for " + id + ": "
                            + redactedResultSummary(result));
            return;
        }
        throw new AssertionError(id + " official contract returned site-unresolved: "
                + redactedResultSummary(result)
                + ". Unresolved site mappings fail by default; an intentional skip requires -D"
                + SITE_UNRESOLVED_SKIP_PROPERTY + "=true or " + SITE_UNRESOLVED_SKIP_ENV + "=1");
    }

    static String redactedResultSummary(Map<String, Object> result) {
        if (result == null) return "{status=missing-result}";
        return "{status=" + result.getOrDefault("status", "missing")
                + ", coverageLevel=" + result.getOrDefault("coverageLevel", "unknown")
                + ", missingCount=" + collectionSize(result.get("missing"))
                + ", missingFields=" + safeSignalFields(result.get("missing"))
                + ", blockerCount=" + collectionSize(result.get("blockers"))
                + ", mismatchCount=" + collectionSize(result.get("decisiveExpectedMismatches"))
                + ", mismatchFields=" + mismatchFields(result.get("decisiveExpectedMismatches"))
                + ", forbiddenCount=" + forbiddenHitCount(result.get("forbidden")) + "}";
    }

    private static java.util.List<String> mismatchFields(Object value) {
        if (!(value instanceof java.util.Collection<?> collection)) return java.util.List.of();
        return collection.stream().map(String::valueOf).map(mismatch -> {
            String prefix = mismatch.startsWith("expected-mismatch:")
                    ? "expected-mismatch:" : mismatch.startsWith("actual-missing:")
                    ? "actual-missing:" : "";
            if (prefix.isEmpty()) return "redacted";
            String remainder = mismatch.substring(prefix.length());
            int boundary = remainder.indexOf(' ');
            String field = boundary < 0 ? remainder : remainder.substring(0, boundary);
            return field.matches("[A-Za-z0-9._-]+") ? field : "redacted";
        }).distinct().sorted().toList();
    }

    private static java.util.List<String> safeSignalFields(Object value) {
        if (!(value instanceof java.util.Collection<?> collection)) return java.util.List.of();
        return collection.stream().map(String::valueOf)
                .map(field -> field.matches("[A-Za-z0-9._-]+") ? field : "redacted")
                .distinct().sorted().toList();
    }

    private static int collectionSize(Object value) {
        return value instanceof java.util.Collection<?> collection ? collection.size() : 0;
    }

    private static int forbiddenHitCount(Object value) {
        return value instanceof Map<?, ?> map ? collectionSize(map.get("hits")) : 0;
    }

    private static boolean hasEntries(Object value) {
        return value instanceof java.util.Collection<?> collection && !collection.isEmpty();
    }

    static boolean allowSiteUnresolvedSkip(String propertyValue, String environmentValue) {
        if (propertyValue != null) {
            return isExplicitTrue(propertyValue);
        }
        return isExplicitTrue(environmentValue);
    }

    private static boolean isExplicitTrue(String value) {
        if (value == null) {
            return false;
        }
        String normalized = value.trim();
        return "1".equals(normalized) || "true".equalsIgnoreCase(normalized);
    }

    static void assertNoFingerprintRuntimeFailure(SyncChrome driver, String id) {
        if (!driver.hasFingerprintRuntimeFailure()) {
            return;
        }
        String diagnostic = driver.getFingerprintRuntimeFailure()
                .filter(value -> !value.isBlank())
                .map(StealthFingerprintExternalSiteSupport::sanitizeRuntimeFailure)
                .orElse("diagnóstico indisponível");
        throw new AssertionError(id + " teve falha assíncrona no fingerprint: " + diagnostic);
    }

    private static String sanitizeRuntimeFailure(String message) {
        String sanitized = message
                .replaceAll("[\\r\\n\\t]+", " ")
                .replaceAll("(?i)(?:https?|wss?|file)://\\S+", "<url>")
                .replaceAll("(?i)(authorization|proxy-authorization|cookie|set-cookie|x-api-key|"
                                + "api[_-]?key|password|passwd|secret|token)\\s*[:=]\\s*[^\\s|,;]+",
                        "$1=<redacted>")
                .trim();
        return sanitized.length() <= 512 ? sanitized : sanitized.substring(0, 512) + "…";
    }

    private static void prepareRequiredPermissionsAndOverrides(SyncChrome driver, BrowserFingerprintProfile profile,
                                                               String id, String url) {
        List<String> permissions = FingerprintSitePermissionPolicy.requiredPermissions(id);
        if (!permissions.isEmpty()) {
            String origin = FingerprintSitePermissionPolicy.originOf(url);
            for (String permission : permissions) {
                driver.setPermission(origin, permission, "granted");
            }
        }
        if (permissions.contains("geolocation")) {
            if (profile == null || profile.geolocation == null
                    || profile.geolocation.latitude == null || profile.geolocation.longitude == null) {
                throw new IllegalArgumentException(id + " requires a profile with latitude and longitude");
            }
            driver.setGeolocationOverride(profile.geolocation.latitude, profile.geolocation.longitude,
                    profile.geolocation.accuracy);
        }
    }

    private static void reapplyRequiredTargetOverride(SyncChrome driver, BrowserFingerprintProfile profile, String id) {
        if (FingerprintSitePermissionPolicy.requiredPermissions(id).contains("geolocation")) {
            driver.setGeolocationOverride(profile.geolocation.latitude, profile.geolocation.longitude,
                    profile.geolocation.accuracy);
        }
    }

    @SuppressWarnings("unchecked")
    private static void assertBrowserLeaksWatchPositionAndStop(SyncChrome driver,
                                                               BrowserFingerprintProfile profile, String id) {
        if (!"browserleaks-geo".equals(id)) {
            return;
        }
        Map<String, Object> watch = null;
        long deadline = System.nanoTime() + 15_000_000_000L;
        while (System.nanoTime() < deadline) {
            Object raw = driver.executeScript("""
                    (() => {
                      const value = id => {
                        const element = document.getElementById(id);
                        return element ? (element.dataset.raw || element.textContent || '').trim() : '';
                      };
                      return {
                        active: (document.getElementById('geo-watch-status')?.textContent || '').trim(),
                        checked: Boolean(document.getElementById('geo-watch-button')?.checked),
                        latitude: value('latitude'), longitude: value('longitude'), accuracy: value('accuracy')
                      };
                    })()
                    """);
            if (raw instanceof Map<?, ?> map
                    && "Active".equals(map.get("active"))
                    && Boolean.TRUE.equals(map.get("checked"))
                    && isFiniteNumber(map.get("latitude")) && isFiniteNumber(map.get("longitude"))
                    && isFiniteNumber(map.get("accuracy"))) {
                watch = (Map<String, Object>) raw;
                break;
            }
            driver.sleep(0.2);
        }
        assertThat(watch).as("browserleaks-geo watchPosition rendered result").isNotNull();
        assertThat(Double.parseDouble(String.valueOf(watch.get("latitude"))))
                .isCloseTo(profile.geolocation.latitude, org.assertj.core.data.Offset.offset(0.000_001));
        assertThat(Double.parseDouble(String.valueOf(watch.get("longitude"))))
                .isCloseTo(profile.geolocation.longitude, org.assertj.core.data.Offset.offset(0.000_001));

        Object stopped = driver.executeScript("""
                (() => {
                  const control = document.getElementById('geo-watch-button');
                  if (!control || !control.checked) return false;
                  control.click();
                  return true;
                })()
                """);
        assertThat(stopped).as("browserleaks-geo stop watchPosition action").isEqualTo(true);
    }

    private static boolean isFiniteNumber(Object value) {
        try {
            return Double.isFinite(Double.parseDouble(String.valueOf(value)));
        } catch (RuntimeException ignored) {
            return false;
        }
    }

    @SuppressWarnings("unchecked")
    private static void assertRequiredPermissionAndSurface(SyncChrome driver, BrowserFingerprintProfile profile,
                                                            String id) {
        if (!"browserleaks-geo".equals(id)) {
            return;
        }
        assertThat(profile).as("controlled fingerprint profile").isNotNull();
        assertThat(profile.geolocation.latitude).as("profile geolocation latitude").isNotNull();
        assertThat(profile.geolocation.longitude).as("profile geolocation longitude").isNotNull();
        Object raw = driver.executeScript("""
                (async () => {
                  const permission = await navigator.permissions.query({name: 'geolocation'});
                  const position = await new Promise(resolve =>
                    navigator.geolocation.getCurrentPosition(
                      value => resolve({ok: true, value}),
                      error => resolve({ok: false, error: {code: error.code, message: error.message}}), {
                      enableHighAccuracy: true, timeout: 15000, maximumAge: 0
                    }));
                  if (!position.ok) {
                    return {permission: permission.state, error: position.error};
                  }
                  return {
                    permission: permission.state,
                    latitude: position.value.coords.latitude,
                    longitude: position.value.coords.longitude,
                    accuracy: position.value.coords.accuracy
                  };
                })()
                """, null, true);
        assertThat(raw).as("browserleaks-geo Geolocation API result").isInstanceOf(Map.class);
        Map<String, Object> geo = (Map<String, Object>) raw;
        assertThat(geo.get("permission")).isEqualTo("granted");
        assertThat(geo).as("browserleaks-geo position result: " + geo).doesNotContainKey("error");
        assertThat(((Number) geo.get("latitude")).doubleValue())
                .isCloseTo(profile.geolocation.latitude, org.assertj.core.data.Offset.offset(0.000_001));
        assertThat(((Number) geo.get("longitude")).doubleValue())
                .isCloseTo(profile.geolocation.longitude, org.assertj.core.data.Offset.offset(0.000_001));
        assertThat(((Number) geo.get("accuracy")).doubleValue()).isFinite().isGreaterThanOrEqualTo(0.0);
    }

    @SuppressWarnings("unchecked")
    private static void assertRenderedSiteSpecificResult(SyncChrome driver, BrowserFingerprintProfile profile,
                                                         String id) {
        if (!"browserleaks-geo".equals(id)) {
            return;
        }
        Object raw = driver.executeScript("""
                (() => {
                  const value = id => {
                    const element = document.getElementById(id);
                    return element ? (element.dataset.raw || element.textContent || '').trim() : '';
                  };
                  return {latitude: value('latitude'), longitude: value('longitude'), accuracy: value('accuracy')};
                })()
                """);
        assertThat(raw).as("browserleaks-geo rendered values").isInstanceOf(Map.class);
        Map<String, Object> rendered = (Map<String, Object>) raw;
        assertThat(Double.parseDouble(String.valueOf(rendered.get("latitude"))))
                .isCloseTo(profile.geolocation.latitude, org.assertj.core.data.Offset.offset(0.000_001));
        assertThat(Double.parseDouble(String.valueOf(rendered.get("longitude"))))
                .isCloseTo(profile.geolocation.longitude, org.assertj.core.data.Offset.offset(0.000_001));
        assertThat(Double.parseDouble(String.valueOf(rendered.get("accuracy"))))
                .isFinite().isGreaterThanOrEqualTo(0.0);
    }

    @SuppressWarnings("unchecked")
    static Map<String, String> expectedProfile(BrowserFingerprintProfile profile) {
        Map<String, String> flattened = new java.util.LinkedHashMap<>();
        String configured = System.getProperty("java.driverless.expectedProfile");
        if (configured == null || configured.isBlank()) {
            configured = System.getenv("JAVA_DRIVERLESS_EXPECTED_PROFILE");
        }
        if (configured != null && !configured.isBlank()) {
            Path path = Path.of(configured.trim());
            if (!Files.isRegularFile(path)) {
                throw new IllegalArgumentException("Expected-profile file does not exist: " + path);
            }
            try {
                Object raw = JSON.readValue(path.toFile(), Object.class);
                if (raw instanceof Map<?, ?> root && root.get("values") instanceof Map<?, ?> values) {
                    raw = values;
                }
                flattenExpected("", raw, flattened);
            } catch (java.io.IOException e) {
                throw new IllegalArgumentException("Could not read expected-profile " + path, e);
            }
        }
        FingerprintProfileExpectedValues.derive(profile).forEach(flattened::putIfAbsent);
        return flattened;
    }

    private static boolean hasConfiguredExpectedProfile() {
        String configured = System.getProperty("java.driverless.expectedProfile");
        if (configured == null || configured.isBlank()) {
            configured = System.getenv("JAVA_DRIVERLESS_EXPECTED_PROFILE");
        }
        return configured != null && !configured.isBlank();
    }

    private static void flattenExpected(String prefix, Object value, Map<String, String> out) {
        if (value instanceof Map<?, ?> map) {
            map.forEach((key, item) -> flattenExpected(
                    prefix.isBlank() ? String.valueOf(key) : prefix + "." + key, item, out));
        } else if (value instanceof Iterable<?> values) {
            java.util.ArrayList<String> items = new java.util.ArrayList<>();
            values.forEach(item -> items.add(String.valueOf(item)));
            out.put(prefix, String.join(",", items));
        } else if (value != null && !prefix.isBlank()) {
            out.put(prefix, String.valueOf(value));
        }
    }

}
