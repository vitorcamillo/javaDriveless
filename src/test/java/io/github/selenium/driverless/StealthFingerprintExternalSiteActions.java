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

import static org.assertj.core.api.Assertions.assertThat;

abstract class StealthFingerprintExternalSiteActions {
    static final ObjectMapper JSON = new ObjectMapper();
    static final Map<String, List<String>> ACTION_LABELS = Map.of(
            "browserscan", List.of("start scan", "restart"),
            "printleaks", List.of("collect fingerprints", "run analysis"),
            "browserleaks-dns", List.of("run dns leak test", "run test", "start test", "check dns"),
            "browserleaks-geo", List.of("get current position", "start test", "get location", "show my location"),
            "coveryourtracks", List.of("test your browser", "test me", "start test"),
            "amiunique", List.of("accept", "agree", "ok", "start", "view my browser fingerprint")
    );
    static final Map<String, String> ACTION_SELECTORS = Map.of(
            "coveryourtracks", "#kcarterlink"
    );
    static void triggerAction(SyncChrome driver, String id) {
        if ("trustmyip-timezone".equals(id)) {
            Object triggered = false;
            long deadline = System.nanoTime() + 20_000_000_000L;
            while (System.nanoTime() < deadline && !Boolean.TRUE.equals(triggered)) {
                triggered = driver.executeScript("""
                        (() => {
                          if (typeof runTimezoneTest === 'function') { runTimezoneTest(); return true; }
                          const el = Array.from(document.querySelectorAll('button,[onclick]')).find(e =>
                            ((e.innerText || e.value || '').toLowerCase()).includes('run timezone dissonance test'));
                          if (el) el.click();
                          return Boolean(el);
                        })()
                        """);
                if (!Boolean.TRUE.equals(triggered)) driver.sleep(0.25);
            }
            assertThat(triggered).as("trustmyip-timezone required action").isEqualTo(true);
            return;
        }
        List<String> labels = ACTION_LABELS.get(id);
        if (labels == null) return;
        String json;
        try {
            json = JSON.writeValueAsString(labels);
        } catch (java.io.IOException e) {
            throw new IllegalStateException(e);
        }
        Object triggered = driver.executeScript("""
                (() => {
                  const selector = %s;
                  const labels = %s;
                  let el = null;
                  if (selector) {
                    try { el = document.querySelector(selector); } catch (_) {}
                  }
                  if (!el) el = Array.from(document.querySelectorAll('a,button,input[type=button],input[type=submit]'))
                    .find(e => labels.some(label => ((e.innerText || e.value || '').toLowerCase()).includes(label)));
                  if (el) el.click();
                  return Boolean(el);
                })()
                """.formatted(json(ACTION_SELECTORS.getOrDefault(id, "")), json));
        if ("browserleaks-geo".equals(id) || "coveryourtracks".equals(id)) {
            assertThat(triggered).as(id + " required action").isEqualTo(true);
        }
    }

    static void assertRequiredActionCompleted(SyncChrome driver, String id) {
        if ("trustmyip-timezone".equals(id)) {
            Object complete = driver.executeScript("""
                    (() => {
                      const values = ['res-ip-tz','res-br-tz','res-offset','res-risk']
                        .map(key => (document.getElementById(key)?.innerText || '').trim());
                      const summary = (document.getElementById('res-summary')?.innerText || '').trim();
                      const results = document.getElementById('tz-results');
                      return Boolean(results && !results.classList.contains('hidden') && summary
                        && values.every(value => value && value !== '--'));
                    })()
                    """);
            assertThat(complete).as(id + " completed report").isEqualTo(true);
        } else if ("coveryourtracks".equals(id)) {
            Object complete = driver.executeScript(
                    "location.hostname === 'coveryourtracks.eff.org' "
                            + "&& location.pathname.startsWith('/results') "
                            + "&& Boolean(document.getElementById('fingerprintTable'))");
            assertThat(complete).as(id + " rendered the results page").isEqualTo(true);
        }
    }

    static void waitForRequiredAction(SyncChrome driver, String id) {
        if ("pixelscan".equals(id)) {
            waitForPixelScanFinalReport(driver);
            return;
        }
        if (!"coveryourtracks".equals(id)) {
            driver.sleep(postActionWaitSeconds(id));
            return;
        }
        long deadline = System.nanoTime() + 90_000_000_000L;
        java.util.LinkedHashSet<String> trace = new java.util.LinkedHashSet<>();
        while (System.nanoTime() < deadline) {
            String current = "";
            try {
                current = driver.getCurrentTarget().getUrlViaCdp();
                trace.add(publicOriginAndPath(current));
                java.net.URI uri = java.net.URI.create(current);
                if ("coveryourtracks.eff.org".equalsIgnoreCase(uri.getHost())
                        && uri.getPath() != null && uri.getPath().startsWith("/results")) {
                    Object rendered = driver.executeScript("""
                            (() => {
                              const table = document.getElementById('fingerprintTable');
                              const text = (table && table.innerText || '').toLowerCase();
                              return Boolean(table && text.includes('bits of identifying information')
                                && text.includes('characteristic'));
                            })()
                            """);
                    if (Boolean.TRUE.equals(rendered)) return;
                }
            } catch (RuntimeException ignored) {
                trace.add("<navigation-in-progress>");
            }
            driver.sleep(0.25);
        }
        Object state;
        try {
            state = driver.executeScript("""
                    (() => ({
                      host: location.hostname,
                      path: location.pathname,
                      readyState: document.readyState,
                      actionPresent: Boolean(document.getElementById('kcarterlink')),
                      fingerprintTablePresent: Boolean(document.getElementById('fingerprintTable')),
                      queryLength: location.search.length,
                      bodyLength: document.body ? document.body.innerText.length : 0
                    }))()
                    """);
        } catch (RuntimeException failure) {
            state = "unavailable:" + failure.getClass().getSimpleName();
        }
        throw new AssertionError("coveryourtracks did not render /results; trace=" + trace
                + ", finalState=" + state);
    }

