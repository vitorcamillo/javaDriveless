package io.github.selenium.javaDriverless;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

import io.github.selenium.javaDriverless.types.BrowserFingerprintProfile;

/**
 * Despacho observado do worker spoof sobre o CDPSession falso: sucesso, future nulo,
 * falha assíncrona, throw síncrono, exceptionDetails do Runtime.evaluate e gates de
 * lifecycle. Falhas vão para o sink como resumo seguro.
 */
class ChromeWorkerSpoofBaseDispatchTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private ChromeWorkerSpoofSupport novoSupport(List<Throwable> failures) {
        return new ChromeWorkerSpoofSupport(
            BrowserFingerprintProfile.brLinux(),
            MAPPER,
            LoggerFactory.getLogger(ChromeWorkerSpoofBaseDispatchTest.class),
            ignored -> "void 0",
            failures::add);
    }

    private static JsonNode sucesso() {
        return MAPPER.createObjectNode();
    }

    @Test
    void dispatchCompletesAndReturnsTheResult() {
        ChromeWorkerSpoofSupport support = novoSupport(new ArrayList<>());
        AtomicInteger chamadas = new AtomicInteger();
        ChromeWorkerSpoofSupportBase.SessionCdp cdp = (cmd, args, sessionId) -> {
            chamadas.incrementAndGet();
            assertEquals("sess-1", sessionId);
            return CompletableFuture.completedFuture(sucesso());
        };
        support.sessionTargets.put("sess-1", "tid-1");

        JsonNode resultado = support.dispatchObservedResult(
            cdp, "escopo", "worker", "tid-1", "sess-1", "Runtime.enable", Map.of()).join();

        assertNotNull(resultado);
        assertEquals(1, chamadas.get());
    }

    @Test
    void evaluateWithExceptionDetailsIsReportedAsFailure() {
        List<Throwable> failures = new ArrayList<>();
        ChromeWorkerSpoofSupport support = novoSupport(failures);
        support.sessionTargets.put("sess-1", "tid-1");
        com.fasterxml.jackson.databind.node.ObjectNode comErro = MAPPER.createObjectNode()
            .set("exceptionDetails", MAPPER.createObjectNode().put("text", "boom"));
        ChromeWorkerSpoofSupportBase.SessionCdp cdp =
            (cmd, args, sessionId) -> CompletableFuture.completedFuture(comErro);

        Exception thrown = assertThrows(Exception.class, () -> support.dispatchObservedResult(
            cdp, "escopo", "worker", "tid-1", "sess-1", "Runtime.evaluate", Map.of()).join());

        assertInstanceOf(IllegalStateException.class, thrown.getCause());
        assertFalse(failures.isEmpty(), "falha precisa chegar ao sink");
    }

    @Test
    void nullExecutorFutureIsReportedWithoutThrowing() {
        List<Throwable> failures = new ArrayList<>();
        ChromeWorkerSpoofSupport support = novoSupport(failures);
        support.sessionTargets.put("sess-1", "tid-1");
        ChromeWorkerSpoofSupportBase.SessionCdp cdpNulo =
            (cmd, args, sessionId) -> null;

        assertThrows(Exception.class, () -> support.dispatchObservedResult(
            cdpNulo, "escopo", "worker", "tid-1", "sess-1", "Runtime.enable", Map.of()).join());

        // o sink recebe a falha; o formato do resumo é interno ao reportFailure
        assertTrue(!failures.isEmpty(), "falha do future nulo precisa chegar ao sink");
    }

    @Test
    void asyncFailureAndSynchronousThrowAreBothReported() {
        List<Throwable> failures = new ArrayList<>();
        ChromeWorkerSpoofSupport support = novoSupport(failures);
        support.sessionTargets.put("sess-1", "tid-1");

        ChromeWorkerSpoofSupportBase.SessionCdp falha =
            (cmd, args, sessionId) -> CompletableFuture.failedFuture(
                new IllegalStateException("cdp caiu"));
        Exception assincrona = assertThrows(Exception.class, () -> support.dispatchObserved(
            falha, "escopo", "worker", "tid-1", "sess-1", "Runtime.enable", Map.of()).join());
        assertInstanceOf(IllegalStateException.class, assincrona.getCause());

        ChromeWorkerSpoofSupportBase.SessionCdp explosivo =
            (cmd, args, sessionId) -> { throw new IllegalStateException("exec lançou"); };
        assertThrows(Exception.class, () -> support.dispatchObserved(
            explosivo, "escopo", "worker", "tid-1", "sess-1", "Runtime.enable", Map.of()).join());

        assertEquals(2, failures.size());
    }

    @Test
    void lifecycleEndCancelsDispatchBeforeAndAfterExecution() {
        List<Throwable> failures = new ArrayList<>();
        ChromeWorkerSpoofSupport support = novoSupport(failures);
        ChromeWorkerSpoofSupportBase.SessionCdp cdp =
            (cmd, args, sessionId) -> CompletableFuture.completedFuture(sucesso());

        // sessão não rastreada == lifecycle encerrado: cancela antes de executar
        Exception cancelado = assertThrows(Exception.class, () -> support.dispatchObserved(
            cdp, "escopo", "worker", "tid-2", "sess-2", "Runtime.enable", Map.of()).join());
        assertTrue(cancelado.getCause().getMessage().contains("cancelado"));

        // thenDispatch encadeado também respeita o gate
        Exception encadeado = assertThrows(Exception.class, () -> support.thenDispatch(
            CompletableFuture.completedFuture(null), cdp, "escopo", "worker",
            "tid-2", "sess-2", "Runtime.enable", Map.of()).join());
        assertTrue(encadeado.getCause().getMessage().contains("cancelado"));

        // sessão ativa não é cancelada
        support.sessionTargets.put("sess-3", "tid-3");
        assertDoesNotThrowQuietly(() -> support.dispatchObserved(
            cdp, "escopo", "worker", "tid-3", "sess-3", "Runtime.enable", Map.of()).join());
    }

    private static void assertDoesNotThrowQuietly(Runnable runnable) {
        runnable.run();
    }

    @Test
    void privilegedResumeSkipsNonWaitingAndUnboundTargets() {
        ChromeWorkerSpoofSupport support = novoSupport(new ArrayList<>());
        AtomicInteger execs = new AtomicInteger();
        ChromeWorkerSpoofSupportBase.SessionCdp cdp = (cmd, args, sessionId) -> {
            execs.incrementAndGet();
            return CompletableFuture.completedFuture(sucesso());
        };

        // não está pausado para debugger: nada é feito
        support.resumePrivilegedTargetIfPaused(cdp, "escopo", "sess-x", "chrome", false);
        // pausado mas sem sessionId: nada é feito
        support.resumePrivilegedTargetIfPaused(cdp, "escopo", "", "chrome", true);
        assertEquals(0, execs.get());

        // pausado com sessão: resume é enviado
        support.resumePrivilegedTargetIfPaused(cdp, "escopo", "sess-x", "chrome", true);
        assertEquals(1, execs.get());
    }

    @Test
    void privilegedResumeSurvivesNullFuturesAndFailures() {
        ChromeWorkerSpoofSupport support = novoSupport(new ArrayList<>());
        ChromeWorkerSpoofSupportBase.SessionCdp nulo = (cmd, args, sessionId) -> null;
        ChromeWorkerSpoofSupportBase.SessionCdp falha = (cmd, args, sessionId) ->
            CompletableFuture.failedFuture(new IllegalStateException("resume falhou"));

        assertDoesNotThrowQuietly(() ->
            support.resumePrivilegedTargetIfPaused(nulo, "escopo", "sess-x", "chrome", true));
        assertDoesNotThrowQuietly(() ->
            support.resumePrivilegedTargetIfPaused(falha, "escopo", "sess-x", "chrome", true));
    }
}
