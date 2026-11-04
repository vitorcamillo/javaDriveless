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

class ChromeSessionCleanupSafetyTest extends ChromeSessionStartupTestSupport {
    @Test
    void quitRestoresEgressWebRtcLeavesForTheNextChromeInstance() throws Exception {
        Path profileDir = testDir.resolve("persistent-egress-profile");
        Path preferences = profileDir.resolve("Default/Preferences");
        Files.createDirectories(preferences.getParent());
        Map<String, Object> baseline = Map.of("webrtc", Map.of(
                "custom_preserved", "yes",
                "ip_handling_policy", "default_public_interface_only"));
        new ObjectMapper().writeValue(preferences.toFile(), Map.of(
                "webrtc", Map.of(
                        "custom_preserved", "yes",
                        "ip_handling_policy", "disable_non_proxied_udp",
                        "multiple_routes_enabled", false,
                        "nonproxied_udp_enabled", false)));

        ChromeOptions options = new ChromeOptions();
        options.setUserDataDir(profileDir.toString());
        Chrome chrome = new Chrome(options, 0.25f, false, 1 << 20);
        options.bindCatalogLaunchValidator(ignored -> () -> { });
        ChromeOptions.CatalogLaunchLease lease = options.acquireCatalogLaunchLease();
        Field catalogLease = declaredField(ChromeSessionCore.class, "catalogLaunchLease");
        catalogLease.setAccessible(true);
        catalogLease.set(chrome, lease);
        Field derived = declaredField(ChromeSessionCore.class, "egressWebRtcPrefsDerived");
        derived.setAccessible(true);
        derived.setBoolean(chrome, true);
        Field diskBaseline = declaredField(ChromeSessionCore.class, "egressWebRtcDiskBaseline");
        diskBaseline.setAccessible(true);
        diskBaseline.set(chrome, baseline);
        options.prepareEgressDerivedLaunchBaseline();
        options.applyEgressDerivedWebRtcPrefs();
        options.applyEgressDerivedWebRtcPolicyArgument();
        options.sealCatalogLaunchOptions();

        chrome.quit(false).join();

        @SuppressWarnings("unchecked")
        Map<String, Object> restored = new ObjectMapper().readValue(
                Files.readString(preferences), Map.class);
        assertThat(restored).isEqualTo(baseline);
        assertThat(options.getPrefs()).doesNotContainKey("webrtc");
        assertThat(options.getArguments())
                .noneMatch(argument -> argument.contains("webrtc-ip-handling-policy="));
        assertThatCode(() -> options.updatePref("after_quit", true)).doesNotThrowAnyException();
    }

    @Test
    void failedWebRtcDiskRestoreKeepsOwnershipForExplicitRetry() throws Exception {
        Path profileDir = testDir.resolve("restore-retry-profile");
        Path preferences = profileDir.resolve("Default/Preferences");
        Files.createDirectories(preferences.getParent());
        Files.writeString(preferences, "{invalid-json");

        ChromeOptions options = new ChromeOptions();
        options.setUserDataDir(profileDir.toString());
        options.prepareEgressDerivedLaunchBaseline();
        options.applyEgressDerivedWebRtcPrefs();
        Chrome chrome = new Chrome(options, 0.25f, false, 1 << 20);
        Field derived = declaredField(ChromeSessionCore.class, "egressWebRtcPrefsDerived");
        derived.setAccessible(true);
        derived.setBoolean(chrome, true);
        Map<String, Object> baseline = Map.of("webrtc", Map.of(
                "custom_preserved", "yes",
                "ip_handling_policy", "default_public_interface_only"));
        Field diskBaseline = declaredField(ChromeSessionCore.class, "egressWebRtcDiskBaseline");
        diskBaseline.setAccessible(true);
        diskBaseline.set(chrome, baseline);

        assertThatThrownBy(chrome::restoreEgressDerivedWebRtcState)
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("Falha ao restaurar prefs WebRTC derivados do egress");
        assertThat(derived.getBoolean(chrome)).isTrue();
        assertThat(diskBaseline.get(chrome)).isEqualTo(baseline);
        @SuppressWarnings("unchecked")
        Map<String, Object> stillOwnedPrefs = (Map<String, Object>) options.getPrefs().get("webrtc");
        assertThat(stillOwnedPrefs)
                .containsEntry("ip_handling_policy", "disable_non_proxied_udp");

        new ObjectMapper().writeValue(preferences.toFile(), Map.of("webrtc", Map.of(
                "custom_preserved", "yes",
                "ip_handling_policy", "disable_non_proxied_udp",
                "multiple_routes_enabled", false,
                "nonproxied_udp_enabled", false)));
        chrome.restoreEgressDerivedWebRtcState();

        @SuppressWarnings("unchecked")
        Map<String, Object> restored = new ObjectMapper().readValue(
                Files.readString(preferences), Map.class);
        assertThat(restored).isEqualTo(baseline);
        assertThat(derived.getBoolean(chrome)).isFalse();
        assertThat(diskBaseline.get(chrome)).isNull();
    }

