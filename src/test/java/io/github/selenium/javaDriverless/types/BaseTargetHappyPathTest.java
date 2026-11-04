package io.github.selenium.javaDriverless.types;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import com.fasterxml.jackson.databind.JsonNode;
import com.sun.net.httpserver.HttpServer;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import io.github.selenium.javaDriverless.cdp.FakeCdpServer;

/**
 * Caminho feliz do BaseTarget contra Chrome falso: descoberta HTTP /json/version +
 * WebSocket CDP real em loopback — init, comandos (com e sem sessão), cache de
 * downloads, listeners e fechamento.
 */
class BaseTargetHappyPathTest {

    private FakeCdpServer server;
    private HttpServer discovery;
    private BaseTarget target;

    @BeforeEach
    void setUp() throws Exception {
        server = new FakeCdpServer();
        server.startAndAwaitReady();
        discovery = discoveryServer(server);
        target = new BaseTarget("127.0.0.1:" + discovery.getAddress().getPort(), false, 5f, 1 << 20);
    }

    @AfterEach
    void tearDown() {
        try {
            target.close().join();
        } catch (Exception ignored) {
        }
        discovery.stop(0);
        try {
            server.stop(0);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private static HttpServer discoveryServer(FakeCdpServer cdp) throws Exception {
        HttpServer discoveryServer = HttpServer.create(new java.net.InetSocketAddress("127.0.0.1", 0), 0);
        discoveryServer.createContext("/json/version", exchange -> {
            byte[] response = ("{\"webSocketDebuggerUrl\":\"ws://127.0.0.1:"
                + cdp.port() + "/devtools/browser/test\"}")
                .getBytes(java.nio.charset.StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, response.length);
            try (var output = exchange.getResponseBody()) {
                output.write(response);
            }
        });
        discoveryServer.start();
        return discoveryServer;
    }

    @Test
    void initConnectsThroughDiscoveryAndIsIdempotent() {
        assertSame(target, target.init().join());
        assertNotNull(target.getSocket());
        assertTrue(target.getSocket().isConnected());

        // segunda chamada reaproveita a conexão já estabelecida
        assertSame(target, target.init().join());
        assertEquals("BaseTarget", target.getId());
        assertEquals("BaseTarget", target.getType().join());
        assertTrue(target.toString().contains("BaseTarget"));

        // conexão independente é outro objeto, ainda não iniciada
        BaseTarget outra = target.newIndependentConnection();
        assertNotSame(target, outra);
        assertNull(outra.getSocket());
    }

    @Test
    void executeCdpCmdSendsAndReceivesThroughTheSocket() {
        target.init().join();

        JsonNode result = target.executeCdpCmd("Test.echo", java.util.Map.of("k", "v"), 5f).join();
        assertEquals("Test.echo", result.path("method").asText());
        assertEquals("v", result.path("params").path("k").asText());

        // timeout nulo cai no padrão (10s) e o comando ainda passa
        JsonNode semTimeout = target.executeCdpCmd("Test.echo", java.util.Map.of(), null).join();
        assertEquals("Test.echo", semTimeout.path("method").asText());
    }

    @Test
    void downloadBehaviorCommandIsCachedPerContext() {
        target.init().join();

        target.executeCdpCmd("Browser.setDownloadBehavior",
            java.util.Map.of("downloadPath", "/tmp/dl-ctx", "browserContextId", "ctx-1"), 5f).join();
        target.executeCdpCmd("Browser.setDownloadBehavior",
            java.util.Map.of("downloadPath", "/tmp/dl-padrao"), 5f).join();

        assertEquals("/tmp/dl-ctx", target.downloadsDirForContext("ctx-1"));
        assertEquals("/tmp/dl-padrao", target.downloadsDirForContext(null));
        assertNull(target.downloadsDirForContext("ctx-inexistente"));
    }

    @Test
    void executeCdpCmdRoutesFlattenCommandsToTheAttachedSession() {
        target.init().join();
        AtomicReference<String> frameSession = new AtomicReference<>();
        server.setCustomHandler((conn, req) -> {
            frameSession.set(req.path("sessionId").asText(""));
            conn.send("{\"id\":" + req.get("id").asInt()
                + ",\"result\":{\"session\":\"" + frameSession.get() + "\"}}");
        });

        JsonNode result = target.executeCdpCmd("Runtime.evaluate",
            java.util.Map.of("expression", "1"), 5f, "sess-anexada").join();

        assertEquals("sess-anexada", frameSession.get());
        assertEquals("sess-anexada", result.path("session").asText());
    }

    @Test
    void waitForCdpListenersAndEventIteratorsSeeTheSameStream() throws Exception {
        target.init().join();

        CompletableFuture<JsonNode> espera = target.waitForCdp("Test.event", 5f);
        CompletableFuture<Void> viaListener = new CompletableFuture<>();
        target.addCdpListener("Test.event", node -> viaListener.complete(null));
        BlockingQueue<JsonNode> fila = target.getCdpEventIter("Test.event").join();
        CompletableFuture<Void> viaSessao = new CompletableFuture<>();
        target.addCdpSessionListener("Test.sessionEvent", (sessionId, node) -> viaSessao.complete(null));

        target.executeCdpCmd("Test.fireEvent", null, 5f).join();

        assertEquals(true, espera.get(5, TimeUnit.SECONDS).path("fired").asBoolean());
        viaListener.get(5, TimeUnit.SECONDS);
        viaSessao.get(5, TimeUnit.SECONDS);
        assertEquals(true, fila.poll(5, TimeUnit.SECONDS).path("fired").asBoolean());

        target.removeCdpListener("Test.event", node -> { });
    }

    @Test
    void closeShutsTheSocketDownAndIsSafeWithoutConnection() {
        // sem init: close não pode explodir sem socket
        target.close().join();

        target.init().join();
        target.close().join();
        assertFalse(target.getSocket().isConnected());
    }

    @Test
    void downloadBehaviorWithoutPathIsIgnoredByTheCache() {
        target.init().join();

        target.executeCdpCmd("Browser.setDownloadBehavior", java.util.Map.of(), 5f).join();

        assertNull(target.downloadsDirForContext("DEFAULT"));
        // comando sem args ainda faz round-trip pelo socket
        assertTrue(target.executeCdpCmd("Test.echo", null, 5f).join().isObject());
    }
}
