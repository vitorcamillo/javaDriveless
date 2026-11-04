package io.github.selenium.javaDriverless.cdp.pipe;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.selenium.javaDriverless.cdp.exceptions.CDPException;
import io.github.selenium.javaDriverless.logging.JavaDriverlessLogger;
import org.slf4j.Logger;

import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/** Transporte CDP por frames JSON terminados em NUL. */
public final class CdpPipeChannel implements AutoCloseable {
    private static final Logger logger = JavaDriverlessLogger.getLogger(CdpPipeChannel.class);
    private static final ObjectMapper objectMapper = new ObjectMapper();
    private static final int DEFAULT_MAX_MESSAGE_BYTES = 256 * 1024 * 1024;
    private static final int MAX_QUEUED_WRITES = 256;

    private final InputStream fromBrowser;
    private final OutputStream toBrowser;
    private final Process browserProcess;
    private final float timeout;
    private final int maxMessageBytes;
    private final String host = CdpPipeRegistry.HOST_PREFIX + UUID.randomUUID();
    private final AtomicBoolean started = new AtomicBoolean();
    private final AtomicBoolean closed = new AtomicBoolean();
    private final AtomicLong commandIdCounter = new AtomicLong(1);
    private final ConcurrentMap<Long, Pending> pending = new ConcurrentHashMap<>();
    private final ThreadPoolExecutor writer = new ThreadPoolExecutor(1, 1, 0L, TimeUnit.MILLISECONDS,
            new ArrayBlockingQueue<>(MAX_QUEUED_WRITES),
            Thread.ofPlatform().daemon().name("cdp-pipe-writer").factory());
    private final ConcurrentMap<String, PipeSessionSocket> views = new ConcurrentHashMap<>();
    private final ConcurrentMap<String, PipeSessionSocket> childOwner = new ConcurrentHashMap<>();
    private volatile PipeSessionSocket root;

    public CdpPipeChannel(InputStream fromBrowser, OutputStream toBrowser, float timeout, int maxMessageBytes) {
        this(fromBrowser, toBrowser, timeout, maxMessageBytes, null);
    }

    public CdpPipeChannel(InputStream fromBrowser, OutputStream toBrowser, float timeout, int maxMessageBytes,
            Process browserProcess) {
        this.fromBrowser = fromBrowser;
        this.toBrowser = toBrowser;
        this.browserProcess = browserProcess;
        this.timeout = timeout;
        this.maxMessageBytes = maxMessageBytes <= 0 ? DEFAULT_MAX_MESSAGE_BYTES : maxMessageBytes;
    }

    public String getHost() {
        return host;
    }

    public void start() {
        if (started.compareAndSet(false, true)) {
            CdpPipeRegistry.register(this);
            Thread.ofPlatform().daemon().name("cdp-pipe-reader").start(this::readLoop);
        }
    }

    public boolean isOpen() {
        return !closed.get();
    }

    public synchronized PipeSessionSocket rootView() {
        if (root == null) {
            root = new PipeSessionSocket(this, null, timeout, maxMessageBytes);
        }
        return root;
    }

    public PipeSessionSocket createSessionView(String sessionId) {
        if (sessionId == null || sessionId.isEmpty()) {
            throw new IllegalArgumentException("sessionId não pode ser vazio");
        }
        return views.computeIfAbsent(sessionId,
                id -> new PipeSessionSocket(this, id, timeout, maxMessageBytes));
    }

    public CompletableFuture<PipeSessionSocket> attachToTarget(String targetId, Float timeout) {
        return rootView().exec("Target.attachToTarget", Map.of("targetId", targetId, "flatten", true), timeout)
                .thenApply(this::sessionViewFromResult);
    }

    public CompletableFuture<PipeSessionSocket> attachToBrowserTarget(Float timeout) {
        return rootView().exec("Target.attachToBrowserTarget", Map.of(), timeout)
                .thenApply(this::sessionViewFromResult);
    }

    private PipeSessionSocket sessionViewFromResult(JsonNode result) {
        String sessionId = result.path("sessionId").asText("");
        if (sessionId.isEmpty()) {
            throw new java.util.concurrent.CompletionException(
                    new CDPException("Chrome não devolveu sessionId"));
        }
        return createSessionView(sessionId);
    }

