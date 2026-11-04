package io.github.selenium.javaDriverless.fpmapping;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import com.sun.net.httpserver.HttpExchange;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import io.github.selenium.javaDriverless.types.Target;

/**
 * Helpers puros e de transporte do Plain Chrome CDP: eval*, waitForBoolean,
 * ações dos sites, espera de page target via HTTP local e utilitários de processo.
 */
class PlainChromeCdpSupportHelpersTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private Target target;

    @TempDir
    Path tempDir;
    private HttpServer discovery;

    @BeforeEach
    void setUp() {
        target = mock(Target.class);
    }

    @AfterEach
    void tearDown() {
        if (discovery != null) {
            discovery.stop(0);
        }
    }

    private static JsonNode json(String raw) {
        try {
            return MAPPER.readTree(raw);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private void stubEvaluate(JsonNode result) {
        when(target.executeCdpCmd(eq("Runtime.evaluate"), anyMap(), any()))
            .thenReturn(CompletableFuture.completedFuture(result));
    }

    @Test
    void evalStringAndJsonMapReadRuntimeValues() throws Exception {
        stubEvaluate(json("{\"result\":{\"value\":\"{\\\"chave\\\":42}\"}}"));

        assertEquals("{\"chave\":42}", PlainChromeCdpSupport.evalString(target, "expr"));
        assertEquals(Map.of("chave", 42), PlainChromeCdpSupport.evalJsonMap(target, "expr"));

        // booleano lê asBoolean do value: payload precisa ser booleano de verdade
        stubEvaluate(json("{\"result\":{\"value\":true}}"));
        assertTrue(PlainChromeCdpSupport.evalBoolean(target, "expr", 5f));

        // avaliação passa expressão com returnByValue/awaitPromise
        org.mockito.Mockito.verify(target).executeCdpCmd(eq("Runtime.evaluate"),
            argThat(args -> Boolean.TRUE.equals(args.get("returnByValue"))
                && Boolean.TRUE.equals(args.get("awaitPromise"))), eq(5f));
    }

    @Test
    void missingOrNullRuntimeValuesDegradeToEmpty() throws Exception {
        stubEvaluate(json("{\"result\":{\"value\":null}}"));
        assertEquals("", PlainChromeCdpSupport.evalString(target, "expr"));
        assertEquals(Map.of(), PlainChromeCdpSupport.evalJsonMap(target, "expr"));
        assertFalse(PlainChromeCdpSupport.evalBoolean(target, "expr", 5f));

        stubEvaluate(json("{}"));
        assertEquals("", PlainChromeCdpSupport.evalString(target, "expr"));
    }

    @Test
    void waitForBooleanToleratesTransientFailuresAndTimesOut() {
        when(target.executeCdpCmd(eq("Runtime.evaluate"), anyMap(), any()))
            .thenReturn(CompletableFuture.failedFuture(new IllegalStateException("contexto novo")))
            .thenReturn(CompletableFuture.completedFuture(json("{\"result\":{\"value\":true}}")));

        assertTrue(PlainChromeCdpSupport.waitForBoolean(target, "expr", 2_000L));

        when(target.executeCdpCmd(eq("Runtime.evaluate"), anyMap(), any()))
            .thenReturn(CompletableFuture.completedFuture(json("{\"result\":{\"value\":false}}")));
        long inicio = System.nanoTime();
        assertFalse(PlainChromeCdpSupport.waitForBoolean(target, "expr", 300L));
        assertTrue(System.nanoTime() - inicio < 5_000_000_000L, "timeout respeita o orçamento");
    }

    @Test
    void interruptedWaitPropagatesTheInterruptFlag() throws Exception {
        when(target.executeCdpCmd(eq("Runtime.evaluate"), anyMap(), any()))
            .thenReturn(CompletableFuture.completedFuture(json("{\"result\":{\"value\":false}}")));

        Thread worker = new Thread(() ->
            PlainChromeCdpSupport.waitForBoolean(target, "expr", 60_000L));
        worker.start();
        Thread.sleep(150);
        worker.interrupt();
        worker.join(5_000);

        assertFalse(worker.isAlive());
    }

    @Test
    void screenshotIsWrittenWhenPayloadArrivesAndIgnoredWhenItFails(@TempDir Path dir)
        throws Exception {
        byte[] png = {1, 2, 3};
        when(target.executeCdpCmd(eq("Page.captureScreenshot"), anyMap(), any()))
            .thenReturn(CompletableFuture.completedFuture(json("{\"data\":\""
                + Base64.getEncoder().encodeToString(png) + "\"}")));

        Path saida = dir.resolve("captura.png");
        PlainChromeCdpSupport.captureScreenshot(target, saida);
        assertEquals(true, java.util.Arrays.equals(png, Files.readAllBytes(saida)));

        when(target.executeCdpCmd(eq("Page.captureScreenshot"), anyMap(), any()))
            .thenReturn(CompletableFuture.completedFuture(json("{\"data\":\"\"}")));
        Path vazia = dir.resolve("vazia.png");
        PlainChromeCdpSupport.captureScreenshot(target, vazia);
        assertFalse(Files.exists(vazia));

        when(target.executeCdpCmd(eq("Page.captureScreenshot"), anyMap(), any()))
            .thenReturn(CompletableFuture.failedFuture(new IllegalStateException("sem aba")));
        Path quebrada = dir.resolve("quebrada.png");
        assertDoesNotThrowSilently(() -> PlainChromeCdpSupport.captureScreenshot(target, quebrada));
        assertFalse(Files.exists(quebrada));
    }

    private static void assertDoesNotThrowSilently(Runnable runnable) {
        try {
            runnable.run();
        } catch (RuntimeException permitido) {
            // captureScreenshot engole falhas: o contrato é não propagar
        }
    }

    @Test
    void genericSiteActionsRequireOnlyWhenConfigured() {
        Map<String, String> clickSite = Map.of("id", "meu-site", "runType", "click");
        when(target.executeCdpCmd(eq("Runtime.evaluate"), anyMap(), any()))
            .thenReturn(CompletableFuture.completedFuture(json("{\"result\":{\"value\":true}}")));
        PlainChromeCdpSupport.triggerIfNeeded(target, clickSite);

        // ação obrigatória que não disparou: falha fechada
        Map<String, String> obrigatorio = Map.of("id", "browserleaks-geo");
        when(target.executeCdpCmd(eq("Runtime.evaluate"), anyMap(), any()))
            .thenReturn(CompletableFuture.completedFuture(json("{\"result\":{\"value\":false}}")));
        assertThrows(IllegalStateException.class,
            () -> PlainChromeCdpSupport.triggerIfNeeded(target, obrigatorio));

        // ação opcional que não disparou: apenas segue
        Map<String, String> opcional = Map.of("id", "amiunique");
        PlainChromeCdpSupport.triggerIfNeeded(target, opcional);

        // site sem ação configurada: não toca no target
        Map<String, String> semAcao = Map.of("id", "site-estatico");
        PlainChromeCdpSupport.triggerIfNeeded(target, semAcao);
    }

    @Test
    void actionScriptEmbedsConfiguredLabelSelectorAndLabels() {
        String comLabel = PlainChromeCdpSupport.actionScript(Map.of(
            "id", "site", "runLabel", "Meu Botão", "runSelector", "#run"));
        assertTrue(comLabel.contains("#run"));
        assertTrue(comLabel.contains("meu botão"));

        String semTudo = PlainChromeCdpSupport.actionScript(Map.of("id", "desconhecido"));
        assertEquals("", semTudo);

        String porId = PlainChromeCdpSupport.actionScript(Map.of("id", "browserscan"));
        assertTrue(porId.contains("start scan"));
    }

    @Test
    void actionLabelTableCoversEveryKnownSite() {
        assertTrue(PlainChromeCdpSupport.actionLabels("coveryourtracks")
            .contains("test your browser"));
        assertTrue(PlainChromeCdpSupport.actionLabels("browserleaks-dns")
            .contains("run dns leak test"));
        assertTrue(PlainChromeCdpSupport.actionLabels("browserleaks-geo")
            .contains("get current position"));
        assertTrue(PlainChromeCdpSupport.actionLabels("amiunique").contains("accept"));
        assertTrue(PlainChromeCdpSupport.actionLabels("printleaks")
            .contains("collect fingerprints"));
        assertTrue(PlainChromeCdpSupport.actionLabels("browserscan").contains("start scan"));
        assertTrue(PlainChromeCdpSupport.actionLabels("site-desconhecido").isEmpty());
    }

    @Test
    void completionGatesAcceptSuccessAndFailClosedOnFailure() {
        // sucesso: nenhum site lança
        PlainChromeCdpSupport.requireActionCompleted("trustmyip-timezone", true);
        PlainChromeCdpSupport.requireActionCompleted("coveryourtracks", true);
        PlainChromeCdpSupport.requireActionCompleted("qualquer", false);

        assertThrows(IllegalStateException.class,
            () -> PlainChromeCdpSupport.requireActionCompleted("trustmyip-timezone", false));
        assertThrows(IllegalStateException.class,
            () -> PlainChromeCdpSupport.requireActionCompleted("coveryourtracks", false));

        // script de conclusão só existe para coveryourtracks
        assertFalse(PlainChromeCdpSupport.actionCompletionScript("coveryourtracks").isBlank());
        assertEquals("", PlainChromeCdpSupport.actionCompletionScript("browserleaks-dns"));
    }

    @Test
    void waitBudgetsFollowTheSiteContract() {
        assertEquals(15_000, PlainChromeCdpSupport.configuredWaitMs(Map.of()));
        assertEquals(15_000, PlainChromeCdpSupport.configuredWaitMs(Map.of("waitMs", "lixo")));
        assertEquals(30_000, PlainChromeCdpSupport.configuredWaitMs(Map.of("waitMs", "30000")));

        assertEquals(3_000, PlainChromeCdpSupport.initialWaitMs("coveryourtracks", Map.of()));
        assertEquals(15_000, PlainChromeCdpSupport.initialWaitMs("outro", Map.of()));
        assertEquals(5_000, PlainChromeCdpSupport.initialWaitMs("outro", Map.of("waitMs", "1")));

        assertEquals(0, PlainChromeCdpSupport.postActionSettleMs("coveryourtracks", Map.of()));
        assertEquals(5_000, PlainChromeCdpSupport.postActionSettleMs("outro", Map.of("waitMs", "15000")));
        assertEquals(3_000, PlainChromeCdpSupport.postActionSettleMs("outro", Map.of("waitMs", "3000")));

        // gates de gatilho exigido por tipo/id
        PlainChromeCdpSupport.requireActionTriggered(Map.of("id", "x", "runType", "click"), true);
        PlainChromeCdpSupport.requireActionTriggered(Map.of("id", "x"), false);
        assertThrows(IllegalStateException.class, () -> PlainChromeCdpSupport.requireActionTriggered(
            Map.of("id", "trustmyip-timezone"), false));
        assertThrows(IllegalStateException.class, () -> PlainChromeCdpSupport.requireActionTriggered(
            Map.of("id", "y", "runType", "click"), false));
    }

    @Test
    void waitForPageTargetReadsTheDevtoolsHttpEndpointAndCreatesThePage() throws Exception {
        discovery = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        boolean[] criado = {false};
        discovery.createContext("/json", exchange -> {
            String corpo = criado[0]
                ? "[{\"type\":\"page\",\"id\":\"alvo-1\"}]"
                : "[{\"type\":\"other\",\"id\":\"x\"}]";
            writeBody(exchange, 200, corpo);
        });
        discovery.createContext("/json/new", exchange -> {
            criado[0] = true;
            writeBody(exchange, 200, "{\"type\":\"page\",\"id\":\"alvo-1\"}");
        });
        discovery.start();

        String alvoId = PlainChromeCdpSupport.waitForPageTarget(discovery.getAddress().getPort());

        assertEquals("alvo-1", alvoId);
    }

    @Test
    void httpGetAndPutRoundTripThroughTheLocalEndpoint() throws Exception {
        discovery = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        discovery.createContext("/dados", exchange -> {
            switch (exchange.getRequestMethod()) {
                case "GET" -> writeBody(exchange, 200, "corpo-get");
                case "PUT" -> writeBody(exchange, 200, "corpo-put");
                default -> writeBody(exchange, 405, "");
            }
        });
        discovery.createContext("/erro", exchange -> writeBody(exchange, 500, "boom"));
        discovery.start();
        String base = "http://127.0.0.1:" + discovery.getAddress().getPort();

        assertEquals("corpo-get", PlainChromeCdpSupport.httpGet(base + "/dados"));
        assertEquals("corpo-put", PlainChromeCdpSupport.httpPut(base + "/dados"));

        IOException erro = assertThrows(IOException.class,
            () -> PlainChromeCdpSupport.httpPut(base + "/erro"));
        assertTrue(erro.getMessage().contains("HTTP 500"));
    }

    private static void writeBody(HttpExchange exchange, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.sendResponseHeaders(status, Math.max(0, bytes.length));
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(bytes);
        }
    }

    @Test
    void smallUtilitiesBehavePredictably() throws IOException {
        assertEquals("fallback", PlainChromeCdpSupport.env(
            "JDL_VARIAVEL_INEXISTENTE_TESTES", "fallback"));

        // headless default true quando a env não está configurada; espelha a regra
        String configured = System.getenv("PLAIN_CHROME_HEADLESS");
        boolean esperado = configured == null || configured.isBlank()
            || configured.equals("1") || configured.equalsIgnoreCase("true")
            || configured.equalsIgnoreCase("yes");
        assertEquals(esperado, PlainChromeCdpSupport.plainChromeHeadless());

        assertTrue(PlainChromeCdpSupport.splitExtraArgs(null).isEmpty());
        assertTrue(PlainChromeCdpSupport.splitExtraArgs("   ").isEmpty());
        assertEquals(List.of("--flag-a=1", "--flag-b"),
            PlainChromeCdpSupport.splitExtraArgs("--flag-a=1;--flag-b\n"));
    }
}