    @Test
    void catalogLeaseWaitsForCapturedRootAndDescendantExit() throws Exception {
        Chrome chrome = new Chrome(new ChromeOptions(), 0.25f, false, 1 << 20);
        AtomicBoolean leaseClosed = new AtomicBoolean();
        var leaseField = declaredField(ChromeSessionCore.class, "catalogLaunchLease");
        leaseField.setAccessible(true);
        leaseField.set(chrome, (ChromeOptions.CatalogLaunchLease) () -> leaseClosed.set(true));

        Process root = new ProcessBuilder(
                "/bin/sh", "-c", "trap '' HUP; sleep 30 & child=$!; wait \"$child\"").start();
        java.util.List<ProcessHandle> captured = java.util.List.of();
        try {
            long discoveryDeadline = System.nanoTime() + Duration.ofSeconds(2).toNanos();
            while (captured.size() < 2 && System.nanoTime() < discoveryDeadline) {
                captured = chrome.captureProcessTree(root);
                if (captured.size() < 2) Thread.sleep(10);
            }
            assertThat(captured).hasSizeGreaterThanOrEqualTo(2);
            ProcessHandle descendant = captured.stream()
                    .filter(handle -> handle.pid() != root.pid())
                    .findFirst()
                    .orElseThrow();

            chrome.releaseCatalogLaunchLeaseAfterProcessExit(captured);
            root.destroyForcibly();
            root.onExit().get(2, java.util.concurrent.TimeUnit.SECONDS);

            assertThat(descendant.isAlive()).isTrue();
            assertThat(leaseClosed).isFalse();

            descendant.destroyForcibly();
            descendant.onExit().get(2, java.util.concurrent.TimeUnit.SECONDS);
            long releaseDeadline = System.nanoTime() + Duration.ofSeconds(2).toNanos();
            while (!leaseClosed.get() && System.nanoTime() < releaseDeadline) Thread.sleep(10);
            assertThat(leaseClosed).isTrue();
        } finally {
            captured.forEach(ProcessHandle::destroyForcibly);
            forceCleanup(root);
        }
    }

