package io.github.selenium.javaDriverless.types;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import io.github.selenium.javaDriverless.cdp.CDPSocket;
import io.github.selenium.javaDriverless.cdp.exceptions.CDPException;
import io.github.selenium.javaDriverless.input.Pointer;
import io.github.selenium.javaDriverless.types.TypesExceptions.NoSuchIframe;

import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Consumer;
import java.util.Base64;

/**
 * A classe Target representa uma aba, frame, iframe CORS, WebWorker, etc.
 * <p>
 * Esta é uma das classes mais importantes do framework, fornecendo métodos para:
 * - Navegação (get, back, forward, refresh)
 * - Execução de JavaScript
 * - Busca de elementos
 * - Cookies
 * - Screenshots
 * - Interações com a página
 * </p>
 */
public class Target {
    
    private static final ObjectMapper objectMapper = new ObjectMapper();
    
    // Mapeamento de teclas para eventos CDP
    private static final Map<Character, KeyInfo> KEY_MAPPING = createKeyMapping();
    private static final String SHIFT_KEY_NEEDED = "~!@#$%^&*()_+{}|:\"<>?";
    
    private static class KeyInfo {
        String code;
        int keyCode;
        
        KeyInfo(String code, int keyCode) {
            this.code = code;
            this.keyCode = keyCode;
        }
    }
    
    // Campos de instância
    private Target parentTarget;
    private final Object context;
    private Integer windowId;
    private Pointer pointer;
    private Boolean pageEnabled;
    private Boolean domEnabled;
    private final int maxWsSize;
    
    private final Map<String, Object> globalThis = new ConcurrentHashMap<>();
    private Object documentElem;
    private Object alert;
    
    private CDPSocket socket;
    private String isolatedContextId;
    private String execContextId;
    private final Map<String, Target> targets = new ConcurrentHashMap<>();
    
    private final boolean isRemote;
    private final String host;
    private final String id;
    private String contextId;
    private final String type;
    private final float timeout;
    
    private final boolean startSocket;
    private final List<Runnable> onClosed = new ArrayList<>();
    
    private final Object driver;
    private final ReentrantLock sendKeyLock = new ReentrantLock();
    
