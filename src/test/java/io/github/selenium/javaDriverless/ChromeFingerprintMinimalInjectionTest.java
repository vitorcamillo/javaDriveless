package io.github.selenium.javaDriverless;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Map;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.NullNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.slf4j.LoggerFactory;

import io.github.selenium.javaDriverless.types.BaseTarget;
import io.github.selenium.javaDriverless.types.BrowserFingerprintProfile;
import io.github.selenium.javaDriverless.types.ChromeOptions;
import io.github.selenium.javaDriverless.types.Target;
import io.github.selenium.javaDriverless.utils.UserAgentResolver;

/**
 * Contrato da nativização das injeções de fingerprint (regra de ouro: provar o transporte CDP sem
 * subir Chrome). Prova que, no caminho de SUCESSO, o spoof é registrado SÓ via
 * Page.enable + Page.addScriptToEvaluateOnNewDocument (runImmediately) e NENHUM
 * Runtime.evaluate/executeScript imediato é emitido; falha no preparo ou no registro persistente
 * aborta em vez de fingir sucesso apenas no realm atual. A injeção pausada de iframe/popup usa um
 * único registro com runImmediately.
 */
class ChromeFingerprintMinimalInjectionTest extends ChromeFingerprintNativeInjectionSupport {
    @Test
    @SuppressWarnings("unchecked")
    void surfaceSuccessPathEmitsOnlyAddScriptAndNoImmediateEval() {
        Target target = mock(Target.class);
        when(target.executeCdpCmd(eq("Page.enable"), any(), isNull()))
            .thenReturn(CompletableFuture.completedFuture(NullNode.getInstance()));
        when(target.executeCdpCmd(eq("Page.addScriptToEvaluateOnNewDocument"), any(), isNull()))
            .thenReturn(CompletableFuture.completedFuture(NullNode.getInstance()));
        when(target.executeScript(any(), any(), anyBoolean()))
            .thenReturn(CompletableFuture.completedFuture(null));

        newSupport().injectSeleniumProxySurfaceScript(target).join();

        ArgumentCaptor<Map<String, Object>> args = ArgumentCaptor.forClass(Map.class);
        InOrder order = inOrder(target);
        order.verify(target).executeCdpCmd(eq("Page.enable"), any(), isNull());
        order.verify(target).executeCdpCmd(
                eq("Page.addScriptToEvaluateOnNewDocument"), args.capture(), isNull());
        assertEquals(Boolean.TRUE, args.getValue().get("runImmediately"),
            "surface deve ser registrado com runImmediately=true (cobre o realm existente)");
        assertNotNull(args.getValue().get("source"));
        assertFalse(((String) args.getValue().get("source")).isEmpty(), "source do surface não pode ser vazio");

        // Nenhuma injeção JS imediata no caminho de sucesso — o eval detectável foi removido.
        verify(target, never()).executeScript(any(), any(), anyBoolean());
        verify(target, never()).executeCdpCmd(eq("Runtime.evaluate"), any(), any());
    }

    @Test
    void minimalCdpPolicyDoesNotRegisterAnyPageJavascript() {
        BrowserFingerprintProfile profile = BrowserFingerprintProfile.brLinux();
        profile.runtimePolicy = BrowserFingerprintProfile.RUNTIME_POLICY_MINIMAL_CDP;
        ChromeFingerprintSupport support = new ChromeFingerprintSupport(
                new ChromeOptions(), profile, new ObjectMapper(),
                LoggerFactory.getLogger(ChromeFingerprintNativeInjectionTest.class),
                () -> UserAgentResolver.buildForProfile(profile), browser -> browser);
        Target target = mock(Target.class);
        when(target.executeCdpCmd(eq("Page.enable"), any(), isNull()))
                .thenReturn(CompletableFuture.completedFuture(NullNode.getInstance()));

        support.injectSeleniumProxySurfaceScript(target).join();
        support.injectAntiDetectionScript(target).join();

        verify(target, times(1)).executeCdpCmd(eq("Page.enable"), any(), isNull());
        verify(target, never()).executeCdpCmd(
                eq("Page.addScriptToEvaluateOnNewDocument"), any(), isNull());
        verify(target, never()).executeScript(any(), any(), anyBoolean());
    }

