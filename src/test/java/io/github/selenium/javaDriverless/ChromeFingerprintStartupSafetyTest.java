package io.github.selenium.javaDriverless;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.selenium.javaDriverless.cdp.CDPSocket;
import io.github.selenium.javaDriverless.cdp.exceptions.CDPException;
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
class ChromeFingerprintStartupSafetyTest extends ChromeSessionStartupTestSupport {
    @Test
    void targetOriginDiagnosticsNeverIncludePathQueryOrOpaquePayload() {
        var http = new ObjectMapper().createObjectNode();
        http.put("url", "https://Example.COM/private/path?token=not-logged#fragment");
        var opaque = new ObjectMapper().createObjectNode();
        opaque.put("url", "urn:uuid:opaque-payload");
        var empty = new ObjectMapper().createObjectNode();

        assertThat(ChromeWorkerSpoofSupportBase.safeTargetOrigin(http))
                .isEqualTo("<web>");
        assertThat(ChromeWorkerSpoofSupportBase.safeTargetOrigin(opaque))
                .isEqualTo("<opaque>");
        assertThat(ChromeWorkerSpoofSupportBase.safeTargetOrigin(empty))
                .isEqualTo("<empty>");
    }

    @Test
    void livePersistentProfileLockAbortsBeforePreferencesOrBrowserAreTouched() throws Exception {
        Path profile = Files.createDirectories(testDir.resolve("persistent-profile"));
        Path preferences = profile.resolve("Default/Preferences");
        Files.createDirectories(preferences.getParent());
        String originalPreferences = "{\"caller_state\":{\"sentinel\":true}}";
        Files.writeString(preferences, originalPreferences);
        Files.createSymbolicLink(profile.resolve("SingletonLock"),
                Path.of("localhost-" + ProcessHandle.current().pid()));
        Path marker = testDir.resolve("browser-launched");
        Path fakeChrome = testDir.resolve("must-not-launch.sh");
        Files.writeString(fakeChrome, "#!/bin/sh\ntouch '" + marker + "'\n");
        assertThat(fakeChrome.toFile().setExecutable(true)).isTrue();
        ChromeOptions options = new ChromeOptions();
        options.setBinaryLocation(fakeChrome.toString());
        options.setUserDataDir(profile.toString());
        options.addArgument("--remote-debugging-port=1");
        Chrome chrome = new Chrome(options, 0.2f, false, 1 << 20);
        assertThatThrownBy(() -> chrome.startSession().join())
                .hasRootCauseInstanceOf(IllegalStateException.class)
                .hasRootCauseMessage(
                        "Profile em uso ou com lock ambiguo; auto-unlock stale e opt-in via "
                                + "JAVA_DRIVERLESS_UNLOCK_STALE_PROFILE_LOCKS=true");
        assertThat(marker).doesNotExist();
        assertThat(Files.readString(preferences)).isEqualTo(originalPreferences);
    }
    @Test
    void quitDuringStartupWaitsForAttemptAndPreventsOrphanedRestart() throws Exception {
        Path marker = testDir.resolve("startup-began");
        Path fakeChrome = testDir.resolve("slow-chrome.sh");
        Files.writeString(fakeChrome, "#!/bin/sh\ntouch '" + marker + "'\nsleep 60\n");
        assertThat(fakeChrome.toFile().setExecutable(true)).isTrue();
        ChromeOptions options = new ChromeOptions();
        options.setBinaryLocation(fakeChrome.toString());
        options.addArgument("--remote-debugging-port=1");
        Chrome chrome = new Chrome(options, 0.25f, false, 1 << 20);
        CompletableFuture<Chrome> startup = chrome.startSession();
        CompletableFuture<Void> closing = chrome.quit(false);
        CompletableFuture<Chrome> rejectedRestart = chrome.startSession();
        assertThatThrownBy(rejectedRestart::join)
                .hasRootCauseMessage("Sessão está em processo de fechamento");
        assertThatThrownBy(startup::join).hasRootCauseInstanceOf(RuntimeException.class);
        assertThatCode(closing::join).doesNotThrowAnyException();
        assertThat(marker).exists();
        assertThat(chrome.started).isFalse();
        assertThat(chrome.process).isNull();
        assertThat(chrome.currentTarget).isNull();
    }
    @Test
    void cancellingStartupViewDoesNotLetCloseOvertakeInternalAttempt() {
        ChromeSessionLifecycle lifecycle = new ChromeSessionLifecycle();
        CompletableFuture<Chrome> internalAttempt = new CompletableFuture<>();
        AtomicBoolean closeStarted = new AtomicBoolean();
        CompletableFuture<Chrome> callerView = lifecycle.start(false, () -> internalAttempt);
        assertThat(callerView.cancel(true)).isTrue();
        CompletableFuture<Void> close = lifecycle.close(() -> {
            closeStarted.set(true);
            return CompletableFuture.completedFuture(null);
        }, () -> {});
        assertThat(close).isNotDone();
        assertThat(closeStarted).isFalse();
        internalAttempt.complete(mock(Chrome.class));
        close.join();
        assertThat(closeStarted).isTrue();
    }
    @Test
    void cancellingStartupViewReusesInternalAttemptInsteadOfStartingAgain() {
        ChromeSessionLifecycle lifecycle = new ChromeSessionLifecycle();
        CompletableFuture<Chrome> internalAttempt = new CompletableFuture<>();
        AtomicInteger starterCalls = new AtomicInteger();
        CompletableFuture<Chrome> firstView = lifecycle.start(false, () -> {
            starterCalls.incrementAndGet();
            return internalAttempt;
        });
        assertThat(firstView.cancel(true)).isTrue();
        CompletableFuture<Chrome> replacementView = lifecycle.start(false, () -> {
            starterCalls.incrementAndGet();
            return CompletableFuture.failedFuture(new AssertionError("starter duplicado"));
        });
        assertThat(starterCalls).hasValue(1);
        assertThat(replacementView).isNotSameAs(firstView).isNotCancelled();
        Chrome expected = mock(Chrome.class);
        internalAttempt.complete(expected);
        assertThat(replacementView.join()).isSameAs(expected);
    }
    @Test
    void synchronouslyFailedInternalAttemptStillReturnsFailedCallerView() {
        ChromeSessionLifecycle lifecycle = new ChromeSessionLifecycle();
        CompletableFuture<Chrome> callerView = lifecycle.start(false,
                () -> CompletableFuture.failedFuture(new IllegalStateException("startup falhou")));
        assertThat(callerView).isNotNull();
        assertThatThrownBy(callerView::join).hasRootCauseMessage("startup falhou");
    }
    @Test
    void surfaceScriptFailsClosedWhenNativeInjectionAndFallbackBothFail() {
        ChromeFingerprintSupport support = fingerprintSupport();
        Target target = mock(Target.class);
        when(target.executeCdpCmd(
                eq("Page.enable"), anyMap(), nullable(Float.class)))
                .thenReturn(CompletableFuture.completedFuture(new ObjectMapper().createObjectNode()));
        when(target.executeCdpCmd(
                eq("Page.addScriptToEvaluateOnNewDocument"), anyMap(), nullable(Float.class)))
                .thenReturn(CompletableFuture.failedFuture(new IllegalStateException("native injection failed")));
        when(target.executeScript(anyString(), nullable(Object[].class), eq(true)))
                .thenReturn(CompletableFuture.failedFuture(new IllegalStateException("fallback failed")));
        assertThatThrownBy(() -> support.injectSeleniumProxySurfaceScript(target).join())
                .hasRootCauseMessage("native injection failed");
        verify(target, never()).executeScript(anyString(), nullable(Object[].class), eq(true));
    }
    @Test
    void essentialUserAgentOverrideFailureIsNotReportedAsSuccess() {
        ChromeFingerprintSupport support = fingerprintSupport();
        Target target = mock(Target.class);
        CompletableFuture<JsonNode> success = CompletableFuture.completedFuture(
                new ObjectMapper().createObjectNode());
        when(target.executeCdpCmd(anyString(), nullable(Map.class), nullable(Float.class)))
                .thenReturn(success);
        when(target.executeCdpCmd(
                eq("Network.setUserAgentOverride"), anyMap(), nullable(Float.class)))
                .thenReturn(CompletableFuture.failedFuture(new IllegalStateException("UA override failed")));
        assertThatThrownBy(() -> support.applyFingerprintProfileCdpOverrides(target).join())
                .hasRootCauseMessage("UA override failed");
    }
    @Test
    void minimalCdpDoesNotCreateATargetLocalUserAgentIdentity() {
        BrowserFingerprintProfile profile = BrowserFingerprintProfile.nativeDefaults();
        profile.runtimePolicy = BrowserFingerprintProfile.RUNTIME_POLICY_MINIMAL_CDP;
        profile.connection.mode = "real";
        ChromeOptions options = new ChromeOptions();
        options.setFingerprintProfile(profile);
        ChromeFingerprintSupport support = new ChromeFingerprintSupport(
                options, profile, new ObjectMapper(),
                LoggerFactory.getLogger(ChromeSessionStartupSafetyTest.class),
                () -> UserAgentResolver.buildForProfile(profile));
        Target target = mock(Target.class);
        JsonNode success = minimalLocaleProbe(profile);
        when(target.executeCdpCmd(anyString(), nullable(Map.class), nullable(Float.class)))
                .thenReturn(CompletableFuture.completedFuture(success));
        support.applyFingerprintProfileCdpOverrides(target).join();
        verify(target, never()).executeCdpCmd(
                eq("Network.enable"), nullable(Map.class), nullable(Float.class));
        verify(target, never()).executeCdpCmd(
                eq("Network.setUserAgentOverride"), anyMap(), nullable(Float.class));
    }
    @ParameterizedTest
    @ValueSource(strings = {
            "Emulation.setLocaleOverride",
            "Emulation.setTimezoneOverride",
            "Emulation.setHardwareConcurrencyOverride",
            "Emulation.setDeviceMetricsOverride",
            "Emulation.setGeolocationOverride"
    })
    void everyConfiguredEssentialOverrideFailureAbortsFingerprintStartup(String failedCommand) {
        ChromeFingerprintSupport support = fingerprintSupport();
        Target target = mock(Target.class);
        JsonNode success = new ObjectMapper().createObjectNode();
        when(target.executeCdpCmd(anyString(), nullable(Map.class), nullable(Float.class)))
                .thenAnswer(invocation -> failedCommand.equals(invocation.getArgument(0))
                        ? CompletableFuture.failedFuture(new IllegalStateException("failed: " + failedCommand))
                        : CompletableFuture.completedFuture(success));
        assertThatThrownBy(() -> support.applyFingerprintProfileCdpOverrides(target).join())
                .hasRootCauseMessage("failed: " + failedCommand);
    }
    @Test
    void configuredColorSchemeFailureAbortsFingerprintStartup() {
        ChromeOptions options = new ChromeOptions();
        BrowserFingerprintProfile profile = BrowserFingerprintProfile.brLinux();
        profile.colorScheme = "dark";
        options.setFingerprintProfile(profile);
        ChromeFingerprintSupport support = new ChromeFingerprintSupport(
                options,
                profile,
                new ObjectMapper(),
                LoggerFactory.getLogger(ChromeSessionStartupSafetyTest.class),
                () -> UserAgentResolver.buildForProfile(profile),
                browser -> browser);
        Target target = mock(Target.class);
        JsonNode success = new ObjectMapper().createObjectNode();
        when(target.executeCdpCmd(anyString(), nullable(Map.class), nullable(Float.class)))
                .thenAnswer(invocation -> {
                    String command = invocation.getArgument(0);
                    return "Emulation.setEmulatedMedia".equals(command)
                            ? CompletableFuture.failedFuture(new IllegalStateException("color scheme unavailable"))
                            : CompletableFuture.completedFuture(success);
                });
        assertThatThrownBy(() -> support.applyFingerprintProfileCdpOverrides(target).join())
                .hasRootCauseMessage("color scheme unavailable");
    }
    @Test
    @SuppressWarnings("unchecked")
    void allProfileMediaPreferencesArePinnedInOneEssentialOverride() {
        BrowserFingerprintProfile profile = BrowserFingerprintProfile.brLinux();
        profile.colorScheme = "dark";
        profile.prefersReducedMotion = "reduce";
        profile.prefersReducedTransparency = "reduce";
        profile.prefersContrast = "more";
        profile.forcedColors = "active";
        ChromeOptions options = new ChromeOptions();
        options.setFingerprintProfile(profile);
        ChromeFingerprintSupport support = new ChromeFingerprintSupport(
                options, profile, new ObjectMapper(),
                LoggerFactory.getLogger(ChromeSessionStartupSafetyTest.class),
                () -> UserAgentResolver.buildForProfile(profile));
        Target target = mock(Target.class);
        JsonNode success = new ObjectMapper().createObjectNode();
        when(target.executeCdpCmd(anyString(), nullable(Map.class), nullable(Float.class)))
                .thenReturn(CompletableFuture.completedFuture(success));
        when(target.addCdpListener(anyString(), org.mockito.ArgumentMatchers.any()))
                .thenReturn(CompletableFuture.completedFuture(null));
        support.applyFingerprintProfileCdpOverrides(target).join();
        ArgumentCaptor<Map<String, Object>> args = ArgumentCaptor.forClass(Map.class);
        verify(target).executeCdpCmd(eq("Emulation.setEmulatedMedia"), args.capture(), nullable(Float.class));
        assertThat((java.util.List<Map<String, String>>) args.getValue().get("features"))
                .containsExactly(
                        Map.of("name", "prefers-color-scheme", "value", "dark"),
                        Map.of("name", "prefers-reduced-motion", "value", "reduce"),
                        Map.of("name", "prefers-reduced-transparency", "value", "reduce"),
                        Map.of("name", "prefers-contrast", "value", "more"),
                        Map.of("name", "forced-colors", "value", "active"));
    }
    @Test
    void hardwareConcurrencyIsPinnedByTheEngineInsteadOfANavigatorGetter() {
        BrowserFingerprintProfile profile = BrowserFingerprintProfile.brLinux();
        profile.hardwareConcurrency = 12;
        ChromeOptions options = new ChromeOptions();
        options.setFingerprintProfile(profile);
        ChromeFingerprintSupport support = new ChromeFingerprintSupport(
                options, profile, new ObjectMapper(),
                LoggerFactory.getLogger(ChromeSessionStartupSafetyTest.class),
                () -> UserAgentResolver.buildForProfile(profile));
        Target target = mock(Target.class);
        JsonNode success = new ObjectMapper().createObjectNode();
        when(target.executeCdpCmd(anyString(), nullable(Map.class), nullable(Float.class)))
                .thenReturn(CompletableFuture.completedFuture(success));
        when(target.addCdpListener(anyString(), org.mockito.ArgumentMatchers.any()))
                .thenReturn(CompletableFuture.completedFuture(null));
        support.applyFingerprintProfileCdpOverrides(target).join();
        verify(target).executeCdpCmd(
                eq("Emulation.setHardwareConcurrencyOverride"),
                argThat(args -> Integer.valueOf(12).equals(args.get("hardwareConcurrency"))),
                nullable(Float.class));
    }
    @Test
    void minimalCdpHeadfulLeavesScreenGeometryToTheNativeDesktop() {
        BrowserFingerprintProfile profile = BrowserFingerprintProfile.brLinux();
        profile.runtimePolicy = BrowserFingerprintProfile.RUNTIME_POLICY_MINIMAL_CDP;
        profile.connection.mode = "real";
        ChromeOptions options = new ChromeOptions();
        options.setFingerprintProfile(profile);
        ChromeFingerprintSupport support = new ChromeFingerprintSupport(
                options, profile, new ObjectMapper(),
                LoggerFactory.getLogger(ChromeSessionStartupSafetyTest.class),
                () -> UserAgentResolver.buildForProfile(profile));
        Target target = mock(Target.class);
        JsonNode success = minimalLocaleProbe(profile);
        when(target.executeCdpCmd(anyString(), nullable(Map.class), nullable(Float.class)))
                .thenReturn(CompletableFuture.completedFuture(success));
        when(target.addCdpListener(anyString(), org.mockito.ArgumentMatchers.any()))
                .thenReturn(CompletableFuture.completedFuture(null));
        support.applyFingerprintProfileCdpOverrides(target).join();
        verify(target, never()).executeCdpCmd(
                eq("Emulation.setDeviceMetricsOverride"), anyMap(), nullable(Float.class));
        verify(target, never()).executeCdpCmd(
                eq("Emulation.updateScreen"), anyMap(), nullable(Float.class));
        verify(target, never()).executeCdpCmd(
                eq("Emulation.setVisibleSize"), anyMap(), nullable(Float.class));
        verify(target, never()).executeCdpCmd(
                eq("Emulation.setHardwareConcurrencyOverride"), anyMap(), nullable(Float.class));
    }
    @ParameterizedTest
    @ValueSource(strings = {"real", "prompt"})
    void minimalNativeGeolocationModesDoNotEmitAnOverride(String mode) {
        BrowserFingerprintProfile profile = BrowserFingerprintProfile.nativeDefaults();
        profile.runtimePolicy = BrowserFingerprintProfile.RUNTIME_POLICY_MINIMAL_CDP;
        profile.connection.mode = "real";
        profile.geolocation.mode = mode;
        profile.geolocation.latitude = null;
        profile.geolocation.longitude = null;
        ChromeOptions options = new ChromeOptions();
        options.setFingerprintProfile(profile);
        ChromeFingerprintSupport support = new ChromeFingerprintSupport(
                options, profile, new ObjectMapper(),
                LoggerFactory.getLogger(ChromeSessionStartupSafetyTest.class),
                () -> UserAgentResolver.buildForProfile(profile));
        Target target = mock(Target.class);
        JsonNode success = minimalLocaleProbe(profile);
        when(target.executeCdpCmd(anyString(), nullable(Map.class), nullable(Float.class)))
                .thenReturn(CompletableFuture.completedFuture(success));
        support.applyFingerprintProfileCdpOverrides(target).join();
        verify(target, never()).executeCdpCmd(
                eq("Emulation.setGeolocationOverride"), anyMap(), nullable(Float.class));
    }
    @Test
    void minimalOverrideGeolocationEmitsTheConfiguredNativeCdpCommand() {
        BrowserFingerprintProfile profile = BrowserFingerprintProfile.nativeDefaults();
        profile.runtimePolicy = BrowserFingerprintProfile.RUNTIME_POLICY_MINIMAL_CDP;
        profile.connection.mode = "real";
        profile.geolocation.mode = "override";
        profile.geolocation.latitude = -23.5505;
        profile.geolocation.longitude = -46.6333;
        profile.geolocation.accuracy = 25.0;
        ChromeOptions options = new ChromeOptions();
        options.setFingerprintProfile(profile);
        ChromeFingerprintSupport support = new ChromeFingerprintSupport(
                options, profile, new ObjectMapper(),
                LoggerFactory.getLogger(ChromeSessionStartupSafetyTest.class),
                () -> UserAgentResolver.buildForProfile(profile));
        Target target = mock(Target.class);
        JsonNode success = minimalLocaleProbe(profile);
        when(target.executeCdpCmd(anyString(), nullable(Map.class), nullable(Float.class)))
                .thenReturn(CompletableFuture.completedFuture(success));
        support.applyFingerprintProfileCdpOverrides(target).join();
        verify(target).executeCdpCmd(
                eq("Emulation.setGeolocationOverride"),
                argThat(args -> Double.valueOf(-23.5505).equals(args.get("latitude"))
                        && Double.valueOf(-46.6333).equals(args.get("longitude"))
                        && Double.valueOf(25.0).equals(args.get("accuracy"))),
                nullable(Float.class));
    }
    @Test
    void optionalNetworkEnableFailureDoesNotAbortFingerprintStartup() {
        ChromeFingerprintSupport support = fingerprintSupport();
        Target target = mock(Target.class);
        JsonNode success = new ObjectMapper().createObjectNode();
        when(target.executeCdpCmd(anyString(), nullable(Map.class), nullable(Float.class)))
                .thenAnswer(invocation -> "Network.enable".equals(invocation.getArgument(0))
                        ? CompletableFuture.failedFuture(new IllegalStateException("network already enabled"))
                        : CompletableFuture.completedFuture(success));
        assertThatCode(() -> support.applyFingerprintProfileCdpOverrides(target).join())
                .doesNotThrowAnyException();
    }
    @Test
    void workerAutoAttachFailureIsNotReportedAsSuccess() {
        ChromeFingerprintSupport support = fingerprintSupport();
        Target page = mock(Target.class);
        BaseTarget browser = mock(BaseTarget.class);
        stubTargetDestroyedListeners(page, browser);
        CompletableFuture<JsonNode> failure = CompletableFuture.failedFuture(
                new IllegalStateException("auto-attach failed"));
        when(page.addCdpListener(eq("Target.attachedToTarget"), org.mockito.ArgumentMatchers.any()))
                .thenReturn(CompletableFuture.completedFuture(null));
        when(browser.addCdpListener(eq("Target.attachedToTarget"), org.mockito.ArgumentMatchers.any()))
                .thenReturn(CompletableFuture.completedFuture(null));
        when(page.executeCdpCmd(
                eq("Target.setAutoAttach"), anyMap(), nullable(Float.class), nullable(String.class)))
                .thenReturn(failure);
        when(browser.executeCdpCmd(
                eq("Target.autoAttachRelated"), anyMap(), nullable(Float.class), nullable(String.class)))
                .thenReturn(failure);
        assertThatThrownBy(() -> support.installWorkerSpoofAutoAttach(page, browser).join())
                .hasRootCauseMessage("auto-attach failed");
    }
    @Test
    void browserAutoAttachDoesNotReapplyOverridesToPrimaryPage() {
        ChromeFingerprintSupport support = fingerprintSupport();
        Target page = mock(Target.class);
        BaseTarget browser = mock(BaseTarget.class);
        stubTargetDestroyedListeners(page, browser);
        AtomicReference<Consumer<JsonNode>> browserListener = new AtomicReference<>();
        JsonNode success = new ObjectMapper().createObjectNode();
        when(page.getId()).thenReturn("primary-page");
        when(browser.addCdpListener(eq("Target.attachedToTarget"), org.mockito.ArgumentMatchers.any()))
                .thenAnswer(invocation -> {
                    browserListener.set(invocation.getArgument(1));
                    return CompletableFuture.completedFuture(null);
                });
        when(page.addCdpListener(eq("Target.attachedToTarget"), org.mockito.ArgumentMatchers.any()))
                .thenReturn(CompletableFuture.completedFuture(null));
        when(page.executeCdpCmd(anyString(), anyMap(), nullable(Float.class), nullable(String.class)))
                .thenReturn(CompletableFuture.completedFuture(success));
        when(browser.executeCdpCmd(anyString(), anyMap(), nullable(Float.class), nullable(String.class)))
                .thenReturn(CompletableFuture.completedFuture(success));
        support.installWorkerSpoofAutoAttach(page, browser).join();
        var event = new ObjectMapper().createObjectNode();
        event.put("sessionId", "primary-page-session");
        event.put("waitingForDebugger", true);
        var targetInfo = event.putObject("targetInfo");
        targetInfo.put("type", "page");
        targetInfo.put("targetId", "primary-page");
        browserListener.get().accept(event);
        verify(browser, never()).executeCdpCmd(eq("Page.addScriptToEvaluateOnNewDocument"), anyMap(),
                nullable(Float.class), eq("primary-page-session"));
        verify(browser, never()).executeCdpCmd(eq("Emulation.setTimezoneOverride"), anyMap(),
                nullable(Float.class), eq("primary-page-session"));
        verify(browser, never()).executeCdpCmd(eq("Emulation.setLocaleOverride"), anyMap(),
                nullable(Float.class), eq("primary-page-session"));
        verify(browser, times(1)).executeCdpCmd(eq("Runtime.runIfWaitingForDebugger"), anyMap(),
                nullable(Float.class), eq("primary-page-session"));
        assertThat(support.hasRuntimeFailure()).isFalse();
    }
    @Test
    void oopIframeAppliesTargetScopedLocaleAndTimezoneBeforeResume() {
        ChromeFingerprintSupport support = fingerprintSupport();
        Target page = mock(Target.class);
        BaseTarget browser = mock(BaseTarget.class);
        stubTargetDestroyedListeners(page, browser);
        AtomicReference<Consumer<JsonNode>> browserListener = new AtomicReference<>();
        JsonNode success = new ObjectMapper().createObjectNode();
        when(page.getId()).thenReturn("primary-page");
        when(browser.addCdpListener(eq("Target.attachedToTarget"), org.mockito.ArgumentMatchers.any()))
                .thenAnswer(invocation -> {
                    browserListener.set(invocation.getArgument(1));
                    return CompletableFuture.completedFuture(null);
                });
        when(page.addCdpListener(eq("Target.attachedToTarget"), org.mockito.ArgumentMatchers.any()))
                .thenReturn(CompletableFuture.completedFuture(null));
        when(page.executeCdpCmd(anyString(), anyMap(), nullable(Float.class), nullable(String.class)))
                .thenReturn(CompletableFuture.completedFuture(success));
        when(browser.executeCdpCmd(anyString(), anyMap(), nullable(Float.class), nullable(String.class)))
                .thenReturn(CompletableFuture.completedFuture(success));
        support.installWorkerSpoofAutoAttach(page, browser).join();
        var event = new ObjectMapper().createObjectNode();
        event.put("sessionId", "oop-iframe-session");
        event.put("waitingForDebugger", true);
        var targetInfo = event.putObject("targetInfo");
        targetInfo.put("type", "iframe");
        targetInfo.put("targetId", "oop-iframe");
        browserListener.get().accept(event);
        var order = org.mockito.Mockito.inOrder(browser);
        order.verify(browser, times(1)).executeCdpCmd(eq("Emulation.setTimezoneOverride"),
                argThat(args -> "America/Sao_Paulo".equals(args.get("timezoneId"))),
                nullable(Float.class), eq("oop-iframe-session"));
        order.verify(browser, times(1)).executeCdpCmd(eq("Emulation.setLocaleOverride"),
                argThat(args -> "pt-BR".equals(args.get("locale"))),
                nullable(Float.class), eq("oop-iframe-session"));
        order.verify(browser, times(1)).executeCdpCmd(eq("Page.addScriptToEvaluateOnNewDocument"), anyMap(),
                nullable(Float.class), eq("oop-iframe-session"));
        order.verify(browser, times(1)).executeCdpCmd(eq("Runtime.runIfWaitingForDebugger"), anyMap(),
                nullable(Float.class), eq("oop-iframe-session"));
        assertThat(support.hasRuntimeFailure()).isFalse();
    }

