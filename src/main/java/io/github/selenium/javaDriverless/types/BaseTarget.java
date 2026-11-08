package io.github.selenium.javaDriverless.types;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import io.github.selenium.javaDriverless.cdp.CDPCommandExecutor;
import io.github.selenium.javaDriverless.cdp.CDPSocket;
import io.github.selenium.javaDriverless.cdp.exceptions.CDPException;
import io.github.selenium.javaDriverless.cdp.pipe.CdpPipeChannel;
import io.github.selenium.javaDriverless.cdp.pipe.CdpPipeRegistry;

import io.github.selenium.javaDriverless.cdp.DevToolsHttp;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.function.BiConsumer;

/**
 * O BaseTarget para a instância do Chrome
 * representa uma conexão com o navegador inteiro.
 * <p>
 * <strong>Nota:</strong> comandos executados no BaseTarget geralmente estão em um escopo
 * global sobre toda a instância do Chrome. Infelizmente, nem todos são suportados.
 * </p>
 */
public class BaseTarget implements CDPCommandExecutor {
    
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
    
    protected CDPSocket socket;
    protected final boolean isRemote;
    protected final String host;
    protected final String id;
    protected final float timeout;
    protected final int maxWsSize;
    protected final Map<String, String> downloadsPaths;
    
    protected volatile boolean started = false;
    private volatile CompletableFuture<BaseTarget> initFuture;
    
    /**
     * Cria um novo BaseTarget.
     *
     * @param host endereço do host do Chrome (ex: "localhost:9222")
     * @param isRemote se é uma conexão remota
     * @param timeout timeout em segundos
     * @param maxWsSize tamanho máximo de mensagem WebSocket em bytes
     */
    public BaseTarget(String host, boolean isRemote, float timeout, int maxWsSize) {
        this.socket = null;
        this.isRemote = isRemote;
        this.host = host;
        this.id = "BaseTarget";
        this.timeout = timeout;
        this.maxWsSize = maxWsSize;
        // Chave pode ser nula (contextId null) e ConcurrentHashMap não aceita: usar wrapper sincronizado.
        this.downloadsPaths = Collections.synchronizedMap(new HashMap<>());
    }
    
    /**
     * Retorna o ID do target.
     *
     * @return ID do target
     */
    public String getId() {
        return id;
    }
    
    /**
     * Retorna o tipo do target de forma assíncrona.
     *
     * @return CompletableFuture com o tipo
     */
    public CompletableFuture<String> getType() {
        return CompletableFuture.completedFuture("BaseTarget");
    }
    
    /**
     * Retorna o socket CDP para a conexão.
     *
     * @return socket CDP
     */
    public CDPSocket getSocket() {
        return socket;
    }

    /**
     * Cria outra conexão WebSocket com o mesmo target de navegador.
     * Configurações de auto-attach do CDP são por conexão; alguns grupos de targets precisam de
     * políticas independentes para que uma não substitua a outra.
     */
    public BaseTarget newIndependentConnection() {
        return new BaseTarget(host, isRemote, timeout, maxWsSize);
    }
    
    /**
     * Inicializa a conexão com o Chrome de forma assíncrona.
     *
     * @return CompletableFuture que completa quando a inicialização termina
     */
    public CompletableFuture<BaseTarget> init() {
        if (started) {
            return CompletableFuture.completedFuture(this);
        }
        synchronized (this) {
            if (started) {
                return CompletableFuture.completedFuture(this);
            }
            if (initFuture != null) {
                return initFuture;
            }
            CompletableFuture<BaseTarget> attempt = CompletableFuture.supplyAsync(this::initializeConnection);
            initFuture = attempt;
            attempt.whenComplete((ignored, failure) -> {
                if (failure == null) return;
                synchronized (BaseTarget.this) {
                    if (initFuture == attempt) {
                        initFuture = null;
                    }
                }
            });
            return attempt;
        }
    }

