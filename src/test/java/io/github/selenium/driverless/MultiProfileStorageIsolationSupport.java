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
abstract class MultiProfileStorageIsolationSupport extends MultiProfileStorageIsolationAssertions {
    static BrowserFingerprintProfile minimalLiveFingerprint(
            String suffix,
            String locale,
            List<String> languages,
            String timezone) {
        BrowserFingerprintProfile nativeProfile = BrowserFingerprintProfile.nativeDefaults();
        BrowserFingerprintProfile profile = BrowserFingerprintProfile.brLinux();
        profile.name = "jdl-minimal-live-isolation-" + suffix;
        profile.runtimePolicy = BrowserFingerprintProfile.RUNTIME_POLICY_MINIMAL_CDP;
        profile.locale = locale;
        profile.languages = languages;
        profile.timezoneId = timezone;
        profile.hardwareConcurrency = nativeProfile.hardwareConcurrency;
        profile.deviceMemory = nativeProfile.deviceMemory;
        profile.vendor = "Google Inc.";
        profile.doNotTrack = false;
        profile.browserVersion = "";
        profile.platformVersion = "";
        profile.screen.mode = "real";
        profile.webgl.mode = "real";
        profile.webRtc.mode = "altered";
        profile.webRtc.disableUdp = true;
        profile.mediaDevices.mode = "real";
        profile.speech.mode = "auto";
        profile.audio.mode = "real";
        profile.canvas.mode = "real";
        profile.clientRects.mode = "real";
        profile.storage.mode = "real";
        profile.fonts.mode = "real";
        profile.ports.mode = "real";
        profile.connection.mode = "real";
        profile.maxTouchPoints = 0;
        return profile;
    }

    static void assertMinimalNativeIdentity(
            Map<String, Object> snapshot, BrowserFingerprintProfile profile) {
        assertThat(snapshot.get("userAgent")).isInstanceOf(String.class);
        assertThat((String) snapshot.get("userAgent"))
                .contains("Chrome/")
                .doesNotContainIgnoringCase("Headless");
        assertThat(((Number) snapshot.get("hardwareConcurrency")).intValue())
                .isEqualTo(profile.hardwareConcurrency);
        assertThat(((Number) snapshot.get("deviceMemory")).intValue())
                .isEqualTo(profile.deviceMemory);
        assertThat(snapshot)
                .containsEntry("platform", profile.platform)
                .containsEntry("language", profile.languages.get(0))
                .containsEntry("languages", profile.languages)
                .containsEntry("intlLocale", profile.locale)
                .containsEntry("timezone", profile.timezoneId);
        assertCompleteNativeUserAgentData(snapshot, profile);
        assertCompleteNativeWebGl(snapshot);
    }

    @SuppressWarnings("unchecked")
    static void assertCompleteNativeWebGl(Map<String, Object> snapshot) {
        assertThat(snapshot.get("webgl")).isInstanceOf(Map.class);
        Map<String, Object> webgl = (Map<String, Object>) snapshot.get("webgl");
        assertThat(webgl.get("vendor")).isInstanceOf(String.class).asString().isNotBlank();
        assertThat(webgl.get("renderer"))
                .isInstanceOf(String.class)
                .asString()
                .isNotBlank()
                .doesNotContainIgnoringCase("SwiftShader");
        assertThat(webgl.get("hash")).isInstanceOf(String.class).asString()
                .matches("[a-f0-9]{8}");
    }

