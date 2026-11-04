package io.github.selenium.javaDriverless;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.ArgumentMatchers.nullable;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.NullNode;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.slf4j.LoggerFactory;

import io.github.selenium.javaDriverless.types.BaseTarget;
import io.github.selenium.javaDriverless.types.BrowserFingerprintProfile;
import io.github.selenium.javaDriverless.types.Target;

class ChromeWorkerClientHintIsolationTest {

    @Test
    @SuppressWarnings("unchecked")
    void oopifInstallsSessionGuardBeforeResumeAndRewritesOnlyPresentHints() throws Exception {
        Fixture fixture = new Fixture();
        fixture.install();

        fixture.attachOnBrowser("iframe-session", "iframe-target", "iframe", true);

        InOrder order = inOrder(fixture.browser);
        ArgumentCaptor<BiConsumer<String, JsonNode>> pausedListener = ArgumentCaptor.forClass(BiConsumer.class);
        order.verify(fixture.browser).addCdpSessionListener(
                eq("Fetch.requestPaused"), pausedListener.capture());
        order.verify(fixture.browser).executeCdpCmd(
                eq("Fetch.enable"), anyMap(), isNull(), eq("iframe-session"));
        ArgumentCaptor<Map<String, Object>> uaOverride = ArgumentCaptor.forClass(Map.class);
        order.verify(fixture.browser).executeCdpCmd(
                eq("Network.setUserAgentOverride"), uaOverride.capture(), isNull(), eq("iframe-session"));
        assertThat(uaOverride.getValue()).containsEntry("acceptLanguage", "pt-BR,pt,en-US,en");
        order.verify(fixture.browser).executeCdpCmd(
                eq("Page.enable"), anyMap(), isNull(), eq("iframe-session"));
        order.verify(fixture.browser).executeCdpCmd(
                eq("Page.addScriptToEvaluateOnNewDocument"), anyMap(), isNull(), eq("iframe-session"));
        order.verify(fixture.browser).executeCdpCmd(
                eq("Runtime.runIfWaitingForDebugger"), anyMap(), isNull(), eq("iframe-session"));

        JsonNode request = fixture.mapper.readTree("""
                {
                  "requestId":"oopif-request",
                  "request":{
                    "headers":{
                      "Accept":"text/html",
                      "SEC-CH-DEVICE-MEMORY":"32",
                      "RTT":"999",
                      "X-Opaque-Sentinel":"must-not-be-logged"
                    }
                  }
                }
                """);
        pausedListener.getValue().accept("iframe-session", request);

        ArgumentCaptor<Map<String, Object>> continueArgs = ArgumentCaptor.forClass(Map.class);
        verify(fixture.browser).executeCdpCmd(
                eq("Fetch.continueRequest"), continueArgs.capture(), isNull(), eq("iframe-session"));
        assertThat(continueArgs.getValue()).containsEntry("requestId", "oopif-request");
        assertThat((List<Map<String, String>>) continueArgs.getValue().get("headers"))
                .containsExactlyInAnyOrder(
                        Map.of("name", "Accept", "value", "text/html"),
                        Map.of("name", "SEC-CH-DEVICE-MEMORY", "value", "8"),
                        Map.of("name", "RTT", "value", "50"),
                        Map.of("name", "X-Opaque-Sentinel", "value", "must-not-be-logged"))
                .noneMatch(header -> "Save-Data".equalsIgnoreCase(header.get("name")));
        assertThat(fixture.runtimeFailure.get()).isNull();
    }

