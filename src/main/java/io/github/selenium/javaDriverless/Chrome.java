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

package io.github.selenium.javaDriverless;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import io.github.selenium.javaDriverless.input.Keyboard;
import io.github.selenium.javaDriverless.input.Pointer;
import io.github.selenium.javaDriverless.scripts.Prefs;
import io.github.selenium.javaDriverless.scripts.SwitchTo;
import io.github.selenium.javaDriverless.types.*;
import io.github.selenium.javaDriverless.utils.Utils;

import org.asynchttpclient.AsyncHttpClient;
import org.asynchttpclient.DefaultAsyncHttpClient;

import java.util.function.Consumer;

import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

/**
 * Controla navegadores baseados em Chromium sem nenhum driver.
 * <p>
 * Esta é a classe principal do framework que gerencia:
 * - Processo do Chrome
 * - Sessões e contextos
 * - Targets (abas/frames)
 * - Configurações
 * </p>
 *
 * <h3>Exemplo de uso:</h3>
 * <pre>{@code
 * ChromeOptions options = new ChromeOptions();
 * Chrome.create(options).thenCompose(driver -> {
 *     return driver.get("https://example.com", true)
 *         .thenCompose(v -> driver.getTitle())
 *         .thenAccept(System.out::println)
 *         .thenCompose(v -> driver.quit());
 * }).join();
 * }</pre>
 */
public class Chrome implements AutoCloseable {
    
    private static final ObjectMapper objectMapper = new ObjectMapper();
    
    private final ChromeOptions options;
    private final float timeout;
    private final boolean debug;
    private final int maxWsSize;
    
    private Map<String, Object> prefs = new HashMap<>();
    private Boolean authInterceptionEnabled;
    private BaseTarget baseContext;
    private Process process;
    private Target currentTarget;
    private String host;
    private Integer browserPid;
    private BaseTarget baseTarget;
    private Context currentContext;
    private final Map<String, Context> contexts = new ConcurrentHashMap<>();
    private Path tempDir;
    private final Map<String, Map<String, String>> auth = new HashMap<>();
    private boolean isRemote;
    private boolean hasIncognitoContexts;
    private boolean started = false;
    
    /**
     * Cria uma nova instância do Chrome.
     * <p>
     * Inicia o serviço e então cria uma nova instância do target Chrome.
     * </p>
     *
     * @param options instância de ChromeOptions
     * @param timeout timeout em segundos para iniciar o Chrome
     * @param debug redirecionar erros do processo Chrome para console
     * @param maxWsSize tamanho máximo para mensagens websocket em bytes (padrão: 2^27 ~= 130 MB)
     */
    private Chrome(ChromeOptions options, float timeout, boolean debug, int maxWsSize) {
        this.options = (options != null) ? options : new ChromeOptions();
        this.timeout = timeout;
        this.debug = debug;
        this.maxWsSize = maxWsSize;
        
        // Garantir que o binário do Chrome está configurado
        if (this.options.getBinaryLocation() == null) {
            this.options.setBinaryLocation(Utils.findChromeExecutable());
        }
    }
    
    /**
     * Método estático para criar e iniciar uma instância do Chrome.
     *
     * @param options opções do Chrome
     * @return CompletableFuture com a instância iniciada
     */
    public static CompletableFuture<Chrome> create(ChromeOptions options) {
        return create(options, 30.0f, false, 1 << 27);
    }
    
    /**
     * Método estático para criar e iniciar uma instância do Chrome.
     *
     * @param options opções do Chrome
     * @param timeout timeout em segundos
     * @param debug modo debug
     * @param maxWsSize tamanho máximo de mensagens
     * @return CompletableFuture com a instância iniciada
     */
    public static CompletableFuture<Chrome> create(ChromeOptions options, float timeout, 
                                                   boolean debug, int maxWsSize) {
        Chrome chrome = new Chrome(options, timeout, debug, maxWsSize);
        return chrome.startSession().thenApply(v -> chrome);
    }
    