    @SuppressWarnings("unchecked")
    static void assertCompleteNativeUserAgentData(
            Map<String, Object> snapshot, BrowserFingerprintProfile profile) {
        assertThat(snapshot.get("userAgentData")).isInstanceOf(Map.class);
        Map<String, Object> userAgentData = (Map<String, Object>) snapshot.get("userAgentData");
        assertThat(userAgentData)
                .containsEntry("mobile", false)
                .containsEntry("platform", profile.uaPlatform);
        assertThat(userAgentData.get("brands")).isInstanceOf(List.class);
        assertThat((List<?>) userAgentData.get("brands")).isNotEmpty();

        assertThat(userAgentData.get("highEntropy")).isInstanceOf(Map.class);
        Map<String, Object> highEntropy =
                (Map<String, Object>) userAgentData.get("highEntropy");
        assertThat(highEntropy)
                .containsEntry("architecture", profile.architecture)
                .containsEntry("bitness", profile.bitness)
                .containsEntry("model", "")
                .containsEntry("wow64", false);
        assertThat(highEntropy.get("uaFullVersion"))
                .isInstanceOf(String.class)
                .asString()
                .matches("\\d+\\.\\d+\\.\\d+\\.\\d+");
        assertThat(highEntropy.get("fullVersionList")).isInstanceOf(List.class);
        assertThat((List<?>) highEntropy.get("fullVersionList")).isNotEmpty();
        assertThat(highEntropy.get("formFactors")).isEqualTo(List.of("Desktop"));
    }

    static void assertMinimalRealmsCoherent(
            Map<String, Object> page,
            Map<String, Object> dedicated,
            Map<String, Object> shared,
            Map<String, Object> service,
            Map<String, Object> serviceFirst) {
        for (String surface : List.of(
                "userAgent", "userAgentData", "hardwareConcurrency", "deviceMemory", "platform",
                "language", "languages", "intlLocale", "timezone", "timezoneOffset", "webgl")) {
            assertThat(dedicated.get(surface)).as("dedicated worker %s", surface)
                    .isEqualTo(page.get(surface));
            assertThat(shared.get(surface)).as("shared worker %s", surface)
                    .isEqualTo(page.get(surface));
            assertThat(service.get(surface)).as("service worker %s", surface)
                    .isEqualTo(page.get(surface));
            assertThat(serviceFirst.get(surface)).as("service worker first statement %s", surface)
                    .isEqualTo(page.get(surface));
        }
    }

    static void assertMinimalProfilesDistinct(
            Map<String, Object> profileA, Map<String, Object> profileB) {
        assertThat(profileB.get("language")).isNotEqualTo(profileA.get("language"));
        assertThat(profileB.get("languages")).isNotEqualTo(profileA.get("languages"));
        assertThat(profileB.get("intlLocale")).isNotEqualTo(profileA.get("intlLocale"));
        assertThat(profileB.get("timezone")).isNotEqualTo(profileA.get("timezone"));
    }

    static BrowserFingerprintProfile liveFingerprint(
            String suffix, int noiseSeed, int hardwareConcurrency, int deviceMemory) {
        BrowserFingerprintProfile profile = BrowserFingerprintProfile.brLinux();
        profile.name = "jdl-live-isolation-" + suffix;
        profile.noiseSeed = noiseSeed;
        profile.hardwareConcurrency = hardwareConcurrency;
        profile.deviceMemory = deviceMemory;
        profile.canvas.mode = "noise";
        profile.canvas.noise = 0.004;
        profile.webgl.mode = "spoof_noise";
        profile.audio.mode = "noise";
        profile.audio.noise = 0.002;
        profile.clientRects.mode = "noise";
        profile.clientRects.noise = 0.025;
        profile.connection.mode = "spoof";
        return profile;
    }

    static ChromeOptions liveOptions(
            Path userData, Path downloads, BrowserFingerprintProfile fingerprint) {
        ChromeOptions options = new ChromeOptions();
        options.setUserDataDir(userData.toAbsolutePath().normalize().toString());
        options.setDownloadsDir(downloads.toAbsolutePath().normalize().toString());
        options.setAutoCleanDirs(false);
        options.setStartupUrl("about:blank");
        options.setFingerprintProfile(fingerprint);
        return options;
    }

    static void writeStorage(JavaDriverless driver, String token) throws Exception {
        String tokenJson = MAPPER.writeValueAsString(token);
        Object result = driver.executeScript(WRITE_STORAGE_SCRIPT.replace("__JDL_TOKEN_JSON__", tokenJson));
        assertThat(result).isEqualTo(true);
    }