    @Test
    void oopIframeLocaleFailureClosesTargetWithoutResumingIt() {
        ChromeFingerprintSupport support = fingerprintSupport();
        Target page = mock(Target.class);
        BaseTarget browser = mock(BaseTarget.class);
        stubTargetDestroyedListeners(page, browser);
        AtomicReference<Consumer<JsonNode>> browserListener = new AtomicReference<>();
        JsonNode success = new ObjectMapper().createObjectNode();
        when(page.getId()).thenReturn("primary-page");
        when(browser.addCdpListener(eq("Target.attachedToTarget"), org.mockito.ArgumentMatchers.any()))
                .thenAnswer(invocation -> {
                    browserListener.set(invocation.getArgument(1));
                    return CompletableFuture.completedFuture(null);
                });
        when(page.addCdpListener(eq("Target.attachedToTarget"), org.mockito.ArgumentMatchers.any()))
                .thenReturn(CompletableFuture.completedFuture(null));
        when(page.executeCdpCmd(anyString(), anyMap(), nullable(Float.class), nullable(String.class)))
                .thenReturn(CompletableFuture.completedFuture(success));
        when(browser.executeCdpCmd(anyString(), anyMap(), nullable(Float.class), nullable(String.class)))
                .thenAnswer(invocation -> {
                    String command = invocation.getArgument(0);
                    String sessionId = invocation.getArgument(3);
                    if ("Emulation.setLocaleOverride".equals(command)
                            && "oop-locale-failure-session".equals(sessionId)) {
                        return CompletableFuture.failedFuture(
                                new IllegalStateException("locale override rejected"));
                    }
                    return CompletableFuture.completedFuture(success);
                });
        support.installWorkerSpoofAutoAttach(page, browser).join();
        var event = new ObjectMapper().createObjectNode();
        event.put("sessionId", "oop-locale-failure-session");
        event.put("waitingForDebugger", true);
        var targetInfo = event.putObject("targetInfo");
        targetInfo.put("type", "iframe");
        targetInfo.put("targetId", "oop-locale-failure");
        targetInfo.put("url", "https://cross-origin.example/frame");

        browserListener.get().accept(event);

        verify(browser, timeout(2_000).times(1)).executeCdpCmd(
                eq("Target.closeTarget"),
                argThat(args -> "oop-locale-failure".equals(args.get("targetId"))),
                nullable(Float.class), nullable(String.class));
        verify(browser, never()).executeCdpCmd(eq("Runtime.runIfWaitingForDebugger"), anyMap(),
                nullable(Float.class), eq("oop-locale-failure-session"));
        assertThat(support.hasRuntimeFailure()).isTrue();
    }

