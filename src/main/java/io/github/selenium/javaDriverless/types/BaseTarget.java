package io.github.selenium.javaDriverless.types;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import io.github.selenium.javaDriverless.cdp.CDPSocket;
import io.github.selenium.javaDriverless.cdp.exceptions.CDPException;

import org.asynchttpclient.AsyncHttpClient;
import org.asynchttpclient.DefaultAsyncHttpClient;

import java.io.IOException;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/**
 * O BaseTarget para a instância do Chrome
 * representa uma conexão com o navegador inteiro.
 * <p>
 * <strong>Nota:</strong> comandos executados no BaseTarget geralmente estão em um escopo
 * global sobre toda a instância do Chrome. Infelizmente, nem todos são suportados.
 * </p>
 */
public class BaseTarget {
    
    protected CDPSocket socket;
    protected final boolean isRemote;
    protected final String host;
    protected final String id;
    protected final float timeout;
    protected final int maxWsSize;
    protected final Map<String, String> downloadsPaths;
    
    protected boolean started = false;
    
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
        this.downloadsPaths = new HashMap<>();
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
     * Inicializa a conexão com o Chrome de forma assíncrona.
     *
     * @return CompletableFuture que completa quando a inicialização termina
     */
    public CompletableFuture<BaseTarget> init() {
        if (started) {
            return CompletableFuture.completedFuture(this);
        }
        
        return CompletableFuture.supplyAsync(() -> {
            long startTime = System.nanoTime();
            String url = "http://" + host + "/json/version";
            AsyncHttpClient httpClient = new DefaultAsyncHttpClient();
            
            try {
                while (true) {
                    try {
                        String response = httpClient.prepareGet(url)
                            .execute()
                            .toCompletableFuture()
                            .get(10, TimeUnit.SECONDS)
                            .getResponseBody();
                        
                        // Parsear JSON para obter webSocketDebuggerUrl
                        ObjectMapper mapper = new ObjectMapper();
                        JsonNode json = mapper.readTree(response);
                        String wsUrl = json.get("webSocketDebuggerUrl").asText();
                        
                        // Criar socket CDP
                        this.socket = new CDPSocket(wsUrl, timeout, maxWsSize);
                        this.socket.connectAsync().join();
                        this.started = true;
                        
                        // Fechar cliente HTTP antes de retornar
                        try {
                            httpClient.close();
                        } catch (Exception ignored) {}
                        return this;
                        
                    } catch (Exception e) {
                        double elapsed = (System.nanoTime() - startTime) / 1_000_000_000.0;
                        if (elapsed > timeout) {
                            // Fechar cliente HTTP antes de lançar exceção
                            try {
                                httpClient.close();
                            } catch (IOException ignored) {}
                            throw new RuntimeException(
                                String.format("Não foi possível conectar ao Chrome em %.1f segundos", timeout)
                            );
                        }
                        // Aguardar um pouco antes de tentar novamente
                        try {
                            Thread.sleep(100);
                        } catch (InterruptedException ie) {
                            Thread.currentThread().interrupt();
                            // Fechar cliente HTTP antes de lançar exceção
                            try {
                                httpClient.close();
                            } catch (IOException ignored) {}
                            throw new RuntimeException("Conexão interrompida", ie);
                        }
                    }
                }
            } catch (RuntimeException e) {
                // Re-lançar RuntimeExceptions (já tratadas acima)
                throw e;
            } catch (Exception e) {
                // Garantir que cliente HTTP seja fechado em qualquer exceção
                try {
                    httpClient.close();
                } catch (IOException ignored) {}
                throw new RuntimeException("Erro ao inicializar conexão com Chrome", e);
            }
        });
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
                    if (e.getCode() == -32000 && 
                        "Command can only be executed on top-level targets".equals(e.getCdpMessage())) {
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