    @Test
    @SuppressWarnings("unchecked")
    void minimalCdpPolicyAppliesOnlyNativeOverridesToPausedOopif() {
        BrowserFingerprintProfile profile = BrowserFingerprintProfile.brLinux();
        profile.runtimePolicy = BrowserFingerprintProfile.RUNTIME_POLICY_MINIMAL_CDP;
        Target page = mock(Target.class);
        BaseTarget browser = mock(BaseTarget.class);
        BaseTarget popupBrowser = mock(BaseTarget.class);
        ChromeFingerprintSupport support = new ChromeFingerprintSupport(
                new ChromeOptions(), profile, new ObjectMapper(),
                LoggerFactory.getLogger(ChromeFingerprintNativeInjectionTest.class),
                () -> UserAgentResolver.buildForProfile(profile), ignored -> popupBrowser);
        when(page.getId()).thenReturn("minimal-root");
        when(browser.addCdpListener(any(), any()))
                .thenReturn(CompletableFuture.completedFuture(null));
        when(browser.addCdpSessionListener(any(), any()))
                .thenReturn(CompletableFuture.completedFuture(null));
        when(browser.executeCdpCmd(any(), any(), any(), any()))
                .thenReturn(CompletableFuture.completedFuture(NullNode.getInstance()));
        when(popupBrowser.addCdpListener(any(), any()))
                .thenReturn(CompletableFuture.completedFuture(null));
        when(popupBrowser.addCdpSessionListener(any(), any()))
                .thenReturn(CompletableFuture.completedFuture(null));
        when(popupBrowser.executeCdpCmd(any(), any(), any(), any()))
                .thenReturn(CompletableFuture.completedFuture(NullNode.getInstance()));
        when(page.addCdpListener(any(), any()))
                .thenReturn(CompletableFuture.completedFuture(null));
        when(page.addCdpSessionListener(any(), any()))
                .thenReturn(CompletableFuture.completedFuture(null));
        when(page.executeCdpCmd(any(), any(), any(), any()))
                .thenReturn(CompletableFuture.completedFuture(NullNode.getInstance()));

        support.installWorkerSpoofAutoAttach(page, browser).join();

        ArgumentCaptor<Map<String, Object>> autoAttach = ArgumentCaptor.forClass(Map.class);
        verify(browser).executeCdpCmd(
                eq("Target.autoAttachRelated"), autoAttach.capture(), isNull(), isNull());
        assertEquals("minimal-root", autoAttach.getValue().get("targetId"));
        assertEquals(Boolean.TRUE, autoAttach.getValue().get("waitForDebuggerOnStart"));
        assertEquals(List.of(
                        Map.of("type", "service_worker"),
                        Map.of("type", "iframe"),
                        Map.of("exclude", true)),
                autoAttach.getValue().get("filter"));
        ArgumentCaptor<Map<String, Object>> pageAutoAttach = ArgumentCaptor.forClass(Map.class);
        verify(page).executeCdpCmd(
                eq("Target.setAutoAttach"), pageAutoAttach.capture(), isNull(), isNull());
        assertEquals(List.of(
                        Map.of("type", "service_worker", "exclude", true),
                        Map.of("type", "shared_worker", "exclude", true),
                        Map.of("type", "iframe", "exclude", true),
                        Map.of()),
                pageAutoAttach.getValue().get("filter"));
        verify(popupBrowser).executeCdpCmd(
                eq("Target.setAutoAttach"), any(), isNull(), isNull());

        ArgumentCaptor<Consumer<JsonNode>> listener = ArgumentCaptor.forClass(Consumer.class);
        verify(browser).addCdpListener(eq("Target.attachedToTarget"), listener.capture());
        ObjectNode attached = new ObjectMapper().createObjectNode();
        attached.put("sessionId", "minimal-oopif-session");
        attached.put("waitingForDebugger", true);
        ObjectNode info = attached.putObject("targetInfo");
        info.put("type", "iframe");
        info.put("targetId", "minimal-oopif");
        listener.getValue().accept(attached);

        InOrder order = inOrder(browser);
        order.verify(browser).executeCdpCmd(
                eq("Target.autoAttachRelated"), any(), isNull(), isNull());
        order.verify(browser).executeCdpCmd(
                eq("Emulation.setTimezoneOverride"),
                eq(Map.of("timezoneId", "America/Sao_Paulo")),
                isNull(), eq("minimal-oopif-session"));
        order.verify(browser).executeCdpCmd(
                eq("Emulation.setEmulatedMedia"), any(), isNull(), eq("minimal-oopif-session"));
        order.verify(browser).executeCdpCmd(
                eq("Emulation.setGeolocationOverride"), any(), isNull(), eq("minimal-oopif-session"));
        order.verify(browser).executeCdpCmd(
                eq("Runtime.runIfWaitingForDebugger"), any(), isNull(), eq("minimal-oopif-session"));
        verify(browser, never()).executeCdpCmd(
                eq("Page.addScriptToEvaluateOnNewDocument"), any(), any(), any());
        verify(browser, never()).executeCdpCmd(
                eq("Runtime.evaluate"), any(), any(), any());
        verify(browser, never()).executeCdpCmd(
                eq("Network.setUserAgentOverride"), any(), any(), any());
        verify(browser, never()).executeCdpCmd(
                eq("Fetch.enable"), any(), any(), any());
        verify(browser, never()).addCdpSessionListener(
                eq("Fetch.requestPaused"), any());

        ArgumentCaptor<Consumer<JsonNode>> pageListener = ArgumentCaptor.forClass(Consumer.class);
        verify(page).addCdpListener(eq("Target.attachedToTarget"), pageListener.capture());
        ObjectNode worker = new ObjectMapper().createObjectNode();
        worker.put("sessionId", "minimal-worker-session");
        worker.put("waitingForDebugger", true);
        ObjectNode workerInfo = worker.putObject("targetInfo");
        workerInfo.put("type", "worker");
        workerInfo.put("targetId", "minimal-worker");
        pageListener.getValue().accept(worker);
        verify(page).executeCdpCmd(
                eq("Runtime.runIfWaitingForDebugger"), any(), isNull(), eq("minimal-worker-session"));
        verify(page, never()).executeCdpCmd(
                eq("Emulation.setTimezoneOverride"), any(), any(), any());
    }