    CompletableFuture<JsonNode> send(PipeSessionSocket view, String method, Map<String, Object> params,
            float effectiveTimeout, String sessionId) {
        if (!isOpen()) {
            return CompletableFuture.failedFuture(new CDPException("Canal CDP pipe fechado"));
        }

        long id = commandIdCounter.getAndIncrement();
        CompletableFuture<JsonNode> future = new CompletableFuture<>();
        ObjectNode command = objectMapper.createObjectNode();
        command.put("id", id);
        command.put("method", method);
        if (sessionId != null && !sessionId.isEmpty()) {
            command.put("sessionId", sessionId);
        }
        Pending entry = new Pending(future, view);
        try {
            if (params != null && !params.isEmpty()) {
                command.set("params", objectMapper.valueToTree(params));
            }
            byte[] frame = (objectMapper.writeValueAsString(command) + '\0').getBytes(StandardCharsets.UTF_8);
            pending.put(id, entry);
            if (effectiveTimeout > 0) {
                CompletableFuture.delayedExecutor((long) (effectiveTimeout * 1000), TimeUnit.MILLISECONDS)
                        .execute(() -> timeout(id, entry, method, effectiveTimeout));
            }
            writer.execute(() -> write(id, entry, frame));
        } catch (JsonProcessingException | StackOverflowError e) {
            pending.remove(id);
            future.completeExceptionally(new CDPException("Erro ao serializar comando CDP", e));
            return future;
        } catch (RejectedExecutionException e) {
            // Fila cheia ou close() concorrente que já removeu a entrada: completar sempre.
            pending.remove(id, entry);
            future.completeExceptionally(new CDPException(
                    !isOpen() ? "Canal CDP pipe fechado" : "Fila de escrita do canal CDP pipe cheia", e));
        }
        return future;
    }

    private void write(long id, Pending entry, byte[] frame) {
        try {
            synchronized (toBrowser) {
                boolean closedWhileQueued;
                synchronized (entry) {
                    if (!isOpen()) {
                        pending.remove(id, entry);
                        closedWhileQueued = true;
                    } else if (pending.get(id) != entry) {
                        return;
                    } else {
                        closedWhileQueued = false;
                        entry.writeStarted = true;
                    }
                }
                if (closedWhileQueued) {
                    entry.future.completeExceptionally(new CDPException("Canal CDP pipe fechado"));
                    return;
                }
                toBrowser.write(frame);
                toBrowser.flush();
                synchronized (entry) {
                    entry.writeCompleted = true;
                    entry.writeStarted = false;
                }
            }
        } catch (IOException e) {
            if (pending.remove(id, entry)) {
                entry.future.completeExceptionally(new CDPException("Falha ao escrever no canal CDP pipe", e));
            }
            close();
        } finally {
            synchronized (entry) {
                entry.writeStarted = false;
            }
        }
    }

    private void timeout(long id, Pending entry, String method, float effectiveTimeout) {
        if (pending.remove(id, entry)) {
            entry.future.completeExceptionally(new CDPException(String.format(
                    "Timeout ao executar comando CDP %s após %.1fs", method, effectiveTimeout)));
            boolean blockedWrite;
            synchronized (entry) {
                blockedWrite = entry.writeStarted && !entry.writeCompleted;
            }
            if (blockedWrite) {
                close();
            }
        }
    }

    void forget(PipeSessionSocket view) {
        String sessionId = view.getSessionId();
        if (sessionId != null) {
            views.remove(sessionId, view);
            childOwner.entrySet().removeIf(entry -> entry.getValue() == view);
        }
        CDPException failure = new CDPException("Sessão CDP pipe fechada");
        pending.entrySet().removeIf(entry -> {
            if (entry.getValue().view == view) {
                entry.getValue().future.completeExceptionally(failure);
                return true;
            }
            return false;
        });
    }

    private void readLoop() {
        try (InputStream input = new BufferedInputStream(fromBrowser)) {
            ByteArrayOutputStream frame = new ByteArrayOutputStream();
            int value;
            while ((value = input.read()) != -1) {
                if (value == 0) {
                    processFrame(frame.toByteArray());
                    frame.reset();
                } else {
                    frame.write(value);
                    if (frame.size() > maxMessageBytes) {
                        JavaDriverlessLogger.error(logger,
                                "Frame CDP pipe excedeu limite (bytes={})", frame.size());
                        close();
                        return;
                    }
                }
            }
        } catch (IOException e) {
            if (isOpen()) {
                JavaDriverlessLogger.error(logger, "Leitura do canal CDP pipe falhou: {}",
                        e.getClass().getSimpleName());
            }
        } finally {
            close();
        }
    }

