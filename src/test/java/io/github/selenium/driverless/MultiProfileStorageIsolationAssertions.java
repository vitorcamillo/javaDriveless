package io.github.selenium.driverless;

import static io.github.selenium.driverless.MultiProfileStorageIsolationScripts.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import io.github.selenium.javaDriverless.JavaDriverless;
import io.github.selenium.javaDriverless.types.BrowserFingerprintProfile;
import io.github.selenium.javaDriverless.types.ChromeOptions;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Prova live opt-in de isolamento de storage e fingerprint entre dois processos Chrome reais.
 *
 * <p>A fixture fica restrita a uma origem HTTP de loopback. A campanha só roda quando
 * {@code JAVA_DRIVERLESS_LIVE_ISOLATION_REQUIRED=1}; o valor padrão da origem é
 * uma porta dinâmica em {@code 127.0.0.1} e pode ser alterado por
 * {@code JAVA_DRIVERLESS_LIVE_ORIGIN}.</p>
 */
@Tag("external")
abstract class MultiProfileStorageIsolationAssertions {
    static void assertFingerprintStable(
            Map<String, Object> first, Map<String, Object> repeated) {
        assertThat(repeated).isEqualTo(first);
    }

    static void assertFingerprintCoherentAcrossRealms(
            Map<String, Object> page,
            Map<String, Object> dedicatedWorker,
            Map<String, Object> serviceWorker) {
        List<String> sharedSurfaces = List.of(
                "userAgent", "userAgentData", "hardwareConcurrency", "deviceMemory", "platform",
                "language", "languages", "intlLocale", "timezone", "timezoneOffset", "canvasHash",
                "geometryCanvasHash", "rectCanvasHash", "drawImageCanvasHash",
                "webgl", "connection", "storageEstimate");
        for (String surface : sharedSurfaces) {
            assertThat(dedicatedWorker.get(surface)).as("dedicated worker %s", surface)
                    .isEqualTo(page.get(surface));
            assertThat(serviceWorker.get(surface)).as("service worker %s", surface)
                    .isEqualTo(page.get(surface));
        }
        if (page.get("audioHash") != null && dedicatedWorker.get("audioHash") != null) {
            assertThat(dedicatedWorker.get("audioHash")).isEqualTo(page.get("audioHash"));
        }
        if (page.get("audioHash") != null && serviceWorker.get("audioHash") != null) {
            assertThat(serviceWorker.get("audioHash")).isEqualTo(page.get("audioHash"));
        }
    }

    static void assertFingerprintsDistinct(
            Map<String, Object> profileA, Map<String, Object> profileB) {
        assertThat(profileB).isNotEqualTo(profileA);
        assertThat(profileB.get("canvasHash")).isEqualTo(profileA.get("canvasHash"));
        assertThat(profileB.get("geometryCanvasHash"))
                .isNotEqualTo(profileA.get("geometryCanvasHash"));
        assertThat(profileB.get("rectCanvasHash"))
                .isNotEqualTo(profileA.get("rectCanvasHash"));
        assertThat(profileB.get("drawImageCanvasHash"))
                .isNotEqualTo(profileA.get("drawImageCanvasHash"));
        assertThat(nestedMap(profileB, "webgl").get("hash"))
                .isNotEqualTo(nestedMap(profileA, "webgl").get("hash"));
        if (profileA.get("audioHash") != null && profileB.get("audioHash") != null) {
            assertThat(profileB.get("audioHash")).isNotEqualTo(profileA.get("audioHash"));
        }
    }

    @SuppressWarnings("unchecked")
    static Map<String, Object> nestedMap(Map<String, Object> parent, String key) {
        assertThat(parent.get(key)).as(key).isInstanceOf(Map.class);
        return (Map<String, Object>) parent.get(key);
    }