    @Test
    @SuppressWarnings("unchecked")
    void minimalCdpPolicyTracksOopifsFromRelatedPages() {
        BrowserFingerprintProfile profile = BrowserFingerprintProfile.brLinux();
        profile.runtimePolicy = BrowserFingerprintProfile.RUNTIME_POLICY_MINIMAL_CDP;
        Target initial = mock(Target.class);
        Target related = mock(Target.class);
        BaseTarget browser = mock(BaseTarget.class);
        BaseTarget popupBrowser = mock(BaseTarget.class);
        ChromeFingerprintSupport support = new ChromeFingerprintSupport(
                new ChromeOptions(), profile, new ObjectMapper(),
                LoggerFactory.getLogger(ChromeFingerprintNativeInjectionTest.class),
                () -> UserAgentResolver.buildForProfile(profile), ignored -> popupBrowser);
        when(initial.getId()).thenReturn("minimal-root");
        when(related.getId()).thenReturn("minimal-popup");
        when(browser.addCdpListener(any(), any()))
                .thenReturn(CompletableFuture.completedFuture(null));
        when(browser.addCdpSessionListener(any(), any()))
                .thenReturn(CompletableFuture.completedFuture(null));
        when(browser.executeCdpCmd(any(), any(), any(), any()))
                .thenReturn(CompletableFuture.completedFuture(NullNode.getInstance()));
        when(popupBrowser.addCdpListener(any(), any()))
                .thenReturn(CompletableFuture.completedFuture(null));
        when(popupBrowser.addCdpSessionListener(any(), any()))
                .thenReturn(CompletableFuture.completedFuture(null));
        when(popupBrowser.executeCdpCmd(any(), any(), any(), any()))
                .thenReturn(CompletableFuture.completedFuture(NullNode.getInstance()));
        when(initial.addCdpListener(any(), any()))
                .thenReturn(CompletableFuture.completedFuture(null));
        when(initial.addCdpSessionListener(any(), any()))
                .thenReturn(CompletableFuture.completedFuture(null));
        when(initial.executeCdpCmd(any(), any(), any(), any()))
                .thenReturn(CompletableFuture.completedFuture(NullNode.getInstance()));
        when(related.addCdpListener(any(), any()))
                .thenReturn(CompletableFuture.completedFuture(null));
        when(related.addCdpSessionListener(any(), any()))
                .thenReturn(CompletableFuture.completedFuture(null));
        when(related.executeCdpCmd(any(), any(), any(), any()))
                .thenReturn(CompletableFuture.completedFuture(NullNode.getInstance()));

        support.installWorkerSpoofAutoAttach(initial, browser).join();
        support.installWorkerSpoofForRelatedPage(related, browser).join();

        ArgumentCaptor<Map<String, Object>> autoAttach = ArgumentCaptor.forClass(Map.class);
        verify(browser, times(2)).executeCdpCmd(
                eq("Target.autoAttachRelated"), autoAttach.capture(), isNull(), isNull());
        assertEquals(List.of("minimal-root", "minimal-popup"), autoAttach.getAllValues().stream()
                .map(arguments -> String.valueOf(arguments.get("targetId")))
                .toList());
        assertTrue(autoAttach.getAllValues().stream().allMatch(arguments -> List.of(
                Map.of("type", "service_worker"),
                Map.of("type", "iframe"), Map.of("exclude", true))
                .equals(arguments.get("filter"))));
        verify(related).executeCdpCmd(
                eq("Target.setAutoAttach"), any(), isNull(), isNull());
    }

