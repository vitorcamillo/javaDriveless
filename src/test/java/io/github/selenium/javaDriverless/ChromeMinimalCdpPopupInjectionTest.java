package io.github.selenium.javaDriverless;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.NullNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.selenium.javaDriverless.types.BaseTarget;
import io.github.selenium.javaDriverless.types.BrowserFingerprintProfile;
import io.github.selenium.javaDriverless.types.ChromeOptions;
import io.github.selenium.javaDriverless.types.Target;
import io.github.selenium.javaDriverless.utils.UserAgentResolver;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.slf4j.LoggerFactory;

import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ChromeMinimalCdpPopupInjectionTest {

    @Test
    @SuppressWarnings("unchecked")
    void popupReceivesNativeOverridesAndDescendantScopesBeforeResume() {
        Harness harness = new Harness();
        harness.install();
        ArgumentCaptor<Consumer<JsonNode>> listener = ArgumentCaptor.forClass(Consumer.class);
        verify(harness.popupBrowser).addCdpListener(
                eq("Target.attachedToTarget"), listener.capture());
        clearInvocations(harness.page, harness.browser, harness.popupBrowser);

        listener.getValue().accept(attached("page", "site-popup", "popup-session", "minimal-root"));

        InOrder order = inOrder(harness.popupBrowser, harness.browser);
        order.verify(harness.popupBrowser).executeCdpCmd(
                eq("Emulation.setTimezoneOverride"),
                eq(Map.of("timezoneId", harness.profile.timezoneId)),
                isNull(), eq("popup-session"));
        order.verify(harness.popupBrowser).executeCdpCmd(
                eq("Emulation.setEmulatedMedia"), any(), isNull(), eq("popup-session"));
        order.verify(harness.popupBrowser).executeCdpCmd(
                eq("Emulation.setGeolocationOverride"), any(), isNull(), eq("popup-session"));
        ArgumentCaptor<Map<String, Object>> pageScope = ArgumentCaptor.forClass(Map.class);
        order.verify(harness.popupBrowser).executeCdpCmd(
                eq("Target.setAutoAttach"), pageScope.capture(), isNull(), eq("popup-session"));
        assertEquals(Boolean.TRUE, pageScope.getValue().get("waitForDebuggerOnStart"));
        ArgumentCaptor<Map<String, Object>> browserScope = ArgumentCaptor.forClass(Map.class);
        order.verify(harness.browser).executeCdpCmd(
                eq("Target.autoAttachRelated"), browserScope.capture(), isNull(), isNull());
        assertEquals("site-popup", browserScope.getValue().get("targetId"));
        order.verify(harness.popupBrowser).executeCdpCmd(
                eq("Runtime.runIfWaitingForDebugger"), any(), isNull(), eq("popup-session"));
        verify(harness.popupBrowser, never()).executeCdpCmd(
                eq("Emulation.setLocaleOverride"), any(), any(), any());
        assertFalse(harness.support.hasRuntimeFailure());
    }

    @Test
    @SuppressWarnings("unchecked")
    void rendererNewPopupUsesNativeProcessBaselineWithoutPostResumeOverrides() {
        Harness harness = new Harness();
        harness.install();
        ArgumentCaptor<Consumer<JsonNode>> listener = ArgumentCaptor.forClass(Consumer.class);
        verify(harness.popupBrowser).addCdpListener(
                eq("Target.attachedToTarget"), listener.capture());
        clearInvocations(harness.page, harness.browser, harness.popupBrowser);
        listener.getValue().accept(attached(
                "page", "new-renderer-popup", "new-renderer-session", "minimal-root", false));

        InOrder order = inOrder(harness.popupBrowser, harness.browser);
        order.verify(harness.popupBrowser).executeCdpCmd(
                eq("Emulation.setGeolocationOverride"), any(), isNull(), eq("new-renderer-session"));
        order.verify(harness.popupBrowser).executeCdpCmd(
                eq("Target.setAutoAttach"), any(), isNull(), eq("new-renderer-session"));
        order.verify(harness.browser).executeCdpCmd(
                eq("Target.autoAttachRelated"), any(), isNull(), isNull());
        order.verify(harness.popupBrowser).executeCdpCmd(
                eq("Runtime.runIfWaitingForDebugger"), any(), isNull(), eq("new-renderer-session"));
        verify(harness.popupBrowser, never()).executeCdpCmd(
                eq("Emulation.setTimezoneOverride"), any(), isNull(), eq("new-renderer-session"));
        verify(harness.popupBrowser, never()).executeCdpCmd(
                eq("Emulation.setEmulatedMedia"), any(), isNull(), eq("new-renderer-session"));
        assertFalse(harness.support.hasRuntimeFailure());
    }

    @Test
    @SuppressWarnings("unchecked")
    void closesPausedWorkerWhenResumeIsRejected() {
        Harness harness = new Harness();
        harness.install();
        ArgumentCaptor<Consumer<JsonNode>> listener = ArgumentCaptor.forClass(Consumer.class);
        verify(harness.page).addCdpListener(eq("Target.attachedToTarget"), listener.capture());
        clearInvocations(harness.page, harness.browser, harness.popupBrowser);
        when(harness.page.executeCdpCmd(
                eq("Runtime.runIfWaitingForDebugger"), any(), isNull(), eq("worker-session")))
                .thenReturn(CompletableFuture.failedFuture(new IllegalStateException("resume rejected")));

        listener.getValue().accept(attached("worker", "paused-worker", "worker-session", ""));

        ArgumentCaptor<Map<String, Object>> close = ArgumentCaptor.forClass(Map.class);
        verify(harness.browser, timeout(1000)).executeCdpCmd(
                eq("Target.closeTarget"), close.capture(), isNull(), isNull());
        assertEquals("paused-worker", close.getValue().get("targetId"));
        verify(harness.page, times(1)).executeCdpCmd(
                eq("Runtime.runIfWaitingForDebugger"), any(), isNull(), eq("worker-session"));
        assertTrue(harness.support.hasRuntimeFailure());
    }

    @Test
    @Timeout(15)
    @SuppressWarnings("unchecked")
    void closesPausedWorkerWhenResumeTimesOut() {
        Harness harness = new Harness();
        harness.install();
        ArgumentCaptor<Consumer<JsonNode>> listener = ArgumentCaptor.forClass(Consumer.class);
        verify(harness.page).addCdpListener(eq("Target.attachedToTarget"), listener.capture());
        clearInvocations(harness.page, harness.browser, harness.popupBrowser);
        when(harness.page.executeCdpCmd(
                eq("Runtime.runIfWaitingForDebugger"), any(), isNull(), eq("worker-timeout-session")))
                .thenReturn(new CompletableFuture<>());

        listener.getValue().accept(attached(
                "worker", "timed-out-worker", "worker-timeout-session", ""));

        ArgumentCaptor<Map<String, Object>> close = ArgumentCaptor.forClass(Map.class);
        verify(harness.browser, timeout(12000)).executeCdpCmd(
                eq("Target.closeTarget"), close.capture(), isNull(), isNull());
        assertEquals("timed-out-worker", close.getValue().get("targetId"));
        assertTrue(harness.support.hasRuntimeFailure());
    }

    private static ObjectNode attached(
            String type, String targetId, String sessionId, String openerId) {
        return attached(type, targetId, sessionId, openerId, true);
    }

    private static ObjectNode attached(
            String type, String targetId, String sessionId, String openerId,
            boolean canAccessOpener) {
        ObjectNode attached = new ObjectMapper().createObjectNode();
        attached.put("sessionId", sessionId);
        attached.put("waitingForDebugger", true);
        ObjectNode info = attached.putObject("targetInfo");
        info.put("type", type);
        info.put("targetId", targetId);
        if (!openerId.isBlank()) {
            info.put("openerId", openerId);
            info.put("canAccessOpener", canAccessOpener);
        }
        return attached;
    }

    private static final class Harness {
        final BrowserFingerprintProfile profile = BrowserFingerprintProfile.brLinux();
        final Target page = mock(Target.class);
        final BaseTarget browser = mock(BaseTarget.class);
        final BaseTarget popupBrowser = mock(BaseTarget.class);
        final ChromeFingerprintSupport support;

        Harness() {
            profile.runtimePolicy = BrowserFingerprintProfile.RUNTIME_POLICY_MINIMAL_CDP;
            when(page.getId()).thenReturn("minimal-root");
            ready(page);
            ready(browser);
            ready(popupBrowser);
            support = new ChromeFingerprintSupport(
                    new ChromeOptions(), profile, new ObjectMapper(),
                    LoggerFactory.getLogger(ChromeMinimalCdpPopupInjectionTest.class),
                    () -> UserAgentResolver.buildForProfile(profile), ignored -> popupBrowser);
        }

        void install() {
            support.installWorkerSpoofAutoAttach(page, browser).join();
        }
    }

    private static void ready(Target target) {
        when(target.addCdpListener(any(), any()))
                .thenReturn(CompletableFuture.completedFuture(null));
        when(target.addCdpSessionListener(any(), any()))
                .thenReturn(CompletableFuture.completedFuture(null));
        when(target.executeCdpCmd(any(), any(), any(), any()))
                .thenReturn(CompletableFuture.completedFuture(NullNode.getInstance()));
    }

    private static void ready(BaseTarget target) {
        when(target.addCdpListener(any(), any()))
                .thenReturn(CompletableFuture.completedFuture(null));
        when(target.addCdpSessionListener(any(), any()))
                .thenReturn(CompletableFuture.completedFuture(null));
        when(target.executeCdpCmd(any(), any(), any(), any()))
                .thenReturn(CompletableFuture.completedFuture(NullNode.getInstance()));
        when(target.close()).thenReturn(CompletableFuture.completedFuture(null));
    }
}