    /**
     * Inicia a sessão do Chrome.
     *
     * @return CompletableFuture que completa quando a sessão está pronta
     */
    public CompletableFuture<Chrome> startSession() {
        if (started) {
            return CompletableFuture.completedFuture(this);
        }
        
        return CompletableFuture.supplyAsync(() -> {
            try {
                // Verificar primeira execução
                Utils.isFirstRun().join();
                
                // Configurar porta de debug se não especificada
                if (options.getDebuggerAddress() == null) {
                    int port = Utils.randomPort();
                    options.setDebuggerAddress("127.0.0.1:" + port);
                    options.addArgument("--remote-debugging-port=" + port);
                }
                
                // Configurar user-agent para headless
                if (options.isHeadless() && !isRemote) {
                    String userAgent = Utils.getDefaultUA().join();
                    if (userAgent != null) {
                        options.addArgument("--user-agent=" + userAgent);
                    }
                }
                
                // Configurar diretório de dados do usuário
                if (options.getUserDataDir() == null) {
                    tempDir = Files.createTempDirectory("selenium_driverless_");
                    options.setUserDataDir(tempDir.toString());
                }
                
                // Escrever preferências
                Path prefsPath = Paths.get(options.getUserDataDir(), "Default", "Preferences");
                Files.createDirectories(prefsPath.getParent());
                prefs.putAll(options.getPrefs());
                Prefs.writePrefs(prefs, prefsPath).join();
                
                // Configurar proxy ANTES de iniciar o Chrome (via argumentos de linha de comando)
                if (options.getSingleProxy() != null) {
                    try {
                        configureProxyViaArgs(options.getSingleProxy());
                    } catch (Exception e) {
                        System.err.println("Erro ao configurar proxy: " + e.getMessage());
                        e.printStackTrace();
                    }
                }
                
                // Adicionar startup URL se especificada
                if (options.getStartupUrl() != null && !options.getStartupUrl().isEmpty()) {
                    options.addArgument(options.getStartupUrl());
                }
                
                isRemote = options.isRemote();
                
                // Iniciar processo Chrome se não for remoto
                if (!isRemote) {
                    List<String> command = new ArrayList<>();
                    command.add(options.getBinaryLocation());
                    command.addAll(options.getArguments());
                    
                    ProcessBuilder pb = new ProcessBuilder(command);
                    pb.environment().putAll(options.getEnv());
                    
                    if (!debug) {
                        pb.redirectError(ProcessBuilder.Redirect.DISCARD);
                    }
                    
                    process = pb.start();
                    browserPid = (int) process.pid();
                    
                    // Aguardar porta estar disponível
                    String[] parts = options.getDebuggerAddress().split(":");
                    int port = Integer.parseInt(parts[1]);
                    
                    if (port == 0) {
                        // Ler porta do arquivo DevToolsActivePort
                        Path portFile = Paths.get(options.getUserDataDir(), "DevToolsActivePort");
                        long startTime = System.nanoTime();
                        
                        while (!Files.exists(portFile)) {
                            if ((System.nanoTime() - startTime) / 1_000_000_000.0 > timeout) {
                                throw new RuntimeException("Timeout aguardando arquivo DevToolsActivePort");
                            }
                            Thread.sleep(100);
                        }
                        
                        String portContent = Files.readString(portFile);
                        port = Integer.parseInt(portContent.split("\n")[0]);
                        options.setDebuggerAddress("127.0.0.1:" + port);
                    }
                }
                
                // Conectar ao Chrome via CDP
                host = options.getDebuggerAddress();
                baseTarget = new BaseTarget(host, isRemote, timeout, maxWsSize);
                baseTarget.init().join();
                
                // Obter e corrigir user-agent para headless
                if (!isRemote) {
                    JsonNode versionInfo = baseTarget.executeCdpCmd("Browser.getVersion", null, null).join();
                    String userAgent = versionInfo.get("userAgent").asText();
                    userAgent = userAgent.replace("HeadlessChrome", "Chrome");
                    Utils.setDefaultUA(userAgent).join();
                }
                
                // Buscar target inicial (primeira aba)
                String targetsJson = getTargetsJson(host).join();
                JsonNode targets = objectMapper.readTree(targetsJson);
                
                for (JsonNode targetNode : targets) {
                    String type = targetNode.get("type").asText();
                    String url = targetNode.get("url").asText();
                    
                    if ("page".equals(type)) {
                        String targetId = targetNode.get("id").asText();
                        currentTarget = new Target(host, targetId, this, null, 
                            isRemote, timeout, "page", false, maxWsSize);
                        currentTarget.init().join();
                        break;
                    }
                }
                
                // Configurar emulação de foco (opcional - nem todas as versões do Chrome suportam)
                try {
                    baseTarget.executeCdpCmd("Emulation.setFocusEmulationEnabled", 
                        Map.of("enabled", true), null).join();
                } catch (Exception e) {
                    // Ignorar se não suportado
                    System.err.println("Aviso: Emulation.setFocusEmulationEnabled não suportado nesta versão do Chrome");
                }
                
                // Configurar diretório de downloads
                if (options.getDownloadsDir() != null) {
                    Map<String, Object> args = new HashMap<>();
                    args.put("behavior", "allowAndName");
                    args.put("downloadPath", options.getDownloadsDir());
                    baseTarget.executeCdpCmd("Browser.setDownloadBehavior", args, null).join();
                }
                
                // Inicializar contexto padrão (fix para newWindow)
                if (currentContext == null && currentTarget != null) {
                    Context defaultContext = new Context(
                        currentTarget, this, null, false, maxWsSize
                    );
                    currentContext = defaultContext;
                }
                
                started = true;
                return this;
                
            } catch (Exception e) {
                throw new RuntimeException("Erro ao iniciar sessão Chrome", e);
            }
        });
    }
    