    @Test
    void surfaceFailsClosedWhenPersistentRegistrationFails() {
        Target target = mock(Target.class);
        when(target.executeCdpCmd(eq("Page.enable"), any(), isNull()))
            .thenReturn(CompletableFuture.completedFuture(NullNode.getInstance()));
        when(target.executeCdpCmd(eq("Page.addScriptToEvaluateOnNewDocument"), any(), isNull()))
            .thenReturn(CompletableFuture.failedFuture(new RuntimeException("addScript indisponível")));
        when(target.executeScript(any(), any(), anyBoolean()))
            .thenReturn(CompletableFuture.completedFuture(null));

        org.assertj.core.api.Assertions.assertThatThrownBy(
                () -> newSupport().injectSeleniumProxySurfaceScript(target).join())
            .hasRootCauseMessage("addScript indisponível");

        verify(target, never()).executeScript(any(), any(), anyBoolean());
    }

    @Test
    void surfaceFailsClosedBeforeRegistrationWhenPageEnableFails() {
        Target target = mock(Target.class);
        when(target.executeCdpCmd(eq("Page.enable"), any(), isNull()))
            .thenReturn(CompletableFuture.failedFuture(new RuntimeException("Page domain indisponível")));

        org.assertj.core.api.Assertions.assertThatThrownBy(
                () -> newSupport().injectSeleniumProxySurfaceScript(target).join())
            .hasRootCauseMessage("Page domain indisponível");

        verify(target, never()).executeCdpCmd(
                eq("Page.addScriptToEvaluateOnNewDocument"), any(), isNull());
        verify(target, never()).executeScript(any(), any(), anyBoolean());
    }

    // ---------- anti-detection (dead code hoje, mas nativizado por paridade) ----------

    @Test
    void antiDetectionSuccessPathEmitsOnlyAddScriptAndNoImmediateEval() {
        Target target = mock(Target.class);
        when(target.executeCdpCmd(eq("Page.enable"), any(), isNull()))
            .thenReturn(CompletableFuture.completedFuture(NullNode.getInstance()));
        when(target.executeCdpCmd(eq("Page.addScriptToEvaluateOnNewDocument"), any(), isNull()))
            .thenReturn(CompletableFuture.completedFuture(NullNode.getInstance()));
        when(target.executeScript(any(), any(), anyBoolean()))
            .thenReturn(CompletableFuture.completedFuture(null));

        newSupport().injectAntiDetectionScript(target).join();

        InOrder order = inOrder(target);
        order.verify(target).executeCdpCmd(eq("Page.enable"), any(), isNull());
        order.verify(target).executeCdpCmd(
                eq("Page.addScriptToEvaluateOnNewDocument"), any(), isNull());
        verify(target, never()).executeScript(any(), any(), anyBoolean());
        verify(target, never()).executeCdpCmd(eq("Runtime.evaluate"), any(), any());
    }

    @Test
    void antiDetectionFallbackStillInjectsWhenAddScriptFails() {
        Target target = mock(Target.class);
        when(target.executeCdpCmd(eq("Page.enable"), any(), isNull()))
            .thenReturn(CompletableFuture.completedFuture(NullNode.getInstance()));
        when(target.executeCdpCmd(eq("Page.addScriptToEvaluateOnNewDocument"), any(), isNull()))
            .thenReturn(CompletableFuture.failedFuture(new RuntimeException("addScript indisponível")));
        when(target.executeScript(any(), any(), anyBoolean()))
            .thenReturn(CompletableFuture.completedFuture(null));

        newSupport().injectAntiDetectionScript(target).join();

        verify(target, times(1)).executeScript(any(), isNull(), eq(true));
    }

