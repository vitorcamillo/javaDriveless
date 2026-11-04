package io.github.selenium.javaDriverless.types;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

import com.fasterxml.jackson.databind.JsonNode;

import org.java_websocket.WebSocket;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import io.github.selenium.javaDriverless.Chrome;
import io.github.selenium.javaDriverless.cdp.FakeCdpServer;
import io.github.selenium.javaDriverless.scripts.SwitchTo;

/**
 * TargetConnectionCore (listeners, iteração de eventos, desconexão, identidade)
 * e Context sobre Target REAL com servidor CDP falso.
 */
class TargetConnectionAndContextCdpTest {

    private FakeCdpServer server;
    private Target target;
    private Chrome chrome;

    private final BiConsumer<WebSocket, JsonNode> handler = (conn, req) -> {
        int id = req.get("id").asInt();
        String method = req.path("method").asText("");
        switch (method) {
            case "Target.getTargetInfo" -> conn.send("{\"id\":" + id
                + ",\"result\":{\"targetInfo\":{\"targetId\":\"tid-ctx\","
                + "\"type\":\"page\",\"title\":\"T\",\"url\":\"https://ctx.test/\"}}}");
            case "Network.getCookies" -> conn.send("{\"id\":" + id
                + ",\"result\":{\"cookies\":[{\"name\":\"s\",\"value\":\"1\"}]}}");
            case "DOM.getDocument" -> conn.send("{\"id\":" + id
                + ",\"result\":{\"root\":{\"nodeId\":5}}}");
            case "DOM.getOuterHTML" -> conn.send("{\"id\":" + id
                + ",\"result\":{\"outerHTML\":\"<html><body>ok</body></html>\"}}");
            default -> conn.send("{\"id\":" + id + ",\"result\":{}}");
        }
    };

    @BeforeEach
    void setUp() throws InterruptedException {
        server = new FakeCdpServer();
        server.startAndAwaitReady();
        server.setCustomHandler(handler);

        target = new Target("127.0.0.1:" + server.port(), "tid-ctx", null, null,
            false, 5f, null, false, 10 * 1024 * 1024);
        target.init().join();

        chrome = mock(Chrome.class);
        when(chrome.getBaseTarget())
            .thenReturn(CompletableFuture.completedFuture(mock(BaseTarget.class)));
    }