    @Test
    void oopIframeLocaleFailureBecomesFatalIfTheTargetCannotBeClosed() {
        ChromeFingerprintSupport support = fingerprintSupport();
        Target page = mock(Target.class);
        BaseTarget browser = mock(BaseTarget.class);
        stubTargetDestroyedListeners(page, browser);
        AtomicReference<Consumer<JsonNode>> browserListener = new AtomicReference<>();
        JsonNode success = new ObjectMapper().createObjectNode();
        when(page.getId()).thenReturn("primary-page");
        when(browser.addCdpListener(eq("Target.attachedToTarget"), org.mockito.ArgumentMatchers.any()))
                .thenAnswer(invocation -> {
                    browserListener.set(invocation.getArgument(1));
                    return CompletableFuture.completedFuture(null);
                });
        when(page.addCdpListener(eq("Target.attachedToTarget"), org.mockito.ArgumentMatchers.any()))
                .thenReturn(CompletableFuture.completedFuture(null));
        when(page.executeCdpCmd(anyString(), anyMap(), nullable(Float.class), nullable(String.class)))
                .thenReturn(CompletableFuture.completedFuture(success));
        when(browser.executeCdpCmd(anyString(), anyMap(), nullable(Float.class), nullable(String.class)))
                .thenAnswer(invocation -> {
                    String command = invocation.getArgument(0);
                    String sessionId = invocation.getArgument(3);
                    if ("Emulation.setLocaleOverride".equals(command)
                            && "oop-uncontained-session".equals(sessionId)) {
                        return CompletableFuture.failedFuture(
                                new IllegalStateException("locale override rejected"));
                    }
                    if ("Target.closeTarget".equals(command)) {
                        return CompletableFuture.failedFuture(
                                new IllegalStateException("target close rejected"));
                    }
                    return CompletableFuture.completedFuture(success);
                });
        support.installWorkerSpoofAutoAttach(page, browser).join();
        var event = new ObjectMapper().createObjectNode();
        event.put("sessionId", "oop-uncontained-session");
        event.put("waitingForDebugger", true);
        var targetInfo = event.putObject("targetInfo");
        targetInfo.put("type", "iframe");
        targetInfo.put("targetId", "oop-uncontained-target");
        targetInfo.put("url", "https://cross-origin.example/frame");

        browserListener.get().accept(event);

        verify(browser, timeout(2_000).atLeastOnce()).executeCdpCmd(
                eq("Target.closeTarget"), anyMap(), nullable(Float.class), nullable(String.class));
        verify(browser, never()).executeCdpCmd(eq("Runtime.runIfWaitingForDebugger"), anyMap(),
                nullable(Float.class), eq("oop-uncontained-session"));
        assertThat(support.hasRuntimeFailure()).isTrue();
    }