    /**
     * Cria um novo Target.
     *
     * @param host endereço do host (ex: "localhost:9222")
     * @param targetId ID do target
     * @param driver instância do driver
     * @param context contexto do navegador
     * @param isRemote se é conexão remota
     * @param timeout timeout em segundos
     * @param type tipo do target
     * @param startSocket se deve iniciar socket automaticamente
     * @param maxWsSize tamanho máximo de mensagem WebSocket
     */
    public Target(String host, String targetId, Object driver, Object context,
                 boolean isRemote, float timeout, String type, boolean startSocket, int maxWsSize) {
        this.host = host;
        this.id = targetId;
        this.driver = driver;
        this.context = context;
        this.isRemote = isRemote;
        this.timeout = timeout;
        this.type = type;
        this.startSocket = startSocket;
        this.maxWsSize = maxWsSize;
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
     * Obtém se o target está attached (tem cliente conectado).
     *
     * @return CompletableFuture com true se attached
     */
    public CompletableFuture<Boolean> getAttached() {
        return getInfo().thenApply(info -> (Boolean) info.get("attached"));
    }
    
    /**
     * Obtém o ID do opener (target que abriu este target).
     *
     * @return CompletableFuture com o opener ID
     */
    public CompletableFuture<String> getOpenerId() {
        return getInfo().thenApply(info -> (String) info.get("openerId"));
    }
    
    /**
     * Obtém o frame ID do opener.
     *
     * @return CompletableFuture com o opener frame ID
     */
    public CompletableFuture<String> getOpenerFrameId() {
        return getInfo().thenApply(info -> (String) info.get("openerFrameId"));
    }
    
    /**
     * Obtém se o target pode acessar o opener.
     *
     * @return CompletableFuture com true se pode acessar
     */
    public CompletableFuture<Boolean> getCanAccessOpener() {
        return getInfo().thenApply(info -> (Boolean) info.get("canAccessOpener"));
    }
    
    /**
     * Obtém o subtipo do target (ex: "portal", "prerender").
     *
     * @return CompletableFuture com o subtipo
     */
    public CompletableFuture<String> getSubtype() {
        return getInfo().thenApply(info -> (String) info.get("subtype"));
    }
    
    /**
     * Retorna o socket CDP.
     *
     * @return socket CDP
     */
    public CDPSocket getSocket() {
        return socket;
    }
    
    /**
     * Retorna o ponteiro para interações.
     *
     * @return ponteiro
     */
    public Pointer getPointer() {
        if (pointer == null) {
            pointer = new Pointer(this);
        }
        return pointer;
    }
    
    /**
     * Inicializa o target e a conexão WebSocket.
     *
     * @return CompletableFuture com o target inicializado
     */
    public CompletableFuture<Target> init() {
        if (socket != null) {
            return CompletableFuture.completedFuture(this);
        }
        
        return CompletableFuture.supplyAsync(() -> {
            String wsUrl = String.format("ws://%s/devtools/page/%s", host, id);
            socket = new CDPSocket(wsUrl, timeout, maxWsSize);
            socket.connectAsync().join();
            
            pointer = new Pointer(this);
            
            // Configurar listeners de eventos
            addCdpListener("Page.javascriptDialogOpening", params -> {
                // TODO: Criar objeto Alert
                this.alert = params;
            }).join();
            
            addCdpListener("Page.javascriptDialogClosed", params -> {
                this.alert = null;
            }).join();
            
            addCdpListener("Page.loadEventFired", params -> onLoaded()).join();
            addCdpListener("Page.windowOpen", params -> onLoaded()).join();
            
            // Adicionar callbacks de fechamento
            socket.getOnClosed().addAll(onClosed);
            
            return this;
        });
    }
    
    /**
     * Callback executado quando a página é carregada.
     */
    private void onLoaded() {
        globalThis.clear();
        documentElem = null;
        isolatedContextId = null;
        execContextId = null;
    }
    
    /**
     * Navega para uma URL.
     *
     * @param url URL para navegar
     * @param referrer referrer opcional
     * @param waitLoad se deve aguardar o carregamento
     * @param timeout timeout em segundos
     * @return CompletableFuture com dados do resultado (ou download)
     */
    public CompletableFuture<Map<String, Object>> get(String url, String referrer, 
                                                       boolean waitLoad, float timeout) {
        boolean shouldWaitLoad = waitLoad;
        
        if ("about:blank".equals(url)) {
            shouldWaitLoad = false;
        }
        
        // Lidar com fragmentos (#)
        if (url.contains("#") && shouldWaitLoad) {
            final String finalUrl = url;
            return getCurrentUrl().thenCompose(currentUrl -> {
                String currentBase = currentUrl.split("#")[0];
                String urlBase = finalUrl.split("#")[0];
                
                String navUrl = finalUrl;
                boolean skipWait = false;
                if (finalUrl.startsWith("#")) {
                    navUrl = currentBase + finalUrl;
                    skipWait = true;
                } else if (urlBase.equals(currentBase)) {
                    skipWait = true;
                }
                
                return navigateInternal(navUrl, referrer, !skipWait, timeout);
            });
        }
        
        return navigateInternal(url, referrer, shouldWaitLoad, timeout);
    }
    
    /**
     * Navega para uma URL (versão simplificada).
     *
     * @param url URL para navegar
     * @param waitLoad se deve aguardar o carregamento
     * @return CompletableFuture que completa quando a navegação termina
     */
    public CompletableFuture<Map<String, Object>> get(String url, boolean waitLoad) {
        return get(url, null, waitLoad, timeout);
    }
    
    /**
     * Implementação interna da navegação.
     */
    private CompletableFuture<Map<String, Object>> navigateInternal(String url, String referrer,
                                                                     boolean waitLoad, float timeout) {
        CompletableFuture<Map<String, Object>> result = new CompletableFuture<>();
        
        // Usar timeout fornecido ou padrão
        float actualTimeout = timeout > 0 ? timeout : this.timeout;
        Float timeoutObj = actualTimeout;
        
        // Habilitar Page domain se necessário (deve ser feito antes da navegação)
        CompletableFuture<Void> enableFuture = CompletableFuture.completedFuture(null);
        if (waitLoad) {
            if (pageEnabled == null || !pageEnabled) {
                enableFuture = executeCdpCmd("Page.enable", null, timeoutObj)
                    .thenAccept(enabled -> pageEnabled = true);
            }
        }
        
        // Preparar argumentos de navegação
        Map<String, Object> args = new HashMap<>();
        args.put("url", url);
        args.put("transitionType", "link");
        if (referrer != null) {
            args.put("referrer", referrer);
        }
        
        if (waitLoad) {
            // Primeiro habilitar Page, depois agendar espera pelo evento, depois navegar
            return enableFuture
                .thenCompose(enabled -> {
                    // IMPORTANTE: Agendar espera pelo evento ANTES de navegar
                    CompletableFuture<JsonNode> loadFuture = waitForCdp("Page.loadEventFired", actualTimeout);
                    
                    // Navegar - isso vai disparar o evento que estamos aguardando
                    CompletableFuture<JsonNode> navFuture = executeCdpCmd("Page.navigate", args, timeoutObj);
                    
                    // Aguardar que a navegação seja iniciada e depois esperar o evento de carregamento
                    return navFuture
                        .thenCompose(navResult -> loadFuture)
                        .thenApply(data -> {
                            Map<String, Object> resultMap = objectMapper.convertValue(data, Map.class);
                            onLoaded();
                            return resultMap;
                        });
                })
                .orTimeout((long) (actualTimeout * 1000), TimeUnit.MILLISECONDS);
        } else {
            // Navegar sem aguardar carregamento
            return enableFuture
                .thenCompose(enabled -> executeCdpCmd("Page.navigate", args, timeoutObj))
                .thenApply(navResult -> {
                    onLoaded();
                    return new HashMap<String, Object>();
                });
        }
    }
    
    /**
     * Volta para a página anterior.
     *
     * @return CompletableFuture que completa quando a ação termina
     */
    public CompletableFuture<Void> back() {
        // Usar executeScript para navegar para trás no histórico
        return executeScript("window.history.back()", null, false)
            .thenApply(v -> {
                onLoaded();
                return null;
            });
    }
    
    /**
     * Avança para a próxima página.
     *
     * @return CompletableFuture que completa quando a ação termina
     */
    public CompletableFuture<Void> forward() {
        // Usar executeScript para navegar para frente no histórico
        return executeScript("window.history.forward()", null, false)
            .thenApply(v -> {
                onLoaded();
                return null;
            });
    }
    
    /**
     * Recarrega a página.
     *
     * @param ignoreCache se deve ignorar o cache
     * @return CompletableFuture que completa quando a ação termina
     */
    public CompletableFuture<Void> refresh(boolean ignoreCache) {
        Map<String, Object> args = new HashMap<>();
        if (ignoreCache) {
            args.put("ignoreCache", true);
        }
        return executeCdpCmd("Page.reload", args, null)
            .thenAccept(v -> onLoaded());
    }
    
    /**
     * Retorna a URL atual.
     *
     * @return CompletableFuture com a URL
     */
    public CompletableFuture<String> getCurrentUrl() {
        return executeScript("document.location.href", null, true)
            .thenApply(result -> result.toString());
    }
    
    /**
     * Retorna o título da página.
     *
     * @return CompletableFuture com o título
     */
    public CompletableFuture<String> getTitle() {
        return executeScript("document.title", null, true)
            .thenApply(result -> result != null ? result.toString() : "");
    }
    
    /**
     * Retorna o código-fonte da página.
     *
     * @return CompletableFuture com o HTML
     */
    public CompletableFuture<String> getPageSource() {
        return executeScript("document.documentElement.outerHTML", null, true)
            .thenApply(result -> result != null ? result.toString() : "");
    }
    
    /**
     * Executa JavaScript na página.
     *
     * @param script código JavaScript
     * @param args argumentos opcionais
     * @param awaitPromise se deve aguardar promises
     * @return CompletableFuture com o resultado
     */
    public CompletableFuture<Object> executeScript(String script, Object[] args, boolean awaitPromise) {
        return executeScript(script, args, awaitPromise, null);
    }
    
    /**
     * Executa JavaScript na página com suporte a serialização deep.
     *
     * @param script código JavaScript
     * @param args argumentos opcionais
     * @param awaitPromise se deve aguardar promises
     * @param serialization tipo de serialização ("deep" para retornar WebElements)
     * @return CompletableFuture com o resultado
     */
    public CompletableFuture<Object> executeScript(String script, Object[] args, boolean awaitPromise, String serialization) {
        Map<String, Object> cdpArgs = new HashMap<>();
        cdpArgs.put("expression", script);
        cdpArgs.put("awaitPromise", awaitPromise);
        
        // Se tem serialização deep, NÃO usar returnByValue
        if ("deep".equals(serialization)) {
            cdpArgs.put("returnByValue", false);
            Map<String, Object> serOpts = new HashMap<>();
            serOpts.put("serialization", "deep");
            serOpts.put("maxDepth", 2);
            cdpArgs.put("serializationOptions", serOpts);
        } else {
            cdpArgs.put("returnByValue", true);
        }
        
        if (execContextId != null && !execContextId.isEmpty()) {
            cdpArgs.put("contextId", execContextId);
        }
        
        return executeCdpCmd("Runtime.evaluate", cdpArgs, null)
            .thenApply(result -> {
                if (result.has("exceptionDetails")) {
                    throw new RuntimeException("JavaScript error: " + 
                        result.get("exceptionDetails").toString());
                }
                
                if (result.has("result")) {
                    JsonNode resultNode = result.get("result");
                    
                    // Se tem deepSerializedValue, processar (retorna WebElements)
                    if (resultNode.has("deepSerializedValue")) {
                        return parseDeepSerializedValue(resultNode.get("deepSerializedValue"));
                    }
                    
                    // Senão, retornar value normal
                    if (resultNode.has("value")) {
                        return objectMapper.convertValue(resultNode.get("value"), Object.class);
                    }
                }
                
                return null;
            });
    }
    
    /**
     * Parseia deepSerializedValue do CDP para WebElements.
     */
    private Object parseDeepSerializedValue(JsonNode deepValue) {
        if (!deepValue.has("type")) {
            return null;
        }
        
        String type = deepValue.get("type").asText();
        
        // Array/List de elementos
        if (deepValue.has("value") && deepValue.get("value").isArray()) {
            List<Object> list = new ArrayList<>();
            for (JsonNode item : deepValue.get("value")) {
                if (item.has("type") && "node".equals(item.get("type").asText())) {
                    JsonNode nodeValue = item.get("value");
                    if (nodeValue != null && nodeValue.has("backendNodeId")) {
                        int backendNodeId = nodeValue.get("backendNodeId").asInt();
                        WebElement elem = new WebElement(this, null, null,
                            null, null, backendNodeId, null, false);
                        list.add(elem);
                    }
                } else {
                    list.add(parseDeepSerializedValue(item));
                }
            }
            return list;
        }
        
        // Nó único
        if ("node".equals(type) && deepValue.has("value")) {
            JsonNode nodeValue = deepValue.get("value");
            if (nodeValue.has("backendNodeId")) {
                int backendNodeId = nodeValue.get("backendNodeId").asInt();
                return new WebElement(this, null, null,
                    null, null, backendNodeId, null, false);
            }
        }
        
        // Valores primitivos
        if (deepValue.has("value")) {
            JsonNode valueNode = deepValue.get("value");
            if (valueNode.isValueNode()) {
                if (valueNode.isBoolean()) return valueNode.asBoolean();
                if (valueNode.isInt()) return valueNode.asInt();
                if (valueNode.isLong()) return valueNode.asLong();
                if (valueNode.isDouble()) return valueNode.asDouble();
                if (valueNode.isTextual()) return valueNode.asText();
            }
        }
        
        return null;
    }
    
    /**
     * Executa script JavaScript raw (forma bruta) no GlobalThis.
     *
     * @param script script JavaScript
     * @param awaitRes se deve aguardar resultado
     * @param serialization tipo de serialização (null, "deep", "json", "idOnly")
     * @param args argumentos para o script
     * @return CompletableFuture com o resultado
     */
    public CompletableFuture<Object> executeRawScript(String script, boolean awaitRes, 
                                                      String serialization, Object... args) {
        return getGlobalThis(null).thenCompose(globalThis -> {
            // Em uma implementação completa, usaríamos JSRemoteObj.__exec_raw__
            // Por ora, usamos Runtime.callFunctionOn
            Map<String, Object> cdpArgs = new HashMap<>();
            cdpArgs.put("functionDeclaration", script);
            cdpArgs.put("awaitPromise", awaitRes);
            
            if (serialization != null) {
                cdpArgs.put("serializationOptions", Map.of("serialization", serialization));
            }
            
            if (isolatedContextId != null) {
                cdpArgs.put("executionContextId", isolatedContextId);
            }
            
            return executeCdpCmd("Runtime.callFunctionOn", cdpArgs, null)
                .thenApply(result -> {
                    if (result.has("result")) {
                        JsonNode resultNode = result.get("result");
                        if (resultNode.has("value")) {
                            return objectMapper.convertValue(resultNode.get("value"), Object.class);
                        }
                    }
                    return null;
                });
        });
    }
    
    /**
     * Executa script JavaScript assíncrono no GlobalThis.
     * Similar a execute_async_script do Python.
     *
     * @param script script JavaScript
     * @param maxDepth profundidade máxima de serialização
     * @param serialization tipo de serialização
     * @param args argumentos para o script
     * @return CompletableFuture com o resultado
     */
    public CompletableFuture<Object> executeAsyncScript(String script, int maxDepth, 
                                                        String serialization, Object... args) {
        return CompletableFuture.supplyAsync(() -> {
            long startNanos = System.nanoTime();
            float timeout = 2.0f;
            
            while (true) {
                try {
                    return getGlobalThis(null).thenCompose(globalThis -> {
                        Map<String, Object> cdpArgs = new HashMap<>();
                        cdpArgs.put("functionDeclaration", 
                            "async function() { " + script + " }");
                        cdpArgs.put("awaitPromise", true);
                        cdpArgs.put("returnByValue", true);
                        
                        if (isolatedContextId != null) {
                            cdpArgs.put("executionContextId", isolatedContextId);
                        }
                        
                        return executeCdpCmd("Runtime.callFunctionOn", cdpArgs, null)
                            .thenApply(result -> {
                                if (result.has("result")) {
                                    JsonNode resultNode = result.get("result");
                                    if (resultNode.has("value")) {
                                        return objectMapper.convertValue(resultNode.get("value"), Object.class);
                                    }
                                }
                                return null;
                            });
                    }).join();
                } catch (Exception e) {
                    // StaleJSRemoteObjReference - tentar novamente
                    double elapsed = (System.nanoTime() - startNanos) / 1_000_000_000.0;
                    if (elapsed > timeout) {
                        throw new RuntimeException(
                            "Não foi possível executar script devido a referência stale dentro de " + 
                            timeout + "s, possivelmente devido a loop de reload", e);
                    }
                }
            }
        });
    }
    
    /**
     * Avalia expressão JavaScript assíncrona com await.
     * Permite usar await diretamente no código.
     *
     * @param script expressão JavaScript (pode usar await)
     * @param timeout timeout em segundos
     * @param uniqueContext se deve usar contexto isolado
     * @param args argumentos
     * @return CompletableFuture com o resultado
     */
    public CompletableFuture<Object> evalAsync(String script, float timeout, 
                                               boolean uniqueContext, Object... args) {
        return CompletableFuture.supplyAsync(() -> {
            long startNanos = System.nanoTime();
            
            while (true) {
                try {
                    String contextId = uniqueContext && isolatedContextId != null ? 
                        isolatedContextId : null;
                    
                    return getGlobalThis(contextId).thenCompose(globalThis -> {
                        // Wrapper para permitir await no código
                        String wrappedScript = "(async function() { " + script + " })()";
                        
                        Map<String, Object> cdpArgs = new HashMap<>();
                        cdpArgs.put("expression", wrappedScript);
                        cdpArgs.put("awaitPromise", true);
                        cdpArgs.put("returnByValue", true);
                        
                        if (contextId != null) {
                            cdpArgs.put("executionContextId", contextId);
                        }
                        
                        return executeCdpCmd("Runtime.evaluate", cdpArgs, null)
                            .thenApply(result -> {
                                if (result.has("exceptionDetails")) {
                                    throw new RuntimeException("JavaScript error: " + 
                                        result.get("exceptionDetails").toString());
                                }
                                
                                if (result.has("result")) {
                                    JsonNode resultNode = result.get("result");
                                    if (resultNode.has("value")) {
                                        return objectMapper.convertValue(resultNode.get("value"), Object.class);
                                    }
                                }
                                return null;
                            });
                    }).join();
                } catch (Exception e) {
                    double elapsed = (System.nanoTime() - startNanos) / 1_000_000_000.0;
                    if (elapsed > timeout) {
                        throw new RuntimeException(
                            "Não foi possível executar script dentro de " + timeout + "s", e);
                    }
                }
            }
        });
    }
    
    /**
     * Obtém o GlobalThis object para execução de JavaScript.
     *
     * @param contextId ID do contexto (opcional)
     * @return CompletableFuture com o objeto GlobalThis
     */
    private CompletableFuture<Map<String, Object>> getGlobalThis(String contextId) {
        String key = contextId != null ? contextId : "default";
        
        if (globalThis.containsKey(key)) {
            @SuppressWarnings("unchecked")
            Map<String, Object> cached = (Map<String, Object>) globalThis.get(key);
            return CompletableFuture.completedFuture(cached);
        }
        
        Map<String, Object> args = new HashMap<>();
        args.put("expression", "globalThis");
        
        if (contextId != null) {
            args.put("executionContextId", contextId);
        }
        
        return executeCdpCmd("Runtime.evaluate", args, null)
            .thenApply(result -> {
                Map<String, Object> globalThisObj = new HashMap<>();
                if (result.has("result")) {
                    JsonNode resultNode = result.get("result");
                    globalThisObj.put("objectId", resultNode.get("objectId").asText());
                }
                globalThis.put(key, globalThisObj);
                return globalThisObj;
            });
    }
    
    /**
     * Obtém o alerta JavaScript atual (alert, confirm, prompt).
     *
     * @param timeout timeout em segundos
     * @return CompletableFuture com o Alert
     */
    public CompletableFuture<Alert> getAlert(float timeout) {
        if (pageEnabled == null || !pageEnabled) {
            return executeCdpCmd("Page.enable", null, timeout)
                .thenCompose(v -> {
                    pageEnabled = true;
                    return CompletableFuture.completedFuture(new Alert(this, timeout));
                });
        }
        return CompletableFuture.completedFuture(new Alert(this, timeout));
    }
    
    /**
     * Retorna o elemento documento (raiz).
     *
     * @return CompletableFuture com o WebElement do documento
     */
    private CompletableFuture<WebElement> getDocumentElem() {
        if (documentElem != null) {
            return CompletableFuture.completedFuture((WebElement) documentElem);
        }
        
        return executeCdpCmd("DOM.getDocument", Map.of("pierce", true), null)
            .thenApply(result -> {
                int nodeId = result.get("root").get("nodeId").asInt();
                WebElement elem = new WebElement(this, null, null, 
                    null, nodeId, null, null, false);
                documentElem = elem;
                return elem;
            });
    }
    
    /**
     * Busca um elemento na página.
     *
     * @param by estratégia de busca (ex: By.XPATH)
     * @param value valor da busca
     * @param timeout timeout em segundos
     * @return CompletableFuture com o elemento encontrado
     */
    public CompletableFuture<WebElement> findElement(String by, String value, Float timeout) {
        return CompletableFuture.supplyAsync(() -> {
            long startNanos = System.nanoTime();
            WebElement elem = null;
            float effectiveTimeout = (timeout != null) ? timeout : 10.0f;
            
            while (elem == null) {
                try {
                    WebElement parent = getDocumentElem().join();
                    // Passar 5 segundos de timeout para o WebElement para evitar loop infinito
                    elem = parent.findElement(by, value, 5.0f).join();
                } catch (Exception e) {
                    // StaleElement ou NoSuchElement - recarregar
                    onLoaded();
                }
                
                double elapsed = (System.nanoTime() - startNanos) / 1_000_000_000.0;
                if (elapsed > effectiveTimeout) {
                    break;
                }
                
                if (elem == null) {
                    try {
                        Thread.sleep(10);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        break;
                    }
                }
            }
            
            if (elem == null) {
                throw new WebElement.NoSuchElementException(
                    String.format("Elemento não encontrado: %s='%s'", by, value)
                );
            }
            
            return elem;
        });
    }
    
    /**
     * Busca múltiplos elementos na página.
     *
     * @param by estratégia de busca
     * @param value valor da busca
     * @param timeout timeout em segundos
     * @return CompletableFuture com a lista de elementos
     */
    public CompletableFuture<List<WebElement>> findElements(String by, String value, float timeout) {
        return CompletableFuture.supplyAsync(() -> {
            long startNanos = System.nanoTime();
            
            while (true) {
                try {
                    WebElement parent = getDocumentElem().join();
                    return parent.findElements(by, value).join();
                } catch (Exception e) {
                    // StaleElement - recarregar
                    onLoaded();
                }
                
                double elapsed = (System.nanoTime() - startNanos) / 1_000_000_000.0;
                if (elapsed > timeout) {
                    throw new RuntimeException(
                        String.format("Não foi possível encontrar elementos em %.1f segundos " +
                            "devido a loop de reload do target", timeout)
                    );
                }
                
                try {
                    Thread.sleep(10);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new RuntimeException("Interrompido durante busca de elementos");
                }
            }
        });
    }
    
    /**
     * Busca elementos usando pesquisa de texto, CSS ou XPath.
     *
     * @param query texto plano, seletor CSS ou XPath
     * @return CompletableFuture com a lista de elementos
     */
    public CompletableFuture<List<WebElement>> searchElements(String query) {
        if (domEnabled == null || !domEnabled) {
            executeCdpCmd("DOM.enable", null, null).join();
            domEnabled = true;
        }
        
        // Garantir que DOM.getDocument foi chamado
        getDocumentElem().join();
        
        Map<String, Object> searchArgs = new HashMap<>();
        searchArgs.put("includeUserAgentShadowDOM", true);
        searchArgs.put("query", query);
        
        return executeCdpCmd("DOM.performSearch", searchArgs, null)
            .thenCompose(result -> {
                String searchId = result.get("searchId").asText();
                int elemCount = result.get("resultCount").asInt();
                
                if (elemCount <= 0) {
                    return CompletableFuture.completedFuture(new ArrayList<WebElement>());
                }
                
                Map<String, Object> getResultsArgs = new HashMap<>();
                getResultsArgs.put("searchId", searchId);
                getResultsArgs.put("fromIndex", 0);
                getResultsArgs.put("toIndex", elemCount);
                
                return executeCdpCmd("DOM.getSearchResults", getResultsArgs, null)
                    .thenApply(res -> {
                        List<WebElement> elems = new ArrayList<>();
                        JsonNode nodeIds = res.get("nodeIds");
                        
                        for (JsonNode nodeIdNode : nodeIds) {
                            int nodeId = nodeIdNode.asInt();
                            WebElement elem = new WebElement(this, null, null, null, 
                                nodeId, null, null, false);
                            elems.add(elem);
                        }
                        
                        return elems;
                    });
            });
    }
    
    /**
     * Encontra targets para uma lista de iframes.
     * 
     * AVISO: Apenas iframes CORS têm seu próprio target.
     * Para iframes same-origin, use WebElement.getContentDocument() ao invés disso.
     *
     * @param iframes lista de elementos iframe
     * @return CompletableFuture com lista de targets
     */
    public CompletableFuture<List<Target>> getTargetsForIframes(List<WebElement> iframes) {
        if (iframes == null || iframes.isEmpty()) {
            return CompletableFuture.failedFuture(
                new IllegalArgumentException("Esperado WebElements, mas recebeu vazio/nulo")
            );
        }
        
        return CompletableFuture.supplyAsync(() -> {
            try {
                // Obter todos os targets do tipo iframe
                Map<String, Object> args = new HashMap<>();
                if (contextId != null) {
                    args.put("browserContextId", contextId);
                }
                
                List<Target> foundTargets = new ArrayList<>();
                
                // Obter targets via CDP
                JsonNode targetsResult = executeCdpCmd("Target.getTargets", args, null).join();
                JsonNode targetInfos = targetsResult.get("targetInfos");
                
                if (targetInfos != null && targetInfos.isArray()) {
                    for (JsonNode targetInfo : targetInfos) {
                        String targetType = targetInfo.get("type").asText();
                        if ("iframe".equals(targetType)) {
                            String targetId = targetInfo.get("targetId").asText();
                            
                            // Criar target (simplificado - em produção usar factory)
                            Target iframeTarget = new Target(
                                host, targetId, driver, context,
                                isRemote, timeout, "iframe", false, maxWsSize
                            );
                            
                            // Obter base frame do target
                            Map<String, Object> baseFrame = iframeTarget.getBaseFrame().join();
                            String targetFrameId = (String) baseFrame.get("id");
                            
                            // Verificar se algum iframe corresponde
                            for (WebElement iframe : iframes) {
                                try {
                                    String tagName = iframe.getTagName().join();
                                    if (!"IFRAME".equalsIgnoreCase(tagName)) {
                                        throw new NoSuchIframe(iframe, "elemento não é um iframe");
                                    }
                                    
                                    // Obter frame ID do iframe
                                    String iframeFrameId = iframe.getFrameId().join();
                                    
                                    if (targetFrameId.equals(iframeFrameId)) {
                                        if ("iframe".equals(type)) {
                                            iframeTarget.parentTarget = this;
                                        }
                                        foundTargets.add(iframeTarget);
                                        break;
                                    }
                                } catch (Exception e) {
                                    // Ignorar erro e continuar
                                }
                            }
                        }
                    }
                }
                
                return foundTargets;
            } catch (Exception e) {
                throw new RuntimeException("Erro ao obter targets para iframes: " + e.getMessage(), e);
            }
        });
    }
    
    /**
     * Encontra target para um iframe específico.
     *
     * AVISO: Apenas iframes CORS têm seu próprio target.
     *
     * @param iframe elemento iframe
     * @return CompletableFuture com o target do iframe
     */
    public CompletableFuture<Target> getTargetForIframe(WebElement iframe) {
        return getTargetsForIframes(List.of(iframe)).thenApply(targets -> {
            if (targets.isEmpty()) {
                throw new NoSuchIframe(iframe, 
                    "Nenhum target encontrado para o iframe. Pode ser same-origin - use getContentDocument()");
            }
            return targets.get(0);
        });
    }
    
    /**
     * Captura screenshot da janela atual como PNG.
     *
     * @return CompletableFuture com os bytes da imagem PNG
     */
    public CompletableFuture<byte[]> getScreenshotAsPng() {
        Map<String, Object> args = new HashMap<>();
        args.put("format", "png");
        
        return executeCdpCmd("Page.captureScreenshot", args, 30.0f)
            .thenApply(result -> {
                String base64Data = result.get("data").asText();
                return Base64.getDecoder().decode(base64Data);
            });
    }
    
    /**
     * Salva screenshot da janela atual em arquivo PNG.
     *
     * @param filename caminho completo do arquivo (deve terminar com .png)
     * @return CompletableFuture que completa quando o arquivo é salvo
     */
    public CompletableFuture<Void> getScreenshotAsFile(String filename) {
        if (!filename.toLowerCase().endsWith(".png")) {
            System.err.println("AVISO: nome usado para screenshot não corresponde ao tipo de arquivo. " +
                "Deve terminar com extensão .png");
        }
        
        return getScreenshotAsPng().thenCompose(pngBytes -> {
            return CompletableFuture.runAsync(() -> {
                try {
                    java.nio.file.Files.write(java.nio.file.Paths.get(filename), pngBytes);
                } catch (java.io.IOException e) {
                    throw new RuntimeException("Erro ao salvar screenshot", e);
                }
            });
        });
    }
    
    /**
     * Alias para getScreenshotAsFile.
     *
     * @param filename caminho do arquivo
     * @return CompletableFuture que completa quando o arquivo é salvo
     */
    public CompletableFuture<Void> saveScreenshot(String filename) {
        return getScreenshotAsFile(filename);
    }
    
    /**
     * Obtém o snapshot atual como MHTML.
     *
     * @return CompletableFuture com o MHTML
     */
    public CompletableFuture<String> snapshot() {
        return executeCdpCmd("Page.captureSnapshot", null, null)
            .thenApply(result -> result.get("data").asText());
    }
    
    /**
     * Salva snapshot da janela atual em arquivo MHTML.
     *
     * @param filename caminho completo do arquivo (deve terminar com .mhtml)
     * @return CompletableFuture que completa quando o arquivo é salvo
     */
    public CompletableFuture<Void> saveSnapshot(String filename) {
        if (!filename.endsWith(".mhtml")) {
            System.err.println("AVISO: nome usado para snapshot não corresponde ao tipo de arquivo. " +
                "Deve terminar com extensão .mhtml");
        }
        
        return snapshot().thenCompose(mhtml -> {
            return CompletableFuture.runAsync(() -> {
                try {
                    java.nio.file.Files.writeString(java.nio.file.Paths.get(filename), mhtml);
                } catch (java.io.IOException e) {
                    throw new RuntimeException("Erro ao salvar snapshot", e);
                }
            });
        });
    }
    
    /**
     * Define condições de emulação de rede do Chromium.
     *
     * @param offline se está offline
     * @param latency latência adicional em ms
     * @param downloadThroughput throughput máximo de download
     * @param uploadThroughput throughput máximo de upload
     * @param connectionType tipo de conexão
     * @return CompletableFuture que completa quando as condições são definidas
     */
    public CompletableFuture<Void> setNetworkConditions(boolean offline, int latency,
                                                        int downloadThroughput, int uploadThroughput,
                                                        String connectionType) {
        String[] validTypes = {"none", "cellular2g", "cellular3g", "cellular4g", 
            "bluetooth", "ethernet", "wifi", "wimax", "other"};
        
        if (connectionType != null && !Arrays.asList(validTypes).contains(connectionType)) {
            return CompletableFuture.failedFuture(
                new IllegalArgumentException("connectionType inválido: " + connectionType)
            );
        }
        
        Map<String, Object> args = new HashMap<>();
        args.put("offline", offline);
        args.put("latency", latency);
        args.put("downloadThroughput", downloadThroughput);
        args.put("uploadThroughput", uploadThroughput);
        
        if (connectionType != null) {
            args.put("connectionType", connectionType);
        }
        
        return executeCdpCmd("Network.emulateNetworkConditions", args, null)
            .thenApply(v -> null);
    }
    
    /**
     * Foca no target (ativa e traz para frente).
     *
     * @return CompletableFuture que completa quando o target está focado
     */
    public CompletableFuture<Void> focus() {
        Map<String, Object> args = new HashMap<>();
        args.put("targetId", id);
        
        return executeCdpCmd("Target.activateTarget", args, null)
            .thenCompose(v -> executeScript("window.focus()", null, false))
            .thenApply(v -> null);
    }
    
    /**
     * Ativa o target (traz para frente).
     *
     * @return CompletableFuture que completa quando o target está ativado
     */
    public CompletableFuture<Void> activate() {
        Map<String, Object> args = new HashMap<>();
        args.put("targetId", id);
        
        return executeCdpCmd("Target.activateTarget", args, null)
            .thenApply(v -> null);
    }
    
    /**
     * Obtém todos os cookies visíveis na sessão atual.
     *
     * @return CompletableFuture com a lista de cookies
     */
    public CompletableFuture<List<Map<String, Object>>> getCookies() {
        return executeCdpCmd("Network.getCookies", null, null)
            .thenApply(result -> {
                JsonNode cookies = result.get("cookies");
                List<Map<String, Object>> cookieList = new ArrayList<>();
                
                for (JsonNode cookie : cookies) {
                    Map<String, Object> cookieMap = new HashMap<>();
                    cookie.fields().forEachRemaining(entry -> {
                        JsonNode value = entry.getValue();
                        cookieMap.put(entry.getKey(), 
                            value.isTextual() ? value.asText() : value);
                    });
                    cookieList.add(cookieMap);
                }
                
                return cookieList;
            });
    }
    
    /**
     * Obtém um cookie pelo nome.
     *
     * @param name nome do cookie
     * @return CompletableFuture com o cookie ou null
     */
    public CompletableFuture<Map<String, Object>> getCookie(String name) {
        return getCookies().thenApply(cookies -> {
            for (Map<String, Object> cookie : cookies) {
                if (name.equals(cookie.get("name"))) {
                    return cookie;
                }
            }
            return null;
        });
    }
    
    /**
     * Adiciona um cookie à sessão atual.
     *
     * @param cookieDict dicionário com chaves "name" e "value" (obrigatórias);
     *                  opcionais: "path", "domain", "secure", "httpOnly", "expiry", "sameSite"
     * @return CompletableFuture que completa quando o cookie é adicionado
     */
    public CompletableFuture<Void> addCookie(Map<String, Object> cookieDict) {
        if (cookieDict.containsKey("sameSite")) {
            String sameSite = cookieDict.get("sameSite").toString();
            if (!Arrays.asList("Strict", "Lax", "None").contains(sameSite)) {
                return CompletableFuture.failedFuture(
                    new IllegalArgumentException("sameSite deve ser 'Strict', 'Lax' ou 'None'")
                );
            }
        }
        
        Map<String, Object> args = new HashMap<>();
        args.put("cookies", Collections.singletonList(cookieDict));
        
        return executeCdpCmd("Storage.setCookies", args, null)
            .thenApply(v -> null);
    }
    
    /**
     * Deleta um cookie pelo nome.
     *
     * @param name nome do cookie
     * @return CompletableFuture que completa quando o cookie é deletado
     */
    public CompletableFuture<Void> deleteCookie(String name) {
        Map<String, Object> args = new HashMap<>();
        args.put("name", name);
        
        return executeCdpCmd("Network.deleteCookies", args, null)
            .thenApply(v -> null);
    }
    
    /**
     * Deleta todos os cookies.
     *
     * @return CompletableFuture que completa quando os cookies são deletados
     */
    public CompletableFuture<Void> deleteAllCookies() {
        return executeCdpCmd("Network.clearBrowserCookies", null, null)
            .thenApply(v -> null);
    }
    
    /**
     * Envia teclas para o target (simula digitação).
     *
     * @param text texto a enviar
     * @return CompletableFuture que completa quando as teclas são enviadas
     */
    public CompletableFuture<Void> sendKeys(String text) {
        sendKeyLock.lock();
        try {
            List<CompletableFuture<Void>> futures = new ArrayList<>();
            
            for (char c : text.toCharArray()) {
                KeyInfo keyInfo = KEY_MAPPING.get(c);
                
                if (keyInfo == null) {
                    System.err.println("AVISO: Tecla não mapeada: " + c);
                    continue;
                }
                
                boolean needsShift = SHIFT_KEY_NEEDED.indexOf(c) >= 0;
                int modifiers = needsShift ? 8 : 0;  // SHIFT = 8
                
                // KeyDown
                Map<String, Object> downArgs = new HashMap<>();
                downArgs.put("type", "keyDown");
                downArgs.put("code", keyInfo.code);
                downArgs.put("key", String.valueOf(c));
                downArgs.put("windowsVirtualKeyCode", keyInfo.keyCode);
                downArgs.put("nativeVirtualKeyCode", keyInfo.keyCode);
                downArgs.put("modifiers", modifiers);
                
                CompletableFuture<Void> down = executeCdpCmd("Input.dispatchKeyEvent", downArgs, null)
                    .thenApply(v -> null);
                
                // Small delay
                CompletableFuture<Void> delay = down.thenCompose(v -> 
                    CompletableFuture.runAsync(() -> {
                        try {
                            Thread.sleep((long) (Math.random() * 40 + 10));  // 10-50ms
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                        }
                    })
                );
                
                // KeyUp
                Map<String, Object> upArgs = new HashMap<>();
                upArgs.put("type", "keyUp");
                upArgs.put("code", keyInfo.code);
                upArgs.put("key", String.valueOf(c));
                upArgs.put("windowsVirtualKeyCode", keyInfo.keyCode);
                upArgs.put("nativeVirtualKeyCode", keyInfo.keyCode);
                upArgs.put("modifiers", modifiers);
                
                CompletableFuture<Void> up = delay.thenCompose(v -> 
                    executeCdpCmd("Input.dispatchKeyEvent", upArgs, null)
                        .thenApply(v2 -> null)
                );
                
                futures.add(up);
            }
            
            return CompletableFuture.allOf(futures.toArray(new CompletableFuture[0]));
        } finally {
            sendKeyLock.unlock();
        }
    }
    
    /**
     * Remove o foco do target.
     *
     * @return CompletableFuture que completa quando o foco é removido
     */
    public CompletableFuture<Void> unfocus() {
        return executeScript("window.blur()", null, false)
            .thenApply(v -> null);
    }
    
    /**
     * Imprime a página e retorna o PDF em base64.
     *
     * @return CompletableFuture com o PDF em base64
     */
    public CompletableFuture<String> printPage() {
        return executeCdpCmd("Page.printToPDF", null, null)
            .thenApply(result -> result.get("data").asText());
    }
    
    /**
     * Obtém o histórico de navegação.
     *
     * @return CompletableFuture com o histórico
     */
    public CompletableFuture<Map<String, Object>> getHistory() {
        return executeCdpCmd("Page.getNavigationHistory", null, null)
            .thenApply(result -> {
                Map<String, Object> history = new HashMap<>();
                history.put("currentIndex", result.get("currentIndex").asInt());
                
                List<Map<String, Object>> entries = new ArrayList<>();
                JsonNode entriesNode = result.get("entries");
                for (JsonNode entry : entriesNode) {
                    Map<String, Object> entryMap = new HashMap<>();
                    entry.fields().forEachRemaining(field ->
                        entryMap.put(field.getKey(), field.getValue())
                    );
                    entries.add(entryMap);
                }
                history.put("entries", entries);
                
                return history;
            });
    }
    
    /**
     * Define o código-fonte (OuterHTML) do target.
     *
     * @param source HTML para definir
     * @param timeout timeout em segundos
     * @return CompletableFuture que completa quando o source é definido
     */
    public CompletableFuture<Void> setSource(String source, float timeout) {
        return CompletableFuture.supplyAsync(() -> {
            long startNanos = System.nanoTime();
            
            while (true) {
                try {
                    WebElement document = getDocumentElem().join();
                    document.setSource(source).join();
                    return null;
                } catch (Exception e) {
                    onLoaded();
                }
                
                double elapsed = (System.nanoTime() - startNanos) / 1_000_000_000.0;
                if (elapsed > timeout) {
                    throw new RuntimeException(
                        "Não foi possível obter elemento documento não-stale"
                    );
                }
                
                try {
                    Thread.sleep(10);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new RuntimeException("Interrompido");
                }
            }
        });
    }
    
    /**
     * Aguarda por um download.
     *
     * @param timeout timeout em segundos
     * @return CompletableFuture com dados do download
     */
    public CompletableFuture<Map<String, Object>> waitDownload(float timeout) {
        return CompletableFuture.supplyAsync(() -> {
            // TODO: Implementar completamente quando base_frame estiver disponível
            throw new UnsupportedOperationException(
                "waitDownload requer implementação completa de frames"
            );
        });
    }
    
    /**
     * Obtém informações do target.
     *
     * @return CompletableFuture com as informações
     */
    public CompletableFuture<Map<String, Object>> getInfo() {
        Map<String, Object> args = new HashMap<>();
        args.put("targetId", id);
        
        return executeCdpCmd("Target.getTargetInfo", args, null)
            .thenApply(result -> {
                JsonNode targetInfo = result.get("targetInfo");
                Map<String, Object> info = new HashMap<>();
                targetInfo.fields().forEachRemaining(field ->
                    info.put(field.getKey(), field.getValue())
                );
                return info;
            });
    }
    
    /**
     * Obtém a árvore de frames.
     *
     * @return CompletableFuture com a árvore de frames
     */
    public CompletableFuture<Map<String, Object>> getFrameTree() {
        return executeCdpCmd("Page.getFrameTree", null, null)
            .thenApply(result -> {
                JsonNode frameTree = result.get("frameTree");
                return objectMapper.convertValue(frameTree, Map.class);
            });
    }
    
    /**
     * Obtém o frame base.
     *
     * @return CompletableFuture com o frame base
     */
    public CompletableFuture<Map<String, Object>> getBaseFrame() {
        return getFrameTree().thenApply(frameTree -> {
            @SuppressWarnings("unchecked")
            Map<String, Object> frame = (Map<String, Object>) frameTree.get("frame");
            return frame;
        });
    }
    
    /**
     * Obtém o tipo do target.
     *
     * @return CompletableFuture com o tipo
     */
    public CompletableFuture<String> getType() {
        if (type != null) {
            return CompletableFuture.completedFuture(type);
        }
        return getInfo().thenApply(info -> info.get("type").toString());
    }
    
    /**
     * Obtém o título do target (via CDP).
     *
     * @return CompletableFuture com o título
     */
    public CompletableFuture<String> getTitleViaCDP() {
        return getInfo().thenApply(info -> info.get("title").toString());
    }
    
    /**
     * Obtém a URL do target (via CDP).
     *
     * @return CompletableFuture com a URL
     */
    public CompletableFuture<String> getUrlViaCDP() {
        return getInfo().thenApply(info -> info.get("url").toString());
    }
    
    /**
     * Obtém o ID da janela.
     *
     * @return CompletableFuture com o window ID
     */
    public CompletableFuture<Integer> getWindowId() {
        if (windowId != null) {
            return CompletableFuture.completedFuture(windowId);
        }
        
        return executeCdpCmd("Browser.getWindowForTarget", 
            Map.of("targetId", id), null)
            .thenApply(result -> {
                int winId = result.get("windowId").asInt();
                windowId = winId;
                return winId;
            });
    }
    
    /**
     * Obtém condições de emulação de rede atuais.
     *
     * @return CompletableFuture com as condições
     */
    public CompletableFuture<Map<String, Object>> getNetworkConditions() {
        throw new UnsupportedOperationException("Não iniciado com chromedriver");
    }
    
    /**
     * Reseta condições de emulação de rede.
     *
     * @return CompletableFuture que completa quando as condições são resetadas
     */
    public CompletableFuture<Void> deleteNetworkConditions() {
        throw new UnsupportedOperationException("Não iniciado com chromedriver");
    }
    
    /**
     * Executa uma requisição fetch JavaScript dentro do target.
     *
     * @param url URL para fazer a requisição
     * @param method método HTTP (GET, POST, etc)
     * @param headers headers da requisição
     * @param body corpo da requisição
     * @param timeout timeout em segundos
     * @return CompletableFuture com a resposta
     */
    public CompletableFuture<Map<String, Object>> fetch(
        String url,
        String method,
        Map<String, String> headers,
        Object body,
        float timeout
    ) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                Map<String, Object> options = new HashMap<>();
                
                if (method != null) {
                    options.put("method", method);
                }
                if (headers != null) {
                    options.put("headers", headers);
                }
                if (body != null) {
                    byte[] bodyBytes;
                    if (body instanceof Map) {
                        String json = objectMapper.writeValueAsString(body);
                        bodyBytes = json.getBytes(StandardCharsets.UTF_8);
                    } else if (body instanceof String) {
                        bodyBytes = ((String) body).getBytes(StandardCharsets.UTF_8);
                    } else if (body instanceof byte[]) {
                        bodyBytes = (byte[]) body;
                    } else {
                        throw new IllegalArgumentException("Body deve ser Map, String ou byte[]");
                    }
                    options.put("body", Base64.getEncoder().encodeToString(bodyBytes));
                }
                
                String script = """
                    async function bufferTobase64(array) {
                      return new Promise((resolve) => {
                        const blob = new Blob([array]);
                        const reader = new FileReader();
                        
                        reader.onload = (event) => {
                          const dataUrl = event.target.result;
                          const [_, base64] = dataUrl.split(',');
                          
                          resolve(base64);
                        };
                        
                        reader.readAsDataURL(blob);
                      });
                    };
                    async function base64ToBuffer(base64) {
                      const dataUrl = "data:application/octet-binary;base64," + base64;
                    
                      const res = await fetch(dataUrl)
                      return await res.arrayBuffer()
                    };

                    function headers2dict(headers){
                        var my_dict = {};
                        for (var pair of headers.entries()) {
                                my_dict[pair[0]] = pair[1]};
                        return my_dict}

                    async function get(url, options){
                        if(options.body){options.body = await base64ToBuffer(options.body)}
                        var response = await fetch(url, options);
                        var buffer = await response.arrayBuffer()
                        var b64 = await bufferTobase64(buffer)
                        var res = {
                                "b64":b64,
                                "headers":headers2dict(response.headers),
                                "ok":response.ok,
                                "status_code":response.status,
                                "redirected":response.redirected,
                                "status_text":response.statusText,
                                "type":response.type,
                                "url":response.url
                                };
                        return res;
                    }
                    return await get(arguments[0], arguments[1])
                """;
                
                @SuppressWarnings("unchecked")
                Map<String, Object> result = (Map<String, Object>) evalAsync(
                    script, 
                    timeout, 
                    true, 
                    url, 
                    options
                ).join();
                
                // Decodificar body de base64 para bytes
                String b64 = (String) result.get("b64");
                byte[] bodyResult = Base64.getDecoder().decode(b64);
                result.put("body", bodyResult);
                result.remove("b64");
                
                return result;
            } catch (Exception e) {
                throw new RuntimeException("Erro no fetch: " + e.getMessage(), e);
            }
        });
    }
    
    /**
     * Executa uma requisição XMLHttpRequest JavaScript dentro do target.
     *
     * @param url URL para fazer a requisição
     * @param method método HTTP (GET, POST, etc)
     * @param headers headers da requisição
     * @param body corpo da requisição
     * @param timeout timeout em segundos
     * @return CompletableFuture com a resposta
     */
    public CompletableFuture<Map<String, Object>> xhr(
        String url,
        String method,
        Map<String, String> headers,
        Object body,
        float timeout
    ) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                String bodyB64 = null;
                if (body != null) {
                    byte[] bodyBytes;
                    if (body instanceof Map) {
                        String json = objectMapper.writeValueAsString(body);
                        bodyBytes = json.getBytes(StandardCharsets.UTF_8);
                    } else if (body instanceof String) {
                        bodyBytes = ((String) body).getBytes(StandardCharsets.UTF_8);
                    } else if (body instanceof byte[]) {
                        bodyBytes = (byte[]) body;
                    } else {
                        throw new IllegalArgumentException("Body deve ser Map, String ou byte[]");
                    }
                    bodyB64 = Base64.getEncoder().encodeToString(bodyBytes);
                }
                
                String script = """
                    async function bufferTobase64(array) {
                      return new Promise((resolve) => {
                        const blob = new Blob([array]);
                        const reader = new FileReader();
                        
                        reader.onload = (event) => {
                          const dataUrl = event.target.result;
                          const [_, base64] = dataUrl.split(',');
                          
                          resolve(base64);
                        };
                        
                        reader.readAsDataURL(blob);
                      });
                    };
                    async function base64ToBuffer(base64) {
                      const dataUrl = "data:application/octet-binary;base64," + base64;
                    
                      const res = await fetch(dataUrl)
                      return await res.arrayBuffer()
                    };

                    function headers2dict(headers){
                        var my_dict = {};
                        var pairs = headers.trim().split('\\r\\n')
                        for(let pair of pairs){
                            if(pair){
                                var idx = pair.indexOf(":")
                                var key = pair.slice(0, idx)
                                var value = pair.slice(idx+1).trim()
                                my_dict[key] = value
                            }
                        };
                        return my_dict}

                    async function get(url, method, headers, body){
                        return new Promise(async(resolve, reject) => {
                            const xhr = new XMLHttpRequest();
                            xhr.open(method, url, true);
                            if(headers){
                                for (const [header, value] of Object.entries(headers)) {
                                    xhr.setRequestHeader(header, value)
                                }
                            }
                            xhr.responseType = 'arraybuffer';

                            xhr.onload = async() => {
                                var b64 = await bufferTobase64(xhr.response)
                                resolve({
                                    b64: b64,
                                    headers: headers2dict(xhr.getAllResponseHeaders()),
                                    status_code: xhr.status,
                                    status_text: xhr.statusText
                                });
                            };
                            xhr.onerror = () => {
                                reject({
                                    status: xhr.status,
                                    statusText: xhr.statusText
                                });
                            };
                            if(body){
                                body = await base64ToBuffer(body)
                                xhr.send(body);
                            }
                            else{
                                xhr.send();
                            }
                        });
                    }
                    return await get(arguments[0], arguments[1], arguments[2], arguments[3])
                """;
                
                @SuppressWarnings("unchecked")
                Map<String, Object> result = (Map<String, Object>) evalAsync(
                    script, 
                    timeout, 
                    true, 
                    url, 
                    method,
                    headers,
                    bodyB64
                ).join();
                
                // Decodificar body de base64 para bytes
                String b64 = (String) result.get("b64");
                byte[] bodyResult = Base64.getDecoder().decode(b64);
                result.put("body", bodyResult);
                result.remove("b64");
                
                return result;
            } catch (Exception e) {
                throw new RuntimeException("Erro no xhr: " + e.getMessage(), e);
            }
        });
    }
    
    /**
     * Obtém os sinks disponíveis para casting (ChromeCast, etc).
     *
     * @return CompletableFuture com a lista de sinks
     */
    public CompletableFuture<List<Map<String, Object>>> getSinks() {
        return executeCdpCmd("Cast.enable", null, null)
            .thenCompose(v -> waitForCdp("Cast.sinksUpdated", 5.0f))
            .thenApply(event -> {
                JsonNode sinksNode = event.get("sinks");
                List<Map<String, Object>> sinks = new ArrayList<>();
                for (JsonNode sink : sinksNode) {
                    sinks.add(objectMapper.convertValue(sink, Map.class));
                }
                return sinks;
            });
    }
    
    /**
     * Obtém mensagem de issue.
     *
     * @return CompletableFuture com a mensagem
     */
    public CompletableFuture<String> getIssueMessage() {
        return waitForCdp("Cast.issueUpdated", 5.0f)
            .thenApply(event -> event.get("issueMessage").asText());
    }
    
    /**
     * Define qual sink usar para casting.
     *
     * @param sinkName nome do sink
     * @return CompletableFuture com o resultado
     */
    public CompletableFuture<Map<String, Object>> setSinkToUse(String sinkName) {
        Map<String, Object> args = new HashMap<>();
        args.put("sinkName", sinkName);
        
        return executeCdpCmd("Cast.setSinkToUse", args, null)
            .thenApply(result -> objectMapper.convertValue(result, Map.class));
    }
    
    /**
     * Inicia mirroring de desktop para sink.
     *
     * @param sinkName nome do sink
     * @return CompletableFuture com o resultado
     */
    public CompletableFuture<Map<String, Object>> startDesktopMirroring(String sinkName) {
        Map<String, Object> args = new HashMap<>();
        args.put("sinkName", sinkName);
        
        return executeCdpCmd("Cast.startDesktopMirroring", args, null)
            .thenApply(result -> objectMapper.convertValue(result, Map.class));
    }
    
    /**
     * Inicia mirroring de tab para sink.
     *
     * @param sinkName nome do sink
     * @return CompletableFuture com o resultado
     */
    public CompletableFuture<Map<String, Object>> startTabMirroring(String sinkName) {
        Map<String, Object> args = new HashMap<>();
        args.put("sinkName", sinkName);
        
        return executeCdpCmd("Cast.startTabMirroring", args, null)
            .thenApply(result -> objectMapper.convertValue(result, Map.class));
    }
    
    /**
     * Para o casting para um sink.
     *
     * @param sinkName nome do sink
     * @return CompletableFuture com o resultado
     */
    public CompletableFuture<Map<String, Object>> stopCasting(String sinkName) {
        Map<String, Object> args = new HashMap<>();
        args.put("sinkName", sinkName);
        
        return executeCdpCmd("Cast.stopCasting", args, null)
            .thenApply(result -> objectMapper.convertValue(result, Map.class));
    }
    
    /**
     * Fecha o target.
     *
     * @return CompletableFuture que completa quando o target é fechado
     */
    public CompletableFuture<Void> close() {
        return close(2.0f);
    }
    
    /**
     * Fecha o target com timeout.
     *
     * @param timeout timeout em segundos
     * @return CompletableFuture que completa quando o target é fechado
     */
    public CompletableFuture<Void> close(float timeout) {
        if (socket != null && socket.isConnected()) {
            return executeCdpCmd("Target.closeTarget", Map.of("targetId", id), timeout)
                .thenCompose(v -> socket.closeAsync())
                .exceptionally(e -> {
                    // Ignorar erros de fechamento
                    return null;
                });
        }
        return CompletableFuture.completedFuture(null);
    }
    
    /**
     * Aguarda por um evento CDP.
     *
     * @param event nome do evento
     * @param timeout timeout em segundos (null para infinito)
     * @return CompletableFuture com os parâmetros do evento
     */
    public CompletableFuture<JsonNode> waitForCdp(String event, Float timeout) {
        if (socket == null) {
            return init().thenCompose(t -> socket.waitFor(event, timeout));
        }
        return socket.waitFor(event, timeout);
    }
    
    /**
     * Adiciona um listener para um evento CDP.
     *
     * @param event nome do evento
     * @param callback função a ser chamada
     * @return CompletableFuture que completa quando o listener é adicionado
     */
    public CompletableFuture<Void> addCdpListener(String event, Consumer<JsonNode> callback) {
        if (socket == null) {
            return init().thenAccept(t -> socket.addListener(event, callback));
        }
        socket.addListener(event, callback);
        return CompletableFuture.completedFuture(null);
    }
    
    /**
     * Remove um listener de um evento CDP.
     *
     * @param event nome do evento
     * @param callback função a ser removida
     * @return CompletableFuture que completa quando o listener é removido
     */
    public CompletableFuture<Void> removeCdpListener(String event, Consumer<JsonNode> callback) {
        if (socket == null) {
            return CompletableFuture.completedFuture(null);
        }
        socket.removeListener(event, callback);
        return CompletableFuture.completedFuture(null);
    }
    
    /**
     * Obtém um iterador de eventos CDP.
     * Retorna uma lista que acumula eventos deste tipo.
     *
     * @param event nome do evento para iterar
     * @return CompletableFuture com lista de eventos acumulados
     */
    public CompletableFuture<List<JsonNode>> getCdpEventIter(String event) {
        return CompletableFuture.supplyAsync(() -> {
            if (socket == null) {
                return new ArrayList<>();
            }
            
            List<JsonNode> events = new ArrayList<>();
            Consumer<JsonNode> collector = events::add;
            
            // Adicionar listener temporário que coleta eventos
            socket.addListener(event, collector);
            
            // Retornar lista (eventos serão adicionados conforme chegam)
            return events;
        });
    }
    
    /**
     * Executa um comando CDP.
     *
     * @param cmd nome do comando
     * @param cmdArgs argumentos do comando
     * @param timeout timeout em segundos
     * @return CompletableFuture com o resultado
     */
    public CompletableFuture<JsonNode> executeCdpCmd(String cmd, Map<String, Object> cmdArgs, Float timeout) {
        if (socket == null) {
            return init().thenCompose(t -> socket.exec(cmd, cmdArgs, timeout));
        }
        return socket.exec(cmd, cmdArgs, timeout);
    }
    
    /**
     * Aguarda em segundos.
     *
     * @param seconds segundos para aguardar
     * @return CompletableFuture que completa após o tempo
     */
    public CompletableFuture<Void> sleep(double seconds) {
        return CompletableFuture.runAsync(() -> {
            try {
                Thread.sleep((long) (seconds * 1000));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });
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
    
    @Override
    public boolean equals(Object obj) {
        if (this == obj) return true;
        if (!(obj instanceof Target)) return false;
        Target other = (Target) obj;
        return Objects.equals(socket, other.socket);
    }
    
    @Override
    public int hashCode() {
        return Objects.hash(socket);
    }
    
    /**
     * Cria o mapeamento de teclas.
     */
    private static Map<Character, KeyInfo> createKeyMapping() {
        Map<Character, KeyInfo> map = new HashMap<>();
        
        // Letras minúsculas
        for (char c = 'a'; c <= 'z'; c++) {
            map.put(c, new KeyInfo("Key" + Character.toUpperCase(c), 65 + (c - 'a')));
        }
        
        // Letras maiúsculas
        for (char c = 'A'; c <= 'Z'; c++) {
            map.put(c, new KeyInfo("Key" + c, 65 + (c - 'A')));
        }
        
        // Números
        for (char c = '0'; c <= '9'; c++) {
            map.put(c, new KeyInfo("Digit" + c, 48 + (c - '0')));
        }
        
        // Caracteres especiais
        map.put(' ', new KeyInfo("Space", 32));
        map.put('\r', new KeyInfo("Enter", 13));
        map.put('\n', new KeyInfo("Enter", 13));
        map.put('.', new KeyInfo("Period", 190));
        map.put(',', new KeyInfo("Comma", 188));
        map.put('-', new KeyInfo("Minus", 189));
        map.put('=', new KeyInfo("Equal", 187));
        map.put('[', new KeyInfo("BracketLeft", 219));
        map.put(']', new KeyInfo("BracketRight", 221));
        map.put('\\', new KeyInfo("Backslash", 220));
        map.put(';', new KeyInfo("Semicolon", 186));
        map.put('\'', new KeyInfo("Quote", 222));
        map.put('/', new KeyInfo("Slash", 191));
        map.put('`', new KeyInfo("Backquote", 192));
        
        return Collections.unmodifiableMap(map);
    }
    
    /**
     * Classe interna TargetInfo que representa informações sobre um target.
     * As informações não são dinâmicas.
     */
    public static class TargetInfo {
        private final String id;
        private final String type;
        private final String title;
        private final String url;
        private final Boolean attached;
        private final String openerId;
        private final Boolean canAccessOpener;
        private final String openerFrameId;
        private final String browserContextId;
        private final String subtype;
        private final CompletableFuture<Target> targetGetter;
        
        public TargetInfo(Map<String, Object> targetInfo, CompletableFuture<Target> targetGetter) {
            this.id = (String) targetInfo.get("targetId");
            this.type = (String) targetInfo.get("type");
            this.title = (String) targetInfo.get("title");
            this.url = (String) targetInfo.get("url");
            this.attached = (Boolean) targetInfo.get("attached");
            this.openerId = (String) targetInfo.get("openerId");
            this.canAccessOpener = (Boolean) targetInfo.get("canAccessOpener");
            this.openerFrameId = (String) targetInfo.get("openerFrameId");
            this.browserContextId = (String) targetInfo.get("browserContextId");
            this.subtype = (String) targetInfo.get("subtype");
            this.targetGetter = targetGetter;
        }
        
        /**
         * Obtém o Target associado.
         *
         * @return Target
         */
        public Target getTarget() {
            return targetGetter.join();
        }
        
        /**
         * Target.TargetID
         */
        public String getId() {
            return id;
        }
        
        /**
         * Tipo do target
         */
        public String getType() {
            return type;
        }
        
        /**
         * Título do target
         */
        public String getTitle() {
            return title;
        }
        
        /**
         * URL do target
         */
        public String getUrl() {
            return url;
        }
        
        /**
         * Se o target tem um cliente anexado
         */
        public Boolean getAttached() {
            return attached;
        }
        
        /**
         * Target.TargetID do opener
         */
        public String getOpenerId() {
            return openerId;
        }
        
        /**
         * Se o target tem acesso à janela de origem
         */
        public Boolean getCanAccessOpener() {
            return canAccessOpener;
        }
        
        /**
         * Page.FrameId da janela de origem (definido apenas se target tem um opener)
         */
        public String getOpenerFrameId() {
            return openerFrameId;
        }
        
        /**
         * Browser.BrowserContextID
         */
        public String getBrowserContextId() {
            return browserContextId;
        }
        
        /**
         * Fornece detalhes adicionais para tipos específicos de target.
         * Por exemplo, para o tipo "page", pode ser "portal" ou "prerender".
         */
        public String getSubtype() {
            return subtype;
        }
        
        @Override
        public String toString() {
            return String.format("TargetInfo(type=\"%s\", title=\"%s\")", type, title);
        }
    }
}

