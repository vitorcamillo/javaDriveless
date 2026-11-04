package io.github.selenium.javaDriverless.cdp;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import io.github.selenium.javaDriverless.cdp.exceptions.CDPException;
import io.github.selenium.javaDriverless.logging.JavaDriverlessLogger;

import org.java_websocket.client.WebSocketClient;
import org.java_websocket.drafts.Draft;
import org.java_websocket.drafts.Draft_6455;
import org.java_websocket.protocols.Protocol;
import org.java_websocket.handshake.ServerHandshake;
import org.slf4j.Logger;

import java.net.URI;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;
import java.util.function.BiConsumer;

/**
 * Cliente WebSocket para comunicação com o Chrome DevTools Protocol (CDP).
 * <p>
 * Esta classe gerencia a conexão WebSocket com o Chrome, envia comandos CDP
 * e recebe eventos e respostas de forma assíncrona.
 * </p>
 */
public class CDPSocket extends WebSocketClient implements CdpConnection {

    static final int MAX_QUEUED_EVENTS = 4096;
    private static final Logger logger = JavaDriverlessLogger.getLogger(CDPSocket.class);
    private static final ObjectMapper objectMapper = new ObjectMapper();
    private static final ExecutorService RESPONSE_EXECUTOR = Executors.newThreadPerTaskExecutor(
            Thread.ofVirtual().name("cdp-response-", 0).factory());

    private final AtomicLong commandIdCounter = new AtomicLong(1);
    private final Map<Long, CompletableFuture<JsonNode>> pendingCommands = new ConcurrentHashMap<>();
    private final Map<String, List<Consumer<JsonNode>>> eventListeners = new ConcurrentHashMap<>();
    private final Map<String, List<BiConsumer<String, JsonNode>>> sessionEventListeners = new ConcurrentHashMap<>();
    private final Map<String, BlockingQueue<JsonNode>> eventQueues = new ConcurrentHashMap<>();
    private final Set<String> saturatedEventQueues = ConcurrentHashMap.newKeySet();
    private final List<Runnable> onClosedCallbacks = new CopyOnWriteArrayList<>();

    private final float timeout;
    private final int maxSize;
    private volatile boolean connected = false;
    private volatile boolean closing = false;

    /**
     * Cria uma nova conexão CDP Socket.
     * <p>
     * Quando {@code maxSize} é positivo, ele é aplicado como tamanho máximo de
     * frame do {@code Draft_6455} (Java-WebSocket 1.6.0), limitando mensagens
     * recebidas; caso contrário vale o padrão da lib (sem limite reduzido).
     * </p>
     *
     * @param websockUrl URL do WebSocket do Chrome (ex: ws://localhost:9222/devtools/page/...)
     * @param timeout timeout em segundos para comandos CDP
     * @param maxSize tamanho máximo de mensagem WebSocket em bytes
     */
    public CDPSocket(String websockUrl, float timeout, int maxSize) {
        super(URI.create(websockUrl), draftForMaxSize(maxSize));
        this.timeout = timeout;
        this.maxSize = maxSize;
    }

    // Monta o draft com teto de frame quando maxSize é positivo
    private static Draft draftForMaxSize(int maxSize) {
        if (maxSize > 0) {
            return new Draft_6455(Collections.emptyList(), List.of(new Protocol("")), maxSize);
        }
        return new Draft_6455();
    }

    /** Uso exclusivo de views de outros transportes (ex.: pipe); nunca conecta via WebSocket. */
    protected CDPSocket(float timeout, int maxSize) {
        super(URI.create("ws://127.0.0.1:1/cdp-non-websocket-view"));
        this.timeout = timeout;
        this.maxSize = maxSize;
    }

    /**
     * Conecta ao WebSocket de forma assíncrona.
     *
     * @return CompletableFuture que completa quando a conexão é estabelecida
     */
    public CompletableFuture<Void> connectAsync() {
        return connectAsync(timeout);
    }