    @Test
    void emptyBootstrapOopIframeRetriesLocaleBeforeAnyScriptOrResume() {
        ChromeFingerprintSupport support = fingerprintSupport();
        Target page = mock(Target.class);
        BaseTarget browser = mock(BaseTarget.class);
        stubTargetDestroyedListeners(page, browser);
        AtomicReference<Consumer<JsonNode>> browserListener = new AtomicReference<>();
        AtomicInteger localeAttempts = new AtomicInteger();
        JsonNode success = new ObjectMapper().createObjectNode();
        when(page.getId()).thenReturn("primary-page");
        when(browser.addCdpListener(eq("Target.attachedToTarget"), org.mockito.ArgumentMatchers.any()))
                .thenAnswer(invocation -> {
                    browserListener.set(invocation.getArgument(1));
                    return CompletableFuture.completedFuture(null);
                });
        when(page.addCdpListener(eq("Target.attachedToTarget"), org.mockito.ArgumentMatchers.any()))
                .thenReturn(CompletableFuture.completedFuture(null));
        when(page.executeCdpCmd(anyString(), anyMap(), nullable(Float.class), nullable(String.class)))
                .thenReturn(CompletableFuture.completedFuture(success));
        when(browser.executeCdpCmd(anyString(), anyMap(), nullable(Float.class), nullable(String.class)))
                .thenAnswer(invocation -> {
                    String command = invocation.getArgument(0);
                    String sessionId = invocation.getArgument(3);
                    if ("Emulation.setLocaleOverride".equals(command)
                            && "oop-empty-bootstrap-session".equals(sessionId)
                            && localeAttempts.incrementAndGet() == 1) {
                        return CompletableFuture.failedFuture(
                                CDPException.fromProtocolError(-32000, "bootstrap"));
                    }
                    return CompletableFuture.completedFuture(success);
                });
        support.installWorkerSpoofAutoAttach(page, browser).join();
        var event = new ObjectMapper().createObjectNode();
        event.put("sessionId", "oop-empty-bootstrap-session");
        event.put("waitingForDebugger", true);
        var targetInfo = event.putObject("targetInfo");
        targetInfo.put("type", "iframe");
        targetInfo.put("targetId", "oop-empty-bootstrap-target");

        browserListener.get().accept(event);

        verify(browser, timeout(2_000).times(2)).executeCdpCmd(
                eq("Emulation.setLocaleOverride"), anyMap(), nullable(Float.class),
                eq("oop-empty-bootstrap-session"));
        var order = org.mockito.Mockito.inOrder(browser);
        order.verify(browser, times(2)).executeCdpCmd(eq("Emulation.setLocaleOverride"), anyMap(),
                nullable(Float.class), eq("oop-empty-bootstrap-session"));
        order.verify(browser, times(1)).executeCdpCmd(eq("Page.addScriptToEvaluateOnNewDocument"), anyMap(),
                nullable(Float.class), eq("oop-empty-bootstrap-session"));
        order.verify(browser, times(1)).executeCdpCmd(eq("Runtime.runIfWaitingForDebugger"), anyMap(),
                nullable(Float.class), eq("oop-empty-bootstrap-session"));
        verify(browser, never()).executeCdpCmd(eq("Target.closeTarget"), anyMap(),
                nullable(Float.class), nullable(String.class));
        assertThat(support.hasRuntimeFailure()).isFalse();
    }