    @Test
    void antiDetectionFailsClosedWhenPersistentRegistrationAndFallbackFail() {
        Target target = mock(Target.class);
        when(target.executeCdpCmd(eq("Page.enable"), any(), isNull()))
            .thenReturn(CompletableFuture.completedFuture(NullNode.getInstance()));
        when(target.executeCdpCmd(eq("Page.addScriptToEvaluateOnNewDocument"), any(), isNull()))
            .thenReturn(CompletableFuture.failedFuture(new RuntimeException("registro indisponível")));
        when(target.executeScript(any(), any(), anyBoolean()))
            .thenReturn(CompletableFuture.failedFuture(new RuntimeException("fallback indisponível")));

        org.assertj.core.api.Assertions.assertThatThrownBy(
                () -> newSupport().injectAntiDetectionScript(target).join())
            .hasRootCauseMessage("fallback indisponível");

        verify(target, times(1)).executeScript(any(), isNull(), eq(true));
    }

    @Test
    void antiDetectionPageEnableFailureDoesNotFallbackToCurrentRealm() {
        Target target = mock(Target.class);
        when(target.executeCdpCmd(eq("Page.enable"), any(), isNull()))
            .thenReturn(CompletableFuture.failedFuture(new RuntimeException("Page domain indisponível")));

        org.assertj.core.api.Assertions.assertThatThrownBy(
                () -> newSupport().injectAntiDetectionScript(target).join())
            .hasRootCauseMessage("Page domain indisponível");

        verify(target, never()).executeCdpCmd(
                eq("Page.addScriptToEvaluateOnNewDocument"), any(), isNull());
        verify(target, never()).executeScript(any(), any(), anyBoolean());
    }

    // ---------- guarda: iframe/popup OOP pausado recebe um único addScript runImmediately ----------

