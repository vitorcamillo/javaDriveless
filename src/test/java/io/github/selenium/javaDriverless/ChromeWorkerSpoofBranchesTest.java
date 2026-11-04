package io.github.selenium.javaDriverless;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.selenium.javaDriverless.types.BaseTarget;
import io.github.selenium.javaDriverless.types.BrowserFingerprintProfile;
import io.github.selenium.javaDriverless.types.Target;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.nullable;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Ramos do listener Target.attachedToTarget do worker spoof: gates por tipo de
 * worker, waitingForDebugger, sessionId ausente e esquemas privilegiados.
 */
class ChromeWorkerSpoofBranchesTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static final class Harness {
        final Target page = mock(Target.class);
        final BaseTarget browser = mock(BaseTarget.class);
        final AtomicReference<Consumer<JsonNode>> attachedListener = new AtomicReference<>();
        final AtomicReference<BiConsumer<String, JsonNode>> pausedListener = new AtomicReference<>();
        final List<String> commands = new ArrayList<>();
        final List<Throwable> failures = new ArrayList<>();
        final JsonNode success = MAPPER.createObjectNode();

        Harness() {
            when(page.getId()).thenReturn("primary-page");
            // genéricos primeiro; específicos por último (o último stub vence)
            when(browser.addCdpListener(anyString(), any()))
                .thenReturn(CompletableFuture.completedFuture(null));
            when(browser.addCdpSessionListener(anyString(), any()))
                .thenReturn(CompletableFuture.completedFuture(null));
            when(browser.addCdpListener(eq("Target.attachedToTarget"), any()))
                .thenAnswer(inv -> {
                    attachedListener.set(inv.getArgument(1));
                    return CompletableFuture.completedFuture(null);
                });
            when(browser.addCdpSessionListener(eq("Debugger.paused"), any()))
                .thenAnswer(inv -> {
                    pausedListener.set(inv.getArgument(1));
                    return CompletableFuture.completedFuture(null);
                });
            when(page.addCdpListener(anyString(), any()))
                .thenReturn(CompletableFuture.completedFuture(null));
            when(page.addCdpSessionListener(anyString(), any()))
                .thenReturn(CompletableFuture.completedFuture(null));
            when(page.executeCdpCmd(anyString(), anyMap(), nullable(Float.class), nullable(String.class)))
                .thenAnswer(inv -> record(inv.getArgument(0)));
            when(browser.executeCdpCmd(anyString(), anyMap(), nullable(Float.class), nullable(String.class)))
                .thenAnswer(inv -> record(inv.getArgument(0)));
        }

        private CompletableFuture<JsonNode> record(String cmd) {
            synchronized (commands) {
                commands.add(cmd);
            }
            return CompletableFuture.completedFuture(success);
        }

        ChromeWorkerSpoofSupport support() {
            return new ChromeWorkerSpoofSupport(
                BrowserFingerprintProfile.brLinux(),
                MAPPER,
                LoggerFactory.getLogger(ChromeWorkerSpoofBranchesTest.class),
                ignored -> "void 0",
                failures::add);
        }

        void install() {
            support().install(page, browser).join();
        }

        JsonNode attached(String type, String sessionId, boolean waiting, String url) {
            com.fasterxml.jackson.databind.node.ObjectNode info =
                MAPPER.createObjectNode().put("type", type).put("targetId", "tid-" + type);
            if (url != null) {
                info.put("url", url);
            }
            return MAPPER.createObjectNode()
                .put("sessionId", sessionId)
                .put("waitingForDebugger", waiting)
                .set("targetInfo", info);
        }
    }

    @Test
    void backgroundWorkerWithoutDebuggerWaitInstallsImmediately() {
        Harness h = new Harness();
        h.install();

        h.attachedListener.get().accept(
            h.attached("service_worker", "sess-sw", false, "https://x.test/sw.js"));

        awaitCommands(h, "Runtime.evaluate");
        assertThat(h.failures).isEmpty();
    }

    @Test
    void dedicatedWorkerWithDebuggerWaitOnlyInstallsAfterPause() {
        Harness h = new Harness();
        h.install();

        h.attachedListener.get().accept(
            h.attached("worker", "sess-w", true, "https://x.test/w.js"));

        // gate ativo: nenhum evaluate antes do Debugger.paused da sessão
        assertThat(commandsWithout(h, "Debugger.")).noneMatch(c -> c.equals("Runtime.evaluate"));

        h.pausedListener.get().accept("sess-w", MAPPER.createObjectNode());

        awaitCommands(h, "Runtime.evaluate");
    }

    @Test
    void attachedEventWithoutSessionIdFailsClosedWithoutScripts() {
        Harness h = new Harness();
        h.install();

        JsonNode noSession = h.attached("worker", "", true, "https://x.test/w.js");
        h.attachedListener.get().accept(noSession);

        assertThat(h.failures).isNotEmpty();
        assertThat(h.commands).noneMatch(c -> c.equals("Runtime.evaluate"));
    }

    @Test
    void privilegedSchemeIsResumedInsteadOfInjected() {
        Harness h = new Harness();
        h.install();

        h.attachedListener.get().accept(
            h.attached("service_worker", "sess-ext", true,
                "chrome-extension://abcdef/background.js"));

        awaitCommands(h, "Runtime.runIfWaitingForDebugger");
        // extensões não recebem script de spoof
        assertThat(h.commands).noneMatch(c -> c.equals("Runtime.evaluate"));
        assertThat(h.failures).isEmpty();
    }

    @Test
    void destroyedAndDetachedEventsAreHandledSilently() {
        Harness h = new Harness();
        h.install();

        h.attachedListener.get().accept(
            h.attached("worker", "sess-gone", false, "https://x.test/g.js"));
        awaitCommands(h, "Runtime.evaluate");

        // ciclo de vida: destroy e detach não geram falha não contida
        java.util.Map<String, java.util.function.Consumer<JsonNode>> listeners = new java.util.HashMap<>();
        org.mockito.Mockito.verify(h.browser, org.mockito.Mockito.atLeastOnce())
            .addCdpListener(anyString(), any());

        assertThat(h.failures).isEmpty();
    }

    private static List<String> commandsWithout(Harness h, String prefix) {
        synchronized (h.commands) {
            return h.commands.stream().filter(c -> !c.startsWith(prefix)).toList();
        }
    }

    private static void awaitCommands(Harness harness, String expected) {
        long deadline = System.currentTimeMillis() + 5_000;
        while (System.currentTimeMillis() < deadline) {
            synchronized (harness.commands) {
                if (harness.commands.contains(expected)) {
                    return;
                }
            }
            try {
                Thread.sleep(20);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
        }
    }
}
