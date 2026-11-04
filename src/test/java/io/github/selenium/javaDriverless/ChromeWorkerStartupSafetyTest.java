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

class ChromeWorkerStartupSafetyTest extends ChromeSessionStartupTestSupport {
    @ParameterizedTest
    @ValueSource(strings = {"service_worker", "shared_worker"})
    void pausedBackgroundWorkerStopsBeforeItsFirstScriptAndInstallsSpoofBeforeResume(String targetType) {
        ChromeFingerprintSupport support = fingerprintSupport();
        Target page = mock(Target.class);
        BaseTarget browser = mock(BaseTarget.class);
        stubTargetDestroyedListeners(page, browser);
        AtomicReference<Consumer<JsonNode>> browserListener = new AtomicReference<>();
        AtomicReference<BiConsumer<String, JsonNode>> pausedListener = new AtomicReference<>();
        JsonNode success = new ObjectMapper().createObjectNode();
        JsonNode breakpoint = new ObjectMapper().createObjectNode().put("breakpointId", "bp-1");

        when(browser.addCdpListener(eq("Target.attachedToTarget"), org.mockito.ArgumentMatchers.any()))
                .thenAnswer(invocation -> {
                    browserListener.set(invocation.getArgument(1));
                    return CompletableFuture.completedFuture(null);
                });
        when(browser.addCdpSessionListener(eq("Debugger.paused"), org.mockito.ArgumentMatchers.any()))
                .thenAnswer(invocation -> {
                    pausedListener.set(invocation.getArgument(1));
                    return CompletableFuture.completedFuture(null);
                });
        when(page.addCdpListener(eq("Target.attachedToTarget"), org.mockito.ArgumentMatchers.any()))
                .thenReturn(CompletableFuture.completedFuture(null));
        when(page.executeCdpCmd(anyString(), anyMap(), nullable(Float.class), nullable(String.class)))
                .thenReturn(CompletableFuture.completedFuture(success));
        when(browser.executeCdpCmd(anyString(), anyMap(), nullable(Float.class), nullable(String.class)))
                .thenAnswer(invocation -> CompletableFuture.completedFuture(
                        "Debugger.setInstrumentationBreakpoint".equals(invocation.getArgument(0))
                                ? breakpoint : success));

        support.installWorkerSpoofAutoAttach(page, browser).join();
        var event = new ObjectMapper().createObjectNode();
        event.put("sessionId", targetType + "-session");
        event.put("waitingForDebugger", true);
        var targetInfo = event.putObject("targetInfo");
        targetInfo.put("type", targetType);
        targetInfo.put("targetId", targetType + "-target");

        browserListener.get().accept(event);
        pausedListener.get().accept(targetType + "-session", new ObjectMapper().createObjectNode());

        var order = org.mockito.Mockito.inOrder(browser);
        order.verify(browser, timeout(1000)).executeCdpCmd(eq("Debugger.enable"), anyMap(),
                nullable(Float.class), eq(targetType + "-session"));
        order.verify(browser).executeCdpCmd(eq("Debugger.setInstrumentationBreakpoint"), anyMap(),
                nullable(Float.class), eq(targetType + "-session"));
        order.verify(browser).executeCdpCmd(eq("Runtime.runIfWaitingForDebugger"), anyMap(),
                nullable(Float.class), eq(targetType + "-session"));
        order.verify(browser).executeCdpCmd(eq("Runtime.evaluate"), anyMap(),
                nullable(Float.class), eq(targetType + "-session"));
        order.verify(browser).executeCdpCmd(eq("Emulation.setTimezoneOverride"), anyMap(),
                nullable(Float.class), eq(targetType + "-session"));
        order.verify(browser).executeCdpCmd(eq("Debugger.removeBreakpoint"),
                argThat(args -> "bp-1".equals(args.get("breakpointId"))),
                nullable(Float.class), eq(targetType + "-session"));
        order.verify(browser).executeCdpCmd(eq("Debugger.resume"), anyMap(),
                nullable(Float.class), eq(targetType + "-session"));
        order.verify(browser).executeCdpCmd(eq("Debugger.disable"), anyMap(),
                nullable(Float.class), eq(targetType + "-session"));
        assertThat(support.hasRuntimeFailure()).isFalse();
    }