    @SuppressWarnings("unchecked")
    static Map<String, Object> storageSnapshot(JavaDriverless driver) {
        Object result = driver.executeScript(STORAGE_SNAPSHOT_SCRIPT);
        assertThat(result).isInstanceOf(Map.class);
        return (Map<String, Object>) result;
    }

    @SuppressWarnings("unchecked")
    static Map<String, Object> fingerprintSnapshot(JavaDriverless driver, String script) {
        Object result = driver.executeScript(script);
        assertThat(result).isInstanceOf(Map.class);
        return (Map<String, Object>) result;
    }

    static Map<String, Object> dedicatedWorkerFingerprintSnapshot(JavaDriverless driver) throws Exception {
        String workerSource = FINGERPRINT_SNAPSHOT_FUNCTION + """
                self.onmessage = async () => self.postMessage(await jdlFingerprintSnapshot());
                """;
        String workerSourceJson = MAPPER.writeValueAsString(workerSource);
        return fingerprintSnapshot(driver, """
                (() => new Promise((resolve, reject) => {
                  const source = __JDL_WORKER_SOURCE__;
                  const objectUrl = URL.createObjectURL(new Blob([source], {type: 'application/javascript'}));
                  const worker = new Worker(objectUrl);
                  const timer = setTimeout(() => {
                    worker.terminate();
                    URL.revokeObjectURL(objectUrl);
                    reject(new Error('dedicated worker fingerprint timeout'));
                  }, 15000);
                  worker.onmessage = event => {
                    clearTimeout(timer);
                    worker.terminate();
                    URL.revokeObjectURL(objectUrl);
                    resolve(event.data);
                  };
                  worker.onerror = event => {
                    clearTimeout(timer);
                    worker.terminate();
                    URL.revokeObjectURL(objectUrl);
                    reject(new Error(event.message || 'dedicated worker fingerprint failed'));
                  };
                  worker.postMessage('snapshot');
                }))()
                """.replace("__JDL_WORKER_SOURCE__", workerSourceJson));
    }

    static Map<String, Object> sharedWorkerFingerprintSnapshot(JavaDriverless driver) throws Exception {
        String workerSource = FINGERPRINT_SNAPSHOT_FUNCTION + """
                self.onconnect = event => {
                  const port = event.ports[0];
                  port.onmessage = async () => port.postMessage(await jdlFingerprintSnapshot());
                  port.start();
                };
                """;
        String workerSourceJson = MAPPER.writeValueAsString(workerSource);
        return fingerprintSnapshot(driver, """
                (() => new Promise((resolve, reject) => {
                  if (typeof SharedWorker !== 'function') {
                    reject(new Error('SharedWorker API unavailable'));
                    return;
                  }
                  const source = __JDL_WORKER_SOURCE__;
                  const objectUrl = URL.createObjectURL(new Blob([source], {type: 'application/javascript'}));
                  const worker = new SharedWorker(objectUrl, {name: 'jdl-live-fingerprint'});
                  const timer = setTimeout(() => {
                    worker.port.close();
                    URL.revokeObjectURL(objectUrl);
                    reject(new Error('shared worker fingerprint timeout'));
                  }, 15000);
                  worker.port.onmessage = event => {
                    clearTimeout(timer);
                    worker.port.close();
                    URL.revokeObjectURL(objectUrl);
                    resolve(event.data);
                  };
                  worker.port.onmessageerror = () => {
                    clearTimeout(timer);
                    worker.port.close();
                    URL.revokeObjectURL(objectUrl);
                    reject(new Error('shared worker fingerprint message failed'));
                  };
                  worker.port.start();
                  worker.port.postMessage('snapshot');
                }))()
                """.replace("__JDL_WORKER_SOURCE__", workerSourceJson));
    }