    static void assertEmptySnapshot(Map<String, Object> snapshot) {
        assertThat(snapshot)
                .containsEntry("cookie", null)
                .containsEntry("sessionCookie", null)
                .containsEntry("localStorage", null)
                .containsEntry("sessionStorage", null)
                .containsEntry("indexedDb", null)
                .containsEntry("cacheStorage", null)
                .containsEntry("serviceWorkerRegistered", false)
                .containsEntry("serviceWorker", null);
    }

    static void assertOwnSnapshot(Map<String, Object> snapshot, String token) {
        assertThat(snapshot)
                .containsEntry("cookie", token)
                .containsEntry("sessionCookie", token)
                .containsEntry("localStorage", token)
                .containsEntry("sessionStorage", token)
                .containsEntry("indexedDb", token)
                .containsEntry("cacheStorage", token)
                .containsEntry("serviceWorkerRegistered", true)
                .containsEntry("serviceWorker", token);
    }

    static void assertPersistentSnapshotAfterRestart(Map<String, Object> snapshot, String token) {
        assertThat(snapshot)
                .containsEntry("cookie", token)
                .containsEntry("sessionCookie", null)
                .containsEntry("localStorage", token)
                .containsEntry("sessionStorage", null)
                .containsEntry("indexedDb", token)
                .containsEntry("cacheStorage", token)
                .containsEntry("serviceWorkerRegistered", true)
                .containsEntry("serviceWorker", token);
    }

    static URI normalizedLoopbackOrigin(String rawOrigin) throws Exception {
        URI origin = URI.create(rawOrigin.trim());
        if (!"http".equalsIgnoreCase(origin.getScheme())) {
            throw new IllegalArgumentException(ORIGIN_ENV + " must use http for the local fixture");
        }
        if (origin.getUserInfo() != null || origin.getHost() == null
                || origin.getQuery() != null || origin.getFragment() != null
                || (origin.getPath() != null && !origin.getPath().isBlank() && !"/".equals(origin.getPath()))) {
            throw new IllegalArgumentException(ORIGIN_ENV + " must be an origin without credentials, path, query or fragment");
        }
        InetAddress address = InetAddress.getByName(origin.getHost());
        if (!address.isLoopbackAddress()) {
            throw new IllegalArgumentException(ORIGIN_ENV + " must resolve to loopback; external traffic is forbidden");
        }
        int port = origin.getPort() >= 0 ? origin.getPort() : 80;
        return new URI("http", null, origin.getHost(), port, null, null, null);
    }

    static Map<String, Object> sanitizedOrigin(String rawOrigin) {
        Map<String, Object> sanitized = new LinkedHashMap<>();
        try {
            URI origin = normalizedLoopbackOrigin(rawOrigin);
            sanitized.put("scheme", origin.getScheme());
            sanitized.put("host", origin.getHost());
            sanitized.put("port", origin.getPort());
        } catch (Exception invalid) {
            sanitized.put("valid", false);
        }
        return sanitized;
    }

    static boolean isAlive(long pid) {
        return ProcessHandle.of(pid).map(ProcessHandle::isAlive).orElse(false);
    }

    static void waitForExit(long pid, Duration timeout) throws Exception {
        long deadline = System.nanoTime() + timeout.toNanos();
        while (isAlive(pid) && System.nanoTime() < deadline) {
            Thread.sleep(25);
        }
    }

    static Throwable waitForExitQuietly(long pid) {
        try {
            waitForExit(pid, Duration.ofSeconds(15));
            if (isAlive(pid)) {
                return new AssertionError("Chrome process did not exit during cleanup");
            }
            return null;
        } catch (Throwable failure) {
            return failure;
        }
    }

    static Throwable safeQuit(JavaDriverless driver) {
        if (driver == null) return null;
        try {
            driver.quit();
            return null;
        } catch (Throwable failure) {
            return failure;
        }
    }