    private BaseTarget initializeConnection() {
        if (CdpPipeRegistry.isPipeHost(host)) {
            return initializePipeConnection();
        }

        long startTime = System.nanoTime();
        long timeoutNanos = timeout > 0
                ? Math.max(1L, (long) Math.ceil(timeout * 1_000_000_000.0))
                : 0L;
        String url = "http://" + host + "/json/version";

        // O cliente HTTP é compartilhado (DevToolsHttp); nada para fechar por target.
        try {
            while (true) {
                CDPSocket candidate = null;
                try {
                    long remainingNanos = remainingNanos(startTime, timeoutNanos);
                    if (timeoutNanos > 0 && remainingNanos <= 0) {
                        throw startupTimeout();
                    }
                    long requestTimeoutNanos = timeoutNanos > 0
                            ? Math.max(1L, Math.min(TimeUnit.SECONDS.toNanos(10), remainingNanos))
                            : TimeUnit.SECONDS.toNanos(10);
                    // Sonda /json/version com o cliente JDK compartilhado: sem pilha
                    // Netty por target, só GET simples em loopback.
                    String response = DevToolsHttp.get(url, requestTimeoutNanos);

                    // FIX PERF-03: Usar ObjectMapper singleton (herdado do campo estático)
                    JsonNode json = OBJECT_MAPPER.readTree(response);
                    String wsUrl = json.get("webSocketDebuggerUrl").asText();

                    // Criar socket CDP
                    candidate = new CDPSocket(wsUrl, timeout, maxWsSize);
                    this.socket = candidate;
                    float connectTimeout = timeoutNanos > 0
                            ? Math.max(0.001f,
                                    remainingNanos(startTime, timeoutNanos) / 1_000_000_000.0f)
                            : timeout;
                    candidate.connectAsync(connectTimeout).join();
                    this.started = true;

                    return this;

                } catch (Exception e) {
                    if (e instanceof InterruptedException interrupted) {
                        Thread.currentThread().interrupt();
                        discardFailedSocket(candidate);
                        throw new RuntimeException("Conexão interrompida", interrupted);
                    }
                    discardFailedSocket(candidate);
                    long remainingNanos = remainingNanos(startTime, timeoutNanos);
                    if (timeoutNanos > 0 && remainingNanos <= 0) {
                        throw startupTimeout();
                    }
                    // Aguardar um pouco antes de tentar novamente
                    try {
                        long retryDelayMillis = timeoutNanos > 0
                                ? Math.max(1L, Math.min(100L,
                                        TimeUnit.NANOSECONDS.toMillis(remainingNanos)))
                                : 100L;
                        Thread.sleep(retryDelayMillis);
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        throw new RuntimeException("Conexão interrompida", ie);
                    }
                }
            }
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            throw new RuntimeException("Erro ao inicializar conexão com Chrome", e);
        }
    }

    private BaseTarget initializePipeConnection() {
        CdpPipeChannel channel = CdpPipeRegistry.lookup(host)
                .orElseThrow(() -> new RuntimeException("Canal CDP pipe indisponível"));
        // Cada BaseTarget ganha uma sessão de browser própria, mantendo auto-attach independente
        // sem abrir uma segunda conexão.
        try {
            this.socket = channel.attachToBrowserTarget(timeout).join();
            this.started = true;
            return this;
        } catch (Exception e) {
            throw new RuntimeException("Erro ao inicializar conexão com Chrome", e);
        }
    }

    private static long remainingNanos(long startTime, long timeoutNanos) {
        return timeoutNanos <= 0 ? Long.MAX_VALUE
                : timeoutNanos - (System.nanoTime() - startTime);
    }

    private RuntimeException startupTimeout() {
        return new RuntimeException(
                String.format("Não foi possível conectar ao Chrome em %.1f segundos", timeout));
    }

    private void discardFailedSocket(CDPSocket candidate) {
        if (candidate == null) {
            return;
        }
        try {
            candidate.close();
        } catch (Exception ignored) {
            // A tentativa já falhou; o próximo ciclo ainda precisa poder continuar.
        }
        if (socket == candidate) {
            socket = null;
        }
    }
    
    /**
     * Fecha a conexão com o Chrome.
     *
     * @return CompletableFuture que completa quando o fechamento termina
     */
    public CompletableFuture<Void> close() {
        return CompletableFuture.runAsync(() -> {
            if (socket != null && socket.isConnected()) {
                try {
                    socket.closeAsync().join();
                } catch (CDPException e) {
                    // Ignorar erros específicos
                    if (e.getCode() == -32000
                            && e.hasKind(CDPException.Kind.TOP_LEVEL_TARGET_REQUIRED)) {
                        // OK, ignorar
                    } else {
                        throw e;
                    }
                } catch (Exception e) {
                    // Ignorar erros de conexão fechada
                }
            }
        });
    }
    
