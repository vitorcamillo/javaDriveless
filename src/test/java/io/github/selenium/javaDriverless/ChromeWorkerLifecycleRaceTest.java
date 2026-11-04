package io.github.selenium.javaDriverless;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.selenium.javaDriverless.types.BaseTarget;
import io.github.selenium.javaDriverless.types.BrowserFingerprintProfile;
import io.github.selenium.javaDriverless.types.Target;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.slf4j.LoggerFactory;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.ArgumentMatchers.nullable;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ChromeWorkerLifecycleRaceTest {
    @ParameterizedTest
    @ValueSource(strings = {"Target.targetDestroyed", "Target.detachedFromTarget"})
    void terminatedWorkerBeforeDebuggerPauseDoesNotPoisonRuntimeFailure(String lifecycleEvent) {
        Target page = mock(Target.class);
        BaseTarget browser = mock(BaseTarget.class);
        ObjectMapper mapper = new ObjectMapper();
        AtomicReference<Consumer<JsonNode>> attachedListener = new AtomicReference<>();
        AtomicReference<Consumer<JsonNode>> destroyedListener = new AtomicReference<>();
        AtomicReference<Consumer<JsonNode>> detachedListener = new AtomicReference<>();
        AtomicReference<BiConsumer<String, JsonNode>> pausedListener = new AtomicReference<>();
        AtomicReference<Throwable> runtimeFailure = new AtomicReference<>();
        AtomicBoolean targetTerminated = new AtomicBoolean();
        JsonNode success = mapper.createObjectNode();
        JsonNode breakpoint = mapper.createObjectNode().put("breakpointId", "bp-ephemeral");

        when(page.getId()).thenReturn("primary-page");
        when(browser.addCdpListener(eq("Target.attachedToTarget"), any()))
                .thenAnswer(invocation -> {
                    attachedListener.set(invocation.getArgument(1));
                    return CompletableFuture.completedFuture(null);
                });
        when(browser.addCdpListener(eq("Target.targetDestroyed"), any()))
                .thenAnswer(invocation -> {
                    destroyedListener.set(invocation.getArgument(1));
                    return CompletableFuture.completedFuture(null);
                });
        when(browser.addCdpListener(eq("Target.detachedFromTarget"), any()))
                .thenAnswer(invocation -> {
                    detachedListener.set(invocation.getArgument(1));
                    return CompletableFuture.completedFuture(null);
                });
        when(browser.addCdpSessionListener(eq("Debugger.paused"), any()))
                .thenAnswer(invocation -> {
                    pausedListener.set(invocation.getArgument(1));
                    return CompletableFuture.completedFuture(null);
                });
        when(page.addCdpListener(anyString(), any()))
                .thenReturn(CompletableFuture.completedFuture(null));
        when(page.addCdpSessionListener(anyString(), any()))
                .thenReturn(CompletableFuture.completedFuture(null));
        when(page.executeCdpCmd(anyString(), anyMap(), nullable(Float.class), nullable(String.class)))
                .thenReturn(CompletableFuture.completedFuture(success));
        when(browser.executeCdpCmd(anyString(), anyMap(), nullable(Float.class), nullable(String.class)))
                .thenAnswer(invocation -> {
                    String command = invocation.getArgument(0);
                    if ("Debugger.setInstrumentationBreakpoint".equals(command)) {
                        return CompletableFuture.completedFuture(breakpoint);
                    }
                    if (targetTerminated.get() && "Runtime.evaluate".equals(command)) {
                        return CompletableFuture.failedFuture(
                                new IllegalStateException("worker session no longer exists"));
                    }
                    return CompletableFuture.completedFuture(success);
                });

        ChromeWorkerSpoofSupport support = new ChromeWorkerSpoofSupport(
                BrowserFingerprintProfile.brLinux(),
                mapper,
                LoggerFactory.getLogger(ChromeWorkerLifecycleRaceTest.class),
                ignored -> "void 0",
                failure -> runtimeFailure.compareAndSet(null, failure));
        support.install(page, browser).join();

        JsonNode attached = mapper.createObjectNode()
                .put("sessionId", "ephemeral-session")
                .put("waitingForDebugger", true)
                .set("targetInfo", mapper.createObjectNode()
                        .put("type", "service_worker")
                        .put("targetId", "ephemeral-target"));
        attachedListener.get().accept(attached);

        targetTerminated.set(true);
        JsonNode terminated = mapper.createObjectNode()
                .put("sessionId", "ephemeral-session")
                .put("targetId", "ephemeral-target");
        if ("Target.targetDestroyed".equals(lifecycleEvent)) {
            destroyedListener.get().accept(terminated);
        } else {
            detachedListener.get().accept(terminated);
        }
        pausedListener.get().accept("ephemeral-session", mapper.createObjectNode());

        assertThat(runtimeFailure.get()).isNull();
        verify(browser, never()).executeCdpCmd(
                eq("Runtime.evaluate"), anyMap(), isNull(), eq("ephemeral-session"));
    }
}