    /**
     * Conecta ao WebSocket respeitando um limite explícito.
     *
     * @param timeoutSeconds tempo máximo em segundos; valores não positivos mantêm
     *                       o comportamento sem limite
     * @return CompletableFuture que completa quando a conexão é estabelecida
     */
    public CompletableFuture<Void> connectAsync(float timeoutSeconds) {
        CompletableFuture<Void> future = new CompletableFuture<>();

        Thread.ofVirtual().start(() -> {
            try {
                boolean opened;
                if (timeoutSeconds > 0) {
                    long timeoutMillis = Math.max(1L, (long) Math.ceil(timeoutSeconds * 1000.0));
                    opened = this.connectBlocking(timeoutMillis, TimeUnit.MILLISECONDS);
                } else {
                    opened = this.connectBlocking();
                }
                if (opened) {
                    this.connected = true;
                    future.complete(null);
                } else {
                    future.completeExceptionally(new CDPException("Falha ao conectar ao WebSocket"));
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                future.completeExceptionally(new CDPException("Conexão interrompida", e));
            } catch (Exception e) {
                // A URI do DevTools funciona como capability; não propagar mensagens do
                // handshake/conector para fora desta camada.
                future.completeExceptionally(new CDPException("Falha ao conectar ao WebSocket"));
            }
        });

        return future;
    }

    @Override
    public void onOpen(ServerHandshake handshakedata) {
        // The DevTools websocket path acts as a session capability; never log the URI.
        JavaDriverlessLogger.debug(logger, "Conexão WebSocket CDP aberta");
        connected = true;
    }

    @Override
    public void onMessage(String message) {
        try {
            JsonNode json = objectMapper.readTree(message);

            // Verifica se é uma resposta a um comando
            if (json.has("id")) {
                long id = json.get("id").asLong();
                CompletableFuture<JsonNode> future = pendingCommands.remove(id);

                if (future != null) {
                    try {
                        if (json.has("error")) {
                            JsonNode error = json.get("error");
                            int code = error.path("code").asInt(-32603);
                            String protocolMessage = error.path("message").asText(null);
                            future.completeExceptionally(
                                    CDPException.fromProtocolError(code, protocolMessage));
                        } else {
                            future.complete(json.has("result") ? json.get("result") : objectMapper.createObjectNode());
                        }
                    } catch (RuntimeException e) {
                        future.completeExceptionally(new CDPException("Erro ao processar resposta CDP", e));
                    }
                }
            }
            // Caso contrário, é um evento
            else if (json.has("method")) {
                String method = json.get("method").asText();
                JsonNode params = json.has("params") ? json.get("params") : objectMapper.createObjectNode();
                String eventSessionId = json.path("sessionId").asText("");
                dispatchEvent(method, params, eventSessionId);
            }

        } catch (JsonProcessingException e) {
            JavaDriverlessLogger.error(logger,
                    "Erro ao processar mensagem CDP inválida (charCount={})",
                    message == null ? 0 : message.length());
        } catch (RuntimeException e) {
            JavaDriverlessLogger.error(logger,
                    "Erro ao processar mensagem CDP (charCount={})",
                    message == null ? 0 : message.length());
        }
    }

    protected void dispatchEvent(String method, JsonNode params, String eventSessionId) {
        List<Consumer<JsonNode>> listeners = eventListeners.get(method);
        if (listeners != null) {
            for (Consumer<JsonNode> listener : listeners) {
                try {
                    listener.accept(params);
                } catch (Exception e) {
                    JavaDriverlessLogger.error(logger,
                            "Erro ao executar listener CDP: {}",
                            e.getClass().getSimpleName());
                }
            }
        }
        List<BiConsumer<String, JsonNode>> sessionListeners = sessionEventListeners.get(method);
        if (sessionListeners != null) {
            for (BiConsumer<String, JsonNode> listener : sessionListeners) {
                try {
                    listener.accept(eventSessionId, params);
                } catch (Exception e) {
                    JavaDriverlessLogger.error(logger,
                            "Erro ao executar listener CDP por sessão: {}",
                            e.getClass().getSimpleName());
                }
            }
        }

        BlockingQueue<JsonNode> queue = eventQueues.get(method);
        if (queue != null) {
            synchronized (queue) {
                if (queue.offer(params)) {
                    saturatedEventQueues.remove(method);
                } else if (saturatedEventQueues.add(method)) {
                    JavaDriverlessLogger.warn(logger,
                            "Fila de eventos CDP cheia (limite={}, método={}); descartando eventos",
                            MAX_QUEUED_EVENTS, method);
                }
            }
        }
    }

    protected float getDefaultTimeout() {
        return timeout;
    }

    /** Dependentes do resultado rodam em virtual thread, nunca na thread leitora do transporte. */
    protected static <T> CompletableFuture<T> offReaderThread(CompletableFuture<T> future) {
        return future.whenCompleteAsync((result, error) -> { }, RESPONSE_EXECUTOR);
    }

    @Override
    public void onClose(int code, String reason, boolean remote) {
        JavaDriverlessLogger.debug(logger,
                "Conexão WebSocket CDP fechada: código={}, razãoInformada={}, remoto={}",
                code, reason != null && !reason.isBlank(), remote);
        connected = false;

        // Completa todos os comandos pendentes com exceção
        for (CompletableFuture<JsonNode> future : pendingCommands.values()) {
            future.completeExceptionally(new CDPException("Conexão WebSocket fechada"));
        }
        pendingCommands.clear();

        // Executa callbacks de fechamento
        for (Runnable callback : onClosedCallbacks) {
            try {
                callback.run();
            } catch (Exception e) {
                JavaDriverlessLogger.error(logger,
                        "Erro ao executar callback de fechamento: {}", e.getClass().getSimpleName());
            }
        }
    }

    @Override
    public void onError(Exception ex) {
        JavaDriverlessLogger.error(logger, "Erro na conexão WebSocket CDP: {}",
                ex == null ? "UnknownException" : ex.getClass().getSimpleName());
    }

    /**
     * Executa um comando CDP.
     *
     * @param method nome do método CDP (ex: "Page.navigate")
     * @param params parâmetros do comando
     * @param timeout timeout em segundos (null para usar o padrão)
     * @return CompletableFuture com o resultado do comando
     */
    public CompletableFuture<JsonNode> exec(String method, Map<String, Object> params, Float timeout) {
        return exec(method, params, timeout, null);
    }

    /**
     * Executa um comando CDP roteado para uma sessão anexada (flatten mode).
     * <p>
     * Quando {@code sessionId} é informado, o comando é entregue à sessão filha
     * (worker, service worker ou iframe out-of-process) sobre a mesma conexão
     * WebSocket. A resposta continua sendo casada pelo {@code id} do comando,
     * que é único nesta conexão, então o roteamento por id permanece correto.
     * </p>
     *
     * @param method nome do método CDP
     * @param params parâmetros do comando
     * @param timeout timeout em segundos (null para usar o padrão)
     * @param sessionId sessionId da sessão anexada; null para a sessão atual
     * @return CompletableFuture com o resultado do comando
     */
    public CompletableFuture<JsonNode> exec(String method, Map<String, Object> params, Float timeout, String sessionId) {
        if (!connected && !closing) {
            return CompletableFuture.failedFuture(new CDPException("WebSocket não conectado"));
        }

        long id = commandIdCounter.getAndIncrement();
        CompletableFuture<JsonNode> future = new CompletableFuture<>();
        pendingCommands.put(id, future);

        try {
            ObjectNode command = objectMapper.createObjectNode();
            command.put("id", id);
            command.put("method", method);

            if (sessionId != null && !sessionId.isEmpty()) {
                command.put("sessionId", sessionId);
            }

            if (params != null && !params.isEmpty()) {
                command.set("params", objectMapper.valueToTree(params));
            }

            String message = objectMapper.writeValueAsString(command);
            send(message);

            // Aplica timeout
            float effectiveTimeout = (timeout != null) ? timeout : this.timeout;
            if (effectiveTimeout > 0) {
                CompletableFuture.delayedExecutor((long) (effectiveTimeout * 1000), TimeUnit.MILLISECONDS)
                    .execute(() -> {
                        if (!future.isDone()) {
                            pendingCommands.remove(id);
                            future.completeExceptionally(
                                new CDPException(String.format(
                                        "Timeout ao executar comando CDP %s após %.1fs",
                                        method, effectiveTimeout))
                            );
                        }
                    });
            }

        } catch (JsonProcessingException e) {
            pendingCommands.remove(id);
            future.completeExceptionally(new CDPException("Erro ao serializar comando CDP", e));
        } catch (StackOverflowError e) {
            pendingCommands.remove(id);
            future.completeExceptionally(new CDPException("Erro ao serializar comando CDP", e));
        }

        return offReaderThread(future);
    }

    /**
     * Aguarda por um evento CDP específico.
     *
     * @param event nome do evento (ex: "Page.loadEventFired")
     * @param timeout timeout em segundos (null para infinito)
     * @return CompletableFuture com os parâmetros do evento
     */
    public CompletableFuture<JsonNode> waitFor(String event, Float timeout) {
        CompletableFuture<JsonNode> future = new CompletableFuture<>();

        Consumer<JsonNode> listener = params -> {
            future.complete(params);
        };

        addListener(event, listener);

        // Remove listener após completar
        future.whenComplete((result, error) -> {
            removeListener(event, listener);
        });

        // Aplica timeout se especificado
        if (timeout != null && timeout > 0) {
            CompletableFuture.delayedExecutor((long) (timeout * 1000), TimeUnit.MILLISECONDS)
                .execute(() -> {
                    if (!future.isDone()) {
                        removeListener(event, listener);
                        future.completeExceptionally(
                            new CDPException(String.format(
                                    "Timeout aguardando evento CDP após %.1fs", timeout))
                        );
                    }
                });
        }

        return future;
    }

    /**
     * Adiciona um listener para um evento CDP.
     *
     * @param method nome do evento
     * @param callback função a ser chamada quando o evento ocorrer
     */
    public void addListener(String method, Consumer<JsonNode> callback) {
        eventListeners.computeIfAbsent(method, k -> new CopyOnWriteArrayList<>()).add(callback);
    }

    public void addSessionListener(String method, BiConsumer<String, JsonNode> callback) {
        sessionEventListeners.computeIfAbsent(method, k -> new CopyOnWriteArrayList<>()).add(callback);
    }

    /**
     * Remove um listener de um evento CDP.
     *
     * @param method nome do evento
     * @param callback função a ser removida
     */
    public void removeListener(String method, Consumer<JsonNode> callback) {
        List<Consumer<JsonNode>> listeners = eventListeners.get(method);
        if (listeners != null) {
            listeners.remove(callback);
            if (listeners.isEmpty()) {
                eventListeners.remove(method);
            }
        }
    }

    public void removeSessionListener(String method, BiConsumer<String, JsonNode> callback) {
        List<BiConsumer<String, JsonNode>> listeners = sessionEventListeners.get(method);
        if (listeners != null) {
            listeners.remove(callback);
            if (listeners.isEmpty()) sessionEventListeners.remove(method);
        }
    }

    /**
     * Cria um iterador assíncrono para um evento CDP.
     *
     * @param method nome do evento
     * @return BlockingQueue que recebe os eventos
     */
    public BlockingQueue<JsonNode> methodIterator(String method) {
        return eventQueues.computeIfAbsent(method, k -> new LinkedBlockingQueue<>(MAX_QUEUED_EVENTS));
    }

    /**
     * Adiciona um callback para quando a conexão for fechada.
     *
     * @param callback função a ser chamada
     */
    public void addOnClosedCallback(Runnable callback) {
        onClosedCallbacks.add(callback);
    }

    /**
     * Retorna a lista de callbacks de fechamento.
     *
     * @return lista de callbacks
     */
    public List<Runnable> getOnClosed() {
        return onClosedCallbacks;
    }

    /**
     * Fecha a conexão WebSocket.
     *
     * @return CompletableFuture que completa quando a conexão é fechada
     */
    public CompletableFuture<Void> closeAsync() {
        closing = true;
        return CompletableFuture.runAsync(() -> {
            try {
                this.closeBlocking();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new CompletionException(new CDPException("Erro ao fechar conexão", e));
            }
        });
    }

    /**
     * Verifica se a conexão está aberta.
     *
     * @return true se conectado
     */
    public boolean isConnected() {
        return connected && !closing;
    }
}