    @Test
    @SuppressWarnings("unchecked")
    void pausedOopFrameRegistersSingleRunImmediatelyScriptBeforeResume() {
        Target page = mock(Target.class);
        BaseTarget browser = mock(BaseTarget.class);
        when(page.getId()).thenReturn("primary-page");

        // Ambos os escopos registram listener e chamam setAutoAttach — stub genérico (4-arg + listener).
        when(browser.addCdpListener(eq("Target.attachedToTarget"), any()))
            .thenReturn(CompletableFuture.completedFuture(null));
        when(page.addCdpListener(eq("Target.attachedToTarget"), any()))
            .thenReturn(CompletableFuture.completedFuture(null));
        when(browser.addCdpListener(eq("Target.targetDestroyed"), any()))
            .thenReturn(CompletableFuture.completedFuture(null));
        when(page.addCdpListener(eq("Target.targetDestroyed"), any()))
            .thenReturn(CompletableFuture.completedFuture(null));
        when(browser.addCdpListener(eq("Target.detachedFromTarget"), any()))
            .thenReturn(CompletableFuture.completedFuture(null));
        when(page.addCdpListener(eq("Target.detachedFromTarget"), any()))
            .thenReturn(CompletableFuture.completedFuture(null));
        when(browser.addCdpSessionListener(eq("Debugger.paused"), any()))
            .thenReturn(CompletableFuture.completedFuture(null));
        when(page.addCdpSessionListener(eq("Debugger.paused"), any()))
            .thenReturn(CompletableFuture.completedFuture(null));
        when(browser.addCdpSessionListener(eq("Fetch.requestPaused"), any()))
            .thenReturn(CompletableFuture.completedFuture(null));
        when(page.addCdpSessionListener(eq("Fetch.requestPaused"), any()))
            .thenReturn(CompletableFuture.completedFuture(null));
        when(browser.executeCdpCmd(any(), any(), any(), any()))
            .thenReturn(CompletableFuture.completedFuture(NullNode.getInstance()));
        when(page.executeCdpCmd(any(), any(), any(), any()))
            .thenReturn(CompletableFuture.completedFuture(NullNode.getInstance()));

        ArgumentCaptor<Consumer<JsonNode>> listener = ArgumentCaptor.forClass(Consumer.class);

        newSupport().installWorkerSpoofAutoAttach(page, browser).join();

        // setAutoAttach precisa pausar o alvo no início para o spoof entrar antes do JS do realm.
        ArgumentCaptor<Map<String, Object>> browserAutoAttach = ArgumentCaptor.forClass(Map.class);
        verify(browser).executeCdpCmd(eq("Target.autoAttachRelated"), browserAutoAttach.capture(), isNull(), isNull());
        assertEquals(Boolean.TRUE, browserAutoAttach.getValue().get("waitForDebuggerOnStart"),
            "injeção pausada deve manter waitForDebuggerOnStart=true");
        assertEquals("primary-page", browserAutoAttach.getValue().get("targetId"));
        assertEquals(java.util.List.of(
                        java.util.Map.of("type", "service_worker"),
                        java.util.Map.of("type", "iframe"),
                        java.util.Map.of("exclude", true)),
                browserAutoAttach.getValue().get("filter"),
                "browser related scope deve observar service workers e OOPIFs");
        ArgumentCaptor<Map<String, Object>> browserSharedAutoAttach = ArgumentCaptor.forClass(Map.class);
        verify(browser).executeCdpCmd(
                eq("Target.setAutoAttach"), browserSharedAutoAttach.capture(), isNull(), isNull());
        assertEquals(Boolean.TRUE, browserSharedAutoAttach.getValue().get("autoAttach"));
        assertEquals(Boolean.TRUE, browserSharedAutoAttach.getValue().get("waitForDebuggerOnStart"));
        assertEquals(Boolean.TRUE, browserSharedAutoAttach.getValue().get("flatten"));
        assertEquals(java.util.List.of(
                        java.util.Map.of("type", "shared_worker"),
                        java.util.Map.of("exclude", true)),
                browserSharedAutoAttach.getValue().get("filter"),
                "browser global scope deve observar somente shared workers");
        ArgumentCaptor<Map<String, Object>> pageAutoAttach = ArgumentCaptor.forClass(Map.class);
        verify(page).executeCdpCmd(eq("Target.setAutoAttach"), pageAutoAttach.capture(), isNull(), isNull());
        assertEquals(java.util.List.of(
                        java.util.Map.of("type", "service_worker", "exclude", true),
                        java.util.Map.of("type", "shared_worker", "exclude", true),
                        java.util.Map.of("type", "iframe", "exclude", true),
                        java.util.Map.of()),
                pageAutoAttach.getValue().get("filter"),
                "page scope não deve anexar novamente targets já pertencentes ao browser scope");

        // Simula um iframe OOP anexado (pausado).
        verify(browser, times(2)).addCdpListener(eq("Target.attachedToTarget"), listener.capture());
        ObjectMapper om = new ObjectMapper();
        ObjectNode params = om.createObjectNode();
        params.put("sessionId", "S1");
        params.put("waitingForDebugger", true);
        ObjectNode info = om.createObjectNode();
        info.put("type", "iframe");
        info.put("targetId", "T1");
        params.set("targetInfo", info);
        listener.getAllValues().get(0).accept(params);

        InOrder ord = inOrder(browser);
        ord.verify(browser).addCdpSessionListener(eq("Fetch.requestPaused"), any());
        ord.verify(browser).executeCdpCmd(
                eq("Fetch.enable"), any(), isNull(), eq("S1"));
        ArgumentCaptor<Map<String, Object>> uaOverride = ArgumentCaptor.forClass(Map.class);
        ord.verify(browser).executeCdpCmd(
                eq("Network.setUserAgentOverride"), uaOverride.capture(), isNull(), eq("S1"));
        assertEquals("Linux", ((Map<?, ?>) uaOverride.getValue().get("userAgentMetadata")).get("platform"));
        assertEquals("en-US,en", uaOverride.getValue().get("acceptLanguage"));
        ArgumentCaptor<Map<String, Object>> script = ArgumentCaptor.forClass(Map.class);
        ord.verify(browser).executeCdpCmd(
                eq("Page.addScriptToEvaluateOnNewDocument"), script.capture(), isNull(), eq("S1"));
        assertEquals(Boolean.TRUE, script.getValue().get("runImmediately"));
        assertFalse(((String) script.getValue().get("source")).contains("Symbol.for('a11y.layout"));
        verify(browser, never()).executeCdpCmd(eq("Runtime.evaluate"), any(), isNull(), eq("S1"));
        ord.verify(browser).executeCdpCmd(eq("Runtime.runIfWaitingForDebugger"), any(), isNull(), eq("S1"));
    }

}
