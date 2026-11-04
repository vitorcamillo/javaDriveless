package io.github.selenium.javaDriverless.cdp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import com.fasterxml.jackson.databind.JsonNode;

import io.github.selenium.javaDriverless.cdp.exceptions.CDPException;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** Protocolo CDP sobre WebSocket real (servidor fake local): exec, eventos, timeouts, close. */
class CDPSocketTransportTest {

    private FakeCdpServer server;
    private CDPSocket socket;

    @BeforeEach
    void setUp() throws InterruptedException {
        server = new FakeCdpServer();
        server.startAndAwaitReady();
        socket = new CDPSocket(server.pageWsUrl(), 5f, 10 * 1024 * 1024);
    }

    @AfterEach
    void tearDown() {
        try {
            socket.close(); // não-bloqueante: closeBlocking pode deadlockar via runAsync
        } catch (Exception ignored) {
            // socket já fechado
        }
        try {
            server.stop(0);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    @Test
    void connectExecAndCloseLifecycle() {
        socket.connectAsync().join();
        assertTrue(socket.isConnected());

        JsonNode result = socket.exec("Test.echo", java.util.Map.of("k", "v"), null).join();
        assertEquals("Test.echo", result.path("method").asText());
        assertEquals("v", result.path("params").path("k").asText());

        socket.close();
        awaitDisconnected(socket);
        assertFalse(socket.isConnected());
    }

    @Test
    void dependentStageMayAwaitAnotherCommandWithoutBlockingReader() throws Exception {
        socket.connectAsync().join();
        server.setCustomHandler((conn, req) -> {
            String method = req.path("method").asText();
            if ("Test.slow".equals(method)) {
                try {
                    Thread.sleep(300);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                conn.send("{\"id\":" + req.get("id").asInt() + ",\"result\":{\"step\":1}}");
            } else if ("Test.second".equals(method)) {
                conn.send("{\"id\":" + req.get("id").asInt() + ",\"result\":{\"step\":2}}");
            } else {
                conn.send("{\"id\":" + req.get("id").asInt() + ",\"result\":{}}");
            }
        });

        CompletableFuture<Integer> chained = socket.exec("Test.slow", Map.of(), 5f)
                .thenApply(first -> socket.exec("Test.second", Map.of(), 5f).join().path("step").asInt());

        assertEquals(2, chained.get(3, TimeUnit.SECONDS));
    }

    private static void awaitDisconnected(CDPSocket socket) {
        long deadline = System.currentTimeMillis() + 5_000;
        while (socket.isConnected() && System.currentTimeMillis() < deadline) {
            try {
                Thread.sleep(20);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
        }
    }

    @Test
    void connectFailureSurfacesCdpException() {
        CDPSocket dead = new CDPSocket("ws://127.0.0.1:1/devtools/page/x", 0.5f, 1024);

        CompletableFuture<Void> future = dead.connectAsync();
        Exception thrown = assertThrows(Exception.class, () -> future.join());
        assertTrue(thrown.getCause() instanceof CDPException);
        assertTrue(thrown.getCause().getMessage().contains("conectar"));
    }

    @Test
    void serverErrorFailsTheCommandFuture() {
        socket.connectAsync().join();

        CompletableFuture<JsonNode> future = socket.exec("Test.error", null, null);
        Exception thrown = assertThrows(Exception.class, () -> future.join());
        // a mensagem do protocolo é redigida por categoria (CDPException.fromProtocolError);
        // o contrato é: falha tipada como CDPException, sem vazar o texto bruto do servidor
        assertNotNull(thrown.getCause());
        assertTrue(thrown.getCause() instanceof CDPException);
        assertTrue(!thrown.getCause().getMessage().contains("boom"),
            "mensagem bruta do servidor não pode vazar");
    }

    @Test
    void commandTimeoutExpiresWhenServerStaysSilent() {
        socket.connectAsync().join();

        long start = System.nanoTime();
        CompletableFuture<JsonNode> future = socket.exec("Test.noReply", null, 0.2f);
        assertThrows(Exception.class, () -> future.join());
        long elapsedMs = (System.nanoTime() - start) / 1_000_000;

        assertTrue(elapsedMs < 5_000, "timeout precisa vencer rápido (elapsed=" + elapsedMs + ")");
    }

    @Test
    void waitForCompletesWhenEventArrives() throws Exception {
        socket.connectAsync().join();

        CompletableFuture<JsonNode> waiting = socket.waitFor("Test.event", 5f);
        socket.exec("Test.fireEvent", null, null).join();

        JsonNode event = waiting.get(5, TimeUnit.SECONDS);
        assertEquals(true, event.path("fired").asBoolean());
    }

    @Test
    void listenersReceivePushedEvents() throws Exception {
        socket.connectAsync().join();

        AtomicReference<JsonNode> received = new AtomicReference<>();
        CompletableFuture<Void> got = new CompletableFuture<>();
        socket.addListener("Test.event", node -> {
            received.set(node);
            got.complete(null);
        });

        socket.exec("Test.fireEvent", null, null).join();
        got.get(5, TimeUnit.SECONDS);

        assertNotNull(received.get());
        assertEquals(1, received.get().path("seq").asInt());

        // remoção interrompe a entrega
        socket.removeListener("Test.event", node -> { });
    }

    @Test
    void methodIteratorQueuesPushedEvents() throws Exception {
        socket.connectAsync().join();

        java.util.concurrent.BlockingQueue<JsonNode> queue = socket.methodIterator("Test.event");
        socket.exec("Test.fireEvent", null, null).join();

        JsonNode event = queue.poll(5, TimeUnit.SECONDS);
        assertNotNull(event);
        assertEquals(true, event.path("fired").asBoolean());
    }

    @Test
    void methodIteratorQueueHasBoundedCapacity() {
        java.util.concurrent.BlockingQueue<JsonNode> queue = socket.methodIterator("X.y");
        JsonNode params = com.fasterxml.jackson.databind.node.JsonNodeFactory.instance.objectNode();

        for (int i = 0; i < CDPSocket.MAX_QUEUED_EVENTS + 10; i++) {
            socket.dispatchEvent("X.y", params, "");
        }

        assertEquals(CDPSocket.MAX_QUEUED_EVENTS, queue.size());
    }

    @Test
    void errorResponseWithoutCodeCompletesFutureAndReaderAcceptsNextCommand() {
        socket.connectAsync().join();
        server.setCustomHandler((conn, req) -> {
            int id = req.path("id").asInt();
            if ("Test.errorWithoutCode".equals(req.path("method").asText())) {
                conn.send("{\"id\":" + id + ",\"error\":{}}");
            } else {
                conn.send("{\"id\":" + id + ",\"result\":{\"ok\":true}}");
            }
        });

        CompletableFuture<JsonNode> failed = socket.exec("Test.errorWithoutCode", Map.of(), 5f);
        Exception thrown = assertThrows(Exception.class, failed::join);
        assertTrue(thrown.getCause() instanceof CDPException);

        JsonNode nextResult = socket.exec("Test.afterError", Map.of(), 5f).join();
        assertTrue(nextResult.path("ok").asBoolean());
    }

    @Test
    void sessionEventsCarrySessionIdForSessionListeners() throws Exception {
        socket.connectAsync().join();

        java.util.concurrent.atomic.AtomicReference<String> sessionSeen = new AtomicReference<>();
        java.util.concurrent.atomic.AtomicReference<JsonNode> params = new AtomicReference<>();
        CompletableFuture<Void> got = new CompletableFuture<>();
        socket.addSessionListener("Test.sessionEvent", (sessionId, node) -> {
            sessionSeen.set(sessionId);
            params.set(node);
            got.complete(null);
        });

        socket.exec("Test.fireEvent", null, null).join();
        got.get(5, TimeUnit.SECONDS);

        assertEquals("sess-1", sessionSeen.get());
        assertEquals(7, params.get().path("v").asInt());
    }

    @Test
    void closeCallbacksRunWhenConnectionDrops() throws Exception {
        socket.connectAsync().join();

        CompletableFuture<Void> closed = new CompletableFuture<>();
        socket.addOnClosedCallback(() -> closed.complete(null));

        socket.close(); // close assíncrono do cliente dispara onClose e os callbacks

        closed.get(5, TimeUnit.SECONDS);
        assertFalse(socket.isConnected());
    }

    @Test
    void connectAsyncWithoutTimeoutUsesTheBlockingVariantWithoutBudget() {
        socket.connectAsync(0f).join();
        assertTrue(socket.isConnected());
    }

    @Test
    void failingSessionListenerDoesNotBreakTheOtherListeners() throws Exception {
        socket.connectAsync().join();

        CompletableFuture<Void> sobrevivente = new CompletableFuture<>();
        socket.addSessionListener("Test.sessionEvent", (sessionId, node) -> {
            throw new IllegalStateException("listener explosivo");
        });
        socket.addSessionListener("Test.sessionEvent", (sessionId, node) -> sobrevivente.complete(null));

        socket.exec("Test.fireEvent", null, null).join();
        sobrevivente.get(5, TimeUnit.SECONDS);
    }

    @Test
    void serverDropCompletesPendingCommandsWithClosedConnectionError() throws Exception {
        socket.connectAsync().join();

        CompletableFuture<JsonNode> pendente = socket.exec("Test.noReply", null, 60f);
        server.stop(0); // derruba a conexão: pendente não pode ficar órfã

        Exception thrown = assertThrows(Exception.class, () -> pendente.get(5, TimeUnit.SECONDS));
        assertTrue(thrown.getCause() instanceof CDPException);
        assertTrue(thrown.getCause().getMessage().contains("fechada"));
    }

    @Test
    void explosiveCloseCallbackDoesNotPreventTheRemainingOnes() throws Exception {
        socket.connectAsync().join();

        CompletableFuture<Void> saudavel = new CompletableFuture<>();
        socket.addOnClosedCallback(() -> { throw new IllegalStateException("callback ruim"); });
        socket.addOnClosedCallback(() -> saudavel.complete(null));

        socket.close();
        saudavel.get(5, TimeUnit.SECONDS);
    }

    @Test
    void execWithoutConnectionFailsFastWithoutSending() {
        CDPSocket nuncaConectado = new CDPSocket(server.pageWsUrl(), 5f, 1024);

        CompletableFuture<JsonNode> futuro = nuncaConectado.exec("Test.echo", null, 1f);

        Exception thrown = assertThrows(Exception.class, () -> futuro.join());
        assertTrue(thrown.getCause().getMessage().contains("não conectado"));
    }

    @Test
    void execWithSessionIdFlattensTheSessionIntoTheFrame() throws Exception {
        socket.connectAsync().join();

        AtomicReference<String> frameSession = new AtomicReference<>();
        server.setCustomHandler((conn, req) -> {
            frameSession.set(req.path("sessionId").asText(""));
            conn.send("{\"id\":" + req.get("id").asInt()
                + ",\"result\":{\"session\":\"" + frameSession.get() + "\"}}");
        });

        JsonNode result = socket.exec("Runtime.evaluate", java.util.Map.of("expression", "1"),
            5f, "sess-42").join();

        assertEquals("sess-42", frameSession.get());
        assertEquals("sess-42", result.path("session").asText());
    }

    @Test
    void unserializableParamsFailTheCommandWithSerializationError() {
        socket.connectAsync().join();

        java.util.Map<String, Object> ciclico = new java.util.HashMap<>();
        ciclico.put("self", ciclico);

        CompletableFuture<JsonNode> futuro = socket.exec("Test.echo", ciclico, 5f);

        Exception thrown = assertThrows(Exception.class, () -> futuro.join());
        assertTrue(thrown.getCause() instanceof CDPException);
        assertTrue(thrown.getCause().getMessage().contains("serializar"));
    }

    @Test
    void removeSessionListenerStopsDeliveryAndToleratesUnknownMethods() throws Exception {
        socket.connectAsync().join();

        java.util.concurrent.atomic.AtomicInteger contador = new java.util.concurrent.atomic.AtomicInteger();
        java.util.function.BiConsumer<String, JsonNode> listener = (sessionId, node) -> contador.incrementAndGet();
        socket.addSessionListener("Test.sessionEvent", listener);
        socket.exec("Test.fireEvent", null, null).join();
        Thread.sleep(100);
        int depoisDoPrimeiro = contador.get();

        socket.removeSessionListener("Test.sessionEvent", listener);
        socket.removeSessionListener("Metodo.nuncaRegistrado", listener); // no-op sem listeners
        socket.exec("Test.fireEvent", null, null).join();
        Thread.sleep(100);

        assertEquals(depoisDoPrimeiro, contador.get(), "listener removido não recebe mais eventos");
    }

    @Test
    void closeAsyncSurfacesInterruptionAsCdpException() throws Exception {
        CDPSocket teimoso = new CDPSocket(server.pageWsUrl(), 5f, 1024) {
            @Override
            public void closeBlocking() throws InterruptedException {
                throw new InterruptedException("fechamento interrompido de propósito");
            }
        };

        CompletableFuture<Void> fechamento = teimoso.closeAsync();
        Exception thrown = assertThrows(Exception.class, fechamento::join);

        assertTrue(thrown.getCause() instanceof CDPException);
        assertTrue(thrown.getCause().getMessage().contains("fechar"));
        limpaInterrupcaoDoPoolComum();
    }

    /** O caminho de interrupção marca a thread do pool comum: limpa o flag para não vazar. */
    private static void limpaInterrupcaoDoPoolComum() {
        for (int rodada = 0; rodada < 20; rodada++) {
            boolean tudoLimpo = java.util.stream.IntStream.range(0,
                    Math.max(4, Runtime.getRuntime().availableProcessors()))
                .mapToObj(i -> CompletableFuture.supplyAsync(() -> {
                    if (Thread.currentThread().isInterrupted()) {
                        Thread.interrupted();
                        return false;
                    }
                    return true;
                }))
                .allMatch(CompletableFuture::join);
            if (tudoLimpo) {
                return;
            }
        }
    }
}