    /**
     * Obtém JSON dos targets via HTTP.
     */
    private CompletableFuture<String> getTargetsJson(String host) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                AsyncHttpClient client = new DefaultAsyncHttpClient();
                String response = client.prepareGet("http://" + host + "/json")
                    .execute()
                    .toCompletableFuture()
                    .get((long) timeout, TimeUnit.SECONDS)
                    .getResponseBody();
                client.close();
                return response;
            } catch (Exception e) {
                throw new RuntimeException("Erro ao buscar targets", e);
            }
        });
    }
    
    /**
     * Retorna o target atual (aba ativa).
     *
     * @return CompletableFuture com o target atual
     */
    public CompletableFuture<Target> getCurrentTarget() {
        if (!started) {
            return startSession().thenApply(c -> currentTarget);
        }
        return CompletableFuture.completedFuture(currentTarget);
    }
    
    /**
     * Retorna o base target (conexão global com o Chrome).
     *
     * @return CompletableFuture com o base target
     */
    public CompletableFuture<BaseTarget> getBaseTarget() {
        if (!started) {
            return startSession().thenApply(c -> baseTarget);
        }
        return CompletableFuture.completedFuture(baseTarget);
    }
    
    /**
     * Obtém todos os targets (abas) do navegador.
     *
     * @return CompletableFuture com lista de targets
     */
    public CompletableFuture<List<Target>> getTargets() {
        return getCurrentTarget().thenCompose(target -> {
            Map<String, Object> args = new HashMap<>();
            if (currentContext != null && currentContext.getContextId() != null) {
                args.put("browserContextId", currentContext.getContextId());
            }
            
            return baseTarget.executeCdpCmd("Target.getTargets", args, null);
        }).thenApply(result -> {
            List<Target> targets = new ArrayList<>();
            JsonNode targetInfos = result.get("targetInfos");
            
            if (targetInfos != null && targetInfos.isArray()) {
                for (JsonNode targetInfo : targetInfos) {
                    String targetType = targetInfo.get("type").asText();
                    if ("page".equals(targetType) || "iframe".equals(targetType)) {
                        String targetId = targetInfo.get("targetId").asText();
                        
                        // Criar target
                        Target t = new Target(
                            host, targetId, this, currentContext,
                            isRemote, timeout, targetType, false, maxWsSize
                        );
                        
                        targets.add(t);
                    }
                }
            }
            
            return targets;
        });
    }
    
    /**
     * Encontra targets para uma lista de iframes.
     *
     * @param iframes lista de elementos iframe
     * @return CompletableFuture com lista de targets
     */
    public CompletableFuture<List<Target>> getTargetsForIframes(List<WebElement> iframes) {
        return getCurrentTarget().thenCompose(target -> target.getTargetsForIframes(iframes));
    }
    
    /**
     * Encontra target para um iframe específico.
     *
     * @param iframe elemento iframe
     * @return CompletableFuture com o target do iframe
     */
    public CompletableFuture<Target> getTargetForIframe(WebElement iframe) {
        return getCurrentTarget().thenCompose(target -> target.getTargetForIframe(iframe));
    }
    
    /**
     * Navega para uma URL no target atual.
     *
     * @param url URL para navegar
     * @param waitLoad se deve aguardar o carregamento
     * @return CompletableFuture que completa quando a navegação termina
     */
    public CompletableFuture<Map<String, Object>> get(String url, boolean waitLoad) {
        return getCurrentTarget().thenCompose(target -> target.get(url, waitLoad));
    }
    
    /**
     * Retorna o título da página atual.
     *
     * @return CompletableFuture com o título
     */
    public CompletableFuture<String> getTitle() {
        return getCurrentTarget().thenCompose(Target::getTitle);
    }
    
    /**
     * Retorna a URL atual.
     *
     * @return CompletableFuture com a URL
     */
    public CompletableFuture<String> getCurrentUrl() {
        return getCurrentTarget().thenCompose(Target::getCurrentUrl);
    }
    
    /**
     * Retorna o código-fonte da página.
     *
     * @return CompletableFuture com o HTML
     */
    public CompletableFuture<String> getPageSource() {
        return getCurrentTarget().thenCompose(Target::getPageSource);
    }
    
    /**
     * Executa JavaScript na página atual.
     *
     * @param script código JavaScript
     * @param args argumentos opcionais
     * @param awaitPromise se deve aguardar promises
     * @return CompletableFuture com o resultado
     */
    public CompletableFuture<Object> executeScript(String script, Object[] args, boolean awaitPromise) {
        return getCurrentTarget().thenCompose(target -> 
            target.executeScript(script, args, awaitPromise));
    }
    
    /**
     * Executa script JavaScript raw (forma bruta).
     *
     * @param script script JavaScript
     * @param awaitRes se deve aguardar resultado
     * @param serialization tipo de serialização
     * @param args argumentos
     * @return CompletableFuture com o resultado
     */
    public CompletableFuture<Object> executeRawScript(String script, boolean awaitRes, 
                                                      String serialization, Object... args) {
        return getCurrentTarget().thenCompose(target -> 
            target.executeRawScript(script, awaitRes, serialization, args));
    }
    
    /**
     * Executa script JavaScript assíncrono.
     *
     * @param script script JavaScript
     * @param maxDepth profundidade máxima de serialização
     * @param serialization tipo de serialização
     * @param args argumentos
     * @return CompletableFuture com o resultado
     */
    public CompletableFuture<Object> executeAsyncScript(String script, int maxDepth, 
                                                        String serialization, Object... args) {
        return getCurrentTarget().thenCompose(target -> 
            target.executeAsyncScript(script, maxDepth, serialization, args));
    }
    
    /**
     * Avalia expressão JavaScript assíncrona com await.
     *
     * @param script expressão JavaScript (pode usar await)
     * @param timeout timeout em segundos
     * @param uniqueContext se deve usar contexto isolado
     * @param args argumentos
     * @return CompletableFuture com o resultado
     */
    public CompletableFuture<Object> evalAsync(String script, float timeout, 
                                               boolean uniqueContext, Object... args) {
        return getCurrentTarget().thenCompose(target -> 
            target.evalAsync(script, timeout, uniqueContext, args));
    }
    
    /**
     * Obtém o alerta JavaScript atual.
     *
     * @param timeout timeout em segundos
     * @return CompletableFuture com o Alert
     */
    public CompletableFuture<Alert> getAlert(float timeout) {
        return getCurrentTarget().thenCompose(target -> target.getAlert(timeout));
    }
    
    /**
     * Aguarda por um evento CDP.
     *
     * @param event nome do evento
     * @param timeout timeout em segundos
     * @return CompletableFuture com os parâmetros do evento
     */
    public CompletableFuture<JsonNode> waitForCdp(String event, Float timeout) {
        return getCurrentTarget().thenCompose(target -> target.waitForCdp(event, timeout));
    }
    
    /**
     * Adiciona um listener para eventos CDP.
     *
     * @param event nome do evento
     * @param callback função callback
     * @return CompletableFuture que completa quando o listener é adicionado
     */
    public CompletableFuture<Void> addCdpListener(String event, Consumer<JsonNode> callback) {
        return getCurrentTarget().thenCompose(target -> target.addCdpListener(event, callback));
    }
    
    /**
     * Remove um listener de eventos CDP.
     *
     * @param event nome do evento
     * @param callback função callback
     * @return CompletableFuture que completa quando o listener é removido
     */
    public CompletableFuture<Void> removeCdpListener(String event, Consumer<JsonNode> callback) {
        return getCurrentTarget().thenCompose(target -> target.removeCdpListener(event, callback));
    }
    
    /**
     * Obtém um iterador de eventos CDP.
     *
     * @param event nome do evento
     * @return CompletableFuture com lista de eventos
     */
    public CompletableFuture<List<JsonNode>> getCdpEventIter(String event) {
        return getCurrentTarget().thenCompose(target -> target.getCdpEventIter(event));
    }
    
    /**
     * Obtém um target específico por ID.
     *
     * @param targetId ID do target
     * @return CompletableFuture com o target
     */
    public CompletableFuture<Target> getTarget(String targetId) {
        return getTargets().thenApply(targets -> {
            for (Target t : targets) {
                if (t.getId().equals(targetId)) {
                    return t;
                }
            }
            throw new RuntimeException("Target não encontrado: " + targetId);
        });
    }
    
    /**
     * Obtém todos os contextos do navegador.
     *
     * @return lista de contextos
     */
    public List<Context> getContexts() {
        return new ArrayList<>(contexts.values());
    }
    
    /**
     * Obtém o contexto atual.
     *
     * @return contexto atual
     */
    public Context getCurrentContext() {
        return currentContext;
    }
    
    /**
     * Obtém o contexto base (não-incognito).
     *
     * @return contexto base
     */
    public Context getBaseContext() {
        for (Context ctx : contexts.values()) {
            if (!ctx.isIncognito()) {
                return ctx;
            }
        }
        return currentContext; // fallback
    }
    
    /**
     * Obtém o ponteiro do target atual.
     *
     * @return Pointer atual
     */
    public Pointer getCurrentPointer() {
        return currentTarget.getPointer();
    }
    
    /**
     * Obtém o Keyboard (teclado) do target atual.
     *
     * @return Keyboard atual
     */
    public Keyboard getCurrentKeyboard() {
        return new Keyboard(currentTarget);
    }
    
    /**
     * Obtém informações do target atual.
     *
     * @return CompletableFuture com as informações
     */
    public CompletableFuture<Map<String, Object>> getCurrentTargetInfo() {
        return currentTarget.getInfo();
    }
    
    /**
     * Obtém o PID do processo Chrome.
     *
     * @return PID do navegador
     */
    public long getBrowserPid() {
        return browserPid != null ? browserPid : -1;
    }
    
    /**
     * Obtém o host (endereço do debugger).
     *
     * @return host
     */
    public String getHost() {
        return host;
    }
    
    /**
     * Extrai todos os arquivos de um ZIP.
     *
     * @param zipPath caminho do arquivo ZIP
     * @param extractPath caminho de destino
     * @return CompletableFuture que completa quando extraído
     */
    public CompletableFuture<Void> extractall(String zipPath, String extractPath) {
        return CompletableFuture.runAsync(() -> {
            try {
                java.nio.file.Path destDir = java.nio.file.Paths.get(extractPath);
                java.nio.file.Files.createDirectories(destDir);
                
                try (java.util.zip.ZipInputStream zis = new java.util.zip.ZipInputStream(
                        java.nio.file.Files.newInputStream(java.nio.file.Paths.get(zipPath)))) {
                    
                    java.util.zip.ZipEntry entry;
                    while ((entry = zis.getNextEntry()) != null) {
                        java.nio.file.Path filePath = destDir.resolve(entry.getName());
                        
                        if (entry.isDirectory()) {
                            java.nio.file.Files.createDirectories(filePath);
                        } else {
                            java.nio.file.Files.createDirectories(filePath.getParent());
                            java.nio.file.Files.copy(zis, filePath, 
                                java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                        }
                        zis.closeEntry();
                    }
                }
            } catch (Exception e) {
                throw new RuntimeException("Erro ao extrair arquivo: " + e.getMessage(), e);
            }
        });
    }
    
    /**
     * Limpa diretórios temporários de forma síncrona.
     */
    public void cleanDirsSync() {
        if (tempDir != null && java.nio.file.Files.exists(tempDir)) {
            try {
                deleteDirectory(tempDir);
            } catch (Exception e) {
                // Ignorar erros
            }
        }
    }
    
    /**
     * Foca na janela do target atual.
     *
     * @return CompletableFuture que completa quando focado
     */
    public CompletableFuture<Void> focus() {
        return getCurrentTarget().thenCompose(Target::focus);
    }
    
    /**
     * Obtém o diretório de downloads para o contexto atual.
     *
     * @return CompletableFuture com o caminho do diretório
     */
    public CompletableFuture<String> getDownloadsDir() {
        return CompletableFuture.completedFuture(baseTarget.downloadsDirForContext(currentContext.getContextId()));
    }
    
    /**
     * Obtém a árvore de frames da página atual.
     *
     * @return CompletableFuture com a árvore de frames
     */
    public CompletableFuture<Map<String, Object>> getFrameTree() {
        return getCurrentTarget().thenCompose(target -> target.getFrameTree());
    }
    
    /**
     * Define condições de emulação de rede.
     *
     * @param offline se deve estar offline
     * @param latency latência em ms
     * @param downloadThroughput taxa de download em bytes/s
     * @param uploadThroughput taxa de upload em bytes/s
     * @return CompletableFuture que completa quando as condições são definidas
     */
    public CompletableFuture<Void> setNetworkConditions(boolean offline, int latency,
                                                        int downloadThroughput, int uploadThroughput) {
        return getCurrentTarget().thenCompose(target -> 
            target.setNetworkConditions(offline, latency, downloadThroughput, uploadThroughput, "wifi"));
    }
    
    /**
     * Obtém as condições de rede atuais.
     *
     * @return CompletableFuture com as condições
     */
    public CompletableFuture<Map<String, Object>> getNetworkConditions() {
        return getCurrentTarget().thenCompose(target -> target.getNetworkConditions());
    }
    
    /**
     * Remove condições de emulação de rede.
     *
     * @return CompletableFuture que completa quando as condições são removidas
     */
    public CompletableFuture<Void> deleteNetworkConditions() {
        return getCurrentTarget().thenCompose(target -> target.deleteNetworkConditions());
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
        return getBaseTarget().thenCompose(bt -> bt.executeCdpCmd(cmd, cmdArgs, timeout));
    }
    
    /**
     * Busca um elemento na página.
     *
     * @param by estratégia de busca
     * @param value valor da busca
     * @param timeout timeout em segundos
     * @return CompletableFuture com o elemento
     */
    public CompletableFuture<WebElement> findElement(String by, String value, float timeout) {
        return getCurrentTarget().thenCompose(target -> target.findElement(by, value, timeout));
    }
    
    /**
     * Busca múltiplos elementos na página.
     *
     * @param by estratégia de busca
     * @param value valor da busca
     * @param timeout timeout em segundos
     * @return CompletableFuture com lista de elementos
     */
    public CompletableFuture<List<WebElement>> findElements(String by, String value, float timeout) {
        return getCurrentTarget().thenCompose(target -> target.findElements(by, value, timeout));
    }
    
    /**
     * Busca elementos usando query textual.
     *
     * @param query query de busca
     * @return CompletableFuture com lista de elementos
     */
    public CompletableFuture<List<WebElement>> searchElements(String query) {
        return getCurrentTarget().thenCompose(target -> target.searchElements(query));
    }
    
    /**
     * Salva screenshot (alias para getScreenshotAsFile).
     *
     * @param filename caminho do arquivo
     * @return CompletableFuture que completa quando salvo
     */
    public CompletableFuture<Void> saveScreenshot(String filename) {
        return getScreenshotAsFile(filename);
    }
    
    /**
     * Obtém snapshot MHTML da página.
     *
     * @return CompletableFuture com o MHTML
     */
    public CompletableFuture<String> snapshot() {
        return getCurrentTarget().thenCompose(target -> target.snapshot());
    }
    
    /**
     * Salva snapshot em arquivo MHTML.
     *
     * @param filename caminho do arquivo
     * @return CompletableFuture que completa quando salvo
     */
    public CompletableFuture<Void> saveSnapshot(String filename) {
        return getCurrentTarget().thenCompose(target -> target.saveSnapshot(filename));
    }
    
    /**
     * Define comportamento de download.
     *
     * @param behaviour comportamento (allow, deny, allowAndName, default)
     * @param downloadPath caminho de download
     * @return CompletableFuture que completa quando configurado
     */
    public CompletableFuture<Void> setDownloadBehaviour(String behaviour, String downloadPath) {
        return getBaseTarget().thenCompose(bt -> {
            Map<String, Object> args = new HashMap<>();
            args.put("behavior", behaviour);
            
            if (currentContext != null && currentContext.getContextId() != null) {
                args.put("browserContextId", currentContext.getContextId());
            }
            
            if (downloadPath != null) {
                args.put("downloadPath", downloadPath);
            }
            
            return bt.executeCdpCmd("Browser.setDownloadBehavior", args, null)
                .thenApply(v -> null);
        });
    }
    
    /**
     * Cria novo contexto de navegação.
     *
     * @param incognito se deve ser incognito
     * @return CompletableFuture com o novo contexto
     */
    public CompletableFuture<Context> newContext(boolean incognito) {
        return getBaseTarget().thenCompose(bt -> {
            if (incognito) {
                return bt.executeCdpCmd("Target.createBrowserContext", 
                    Map.of("disposeOnDetach", true), null);
            } else {
                return CompletableFuture.completedFuture(null);
            }
        }).thenApply(result -> {
            String contextId = null;
            if (result != null && result.has("browserContextId")) {
                contextId = result.get("browserContextId").asText();
            }
            
            Target baseTargetForContext = new Target(
                host, "context-" + (contextId != null ? contextId : "default"),
                this, null, isRemote, timeout, "page", false, maxWsSize
            );
            
            Context newContext = new Context(baseTargetForContext, this, contextId, incognito, maxWsSize);
            
            if (contextId != null) {
                contexts.put(contextId, newContext);
            }
            
            if (incognito) {
                hasIncognitoContexts = true;
            }
            
            return newContext;
        });
    }
    
    /**
     * Cria nova janela.
     *
     * @param typeHint tipo (tab ou window)
     * @return CompletableFuture com o novo target
     */
    public CompletableFuture<Target> newWindow(String typeHint) {
        return currentContext.newWindow(typeHint);
    }
    
    /**
     * Retorna objeto SwitchTo.
     *
     * @return SwitchTo
     */
    public SwitchTo getSwitchTo() {
        return currentContext.switchTo();
    }
    
    /**
     * Retorna objeto SwitchTo (alias).
     *
     * @return SwitchTo
     */
    public SwitchTo switchTo() {
        return getSwitchTo();
    }
    
    /**
     * Define autenticação para host.
     *
     * @param username nome de usuário
     * @param password senha
     * @param hostWithPort host com porta
     * @return CompletableFuture que completa quando configurado
     */
    public CompletableFuture<Void> setAuth(String username, String password, String hostWithPort) {
        auth.put(hostWithPort, Map.of("username", username, "password", password));
        
        if (authInterceptionEnabled == null || !authInterceptionEnabled) {
            return ensureAuthInterception();
        }
        
        return CompletableFuture.completedFuture(null);
    }
    
    /**
     * Garante que interceptação de autenticação está habilitada.
     */
    private CompletableFuture<Void> ensureAuthInterception() {
        if (authInterceptionEnabled != null && authInterceptionEnabled) {
            return CompletableFuture.completedFuture(null);
        }
        
        return getBaseTarget().thenCompose(bt -> {
            Map<String, Object> args = new HashMap<>();
            args.put("enabled", true);
            
            return bt.executeCdpCmd("Fetch.enable", args, null);
        }).thenRun(() -> {
            authInterceptionEnabled = true;
            
            getBaseTarget().thenAccept(bt -> {
                bt.addCdpListener("Fetch.authRequired", event -> {
                    String requestId = event.get("requestId").asText();
                    JsonNode request = event.get("request");
                    String url = request.get("url").asText();
                    
                    for (Map.Entry<String, Map<String, String>> entry : auth.entrySet()) {
                        if (url.contains(entry.getKey())) {
                            Map<String, String> creds = entry.getValue();
                            
                            Map<String, Object> response = new HashMap<>();
                            response.put("response", "ProvideCredentials");
                            response.put("username", creds.get("username"));
                            response.put("password", creds.get("password"));
                            
                            Map<String, Object> authArgs = new HashMap<>();
                            authArgs.put("requestId", requestId);
                            authArgs.put("authChallengeResponse", response);
                            
                            bt.executeCdpCmd("Fetch.continueWithAuth", authArgs, null).join();
                            return;
                        }
                    }
                    
                    Map<String, Object> authArgs = new HashMap<>();
                    authArgs.put("requestId", requestId);
                    authArgs.put("authChallengeResponse", Map.of("response", "CancelAuth"));
                    bt.executeCdpCmd("Fetch.continueWithAuth", authArgs, null).join();
                }).join();
            }).join();
        });
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
        return getCurrentTarget().thenCompose(Target::back);
    }
    
    /**
     * Avança para a próxima página.
     *
     * @return CompletableFuture que completa quando a ação termina
     */
    public CompletableFuture<Void> forward() {
        return getCurrentTarget().thenCompose(Target::forward);
    }
    
    /**
     * Recarrega a página.
     *
     * @param ignoreCache se deve ignorar o cache
     * @return CompletableFuture que completa quando a ação termina
     */
    public CompletableFuture<Void> refresh(boolean ignoreCache) {
        return getCurrentTarget().thenCompose(t -> t.refresh(ignoreCache));
    }
    
    /**
     * Recarrega a página (sem ignorar cache).
     *
     * @return CompletableFuture que completa quando a ação termina
     */
    public CompletableFuture<Void> refresh() {
        return refresh(false);
    }
    
    /**
     * Obtém todos os cookies.
     *
     * @return CompletableFuture com a lista de cookies
     */
    public CompletableFuture<List<Map<String, Object>>> getCookies() {
        return getCurrentTarget().thenCompose(Target::getCookies);
    }
    
    /**
     * Obtém um cookie pelo nome.
     *
     * @param name nome do cookie
     * @return CompletableFuture com o cookie
     */
    public CompletableFuture<Map<String, Object>> getCookie(String name) {
        return getCurrentTarget().thenCompose(t -> t.getCookie(name));
    }
    
    /**
     * Adiciona um cookie.
     *
     * @param cookieDict dicionário do cookie
     * @return CompletableFuture que completa quando o cookie é adicionado
     */
    public CompletableFuture<Void> addCookie(Map<String, Object> cookieDict) {
        return getCurrentTarget().thenCompose(t -> t.addCookie(cookieDict));
    }
    
    /**
     * Deleta um cookie.
     *
     * @param name nome do cookie
     * @return CompletableFuture que completa quando o cookie é deletado
     */
    public CompletableFuture<Void> deleteCookie(String name) {
        return getCurrentTarget().thenCompose(t -> t.deleteCookie(name));
    }
    
    /**
     * Deleta todos os cookies.
     *
     * @return CompletableFuture que completa quando os cookies são deletados
     */
    public CompletableFuture<Void> deleteAllCookies() {
        return getCurrentTarget().thenCompose(Target::deleteAllCookies);
    }
    
    /**
     * Captura screenshot.
     *
     * @return CompletableFuture com os bytes PNG
     */
    public CompletableFuture<byte[]> getScreenshotAsPng() {
        return getCurrentTarget().thenCompose(Target::getScreenshotAsPng);
    }
    
    /**
     * Salva screenshot em arquivo.
     *
     * @param filename caminho do arquivo
     * @return CompletableFuture que completa quando o arquivo é salvo
     */
    public CompletableFuture<Void> getScreenshotAsFile(String filename) {
        return getCurrentTarget().thenCompose(t -> t.getScreenshotAsFile(filename));
    }
    
    /**
     * Envia teclas.
     *
     * @param text texto a enviar
     * @return CompletableFuture que completa quando as teclas são enviadas
     */
    public CompletableFuture<Void> sendKeys(String text) {
        return getCurrentTarget().thenCompose(t -> t.sendKeys(text));
    }
    
    /**
     * Obtém todos os handles de janelas.
     *
     * @return CompletableFuture com lista de window handles
     */
    public CompletableFuture<List<String>> getWindowHandles() {
        return getTargets().thenApply(targets -> {
            List<String> handles = new ArrayList<>();
            for (Target target : targets) {
                handles.add(target.getId());
            }
            return handles;
        });
    }
    
    /**
     * Obtém o handle da janela atual.
     *
     * @return CompletableFuture com o window handle atual
     */
    public CompletableFuture<String> getCurrentWindowHandle() {
        return CompletableFuture.completedFuture(currentTarget.getId());
    }
    
    /**
     * Obtém o ID da janela atual.
     *
     * @return CompletableFuture com o window ID
     */
    public CompletableFuture<Integer> getCurrentWindowId() {
        return currentTarget.getWindowId();
    }
    
    /**
     * Maximiza a janela.
     *
     * @return CompletableFuture que completa quando a janela é maximizada
     */
    public CompletableFuture<Void> maximizeWindow() {
        return setWindowState("maximized");
    }
    
    /**
     * Minimiza a janela.
     *
     * @return CompletableFuture que completa quando a janela é minimizada
     */
    public CompletableFuture<Void> minimizeWindow() {
        return setWindowState("minimized");
    }
    
    /**
     * Coloca a janela em fullscreen.
     *
     * @return CompletableFuture que completa quando a janela está em fullscreen
     */
    public CompletableFuture<Void> fullscreenWindow() {
        return setWindowState("fullscreen");
    }
    
    /**
     * Normaliza a janela (sai de maximizado/minimizado/fullscreen).
     *
     * @return CompletableFuture que completa quando a janela é normalizada
     */
    public CompletableFuture<Void> normalizeWindow() {
        return setWindowState("normal");
    }
    
    /**
     * Define o estado da janela.
     *
     * @param state estado desejado (normal, minimized, maximized, fullscreen)
     * @return CompletableFuture que completa quando o estado é definido
     */
    public CompletableFuture<Void> setWindowState(String state) {
        return getCurrentWindowId().thenCompose(windowId -> {
            Map<String, Object> args = new HashMap<>();
            args.put("windowId", windowId);
            
            Map<String, Object> bounds = new HashMap<>();
            bounds.put("windowState", state);
            args.put("bounds", bounds);
            
            return baseTarget.executeCdpCmd("Browser.setWindowBounds", args, null)
                .thenApply(v -> null);
        });
    }
    
    /**
     * Obtém a posição da janela.
     *
     * @return CompletableFuture com x e y
     */
    public CompletableFuture<Map<String, Object>> getWindowPosition() {
        return getWindowRect().thenApply(rect -> {
            Map<String, Object> position = new HashMap<>();
            position.put("x", rect.get("x"));
            position.put("y", rect.get("y"));
            return position;
        });
    }
    
    /**
     * Define a posição da janela.
     *
     * @param x posição X
     * @param y posição Y
     * @return CompletableFuture que completa quando a posição é definida
     */
    public CompletableFuture<Void> setWindowPosition(int x, int y) {
        return getCurrentWindowId().thenCompose(windowId -> {
            Map<String, Object> args = new HashMap<>();
            args.put("windowId", windowId);
            
            Map<String, Object> bounds = new HashMap<>();
            bounds.put("left", x);
            bounds.put("top", y);
            args.put("bounds", bounds);
            
            return baseTarget.executeCdpCmd("Browser.setWindowBounds", args, null)
                .thenApply(v -> null);
        });
    }
    
    /**
     * Obtém o retângulo (bounds) da janela.
     *
     * @return CompletableFuture com x, y, width, height
     */
    public CompletableFuture<Map<String, Object>> getWindowRect() {
        return getCurrentWindowId().thenCompose(windowId -> {
            Map<String, Object> args = new HashMap<>();
            args.put("windowId", windowId);
            
            return baseTarget.executeCdpCmd("Browser.getWindowBounds", args, null);
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
     * Define o retângulo (bounds) da janela.
     *
     * @param x posição X
     * @param y posição Y
     * @param width largura
     * @param height altura
     * @return CompletableFuture que completa quando o bounds é definido
     */
    public CompletableFuture<Void> setWindowRect(int x, int y, int width, int height) {
        return getCurrentWindowId().thenCompose(windowId -> {
            Map<String, Object> args = new HashMap<>();
            args.put("windowId", windowId);
            
            Map<String, Object> bounds = new HashMap<>();
            bounds.put("left", x);
            bounds.put("top", y);
            bounds.put("width", width);
            bounds.put("height", height);
            args.put("bounds", bounds);
            
            return baseTarget.executeCdpCmd("Browser.setWindowBounds", args, null)
                .thenApply(v -> null);
        });
    }
    
    /**
     * Obtém o tamanho da janela.
     *
     * @return CompletableFuture com width e height
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
     * Define o tamanho da janela.
     *
     * @param width largura
     * @param height altura
     * @return CompletableFuture que completa quando o tamanho é definido
     */
    public CompletableFuture<Void> setWindowSize(int width, int height) {
        return getWindowPosition().thenCompose(position -> {
            int x = (Integer) position.get("x");
            int y = (Integer) position.get("y");
            return setWindowRect(x, y, width, height);
        });
    }
    
    /**
     * Remove (fecha) um contexto específico.
     *
     * @param context contexto para remover
     * @return CompletableFuture que completa quando o contexto é removido
     */
    public CompletableFuture<Void> removeContext(Context context) {
        return context.quit().thenRun(() -> {
            contexts.remove(context.getContextId());
        });
    }
    
    /**
     * Define permissões para uma origem.
     *
     * @param origin origem (URL)
     * @param permissions lista de permissões
     * @return CompletableFuture que completa quando as permissões são definidas
     */
    public CompletableFuture<Void> setPermissions(String origin, List<String> permissions) {
        Map<String, Object> args = new HashMap<>();
        args.put("origin", origin);
        args.put("permissions", permissions);
        args.put("browserContextId", currentContext.getContextId());
        
        return baseTarget.executeCdpCmd("Browser.grantPermissions", args, null)
            .thenApply(v -> null);
    }
    
    /**
     * Define proxy para o contexto atual.
     *
     * @param proxyServer servidor proxy
     * @param bypassList lista de bypass
     * @return CompletableFuture que completa quando o proxy é definido
     */
    public CompletableFuture<Void> setProxy(String proxyServer, List<String> bypassList) {
        Map<String, Object> args = new HashMap<>();
        args.put("proxyServer", proxyServer);
        if (bypassList != null && !bypassList.isEmpty()) {
            args.put("proxyBypassList", String.join(",", bypassList));
        }
        
        return baseTarget.executeCdpCmd("Network.setProxy", args, null)
            .thenApply(v -> null);
    }
    
    /**
     * Configura proxy via argumentos de linha de comando do Chrome.
     * Esta é a abordagem mais confiável para configurar proxies.
     *
     * @param proxy string do proxy (ex: "http://user:pass@host:port/")
     */
    private void configureProxyViaArgs(String proxy) {
        try {
            // Parse proxy string
            String proxyStr = proxy;
            String scheme = null;
            String creds = null;
            String host;
            Integer port = null;
            
            // Parse scheme
            if (proxyStr.contains("://")) {
                String[] parts = proxyStr.split("://", 2);
                scheme = parts[0];
                proxyStr = parts[1];
            }
            
            // Parse credentials
            if (proxyStr.contains("@")) {
                String[] parts = proxyStr.split("@", 2);
                creds = parts[0];
                proxyStr = parts[1];
            }
            
            // Parse host and port
            proxyStr = proxyStr.replace("/", "");
            if (proxyStr.contains(":")) {
                String[] parts = proxyStr.split(":", 2);
                host = parts[0];
                port = Integer.parseInt(parts[1]);
            } else {
                host = proxyStr;
            }
            
            // Build proxy server string (apenas host:port, sem autenticação)
            String proxyServer = host + (port != null ? ":" + port : "");
            
            // Configure proxy via Chrome arguments (apenas proxy sem autenticação)
            // Nota: Proxies com autenticação não são suportados
            if (creds != null) {
                System.err.println("Aviso: Autenticação de proxy não é suportada. Configurando apenas proxy sem autenticação: " + proxyServer);
            }
            
            options.addArgument("--proxy-server=" + proxyServer);
            
            System.out.println("Proxy configurado via argumentos: " + proxyServer);
            
        } catch (Exception e) {
            throw new RuntimeException("Erro ao configurar proxy via argumentos: " + e.getMessage(), e);
        }
    }
    
    /**
     * Define um proxy único dinamicamente para todos os contextos.
     * Suporta formato: "http://host:port/" ou "socks5://host:port/"
     * 
     * Nota: Proxies com autenticação não são suportados. Use apenas proxies sem autenticação.
     *
     * @param proxy string do proxy (ex: "http://example.com:8080/" ou "socks5://example.com:1080/")
     * @param bypassList lista de bypass (opcional)
     * @return CompletableFuture que completa quando o proxy é configurado
     */
    public CompletableFuture<Void> setSingleProxy(String proxy, List<String> bypassList) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                // Parse proxy string
                String proxyStr = proxy;
                String scheme = null;
                String creds = null;
                String host;
                Integer port = null;
                
                // Parse scheme
                if (proxyStr.contains("://")) {
                    String[] parts = proxyStr.split("://", 2);
                    scheme = parts[0];
                    proxyStr = parts[1];
                }
                
                // Parse credentials (ignorar se presente)
                if (proxyStr.contains("@")) {
                    String[] parts = proxyStr.split("@", 2);
                    creds = parts[0];
                    proxyStr = parts[1];
                    System.err.println("Aviso: Autenticação de proxy não é suportada. Ignorando credenciais.");
                }
                
                // Parse host and port
                proxyStr = proxyStr.replace("/", "");
                if (proxyStr.contains(":")) {
                    String[] parts = proxyStr.split(":", 2);
                    host = parts[0];
                    port = Integer.parseInt(parts[1]);
                } else {
                    host = proxyStr;
                }
                
                // Build proxy server string (sem autenticação)
                String proxyServer = host + (port != null ? ":" + port : "");
                
                // Apply proxy using the correct CDP command
                setProxy(proxyServer, bypassList).join();
                
                return null;
            } catch (Exception e) {
                throw new RuntimeException("Erro ao configurar proxy único: " + e.getMessage(), e);
            }
        });
    }
    
    /**
     * Limpa o proxy.
     *
     * @return CompletableFuture que completa quando o proxy é limpo
     */
    public CompletableFuture<Void> clearProxy() {
        return baseTarget.executeCdpCmd("Network.clearProxy", null, null)
            .thenApply(v -> null);
    }
    
    /**
     * Aguarda por um download.
     *
     * @param timeout timeout em segundos
     * @return CompletableFuture com dados do download
     */
    public CompletableFuture<Map<String, Object>> waitDownload(float timeout) {
        return currentTarget.waitDownload(timeout);
    }
    
    /**
     * Executa uma requisição fetch.
     *
     * @param url URL
     * @param method método HTTP
     * @param headers headers
     * @param body corpo da requisição
     * @param timeout timeout
     * @return CompletableFuture com a resposta
     */
    public CompletableFuture<Map<String, Object>> fetch(
        String url, String method, Map<String, String> headers, Object body, float timeout
    ) {
        return currentTarget.fetch(url, method, headers, body, timeout);
    }
    
    /**
     * Executa uma requisição XHR.
     *
     * @param url URL
     * @param method método HTTP
     * @param headers headers
     * @param body corpo da requisição
     * @param timeout timeout
     * @return CompletableFuture com a resposta
     */
    public CompletableFuture<Map<String, Object>> xhr(
        String url, String method, Map<String, String> headers, Object body, float timeout
    ) {
        return currentTarget.xhr(url, method, headers, body, timeout);
    }
    
    /**
     * Imprime a página como PDF.
     *
     * @return CompletableFuture com o PDF em base64
     */
    public CompletableFuture<String> printPage() {
        return currentTarget.printPage();
    }
    
    /**
     * Obtém sinks disponíveis para casting.
     *
     * @return CompletableFuture com lista de sinks
     */
    public CompletableFuture<List<Map<String, Object>>> getSinks() {
        return currentTarget.getSinks();
    }
    
    /**
     * Obtém mensagem de issue de casting.
     *
     * @return CompletableFuture com a mensagem
     */
    public CompletableFuture<String> getIssueMessage() {
        return currentTarget.getIssueMessage();
    }
    
    /**
     * Define sink para usar no casting.
     *
     * @param sinkName nome do sink
     * @return CompletableFuture com o resultado
     */
    public CompletableFuture<Map<String, Object>> setSinkToUse(String sinkName) {
        return currentTarget.setSinkToUse(sinkName);
    }
    
    /**
     * Inicia mirroring de desktop.
     *
     * @param sinkName nome do sink
     * @return CompletableFuture com o resultado
     */
    public CompletableFuture<Map<String, Object>> startDesktopMirroring(String sinkName) {
        return currentTarget.startDesktopMirroring(sinkName);
    }
    
    /**
     * Inicia mirroring de tab.
     *
     * @param sinkName nome do sink
     * @return CompletableFuture com o resultado
     */
    public CompletableFuture<Map<String, Object>> startTabMirroring(String sinkName) {
        return currentTarget.startTabMirroring(sinkName);
    }
    
    /**
     * Para o casting.
     *
     * @param sinkName nome do sink
     * @return CompletableFuture com o resultado
     */
    public CompletableFuture<Map<String, Object>> stopCasting(String sinkName) {
        return currentTarget.stopCasting(sinkName);
    }
    
    /**
     * Fecha o Chrome e limpa recursos.
     *
     * @param cleanDirs se deve limpar diretórios temporários
     * @return CompletableFuture que completa quando o fechamento termina
     */
    public CompletableFuture<Void> quit(boolean cleanDirs) {
        return CompletableFuture.runAsync(() -> {
            try {
                // Fechar target atual
                if (currentTarget != null) {
                    currentTarget.close().join();
                }
                
                // Fechar base target
                if (baseTarget != null) {
                    baseTarget.close().join();
                }
                
                // Fechar processo Chrome
                if (process != null && process.isAlive()) {
                    process.destroy();
                    process.waitFor(5, TimeUnit.SECONDS);
                    
                    if (process.isAlive()) {
                        process.destroyForcibly();
                    }
                }
                
                // Limpar diretórios temporários
                if (cleanDirs && tempDir != null && Files.exists(tempDir)) {
                    deleteDirectory(tempDir);
                }
                
            } catch (Exception e) {
                throw new RuntimeException("Erro ao fechar Chrome", e);
            }
        });
    }
    
    /**
     * Fecha o Chrome com limpeza automática.
     *
     * @return CompletableFuture que completa quando o fechamento termina
     */
    public CompletableFuture<Void> quit() {
        return quit(options.isAutoCleanDirs());
    }
    
    /**
     * Implementação de AutoCloseable para uso com try-with-resources.
     */
    @Override
    public void close() {
        quit().join();
    }
    
    /**
     * Deleta um diretório recursivamente.
     */
    private void deleteDirectory(Path directory) throws IOException {
        if (Files.exists(directory)) {
            Files.walk(directory)
                .sorted(Comparator.reverseOrder())
                .forEach(path -> {
                    try {
                        Files.delete(path);
                    } catch (IOException e) {
                        // Ignorar erros de deleção
                    }
                });
        }
    }
    
    @Override
    public String toString() {
        return String.format("<%s.%s (host=\"%s\", pid=%d)>",
            getClass().getPackage().getName(),
            getClass().getSimpleName(),
            host,
            browserPid
        );
    }
}