    static void waitForPixelScanFinalReport(SyncChrome driver) {
        long timeoutMillis = Math.max(5_000L, Long.getLong(
                "java.driverless.pixelscanTimeoutMillis", 90_000L));
        long deadline = System.nanoTime()
                + java.util.concurrent.TimeUnit.MILLISECONDS.toNanos(timeoutMillis);
        String lastText = "";
        while (System.nanoTime() < deadline) {
            try {
                Object raw = driver.executeScript("""
                        (() => {
                          const root = document.querySelector('pxlscn-consistency-check');
                          return (root && root.innerText || '').trim();
                        })()
                        """);
                lastText = raw == null ? "" : String.valueOf(raw);
                if (isPixelScanFinalBody(lastText)) {
                    return;
                }
            } catch (RuntimeException ignored) {
                // The Angular view can be replaced while the detector is finishing; poll again.
            }
            driver.sleep(0.5);
        }
        String normalized = normalizeStatusText(lastText);
        String state = normalized.contains("scanning") || normalized.contains("collecting data")
                ? "scanner-still-running" : normalized.isBlank() ? "scanner-root-empty" : "final-verdict-missing";
        Object structure;
        try {
            structure = driver.executeScript("""
                    (() => ({
                      readyState: document.readyState,
                      rootPresent: Boolean(document.querySelector('pxlscn-consistency-check')),
                      bodyLength: document.body ? document.body.innerText.length : -1,
                      htmlLength: document.documentElement ? document.documentElement.outerHTML.length : -1,
                      scriptCount: document.scripts.length,
                      resourceCount: performance.getEntriesByType('resource').length,
                      online: navigator.onLine,
                      visibility: document.visibilityState
                    }))()
                    """);
        } catch (RuntimeException failure) {
            Throwable root = failure;
            while (root.getCause() != null && root.getCause() != root) root = root.getCause();
            structure = "unavailable:" + failure.getClass().getSimpleName()
                    + "->" + root.getClass().getSimpleName()
                    + (root instanceof io.github.selenium.javaDriverless.cdp.exceptions.CDPException cdp
                    ? "(code=" + cdp.getCode() + ",kind=" + cdp.getKind() + ")" : "");
        }
        throw new AssertionError("pixelscan did not render a final report within "
                + timeoutMillis + "ms; state=" + state
                + ", textLength=" + lastText.length() + ", structure=" + structure);
    }

    static boolean isPixelScanFinalBody(String body) {
        String normalized = normalizeStatusText(body);
        return normalized.contains("your browser fingerprint is consistent")
                || normalized.contains("your browser fingerprint is inconsistent");
    }

    static String normalizeStatusText(String value) {
        return (value == null ? "" : value)
                .toLowerCase(java.util.Locale.ROOT)
                .replace('\u2026', ' ')
                .replaceAll("\\s+", " ")
                .trim();
    }

    static String publicOriginAndPath(String url) {
        try {
            java.net.URI uri = java.net.URI.create(url == null ? "" : url);
            if (uri.getScheme() == null || uri.getHost() == null) return "<non-http>";
            return uri.getScheme().toLowerCase() + "://" + uri.getHost().toLowerCase()
                    + (uri.getPath() == null || uri.getPath().isBlank() ? "/" : uri.getPath());
        } catch (RuntimeException ignored) {
            return "<invalid-url>";
        }
    }

    static String json(String value) {
        try {
            return JSON.writeValueAsString(value);
        } catch (java.io.IOException e) {
            throw new IllegalStateException(e);
        }
    }

    static double postActionWaitSeconds(String id) {
        return switch (id) {
            case "coveryourtracks" -> 90;
            case "browserleaks-dns", "amiunique" -> 45;
            case "browserleaks-geo", "printleaks" -> 30;
            case "browserscan" -> 20;
            case "trustmyip-timezone" -> 10;
            default -> 3;
        };
    }

    static boolean isProvenUnavailable(String message) {
        String value = message == null ? "" : message.toLowerCase();
        return value.contains("err_name_not_resolved") || value.contains("dns_probe")
                || value.contains("connection timed out") || value.contains("err_connection_timed_out")
                || value.contains("unknownhost") || value.contains("temporary failure in name resolution");
    }

    static void waitForCurrentUrl(SyncChrome driver, String expectedUrl) {
        String expectedHost = URI.create(expectedUrl).getHost();
        long deadline = System.nanoTime() + 30_000_000_000L;
        String currentUrl = "";
        while (System.nanoTime() < deadline) {
            currentUrl = driver.getCurrentTarget().getUrlViaCdp();
            if (currentUrl.contains(expectedHost)) {
                return;
            }
            driver.sleep(0.2);
        }
        throw new AssertionError("Timed out waiting for navigation to " + expectedUrl + ", current URL=" + currentUrl);
    }
}
