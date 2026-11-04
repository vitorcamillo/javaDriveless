package io.github.selenium.javaDriverless;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.selenium.javaDriverless.types.BaseTarget;
import io.github.selenium.javaDriverless.types.BrowserFingerprintProfile;
import io.github.selenium.javaDriverless.types.ChromeOptions;
import io.github.selenium.javaDriverless.types.Target;
import io.github.selenium.javaDriverless.utils.UserAgentResolver;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.slf4j.LoggerFactory;

import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.nullable;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ChromeWorkerPrivilegedTargetPolicyTest {

    @ParameterizedTest
    @ValueSource(strings = {
            "chrome-extension://extension-id/background.js",
            "chrome://settings/",
            "devtools://devtools/bundled/inspector.html"
    })
    void privilegedPausedTargetIsOnlyResumedAndNeverSpoofed(String url) {
        Fixture fixture = new Fixture();
        fixture.install();

        fixture.attach(url, true);

        verify(fixture.browser, times(1)).executeCdpCmd(
                eq("Runtime.runIfWaitingForDebugger"), anyMap(),
                nullable(Float.class), eq("privileged-session"));
        verify(fixture.browser, never()).executeCdpCmd(
                eq("Runtime.evaluate"), anyMap(),
                nullable(Float.class), eq("privileged-session"));
        verify(fixture.browser, never()).executeCdpCmd(
                eq("Debugger.enable"), anyMap(),
                nullable(Float.class), eq("privileged-session"));
        verify(fixture.browser, never()).executeCdpCmd(
                eq("Target.closeTarget"), anyMap(),
                nullable(Float.class), nullable(String.class));
        assertThat(fixture.support.hasRuntimeFailure()).isFalse();
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "https://example.test/service-worker.js",
            "http://example.test/service-worker.js",
            "blob:https://example.test/worker-id"
    })
    void webSchemesRemainOnTheSpoofPath(String url) {
        Fixture fixture = new Fixture();
        fixture.install();

        fixture.attach(url, false);

        verify(fixture.browser, times(1)).executeCdpCmd(
                eq("Runtime.evaluate"), anyMap(),
                nullable(Float.class), eq("privileged-session"));
        verify(fixture.browser, never()).executeCdpCmd(
                eq("Runtime.runIfWaitingForDebugger"), anyMap(),
                nullable(Float.class), eq("privileged-session"));
        assertThat(fixture.support.hasRuntimeFailure()).isFalse();
    }

    @Test
    void privilegedResumeFailureDoesNotPoisonTheWebFingerprintSession() {
        Fixture fixture = new Fixture();
        fixture.resumeFailure.set(new IllegalStateException("privileged resume unavailable"));
        fixture.install();

        fixture.attach("chrome-extension://extension-id/background.js", true);

        assertThat(fixture.support.hasRuntimeFailure()).isFalse();
        verify(fixture.browser, never()).executeCdpCmd(
                eq("Runtime.evaluate"), anyMap(),
                nullable(Float.class), eq("privileged-session"));
        verify(fixture.browser, never()).executeCdpCmd(
                eq("Target.closeTarget"), anyMap(),
                nullable(Float.class), nullable(String.class));
    }

    private static final class Fixture {
        private final ObjectMapper mapper = new ObjectMapper();
        private final Target page = mock(Target.class);
        private final BaseTarget browser = mock(BaseTarget.class);
        private final AtomicReference<Consumer<JsonNode>> browserAttached = new AtomicReference<>();
        private final AtomicReference<Throwable> resumeFailure = new AtomicReference<>();
        private final ChromeFingerprintSupport support = fingerprintSupport();

        private Fixture() {
            JsonNode success = mapper.createObjectNode();
            when(page.getId()).thenReturn("primary-page");
            when(browser.addCdpListener(anyString(), org.mockito.ArgumentMatchers.any()))
                    .thenAnswer(invocation -> {
                        if ("Target.attachedToTarget".equals(invocation.getArgument(0))) {
                            browserAttached.set(invocation.getArgument(1));
                        }
                        return CompletableFuture.completedFuture(null);
                    });
            when(page.addCdpListener(anyString(), org.mockito.ArgumentMatchers.any()))
                    .thenReturn(CompletableFuture.completedFuture(null));
            when(browser.addCdpSessionListener(eq("Debugger.paused"),
                    org.mockito.ArgumentMatchers.any()))
                    .thenReturn(CompletableFuture.completedFuture(null));
            when(page.addCdpSessionListener(eq("Debugger.paused"),
                    org.mockito.ArgumentMatchers.any()))
                    .thenReturn(CompletableFuture.completedFuture(null));
            when(page.executeCdpCmd(anyString(), nullable(Map.class), nullable(Float.class),
                    nullable(String.class)))
                    .thenReturn(CompletableFuture.completedFuture(success));
            when(browser.executeCdpCmd(anyString(), nullable(Map.class), nullable(Float.class),
                    nullable(String.class)))
                    .thenAnswer(invocation -> {
                        if ("Runtime.runIfWaitingForDebugger".equals(invocation.getArgument(0))
                                && resumeFailure.get() != null) {
                            return CompletableFuture.failedFuture(resumeFailure.get());
                        }
                        return CompletableFuture.completedFuture(success);
                    });
        }

        private void install() {
            support.installWorkerSpoofAutoAttach(page, browser).join();
            assertThat(browserAttached.get()).isNotNull();
        }

        private void attach(String url, boolean waitingForDebugger) {
            var event = mapper.createObjectNode();
            event.put("sessionId", "privileged-session");
            event.put("waitingForDebugger", waitingForDebugger);
            var targetInfo = event.putObject("targetInfo");
            targetInfo.put("type", "service_worker");
            targetInfo.put("targetId", "privileged-target");
            targetInfo.put("url", url);
            browserAttached.get().accept(event);
        }
    }

    private static ChromeFingerprintSupport fingerprintSupport() {
        ChromeOptions options = new ChromeOptions();
        BrowserFingerprintProfile profile = BrowserFingerprintProfile.brLinux();
        options.setFingerprintProfile(profile);
        return new ChromeFingerprintSupport(
                options,
                profile,
                new ObjectMapper(),
                LoggerFactory.getLogger(ChromeWorkerPrivilegedTargetPolicyTest.class),
                () -> UserAgentResolver.buildForProfile(profile),
                browser -> browser);
    }
}
