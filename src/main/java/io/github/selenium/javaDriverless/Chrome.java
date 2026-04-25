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
// Ported from: https://github.com/kaliiiiiiiiii/Selenium-Driverless
// Original Author: kaliiiiiiiiii | Aurin Aegerter
// Java Port: Vitor Camillo (io.github.vitorcamillo)
// Logic: Chrome DevTools Protocol (CDP) direct automation without driver

package io.github.selenium.javaDriverless;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import io.github.selenium.javaDriverless.input.Keyboard;
import io.github.selenium.javaDriverless.input.Pointer;
import io.github.selenium.javaDriverless.scripts.Prefs;
import io.github.selenium.javaDriverless.scripts.SwitchTo;
import io.github.selenium.javaDriverless.logging.JavaDriverlessLogger;
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

import org.slf4j.Logger;

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
 *
 * <pre>{@code
 * ChromeOptions options = new ChromeOptions();
 * Chrome.create(options).thenCompose(driver -> {
 *     return driver.get("https://example.com", true)
 *             .thenCompose(v -> driver.getTitle())
 *             .thenAccept(System.out::println)
 *             .thenCompose(v -> driver.quit());
 * }).join();
 * }</pre>
 */
public class Chrome implements AutoCloseable {

    private static final Logger logger = JavaDriverlessLogger.getLogger(Chrome.class);
    private static final ObjectMapper objectMapper = new ObjectMapper();
    private static final boolean UNLOCK_STALE_PROFILE_LOCKS = envBool(
            "JAVA_DRIVERLESS_UNLOCK_STALE_PROFILE_LOCKS", true);

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
    private final StringBuilder startupOutputTail = new StringBuilder();
    private final Object startupOutputLock = new Object();
    private Thread startupOutputReader;

    /**
     * Cria uma nova instância do Chrome.
     * <p>
     * Inicia o serviço e então cria uma nova instância do target Chrome.
     * </p>
     *
     * @param options   instância de ChromeOptions
     * @param timeout   timeout em segundos para iniciar o Chrome
     * @param debug     redirecionar erros do processo Chrome para console
     * @param maxWsSize tamanho máximo para mensagens websocket em bytes (padrão:
     *                  2^27 ~= 130 MB)
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
     * @param options   opções do Chrome
     * @param timeout   timeout em segundos
     * @param debug     modo debug
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

                // Configurar user-agent para parecer Chrome real (não HeadlessChrome)
                if (!isRemote) {
                    String userAgent = Utils.getDefaultUA().join();
                    if (userAgent == null || userAgent.contains("HeadlessChrome")) {
                        // Usar user-agent de Chrome real de Windows
                        userAgent = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/123.0.0.0 Safari/537.36";
                    }
                    options.addArgument("--user-agent=" + userAgent);
                    Utils.setDefaultUA(userAgent).join();
                }

                // Configurar diretório de dados do usuário
                if (options.getUserDataDir() == null) {
                    tempDir = Files.createTempDirectory("selenium_driverless_");
                    options.setUserDataDir(tempDir.toString());
                }
                unlockStaleProfileLocks(Paths.get(options.getUserDataDir()));

                // Escrever preferências
                Path prefsPath = Paths.get(options.getUserDataDir(), "Default", "Preferences");
                Files.createDirectories(prefsPath.getParent());
                prefs.putAll(options.getPrefs());
                Prefs.writePrefs(prefs, prefsPath).join();

                // Configurar proxy ANTES de iniciar o Chrome (via argumentos de linha de
                // comando)
                if (options.getSingleProxy() != null) {
                    try {
                        configureProxyViaArgs(options.getSingleProxy());
                    } catch (Exception e) {
                        JavaDriverlessLogger.warn(logger, "Erro ao configurar proxy: {}", e.getMessage());
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

                    JavaDriverlessLogger.info(logger, "Iniciando Chrome local. binary={}, debuggerAddress={}, userDataDir={}",
                            options.getBinaryLocation(), options.getDebuggerAddress(), options.getUserDataDir());
                    JavaDriverlessLogger.debug(logger, "Comando completo do Chrome: {}", command);

                    ProcessBuilder pb = new ProcessBuilder(command);
                    pb.environment().putAll(options.getEnv());
                    pb.redirectErrorStream(true);

                    process = pb.start();
                    browserPid = (int) process.pid();
                    startProcessOutputCapture(process);

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

                // Buscar target inicial (primeira aba) ANTES de injetar script
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

                // Configurar diretório de downloads
                if (options.getDownloadsDir() != null) {
                    Map<String, Object> args = new HashMap<>();
                    args.put("behavior", "allowAndName");
                    args.put("downloadPath", options.getDownloadsDir());
                    currentTarget.executeCdpCmd("Browser.setDownloadBehavior", args, null).join();
                }

                // Inicializar contexto padrão (fix para newWindow)
                if (currentContext == null && currentTarget != null) {
                    Context defaultContext = new Context(
                            currentTarget, this, null, false, maxWsSize);
                    currentContext = defaultContext;
                }

                // Inject anti-detection script AFTER target is initialized
                try {
                    injectAntiDetectionScript().join();
                } catch (Exception e) {
                    JavaDriverlessLogger.warn(logger, "AntiDetection: falha ao injetar script: {}", e.getMessage());
                }

                // Aplicar spoofing de device metrics (with error handling to not block startup)
                try {
                    setRealisticDeviceMetrics().join();
                } catch (Exception e) {
                    JavaDriverlessLogger.warn(logger, "DeviceMetrics: falha ao configurar métricas: {}", e.getMessage());
                }

                // Sobrescrever user-agent via CDP para todas as requisições
                if (!isRemote) {
                    String userAgent = Utils.getDefaultUA().join();
                    if (userAgent.contains("HeadlessChrome")) {
                        userAgent = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/123.0.0.0 Safari/537.36";
                        Utils.setDefaultUA(userAgent).join();
                    }

                    // Apply via Network.setUserAgentOverride for network-level spoofing
                    try {
                        Map<String, Object> uaArgs = new HashMap<>();
                        uaArgs.put("userAgent", userAgent);
                        currentTarget.executeCdpCmd("Network.setUserAgentOverride", uaArgs, null).join();
                    } catch (Exception e) {
                        JavaDriverlessLogger.warn(logger, "UserAgent: falha ao aplicar override: {}", e.getMessage());
                    }
                }

                started = true;
                return this;

            } catch (Exception e) {
                StringBuilder diag = new StringBuilder();
                diag.append("Erro ao iniciar sessão Chrome");
                if (!isRemote) {
                    diag.append(" | binary=").append(options.getBinaryLocation());
                    diag.append(" | debuggerAddress=").append(options.getDebuggerAddress());
                    diag.append(" | userDataDir=").append(options.getUserDataDir());
                    if (process != null) {
                        diag.append(" | processAlive=").append(process.isAlive());
                        if (!process.isAlive()) {
                            try {
                                diag.append(" | processExitCode=").append(process.exitValue());
                            } catch (Exception ignore) {
                                // ignore
                            }
                        }
                    }
                    String tail = getStartupOutputTail();
                    if (JavaDriverlessLogger.isChromeTailOnErrorEnabled() && !tail.isBlank()) {
                        diag.append(" | chromeOutputTail=").append(tail.replace("\n", " \\n "));
                    }
                }
                throw new RuntimeException(diag.toString(), e);
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

    private void startProcessOutputCapture(Process process) {
        if (process == null) return;
        startupOutputReader = new Thread(() -> {
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    appendStartupOutput(line);
                    if (debug || JavaDriverlessLogger.isChromeLogsEnabled()) {
                        JavaDriverlessLogger.chromeProcess(logger, line);
                    }
                }
            } catch (Exception e) {
                appendStartupOutput("[output-reader-error] " + e.getMessage());
            }
        }, "chrome-startup-output-reader");
        startupOutputReader.setDaemon(true);
        startupOutputReader.start();
    }

    private void appendStartupOutput(String line) {
        synchronized (startupOutputLock) {
            startupOutputTail.append(line).append('\n');
            int maxChars = 12000;
            if (startupOutputTail.length() > maxChars) {
                startupOutputTail.delete(0, startupOutputTail.length() - maxChars);
            }
        }
    }

    private String getStartupOutputTail() {
        synchronized (startupOutputLock) {
            return startupOutputTail.toString();
        }
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
                                isRemote, timeout, targetType, false, maxWsSize);

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
     * @param url      URL para navegar
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
     * @param script       código JavaScript
     * @param args         argumentos opcionais
     * @param awaitPromise se deve aguardar promises
     * @return CompletableFuture com o resultado
     */
    public CompletableFuture<Object> executeScript(String script, Object[] args, boolean awaitPromise) {
        return getCurrentTarget().thenCompose(target -> target.executeScript(script, args, awaitPromise));
    }