    @Test
    void emptyBootstrapOopIframeUsesMaterializedTargetInfoBeforeRetry() {
        ChromeFingerprintSupport support = fingerprintSupport();
        Target page = mock(Target.class);
        BaseTarget browser = mock(BaseTarget.class);
        stubTargetDestroyedListeners(page, browser);
        AtomicReference<Consumer<JsonNode>> attachedListener = new AtomicReference<>();
        AtomicReference<Consumer<JsonNode>> targetInfoListener = new AtomicReference<>();
        AtomicInteger localeAttempts = new AtomicInteger();
        JsonNode success = new ObjectMapper().createObjectNode();
        when(page.getId()).thenReturn("primary-page");
        when(browser.addCdpListener(eq("Target.attachedToTarget"), org.mockito.ArgumentMatchers.any()))
                .thenAnswer(invocation -> {
                    attachedListener.set(invocation.getArgument(1));
                    return CompletableFuture.completedFuture(null);
                });
        when(browser.addCdpListener(eq("Target.targetInfoChanged"), org.mockito.ArgumentMatchers.any()))
                .thenAnswer(invocation -> {
                    targetInfoListener.set(invocation.getArgument(1));
                    return CompletableFuture.completedFuture(null);
                });
        when(page.addCdpListener(eq("Target.attachedToTarget"), org.mockito.ArgumentMatchers.any()))
                .thenReturn(CompletableFuture.completedFuture(null));
        when(page.executeCdpCmd(anyString(), anyMap(), nullable(Float.class), nullable(String.class)))
                .thenReturn(CompletableFuture.completedFuture(success));
        when(browser.executeCdpCmd(anyString(), anyMap(), nullable(Float.class), nullable(String.class)))
                .thenAnswer(invocation -> {
                    String command = invocation.getArgument(0);
                    String sessionId = invocation.getArgument(3);
                    if ("Emulation.setLocaleOverride".equals(command)
                            && "oop-materialized-session".equals(sessionId)
                            && localeAttempts.incrementAndGet() == 1) {
                        return CompletableFuture.failedFuture(
                                CDPException.fromProtocolError(-32000, "bootstrap"));
                    }
                    return CompletableFuture.completedFuture(success);
                });
        support.installWorkerSpoofAutoAttach(page, browser).join();
        var attached = new ObjectMapper().createObjectNode();
        attached.put("sessionId", "oop-materialized-session");
        attached.put("waitingForDebugger", true);
        var initialInfo = attached.putObject("targetInfo");
        initialInfo.put("type", "iframe");
        initialInfo.put("targetId", "oop-materialized-target");

        attachedListener.get().accept(attached);
        verify(browser, timeout(1_000).times(1)).executeCdpCmd(
                eq("Emulation.setLocaleOverride"), anyMap(), nullable(Float.class),
                eq("oop-materialized-session"));
        var changed = new ObjectMapper().createObjectNode();
        var materializedInfo = changed.putObject("targetInfo");
        materializedInfo.put("type", "iframe");
        materializedInfo.put("targetId", "oop-materialized-target");
        materializedInfo.put("url", "https://cross-origin.example/frame");
        targetInfoListener.get().accept(changed);

        verify(browser, timeout(2_000).times(2)).executeCdpCmd(
                eq("Emulation.setLocaleOverride"), anyMap(), nullable(Float.class),
                eq("oop-materialized-session"));
        verify(browser, timeout(2_000).times(1)).executeCdpCmd(
                eq("Runtime.runIfWaitingForDebugger"), anyMap(), nullable(Float.class),
                eq("oop-materialized-session"));
        verify(browser, never()).executeCdpCmd(eq("Target.closeTarget"), anyMap(),
                nullable(Float.class), nullable(String.class));
        assertThat(support.hasRuntimeFailure()).isFalse();
    }