    @Test
    void cleanupClosesCdpAndProxyIndependentlyAndIsIdempotent() throws Exception {
        ChromeOptions options = new ChromeOptions();
        options.setBinaryLocation("/bin/true");
        Path callerOwnedProfile = Files.createDirectories(testDir.resolve("caller-profile"));
        options.setUserDataDir(callerOwnedProfile.toString());
        Chrome chrome = new Chrome(options, 0.25f, false, 1 << 20);

        Target target = mock(Target.class);
        BaseTarget browserTarget = mock(BaseTarget.class);
        BaseTarget contextTarget = mock(BaseTarget.class);
        AuthenticatedProxyBridge bridge = mock(AuthenticatedProxyBridge.class);
        when(target.close()).thenThrow(new IllegalStateException("synchronous target close issue"));
        when(browserTarget.close()).thenReturn(CompletableFuture.completedFuture(null));
        when(contextTarget.close()).thenReturn(CompletableFuture.completedFuture(null));

        chrome.currentTarget = target;
        chrome.baseTarget = browserTarget;
        chrome.baseContext = contextTarget;
        chrome.authenticatedProxyBridge = bridge;
        chrome.started = true;

        chrome.cleanupAfterStartupFailure();
        chrome.cleanupAfterStartupFailure();

        verify(target).close();
        verify(browserTarget).close();
        verify(contextTarget).close();
        verify(bridge, times(1)).close();
        assertThat(chrome.started).isFalse();
        assertThat(chrome.currentTarget).isNull();
        assertThat(chrome.baseTarget).isNull();
        assertThat(chrome.baseContext).isNull();
        assertThat(chrome.authenticatedProxyBridge).isNull();
        assertThat(callerOwnedProfile).exists();
    }

    @Test
    void ownedCleanupRequestsBrowserCloseBeforeDisconnect() {
        Target target = mock(Target.class);
        BaseTarget browserTarget = connectedBrowserTarget();
        JsonNode success = new ObjectMapper().createObjectNode();

        when(browserTarget.executeCdpCmd(eq("Browser.close"), anyMap(), nullable(Float.class)))
                .thenReturn(CompletableFuture.completedFuture(success));
        when(target.close()).thenReturn(CompletableFuture.completedFuture(null));
        when(browserTarget.close()).thenReturn(CompletableFuture.completedFuture(null));

        ChromeStartupResourceCleaner.cleanup(
                LoggerFactory.getLogger(ChromeSessionStartupSafetyTest.class),
                target,
                browserTarget,
                null,
                null,
                null,
                true,
                null);

        var order = org.mockito.Mockito.inOrder(browserTarget, target);
        order.verify(browserTarget).executeCdpCmd(eq("Browser.close"), anyMap(), nullable(Float.class));
        order.verify(target).close();
        order.verify(browserTarget).close();
    }

    @Test
    void successfulBrowserCloseAllowsNaturalExitBeforeProcessFallback() {
        BaseTarget browserTarget = connectedBrowserTarget();
        Process process = mock(Process.class);
        ProcessHandle processHandle = mock(ProcessHandle.class);
        JsonNode success = new ObjectMapper().createObjectNode();

        when(browserTarget.executeCdpCmd(eq("Browser.close"), anyMap(), nullable(Float.class)))
                .thenReturn(CompletableFuture.completedFuture(success));
        when(browserTarget.close()).thenReturn(CompletableFuture.completedFuture(null));
        when(process.toHandle()).thenReturn(processHandle);
        when(processHandle.descendants()).thenReturn(java.util.stream.Stream.empty());
        when(processHandle.isAlive()).thenReturn(true, false);

        ChromeStartupResourceCleaner.cleanup(
                LoggerFactory.getLogger(ChromeSessionStartupSafetyTest.class),
                null,
                browserTarget,
                null,
                process,
                null,
                true,
                null);

        verify(processHandle, never()).destroy();
        verify(processHandle, never()).destroyForcibly();
    }

    @Test
    void failedBrowserCloseResponseStillAllowsNaturalExitBeforeFallback() {
        BaseTarget browserTarget = connectedBrowserTarget();
        Process process = mock(Process.class);
        ProcessHandle processHandle = mock(ProcessHandle.class);

        when(browserTarget.executeCdpCmd(eq("Browser.close"), anyMap(), nullable(Float.class)))
                .thenReturn(CompletableFuture.failedFuture(
                        new IllegalStateException("socket encerrou durante Browser.close")));
        when(browserTarget.close()).thenReturn(CompletableFuture.completedFuture(null));
        when(process.toHandle()).thenReturn(processHandle);
        when(processHandle.descendants()).thenReturn(java.util.stream.Stream.empty());
        when(processHandle.isAlive()).thenReturn(true, false);

        ChromeStartupResourceCleaner.cleanup(
                LoggerFactory.getLogger(ChromeSessionStartupSafetyTest.class),
                null,
                browserTarget,
                null,
                process,
                null,
                true,
                null);

        verify(processHandle, never()).destroy();
        verify(processHandle, never()).destroyForcibly();
    }