    @Test
    void workersWithoutClientHintsDoNotEnableAnUnneededFetchDomain() {
        Fixture fixture = new Fixture();
        fixture.install();

        fixture.attachOnPage("worker-session", "worker-target", "worker", false);
        fixture.attachOnBrowser(
                "service-worker-session", "service-worker-target", "service_worker", false);

        verify(fixture.page, never()).addCdpSessionListener(eq("Fetch.requestPaused"), any());
        verify(fixture.browser, never()).addCdpSessionListener(eq("Fetch.requestPaused"), any());
        verify(fixture.page, never()).executeCdpCmd(
                eq("Fetch.enable"), anyMap(), nullable(Float.class), eq("worker-session"));
        verify(fixture.browser, never()).executeCdpCmd(
                eq("Fetch.enable"), anyMap(), nullable(Float.class), eq("service-worker-session"));
        assertThat(fixture.runtimeFailure.get()).isNull();
    }

    @Test
    void sharedWorkerUsesAnIndependentBrowserConnection() {
        Fixture fixture = new Fixture();
        fixture.install();

        fixture.attachOnShared(
                "shared-session", "shared-target", "shared_worker", false);

        verify(fixture.sharedBrowser).executeCdpCmd(
                eq("Target.setAutoAttach"), anyMap(), isNull(), isNull());
        verify(fixture.browser, never()).executeCdpCmd(
                eq("Target.setAutoAttach"), anyMap(), isNull(), isNull());
        verify(fixture.sharedBrowser).executeCdpCmd(
                eq("Runtime.evaluate"), anyMap(), isNull(), eq("shared-session"));
        assertThat(fixture.runtimeFailure.get()).isNull();
    }

    @Test
    void closingWorkerSupportClosesTheAuxiliaryBrowserConnection() {
        Fixture fixture = new Fixture();
        fixture.install();

        fixture.support.close().join();

        verify(fixture.sharedBrowser).close();
        verify(fixture.browser, never()).close();
    }

    @Test
    @SuppressWarnings("unchecked")
    void relatedPageAddsItsRootWithoutDuplicatingBrowserListeners() {
        Fixture fixture = new Fixture();
        fixture.install();
        Target related = mock(Target.class);
        when(related.getId()).thenReturn("related-page");
        when(related.addCdpListener(anyString(), any()))
                .thenReturn(CompletableFuture.completedFuture(null));
        when(related.addCdpSessionListener(anyString(), any()))
                .thenReturn(CompletableFuture.completedFuture(null));
        when(related.executeCdpCmd(anyString(), any(), nullable(Float.class), nullable(String.class)))
                .thenReturn(CompletableFuture.completedFuture(NullNode.getInstance()));

        fixture.support.installRelatedPage(related, fixture.browser).join();

        ArgumentCaptor<Map<String, Object>> roots = ArgumentCaptor.forClass(Map.class);
        verify(fixture.browser, times(2)).executeCdpCmd(
                eq("Target.autoAttachRelated"), roots.capture(), isNull(), isNull());
        assertThat(roots.getAllValues()).extracting(value -> value.get("targetId"))
                .containsExactly("primary-page", "related-page");
        verify(fixture.browser, times(1)).addCdpListener(
                eq("Target.attachedToTarget"), any());
        verify(fixture.sharedBrowser, times(1)).addCdpListener(
                eq("Target.attachedToTarget"), any());
        verify(fixture.sharedBrowser, times(1)).executeCdpCmd(
                eq("Target.setAutoAttach"), anyMap(), isNull(), isNull());
        verify(related).executeCdpCmd(
                eq("Target.setAutoAttach"), anyMap(), isNull(), isNull());
    }

    private static final class Fixture {
        private final ObjectMapper mapper = new ObjectMapper();
        private final Target page = mock(Target.class);
        private final BaseTarget browser = mock(BaseTarget.class);
        private final BaseTarget sharedBrowser = mock(BaseTarget.class);
        private final AtomicReference<Consumer<JsonNode>> pageAttached = new AtomicReference<>();
        private final AtomicReference<Consumer<JsonNode>> browserAttached = new AtomicReference<>();
        private final AtomicReference<Consumer<JsonNode>> sharedAttached = new AtomicReference<>();
        private final AtomicReference<Throwable> runtimeFailure = new AtomicReference<>();
        private ChromeWorkerSpoofSupport support;

