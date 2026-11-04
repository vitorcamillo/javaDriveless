package io.github.selenium.javaDriverless.fpmapping;

import io.github.selenium.javaDriverless.JavaDriverless;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.BooleanSupplier;

final class FingerprintMappingSiteActions {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final long REQUIRED_ACTION_TIMEOUT_MS = 15_000L;
    private static final long COVER_YOUR_TRACKS_COMPLETION_TIMEOUT_MS = 90_000L;

    private FingerprintMappingSiteActions() {
    }

    static void triggerIfNeeded(JavaDriverless driver, Map<String, String> site) {
        triggerIfNeeded(driver, site, REQUIRED_ACTION_TIMEOUT_MS);
    }

    static void triggerIfNeeded(JavaDriverless driver, Map<String, String> site, long timeoutMs) {
        String id = site.getOrDefault("id", "");
        if ("trustmyip-timezone".equals(id)) {
            if (!pollUntilTrue(driver, timeoutMs, () -> triggerTrustMyIpTimezone(driver))) {
                throw new IllegalStateException(id + " required action was not found");
            }
            return;
        }

        List<String> labels = labelsFor(id);
        String configuredLabel = site.getOrDefault("runLabel", "").trim().toLowerCase();
        if (!configuredLabel.isBlank()) {
            labels.addFirst(configuredLabel);
        }
        String selector = site.getOrDefault("runSelector", "");
        if (selector.isBlank() && labels.isEmpty()) {
            return;
        }
        boolean required = "click".equals(site.get("runType")) || "browserleaks-geo".equals(id);
        boolean triggered = required
                ? pollUntilTrue(driver, timeoutMs, () -> clickBySelectorOrLabels(driver, selector, labels))
                : clickBySelectorOrLabels(driver, selector, labels);
        if (required && !triggered) {
            throw new IllegalStateException(id + " required action was not found");
        }
    }

    static void requireCompleted(JavaDriverless driver, String id) {
        requireCompleted(driver, id, COVER_YOUR_TRACKS_COMPLETION_TIMEOUT_MS);
    }

    static void requireCompleted(JavaDriverless driver, String id, long timeoutMs) {
        boolean complete;
        if ("trustmyip-timezone".equals(id)) {
            complete = pollUntilTrue(driver, timeoutMs,
                    () -> Boolean.TRUE.equals(driver.executeScript("""
                            (() => {
                              const values = ['res-ip-tz','res-br-tz','res-offset','res-risk']
                                .map(id => (document.getElementById(id)?.innerText || '').trim());
                              const summary = (document.getElementById('res-summary')?.innerText || '').trim();
                              const results = document.getElementById('tz-results');
                              return Boolean(results && !results.classList.contains('hidden') && summary
                                && values.every(value => value && value !== '--'));
                            })()
                            """)));
            if (!complete) {
                throw new IllegalStateException(id + " action did not produce a completed report");
            }
        } else if ("coveryourtracks".equals(id)) {
            complete = pollUntilTrue(driver, timeoutMs,
                    () -> Boolean.TRUE.equals(driver.executeScript(coverYourTracksCompletionScript())));
            if (!complete) {
                throw new IllegalStateException(id + " action did not render the completed results");
            }
        } else if ("pixelscan".equals(id)) {
            complete = pollUntilTrue(driver, timeoutMs,
                    () -> Boolean.TRUE.equals(driver.executeScript(pixelScanCompletionScript())));
            if (!complete) {
                throw new IllegalStateException(id + " did not render a final report");
            }
        }
    }

    private static boolean pollUntilTrue(JavaDriverless driver, long timeoutMs, BooleanSupplier probe) {
        long deadline = System.nanoTime() + Math.max(0L, timeoutMs) * 1_000_000L;
        while (true) {
            try {
                if (probe.getAsBoolean()) {
                    return true;
                }
            } catch (RuntimeException ignored) {
                // Navigation may temporarily replace the JavaScript execution context.
            }
            long remainingNanos = deadline - System.nanoTime();
            if (remainingNanos <= 0L) {
                return false;
            }
            driver.sleep(Math.min(0.25, remainingNanos / 1_000_000_000.0));
        }
    }

    static String coverYourTracksCompletionScript() {
        return """
                (() => {
                  const table = document.getElementById('fingerprintTable');
                  const text = (table && table.innerText || '').toLowerCase();
                  return location.hostname === 'coveryourtracks.eff.org'
                    && location.pathname.startsWith('/results')
                    && Boolean(table && text.includes('bits of identifying information')
                      && text.includes('characteristic'));
                })()
                """;
    }

    static String pixelScanCompletionScript() {
        return """
                (() => {
                  const root = document.querySelector('pxlscn-consistency-check');
                  const text = (root && root.innerText || '').toLowerCase();
                  return text.includes('fingerprint is consistent')
                    || text.includes('fingerprint is inconsistent');
                })()
                """;
    }

    private static boolean triggerTrustMyIpTimezone(JavaDriverless driver) {
        String currentUrl = String.valueOf(driver.executeScript("return location.href"));
        if (!currentUrl.contains("trustmyip.com/timezone-dissonance")) {
            driver.get("https://trustmyip.com/timezone-dissonance");
            driver.sleep(3);
        }
        return Boolean.TRUE.equals(driver.executeScript("""
                (function(){
                  if (typeof runTimezoneTest === 'function') {
                    runTimezoneTest();
                    return true;
                  }
                  const candidates = Array.from(document.querySelectorAll('button,[onclick]'));
                  const el = candidates.find(e => ((e.innerText || e.value || '').toLowerCase()).includes('run timezone dissonance test'));
                  if (el) { el.click(); return true; }
                  return false;
                })()
                """));
    }

    private static boolean clickBySelectorOrLabels(JavaDriverless driver, String selector, List<String> labels) {
        String selectorJson = json(selector == null ? "" : selector);
        String labelsJson = json(labels);
        return Boolean.TRUE.equals(driver.executeScript("""
                (function(){
                  const selector = %s;
                  const labels = %s;
                  const items = Array.from(document.querySelectorAll('a,button,input[type=button],input[type=submit]'));
                  let el = null;
                  if (selector) {
                    try { el = document.querySelector(selector); } catch (_) {}
                  }
                  if (!el) el = items.find(e => labels.some(label =>
                    ((e.innerText || e.value || '').toLowerCase()).includes(label)));
                  if (el) { el.click(); return true; }
                  return false;
                })()
                """.formatted(selectorJson, labelsJson)));
    }

    private static List<String> labelsFor(String id) {
        return new ArrayList<>(switch (id) {
            case "coveryourtracks" -> List.of("test your browser", "test me", "start test");
            case "browserleaks-dns" -> List.of("run dns leak test", "run test", "start test", "check dns");
            case "browserleaks-geo" -> List.of(
                    "get current position", "start test", "get location", "show my location");
            case "amiunique" -> List.of("accept", "agree", "ok", "start", "view my browser fingerprint");
            case "printleaks" -> List.of("collect fingerprints", "run analysis");
            case "browserscan" -> List.of("start scan", "restart");
            default -> List.of();
        });
    }

    private static String json(Object value) {
        try {
            return JSON.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Could not serialize fingerprint site action", e);
        }
    }
}