    static Map<String, Object> crossOriginIframeFingerprintSnapshot(
            JavaDriverless driver, String crossOrigin) throws Exception {
        String crossOriginJson = MAPPER.writeValueAsString(crossOrigin);
        return fingerprintSnapshot(driver, """
                (() => new Promise((resolve, reject) => {
                  const expectedOrigin = __JDL_CROSS_ORIGIN__;
                  const token = crypto.randomUUID();
                  const iframe = document.createElement('iframe');
                  iframe.hidden = true;
                  const cleanup = () => {
                    window.removeEventListener('message', onMessage);
                    iframe.remove();
                  };
                  const timer = setTimeout(() => {
                    cleanup();
                    reject(new Error('cross-origin iframe fingerprint timeout'));
                  }, 15000);
                  const onMessage = event => {
                    if (event.origin !== expectedOrigin || !event.data || event.data.token !== token) return;
                    clearTimeout(timer);
                    cleanup();
                    resolve(event.data.snapshot);
                  };
                  window.addEventListener('message', onMessage);
                  iframe.onload = () => iframe.contentWindow.postMessage({
                    type: 'jdl-fingerprint-request', token
                  }, expectedOrigin);
                  iframe.src = expectedOrigin + '/?realm=cross-origin-iframe&token=' + encodeURIComponent(token);
                  document.body.appendChild(iframe);
                }))()
                """.replace("__JDL_CROSS_ORIGIN__", crossOriginJson));
    }

    static Map<String, Object> serviceWorkerFingerprintSnapshot(JavaDriverless driver) {
        return serviceWorkerFingerprintSnapshot(driver, "read-fingerprint");
    }

    static Map<String, Object> serviceWorkerFirstStatementFingerprintSnapshot(JavaDriverless driver) {
        return serviceWorkerFingerprintSnapshot(driver, "read-first-statement-fingerprint");
    }

    static Map<String, Object> serviceWorkerFingerprintSnapshot(JavaDriverless driver, String requestType) {
        return fingerprintSnapshot(driver, """
                (async () => {
                  const registration = await navigator.serviceWorker.getRegistration('/');
                  if (!registration) throw new Error('service worker registration missing');
                  const worker = registration.active || registration.waiting || registration.installing;
                  if (!worker) throw new Error('service worker instance missing');
                  return new Promise((resolve, reject) => {
                    const channel = new MessageChannel();
                    const timer = setTimeout(() => reject(new Error('service worker fingerprint timeout')), 15000);
                    channel.port1.onmessage = event => {
                      clearTimeout(timer);
                      resolve(event.data);
                    };
                    worker.postMessage({type: '__JDL_REQUEST_TYPE__'}, [channel.port2]);
                  });
                })()
                """.replace("__JDL_REQUEST_TYPE__", requestType));
    }

    static void assertFirstStatementWorkerIdentity(
            Map<String, Object> page, Map<String, Object> firstStatement) {
        for (String surface : List.of(
                "userAgent", "userAgentData", "hardwareConcurrency", "deviceMemory", "platform",
                "language", "languages", "intlLocale", "timezone", "timezoneOffset", "connection")) {
            assertThat(firstStatement.get(surface)).as("service worker first statement %s", surface)
                    .isEqualTo(page.get(surface));
        }
    }