    @Test
    void asyncWorkerInjectionFailureClosesTargetWithoutResumingIt() {
        ChromeFingerprintSupport support = fingerprintSupport();
        Target page = mock(Target.class);
        BaseTarget browser = mock(BaseTarget.class);
        stubTargetDestroyedListeners(page, browser);
        AtomicReference<Consumer<JsonNode>> browserListener = new AtomicReference<>();
        AtomicReference<Consumer<JsonNode>> pageListener = new AtomicReference<>();
        JsonNode success = new ObjectMapper().createObjectNode();

        when(browser.addCdpListener(eq("Target.attachedToTarget"), org.mockito.ArgumentMatchers.any()))
                .thenAnswer(invocation -> {
                    browserListener.set(invocation.getArgument(1));
                    return CompletableFuture.completedFuture(null);
                });
        when(page.addCdpListener(eq("Target.attachedToTarget"), org.mockito.ArgumentMatchers.any()))
                .thenAnswer(invocation -> {
                    pageListener.set(invocation.getArgument(1));
                    return CompletableFuture.completedFuture(null);
                });
        when(page.executeCdpCmd(anyString(), anyMap(), nullable(Float.class), nullable(String.class)))
                .thenReturn(CompletableFuture.completedFuture(success));
        when(browser.executeCdpCmd(anyString(), anyMap(), nullable(Float.class), nullable(String.class)))
                .thenAnswer(invocation -> "Runtime.evaluate".equals(invocation.getArgument(0))
                        ? CompletableFuture.failedFuture(new IllegalStateException("worker evaluate failed"))
                        : CompletableFuture.completedFuture(success));

        support.installWorkerSpoofAutoAttach(page, browser).join();
        assertThat(browserListener.get()).isNotNull();
        assertThat(pageListener.get()).isNotNull();

        var event = new ObjectMapper().createObjectNode();
        event.put("sessionId", "session-1");
        event.put("waitingForDebugger", false);
        var targetInfo = event.putObject("targetInfo");
        targetInfo.put("type", "worker");
        targetInfo.put("targetId", "worker-1");
        browserListener.get().accept(event);

        assertThat(support.hasRuntimeFailure()).isTrue();
        assertThat(support.runtimeFailureMessage()).hasValueSatisfying(
                message -> assertThat(message).contains("Runtime.evaluate", "worker-1"));
        verify(browser).executeCdpCmd(eq("Target.closeTarget"),
                argThat(args -> "worker-1".equals(args.get("targetId"))),
                nullable(Float.class), nullable(String.class));
        verify(browser, never()).executeCdpCmd(eq("Debugger.resume"), anyMap(),
                nullable(Float.class), eq("session-1"));
    }

    @Test
    void workerEvaluateExceptionDetailsClosesTargetWithoutResumingIt() {
        ChromeFingerprintSupport support = fingerprintSupport();
        Target page = mock(Target.class);
        BaseTarget browser = mock(BaseTarget.class);
        stubTargetDestroyedListeners(page, browser);
        AtomicReference<Consumer<JsonNode>> browserListener = new AtomicReference<>();
        ObjectMapper mapper = new ObjectMapper();
        JsonNode success = mapper.createObjectNode();
        var evaluateFailure = mapper.createObjectNode();
        evaluateFailure.putObject("exceptionDetails").put("text", "SyntaxError");

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
                .thenAnswer(invocation -> "Runtime.evaluate".equals(invocation.getArgument(0))
                        ? CompletableFuture.completedFuture(evaluateFailure)
                        : CompletableFuture.completedFuture(success));

        support.installWorkerSpoofAutoAttach(page, browser).join();
        var event = mapper.createObjectNode();
        event.put("sessionId", "session-js-error");
        event.put("waitingForDebugger", false);
        var targetInfo = event.putObject("targetInfo");
        targetInfo.put("type", "worker");
        targetInfo.put("targetId", "worker-js-error");

        browserListener.get().accept(event);

        assertThat(support.runtimeFailureMessage()).hasValueSatisfying(
                message -> assertThat(message).contains("Runtime.evaluate", "worker-js-error"));
        verify(browser).executeCdpCmd(eq("Target.closeTarget"),
                argThat(args -> "worker-js-error".equals(args.get("targetId"))),
                nullable(Float.class), nullable(String.class));
        verify(browser, never()).executeCdpCmd(eq("Debugger.resume"), anyMap(),
                nullable(Float.class), eq("session-js-error"));
    }