    @Test
    void permanentlyEmptyOopIframeInheritsNativeLocaleAndResumesWithoutClosing() throws Exception {
        ChromeFingerprintSupport support = fingerprintSupport();
        support.configureHeadlessFingerprintArgs(Map.of());
        Target page = mock(Target.class);
        BaseTarget browser = mock(BaseTarget.class);
        stubTargetDestroyedListeners(page, browser);
        AtomicReference<Consumer<JsonNode>> browserListener = new AtomicReference<>();
        JsonNode success = new ObjectMapper().createObjectNode();
        when(page.getId()).thenReturn("primary-page");
        when(browser.addCdpListener(eq("Target.attachedToTarget"), org.mockito.ArgumentMatchers.any()))
                .thenAnswer(invocation -> {
                    browserListener.set(invocation.getArgument(1));
                    return CompletableFuture.completedFuture(null);
                });
        when(page.addCdpListener(eq("Target.attachedToTarget"), org.mockito.ArgumentMatchers.any()))
                .thenReturn(CompletableFuture.completedFuture(null));
        when(page.executeCdpCmd(anyString(), anyMap(), nullable(Float.class), nullable(String.class)))
                .thenReturn(CompletableFuture.completedFuture(success));
        when(browser.executeCdpCmd(anyString(), anyMap(), nullable(Float.class), nullable(String.class)))
                .thenAnswer(invocation -> {
                    String command = invocation.getArgument(0);
                    String sessionId = invocation.getArgument(3);
                    if ("Emulation.setLocaleOverride".equals(command)
                            && "oop-permanently-empty-session".equals(sessionId)) {
                        return CompletableFuture.failedFuture(
                                CDPException.fromProtocolError(-32000, "bootstrap"));
                    }
                    return CompletableFuture.completedFuture(success);
                });
        support.installWorkerSpoofAutoAttach(page, browser).join();
        var event = new ObjectMapper().createObjectNode();
        event.put("sessionId", "oop-permanently-empty-session");
        event.put("waitingForDebugger", true);
        var targetInfo = event.putObject("targetInfo");
        targetInfo.put("type", "iframe");
        targetInfo.put("targetId", "oop-permanently-empty-target");

        browserListener.get().accept(event);

        verify(browser, never()).executeCdpCmd(
                eq("Emulation.setLocaleOverride"), anyMap(), nullable(Float.class),
                eq("oop-permanently-empty-session"));
        verify(browser, timeout(2_000).times(1)).executeCdpCmd(
                eq("Page.addScriptToEvaluateOnNewDocument"), anyMap(),
                nullable(Float.class), eq("oop-permanently-empty-session"));
        verify(browser, timeout(2_000).times(1)).executeCdpCmd(
                eq("Runtime.runIfWaitingForDebugger"), anyMap(),
                nullable(Float.class), eq("oop-permanently-empty-session"));
        verify(browser, never()).executeCdpCmd(eq("Target.closeTarget"), anyMap(),
                nullable(Float.class), nullable(String.class));
        assertThat(support.hasRuntimeFailure()).isFalse();
    }

