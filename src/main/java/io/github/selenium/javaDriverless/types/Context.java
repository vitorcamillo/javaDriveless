// Licensed to the Software Freedom Conservancy (SFC) under one
// or more contributor license agreements.  See the NOTICE file
// distributed with this work for additional information
// regarding copyright ownership.  The SFC licenses this file
// to you under the Apache License, Version 2.0 (the
// "License"); you may not use this file except in compliance
// with the License.  You may obtain a copy of the License at
//
//   http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing,
// software distributed under the License is distributed on an
// "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
// KIND, either express or implied.  See the License for the
// specific language governing permissions and limitations
// under the License.
//
// Modificado por kaliiiiiiiiii | Aurin Aegerter
// Conversão para Java: Java Driverless
// Todas as modificações são licenciadas sob a licença fornecida em LICENSE.md

package io.github.selenium.javaDriverless.types;

import com.fasterxml.jackson.databind.JsonNode;

import io.github.selenium.javaDriverless.input.Pointer;
import io.github.selenium.javaDriverless.scripts.DriverUtils;
import io.github.selenium.javaDriverless.scripts.SwitchTo;

import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Permite dirigir o navegador sem chromedriver.
 * <p>
 * Um Context representa um contexto de navegação (normal ou incognito) que pode
 * conter múltiplas abas e janelas. Cada context tem:
 * - Cookies isolados
 * - Local storage isolado
 * - Configuração de proxy opcional (por context)
 * </p>
 */
public class Context implements AutoCloseable {
    
    private final Target currentTarget;
    private final String host;
    private final boolean isRemote;
    private final int maxWsSize;
    private String contextId;
    private final List<Runnable> closedCallbacks = new ArrayList<>();
    private final Object driver;
    private final boolean isIncognito;
    private final Map<String, Target> targets = new ConcurrentHashMap<>();
    
    private SwitchTo switchTo;
    private boolean started = false;
    
    /**
     * Cria um novo Context.
     *
     * @param baseTarget target base para o contexto
     * @param driver instância do driver
     * @param contextId ID do contexto (null para obter automaticamente)
     * @param isIncognito se é um contexto incognito
     * @param maxWsSize tamanho máximo de mensagens WebSocket
     */
    public Context(Target baseTarget, Object driver, String contextId, 
                  boolean isIncognito, int maxWsSize) {
        this.currentTarget = baseTarget;
        // Obter host do driver (Chrome)
        String hostTemp;
        try {
            hostTemp = ((io.github.selenium.javaDriverless.Chrome)driver).getHost();
        } catch (Exception e) {
            hostTemp = baseTarget.getId();  // Fallback
        }
        this.host = hostTemp;
        this.isRemote = false;  // Simplificação
        this.contextId = contextId;
        this.driver = driver;
        this.isIncognito = isIncognito;
        this.maxWsSize = maxWsSize;
    }
    
    /**
     * Inicia a sessão do contexto.
     *
     * @return CompletableFuture com o Context inicializado
     */
    public CompletableFuture<Context> startSession() {
        if (started) {
            return CompletableFuture.completedFuture(this);
        }
        
        return CompletableFuture.supplyAsync(() -> {
            switchTo = new SwitchTo(this, contextId);
            started = true;
            return this;
        });
    }
    
    /**
     * Retorna o target atual.
     *
     * @return target atual
     */
    public Target getCurrentTarget() {
        return currentTarget;
    }
    
    /**
     * Retorna o base target.
     *
     * @return base target
     */
    public BaseTarget getBaseTarget() {
        try {
            var method = driver.getClass().getMethod("getBaseTarget");
            @SuppressWarnings("unchecked")
            CompletableFuture<BaseTarget> future = (CompletableFuture<BaseTarget>) method.invoke(driver);
            return future.join();
        } catch (Exception e) {
            throw new RuntimeException("Erro ao obter base target", e);
        }
    }
    
    /**
     * Retorna o SwitchTo para este contexto.
     *
     * @return SwitchTo
     */
    public SwitchTo switchTo() {
        if (!started) {
            startSession().join();
        }
        return switchTo;
    }
    
    /**
     * Retorna o ponteiro do target atual.
     *
     * @return Pointer
     */
    public Pointer getCurrentPointer() {
        return currentTarget.getPointer();
    }
    