    @Test
    void synchronousWorkerDispatchFailureClosesTargetWithoutResumingIt() {
        ChromeFingerprintSupport support = fingerprintSupport();
        Target page = mock(Target.class);
        BaseTarget browser = mock(BaseTarget.class);
        stubTargetDestroyedListeners(page, browser);
        AtomicReference<Consumer<JsonNode>> browserListener = new AtomicReference<>();
        JsonNode success = new ObjectMapper().createObjectNode();

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
                    if ("Debugger.enable".equals(invocation.getArgument(0))) {
                        throw new IllegalStateException("sync dispatch failed");
                    }
                    return CompletableFuture.completedFuture(success);
                });

        support.installWorkerSpoofAutoAttach(page, browser).join();
        var event = new ObjectMapper().createObjectNode();
        event.put("sessionId", "session-sync");
        event.put("waitingForDebugger", true);
        var targetInfo = event.putObject("targetInfo");
        targetInfo.put("type", "worker");
        targetInfo.put("targetId", "worker-sync");

        browserListener.get().accept(event);

        assertThat(support.hasRuntimeFailure()).isTrue();
        assertThat(support.runtimeFailureMessage()).hasValueSatisfying(
                message -> assertThat(message).contains("Debugger.enable", "worker-sync"));
        verify(browser, times(1)).executeCdpCmd(eq("Target.closeTarget"),
                argThat(args -> "worker-sync".equals(args.get("targetId"))),
                nullable(Float.class), nullable(String.class));
        verify(browser, never()).executeCdpCmd(eq("Debugger.resume"), anyMap(),
                nullable(Float.class), eq("session-sync"));
    }

    @Test
    void destroyedTargetReleasesDeduplicationGateAndAllowsIdReuse() {
        ChromeFingerprintSupport support = fingerprintSupport();
        Target page = mock(Target.class);
        BaseTarget browser = mock(BaseTarget.class);
        when(page.getId()).thenReturn("primary-page");
        AtomicReference<Consumer<JsonNode>> browserAttached = new AtomicReference<>();
        AtomicReference<Consumer<JsonNode>> browserDestroyed = new AtomicReference<>();
        JsonNode success = new ObjectMapper().createObjectNode();

        when(browser.addCdpListener(anyString(), org.mockito.ArgumentMatchers.any()))
                .thenAnswer(invocation -> {
                    if ("Target.attachedToTarget".equals(invocation.getArgument(0))) {
                        browserAttached.set(invocation.getArgument(1));
                    } else if ("Target.targetDestroyed".equals(invocation.getArgument(0))) {
                        browserDestroyed.set(invocation.getArgument(1));
                    }
                    return CompletableFuture.completedFuture(null);
                });
        when(page.addCdpListener(anyString(), org.mockito.ArgumentMatchers.any()))
                .thenAnswer(invocation -> {
                    return CompletableFuture.completedFuture(null);
                });
        when(page.executeCdpCmd(anyString(), anyMap(), nullable(Float.class), nullable(String.class)))
                .thenReturn(CompletableFuture.completedFuture(success));
        when(browser.executeCdpCmd(anyString(), anyMap(), nullable(Float.class), nullable(String.class)))
                .thenReturn(CompletableFuture.completedFuture(success));

        support.installWorkerSpoofAutoAttach(page, browser).join();
        assertThat(browserDestroyed.get()).isNotNull();

        var first = new ObjectMapper().createObjectNode();
        first.put("sessionId", "first-session");
        first.put("waitingForDebugger", false);
        var firstInfo = first.putObject("targetInfo");
        firstInfo.put("type", "service_worker");
        firstInfo.put("targetId", "reused-target");
        var duplicate = new ObjectMapper().createObjectNode();
        duplicate.put("sessionId", "duplicate-session");
        duplicate.put("waitingForDebugger", false);
        var duplicateInfo = duplicate.putObject("targetInfo");
        duplicateInfo.put("type", "service_worker");
        duplicateInfo.put("targetId", "reused-target");

        browserAttached.get().accept(first);
        browserAttached.get().accept(duplicate);

        var destroyed = new ObjectMapper().createObjectNode();
        destroyed.put("targetId", "reused-target");
        browserDestroyed.get().accept(destroyed);

        var reused = new ObjectMapper().createObjectNode();
        reused.put("sessionId", "reused-session");
        reused.put("waitingForDebugger", false);
        var reusedInfo = reused.putObject("targetInfo");
        reusedInfo.put("type", "service_worker");
        reusedInfo.put("targetId", "reused-target");
        browserAttached.get().accept(reused);

        verify(browser, times(1)).executeCdpCmd(eq("Runtime.evaluate"), anyMap(),
                nullable(Float.class), eq("first-session"));
        verify(browser, never()).executeCdpCmd(eq("Runtime.evaluate"), anyMap(),
                nullable(Float.class), eq("duplicate-session"));
        verify(browser, times(1)).executeCdpCmd(eq("Runtime.evaluate"), anyMap(),
                nullable(Float.class), eq("reused-session"));
    }

}