        private Fixture() {
            when(page.getId()).thenReturn("primary-page");
            when(page.addCdpListener(anyString(), any())).thenAnswer(invocation -> {
                if ("Target.attachedToTarget".equals(invocation.getArgument(0))) {
                    pageAttached.set(invocation.getArgument(1));
                }
                return CompletableFuture.completedFuture(null);
            });
            when(browser.addCdpListener(anyString(), any())).thenAnswer(invocation -> {
                if ("Target.attachedToTarget".equals(invocation.getArgument(0))) {
                    browserAttached.set(invocation.getArgument(1));
                }
                return CompletableFuture.completedFuture(null);
            });
            when(sharedBrowser.addCdpListener(anyString(), any())).thenAnswer(invocation -> {
                if ("Target.attachedToTarget".equals(invocation.getArgument(0))) {
                    sharedAttached.set(invocation.getArgument(1));
                }
                return CompletableFuture.completedFuture(null);
            });
            when(page.addCdpSessionListener(anyString(), any()))
                    .thenReturn(CompletableFuture.completedFuture(null));
            when(browser.addCdpSessionListener(anyString(), any()))
                    .thenReturn(CompletableFuture.completedFuture(null));
            when(sharedBrowser.addCdpSessionListener(anyString(), any()))
                    .thenReturn(CompletableFuture.completedFuture(null));
            when(page.executeCdpCmd(anyString(), any(), nullable(Float.class), nullable(String.class)))
                    .thenReturn(CompletableFuture.completedFuture(NullNode.getInstance()));
            when(browser.executeCdpCmd(anyString(), any(), nullable(Float.class), nullable(String.class)))
                    .thenReturn(CompletableFuture.completedFuture(NullNode.getInstance()));
            when(sharedBrowser.executeCdpCmd(
                    anyString(), any(), nullable(Float.class), nullable(String.class)))
                    .thenReturn(CompletableFuture.completedFuture(NullNode.getInstance()));
            when(sharedBrowser.close()).thenReturn(CompletableFuture.completedFuture(null));
        }

        private void install() {
            BrowserFingerprintProfile profile = BrowserFingerprintProfile.brLinux();
            profile.deviceMemory = 8;
            profile.connection.mode = "spoof";
            profile.connection.rtt = 50;
            profile.connection.downlink = 10;
            profile.connection.effectiveType = "4g";
            support = new ChromeWorkerSpoofSupport(
                    profile,
                    mapper,
                    LoggerFactory.getLogger(ChromeWorkerClientHintIsolationTest.class),
                    ignored -> "void 0",
                    failure -> runtimeFailure.compareAndSet(null, failure),
                    ignored -> sharedBrowser);
            support.install(page, browser).join();
            assertThat(pageAttached.get()).isNotNull();
            assertThat(browserAttached.get()).isNotNull();
            assertThat(sharedAttached.get()).isNotNull();
        }

        private void attachOnPage(
                String sessionId, String targetId, String type, boolean waitingForDebugger) {
            pageAttached.get().accept(attached(sessionId, targetId, type, waitingForDebugger));
        }

        private void attachOnBrowser(
                String sessionId, String targetId, String type, boolean waitingForDebugger) {
            browserAttached.get().accept(attached(sessionId, targetId, type, waitingForDebugger));
        }

        private void attachOnShared(
                String sessionId, String targetId, String type, boolean waitingForDebugger) {
            sharedAttached.get().accept(attached(sessionId, targetId, type, waitingForDebugger));
        }

        private JsonNode attached(
                String sessionId, String targetId, String type, boolean waitingForDebugger) {
            var event = mapper.createObjectNode();
            event.put("sessionId", sessionId);
            event.put("waitingForDebugger", waitingForDebugger);
            var targetInfo = event.putObject("targetInfo");
            targetInfo.put("type", type);
            targetInfo.put("targetId", targetId);
            targetInfo.put("url", "https://oopif.example.invalid/");
            return event;
        }
    }
}