    /**
     * Executa script JavaScript raw (forma bruta).
     *
     * @param script        script JavaScript
     * @param awaitRes      se deve aguardar resultado
     * @param serialization tipo de serialização
     * @param args          argumentos
     * @return CompletableFuture com o resultado
     */
    public CompletableFuture<Object> executeRawScript(String script, boolean awaitRes,
            String serialization, Object... args) {
        return getCurrentTarget().thenCompose(target -> target.executeRawScript(script, awaitRes, serialization, args));
    }

    /**
     * Executa script JavaScript assíncrono.
     *
     * @param script        script JavaScript
     * @param maxDepth      profundidade máxima de serialização
     * @param serialization tipo de serialização
     * @param args          argumentos
     * @return CompletableFuture com o resultado
     */
    public CompletableFuture<Object> executeAsyncScript(String script, int maxDepth,
            String serialization, Object... args) {
        return getCurrentTarget()
                .thenCompose(target -> target.executeAsyncScript(script, maxDepth, serialization, args));
    }

    /**
     * Avalia expressão JavaScript assíncrona com await.
     *
     * @param script        expressão JavaScript (pode usar await)
     * @param timeout       timeout em segundos
     * @param uniqueContext se deve usar contexto isolado
     * @param args          argumentos
     * @return CompletableFuture com o resultado
     */
    public CompletableFuture<Object> evalAsync(String script, float timeout,
            boolean uniqueContext, Object... args) {
        return getCurrentTarget().thenCompose(target -> target.evalAsync(script, timeout, uniqueContext, args));
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
     * @param event   nome do evento
     * @param timeout timeout em segundos
     * @return CompletableFuture com os parâmetros do evento
     */
    public CompletableFuture<JsonNode> waitForCdp(String event, Float timeout) {
        return getCurrentTarget().thenCompose(target -> target.waitForCdp(event, timeout));
    }

    /**
     * Adiciona um listener para eventos CDP.
     *
     * @param event    nome do evento
     * @param callback função callback
     * @return CompletableFuture que completa quando o listener é adicionado
     */
    public CompletableFuture<Void> addCdpListener(String event, Consumer<JsonNode> callback) {
        return getCurrentTarget().thenCompose(target -> target.addCdpListener(event, callback));
    }

    /**
     * Remove um listener de eventos CDP.
     *
     * @param event    nome do evento
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
     * @param zipPath     caminho do arquivo ZIP
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
     * @param offline            se deve estar offline
     * @param latency            latência em ms
     * @param downloadThroughput taxa de download em bytes/s
     * @param uploadThroughput   taxa de upload em bytes/s
     * @return CompletableFuture que completa quando as condições são definidas
     */
    public CompletableFuture<Void> setNetworkConditions(boolean offline, int latency,
            int downloadThroughput, int uploadThroughput) {
        return getCurrentTarget().thenCompose(
                target -> target.setNetworkConditions(offline, latency, downloadThroughput, uploadThroughput, "wifi"));
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
     * @param cmd     nome do comando
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
     * @param by      estratégia de busca
     * @param value   valor da busca
     * @param timeout timeout em segundos
     * @return CompletableFuture com o elemento
     */
    public CompletableFuture<WebElement> findElement(String by, String value, float timeout) {
        return getCurrentTarget().thenCompose(target -> target.findElement(by, value, timeout));
    }

    /**
     * Busca múltiplos elementos na página.
     *
     * @param by      estratégia de busca
     * @param value   valor da busca
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
     * @param behaviour    comportamento (allow, deny, allowAndName, default)
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
                    this, null, isRemote, timeout, "page", false, maxWsSize);

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
     * @param username     nome de usuário
     * @param password     senha
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
     * Script JavaScript para spoof de anti-detecção.
     * Este script é injetado em cada nova página para evitar detecção de bot.
     * Versão melhorada 2.0 - Aborda todos os 10 pontos de detecção conhecidos.
     */
    /**
     * Anti-detection script - COMPREHENSIVE version 4.0
     *
     * KEY PRINCIPLE from pixelscan.net: "The problem is INCONSISTENCY, not the presence of spoofing"
     *
     * All spoofed values MUST be consistent across multiple page loads.
     * REMOVED all Math.random() calls - they cause fingerprint inconsistency.
     *
     * Based on: puppeteer-extra-plugin-stealth, selenium-stealth, antic detection research
     */
    private static final String ANTI_DETECTION_SCRIPT =
        "(function() {" +
        "'use strict';" +

        // ============================================================" +
        // 1. NAVIGATOR.WEBDRIVER - Fix using prototype approach (stealth pattern)" +
        // ============================================================" +
        "Object.defineProperty(navigator, 'webdriver', {get: () => false, configurable: true, enumerable: true, writable: true});" +

        // ============================================================" +
        // 2. CHROME OBJECT - Based on puppeteer-extra-plugin-stealth pattern" +
        // IMPORTANT: Only mock chrome.runtime if it doesn't exist (headful has it)" +
        // ============================================================" +
        "if (!window.chrome) {" +
        "  Object.defineProperty(window, 'chrome', {" +
        "    writable: true," +
        "    enumerable: true," +
        "    configurable: false," +  // note: must be false like in real Chrome
        "    value: {}" +
        "  });" +
        "}" +
        "// Only mock chrome.runtime if it doesn't exist (headful mode already has it)" +
        "if (!('runtime' in window.chrome)) {" +
        "  window.chrome.runtime = {" +
        "    connect: function connect() { return { onMessage: { addListener: function(){} }, onDisconnect: { addListener: function(){} }, postMessage: function(){}, disconnect: function(){} }; }," +
        "    sendMessage: function sendMessage() { return Promise.resolve({}); }," +
        "    getManifest: function getManifest() { return { manifest_version: 3, name: 'Chrome', version: '123.0.0.0' }; }," +
        "    getURL: function getURL(path) { return 'chrome-extension://jbiepmmlhdimnamibkegfhiciejcjolo/' + path; }," +
        "    id: undefined, // Real Chrome returns undefined for id" +
        "    lastError: null," +
        "    onInstalled: { addListener: function(){}, removeListener: function(){}, hasListener: function(){} }," +
        "    onStartup: { addListener: function(){} }," +
        "    onUpdateAvailable: { addListener: function(){} }," +
        "    onUpdateFound: { addListener: function(){} }," +
        "    requestUpdateCheck: function() { return Promise.resolve({ status: 'throttled' }); }," +
        "    reload: function(){}," +
        "    restart: function(){}" +
        "  };" +
        "}" +

        // Add other chrome properties if missing (stealth pattern)" +
        "if (!window.chrome.loadTimes) {" +
        "  window.chrome.loadTimes = function() {" +
        "    var now = Date.now() / 1000;" +
        "    return {" +
        "      commitTime: 0.500," +
        "      connectionInfo: 'h2'," +
        "      finishTime: 0.100," +
        "      firstPaintAfterLoadTime: 0," +
        "      firstPaintTime: 0.800," +
        "      firstMeaningfulPaintTime: 0," +
        "      firstMeaningfulPaintCandidateTime: 0," +
        "      navigationType: 'Other'," +
        "      navigationStart: now - 1," +
        "      loadType: 1," +
        "      referrerPolicy: 'no-referrer-when-downgrade'," +
        "      navigationEntryCommitted: false" +
        "    };" +
        "  };" +
        "}" +
        "if (!window.chrome.csi) {" +
        "  window.chrome.csi = function() {" +
        "    var now = Date.now();" +
        "    return { timestamp: now, startTime: now - 1000 };" +
        "  };" +
        "}" +
        "if (!window.chrome.app) {" +
        "  window.chrome.app = {" +
        "    isInstalled: false," +
        "    InstallState: { DISABLED: 'disabled', INSTALLED: 'installed', NOT_INSTALLED: 'not_installed' }," +
        "    RunningState: { CANNOT_RUN: 'cannot_run', RUNNING: 'running', READY: 'ready' }," +
        "    getDetails: function() { return { id: 'jbiepmmlhdimnamibkegfhiciejcjolo' }; }," +
        "    getIsInstalled: function() { return false; }" +
        "  };" +
        "}" +
        "if (!window.chrome.storage) {" +
        "  window.chrome.storage = {" +
        "    local: { get: function(k, cb) { cb && cb({}); }, set: function(i, cb) { cb && cb(); }, remove: function(k, cb) { cb && cb(); }, clear: function(cb) { cb && cb(); }, getBytesInUse: function(k, cb) { cb && cb(0); } }," +
        "    session: { get: function(k, cb) { cb && cb({}); }, set: function(i, cb) { cb && cb(); }, remove: function(k, cb) { cb && cb(); }, clear: function(cb) { cb && cb(); } }," +
        "    managed: { get: function(k, cb) { cb && cb({}); }, set: function(i, cb) { cb && cb(); } }," +
        "    sync: { get: function(k, cb) { cb && cb({}); }, set: function(i, cb) { cb && cb(); } }" +
        "  };" +
        "}" +
        "if (!window.chrome.tabs) {" +
        "  window.chrome.tabs = { getCurrent: function(cb) { cb && cb(null); }, create: function(){}, get: function(){}, update: function(){}, remove: function(){}, query: function(){}, detectLanguage: function(tabId, cb) { cb && cb('en'); } };" +
        "}" +
        "if (!window.chrome.webstore) {" +
        "  window.chrome.webstore = { onInstallStateChanged: { addListener: function(){}, removeListener: function(){}, hasListener: function(){} }, isInstalled: false, install: function(){}, getIsInstalled: function() { return false; } };" +
        "}" +
        "if (!window.chrome.windows) {" +
        "  window.chrome.windows = { get: function(){}, getCurrent: function(){}, create: function(){}, update: function(){}, remove: function(){}, getAll: function(){}, getFocused: function(){} };" +
        "}" +
        "if (!window.chrome.commands) {" +
        "  window.chrome.commands = { getAll: function() { return Promise.resolve([]); } };" +
        "}" +
        "if (!window.chrome.i18n) {" +
        "  window.chrome.i18n = { getMessage: function(msg, subs) { return msg || ''; }, getUILanguage: function() { return navigator.language || 'en'; }, getAcceptLanguages: function() { return navigator.languages || ['en']; } };" +
        "}" +
        "if (!window.chrome.idle) {" +
        "  window.chrome.idle = { queryState: function() { return Promise.resolve('active'); }, addListener: function(){} };" +
        "}" +
        "if (!window.chrome.notifications) {" +
        "  window.chrome.notifications = { create: function(){}, getAll: function(){}, clear: function(){}, onClicked: { addListener: function(){} } };" +
        "}" +
        "if (!window.chrome.power) {" +
        "  window.chrome.power = { requestKeepAwake: function(){}, releaseKeepAwake: function(){} };" +
        "}" +
        "if (!window.chrome.system) {" +
        "  window.chrome.system = { cpu: { getInfo: function() { return Promise.resolve({}); } }, memory: { getInfo: function() { return Promise.resolve({}); } }, storage: { getInfo: function() { return Promise.resolve({}); } } };" +
        "}" +
        "if (!window.chrome.pointerLock) {" +
        "  window.chrome.pointerLock = { exitPointerLock: function(){}, screen: { width: 1920, height: 1080 } };" +
        "}" +
        "if (!window.chrome.vivaldi) window.chrome.vivaldi = null;" +
        "if (!window.chrome.session) {" +
        "  window.chrome.session = { installPage: function(){}, getPreset: function(){}, get: function(){}, set: function(){}, clear: function(){} };" +
        "}" +
        "if (!window.chrome.event) {" +
        "  window.chrome.event = function() { return { addListener: function(){}, removeListener: function(){}, hasListener: function(){}, getListenerKeys: function() { return []; } }; };" +
        "}" +

        // ============================================================" +
        // 3. NAVIGATOR.PLUGINS - Mimic headful Chrome plugins array" +
        // Based on puppeteer-extra-plugin-stealth navigator.plugins" +
        // ============================================================" +
        "(function() {" +
        "  // Only mock if plugins array is empty (headless)" +
        "  if (navigator.plugins && navigator.plugins.length > 0) return;" +
        "  var plugins = [" +
        "    { name: 'Chrome PDF Plugin', filename: 'internal-pdf-viewer', description: 'Portable Document Format viewer', length: 0 }," +
        "    { name: 'Chrome PDF Viewer', filename: 'mhjfbmdgcfgjoadjgmfgcdmkdgbpian', description: 'Chrome PDF Viewer', length: 0 }," +
        "    { name: 'Native Client', filename: 'internal-nacl-plugin', description: 'Native Client', length: 0 }" +
        "  ];" +
        "  plugins.length = 3;" +
        "  plugins.item = function(i) { return this[i] || null; };" +
        "  plugins.namedItem = function(name) {" +
        "    for (var i = 0; i < this.length; i++) {" +
        "      if (this[i].name === name) return this[i];" +
        "    }" +
        "    return null;" +
        "  };" +
        "  plugins.refresh = function(){};" +
        "  Object.defineProperty(navigator, 'plugins', {" +
        "    get: function() { return plugins; }," +
        "    configurable: true," +
        "    enumerable: true" +
        "  });" +
        "  // Also define mimeTypes" +
        "  var mimeTypes = [];" +
        "  mimeTypes.length = 0;" +
        "  mimeTypes.item = function(i) { return this[i] || null; };" +
        "  mimeTypes.namedItem = function(name) {" +
        "    for (var i = 0; i < this.length; i++) {" +
        "      if (this[i].type === name) return this[i]; } return null;" +
        "  };" +
        "  Object.defineProperty(navigator, 'mimeTypes', {" +
        "    get: function() { return mimeTypes; }," +
        "    configurable: true," +
        "    enumerable: true" +
        "  });" +
        "})();" +

        // ============================================================" +
        // 4. NAVIGATOR.LANGUAGES - Realistic language array" +
        // ============================================================" +
        "Object.defineProperty(navigator, 'languages', {" +
        "  get: function() { return ['en-US', 'en', 'pt-BR', 'pt']; }," +
        "  configurable: true," +
        "  enumerable: true" +
        "});" +

        // ============================================================" +
        // 5. NAVIGATOR.MEDIADEVICES - Based on stealth pattern" +
        // ============================================================" +
        "(function() {" +
        "  // Only mock if mediaDevices doesn't exist or is empty" +
        "  if (navigator.mediaDevices && navigator.mediaDevices.enumerateDevices) return;" +
        "  var devices = [" +
        "    { kind: 'audioinput', deviceId: 'default', groupId: 'group_0', label: 'Default Audio Input', toJSON: function() { return Object.assign({}, this); } }," +
        "    { kind: 'audiooutput', deviceId: 'default', groupId: 'group_0', label: 'Default Audio Output', toJSON: function() { return Object.assign({}, this); } }," +
        "    { kind: 'videoinput', deviceId: 'default', groupId: 'group_0', label: 'FaceTime HD Camera', toJSON: function() { return Object.assign({}, this); } }" +
        "  ];" +
        "  var mockMediaDevices = {" +
        "    getUserMedia: function(constraints) {" +
        "      return Promise.resolve({ getTracks: function() { return []; }, getAudioTracks: function() { return []; }, getVideoTracks: function() { return []; }, getTrackById: function(id) { return null; }, addTrack: function(){}, removeTrack: function(){}, addEventListener: function(){}, removeEventListener: function(){}, dispatchEvent: function() { return true; } });" +
        "    }," +
        "    enumerateDevices: function() {" +
        "      return Promise.resolve(devices.map(function(d) { return Object.assign({}, d); }));" +
        "    }," +
        "    getSupportedConstraints: function() {" +
        "      return { width: true, height: true, aspectRatio: true, frameRate: true, facingMode: true, resizeMode: true, volume: true, sampleRate: true, sampleSize: true, echoCancellation: true, latency: true, channelCount: true, deviceId: true, groupId: true, displaySurface: true, logicalSurface: true, cursor: true };" +
        "    }," +
        "    addEventListener: function(){}," +
        "    removeEventListener: function(){}," +
        "    dispatchEvent: function() { return true; }," +
        "    ondevicechange: null" +
        "  };" +
        "  Object.defineProperty(navigator, 'mediaDevices', {" +
        "    get: function() { return mockMediaDevices; }," +
        "    configurable: true," +
        "    enumerable: true" +
        "  });" +
        "})();" +

        // ============================================================" +
        // 6. CRITICAL: USER-AGENT AND PLATFORM - PRESERVE REAL VALUES" +
        // In headful mode, the real UA and platform are already correct." +
        // Only override if we detect HeadlessChrome (headless mode)." +
        // Overriding a real Linux UA with Win32 is a DETECTION VECTOR!" +
        // ============================================================" +
        "(function() {" +
        "  var currentUA = navigator.userAgent || '';" +
        "  // Only override if headless is detected in UA" +
        "  if (currentUA.indexOf('HeadlessChrome') !== -1) {" +
        "    var fixedUA = currentUA.replace('HeadlessChrome', 'Chrome');" +
        "    try { delete navigator.userAgent; } catch(e) {}" +
        "    Object.defineProperty(navigator, 'userAgent', { get: function() { return fixedUA; }, configurable: true, enumerable: true });" +
        "  }" +
        "  // Platform: only override if it's empty or indicates headless" +
        "  var plat = navigator.platform;" +
        "  if (!plat || plat === '') {" +
        "    var guessedPlatform = 'Win32';" +
        "    if (currentUA.indexOf('Linux') !== -1) guessedPlatform = 'Linux x86_64';" +
        "    else if (currentUA.indexOf('Mac') !== -1) guessedPlatform = 'MacIntel';" +
        "    try { delete navigator.platform; } catch(e) {}" +
        "    Object.defineProperty(navigator, 'platform', { get: function() { return guessedPlatform; }, configurable: true, enumerable: true });" +
        "  }" +
        "})();" +

        // ============================================================" +
        // 7. NAVIGATOR PROPERTIES - PRESERVE REAL, ONLY MOCK IF MISSING" +
        // In headful mode, real values (e.g. 16 cores, 32GB RAM) are" +
        // MORE convincing than fake ones (8/8). Only override if the" +
        // values are missing or zero (headless mode)." +
        // ============================================================" +
        "(function() {" +
        "  // Only override hardwareConcurrency if it's 0 or missing" +
        "  if (!navigator.hardwareConcurrency || navigator.hardwareConcurrency < 2) {" +
        "    Object.defineProperty(navigator, 'hardwareConcurrency', { get: function() { return 8; }, configurable: true, enumerable: true });" +
        "  }" +
        "  // Only override deviceMemory if it's missing" +
        "  if (typeof navigator.deviceMemory === 'undefined' || navigator.deviceMemory < 2) {" +
        "    Object.defineProperty(navigator, 'deviceMemory', { get: function() { return 8; }, configurable: true, enumerable: true });" +
        "  }" +
        "})();" +
        "Object.defineProperty(navigator, 'vendor', { get: function() { return 'Google Inc.'; }, configurable: true, enumerable: true });" +
        "Object.defineProperty(navigator, 'maxTouchPoints', { get: function() { return 0; }, configurable: true, enumerable: true });" +
        "Object.defineProperty(navigator, 'doNotTrack', { get: function() { return null; }, configurable: true, enumerable: true });" +
        "Object.defineProperty(navigator, 'cookieEnabled', { get: function() { return true; }, configurable: true, enumerable: true });" +
        "Object.defineProperty(navigator, 'onLine', { get: function() { return true; }, configurable: true, enumerable: true });" +
        "Object.defineProperty(navigator, 'pdfViewerEnabled', { get: function() { return true; }, configurable: true, enumerable: true });" +
        "Object.defineProperty(navigator, 'geolocation', { get: function() { return { clearWatch: function(){}, getCurrentPosition: function(success) { success({ coords: { latitude: 0, longitude: 0, accuracy: 0 }, timestamp: Date.now() }); }, watchPosition: function() { return 0; } }; }, configurable: true, enumerable: true });" +

        // ============================================================" +
        // 8. PERMISSIONS API - Based on stealth pattern" +
        // ============================================================" +
        "(function() {" +
        "  if (navigator.permissions) return; // Already exists in headful" +
        "  var permissions = {" +
        "    query: function query(permDescriptor) {" +
        "      var name = (typeof permDescriptor === 'string') ? permDescriptor : (permDescriptor && permDescriptor.name) || 'default';" +
        "      var result = { state: 'prompt', onchange: null };" +
        "      if (name === 'geolocation') result.state = 'prompt';" +
        "      else if (name === 'notifications') result.state = 'denied';" +
        "      else if (name === 'persistent-storage') result.state = 'granted';" +
        "      else if (name === 'storage-access') result.state = 'prompt';" +
        "      else if (name === 'camera') result.state = 'prompt';" +
        "      else if (name === 'microphone') result.state = 'prompt';" +
        "      else result.state = 'granted';" +
        "      return Promise.resolve(result);" +
        "    }," +
        "    request: function(permission) { return this.query(permission); }," +
        "    revoke: function(permission) { return this.query(permission); }" +
        "  };" +
        "  Object.defineProperty(navigator, 'permissions', {" +
        "    get: function() { return permissions; }," +
        "    configurable: true," +
        "    enumerable: true" +
        "  });" +
        "})();" +

        // ============================================================" +
        // 9. SCREEN PROPERTIES" +
        // ============================================================" +
        "Object.defineProperty(screen, 'width', { get: function() { return 1920; }, configurable: false });" +
        "Object.defineProperty(screen, 'height', { get: function() { return 1080; }, configurable: false });" +
        "Object.defineProperty(screen, 'availWidth', { get: function() { return 1920; }, configurable: false });" +
        "Object.defineProperty(screen, 'availHeight', { get: function() { return 1040; }, configurable: false });" +
        "Object.defineProperty(screen, 'colorDepth', { get: function() { return 24; }, configurable: false });" +
        "Object.defineProperty(screen, 'pixelDepth', { get: function() { return 24; }, configurable: false });" +
        "Object.defineProperty(screen, 'availLeft', { get: function() { return 0; }, configurable: false });" +
        "Object.defineProperty(screen, 'availTop', { get: function() { return 0; }, configurable: false });" +
        "Object.defineProperty(screen, 'orientation', { get: function() { return { type: 'landscape-primary', angle: 0, onchange: null }; }, configurable: false });" +

        // ============================================================" +
        // 10. OUTER DIMENSIONS - Fix for headless missing outerWidth/outerHeight" +
        // Based on puppeteer-extra-plugin-stealth window.outerdimensions" +
        // ============================================================" +
        "(function() {" +
        "  try {" +
        "    if (window.outerWidth !== undefined && window.outerHeight !== undefined) return;" +
        "    var windowFrame = 85; // OS and WM dependent" +
        "    window.outerWidth = window.innerWidth;" +
        "    window.outerHeight = window.innerHeight + windowFrame;" +
        "  } catch(err) {}" +
        "})();" +

        // ============================================================" +
        // 11. WEBGL SPOOFING - Realistic GPU strings via proxy (stealth pattern)" +
        // ============================================================" +
        "(function() {" +
        "  if (typeof WebGLRenderingContext === 'undefined') return;" +
        "  var VENDOR = 'Intel Inc.';" +
        "  var RENDERER = 'Intel(R) UHD Graphics 630';" +
        "  var UNMASKED_VENDOR_WEBGL = 37445;" +
        "  var UNMASKED_RENDERER_WEBGL = 37446;" +

        "  // Proxy handler for getParameter" +
        "  var getParameterProxyHandler = {" +
        "    apply: function(target, ctx, args) {" +
        "      var param = (args || [])[0];" +
        "      var result = target.apply(ctx, args);" +
        "      if (param === UNMASKED_VENDOR_WEBGL) return VENDOR;" +
        "      if (param === UNMASKED_RENDERER_WEBGL) return RENDERER;" +
        "      return result;" +
        "    }" +
        "  };" +

        "  // Apply to WebGL1" +
        "  try {" +
        "    var origGetParameter1 = WebGLRenderingContext.prototype.getParameter;" +
        "    WebGLRenderingContext.prototype.getParameter = new Proxy(origGetParameter1, getParameterProxyHandler);" +
        "  } catch(e) {}" +

        "  // Apply to WebGL2" +
        "  if (typeof WebGL2RenderingContext !== 'undefined') {" +
        "    try {" +
        "      var origGetParameter2 = WebGL2RenderingContext.prototype.getParameter;" +
        "      WebGL2RenderingContext.prototype.getParameter = new Proxy(origGetParameter2, getParameterProxyHandler);" +
        "    } catch(e) {}" +
        "  }" +

        "  // Also spoof getExtension for WEBGL_debug_renderer_info" +
        "  try {" +
        "    var origGetExtension = WebGLRenderingContext.prototype.getExtension;" +
        "    WebGLRenderingContext.prototype.getExtension = function(name) {" +
        "      var ext = origGetExtension.call(this, name);" +
        "      if (name === 'WEBGL_debug_renderer_info' && ext) {" +
        "        ext.getParameter = new Proxy(ext.getParameter, getParameterProxyHandler);" +
        "      }" +
        "      return ext;" +
        "    };" +
        "  } catch(e) {}" +
        "})();" +

        // ============================================================" +
        // 12. CANVAS FINGERPRINT - STABLE (no modification)" +
        // KEY: consistency > modification (pixelscan principle)" +
        // ============================================================" +
        "(function() {" +
        "  // PASS-THROUGH: Let canvas return natural values" +
        "  // The key insight is that INCONSISTENCY causes detection, not the values themselves" +
        "  // We don't add any noise to ensure same values on every call" +
        "  var origToDataURL = HTMLCanvasElement.prototype.toDataURL;" +
        "  HTMLCanvasElement.prototype.toDataURL = function(type) {" +
        "    return origToDataURL.apply(this, arguments);" +
        "  };" +
        "  var origGetImageData = CanvasRenderingContext2D.prototype.getImageData;" +
        "  CanvasRenderingContext2D.prototype.getImageData = function(sx, sy, sw, sh) {" +
        "    return origGetImageData.call(this, sx, sy, sw, sh);" +
        "  };" +
        "  var origToBlob = HTMLCanvasElement.prototype.toBlob;" +
        "  HTMLCanvasElement.prototype.toBlob = function(cb, type, quality) {" +
        "    return origToBlob.call(this, cb, type, quality);" +
        "  };" +
        "})();" +

        // ============================================================" +
        // 13. AUDIO FINGERPRINT - STABLE (pass-through)" +
        // ============================================================" +
        "(function() {" +
        "  if (!window.AudioContext && !window.webkitAudioContext) return;" +
        "  var AudioCtx = window.AudioContext || window.webkitAudioContext;" +
        "  // Pass-through for consistency" +
        "  var origCreateAnalyser = AudioCtx.prototype.createAnalyser;" +
        "  AudioCtx.prototype.createAnalyser = function() { return origCreateAnalyser.call(this); };" +
        "  var origCreateOscillator = AudioCtx.prototype.createOscillator;" +
        "  AudioCtx.prototype.createOscillator = function() {" +
        "    var osc = origCreateOscillator.call(this);" +
        "    var origConnect = osc.connect;" +
        "    osc.connect = function(dest) { return origConnect.call(this, dest); };" +
        "    return osc;" +
        "  };" +
        "})();" +

        // ============================================================" +
        // 14. WEBRTC SPOOFING" +
        // ============================================================" +
        "(function() {" +
        "  if (!window.RTCPeerConnection && !window.webkitRTCPeerConnection) return;" +
        "  var RTCPeerConnection = window.RTCPeerConnection || window.webkitRTCPeerConnection;" +
        "  var OrigRTC = RTCPeerConnection;" +
        "  window.RTCPeerConnection = function(config) {" +
        "    var pc = new OrigRTC(config);" +
        "    pc.getConfiguration = function() { return config || { iceServers: [] }; };" +
        "    return pc;" +
        "  };" +
        "  window.RTCPeerConnection.prototype = OrigRTC.prototype;" +
        "  window.RTCPeerConnection.prototype.constructor = window.RTCPeerConnection;" +
        "})();" +

        // ============================================================" +
        // 15. CONNECTION INFO SPOOFING" +
        // ============================================================" +
        "(function() {" +
        "  if (navigator.connection || navigator.mozConnection || navigator.webkitConnection) return;" +
        "  Object.defineProperty(navigator, 'connection', {" +
        "    get: function() {" +
        "      return {" +
        "        downlink: 10," +
        "        effectiveType: '4g'," +
        "        rtt: 50," +
        "        saveData: false," +
        "        onchange: null," +
        "        addEventListener: function(){}," +
        "        removeEventListener: function(){}," +
        "        dispatchEvent: function() { return true; }" +
        "      };" +
        "    }," +
        "    configurable: true," +
        "    enumerable: true" +
        "  });" +
        "})();" +

        // ============================================================" +
        // 16. REMOVE CDP AUTOMATION VARIABLES - Comprehensive cleanup" +
        // Based on stealth pattern - delete via Object.defineProperty" +
        // ============================================================" +
        "(function() {" +
        "  var cleanup = function(obj, prop) {" +
        "    try {" +
        "      var val = obj[prop];" +
        "      if (val !== undefined && val !== null) {" +
        "        delete obj[prop];" +
        "      }" +
        "    } catch(e) {" +
        "      try { Object.defineProperty(obj, prop, { get: function() { return undefined; }, configurable: true }); } catch(e2) {}" +
        "    }" +
        "  };" +
        "  // CDP properties to remove" +
        "  var cdcProps = [" +
        "    'cdc_adoQpoasnfa76pfcZLmcfl_Array'," +
        "    'cdc_adoQpoasnfa76pfcZLmcfl_Promise'," +
        "    'cdc_adoQpoasnfa76pfcZLmcfl_Symbol'," +
        "    '$chrome_asyncScriptInfo'," +
        "    '__webdriver_script_function'," +
        "    '__selenium_evaluate'," +
        "    '__webdriver_evaluate'," +
        "    '__selenium_scriptFunction'," +
        "    '__webdriver_script_function'," +
        "    '__webdriver_script_func'," +
        "    '__selenium_script_func'," +
        "    '__fxdriver_alert'," +
        "    '__fxdriver_exception'," +
        "    '__fxdriver_is_stale'," +
        "    '__selenium_ide_click'," +
        "    '__lastWatirKey'," +
        "    '__webnode_'," +
        "    '__webnode_id'," +
        "    '__native_error'," +
        "    '__nightwatch'," +
        "    '__webdriver_'," +
        "    '__selenium__'," +
        "    '__driver_'," +
        "    '__fxdad_'," +
        "    '__dom_automation_'," +
        "    '__chromepopup'," +
        "    '__detect_nightmare'," +
        "    '__nightmare'," +
        "    '_WEBDRIVER_ELEM_CACHE'," +
        "    'selenium'," +
        "    'webdriver'," +
        "    '__webgl2debug'," +
        "    '__driver_undefined'" +
        "  ];" +
        "  cdcProps.forEach(function(p) { cleanup(window, p); });" +
        "  // Also clean up prototype properties" +
        "  try { delete Object.getPrototypeOf(navigator).webdriver; } catch(e) {}" +
        "  try { delete Object.getPrototypeOf(navigator).plugins; } catch(e) {}" +
        "  try { delete Object.getPrototypeOf(navigator).mimeTypes; } catch(e) {}" +
        "})();" +

        // ============================================================" +
        // 17. PERFORMANCE TIMING - STABLE (no modification)" +
        // ============================================================" +
        "(function() {" +
        "  // Don't modify Performance.now() - natural consistent values are better" +
        "  // The native implementation is already consistent" +
        "})();" +

        // ============================================================" +
        // 18. FONTS FINGERPRINT - Proper implementation" +
        // ============================================================" +
        "(function() {" +
        "  // Use prototype-based approach like stealth" +
        "  if (navigator.fonts && navigator.fonts.query) return; // Already exists" +
        "  Object.defineProperty(navigator, 'fonts', {" +
        "    get: function() {" +
        "      return {" +
        "        get query() { return 'always'; }," +
        "        get size() { return 0; }," +
        "        size: 0," +
        "        status: 'complete'," +
        "        ready: Promise.resolve()" +
        "      };" +
        "    }," +
        "    configurable: true," +
        "    enumerable: true" +
        "  });" +
        "})();" +

        // ============================================================" +
        // 19. VENDOR/CHROME SPECIFIC" +
        // ============================================================" +
        "try { delete navigator.vendor; } catch(e) {}" +
        // FIX GAP-10: Consistência — usar 'Google Inc.' em todos os lugares" +
        "Object.defineProperty(navigator, 'vendor', { get: function() { return 'Google Inc.'; }, configurable: true, enumerable: true });" +
        "if (!window.webkitResolveLocalFileSystemURL) {" +
        "  window.webkitResolveLocalFileSystemURL = function(url, success, failure) { if (failure) failure(null); };" +
        "}" +

        // ============================================================" +
        // 20. SECURE CONTEXT CHECK BYPASS" +
        // ============================================================" +
        "if (typeof window.isSecureContext !== 'undefined') {" +
        "  Object.defineProperty(window, 'isSecureContext', { get: function() { return true; }, configurable: true });" +
        "}" +

        // ============================================================" +
        // 21. WEBDRIVER FLAG BYPASS (Chrome 89+)" +
        // ============================================================" +
        "try { delete navigator.webdriver; } catch(e) {}" +
        "Object.defineProperty(navigator, 'webdriver', { get: function() { return false; }, configurable: true, enumerable: true });" +

        // ============================================================" +
        // 22. NATIVE TOSTRING WRAPPER - Make mock functions look native" +
        // ============================================================" +
        "(function() {" +
        "  var nativeToString = Function.prototype.toString;" +
        "  var fakeToString = function toString() {" +
        "    if (this.__nativeName) return 'function ' + this.__nativeName + '() { [native code] }';" +
        "    return nativeToString.call(this);" +
        "  };" +
        "  fakeToString.__nativeName = 'toString';" +
        "  Function.prototype.toString = fakeToString;" +
        "})();" +

        // FIX GAP-06: Removido console.log que expunha anti-detection" +
        "})();";

    /**
     * Injeta o script anti-detecção em todas as novas páginas e iframes.
     * <p>
     * Usa Page.addScriptToEvaluateOnNewDocument com runImmediately=true
     * para injetar antes de qualquer JS executar. Também injeta na página
     * atual caso já tenha carregado.
     * </p>
     *
     * @return CompletableFuture que completa quando o script é injetado
     */
    public CompletableFuture<Void> injectAntiDetectionScript() {
        // FIX GAP-05: runImmediately garante injeção em iframes e antes de qualquer JS
        java.util.HashMap<String, Object> scriptParams = new java.util.HashMap<>();
        scriptParams.put("source", ANTI_DETECTION_SCRIPT);
        scriptParams.put("runImmediately", true);

        return currentTarget.executeCdpCmd("Page.addScriptToEvaluateOnNewDocument",
            scriptParams, null)
            .<Void>thenCompose(v -> {
                JavaDriverlessLogger.debug(logger, "Anti-detection script registrado para novos documentos e iframes");
                // Também injetar na página atual
                return currentTarget.executeScript(ANTI_DETECTION_SCRIPT, null, true)
                    .<Void>thenApply(r -> null)
                    .exceptionally(ex2 -> null);
            })
            .exceptionally(ex -> {
                JavaDriverlessLogger.warn(logger, "Page.addScriptToEvaluateOnNewDocument indisponível, usando fallback");
                try {
                    currentTarget.executeScript(ANTI_DETECTION_SCRIPT, null, true).join();
                    JavaDriverlessLogger.debug(logger, "Anti-detection script injetado via Runtime.evaluate");
                } catch (Exception e) {
                    JavaDriverlessLogger.error(logger, "Falha completa ao injetar anti-detection script: {}", e.getMessage());
                }
                return null;
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

            if (boundsNode.has("left"))
                rect.put("x", boundsNode.get("left").asInt());
            if (boundsNode.has("top"))
                rect.put("y", boundsNode.get("top").asInt());
            if (boundsNode.has("width"))
                rect.put("width", boundsNode.get("width").asInt());
            if (boundsNode.has("height"))
                rect.put("height", boundsNode.get("height").asInt());

            return rect;
        });
    }

    /**
     * Define o retângulo (bounds) da janela.
     *
     * @param x      posição X
     * @param y      posição Y
     * @param width  largura
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
     * @param width  largura
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
     * @param origin      origem (URL)
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
     * <p>
     * <strong>Nota:</strong> O CDP não possui um comando nativo "Network.setProxy".
     * A abordagem correta é configurar o proxy via argumentos de linha de comando
     * do Chrome ({@code --proxy-server}). Este método tenta usar
     * {@code Fetch.enable} para interceptar requests, mas a melhor abordagem
     * é configurar o proxy antes de iniciar o Chrome.
     * </p>
     *
     * @param proxyServer servidor proxy (host:port)
     * @param bypassList  lista de bypass
     * @return CompletableFuture que completa quando o proxy é configurado
     */
    public CompletableFuture<Void> setProxy(String proxyServer, List<String> bypassList) {
        // CDP não possui comando "Network.setProxy".
        // Proxy deve ser configurado via --proxy-server arg ANTES de iniciar o Chrome.
        // Logar aviso e tentar configurar via Fetch.enable como fallback.
        JavaDriverlessLogger.warn(logger, "setProxy() chamado após o Chrome iniciar. " +
            "Proxy dinâmico via CDP tem limitações. " +
            "Para melhor resultado, use options.addArgument('--proxy-server=...').");

        Map<String, Object> args = new HashMap<>();
        args.put("handleAuthRequests", true);

        return currentTarget.executeCdpCmd("Fetch.enable", args, null)
                .thenApply(v -> null);
    }

    /**
     * Configura device metrics para parecer navegador real.
     * <p>
     * Os parâmetros são passados de forma "flat" conforme a especificação
     * do CDP para {@code Emulation.setDeviceMetricsOverride}.
     * </p>
     *
     * @return CompletableFuture que completa quando configurado
     */
    public CompletableFuture<Void> setRealisticDeviceMetrics() {
        // FIX BUG-03: Parâmetros devem ser flat, não aninhados em "metrics"
        Map<String, Object> args = new HashMap<>();
        args.put("width", 1920);
        args.put("height", 1080);
        args.put("deviceScaleFactor", 1);
        args.put("mobile", false);
        args.put("screenWidth", 1920);
        args.put("screenHeight", 1080);

        return currentTarget.executeCdpCmd("Emulation.setDeviceMetricsOverride", args, null)
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
                JavaDriverlessLogger.warn(logger,
                        "Autenticação de proxy não é suportada. Configurando apenas proxy sem autenticação: {}",
                        proxyServer);
            }

            options.addArgument("--proxy-server=" + proxyServer);

            JavaDriverlessLogger.info(logger, "Proxy configurado via argumentos do Chrome");

        } catch (Exception e) {
            throw new RuntimeException("Erro ao configurar proxy via argumentos: " + e.getMessage(), e);
        }
    }