    /**
     * Navega para uma URL.
     *
     * @param url URL para navegar
     * @param referrer referrer opcional
     * @param waitLoad se deve aguardar o carregamento
     * @param timeout timeout em segundos
     * @return CompletableFuture com dados do resultado
     */
    public CompletableFuture<Map<String, Object>> get(String url, String referrer, 
                                                      boolean waitLoad, float timeout) {
        if (isIncognito && "chrome://extensions".equals(url)) {
            return CompletableFuture.failedFuture(
                new IllegalArgumentException(url + " apenas suportado em contextos não-incognito")
            );
        }
        return currentTarget.get(url, referrer, waitLoad, timeout);
    }
    
    /**
     * Navega para uma URL.
     *
     * @param url URL para navegar
     * @param waitLoad se deve aguardar o carregamento
     * @return CompletableFuture com dados do resultado
     */
    public CompletableFuture<Map<String, Object>> get(String url, boolean waitLoad) {
        return get(url, null, waitLoad, 30.0f);
    }
    
    /**
     * Retorna o título da página atual.
     *
     * @return CompletableFuture com o título
     */
    public CompletableFuture<String> getTitle() {
        return currentTarget.getTitle();
    }
    
    /**
     * Retorna a URL atual.
     *
     * @return CompletableFuture com a URL
     */
    public CompletableFuture<String> getCurrentUrl() {
        return currentTarget.getCurrentUrl();
    }
    
    /**
     * Retorna o código-fonte da página.
     *
     * @return CompletableFuture com o HTML
     */
    public CompletableFuture<String> getPageSource() {
        return currentTarget.getPageSource();
    }
    
    /**
     * Executa JavaScript.
     *
     * @param script código JavaScript
     * @param args argumentos
     * @param awaitPromise se deve aguardar promises
     * @return CompletableFuture com o resultado
     */
    public CompletableFuture<Object> executeScript(String script, Object[] args, boolean awaitPromise) {
        return currentTarget.executeScript(script, args, awaitPromise);
    }
    
    /**
     * Busca um elemento.
     *
     * @param by estratégia de busca
     * @param value valor da busca
     * @param timeout timeout em segundos
     * @return CompletableFuture com o elemento
     */
    public CompletableFuture<WebElement> findElement(String by, String value, float timeout) {
        return currentTarget.findElement(by, value, timeout);
    }
    
    /**
     * Busca múltiplos elementos.
     *
     * @param by estratégia de busca
     * @param value valor da busca
     * @param timeout timeout em segundos
     * @return CompletableFuture com a lista de elementos
     */
    public CompletableFuture<List<WebElement>> findElements(String by, String value, float timeout) {
        return currentTarget.findElements(by, value, timeout);
    }
    
    /**
     * Busca elementos usando texto, CSS ou XPath.
     *
     * @param query query de busca
     * @return CompletableFuture com a lista de elementos
     */
    public CompletableFuture<List<WebElement>> searchElements(String query) {
        return currentTarget.searchElements(query);
    }
    
    /**
     * Obtém todos os cookies.
     *
     * @return CompletableFuture com a lista de cookies
     */
    public CompletableFuture<List<Map<String, Object>>> getCookies() {
        return DriverUtils.getCookies(currentTarget);
    }
    
    /**
     * Obtém um cookie pelo nome.
     *
     * @param name nome do cookie
     * @return CompletableFuture com o cookie
     */
    public CompletableFuture<Map<String, Object>> getCookie(String name) {
        return DriverUtils.getCookie(currentTarget, name);
    }
    
    /**
     * Adiciona um cookie.
     *
     * @param cookieDict dicionário do cookie
     * @return CompletableFuture que completa quando o cookie é adicionado
     */
    public CompletableFuture<Void> addCookie(Map<String, Object> cookieDict) {
        return DriverUtils.addCookie(currentTarget, cookieDict, contextId);
    }
    
    /**
     * Deleta um cookie.
     *
     * @param name nome do cookie
     * @return CompletableFuture que completa quando o cookie é deletado
     */
    public CompletableFuture<Void> deleteCookie(String name) {
        return DriverUtils.deleteCookie(currentTarget, name, null, null, null);
    }
    
