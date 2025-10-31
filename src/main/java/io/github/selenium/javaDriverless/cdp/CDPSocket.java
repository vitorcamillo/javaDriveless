package io.github.selenium.javaDriverless.cdp;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import io.github.selenium.javaDriverless.cdp.exceptions.CDPException;

import org.java_websocket.client.WebSocketClient;
import org.java_websocket.handshake.ServerHandshake;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URI;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;

/**
 * Cliente WebSocket para comunicação com o Chrome DevTools Protocol (CDP).
 * <p>
 * Esta classe gerencia a conexão WebSocket com o Chrome, envia comandos CDP
 * e recebe eventos e respostas de forma assíncrona.
 * </p>
 */
public class CDPSocket extends WebSocketClient {
    
    private static final Logger logger = LoggerFactory.getLogger(CDPSocket.class);
    private static final ObjectMapper objectMapper = new ObjectMapper();
    
    private final AtomicLong commandIdCounter = new AtomicLong(1);
    private final Map<Long, CompletableFuture<JsonNode>> pendingCommands = new ConcurrentHashMap<>();
    private final Map<String, List<Consumer<JsonNode>>> eventListeners = new ConcurrentHashMap<>();
    private final Map<String, BlockingQueue<JsonNode>> eventQueues = new ConcurrentHashMap<>();
    private final List<Runnable> onClosedCallbacks = new CopyOnWriteArrayList<>();
    
    private final float timeout;
    private final int maxSize;
    private volatile boolean connected = false;
    private volatile boolean closing = false;
    
    /**
     * Cria uma nova conexão CDP Socket.
     *
     * @param websockUrl URL do WebSocket do Chrome (ex: ws://localhost:9222/devtools/page/...)
     * @param timeout timeout em segundos para comandos CDP
     * @param maxSize tamanho máximo de mensagem WebSocket em bytes
     */
    public CDPSocket(String websockUrl, float timeout, int maxSize) {
        super(URI.create(websockUrl));
        this.timeout = timeout;
        this.maxSize = maxSize;
    }
    
    /**
     * Conecta ao WebSocket de forma assíncrona.
     *
     * @return CompletableFuture que completa quando a conexão é estabelecida
     */
    public CompletableFuture<Void> connectAsync() {
        CompletableFuture<Void> future = new CompletableFuture<>();
        
        Thread.ofVirtual().start(() -> {
            try {
                boolean connected = this.connectBlocking();
                if (connected) {
                    this.connected = true;
                    future.complete(null);
                } else {
                    future.completeExceptionally(new CDPException("Falha ao conectar ao WebSocket"));
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                future.completeExceptionally(new CDPException("Conexão interrompida", e));
            }
        });
        
        return future;
    }
    
    @Override
    public void onOpen(ServerHandshake handshakedata) {
        logger.debug("Conexão WebSocket CDP aberta: {}", this.getURI());
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
                    if (json.has("error")) {
                        JsonNode error = json.get("error");
                        int code = error.get("code").asInt();
                        String errorMessage = error.get("message").asText();
                        future.completeExceptionally(new CDPException(code, errorMessage));
                    } else {
                        future.complete(json.has("result") ? json.get("result") : objectMapper.createObjectNode());
                    }
                }
            }
            // Caso contrário, é um evento
            else if (json.has("method")) {
                String method = json.get("method").asText();
                JsonNode params = json.has("params") ? json.get("params") : objectMapper.createObjectNode();
                
                // Notifica listeners
                List<Consumer<JsonNode>> listeners = eventListeners.get(method);
                if (listeners != null) {
                    for (Consumer<JsonNode> listener : listeners) {
                        try {
                            listener.accept(params);
                        } catch (Exception e) {
                            logger.error("Erro ao executar listener para evento {}: {}", method, e.getMessage(), e);
                        }
                    }
                }
                
                // Adiciona a filas de eventos
                BlockingQueue<JsonNode> queue = eventQueues.get(method);
                if (queue != null) {
                    queue.offer(params);
                }
            }
            
        } catch (JsonProcessingException e) {
            logger.error("Erro ao processar mensagem CDP: {}", message, e);
        }
    }
    
    @Override
    public void onClose(int code, String reason, boolean remote) {
        logger.debug("Conexão WebSocket CDP fechada: código={}, razão={}, remoto={}", code, reason, remote);
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
                logger.error("Erro ao executar callback de fechamento: {}", e.getMessage(), e);
            }
        }
    }
    
    @Override
    public void onError(Exception ex) {
        logger.error("Erro na conexão WebSocket CDP", ex);
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
                                new CDPException(String.format("Timeout ao executar comando %s após %.1fs", method, effectiveTimeout))
                            );
                        }
                    });
            }
            
        } catch (JsonProcessingException e) {
            pendingCommands.remove(id);
            future.completeExceptionally(new CDPException("Erro ao serializar comando CDP", e));
        }
        
        return future;
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
                            new CDPException(String.format("Timeout aguardando evento %s após %.1fs", event, timeout))
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
    
    /**
     * Cria um iterador assíncrono para um evento CDP.
     *
     * @param method nome do evento
     * @return BlockingQueue que recebe os eventos
     */
    public BlockingQueue<JsonNode> methodIterator(String method) {
        return eventQueues.computeIfAbsent(method, k -> new LinkedBlockingQueue<>());
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