    /**
     * Aguarda por um evento CDP.
     *
     * @param event nome do evento (ex: "Page.loadEventFired")
     * @param timeout timeout em segundos (null para usar o padrão)
     * @return CompletableFuture com os parâmetros do evento
     */
    public CompletableFuture<JsonNode> waitForCdp(String event, Float timeout) {
        return init().thenCompose(t -> socket.waitFor(event, timeout));
    }
    
    /**
     * Adiciona um listener para um evento CDP.
     *
     * @param event nome do evento
     * @param callback função a ser chamada quando o evento ocorrer
     * @return CompletableFuture que completa quando o listener é adicionado
     */
    public CompletableFuture<Void> addCdpListener(String event, Consumer<JsonNode> callback) {
        return init().thenAccept(t -> socket.addListener(event, callback));
    }

    public CompletableFuture<Void> addCdpSessionListener(
            String event, BiConsumer<String, JsonNode> callback) {
        return init().thenAccept(t -> socket.addSessionListener(event, callback));
    }
    
    /**
     * Remove um listener de um evento CDP.
     *
     * @param event nome do evento
     * @param callback função a ser removida
     * @return CompletableFuture que completa quando o listener é removido
     */
    public CompletableFuture<Void> removeCdpListener(String event, Consumer<JsonNode> callback) {
        return init().thenAccept(t -> socket.removeListener(event, callback));
    }
    
    /**
     * Obtém um iterador de eventos CDP.
     *
     * @param event nome do evento
     * @return CompletableFuture com a fila de eventos
     */
    public CompletableFuture<java.util.concurrent.BlockingQueue<JsonNode>> getCdpEventIter(String event) {
        return init().thenApply(t -> socket.methodIterator(event));
    }
    
    /**
     * Executa um comando CDP e obtém o resultado retornado.
     *
     * @param cmd nome do comando CDP (ex: "Page.navigate")
     * @param cmdArgs argumentos do comando
     * @param timeout timeout em segundos (null para usar o padrão 10s)
     * @return CompletableFuture com o resultado do comando
     */
    public CompletableFuture<JsonNode> executeCdpCmd(String cmd, Map<String, Object> cmdArgs, Float timeout) {
        return init().thenCompose(t -> {
            // Processar comandos especiais
            if ("Browser.setDownloadBehavior".equals(cmd) && cmdArgs != null) {
                Object path = cmdArgs.get("downloadPath");
                if (path != null) {
                    String contextId = cmdArgs.containsKey("browserContextId") ? 
                        cmdArgs.get("browserContextId").toString() : "DEFAULT";
                    downloadsPaths.put(contextId, path.toString());
                }
            }
            
            Float effectiveTimeout = (timeout != null) ? timeout : 10.0f;
            return socket.exec(cmd, cmdArgs, effectiveTimeout);
        });
    }

    /**
     * Executa um comando CDP roteado para uma sessão anexada (flatten mode), por exemplo a
     * sessão de um worker/service worker obtida via {@code Target.attachedToTarget} sobre esta
     * conexão browser-level.
     *
     * @param cmd nome do comando CDP
     * @param cmdArgs argumentos do comando
     * @param timeout timeout em segundos (null para o padrão 10s)
     * @param sessionId sessionId da sessão anexada; null para a sessão atual
     * @return CompletableFuture com o resultado do comando
     */
    public CompletableFuture<JsonNode> executeCdpCmd(String cmd, Map<String, Object> cmdArgs, Float timeout, String sessionId) {
        return init().thenCompose(t -> {
            Float effectiveTimeout = (timeout != null) ? timeout : 10.0f;
            return socket.exec(cmd, cmdArgs, effectiveTimeout, sessionId);
        });
    }
    
    /**
     * Obtém o diretório de downloads padrão para um contexto específico.
     *
     * @param contextId ID do contexto
     * @return caminho do diretório de downloads
     */
    public String downloadsDirForContext(String contextId) {
        return downloadsPaths.get(contextId != null ? contextId : "DEFAULT");
    }
    
    @Override
    public String toString() {
        return String.format("<%s.%s (target_id=\"%s\", host=\"%s\")>",
            getClass().getPackage().getName(),
            getClass().getSimpleName(),
            id,
            host
        );
    }
}