    @Test
    void ownedCleanupStillTerminatesProcessWhenBrowserCloseFails() {
        BaseTarget browserTarget = connectedBrowserTarget();
        Process process = mock(Process.class);
        ProcessHandle processHandle = mock(ProcessHandle.class);

        when(browserTarget.executeCdpCmd(eq("Browser.close"), anyMap(), nullable(Float.class)))
                .thenReturn(CompletableFuture.failedFuture(
                        new IllegalStateException("browser close indisponível")));
        when(browserTarget.close()).thenReturn(CompletableFuture.completedFuture(null));
        when(process.toHandle()).thenReturn(processHandle);
        when(processHandle.descendants()).thenReturn(java.util.stream.Stream.empty());
        AtomicBoolean alive = new AtomicBoolean(true);
        when(processHandle.isAlive()).thenAnswer(ignored -> alive.get());
        when(processHandle.destroy()).thenAnswer(ignored -> {
            alive.set(false);
            return true;
        });

        String previousGrace = System.getProperty("jdl.browserCloseGraceMillis");
        System.setProperty("jdl.browserCloseGraceMillis", "100");
        try {
            ChromeStartupResourceCleaner.cleanup(
                    LoggerFactory.getLogger(ChromeSessionStartupSafetyTest.class),
                    null,
                    browserTarget,
                    null,
                    process,
                    null,
                    true,
                    null);
        } finally {
            if (previousGrace == null) {
                System.clearProperty("jdl.browserCloseGraceMillis");
            } else {
                System.setProperty("jdl.browserCloseGraceMillis", previousGrace);
            }
        }

        verify(browserTarget).executeCdpCmd(eq("Browser.close"), anyMap(), nullable(Float.class));
        verify(processHandle).destroy();
    }

    @Test
    void partialBrowserTargetCleanupDoesNotReconnectThroughBrowserClose() {
        BaseTarget browserTarget = mock(BaseTarget.class);
        when(browserTarget.close()).thenReturn(CompletableFuture.completedFuture(null));

        ChromeStartupResourceCleaner.cleanup(
                LoggerFactory.getLogger(ChromeSessionStartupSafetyTest.class),
                null,
                browserTarget,
                null,
                null,
                null,
                true,
                null);

        verify(browserTarget, never()).executeCdpCmd(
                eq("Browser.close"), anyMap(), nullable(Float.class));
        verify(browserTarget).close();
    }

    @Test
    void attachCleanupDisconnectsWithoutClosingCallerOwnedPage() {
        ChromeOptions options = new ChromeOptions();
        options.setBinaryLocation("/bin/true");
        Chrome chrome = new Chrome(options, 0.25f, false, 1 << 20);
        Target target = mock(Target.class);
        BaseTarget browserTarget = mock(BaseTarget.class);
        when(target.disconnect()).thenReturn(CompletableFuture.completedFuture(null));
        when(browserTarget.close()).thenReturn(CompletableFuture.completedFuture(null));

        chrome.isRemote = true;
        chrome.currentTarget = target;
        chrome.baseTarget = browserTarget;

        chrome.cleanupAfterStartupFailure();

        verify(target).disconnect();
        verify(target, never()).close();
        verify(browserTarget, never()).executeCdpCmd(
                eq("Browser.close"), anyMap(), nullable(Float.class));
        verify(browserTarget).close();
        assertThat(chrome.currentTarget).isNull();
    }
}
