package io.github.selenium.javaDriverless.types;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BiConsumer;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import org.java_websocket.WebSocket;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import io.github.selenium.javaDriverless.cdp.FakeCdpServer;

/**
 * Target REAL contra servidor CDP falso: navegação nativa (Page.navigate/reload,
 * histórico), leitura de URL/título/fonte, cookies, impressão e fechamento.
 */
class TargetNavigationCdpTest {

    private static final com.fasterxml.jackson.databind.ObjectMapper MAPPER =
        new com.fasterxml.jackson.databind.ObjectMapper();

    private FakeCdpServer server;
    private Target target;

    private final AtomicReference<String> currentUrl =
        new AtomicReference<>("https://start.test/pagina");
    private final AtomicBoolean historyMoved = new AtomicBoolean(false);
    private boolean autoFireLoadEvent = true;

    private static ObjectNode json(String raw) {
        try {
            return (ObjectNode) MAPPER.readTree(raw);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private void send(WebSocket conn, ObjectNode message) {
        conn.send(message.toString());
    }

    private void fireLoadEvent(WebSocket conn) {
        conn.send("{\"method\":\"Page.loadEventFired\",\"params\":{}}");
    }

    /** handler padrão: cobre os métodos CDP usados pelas operações de navegação. */
    private final BiConsumer<WebSocket, JsonNode> defaultHandler = (conn, req) -> {
        int id = req.get("id").asInt();
        String method = req.path("method").asText("");

        switch (method) {
            case "Runtime.evaluate" -> send(conn, (ObjectNode) json(
                "{\"id\":" + id + ",\"result\":{\"result\":{\"objectId\":\"glob-1\"}}}"));
            case "Page.enable", "Emulation.setFocusEmulationEnabled",
                 "Input.dispatchKeyEvent", "Storage.setCookies",
                 "Network.deleteCookies", "Network.clearBrowserCookies" ->
                send(conn, (ObjectNode) json("{\"id\":" + id + ",\"result\":{}}"));
            case "Page.navigate" -> {
                String url = req.path("params").path("url").asText();
                if (!url.startsWith("#")) {
                    currentUrl.set(url);
                }
                send(conn, (ObjectNode) json(
                    "{\"id\":" + id + ",\"result\":{\"frameId\":\"f-1\"}}"));
                if (autoFireLoadEvent) {
                    CompletableFuture.delayedExecutor(50, TimeUnit.MILLISECONDS)
                        .execute(() -> fireLoadEvent(conn));
                }
            }
            case "Page.reload" -> {
                send(conn, (ObjectNode) json("{\"id\":" + id + ",\"result\":{}}"));
                CompletableFuture.delayedExecutor(50, TimeUnit.MILLISECONDS)
                    .execute(() -> fireLoadEvent(conn));
            }
            case "Page.getNavigationHistory" -> {
                int index = historyMoved.get() ? 0 : 1;
                String body = "{\"id\":" + id + ",\"result\":{\"currentIndex\":" + index
                    + ",\"entries\":["
                    + "{\"id\":10,\"url\":\"https://old.test/anterior\"},"
                    + "{\"id\":11,\"url\":\"" + currentUrl.get() + "\"}]}}";
                send(conn, (ObjectNode) json(body));
            }
            case "Page.navigateToHistoryEntry" -> {
                historyMoved.set(true);
                currentUrl.set("https://old.test/anterior");
                send(conn, (ObjectNode) json("{\"id\":" + id + ",\"result\":{}}"));
            }
            case "Target.getTargetInfo" -> {
                String body = "{\"id\":" + id + ",\"result\":{\"targetInfo\":{"
                    + "\"targetId\":\"tid-nav\",\"type\":\"page\","
                    + "\"title\":\"Página Atual\",\"url\":\"" + currentUrl.get() + "\"}}}";
                send(conn, (ObjectNode) json(body));
            }
            case "DOM.getDocument" -> send(conn, (ObjectNode) json(
                "{\"id\":" + id + ",\"result\":{\"root\":{\"nodeId\":5}}}"));
            case "DOM.getOuterHTML" -> send(conn, (ObjectNode) json(
                "{\"id\":" + id + ",\"result\":{\"outerHTML\":\"<html><body>ok</body></html>\"}}"));
            case "Network.getCookies" -> send(conn, (ObjectNode) json(
                "{\"id\":" + id + ",\"result\":{\"cookies\":["
                + "{\"name\":\"session\",\"value\":\"abc\",\"domain\":\".test\"}]}}"));
            case "Page.printToPDF" -> {
                String base64 = java.util.Base64.getEncoder()
                    .encodeToString("%PDF-fake".getBytes());
                send(conn, (ObjectNode) json(
                    "{\"id\":" + id + ",\"result\":{\"data\":\"" + base64 + "\"}}"));
            }
            case "Page.getFrameTree" -> send(conn, (ObjectNode) json(
                "{\"id\":" + id + ",\"result\":{\"frameTree\":{\"frame\":{\"id\":\"f-1\"}}}}"));
            case "Target.closeTarget" ->
                send(conn, (ObjectNode) json("{\"id\":" + id + ",\"result\":{}}"));
            default -> send(conn, (ObjectNode) json("{\"id\":" + id + ",\"result\":{}}"));
        }
    };

    @BeforeEach
    void setUp() throws InterruptedException {
        server = new FakeCdpServer();
        server.startAndAwaitReady();
        server.setCustomHandler(defaultHandler);

        target = new Target("127.0.0.1:" + server.port(), "tid-nav", null, null,
            false, 5f, null, false, 10 * 1024 * 1024);
        target.init().join();
    }

    @AfterEach
    void tearDown() {
        try {
            if (target.getSocket() != null) {
                target.getSocket().close();
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
    void getWithWaitLoadEnablesPageAndWaitsForLoadEvent() throws Exception {
        Map<String, Object> result = target.get("https://navegada.test/", true)
            .get(5, TimeUnit.SECONDS);

        assertEquals("https://navegada.test/", currentUrl.get());
        // o valor devolvido é o payload (params) do evento Page.loadEventFired aguardado
        assertTrue(result.isEmpty());
    }

    @Test
    void getOnAboutBlankSkipsWaitLoad() throws Exception {
        autoFireLoadEvent = false;

        target.get("about:blank", true).get(5, TimeUnit.SECONDS);

        assertEquals("about:blank", currentUrl.get());
    }

    @Test
    void getWithSameBaseFragmentAppendsFragmentWithoutWaitingLoad() throws Exception {
        autoFireLoadEvent = false; // ramo skipWait não deve exigir o evento

        target.get("https://start.test/pagina#secao", true).get(5, TimeUnit.SECONDS);

        // navegação com fragmento vai direto ao Page.navigate com a URL completa
        assertEquals("https://start.test/pagina#secao", currentUrl.get());
    }

    @Test
    void getWithBareFragmentResolvesAgainstCurrentUrl() throws Exception {
        autoFireLoadEvent = false;

        target.get("#ancora", true).get(5, TimeUnit.SECONDS);

        assertEquals("https://start.test/pagina#ancora", currentUrl.get());
    }

    @Test
    void getUrlTitleAndInfoComeFromTargetGetTargetInfo() throws Exception {
        assertEquals("https://start.test/pagina", target.getUrlViaCDP().get(5, TimeUnit.SECONDS));
        assertEquals("https://start.test/pagina", target.getCurrentUrl().get(5, TimeUnit.SECONDS));
        assertEquals("Página Atual", target.getTitleViaCDP().get(5, TimeUnit.SECONDS));
        assertEquals("Página Atual", target.getTitle().get(5, TimeUnit.SECONDS));

        Map<String, Object> info = target.getInfo().get(5, TimeUnit.SECONDS);
        // getInfo desembrulha targetInfo mantendo os valores como JsonNode
        assertEquals("page", ((JsonNode) info.get("type")).asText());
        assertEquals("Página Atual", ((JsonNode) info.get("title")).asText());
    }

    @Test
    void getPageSourceWalksDocumentThenOuterHtml() throws Exception {
        String source = target.getPageSource().get(5, TimeUnit.SECONDS);

        assertEquals("<html><body>ok</body></html>", source);
    }

    @Test
    void refreshReloadsAndWaitsForLoadEvent() throws Exception {
        target.refresh(true).get(5, TimeUnit.SECONDS);
        target.refresh(false).get(5, TimeUnit.SECONDS);
    }

    @Test
    void backNavigatesHistoryAndWaitsForActiveUrl() throws Exception {
        target.back().get(10, TimeUnit.SECONDS);

        assertEquals("https://old.test/anterior", currentUrl.get());
    }

    @Test
    void forwardAtEdgeOfHistoryIsBenignNoOp() throws Exception {
        // currentIndex=1 é o último: forward não tem entrada e não deve lançar
        target.forward().get(10, TimeUnit.SECONDS);

        assertEquals("https://start.test/pagina", currentUrl.get());
    }

    @Test
    void getHistoryExposesNavigationEntries() throws Exception {
        Map<String, Object> history = target.getHistory().get(5, TimeUnit.SECONDS);

        assertTrue(history.containsKey("currentIndex"));
        assertTrue(history.containsKey("entries"));
    }

    @Test
    void cookieLifecycleUsesNetworkAndStorageCommands() throws Exception {
        List<Map<String, Object>> cookies = target.getCookies().get(5, TimeUnit.SECONDS);
        assertEquals(1, cookies.size());
        assertEquals("session", cookies.get(0).get("name"));

        Map<String, Object> found = target.getCookie("session").get(5, TimeUnit.SECONDS);
        assertEquals("abc", found.get("value"));
        assertNull(target.getCookie("fantasma").get(5, TimeUnit.SECONDS));

        target.addCookie(Map.of("name", "novo", "value", "1")).get(5, TimeUnit.SECONDS);
        target.deleteCookie("session").get(5, TimeUnit.SECONDS);
        target.deleteAllCookies().get(5, TimeUnit.SECONDS);
    }

    @Test
    void printPageReturnsBase64DataFromPrintToPdf() throws Exception {
        String pdf = target.printPage().get(5, TimeUnit.SECONDS);

        // contrato atual: devolve o campo data cru (base64), sem decodificar
        assertEquals(java.util.Base64.getEncoder().encodeToString("%PDF-fake".getBytes()), pdf);
    }

    @Test
    void getFrameTreeReturnsFrameHierarchy() throws Exception {
        Map<String, Object> tree = target.getFrameTree().get(5, TimeUnit.SECONDS);

        // getFrameTree desembrulha o envelope: frameTree interno vira a raiz do mapa
        Object frame = tree.get("frame");
        org.junit.jupiter.api.Assertions.assertNotNull(frame);
    }

    @Test
    void unfocusAndTypingDispatchCdpCommands() throws Exception {
        target.unfocus().get(5, TimeUnit.SECONDS);
        target.sendKeys("texto").get(5, TimeUnit.SECONDS);
    }
}