    /**
     * Deleta todos os cookies.
     *
     * @return CompletableFuture que completa quando os cookies são deletados
     */
    public CompletableFuture<Void> deleteAllCookies() {
        return DriverUtils.deleteAllCookies(currentTarget);
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
    
    /**
     * Volta para a página anterior.
     *
     * @return CompletableFuture que completa quando a ação termina
     */
    public CompletableFuture<Void> back() {
        return currentTarget.back();
    }
    
    /**
     * Avança para a próxima página.
     *
     * @return CompletableFuture que completa quando a ação termina
     */
    public CompletableFuture<Void> forward() {
        return currentTarget.forward();
    }
    
    /**
     * Recarrega a página.
     *
     * @param ignoreCache se deve ignorar o cache
     * @return CompletableFuture que completa quando a ação termina
     */
    public CompletableFuture<Void> refresh(boolean ignoreCache) {
        return currentTarget.refresh(ignoreCache);
    }
    
    /**
     * Executa script JavaScript raw.
     *
     * @param script script
     * @param awaitRes se aguarda resultado
     * @param serialization tipo de serialização
     * @param args argumentos
     * @return CompletableFuture com resultado
     */
    public CompletableFuture<Object> executeRawScript(String script, boolean awaitRes, 
                                                      String serialization, Object... args) {
        return currentTarget.executeRawScript(script, awaitRes, serialization, args);
    }
    
    /**
     * Executa script JavaScript assíncrono.
     *
     * @param script script
     * @param maxDepth profundidade de serialização
     * @param serialization tipo de serialização
     * @param args argumentos
     * @return CompletableFuture com resultado
     */
    public CompletableFuture<Object> executeAsyncScript(String script, int maxDepth, 
                                                        String serialization, Object... args) {
        return currentTarget.executeAsyncScript(script, maxDepth, serialization, args);
    }
    
    /**
     * Avalia expressão JavaScript assíncrona.
     *
     * @param script expressão
     * @param timeout timeout
     * @param uniqueContext se usa contexto único
     * @param args argumentos
     * @return CompletableFuture com resultado
     */
    public CompletableFuture<Object> evalAsync(String script, float timeout, 
                                               boolean uniqueContext, Object... args) {
        return currentTarget.evalAsync(script, timeout, uniqueContext, args);
    }
    
    /**
     * Foca na janela atual.
     *
     * @return CompletableFuture que completa quando focado
     */
    public CompletableFuture<Void> focus() {
        return currentTarget.focus();
    }
    
    /**
     * Adiciona listener de evento CDP.
     *
     * @param event nome do evento
     * @param callback callback
     * @return CompletableFuture que completa quando adicionado
     */
    public CompletableFuture<Void> addCdpListener(String event, java.util.function.Consumer<JsonNode> callback) {
        return currentTarget.addCdpListener(event, callback);
    }
    
    /**
     * Remove listener de evento CDP.
     *
     * @param event nome do evento
     * @param callback callback
     * @return CompletableFuture que completa quando removido
     */
    public CompletableFuture<Void> removeCdpListener(String event, java.util.function.Consumer<JsonNode> callback) {
        return currentTarget.removeCdpListener(event, callback);
    }
    
    /**
     * Obtém iterador de eventos CDP.
     *
     * @param event nome do evento
     * @return CompletableFuture com lista de eventos
     */
    public CompletableFuture<List<JsonNode>> getCdpEventIter(String event) {
        return currentTarget.getCdpEventIter(event);
    }
    
    /**
     * Obtém todos os targets deste contexto.
     *
     * @return CompletableFuture com lista de targets
     */
    public CompletableFuture<List<Target>> getTargets() {
        return CompletableFuture.supplyAsync(() -> new ArrayList<>(targets.values()));
    }
    
    /**
     * Obtém um target específico por ID.
     *
     * @param targetId ID do target
     * @return CompletableFuture com o target
     */
    public CompletableFuture<Target> getTarget(String targetId) {
        return CompletableFuture.supplyAsync(() -> {
            Target t = targets.get(targetId);
            if (t == null) {
                throw new RuntimeException("Target não encontrado: " + targetId);
            }
            return t;
        });
    }
    
    /**
     * Remove um target deste contexto.
     *
     * @param target target para remover
     * @return CompletableFuture que completa quando removido
     */
    public CompletableFuture<Void> removeTarget(Target target) {
        return target.close().thenRun(() -> {
            targets.remove(target.getId());
        });
    }
    
    /**
     * Obtém a árvore de frames.
     *
     * @return CompletableFuture com a árvore
     */
    public CompletableFuture<Map<String, Object>> getFrameTree() {
        return currentTarget.getFrameTree();
    }
    
    /**
     * Obtém target para iframe.
     *
     * @param iframe elemento iframe
     * @return CompletableFuture com o target
     */
    public CompletableFuture<Target> getTargetForIframe(WebElement iframe) {
        return currentTarget.getTargetForIframe(iframe);
    }
    
    /**
     * Obtém handles de janelas.
     *
     * @return CompletableFuture com lista de handles
     */
    public CompletableFuture<List<String>> getWindowHandles() {
        return getTargets().thenApply(tgts -> {
            List<String> handles = new ArrayList<>();
            for (Target t : tgts) {
                handles.add(t.getId());
            }
            return handles;
        });
    }
    
    /**
     * Obtém handle da janela atual.
     *
     * @return handle da janela
     */
    public String getCurrentWindowHandle() {
        return currentTarget.getId();
    }
    
    /**
     * Obtém ID da janela atual.
     *
     * @return CompletableFuture com window ID
     */
    public CompletableFuture<Integer> getCurrentWindowId() {
        return currentTarget.getWindowId();
    }
    
    /**
     * Maximiza a janela.
     *
     * @return CompletableFuture que completa quando maximizado
     */
    public CompletableFuture<Void> maximizeWindow() {
        Map<String, Object> bounds = Map.of("windowState", "maximized");
        return setWindowBounds(bounds);
    }
    
    /**
     * Minimiza a janela.
     *
     * @return CompletableFuture que completa quando minimizado
     */
    public CompletableFuture<Void> minimizeWindow() {
        Map<String, Object> bounds = Map.of("windowState", "minimized");
        return setWindowBounds(bounds);
    }
    
    /**
     * Coloca janela em fullscreen.
     *
     * @return CompletableFuture que completa quando em fullscreen
     */
    public CompletableFuture<Void> fullscreenWindow() {
        Map<String, Object> bounds = Map.of("windowState", "fullscreen");
        return setWindowBounds(bounds);
    }
    
    /**
     * Normaliza a janela.
     *
     * @return CompletableFuture que completa quando normalizado
     */
    public CompletableFuture<Void> normalizeWindow() {
        Map<String, Object> bounds = Map.of("windowState", "normal");
        return setWindowBounds(bounds);
    }
    
    /**
     * Define estado da janela.
     *
     * @param state estado (normal, minimized, maximized, fullscreen)
     * @return CompletableFuture que completa quando definido
     */
    public CompletableFuture<Void> setWindowState(String state) {
        Map<String, Object> bounds = Map.of("windowState", state);
        return setWindowBounds(bounds);
    }
    
    /**
     * Define bounds da janela.
     *
     * @param bounds bounds da janela
     * @return CompletableFuture que completa quando definido
     */
    private CompletableFuture<Void> setWindowBounds(Map<String, Object> bounds) {
        return getCurrentWindowId().thenCompose(windowId -> {
            Map<String, Object> args = new HashMap<>();
            args.put("windowId", windowId);
            args.put("bounds", bounds);
            
            return getBaseTarget().executeCdpCmd("Browser.setWindowBounds", args, null)
                .thenApply(v -> null);
        });
    }
    
    /**
     * Obtém posição da janela.
     *
     * @return CompletableFuture com x, y
     */
    public CompletableFuture<Map<String, Object>> getWindowPosition() {
        return getWindowRect().thenApply(rect -> {
            Map<String, Object> pos = new HashMap<>();
            pos.put("x", rect.get("x"));
            pos.put("y", rect.get("y"));
            return pos;
        });
    }
    
    /**
     * Define posição da janela.
     *
     * @param x posição X
     * @param y posição Y
     * @return CompletableFuture que completa quando definido
     */
    public CompletableFuture<Void> setWindowPosition(int x, int y) {
        Map<String, Object> bounds = new HashMap<>();
        bounds.put("left", x);
        bounds.put("top", y);
        return setWindowBounds(bounds);
    }
    
    /**
     * Obtém retângulo da janela.
     *
     * @return CompletableFuture com x, y, width, height
     */
    public CompletableFuture<Map<String, Object>> getWindowRect() {
        return getCurrentWindowId().thenCompose(windowId -> {
            Map<String, Object> args = new HashMap<>();
            args.put("windowId", windowId);
            
            return getBaseTarget().executeCdpCmd("Browser.getWindowBounds", args, null);
        }).thenApply(result -> {
            JsonNode boundsNode = result.get("bounds");
            Map<String, Object> rect = new HashMap<>();
            if (boundsNode.has("left")) rect.put("x", boundsNode.get("left").asInt());
            if (boundsNode.has("top")) rect.put("y", boundsNode.get("top").asInt());
            if (boundsNode.has("width")) rect.put("width", boundsNode.get("width").asInt());
            if (boundsNode.has("height")) rect.put("height", boundsNode.get("height").asInt());
            return rect;
        });
    }
    
    /**
     * Define retângulo da janela.
     *
     * @param x posição X
     * @param y posição Y
     * @param width largura
     * @param height altura
     * @return CompletableFuture que completa quando definido
     */
    public CompletableFuture<Void> setWindowRect(int x, int y, int width, int height) {
        Map<String, Object> bounds = new HashMap<>();
        bounds.put("left", x);
        bounds.put("top", y);
        bounds.put("width", width);
        bounds.put("height", height);
        return setWindowBounds(bounds);
    }
    
    /**
     * Obtém tamanho da janela.
     *
     * @return CompletableFuture com width, height
     */
    public CompletableFuture<Map<String, Object>> getWindowSize() {
        return getWindowRect().thenApply(rect -> {
            Map<String, Object> size = new HashMap<>();
            size.put("width", rect.get("width"));
            size.put("height", rect.get("height"));
            return size;
        });
    }
    
    /**
     * Define tamanho da janela.
     *
     * @param width largura
     * @param height altura
     * @return CompletableFuture que completa quando definido
     */
    public CompletableFuture<Void> setWindowSize(int width, int height) {
        return getWindowPosition().thenCompose(pos -> {
            int x = (Integer) pos.get("x");
            int y = (Integer) pos.get("y");
            return setWindowRect(x, y, width, height);
        });
    }
    
    /**
     * Imprime página como PDF.
     *
     * @return CompletableFuture com PDF em base64
     */
    public CompletableFuture<String> printPage() {
        return currentTarget.printPage();
    }
    
    /**
     * Executa fetch request.
     *
     * @param url URL
     * @param method método HTTP
     * @param headers headers
     * @param body corpo
     * @param timeout timeout
     * @return CompletableFuture com resposta
     */
    public CompletableFuture<Map<String, Object>> fetch(String url, String method, 
                                                        Map<String, String> headers, 
                                                        Object body, float timeout) {
        return currentTarget.fetch(url, method, headers, body, timeout);
    }
    
    /**
     * Executa XHR request.
     *
     * @param url URL
     * @param method método HTTP
     * @param headers headers
     * @param body corpo
     * @param timeout timeout
     * @return CompletableFuture com resposta
     */
    public CompletableFuture<Map<String, Object>> xhr(String url, String method, 
                                                      Map<String, String> headers, 
                                                      Object body, float timeout) {
        return currentTarget.xhr(url, method, headers, body, timeout);
    }
    
    /**
     * Obtém condições de rede.
     *
     * @return CompletableFuture com condições
     */
    public CompletableFuture<Map<String, Object>> getNetworkConditions() {
        return currentTarget.getNetworkConditions();
    }
    
    /**
     * Remove condições de rede.
     *
     * @return CompletableFuture que completa quando removido
     */
    public CompletableFuture<Void> deleteNetworkConditions() {
        return currentTarget.deleteNetworkConditions();
    }
    
    /**
     * Aguarda download.
     *
     * @param timeout timeout
     * @return CompletableFuture com dados do download
     */
    public CompletableFuture<Map<String, Object>> waitDownload(float timeout) {
        return currentTarget.waitDownload(timeout);
    }
    
    /**
     * Obtém sinks para casting.
     *
     * @return CompletableFuture com lista de sinks
     */
    public CompletableFuture<List<Map<String, Object>>> getSinks() {
        return currentTarget.getSinks();
    }
    
    /**
     * Obtém mensagem de issue.
     *
     * @return CompletableFuture com mensagem
     */
    public CompletableFuture<String> getIssueMessage() {
        return currentTarget.getIssueMessage();
    }
    
    /**
     * Define sink para usar.
     *
     * @param sinkName nome do sink
     * @return CompletableFuture com resultado
     */
    public CompletableFuture<Map<String, Object>> setSinkToUse(String sinkName) {
        return currentTarget.setSinkToUse(sinkName);
    }
    
    /**
     * Inicia desktop mirroring.
     *
     * @param sinkName nome do sink
     * @return CompletableFuture com resultado
     */
    public CompletableFuture<Map<String, Object>> startDesktopMirroring(String sinkName) {
        return currentTarget.startDesktopMirroring(sinkName);
    }
    
    /**
     * Inicia tab mirroring.
     *
     * @param sinkName nome do sink
     * @return CompletableFuture com resultado
     */
    public CompletableFuture<Map<String, Object>> startTabMirroring(String sinkName) {
        return currentTarget.startTabMirroring(sinkName);
    }
    
    /**
     * Para casting.
     *
     * @param sinkName nome do sink
     * @return CompletableFuture com resultado
     */
    public CompletableFuture<Map<String, Object>> stopCasting(String sinkName) {
        return currentTarget.stopCasting(sinkName);
    }
    
    /**
     * Define permissões.
     *
     * @param origin origem
     * @param permissions lista de permissões
     * @return CompletableFuture que completa quando definido
     */
    public CompletableFuture<Void> setPermissions(String origin, List<String> permissions) {
        Map<String, Object> args = new HashMap<>();
        args.put("origin", origin);
        args.put("permissions", permissions);
        args.put("browserContextId", contextId);
        
        return getBaseTarget().executeCdpCmd("Browser.grantPermissions", args, null)
            .thenApply(v -> null);
    }
    
    /**
     * Define comportamento de download.
     *
     * @param behavior comportamento (allow, deny, default)
     * @param downloadPath caminho de download
     * @return CompletableFuture que completa quando definido
     */
    public CompletableFuture<Void> setDownloadBehaviour(String behavior, String downloadPath) {
        Map<String, Object> args = new HashMap<>();
        args.put("behavior", behavior);
        args.put("browserContextId", contextId);
        if (downloadPath != null) {
            args.put("downloadPath", downloadPath);
        }
        
        return getBaseTarget().executeCdpCmd("Browser.setDownloadBehavior", args, null)
            .thenApply(v -> null);
    }
    
    /**
     * Cria nova janela neste contexto.
     *
     * @param typeHint tipo de janela (tab ou window)
     * @return CompletableFuture com o novo target
     */
    public CompletableFuture<Target> newWindow(String typeHint) {
        Map<String, Object> args = new HashMap<>();
        args.put("url", "about:blank");
        if (typeHint != null) {
            args.put("newWindow", "window".equals(typeHint));
        }
        // Só incluir browserContextId se não for null (contexto padrão)
        if (contextId != null) {
            args.put("browserContextId", contextId);
        }
        
        return getBaseTarget().executeCdpCmd("Target.createTarget", args, null)
            .thenApply(result -> {
                String newTargetId = result.get("targetId").asText();
                
                // Criar novo target
                Target newTarget = new Target(
                    host, newTargetId, driver, this,
                    isRemote, 30.0f, "page", false, maxWsSize
                );
                
                targets.put(newTargetId, newTarget);
                return newTarget;
            });
    }
    
    /**
     * Obtém diretório de downloads.
     *
     * @return CompletableFuture com caminho
     */
    public CompletableFuture<String> getDownloadsDir() {
        return CompletableFuture.completedFuture(getBaseTarget().downloadsDirForContext(contextId));
    }
    
    /**
     * Obtém info do target atual.
     *
     * @return CompletableFuture com info
     */
    public CompletableFuture<Map<String, Object>> getCurrentTargetInfo() {
        return currentTarget.getInfo();
    }
    
    /**
     * Fecha o contexto.
     *
     * @return CompletableFuture que completa quando o contexto é fechado
     */
    public CompletableFuture<Void> quit() {
        return CompletableFuture.runAsync(() -> {
            // Fechar todos os targets
            for (Target target : targets.values()) {
                try {
                    target.close().join();
                } catch (Exception e) {
                    // Ignorar erros de fechamento
                }
            }
            
            // Executar callbacks de fechamento
            for (Runnable callback : closedCallbacks) {
                try {
                    callback.run();
                } catch (Exception e) {
                    // Ignorar erros
                }
            }
        });
    }
    
    @Override
    public void close() {
        quit().join();
    }
    
    /**
     * Retorna o ID do contexto.
     *
     * @return ID do contexto
     */
    public String getContextId() {
        return contextId;
    }
    
    /**
     * Verifica se é um contexto incognito.
     *
     * @return true se incognito
     */
    public boolean isIncognito() {
        return isIncognito;
    }
    
    /**
     * Captura screenshot da janela atual como PNG.
     *
     * @return CompletableFuture com os bytes da imagem
     */
    public CompletableFuture<byte[]> getScreenshotAsPng() {
        return currentTarget.getScreenshotAsPng();
    }
    
    /**
     * Salva screenshot em arquivo.
     *
     * @param filename caminho do arquivo (deve terminar com .png)
     * @return CompletableFuture que completa quando o arquivo é salvo
     */
    public CompletableFuture<Void> getScreenshotAsFile(String filename) {
        return currentTarget.getScreenshotAsFile(filename);
    }
    
    /**
     * Alias para getScreenshotAsFile.
     *
     * @param filename caminho do arquivo
     * @return CompletableFuture que completa quando o arquivo é salvo
     */
    public CompletableFuture<Void> saveScreenshot(String filename) {
        return currentTarget.saveScreenshot(filename);
    }
    
    /**
     * Obtém snapshot como MHTML.
     *
     * @return CompletableFuture com o MHTML
     */
    public CompletableFuture<String> snapshot() {
        return currentTarget.snapshot();
    }
    
    /**
     * Salva snapshot em arquivo MHTML.
     *
     * @param filename caminho do arquivo (deve terminar com .mhtml)
     * @return CompletableFuture que completa quando o arquivo é salvo
     */
    public CompletableFuture<Void> saveSnapshot(String filename) {
        return currentTarget.saveSnapshot(filename);
    }
    
    /**
     * Define condições de emulação de rede.
     *
     * @param offline se está offline
     * @param latency latência em ms
     * @param downloadThroughput throughput de download
     * @param uploadThroughput throughput de upload
     * @param connectionType tipo de conexão
     * @return CompletableFuture que completa quando as condições são definidas
     */
    public CompletableFuture<Void> setNetworkConditions(boolean offline, int latency,
                                                        int downloadThroughput, int uploadThroughput,
                                                        String connectionType) {
        return currentTarget.setNetworkConditions(offline, latency, 
            downloadThroughput, uploadThroughput, connectionType);
    }
    
    /**
     * Envia teclas para o target atual.
     *
     * @param text texto a enviar
     * @return CompletableFuture que completa quando as teclas são enviadas
     */
    public CompletableFuture<Void> sendKeys(String text) {
        return currentTarget.sendKeys(text);
    }
    
    /**
     * Aguarda por um evento CDP.
     *
     * @param event nome do evento
     * @param timeout timeout em segundos
     * @return CompletableFuture com os parâmetros do evento
     */
    public CompletableFuture<JsonNode> waitForCdp(String event, Float timeout) {
        return currentTarget.waitForCdp(event, timeout);
    }
    
    /**
     * Executa um comando CDP.
     *
     * @param cmd nome do comando
     * @param args argumentos
     * @param timeout timeout em segundos
     * @return CompletableFuture com o resultado
     */
    public CompletableFuture<JsonNode> executeCdpCmd(String cmd, Map<String, Object> args, Float timeout) {
        return currentTarget.executeCdpCmd(cmd, args, timeout);
    }
}

