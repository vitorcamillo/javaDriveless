package io.github.selenium.javaDriverless.types;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import com.fasterxml.jackson.databind.JsonNode;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import io.github.selenium.javaDriverless.cdp.FakeCdpServer;

/**
 * Target REAL (sem mock) contra servidor CDP falso: init, round-trip de comandos,
 * eventos e ciclo de fechamento — o caminho completo de transporte da biblioteca.
 */
class TargetTransportTest {

    private FakeCdpServer server;
    private Target target;

    @BeforeEach
    void setUp() throws InterruptedException {
        server = new FakeCdpServer();
        server.startAndAwaitReady();
        target = new Target("127.0.0.1:" + server.port(), "tid-teste", null, null,
            false, 5f, null, false, 10 * 1024 * 1024);
    }

    @AfterEach
    void tearDown() {
        try {
            if (target.getSocket() != null) {
                target.getSocket().close(); // não-bloqueante no teardown
            }
        } catch (Exception ignored) {
            // já fechado
        }
        try {
            server.stop(0);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    @Test
    void initConnectsRealSocketAndIsIdempotent() {
        Target first = target.init().join();
        Target second = target.init().join();

        assertSame(target, first);
        assertSame(target, second);
        assertNotNull(target.getSocket());
        assertTrue(target.getSocket().isConnected());
    }

    @Test
    void executeCdpCmdRoundTripsThroughTheRealSocket() {
        target.init().join();

        JsonNode result = target.executeCdpCmd("Test.echo", Map.of("chave", "valor"), 5f).join();

        assertEquals("Test.echo", result.path("method").asText());
        assertEquals("valor", result.path("params").path("chave").asText());
    }

    @Test
    void waitForCdpDeliversServerPushedEvents() throws Exception {
        target.init().join();

        CompletableFuture<JsonNode> waiting = target.waitForCdp("Test.event", 5f);
        target.executeCdpCmd("Test.fireEvent", null, 5f).join();

        JsonNode event = waiting.get(5, TimeUnit.SECONDS);
        assertEquals(true, event.path("fired").asBoolean());
    }

    @Test
    void cdpListenerRegisteredDuringInitReceivesEvents() throws Exception {
        target.init().join();

        // listeners de init (Page.javascriptDialogOpening etc.) rodam no socket real;
        // disparamos um evento e conferimos a entrega via waitForCdp
        CompletableFuture<JsonNode> waiting = target.waitForCdp("Test.sessionEvent", 5f);
        target.executeCdpCmd("Test.fireEvent", null, 5f).join();

        JsonNode event = waiting.get(5, TimeUnit.SECONDS);
        assertEquals(7, event.path("v").asInt());
    }

    @Test
    void targetMetadataQueriesUseTheRealTransport() {
        target.init().join();

        // Target.getTargetInfo passa pelo socket real e volta com o resultado do fake
        JsonNode info = target.executeCdpCmd("Target.getTargetInfo",
            Map.of("targetId", "tid-teste"), 5f).join();

        assertTrue(info.isObject());
    }

    @Test
    void closeClosesTheUnderlyingSocket() {
        target.init().join();

        target.close().join();

        assertTrue(!target.getSocket().isConnected());
    }

    @Test
    void lazyEntryPointsInitializeTheConnectionOnFirstUse() throws Exception {
        Target fresco = new Target("127.0.0.1:" + server.port(), "tid-lazy", null, null,
            false, 5f, null, false, 10 * 1024 * 1024);

        // primeira chamada com socket null: init() roda dentro da cadeia e o join
        // só completa com a conexão pronta (Target.init atribui o socket ANTES de
        // conectar; entry points seguintes no fast path exigem conexão estabelecida)
        JsonNode eco = fresco.executeCdpCmd("Test.echo", Map.of("k", "v"), 5f)
            .get(5, TimeUnit.SECONDS);
        assertEquals("v", eco.path("params").path("k").asText());

        CompletableFuture<JsonNode> espera = fresco.waitForCdp("Lazy.Event", 5f);
        fresco.addCdpListener("Lazy.Event", node -> { }).get(5, TimeUnit.SECONDS);
        fresco.addCdpSessionListener("Lazy.Session", (sessionId, node) -> { })
            .get(5, TimeUnit.SECONDS);
        JsonNode ecoSessao = fresco.executeCdpCmd("Test.echo", Map.of(), 5f, "sess-lazy")
            .get(5, TimeUnit.SECONDS);
        assertEquals("Test.echo", ecoSessao.path("method").asText());

        server.broadcast("{\"method\":\"Lazy.Event\",\"params\":{\"marca\":1}}");
        espera.get(5, TimeUnit.SECONDS);
        assertNotNull(fresco.getSocket());
    }

    @Test
    void facadeGettersAndHelpersReadThroughTheRealTransport() throws Exception {
        server.setCustomHandler((conn, req) -> {
            int id = req.get("id").asInt();
            String method = req.path("method").asText("");
            switch (method) {
                case "Target.getTargetInfo" -> conn.send("{\"id\":" + id
                    + ",\"result\":{\"targetInfo\":{\"targetId\":\"tid-teste\","
                    + "\"type\":\"page\",\"title\":\"Título\",\"url\":\"https://facade.test/\","
                    + "\"attached\":true,\"openerId\":\"op-1\",\"openerFrameId\":\"frame-9\","
                    + "\"canAccessOpener\":true,\"subtype\":\"prerender\"}}}");
                case "Page.getFrameTree" -> conn.send("{\"id\":" + id
                    + ",\"result\":{\"frameTree\":{\"frame\":{\"id\":\"F1\"}}}}");
                case "Browser.getWindowForTarget" -> conn.send("{\"id\":" + id
                    + ",\"result\":{\"windowId\":77}}");
                case "Page.captureScreenshot" -> conn.send("{\"id\":" + id
                    + ",\"result\":{\"data\":\"" + java.util.Base64.getEncoder()
                        .encodeToString(new byte[]{5, 6}) + "\"}}");
                case "Page.printToPDF" -> conn.send("{\"id\":" + id
                    + ",\"result\":{\"data\":\"" + java.util.Base64.getEncoder()
                        .encodeToString("%PDF-1.4".getBytes()) + "\"}}");
                case "Page.getNavigationHistory" -> conn.send("{\"id\":" + id
                    + ",\"result\":{\"currentIndex\":1,\"entries\":"
                    + "[{\"id\":1,\"url\":\"https://a.test/\"},{\"id\":2,\"url\":\"https://b.test/\"}]}}");
                case "DOM.getDocument" -> conn.send("{\"id\":" + id
                    + ",\"result\":{\"root\":{\"nodeId\":1}}}");
                case "DOM.performSearch" -> conn.send("{\"id\":" + id
                    + ",\"result\":{\"searchId\":\"s-1\",\"resultCount\":1}}");
                case "DOM.getSearchResults" -> conn.send("{\"id\":" + id
                    + ",\"result\":{\"nodeIds\":[7]}}");
                default -> conn.send("{\"id\":" + id + ",\"result\":{}}");
            }
        });
        target.init().join();

        // BUG LATENTE DE PRODUÇÃO documentado por este teste: getInfo devolve os valores
        // como JsonNode cru e os getters fazem cast direto — contra transporte real
        // (BooleanNode/TextNode) todos estes getters lançam ClassCastException.
        assertThrows(java.util.concurrent.ExecutionException.class,
            () -> target.getAttached().get(5, TimeUnit.SECONDS));
        assertThrows(java.util.concurrent.ExecutionException.class,
            () -> target.getOpenerId().get(5, TimeUnit.SECONDS));
        assertThrows(java.util.concurrent.ExecutionException.class,
            () -> target.getOpenerFrameId().get(5, TimeUnit.SECONDS));
        assertThrows(java.util.concurrent.ExecutionException.class,
            () -> target.getCanAccessOpener().get(5, TimeUnit.SECONDS));
        assertThrows(java.util.concurrent.ExecutionException.class,
            () -> target.getSubtype().get(5, TimeUnit.SECONDS));
        // quirks conhecido: getType() serializa o TextNode com aspas (ToString do JsonNode)
        assertEquals("\"page\"", target.getType().get(5, TimeUnit.SECONDS));
        assertEquals("Título", target.getTitleViaCDP().get(5, TimeUnit.SECONDS));
        assertEquals("https://facade.test/", target.getUrlViaCDP().get(5, TimeUnit.SECONDS));
        assertEquals(77, target.getWindowId().get(5, TimeUnit.SECONDS));
        assertEquals("F1", target.getBaseFrame().get(5, TimeUnit.SECONDS).get("id"));
        assertNotNull(target.getScreenshotAsPng().get(5, TimeUnit.SECONDS));
        assertEquals(java.util.Base64.getEncoder().encodeToString("%PDF-1.4".getBytes()),
            target.printPage().get(5, TimeUnit.SECONDS),
            "printPage devolve o payload base64 do PDF");
        assertEquals(1, target.getHistory().get(5, TimeUnit.SECONDS).get("currentIndex"));
        target.setNetworkConditions(true, 10, 20, 30, "wifi").get(5, TimeUnit.SECONDS);
        target.sleep(0.0).get(5, TimeUnit.SECONDS);

        // busca nativa: performSearch + getSearchResults + discard
        assertEquals(1, target.searchElements("//a").get(5, TimeUnit.SECONDS).size());

        // listener de sessão com socket já criado: completed sem CDP
        target.addCdpSessionListener("Qualquer.Evento", (sessionId, node) -> { })
            .get(5, TimeUnit.SECONDS);
    }
}
