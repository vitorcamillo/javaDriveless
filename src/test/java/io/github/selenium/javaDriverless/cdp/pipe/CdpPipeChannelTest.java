package io.github.selenium.javaDriverless.cdp.pipe;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.selenium.javaDriverless.cdp.exceptions.CDPException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PipedInputStream;
import java.io.PipedOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CdpPipeChannelTest {
    private static final int PIPE_BUFFER = 1024 * 1024;
    private static final ObjectMapper JSON = new ObjectMapper();
    private PipedInputStream chromeReads;
    private PipedOutputStream commands;
    private PipedInputStream responses;
    private PipedOutputStream chromeWrites;
    private FakeChrome chrome;
    private CdpPipeChannel channel;

    @BeforeEach
    void setUp() throws Exception {
        chromeReads = new PipedInputStream(PIPE_BUFFER);
        commands = new PipedOutputStream(chromeReads);
        responses = new PipedInputStream(PIPE_BUFFER);
        chromeWrites = new PipedOutputStream(responses);
        chrome = new FakeChrome(chromeReads, chromeWrites);
        channel = new CdpPipeChannel(responses, commands, 5f, 1024 * 1024);
        channel.start();
        chrome.start();
    }

    @Test
    void tcpHostStartingWithPipeIsNotTreatedAsPipeChannel() {
        assertThat(CdpPipeRegistry.isPipeHost("pipe-browser:9222")).isFalse();
        assertThat(CdpPipeRegistry.isPipeHost(channel.getHost())).isTrue();
    }

    @AfterEach
    void tearDown() throws Exception {
        channel.close();
        chrome.close();
    }

    @Test
    void execRootSendsNulTerminatedJsonWithoutSessionAndCompletes() throws Exception {
        CompletableFuture<JsonNode> result = channel.rootView().exec("Test.root", Map.of("x", 1), 5f);
        JsonNode command = chrome.nextCommand();
        assertThat(command.path("method").asText()).isEqualTo("Test.root");
        assertThat(command.has("sessionId")).isFalse();
        chrome.respond(command.path("id").asLong(), "{\"ok\":true}");
        assertThat(result.get(5, TimeUnit.SECONDS).path("ok").asBoolean()).isTrue();
    }

    @Test
    void protocolErrorFailsFutureAsCdpException() throws Exception {
        CompletableFuture<JsonNode> result = channel.rootView().exec("Test.error", Map.of(), 5f);
        JsonNode command = chrome.nextCommand();
        chrome.write("{\"id\":" + command.path("id").asLong()
                + ",\"error\":{\"code\":-32000,\"message\":\"x\"}}");
        assertThatThrownBy(() -> result.get(5, TimeUnit.SECONDS))
                .isInstanceOf(ExecutionException.class)
                .hasCauseInstanceOf(CDPException.class);
    }

    @Test
    void attachAndSessionExecUseFlattenedSessionId() throws Exception {
        CompletableFuture<PipeSessionSocket> attached = channel.attachToTarget("T1", 5f);
        JsonNode attach = chrome.nextCommand();
        assertThat(attach.path("method").asText()).isEqualTo("Target.attachToTarget");
        assertThat(attach.path("params").path("targetId").asText()).isEqualTo("T1");
        assertThat(attach.path("params").path("flatten").asBoolean()).isTrue();
        chrome.respond(attach.path("id").asLong(), "{\"sessionId\":\"S1\"}");
        PipeSessionSocket session = attached.get(5, TimeUnit.SECONDS);
        assertThat(session.getSessionId()).isEqualTo("S1");
        CompletableFuture<JsonNode> result = session.exec("Runtime.enable", Map.of(), 5f);
        JsonNode command = chrome.nextCommand();
        assertThat(command.path("sessionId").asText()).isEqualTo("S1");
        chrome.respond(command.path("id").asLong(), "{}");
        result.get(5, TimeUnit.SECONDS);
    }

    @Test
    void eventsRouteToTheirSessionOrRootOnly() throws Exception {
        PipeSessionSocket session = channel.createSessionView("S1");
        CompletableFuture<JsonNode> sessionEvent = new CompletableFuture<>();
        CompletableFuture<JsonNode> rootEvent = new CompletableFuture<>();
        CompletableFuture<JsonNode> rootOnly = new CompletableFuture<>();
        session.addListener("Page.loadEventFired", sessionEvent::complete);
        channel.rootView().addListener("Page.loadEventFired", rootEvent::complete);
        channel.rootView().addListener("Test.rootEvent", rootOnly::complete);
        chrome.write("{\"method\":\"Page.loadEventFired\",\"sessionId\":\"S1\",\"params\":{}}");
        assertThat(sessionEvent.get(5, TimeUnit.SECONDS)).isNotNull();
        assertThat(rootEvent).isNotCompleted();
        chrome.write("{\"method\":\"Test.rootEvent\",\"params\":{}}");
        assertThat(rootOnly.get(5, TimeUnit.SECONDS)).isNotNull();
    }

    @Test
    void childSessionEventsReachOwnerSessionListenerWithChildId() throws Exception {
        PipeSessionSocket session = channel.createSessionView("S1");
        AtomicReference<String> receivedId = new AtomicReference<>();
        CompletableFuture<Void> received = new CompletableFuture<>();
        session.addSessionListener("Runtime.consoleAPICalled", (id, params) -> {
            receivedId.set(id);
            received.complete(null);
        });
        chrome.write("{\"method\":\"Target.attachedToTarget\",\"sessionId\":\"S1\","
                + "\"params\":{\"sessionId\":\"C1\"}}");
        chrome.write("{\"method\":\"Runtime.consoleAPICalled\",\"sessionId\":\"C1\",\"params\":{}}");
        received.get(5, TimeUnit.SECONDS);
        assertThat(receivedId.get()).isEqualTo("C1");
    }

    @Test
    void detachClosesViewRunsCallbackAndFailsItsPendingCommand() throws Exception {
        PipeSessionSocket session = channel.createSessionView("S1");
        CompletableFuture<Void> closed = new CompletableFuture<>();
        session.addOnClosedCallback(() -> closed.complete(null));
        CompletableFuture<JsonNode> pending = session.exec("Runtime.never", Map.of(), 5f);
        chrome.nextCommand();
        chrome.write("{\"method\":\"Target.detachedFromTarget\",\"params\":{\"sessionId\":\"S1\"}}");
        closed.get(5, TimeUnit.SECONDS);
        assertThat(session.isConnected()).isFalse();
        assertThatThrownBy(() -> pending.get(5, TimeUnit.SECONDS))
                .isInstanceOf(ExecutionException.class)
                .hasCauseInstanceOf(CDPException.class)
                .hasMessageContaining("Sessão CDP pipe fechada");
    }

    @Test
    void eofFailsPendingCommandsAndUnregistersChannel() throws Exception {
        String host = channel.getHost();
        assertThat(CdpPipeRegistry.lookup(host)).contains(channel);
        CompletableFuture<JsonNode> pending = channel.rootView().exec("Test.pending", Map.of(), 5f);
        chrome.nextCommand();
        chrome.closeWriter();
        assertThatThrownBy(() -> pending.get(5, TimeUnit.SECONDS))
                .isInstanceOf(ExecutionException.class)
                .hasCauseInstanceOf(CDPException.class);
        awaitTrue(() -> !channel.isOpen(), "canal fechado");
        awaitTrue(() -> CdpPipeRegistry.lookup(host).isEmpty(), "canal removido do registro");
    }

    @Test
    void oversizedFrameClosesChannel() throws Exception {
        channel.close();
        chrome.close();
        chromeReads = new PipedInputStream(PIPE_BUFFER);
        commands = new PipedOutputStream(chromeReads);
        responses = new PipedInputStream(PIPE_BUFFER);
        chromeWrites = new PipedOutputStream(responses);
        chrome = new FakeChrome(chromeReads, chromeWrites);
        channel = new CdpPipeChannel(responses, commands, 5f, 64);
        channel.start();
        chrome.start();
        CompletableFuture<JsonNode> pending = channel.rootView().exec("Test.pending", Map.of(), 5f);
        chrome.nextCommand();
        chrome.write("x".repeat(65));
        assertThatThrownBy(() -> pending.get(5, TimeUnit.SECONDS))
                .isInstanceOf(ExecutionException.class)
                .hasCauseInstanceOf(CDPException.class);
        assertThat(channel.isOpen()).isFalse();
    }

    @Test
    void invalidJsonDoesNotPreventFollowingValidResponse() throws Exception {
        CompletableFuture<JsonNode> result = channel.rootView().exec("Test.afterInvalid", Map.of(), 5f);
        JsonNode command = chrome.nextCommand();
        chrome.write("{invalid json");
        chrome.respond(command.path("id").asLong(), "{\"valid\":true}");
        assertThat(result.get(5, TimeUnit.SECONDS).path("valid").asBoolean()).isTrue();
        assertThat(channel.isOpen()).isTrue();
    }

    @Test
    void blockedSessionListenerDoesNotBlockRootResponse() throws Exception {
        PipeSessionSocket session = channel.createSessionView("S1");
        CompletableFuture<JsonNode> rootResult = channel.rootView().exec("Test.root", Map.of(), 5f);
        JsonNode command = chrome.nextCommand();
        CompletableFuture<Void> listenerFinished = new CompletableFuture<>();
        session.addListener("Test.block", event -> {
            try {
                rootResult.join();
                listenerFinished.complete(null);
            } catch (Throwable t) {
                listenerFinished.completeExceptionally(t);
            }
        });
        chrome.write("{\"method\":\"Test.block\",\"sessionId\":\"S1\",\"params\":{}}");
        chrome.respond(command.path("id").asLong(), "{\"unblocked\":true}");
        assertThat(rootResult.get(5, TimeUnit.SECONDS).path("unblocked").asBoolean()).isTrue();
        listenerFinished.get(5, TimeUnit.SECONDS);
    }

    @Test
    void blockedSessionListenerCanAwaitCommandOnSameSession() throws Exception {
        PipeSessionSocket session = channel.createSessionView("S1");
        CompletableFuture<Void> listenerFinished = new CompletableFuture<>();
        session.addListener("Test.block", event -> {
            try {
                session.exec("Runtime.evaluate", Map.of(), 5f).join();
                listenerFinished.complete(null);
            } catch (Throwable t) {
                listenerFinished.completeExceptionally(t);
            }
        });

        chrome.write("{\"method\":\"Test.block\",\"sessionId\":\"S1\",\"params\":{}}");
        JsonNode command = chrome.nextCommand();
        chrome.respond(command.path("id").asLong(), "{}");
        listenerFinished.get(5, TimeUnit.SECONDS);
    }

    @Test
    void blockedListenerBurstIsBoundedAndClosedViewDropsPending() throws Exception {
        PipeSessionSocket session = channel.createSessionView("S1");
        CountDownLatch release = new CountDownLatch(1);
        CountDownLatch entered = new CountDownLatch(1);
        session.addListener("Test.burst", event -> {
            entered.countDown();
            try {
                release.await();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });

        try {
            session.enqueueEvent("Test.burst", JSON.createObjectNode(), "S1");
            assertThat(entered.await(5, TimeUnit.SECONDS)).isTrue();

            long started = System.nanoTime();
            for (int i = 0; i < PipeSessionSocket.MAX_PENDING_EVENTS + 500; i++) {
                session.enqueueEvent("Test.burst", JSON.createObjectNode(), "S1");
            }
            long elapsed = System.nanoTime() - started;

            assertThat(elapsed).isLessThan(TimeUnit.SECONDS.toNanos(5));
            assertThat(session.pendingEvents()).isLessThanOrEqualTo(PipeSessionSocket.MAX_PENDING_EVENTS);

            session.markClosed();
            assertThat(session.pendingEvents()).isZero();
        } finally {
            release.countDown();
        }
    }

    @Test
    void blockedPipeWriteReturnsFutureAndTimeoutClosesChannel() throws Exception {
        channel.close();
        chrome.close();

        PipedInputStream stalledConsumer = new PipedInputStream(1024);
        PipedOutputStream blockedCommands = new PipedOutputStream(stalledConsumer);
        PipedInputStream stalledResponses = new PipedInputStream(PIPE_BUFFER);
        PipedOutputStream responseWriter = new PipedOutputStream(stalledResponses);
        channel = new CdpPipeChannel(stalledResponses, blockedCommands, 5f, 1024 * 1024);
        channel.start();
        try {
            CompletableFuture<JsonNode> pending = channel.rootView().exec(
                    "Test.blockedWrite", Map.of("payload", "x".repeat(2 * 1024 * 1024)), 0.5f);
            assertThatThrownBy(() -> pending.get(5, TimeUnit.SECONDS))
                    .isInstanceOf(ExecutionException.class)
                    .hasCauseInstanceOf(CDPException.class)
                    .hasMessageContaining("Timeout ao executar comando CDP");
            awaitTrue(() -> !channel.isOpen(), "canal fechado");
        } finally {
            responseWriter.close();
        }
    }

    @Test
    @EnabledOnOs({OS.LINUX, OS.MAC})
    void blockedProcessPipeWriteDoesNotPreventChannelCleanup() throws Exception {
        channel.close();
        chrome.close();

        Process stalledConsumer = new ProcessBuilder("/bin/sleep", "30").start();
        PipedInputStream stalledResponses = new PipedInputStream(PIPE_BUFFER);
        PipedOutputStream responseWriter = new PipedOutputStream(stalledResponses);
        channel = new CdpPipeChannel(stalledResponses, stalledConsumer.getOutputStream(), 5f,
                16 * 1024 * 1024, stalledConsumer);
        channel.start();
        try {
            CompletableFuture<JsonNode> pending = channel.rootView().exec(
                    "Test.blockedProcessWrite", Map.of("payload", "x".repeat(4 * 1024 * 1024)), 5f);
            Thread.sleep(200);

            CompletableFuture<Void> close = CompletableFuture.runAsync(channel::close);
            close.get(5, TimeUnit.SECONDS);
            assertThatThrownBy(() -> pending.get(5, TimeUnit.SECONDS))
                    .isInstanceOf(ExecutionException.class)
                    .hasCauseInstanceOf(CDPException.class);
            assertThat(stalledConsumer.waitFor(5, TimeUnit.SECONDS)).isTrue();
            awaitTrue(() -> !channel.isOpen(), "canal fechado");
            assertThat(CdpPipeRegistry.lookup(channel.getHost())).isEmpty();
        } finally {
            channel.close();
            stalledConsumer.destroyForcibly();
            responseWriter.close();
        }
    }

    /** O future falha antes de close()/unregister() terminarem: espera com prazo limitado. */
    private static void awaitTrue(java.util.function.BooleanSupplier condition, String description) {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (!condition.getAsBoolean()) {
            if (System.nanoTime() >= deadline) {
                throw new AssertionError("Condição não atingida em 5s: " + description);
            }
            try {
                Thread.sleep(10);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new AssertionError("Interrompido ao aguardar: " + description, e);
            }
        }
    }

    @Test
    void sendRacingCloseNeverLeavesFuturePending() throws Exception {
        for (int iteration = 0; iteration < 200; iteration++) {
            PipedInputStream raceResponses = new PipedInputStream(PIPE_BUFFER);
            PipedOutputStream raceCommands = new PipedOutputStream(raceResponses);
            CdpPipeChannel racing = new CdpPipeChannel(raceResponses, raceCommands, 5f, 1024 * 1024);
            racing.start();
            try {
                CountDownLatch release = new CountDownLatch(1);
                List<CompletableFuture<JsonNode>> futures = Collections.synchronizedList(new ArrayList<>());
                AtomicReference<Throwable> senderFailure = new AtomicReference<>();
                Thread sender = Thread.ofPlatform().daemon().name("race-sender").start(() -> {
                    try {
                        release.await();
                        for (int i = 0; i < 8; i++) {
                            futures.add(racing.rootView().exec("Test.race", Map.of(), 0f));
                        }
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    } catch (Throwable t) {
                        senderFailure.set(t);
                    }
                });
                Thread closer = Thread.ofPlatform().daemon().name("race-closer").start(() -> {
                    try {
                        release.await();
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                    racing.close();
                });
                release.countDown();
                sender.join(TimeUnit.SECONDS.toMillis(5));
                closer.join(TimeUnit.SECONDS.toMillis(5));
                assertThat(senderFailure.get()).isNull();
                for (CompletableFuture<JsonNode> future : futures) {
                    try {
                        future.get(2, TimeUnit.SECONDS);
                    } catch (ExecutionException expected) {
                        // Falha ao fechar o canal antes da resposta é aceitável; pendência indefinida não.
                    }
                }
            } finally {
                racing.close();
                raceResponses.close();
                raceCommands.close();
            }
        }
    }

    private static final class FakeChrome implements AutoCloseable {
        private final PipedInputStream input;
        private final PipedOutputStream output;
        private final BlockingQueue<String> frames = new LinkedBlockingQueue<>();
        private volatile boolean running = true;
        private Thread reader;

        FakeChrome(PipedInputStream input, PipedOutputStream output) {
            this.input = input;
            this.output = output;
        }

        void start() {
            reader = Thread.ofPlatform().daemon().name("fake-chrome").start(() -> {
                ByteArrayOutputStream frame = new ByteArrayOutputStream();
                try {
                    int b;
                    while (running && (b = input.read()) != -1) {
                        if (b == 0) {
                            frames.put(frame.toString(StandardCharsets.UTF_8));
                            frame.reset();
                        } else {
                            frame.write(b);
                        }
                    }
                } catch (IOException | InterruptedException ignored) {
                    Thread.currentThread().interrupt();
                }
            });
        }

        JsonNode nextCommand() throws Exception {
            String frame = frames.poll(5, TimeUnit.SECONDS);
            assertThat(frame).as("Chrome command frame").isNotNull().endsWith("}");
            return JSON.readTree(frame);
        }

        void respond(long id, String result) throws IOException {
            write("{\"id\":" + id + ",\"result\":" + result + "}");
        }

        synchronized void write(String json) throws IOException {
            output.write(json.getBytes(StandardCharsets.UTF_8));
            output.write(0);
            output.flush();
        }

        void closeWriter() throws IOException {
            output.close();
        }

        @Override
        public void close() throws IOException {
            running = false;
            input.close();
            output.close();
        }
    }
}
