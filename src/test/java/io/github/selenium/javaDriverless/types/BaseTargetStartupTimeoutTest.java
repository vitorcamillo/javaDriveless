package io.github.selenium.javaDriverless.types;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

class BaseTargetStartupTimeoutTest {

    @Test
    void initHonorsEndToEndTimeoutWhenWebSocketHandshakeNeverCompletes() throws Exception {
        try (StalledWebSocketEndpoint stalledWebSocket = new StalledWebSocketEndpoint()) {
            HttpServer discovery = discoveryServer(stalledWebSocket);

            try {
                BaseTarget target = new BaseTarget(
                        "127.0.0.1:" + discovery.getAddress().getPort(),
                        false,
                        0.25f,
                        1 << 20);

                long startedAt = System.nanoTime();
                CompletableFuture<BaseTarget> attempt = target.init();
                Throwable failure = catchThrowable(() -> attempt.get(2, TimeUnit.SECONDS));
                long elapsedMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedAt);

                assertThat(failure)
                        .isInstanceOf(ExecutionException.class)
                        .hasRootCauseInstanceOf(RuntimeException.class);
                assertThat(failure.getCause().getMessage())
                        .startsWith("Não foi possível conectar ao Chrome em ")
                        .endsWith(" segundos");
                assertThat(elapsedMillis).isLessThan(2_000L);
                assertThat(target.getSocket()).isNull();
            } finally {
                discovery.stop(0);
            }
        }
    }

    @Test
    void concurrentInitCallersShareOneSingleFlightAttempt() throws Exception {
        try (StalledWebSocketEndpoint stalledWebSocket = new StalledWebSocketEndpoint()) {
            HttpServer discovery = discoveryServer(stalledWebSocket);
            ExecutorService callers = Executors.newFixedThreadPool(12);
            try {
                BaseTarget target = new BaseTarget(
                        "127.0.0.1:" + discovery.getAddress().getPort(),
                        false,
                        0.5f,
                        1 << 20);
                CountDownLatch start = new CountDownLatch(1);
                List<CompletableFuture<CompletableFuture<BaseTarget>>> invocations =
                        IntStream.range(0, 12)
                                .mapToObj(ignored -> CompletableFuture.supplyAsync(() -> {
                                    try {
                                        start.await();
                                    } catch (InterruptedException failure) {
                                        Thread.currentThread().interrupt();
                                        throw new IllegalStateException(failure);
                                    }
                                    return target.init();
                                }, callers))
                                .toList();

                start.countDown();
                List<CompletableFuture<BaseTarget>> attempts = invocations.stream()
                        .map(CompletableFuture::join)
                        .toList();
                CompletableFuture<BaseTarget> shared = attempts.get(0);
                assertThat(attempts).allSatisfy(attempt -> assertThat(attempt).isSameAs(shared));
                assertThat(catchThrowable(() -> shared.get(2, TimeUnit.SECONDS)))
                        .isInstanceOf(ExecutionException.class)
                        .hasRootCauseInstanceOf(RuntimeException.class);

                CompletableFuture<BaseTarget> retry = target.init();
                assertThat(retry).isNotSameAs(shared);
                assertThat(catchThrowable(() -> retry.get(2, TimeUnit.SECONDS)))
                        .isInstanceOf(ExecutionException.class)
                        .hasRootCauseInstanceOf(RuntimeException.class);
            } finally {
                callers.shutdownNow();
                assertThat(callers.awaitTermination(2, TimeUnit.SECONDS)).isTrue();
                discovery.stop(0);
            }
        }
    }

    private static HttpServer discoveryServer(StalledWebSocketEndpoint stalledWebSocket) throws Exception {
        HttpServer discovery = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        discovery.createContext("/json/version", exchange -> {
            byte[] response = ("{\"webSocketDebuggerUrl\":\"ws://127.0.0.1:"
                    + stalledWebSocket.port() + "/devtools/browser/test\"}")
                    .getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, response.length);
            try (var output = exchange.getResponseBody()) {
                output.write(response);
            }
        });
        discovery.start();
        return discovery;
    }

    private static final class StalledWebSocketEndpoint implements AutoCloseable {
        private final ServerSocket server;
        private final List<Socket> accepted = new CopyOnWriteArrayList<>();
        private final Thread acceptThread;
        private volatile boolean closed;

        private StalledWebSocketEndpoint() throws Exception {
            server = new ServerSocket();
            server.bind(new InetSocketAddress("127.0.0.1", 0));
            acceptThread = Thread.ofPlatform().daemon().name("stalled-websocket-test-endpoint").start(() -> {
                while (!closed) {
                    try {
                        accepted.add(server.accept());
                    } catch (Exception failure) {
                        if (!closed) {
                            throw new IllegalStateException("Stalled WebSocket endpoint failed", failure);
                        }
                    }
                }
            });
        }

        private int port() {
            return server.getLocalPort();
        }

        @Override
        public void close() throws Exception {
            closed = true;
            server.close();
            acceptThread.join(1_000L);
            for (Socket socket : accepted) {
                socket.close();
            }
        }
    }
}
