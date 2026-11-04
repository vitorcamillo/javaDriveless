package io.github.selenium.javaDriverless.cdp.pipe;

import com.fasterxml.jackson.databind.JsonNode;
import io.github.selenium.javaDriverless.cdp.CDPSocket;
import io.github.selenium.javaDriverless.cdp.exceptions.CDPException;
import io.github.selenium.javaDriverless.logging.JavaDriverlessLogger;
import org.slf4j.Logger;

import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/** View de uma sessão CDP sobre um canal pipe compartilhado. */
public class PipeSessionSocket extends CDPSocket {
    static final int MAX_PENDING_EVENTS = 4096;
    private static final Logger logger = JavaDriverlessLogger.getLogger(PipeSessionSocket.class);

    private final CdpPipeChannel channel;
    private final String sessionId;
    private final ThreadPoolExecutor executor = new ThreadPoolExecutor(1, 1, 0L, TimeUnit.MILLISECONDS,
            new LinkedBlockingQueue<>(MAX_PENDING_EVENTS),
            Thread.ofVirtual().name("cdp-pipe-view").factory(), new ThreadPoolExecutor.AbortPolicy());
    private final AtomicBoolean closed = new AtomicBoolean();
    private final AtomicBoolean saturated = new AtomicBoolean();

    PipeSessionSocket(CdpPipeChannel channel, String sessionId, float timeout, int maxSize) {
        super(timeout, maxSize);
        this.channel = channel;
        this.sessionId = sessionId;
    }

    public String getSessionId() {
        return sessionId;
    }

    public CdpPipeChannel getChannel() {
        return channel;
    }

    void enqueueEvent(String method, JsonNode params, String eventSessionId) {
        if (closed.get()) {
            return;
        }
        runOnExecutor(() -> dispatchEvent(method, params, eventSessionId));
    }

    void runOnExecutor(Runnable task) {
        try {
            executor.execute(task);
            saturated.set(false);
        } catch (RejectedExecutionException ignored) {
            if (!executor.isShutdown() && saturated.compareAndSet(false, true)) {
                JavaDriverlessLogger.warn(logger,
                        "Fila de eventos CDP pipe cheia (limite={}, sessionId={}); descartando eventos",
                        MAX_PENDING_EVENTS, sessionId);
            }
        }
    }

    int pendingEvents() {
        return executor.getQueue().size();
    }

    void markClosed() {
        if (!closed.compareAndSet(false, true)) {
            return;
        }
        channel.forget(this);
        super.onClose(1000, "", true);
        executor.getQueue().clear();
        executor.shutdown();
    }

    @Override
    public CompletableFuture<Void> connectAsync() {
        return CompletableFuture.completedFuture(null);
    }

    @Override
    public CompletableFuture<Void> connectAsync(float timeoutSeconds) {
        return CompletableFuture.completedFuture(null);
    }

    @Override
    public CompletableFuture<JsonNode> exec(String method, Map<String, Object> params, Float timeout,
            String requestedSessionId) {
        if (closed.get()) {
            return CompletableFuture.failedFuture(new CDPException("Sessão CDP pipe fechada"));
        }
        String targetSessionId = requestedSessionId != null && !requestedSessionId.isEmpty()
                ? requestedSessionId : sessionId;
        float effectiveTimeout = timeout != null ? timeout : getDefaultTimeout();
        return offReaderThread(channel.send(this, method, params, effectiveTimeout, targetSessionId));
    }

    @Override
    public boolean isConnected() {
        return !closed.get() && channel.isOpen();
    }

    @Override
    public CompletableFuture<Void> closeAsync() {
        if (sessionId == null) {
            markClosed();
            return CompletableFuture.completedFuture(null);
        }
        if (closed.get() || !channel.isOpen()) {
            markClosed();
            return CompletableFuture.completedFuture(null);
        }
        return channel.rootView().exec("Target.detachFromTarget", Map.of("sessionId", sessionId), 2f)
                .handle((result, error) -> {
                    markClosed();
                    return null;
                });
    }

    @Override
    public void close() {
        closeAsync();
    }

    @Override
    public void send(String text) {
        throw new IllegalStateException("Use exec no transporte pipe");
    }
}