    private void processFrame(byte[] bytes) {
        String message = new String(bytes, StandardCharsets.UTF_8);
        JsonNode json;
        try {
            json = objectMapper.readTree(message);
        } catch (JsonProcessingException e) {
            JavaDriverlessLogger.error(logger, "Mensagem CDP pipe inválida (charCount={})", message.length());
            return;
        }
        if (json == null) {
            JavaDriverlessLogger.error(logger, "Mensagem CDP pipe inválida (charCount={})", message.length());
            return;
        }
        if (json.has("id")) {
            handleResponse(json);
        } else if (json.has("method")) {
            handleEvent(json);
        }
    }

    private void handleResponse(JsonNode json) {
        Pending entry = pending.remove(json.path("id").asLong());
        if (entry == null) {
            return;
        }
        if (json.has("error")) {
            JsonNode error = json.get("error");
            entry.future.completeExceptionally(CDPException.fromProtocolError(
                    error.path("code").asInt(), error.path("message").asText(null)));
        } else {
            entry.future.complete(json.has("result") ? json.get("result") : objectMapper.createObjectNode());
        }
    }

    private void handleEvent(JsonNode json) {
        String method = json.path("method").asText();
        JsonNode params = json.has("params") ? json.get("params") : objectMapper.createObjectNode();
        String sessionId = json.path("sessionId").asText("");
        PipeSessionSocket owner = ownerOf(sessionId);
        if ("Target.attachedToTarget".equals(method)) {
            String child = params.path("sessionId").asText("");
            if (!child.isEmpty() && owner != null) {
                childOwner.put(child, owner);
            }
        }
        owner = ownerOf(sessionId);
        if (owner != null) {
            String eventSessionId = sessionId.isEmpty() || sessionId.equals(owner.getSessionId()) ? "" : sessionId;
            owner.enqueueEvent(method, params, eventSessionId);
        }
        if ("Target.detachedFromTarget".equals(method)) {
            String child = params.path("sessionId").asText("");
            childOwner.remove(child);
            PipeSessionSocket detached = views.get(child);
            if (detached != null) {
                detached.markClosed();
            }
        }
    }

    private PipeSessionSocket ownerOf(String sessionId) {
        if (sessionId == null || sessionId.isEmpty()) {
            return root;
        }
        PipeSessionSocket view = views.get(sessionId);
        return view != null ? view : childOwner.get(sessionId);
    }

    @Override
    public void close() {
        if (!closed.compareAndSet(false, true)) {
            return;
        }
        try {
            fromBrowser.close();
        } catch (IOException ignored) {
            // Fechamento best-effort.
        }
        writer.shutdownNow();
        if (browserProcess != null) {
            try {
                browserProcess.toHandle().descendants().forEach(descendant -> {
                    try {
                        descendant.destroyForcibly();
                    } catch (RuntimeException ignored) {
                        // A falha em um processo filho não deve impedir o fechamento do processo raiz.
                    }
                });
            } catch (RuntimeException ignored) {
                // O fechamento do stream e a liberação do estado continuam sendo best-effort.
            }
            try {
                browserProcess.destroyForcibly();
            } catch (RuntimeException ignored) {
                // O fechamento do stream e a liberação do estado continuam sendo best-effort.
            }
        }
        try {
            toBrowser.close();
        } catch (IOException ignored) {
            // Fechamento best-effort.
        }
        CDPException failure = new CDPException("Canal CDP pipe fechado");
        pending.forEach((id, entry) -> entry.future.completeExceptionally(failure));
        pending.clear();
        PipeSessionSocket rootView = root;
        if (rootView != null) {
            rootView.markClosed();
        }
        views.values().forEach(PipeSessionSocket::markClosed);
        views.clear();
        childOwner.clear();
        CdpPipeRegistry.unregister(host);
    }

    private static final class Pending {
        private final CompletableFuture<JsonNode> future;
        private final PipeSessionSocket view;
        private boolean writeStarted;
        private boolean writeCompleted;

        private Pending(CompletableFuture<JsonNode> future, PipeSessionSocket view) {
            this.future = future;
            this.view = view;
        }
    }
}