    /**
     * Define um proxy único dinamicamente para todos os contextos.
     * Suporta formato: "http://host:port/" ou "socks5://host:port/"
     *
     * Nota: Proxies com autenticação não são suportados. Use apenas proxies sem
     * autenticação.
     *
     * @param proxy      string do proxy (ex: "http://example.com:8080/" ou
     *                   "socks5://example.com:1080/")
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
                    JavaDriverlessLogger.warn(logger, "Autenticação de proxy não é suportada. Ignorando credenciais.");
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
     * <p>
     * <strong>Nota:</strong> O CDP não possui comando "Network.clearProxy".
     * Este método desabilita o interceptor Fetch, o que remove o proxy
     * configurado via {@link #setProxy}.
     * </p>
     *
     * @return CompletableFuture que completa quando o proxy é limpo
     */
    public CompletableFuture<Void> clearProxy() {
        return currentTarget.executeCdpCmd("Fetch.disable", null, null)
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
     * @param url     URL
     * @param method  método HTTP
     * @param headers headers
     * @param body    corpo da requisição
     * @param timeout timeout
     * @return CompletableFuture com a resposta
     */
    public CompletableFuture<Map<String, Object>> fetch(
            String url, String method, Map<String, String> headers, Object body, float timeout) {
        return currentTarget.fetch(url, method, headers, body, timeout);
    }

    /**
     * Executa uma requisição XHR.
     *
     * @param url     URL
     * @param method  método HTTP
     * @param headers headers
     * @param body    corpo da requisição
     * @param timeout timeout
     * @return CompletableFuture com a resposta
     */
    public CompletableFuture<Map<String, Object>> xhr(
            String url, String method, Map<String, String> headers, Object body, float timeout) {
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

    private void unlockStaleProfileLocks(Path userDataDir) {
        if (!UNLOCK_STALE_PROFILE_LOCKS || userDataDir == null) {
            return;
        }

        Path profileDir = userDataDir.toAbsolutePath().normalize();
        if (!Files.exists(profileDir) || !Files.isDirectory(profileDir)) {
            return;
        }

        int removed = 0;
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(profileDir, "Singleton*")) {
            for (Path lockFile : stream) {
                if (deleteProfileStartupFile(lockFile)) {
                    removed++;
                }
            }
        } catch (IOException e) {
            JavaDriverlessLogger.warn(logger, "Nao foi possivel listar locks do profile {}: {}", profileDir, e.getMessage());
        }

        Path devToolsPort = profileDir.resolve("DevToolsActivePort");
        if (deleteProfileStartupFile(devToolsPort)) {
            removed++;
        }

        if (removed > 0) {
            JavaDriverlessLogger.info(logger, "Removidos {} arquivos stale do profile antes do startup: {}", removed, profileDir);
        }
    }

    private boolean deleteProfileStartupFile(Path file) {
        try {
            if (Files.deleteIfExists(file)) {
                JavaDriverlessLogger.profile(logger, "Arquivo stale removido do profile: {}", file.getFileName());
                return true;
            }
        } catch (IOException e) {
            JavaDriverlessLogger.warn(logger, "Nao foi possivel remover arquivo stale {}: {}", file, e.getMessage());
        }
        return false;
    }

    private static boolean envBool(String key, boolean defaultValue) {
        String raw = System.getenv(key);
        if (raw == null || raw.isBlank()) {
            return defaultValue;
        }
        String value = raw.trim().toLowerCase(Locale.ROOT);
        return value.equals("1") || value.equals("true") || value.equals("yes") || value.equals("on");
    }

    @Override
    public String toString() {
        return String.format("<%s.%s (host=\"%s\", pid=%d)>",
                getClass().getPackage().getName(),
                getClass().getSimpleName(),
                host,
                browserPid);
    }
}