    @Test
    void destroyedEmptyOopIframeCancelsLocaleRetryAndCompletesLifecycleGate() throws Exception {
        ChromeFingerprintSupport support = fingerprintSupport();
        Target page = mock(Target.class);
        BaseTarget browser = mock(BaseTarget.class);
        stubTargetDestroyedListeners(page, browser);
        AtomicReference<Consumer<JsonNode>> attachedListener = new AtomicReference<>();
        AtomicReference<Consumer<JsonNode>> destroyedListener = new AtomicReference<>();
        AtomicInteger localeAttempts = new AtomicInteger();
        JsonNode success = new ObjectMapper().createObjectNode();
        when(page.getId()).thenReturn("primary-page");
        when(browser.addCdpListener(eq("Target.attachedToTarget"), org.mockito.ArgumentMatchers.any()))
                .thenAnswer(invocation -> {
                    attachedListener.set(invocation.getArgument(1));
                    return CompletableFuture.completedFuture(null);
                });
        when(browser.addCdpListener(eq("Target.targetDestroyed"), org.mockito.ArgumentMatchers.any()))
                .thenAnswer(invocation -> {
                    destroyedListener.set(invocation.getArgument(1));
                    return CompletableFuture.completedFuture(null);
                });
        when(page.addCdpListener(eq("Target.attachedToTarget"), org.mockito.ArgumentMatchers.any()))
                .thenReturn(CompletableFuture.completedFuture(null));
        when(page.executeCdpCmd(anyString(), anyMap(), nullable(Float.class), nullable(String.class)))
                .thenReturn(CompletableFuture.completedFuture(success));
        when(browser.executeCdpCmd(anyString(), anyMap(), nullable(Float.class), nullable(String.class)))
                .thenAnswer(invocation -> {
                    if ("Emulation.setLocaleOverride".equals(invocation.getArgument(0))) {
                        localeAttempts.incrementAndGet();
                        return CompletableFuture.failedFuture(
                                CDPException.fromProtocolError(-32000, "bootstrap"));
                    }
                    return CompletableFuture.completedFuture(success);
                });
        support.installWorkerSpoofAutoAttach(page, browser).join();
        var attached = new ObjectMapper().createObjectNode();
        attached.put("sessionId", "oop-destroyed-session");
        attached.put("waitingForDebugger", true);
        var targetInfo = attached.putObject("targetInfo");
        targetInfo.put("type", "iframe");
        targetInfo.put("targetId", "oop-destroyed-target");

        attachedListener.get().accept(attached);
        verify(browser, timeout(1_000).times(1)).executeCdpCmd(
                eq("Emulation.setLocaleOverride"), anyMap(), nullable(Float.class),
                eq("oop-destroyed-session"));
        var destroyed = new ObjectMapper().createObjectNode();
        destroyed.put("targetId", "oop-destroyed-target");
        destroyedListener.get().accept(destroyed);
        Thread.sleep(150);

        assertThat(localeAttempts).hasValue(1);
        verify(browser, never()).executeCdpCmd(eq("Page.addScriptToEvaluateOnNewDocument"), anyMap(),
                nullable(Float.class), eq("oop-destroyed-session"));
        verify(browser, never()).executeCdpCmd(eq("Runtime.runIfWaitingForDebugger"), anyMap(),
                nullable(Float.class), eq("oop-destroyed-session"));
        verify(browser, never()).executeCdpCmd(eq("Target.closeTarget"), anyMap(),
                nullable(Float.class), nullable(String.class));
        assertThat(support.hasRuntimeFailure()).isFalse();
    }

