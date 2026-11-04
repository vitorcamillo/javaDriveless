package io.github.selenium.javaDriverless;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.selenium.javaDriverless.cdp.CDPSocket;
import io.github.selenium.javaDriverless.types.BaseTarget;
import io.github.selenium.javaDriverless.types.BrowserFingerprintProfile;
import io.github.selenium.javaDriverless.types.ChromeOptions;
import io.github.selenium.javaDriverless.types.EgressInfo;
import io.github.selenium.javaDriverless.types.Target;
import io.github.selenium.javaDriverless.utils.UserAgentResolver;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.slf4j.LoggerFactory;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.function.BiConsumer;
import java.lang.reflect.Field;
import java.lang.reflect.Method;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.nullable;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

abstract class ChromeSessionStartupTestSupport {
    @TempDir
    Path testDir;

    static Field declaredField(Class<?> type, String name) throws NoSuchFieldException {
        Class<?> current = type;
        while (current != null) {
            try {
                return current.getDeclaredField(name);
            } catch (NoSuchFieldException ignored) {
                current = current.getSuperclass();
            }
        }
        throw new NoSuchFieldException(name);
    }

    static BaseTarget connectedBrowserTarget() {
        BaseTarget target = mock(BaseTarget.class);
        CDPSocket socket = mock(CDPSocket.class);
        when(target.getSocket()).thenReturn(socket);
        when(socket.isConnected()).thenReturn(true);
        return target;
    }

    static ChromeFingerprintSupport fingerprintSupport() {
        ChromeOptions options = new ChromeOptions();
        BrowserFingerprintProfile profile = BrowserFingerprintProfile.brLinux();
        options.setFingerprintProfile(profile);
        return new ChromeFingerprintSupport(
                options,
                profile,
                new ObjectMapper(),
                LoggerFactory.getLogger(ChromeSessionStartupSafetyTest.class),
                () -> UserAgentResolver.buildForProfile(profile),
                browser -> browser);
    }

    static BrowserFingerprintProfile minimalNativeProfile() {
        BrowserFingerprintProfile profile = BrowserFingerprintProfile.nativeDefaults();
        profile.runtimePolicy = BrowserFingerprintProfile.RUNTIME_POLICY_MINIMAL_CDP;
        profile.locale = "en-US";
        profile.languages = java.util.List.of("en-US", "en");
        profile.webgl.mode = "real";
        profile.webRtc.mode = "real";
        profile.webRtc.disableUdp = false;
        profile.mediaDevices.mode = "real";
        profile.speech.mode = "auto";
        profile.audio.mode = "real";
        profile.canvas.mode = "real";
        profile.clientRects.mode = "real";
        profile.storage.mode = "real";
        profile.fonts.mode = "real";
        profile.ports.mode = "real";
        profile.connection.mode = "real";
        profile.screen.mode = "real";
        return profile;
    }

    static void stubTargetDestroyedListeners(Target page, BaseTarget browser) {
        when(page.getId()).thenReturn("primary-page");
        when(page.addCdpListener(
                eq("Target.targetDestroyed"), org.mockito.ArgumentMatchers.any()))
                .thenReturn(CompletableFuture.completedFuture(null));
        when(browser.addCdpListener(
                eq("Target.targetDestroyed"), org.mockito.ArgumentMatchers.any()))
                .thenReturn(CompletableFuture.completedFuture(null));
        when(page.addCdpListener(
                eq("Target.detachedFromTarget"), org.mockito.ArgumentMatchers.any()))
                .thenReturn(CompletableFuture.completedFuture(null));
        when(browser.addCdpListener(
                eq("Target.detachedFromTarget"), org.mockito.ArgumentMatchers.any()))
                .thenReturn(CompletableFuture.completedFuture(null));
        when(page.addCdpListener(
                eq("Target.targetInfoChanged"), org.mockito.ArgumentMatchers.any()))
                .thenReturn(CompletableFuture.completedFuture(null));
        when(browser.addCdpListener(
                eq("Target.targetInfoChanged"), org.mockito.ArgumentMatchers.any()))
                .thenReturn(CompletableFuture.completedFuture(null));
        when(page.addCdpSessionListener(
                eq("Debugger.paused"), org.mockito.ArgumentMatchers.any()))
                .thenReturn(CompletableFuture.completedFuture(null));
        when(browser.addCdpSessionListener(
                eq("Debugger.paused"), org.mockito.ArgumentMatchers.any()))
                .thenReturn(CompletableFuture.completedFuture(null));
        when(page.addCdpSessionListener(
                eq("Fetch.requestPaused"), org.mockito.ArgumentMatchers.any()))
                .thenReturn(CompletableFuture.completedFuture(null));
        when(browser.addCdpSessionListener(
                eq("Fetch.requestPaused"), org.mockito.ArgumentMatchers.any()))
                .thenReturn(CompletableFuture.completedFuture(null));
    }

    static void forceCleanup(Process process) {
        if (process == null) {
            return;
        }
        var descendants = process.toHandle().descendants().toList();
        descendants.forEach(ProcessHandle::destroyForcibly);
        process.destroyForcibly();
        long deadline = System.nanoTime() + Duration.ofSeconds(2).toNanos();
        while ((process.isAlive() || descendants.stream().anyMatch(ProcessHandle::isAlive))
                && System.nanoTime() < deadline) {
            try {
                Thread.sleep(10);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
        }
    }

    static void deleteDirectory(Path path) throws Exception {
        if (!Files.exists(path)) {
            return;
        }
        try (var stream = Files.walk(path)) {
            for (Path current : stream.sorted((a, b) -> b.compareTo(a)).toList()) {
                Files.deleteIfExists(current);
            }
        }
    }
}
