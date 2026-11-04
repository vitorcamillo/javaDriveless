package io.github.selenium.javaDriverless.fpmapping;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import io.github.selenium.javaDriverless.FingerprintSitePermissionPolicy;
import io.github.selenium.javaDriverless.types.BaseTarget;
import io.github.selenium.javaDriverless.types.BrowserFingerprintProfile;
import io.github.selenium.javaDriverless.types.ChromeOptions;
import io.github.selenium.javaDriverless.types.Target;
import io.github.selenium.javaDriverless.utils.SafeDiagnostics;

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
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

abstract class PlainChromeCdpSupport {
    static final ObjectMapper JSON = new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT);
    static final HttpClient HTTP = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
    // Sondas no loopback não podem bloquear além do deadline de waitForPageTarget.
    static final Duration HTTP_REQUEST_TIMEOUT = Duration.ofSeconds(10);
    static final long REQUIRED_ACTION_TIMEOUT_MS = 15_000L;

    static Process startChrome(int port, Path userDataDir) throws IOException {
        String binary = env("JAVA_DRIVERLESS_BROWSER_BINARY", "/usr/bin/google-chrome-stable");
        List<String> command = new ArrayList<>();
        command.add(binary);
        boolean headless = plainChromeHeadless();
        if (headless) {
            command.add("--headless=new");
        }
        command.add("--no-sandbox");
        command.add("--disable-dev-shm-usage");
        command.add("--remote-debugging-address=127.0.0.1");
        command.add("--remote-debugging-port=" + port);
        command.add("--user-data-dir=" + userDataDir);
        command.add("--window-size=1920,1040");
        if (headless) {
            command.add("--use-gl=angle");
            command.add("--use-angle=swiftshader");
            command.add("--enable-unsafe-swiftshader");
        }
        command.addAll(splitExtraArgs(env("PLAIN_CHROME_EXTRA_ARGS", "")));
        command.add("about:blank");
        // Só nomes de flags: argv completo traz user-data-dir e PLAIN_CHROME_EXTRA_ARGS (proxy com credencial).
        System.out.println("Plain Chrome flags: " + String.join(" ",
                SafeDiagnostics.safeChromeArgumentFlags(command.subList(1, command.size()))));
        ProcessBuilder builder = new ProcessBuilder(command);
        builder.redirectErrorStream(true);
        // PLAIN_CHROME_LOG é diagnóstico opt-in e grava a saída crua do Chrome (pode conter dados sensíveis).
        String chromeLog = System.getenv("PLAIN_CHROME_LOG");
        if (chromeLog != null && !chromeLog.isBlank()) {
            builder.redirectOutput(ProcessBuilder.Redirect.appendTo(Path.of(chromeLog.trim()).toFile()));
        } else {
            builder.redirectOutput(ProcessBuilder.Redirect.DISCARD);
        }
        return builder.start();
    }

    static String waitForPageTarget(int port) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(45);
        Exception last = null;
        boolean pageCreationRequested = false;
        while (System.nanoTime() < deadline) {
            try {
                JsonNode targets = JSON.readTree(httpGet("http://127.0.0.1:" + port + "/json"));
                for (JsonNode target : targets) {
                    if ("page".equals(target.path("type").asText())) {
                        return target.path("id").asText();
                    }
                }
                // Chrome headed 150 pode publicar apenas um target inicial type=other.
                if (!pageCreationRequested) {
                    httpPut("http://127.0.0.1:" + port + "/json/new?about%3Ablank");
                    pageCreationRequested = true;
                }
            } catch (Exception e) {
                last = e;
            }
            Thread.sleep(100);
        }
        throw new IllegalStateException("Plain Chrome CDP page target not available", last);
    }

    static String httpGet(String url) throws Exception {
        HttpRequest request = HttpRequest.newBuilder(URI.create(url)).GET()
                .timeout(HTTP_REQUEST_TIMEOUT).build();
        return HTTP.send(request, HttpResponse.BodyHandlers.ofString()).body();
    }

    static String httpPut(String url) throws Exception {
        HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                .PUT(HttpRequest.BodyPublishers.noBody())
                .timeout(HTTP_REQUEST_TIMEOUT).build();
        HttpResponse<String> response = HTTP.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() / 100 != 2) {
            throw new IOException("HTTP " + response.statusCode() + " for " + url);
        }
        return response.body();
    }

    static String evalString(Target target, String expression) {
        JsonNode result = eval(target, expression, 20.0f);
        JsonNode value = result.path("result").path("value");
        return value.isMissingNode() || value.isNull() ? "" : value.asText();
    }

    @SuppressWarnings("unchecked")
    static Map<String, Object> evalJsonMap(Target target, String expression) throws Exception {
        String json = evalString(target, expression);
        if (json == null || json.isBlank()) {
            return Map.of();
        }
        return JSON.readValue(json, Map.class);
    }

    static JsonNode eval(Target target, String expression, Float timeout) {
        Map<String, Object> args = new LinkedHashMap<>();
        args.put("expression", expression);
        args.put("returnByValue", true);
        args.put("awaitPromise", true);
        return target.executeCdpCmd("Runtime.evaluate", args, timeout).join();
    }

    static void captureScreenshot(Target target, Path output) {
        try {
            JsonNode result = target.executeCdpCmd("Page.captureScreenshot",
                    Map.of("format", "png", "captureBeyondViewport", true), 20.0f).join();
            String data = result.path("data").asText("");
            if (!data.isBlank()) {
                FingerprintMappingCaptureSupport.writeBytes(output, Base64.getDecoder().decode(data));
            }
        } catch (Exception ignored) {
        }
    }

    static void triggerIfNeeded(Target target, Map<String, String> site) {
        String id = site.getOrDefault("id", "");
        if ("trustmyip-timezone".equals(id)) {
            String currentUrl = evalString(target, "location.href");
            if (!currentUrl.contains("trustmyip.com/timezone-dissonance")) {
                CompletableFuture<JsonNode> load = target.waitForCdp("Page.loadEventFired", 20.0f);
                target.executeCdpCmd("Page.navigate",
                        Map.of("url", "https://trustmyip.com/timezone-dissonance"), 10.0f).join();
                try {
                    load.get(20, TimeUnit.SECONDS);
                } catch (Exception ignored) {
                }
            }
            String script = """
                    (() => {
                      if (typeof runTimezoneTest === 'function') {
                        runTimezoneTest();
                        return true;
                      }
                      const candidates = Array.from(document.querySelectorAll('button,[onclick]'));
                      const el = candidates.find(e => ((e.innerText || e.value || '').toLowerCase()).includes('run timezone dissonance test'));
                      if (el) { el.click(); return true; }
                      return false;
                    })()
                    """;
            boolean triggered = waitForBoolean(target, script, REQUIRED_ACTION_TIMEOUT_MS);
            requireActionTriggered(site, triggered);
            return;
        }
        String actionScript = actionScript(site);
        if (actionScript.isBlank()) {
            return;
        }
        boolean required = "click".equals(site.get("runType"))
                || "browserleaks-geo".equals(id);
        boolean triggered = required
                ? waitForBoolean(target, actionScript, REQUIRED_ACTION_TIMEOUT_MS)
                : evalBoolean(target, actionScript, 10.0f);
        requireActionTriggered(site, triggered);
    }

    static String actionScript(Map<String, String> site) {
        String id = site.getOrDefault("id", "");
        List<String> labels = actionLabels(id);
        String configuredLabel = site.getOrDefault("runLabel", "").trim().toLowerCase();
        if (!configuredLabel.isBlank()) {
            labels.addFirst(configuredLabel);
        }
        String selector = site.getOrDefault("runSelector", "");
        if (selector.isBlank() && labels.isEmpty()) {
            return "";
        }
        return """
                (() => {
                  const selector = __SELECTOR__;
                  const labels = __LABELS__;
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
                """.replace("__SELECTOR__", actionJson(selector))
                .replace("__LABELS__", actionJson(labels));
    }

    static void requireActionCompletion(Target target, String id) {
        if ("trustmyip-timezone".equals(id)) {
            boolean complete = evalBoolean(target, """
                    (() => {
                      const values = ['res-ip-tz','res-br-tz','res-offset','res-risk']
                        .map(id => (document.getElementById(id)?.innerText || '').trim());
                      const summary = (document.getElementById('res-summary')?.innerText || '').trim();
                      const results = document.getElementById('tz-results');
                      return Boolean(results && !results.classList.contains('hidden') && summary
                        && values.every(value => value && value !== '--'));
                    })()
                    """, 10.0f);
            requireActionCompleted(id, complete);
        } else if ("coveryourtracks".equals(id)) {
            boolean complete = waitForBoolean(target, actionCompletionScript(id), 90_000L);
            requireActionCompleted(id, complete);
        }
    }

    static boolean waitForBoolean(Target target, String expression, long timeoutMs) {
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMs);
        do {
            try {
                if (evalBoolean(target, expression, 10.0f)) {
                    return true;
                }
            } catch (RuntimeException ignored) {
                // Navigation may briefly replace the execution context while the report loads.
            }
            if (System.nanoTime() >= deadline) {
                break;
            }
            try {
                Thread.sleep(250L);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("Interrupted while waiting for " + timeoutMs + "ms", interrupted);
            }
        } while (true);
        return false;
    }

    static String actionCompletionScript(String id) {
        if ("coveryourtracks".equals(id)) {
            return FingerprintMappingSiteActions.coverYourTracksCompletionScript();
        }
        return "";
    }

    static void requireActionTriggered(Map<String, String> site, boolean triggered) {
        String id = site.getOrDefault("id", "");
        boolean required = "click".equals(site.get("runType"))
                || "browserleaks-geo".equals(id) || "trustmyip-timezone".equals(id);
        if (required && !triggered) {
            throw new IllegalStateException(id + " required action was not found");
        }
    }

    static void requireActionCompleted(String id, boolean complete) {
        if (complete) {
            return;
        }
        if ("trustmyip-timezone".equals(id)) {
            throw new IllegalStateException(id + " action did not produce a completed report");
        }
        if ("coveryourtracks".equals(id)) {
            throw new IllegalStateException(id + " action did not render the completed results");
        }
    }

    static List<String> actionLabels(String id) {
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

    static String actionJson(Object value) {
        try {
            return JSON.writeValueAsString(value);
        } catch (IOException e) {
            throw new UncheckedIOException("Could not serialize fingerprint site action", e);
        }
    }

    static boolean evalBoolean(Target target, String expression, Float timeout) {
        return eval(target, expression, timeout).path("result").path("value").asBoolean(false);
    }

    static int configuredWaitMs(Map<String, String> site) {
        try {
            String value = site.getOrDefault("waitMs", "");
            return value.isBlank() ? 15000 : Integer.parseInt(value);
        } catch (Exception ignored) {
            return 15000;
        }
    }

    static int initialWaitMs(String id, Map<String, String> site) {
        if ("coveryourtracks".equals(id)) {
            return 3000;
        }
        return Math.max(5000, configuredWaitMs(site));
    }

    static int postActionSettleMs(String id, Map<String, String> site) {
        if ("coveryourtracks".equals(id)) {
            return 0;
        }
        return Math.max(3000, Math.min(10_000, configuredWaitMs(site) / 3));
    }

    static String env(String key, String fallback) {
        String value = System.getenv(key);
        return value == null || value.isBlank() ? fallback : value.trim();
    }

    static boolean plainChromeHeadless() {
        String value = System.getenv("PLAIN_CHROME_HEADLESS");
        return value == null || value.isBlank()
                || value.equals("1")
                || value.equalsIgnoreCase("true")
                || value.equalsIgnoreCase("yes");
    }

    static List<String> splitExtraArgs(String raw) {
        if (raw == null || raw.isBlank()) {
            return List.of();
        }
        List<String> args = new ArrayList<>();
        for (String part : raw.split("[;\\n]")) {
            String arg = part.trim();
            if (!arg.isEmpty()) {
                args.add(arg);
            }
        }
        return args;
    }

    static void terminateProcessTree(Process process) {
        if (process == null) {
            return;
        }
        ProcessHandle root = process.toHandle();
        List<ProcessHandle> descendants;
        try {
            descendants = root.descendants().toList();
        } catch (RuntimeException ignored) {
            descendants = List.of();
        }

        descendants.reversed().forEach(PlainChromeCdpFingerprintControl::destroyQuietly);
        destroyQuietly(process);
        waitForExit(root, descendants, 3, TimeUnit.SECONDS);
        descendants.reversed().forEach(PlainChromeCdpFingerprintControl::destroyForciblyQuietly);
        destroyForciblyQuietly(process);
        waitForExit(process, 2, TimeUnit.SECONDS);
        waitForExit(root, descendants, 2, TimeUnit.SECONDS);
        if (process.isAlive()) {
            throw new IllegalStateException("Chrome process root remained alive after forced cleanup");
        }
    }

    static void destroyQuietly(Process process) {
        try {
            if (process.isAlive()) {
                process.destroy();
            }
        } catch (RuntimeException ignored) {
        }
    }

    static void destroyForciblyQuietly(Process process) {
        try {
            if (process.isAlive()) {
                process.destroyForcibly();
            }
        } catch (RuntimeException ignored) {
        }
    }

    static void waitForExit(Process process, long timeout, TimeUnit unit) {
        try {
            process.waitFor(timeout, unit);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    static void destroyQuietly(ProcessHandle process) {
        try {
            if (process.isAlive()) {
                process.destroy();
            }
        } catch (RuntimeException ignored) {
        }
    }

    static void destroyForciblyQuietly(ProcessHandle process) {
        try {
            if (process.isAlive()) {
                process.destroyForcibly();
            }
        } catch (RuntimeException ignored) {
        }
    }

    static void waitForExit(ProcessHandle root, List<ProcessHandle> descendants,
                                    long timeout, TimeUnit unit) {
        long deadline = System.nanoTime() + unit.toNanos(timeout);
        while ((root.isAlive() || descendants.stream().anyMatch(ProcessHandle::isAlive))
                && System.nanoTime() < deadline) {
            try {
                Thread.sleep(20);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
        }
    }

    static void deleteTree(Path root) {
        if (root == null) {
            return;
        }
        for (int attempt = 0; attempt < 4 && Files.exists(root); attempt++) {
            try {
                Files.walkFileTree(root, new SimpleFileVisitor<>() {
                    @Override
                    public FileVisitResult visitFile(Path file, BasicFileAttributes attributes) {
                        deleteIfExists(file);
                        return FileVisitResult.CONTINUE;
                    }

                    @Override
                    public FileVisitResult visitFileFailed(Path file, IOException failure) {
                        deleteIfExists(file);
                        return FileVisitResult.CONTINUE;
                    }

                    @Override
                    public FileVisitResult postVisitDirectory(Path directory, IOException failure) {
                        deleteIfExists(directory);
                        return FileVisitResult.CONTINUE;
                    }
                });
            } catch (IOException | UncheckedIOException | SecurityException ignored) {
                // O perfil temporário pode continuar mudando por alguns ms enquanto os filhos encerram.
            }
            if (Files.notExists(root)) {
                return;
            }
            try {
                Thread.sleep(25L << attempt);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
        }
    }

    static void deleteIfExists(Path path) {
        try {
            Files.deleteIfExists(path);
        } catch (IOException | SecurityException ignored) {
        }
    }
}