    static void assertFingerprintMatchesProfile(
            Map<String, Object> snapshot, BrowserFingerprintProfile profile) {
        assertThat(snapshot.get("userAgent")).isInstanceOf(String.class);
        assertThat((String) snapshot.get("userAgent"))
                .contains("Chrome/")
                .doesNotContainIgnoringCase("Headless");
        assertThat(((Number) snapshot.get("hardwareConcurrency")).intValue())
                .isEqualTo(profile.hardwareConcurrency);
        assertThat(((Number) snapshot.get("deviceMemory")).intValue())
                .isEqualTo(profile.deviceMemory);
        assertThat(snapshot)
                .containsEntry("platform", profile.platform)
                .containsEntry("language", profile.languages.get(0))
                .containsEntry("languages", profile.languages)
                .containsEntry("intlLocale", profile.locale)
                .containsEntry("timezone", profile.timezoneId);
        assertThat(snapshot.get("canvasHash")).isInstanceOf(String.class);
        assertThat((String) snapshot.get("canvasHash")).hasSize(8);
        assertThat(snapshot.get("geometryCanvasHash")).isInstanceOf(String.class);
        assertThat((String) snapshot.get("geometryCanvasHash")).hasSize(8);
        assertThat(snapshot.get("rectCanvasHash")).isInstanceOf(String.class);
        assertThat((String) snapshot.get("rectCanvasHash")).hasSize(8);
        assertThat(snapshot.get("drawImageCanvasHash")).isInstanceOf(String.class);
        assertThat((String) snapshot.get("drawImageCanvasHash")).hasSize(8);
        if (snapshot.get("audioHash") != null) {
            assertThat(snapshot.get("audioHash")).isInstanceOf(String.class);
            assertThat((String) snapshot.get("audioHash")).hasSize(8);
        }

        Map<String, Object> webgl = nestedMap(snapshot, "webgl");
        assertThat(webgl)
                .containsEntry("vendor", profile.webgl.vendor)
                .containsEntry("renderer", profile.webgl.renderer);
        assertThat(webgl.get("hash")).isInstanceOf(String.class);
        assertThat((String) webgl.get("hash")).hasSize(8);

        Map<String, Object> connection = nestedMap(snapshot, "connection");
        assertThat(connection)
                .containsEntry("effectiveType", profile.connection.effectiveType)
                .containsEntry("saveData", profile.connection.saveData);
        assertThat(((Number) connection.get("downlink")).doubleValue()).isEqualTo(profile.connection.downlink);
        assertThat(((Number) connection.get("rtt")).intValue()).isEqualTo(profile.connection.rtt);

        Map<String, Object> estimate = nestedMap(snapshot, "storageEstimate");
        long seed = Integer.toUnsignedLong(profile.noiseSeed);
        long expectedQuota = ((96L + (seed % 384L)) * 1024L * 1024L * 1024L)
                - ((seed % 7919L) * 1024L * 1024L);
        assertThat(((Number) estimate.get("quota")).longValue()).isEqualTo(expectedQuota);

        if (snapshot.get("clientRects") != null) {
            assertThat(nestedMap(snapshot, "clientRects"))
                    .containsEntry("tag", "[object DOMRectList]")
                    .containsEntry("isArray", false)
                    .containsEntry("hasItem", true)
                    .containsEntry("stable", true);
        }
        if (snapshot.get("screen") != null) {
            assertThat(nestedMap(snapshot, "screen"))
                    .containsEntry("width", profile.screen.width)
                    .containsEntry("height", profile.screen.height)
                    .containsEntry("availWidth", profile.screen.availWidth)
                    .containsEntry("availHeight", profile.screen.availHeight);
        }
        if (snapshot.get("notification") != null) {
            Map<String, Object> notification = nestedMap(snapshot, "notification");
            String nativePermission = String.valueOf(notification.get("permission"));
            String expectedPermissionState = "default".equals(nativePermission)
                    ? "prompt" : nativePermission;
            assertThat(nativePermission).isIn("default", "granted", "denied");
            assertThat(notification).containsEntry("permissionState", expectedPermissionState);
        }
        if (snapshot.get("runtimeShape") != null) {
            assertThat(nestedMap(snapshot, "runtimeShape"))
                    .containsEntry("share", false)
                    .containsEntry("canShare", false)
                    .containsEntry("contentIndex", false)
                    .containsEntry("contactsManager", false)
                    .containsEntry("downlinkMax", false)
                    .containsEntry("xhrBlockedMarker", false)
                    .containsEntry("createElementNative", true)
                    .containsEntry("setTimeoutNative", true)
                    .containsEntry("workerNative", true);
        }
    }

}