    @ParameterizedTest
    @ValueSource(strings = {"destroy", "detach", "close"})
    void lifecycleDuringPendingLocaleNeverContinuesTheOopifChain(String lifecycle) throws Exception {
        ChromeFingerprintSupport support = fingerprintSupport();
        Target page = mock(Target.class);
        BaseTarget browser = mock(BaseTarget.class);
        stubTargetDestroyedListeners(page, browser);
        AtomicReference<Consumer<JsonNode>> attachedListener = new AtomicReference<>();
        AtomicReference<Consumer<JsonNode>> destroyedListener = new AtomicReference<>();
        AtomicReference<Consumer<JsonNode>> detachedListener = new AtomicReference<>();
        CompletableFuture<JsonNode> pendingLocale = new CompletableFuture<>();
        JsonNode success = new ObjectMapper().createObjectNode();
        when(page.getId()).thenReturn("primary-page");
        when(browser.addCdpListener(eq("Target.attachedToTarget"), org.mockito.ArgumentMatchers.any()))
                .thenAnswer(invocation -> {
                    attachedListener.set(invocation.getArgument(1));
                    return CompletableFuture.completedFuture(null);
                });
        when(browser.addCdpListener(eq("Target.targetDestroyed"), org.mockito.ArgumentMatchers.any()))
                .thenAnswer(invocation -> {
                    destroyedListener.set(invocation.getArgument(1));
                    return CompletableFuture.completedFuture(null);
                });
        when(browser.addCdpListener(eq("Target.detachedFromTarget"), org.mockito.ArgumentMatchers.any()))
                .thenAnswer(invocation -> {
                    detachedListener.set(invocation.getArgument(1));
                    return CompletableFuture.completedFuture(null);
                });
        when(page.addCdpListener(eq("Target.attachedToTarget"), org.mockito.ArgumentMatchers.any()))
                .thenReturn(CompletableFuture.completedFuture(null));
        when(page.executeCdpCmd(anyString(), anyMap(), nullable(Float.class), nullable(String.class)))
                .thenReturn(CompletableFuture.completedFuture(success));
        when(browser.executeCdpCmd(anyString(), anyMap(), nullable(Float.class), nullable(String.class)))
                .thenAnswer(invocation -> "Emulation.setLocaleOverride".equals(invocation.getArgument(0))
                        ? pendingLocale
                        : CompletableFuture.completedFuture(success));
        support.installWorkerSpoofAutoAttach(page, browser).join();
        var attached = new ObjectMapper().createObjectNode();
        attached.put("sessionId", "oop-pending-session");
        attached.put("waitingForDebugger", true);
        var targetInfo = attached.putObject("targetInfo");
        targetInfo.put("type", "iframe");
        targetInfo.put("targetId", "oop-pending-target");
        attachedListener.get().accept(attached);
        verify(browser, timeout(1_000).times(1)).executeCdpCmd(
                eq("Emulation.setLocaleOverride"), anyMap(), nullable(Float.class),
                eq("oop-pending-session"));

        if ("destroy".equals(lifecycle)) {
            var event = new ObjectMapper().createObjectNode();
            event.put("targetId", "oop-pending-target");
            destroyedListener.get().accept(event);
        } else if ("detach".equals(lifecycle)) {
            var event = new ObjectMapper().createObjectNode();
            event.put("sessionId", "oop-pending-session");
            event.put("targetId", "oop-pending-target");
            detachedListener.get().accept(event);
        } else {
            support.closeWorkerSpoofSupport().join();
        }
        pendingLocale.complete(success);
        Thread.sleep(100);

        verify(browser, never()).executeCdpCmd(eq("Page.enable"), anyMap(),
                nullable(Float.class), eq("oop-pending-session"));
        verify(browser, never()).executeCdpCmd(eq("Page.addScriptToEvaluateOnNewDocument"), anyMap(),
                nullable(Float.class), eq("oop-pending-session"));
        verify(browser, never()).executeCdpCmd(eq("Runtime.runIfWaitingForDebugger"), anyMap(),
                nullable(Float.class), eq("oop-pending-session"));
        verify(browser, never()).executeCdpCmd(eq("Target.closeTarget"), anyMap(),
                nullable(Float.class), nullable(String.class));
        assertThat(support.hasRuntimeFailure()).isFalse();
    }

    private static JsonNode minimalLocaleProbe(BrowserFingerprintProfile profile) {
        profile.applyDefaults();
        var response = new ObjectMapper().createObjectNode();
        var value = response.putObject("result").putObject("value");
        value.put("intlLocale", profile.locale);
        value.put("navigatorLanguage", profile.languages.get(0));
        return response;
    }

    private static int localeAttemptsFor(BaseTarget browser, String sessionId) {
        return org.mockito.Mockito.mockingDetails(browser).getInvocations().stream()
                .filter(invocation -> "executeCdpCmd".equals(invocation.getMethod().getName()))
                .filter(invocation -> "Emulation.setLocaleOverride".equals(invocation.getArgument(0)))
                .filter(invocation -> sessionId.equals(invocation.getArgument(3)))
                .mapToInt(ignored -> 1)
                .sum();
    }
}