    static Throwable shutdownExecutor(ExecutorService executor) {
        if (executor == null) return null;
        try {
            executor.shutdownNow();
            if (!executor.awaitTermination(60, TimeUnit.SECONDS)) {
                return new AssertionError("Live launch executor did not terminate during cleanup");
            }
            return null;
        } catch (Throwable failure) {
            return failure;
        }
    }

    static Throwable deleteTreeQuietly(Path root) {
        if (root == null || !Files.exists(root)) return null;
        try (var paths = Files.walk(root)) {
            for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) {
                Files.deleteIfExists(path);
            }
            return null;
        } catch (Throwable failure) {
            return failure;
        }
    }

    static Throwable merge(Throwable current, Throwable next) {
        if (next == null) return current;
        if (current == null) return next;
        current.addSuppressed(next);
        return current;
    }

    static java.util.concurrent.ThreadFactory daemonThreadFactory(String prefix) {
        AtomicInteger sequence = new AtomicInteger();
        return task -> {
            Thread thread = new Thread(task, prefix + sequence.incrementAndGet());
            thread.setDaemon(true);
            return thread;
        };
    }

    static final class OriginFixture implements AutoCloseable {
        static final byte[] HTML = ("""
                <!doctype html>
                <html lang="en"><head><meta charset="utf-8"><title>JavaDriverless isolation fixture</title></head>
                <body data-jdl-live-isolation="ready">local isolation fixture
                <script>
                """ + FINGERPRINT_SNAPSHOT_FUNCTION + """
                window.addEventListener('message', async event => {
                  if (!event.data || event.data.type !== 'jdl-fingerprint-request' || !event.source) return;
                  event.source.postMessage({
                    token: event.data.token,
                    snapshot: await jdlFingerprintSnapshot()
                  }, event.origin);
                });
                </script></body></html>
                """).getBytes(StandardCharsets.UTF_8);

        private final HttpServer server;
        private final ExecutorService executor;
        private final String origin;

        private OriginFixture(HttpServer server, ExecutorService executor, String origin) {
            this.server = server;
            this.executor = executor;
            this.origin = origin;
        }

        static OriginFixture start(URI origin) throws IOException {
            HttpServer server = HttpServer.create(
                    new InetSocketAddress(origin.getHost(), origin.getPort()), 0);
            ExecutorService executor = Executors.newFixedThreadPool(
                    2, daemonThreadFactory("jdl-live-origin-"));
            server.setExecutor(executor);
            server.createContext(SERVICE_WORKER_PATH, exchange -> respond(
                    exchange,
                    200,
                    "application/javascript; charset=utf-8",
                    SERVICE_WORKER_SCRIPT.getBytes(StandardCharsets.UTF_8),
                    Map.of("Service-Worker-Allowed", "/")));
            server.createContext("/", exchange -> respond(
                    exchange,
                    200,
                    "text/html; charset=utf-8",
                    HTML,
                    Map.of()));
            server.start();
            String boundOrigin = "http://" + origin.getHost() + ":" + server.getAddress().getPort();
            return new OriginFixture(server, executor, boundOrigin);
        }

        String origin() {
            return origin;
        }

        String alternateOrigin() {
            URI current = URI.create(origin);
            String alternateHost = "localhost".equalsIgnoreCase(current.getHost())
                    ? "127.0.0.1" : "localhost";
            return "http://" + alternateHost + ":" + current.getPort();
        }

        @Override
        public void close() {
            server.stop(0);
            executor.shutdownNow();
        }

        static void respond(
                HttpExchange exchange,
                int status,
                String contentType,
                byte[] body,
                Map<String, String> extraHeaders) throws IOException {
            try (exchange) {
                exchange.getResponseHeaders().set("Content-Type", contentType);
                exchange.getResponseHeaders().set("Cache-Control", "no-store");
                extraHeaders.forEach((name, value) -> exchange.getResponseHeaders().set(name, value));
                exchange.sendResponseHeaders(status, body.length);
                exchange.getResponseBody().write(body);
            }
        }
    }
}