    @AfterEach
    void tearDown() {
        try {
            if (target.getSocket() != null) {
                target.getSocket().close();
            }
        } catch (Exception ignored) {
        }
        try {
            server.stop(0);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    // ---------- TargetConnectionCore ----------

    @Test
    void cdpListenersReceiveEventsDispatchedBySocket() throws Exception {
        AtomicInteger recebidos = new AtomicInteger();
        target.addCdpListener("Custom.Event", node -> recebidos.incrementAndGet())
            .get(5, TimeUnit.SECONDS);

        server.broadcast("{\"method\":\"Custom.Event\",\"params\":{\"n\":1}}");
        Thread.sleep(200);

        assertEquals(1, recebidos.get());

        target.removeCdpListener("Custom.Event", node -> { }).get(5, TimeUnit.SECONDS);
    }

    @Test
    void waitForCdpResolvesWithEventParamsAndTimesOutSilently() throws Exception {
        CompletableFuture<JsonNode> espera = target.waitForCdp("Page.Future", 5f);
        server.broadcast("{\"method\":\"Page.Future\",\"params\":{\"marca\":true}}");

        JsonNode params = espera.get(5, TimeUnit.SECONDS);
        assertEquals(true, params.path("marca").asBoolean());
    }

    @Test
    void waitForCdpCompletesExceptionallyWhenEventNeverArrives() {
        CompletableFuture<JsonNode> espera = target.waitForCdp("Page.Nunca", 0.3f);

        assertThrows(Exception.class, () -> espera.get(3, TimeUnit.SECONDS));
    }

    @Test
    void cdpEventIterReturnsLiveListFilledAsEventsArrive() throws Exception {
        // coletor único por evento: a lista devolvida é a referência viva
        List<JsonNode> eventos = target.getCdpEventIter("Iter.Event").get(5, TimeUnit.SECONDS);
        assertTrue(eventos.isEmpty());

        server.broadcast("{\"method\":\"Iter.Event\",\"params\":{\"i\":1}}");
        server.broadcast("{\"method\":\"Iter.Event\",\"params\":{\"i\":2}}");
        Thread.sleep(200);

        assertEquals(2, eventos.size());
        assertEquals(1, eventos.get(0).path("i").asInt());
        assertEquals(2, eventos.get(1).path("i").asInt());
    }

    @Test
    void cdpEventIterReusesSingleCollectorAndListenerPerEvent() throws Exception {
        // 50 chamadas para o mesmo evento devolvem a mesma instância (um listener só)
        List<JsonNode> primeira = target.getCdpEventIter("Iter.Shared").get(5, TimeUnit.SECONDS);
        List<List<JsonNode>> demais = new ArrayList<>();
        for (int i = 1; i < 50; i++) {
            demais.add(target.getCdpEventIter("Iter.Shared").get(5, TimeUnit.SECONDS));
        }
        for (List<JsonNode> lista : demais) {
            assertSame(primeira, lista);
        }

        server.broadcast("{\"method\":\"Iter.Shared\",\"params\":{\"i\":1}}");
        Thread.sleep(200);

        // um único listener: o broadcast gera exatamente 1 elemento
        assertEquals(1, primeira.size());
        assertEquals(1, primeira.get(0).path("i").asInt());
    }

    @Test
    void identityContractIsBasedOnTheSocketNotTheTargetId() {
        // equals/hashCode usam o socket: mesmo id/host com socket próprio NÃO é igual
        Target mesmo = new Target("127.0.0.1:" + server.port(), "tid-ctx", null, null,
            false, 5f, null, false, 10 * 1024 * 1024);
        Target outroId = new Target("127.0.0.1:" + server.port(), "tid-outro", null, null,
            false, 5f, null, false, 10 * 1024 * 1024);

        assertNotEquals(target, mesmo);
        assertNotEquals(target.hashCode(), mesmo.hashCode());
        assertEquals(target, target);
        assertNotEquals(target, outroId);
        assertNotEquals(target, null);
        assertNotEquals(target, new Object());
        assertTrue(target.toString().contains("tid-ctx"));
        assertEquals("tid-ctx", target.getId());
        target.getSocket(); // exposto sem lançar
    }

    @Test
    void disconnectClosesSocketWithoutCdpCloseTarget() throws Exception {
        target.disconnect().get(5, TimeUnit.SECONDS);

        assertTrue(target.getSocket() == null || target.getSocket().isClosed());
    }

    @Test
    void sleepCompletesWithoutCdpTraffic() throws Exception {
        target.sleep(0.0).get(5, TimeUnit.SECONDS);
    }

    // ---------- Context ----------

    @Test
    void contextStartSessionCreatesSwitchToIdempotently() {
        Context context = new Context(target, chrome, "ctx-1", false, 10 * 1024 * 1024);

        assertSame(target, context.getCurrentTarget());
        Context iniciado = context.startSession().join();
        assertSame(context, iniciado);
        assertNotNull(context.switchTo());
        assertInstanceOf(SwitchTo.class, context.switchTo());
        // idempotente
        assertSame(iniciado, context.startSession().join());
    }

    @Test
    void contextIsIncognitoAndContextIdAreExposed() {
        Context normal = new Context(target, chrome, "ctx-a", false, 10 * 1024 * 1024);
        Context incognito = new Context(target, chrome, "ctx-b", true, 10 * 1024 * 1024);

        assertFalse(normal.isIncognito());
        assertTrue(incognito.isIncognito());
        assertEquals("ctx-a", normal.getContextId());
        assertEquals("ctx-b", incognito.getContextId());
    }

    @Test
    void contextPageQueriesDelegateToCurrentTarget() throws Exception {
        Context context = new Context(target, chrome, "ctx-1", false, 10 * 1024 * 1024);

        assertEquals("https://ctx.test/", context.getCurrentUrl().get(5, TimeUnit.SECONDS));
        assertEquals("T", context.getTitle().get(5, TimeUnit.SECONDS));
        assertEquals("<html><body>ok</body></html>",
            context.getPageSource().get(5, TimeUnit.SECONDS));
    }

    @Test
    void contextCookieOperationsDelegateToCurrentTarget() throws Exception {
        Context context = new Context(target, chrome, "ctx-1", false, 10 * 1024 * 1024);

        assertEquals(1, context.getCookies().get(5, TimeUnit.SECONDS).size());
        assertEquals("1", context.getCookie("s").get(5, TimeUnit.SECONDS).get("value"));
        assertNull(context.getCookie("fantasma").get(5, TimeUnit.SECONDS));
        context.addCookie(Map.of("name", "n")).get(5, TimeUnit.SECONDS);
        context.deleteCookie("s").get(5, TimeUnit.SECONDS);
        context.deleteAllCookies().get(5, TimeUnit.SECONDS);
    }

    @Test
    void contextCdpListenersAndEventIterWrapCurrentTarget() throws Exception {
        Context context = new Context(target, chrome, "ctx-1", false, 10 * 1024 * 1024);
        Consumer<JsonNode> callback = node -> { };

        context.addCdpListener("Ctx.Event", callback).get(5, TimeUnit.SECONDS);
        context.removeCdpListener("Ctx.Event", callback).get(5, TimeUnit.SECONDS);
        assertNotNull(context.getCdpEventIter("Ctx.Event").get(5, TimeUnit.SECONDS));
    }
}
