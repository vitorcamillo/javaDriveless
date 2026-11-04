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
import io.github.selenium.javaDriverless.scripts.AntiDetectionScripts;
import io.github.selenium.javaDriverless.scripts.SeleniumProxySurfaceScripts;
import io.github.selenium.javaDriverless.scripts.SwitchTo;
import io.github.selenium.javaDriverless.logging.JavaDriverlessLogger;
import io.github.selenium.javaDriverless.types.*;
import io.github.selenium.javaDriverless.utils.Utils;
import io.github.selenium.javaDriverless.utils.UserAgentResolver;

import java.util.function.Consumer;

import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.slf4j.Logger;

abstract class ChromeTargetActions extends ChromeSessionCore {
    private static final int EXTRACTION_WORKERS = 2;
    private static final int EXTRACTION_QUEUE_CAPACITY = 8;
    private static final ThreadPoolExecutor EXTRACTION_EXECUTOR = createExtractionExecutor();
    private final ChromeAuthSupport authSupport = new ChromeAuthSupport(logger);
    private static final ExtractionLimits DEFAULT_EXTRACTION_LIMITS = new ExtractionLimits(
            10_000,
            512L * 1024L * 1024L,
            1024L * 1024L * 1024L);

    record ExtractionLimits(int maxEntries, long maxEntryBytes, long maxTotalBytes) {
        ExtractionLimits {
            if (maxEntries <= 0 || maxEntryBytes <= 0 || maxTotalBytes <= 0) {
                throw new IllegalArgumentException("Limites de extracao devem ser positivos");
            }
        }
    }

    private static ThreadPoolExecutor createExtractionExecutor() {
        AtomicInteger threadSequence = new AtomicInteger();
        ThreadPoolExecutor executor = new ThreadPoolExecutor(
                EXTRACTION_WORKERS,
                EXTRACTION_WORKERS,
                30L,
                TimeUnit.SECONDS,
                new ArrayBlockingQueue<>(EXTRACTION_QUEUE_CAPACITY),
                task -> {
                    Thread thread = new Thread(
                            task, "java-driverless-zip-extract-" + threadSequence.incrementAndGet());
                    thread.setDaemon(true);
                    return thread;
                },
                new ThreadPoolExecutor.AbortPolicy());
        executor.allowCoreThreadTimeOut(true);
        return executor;
    }

    protected ChromeTargetActions(ChromeOptions options, float timeout, boolean debug, int maxWsSize) {
        super(options, timeout, debug, maxWsSize);
    }

    @Override
    protected void resetSessionScopedState() {
        super.resetSessionScopedState();
        authSupport.resetForNewSession();
    }

    public CompletableFuture<Target> getCurrentTarget() {
        if (!started) {
            return startSession().thenApply(c -> currentTarget);
        }
        return CompletableFuture.completedFuture(currentTarget);
    }

    public CompletableFuture<BaseTarget> getBaseTarget() {
        if (!started) {
            return startSession().thenApply(c -> baseTarget);
        }
        return CompletableFuture.completedFuture(baseTarget);
    }

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
                                host, targetId, (Chrome) this, currentContext,
                                isRemote, timeout, targetType, false, maxWsSize);

                        targets.add(t);
                    }
                }
            }

            return targets;
        });
    }

    public CompletableFuture<List<Target>> getTargetsForIframes(List<WebElement> iframes) {
        return getCurrentTarget().thenCompose(target -> target.getTargetsForIframes(iframes));
    }

    public CompletableFuture<Target> getTargetForIframe(WebElement iframe) {
        return getCurrentTarget().thenCompose(target -> target.getTargetForIframe(iframe));
    }

    public CompletableFuture<Map<String, Object>> get(String url, boolean waitLoad) {
        return getCurrentTarget().thenCompose(target -> target.get(url, waitLoad));
    }

    public CompletableFuture<String> getTitle() {
        return getCurrentTarget().thenCompose(Target::getTitle);
    }

    public CompletableFuture<String> getCurrentUrl() {
        return getCurrentTarget().thenCompose(Target::getCurrentUrl);
    }

    public CompletableFuture<String> getPageSource() {
        return getCurrentTarget().thenCompose(Target::getPageSource);
    }

    public CompletableFuture<Object> executeScript(String script, Object[] args, boolean awaitPromise) {
        return getCurrentTarget().thenCompose(target -> target.executeScript(script, args, awaitPromise));
    }

    public CompletableFuture<Alert> getAlert(float timeout) {
        return getCurrentTarget().thenCompose(target -> target.getAlert(timeout));
    }

    public CompletableFuture<JsonNode> waitForCdp(String event, Float timeout) {
        return getCurrentTarget().thenCompose(target -> target.waitForCdp(event, timeout));
    }

    public CompletableFuture<Void> addCdpListener(String event, Consumer<JsonNode> callback) {
        return getCurrentTarget().thenCompose(target -> target.addCdpListener(event, callback));
    }

    public CompletableFuture<Void> removeCdpListener(String event, Consumer<JsonNode> callback) {
        return getCurrentTarget().thenCompose(target -> target.removeCdpListener(event, callback));
    }

    public CompletableFuture<List<JsonNode>> getCdpEventIter(String event) {
        return getCurrentTarget().thenCompose(target -> target.getCdpEventIter(event));
    }

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

    public List<Context> getContexts() {
        return new ArrayList<>(contexts.values());
    }

    public Context getCurrentContext() {
        return currentContext;
    }

    public Context getBaseContext() {
        for (Context ctx : contexts.values()) {
            if (!ctx.isIncognito()) {
                return ctx;
            }
        }
        return currentContext; // fallback
    }

    public Pointer getCurrentPointer() {
        return currentTarget.getPointer();
    }

    public Keyboard getCurrentKeyboard() {
        return new Keyboard(currentTarget);
    }

    public CompletableFuture<Map<String, Object>> getCurrentTargetInfo() {
        return currentTarget.getInfo();
    }

    public long getBrowserPid() {
        return browserPid != null ? browserPid : -1;
    }

    public String getHost() {
        return host;
    }

    public CompletableFuture<Void> extractall(String zipPath, String extractPath) {
        return extractall(zipPath, extractPath, DEFAULT_EXTRACTION_LIMITS);
    }

    CompletableFuture<Void> extractall(String zipPath, String extractPath, ExtractionLimits limits) {
        return extractall(zipPath, extractPath, limits, () -> {});
    }

    CompletableFuture<Void> extractall(
            String zipPath, String extractPath, ExtractionLimits limits, Runnable beforeLeafOpen) {
        Runnable extraction = () -> {
            try {
                SecureZipExtractor.extract(
                        java.nio.file.Paths.get(zipPath),
                        java.nio.file.Paths.get(extractPath),
                        limits,
                        beforeLeafOpen);
            } catch (Exception e) {
                throw new RuntimeException("Erro ao extrair arquivo: " + e.getMessage(), e);
            }
        };
        try {
            return CompletableFuture.runAsync(extraction, EXTRACTION_EXECUTOR);
        } catch (RejectedExecutionException saturated) {
            return CompletableFuture.failedFuture(
                    new RejectedExecutionException("Fila de extracao saturada", saturated));
        }
    }

    public void cleanDirsSync() {
        if (tempDir != null && java.nio.file.Files.exists(tempDir)) {
            try {
                ProfileStartupFiles.deleteDirectory(tempDir);
            } catch (Exception e) {
                // Ignorar erros
            }
        }
    }

    public CompletableFuture<Void> focus() {
        return getCurrentTarget().thenCompose(Target::focus);
    }

    public CompletableFuture<String> getDownloadsDir() {
        return CompletableFuture.completedFuture(baseTarget.downloadsDirForContext(currentContext.getContextId()));
    }

    public CompletableFuture<Map<String, Object>> getFrameTree() {
        return getCurrentTarget().thenCompose(target -> target.getFrameTree());
    }

    public CompletableFuture<Map<String, Object>> getNetworkConditions() {
        return getCurrentTarget().thenCompose(target -> target.getNetworkConditions());
    }

    public CompletableFuture<Void> deleteNetworkConditions() {
        return getCurrentTarget().thenCompose(target -> target.deleteNetworkConditions());
    }

    public CompletableFuture<JsonNode> executeCdpCmd(String cmd, Map<String, Object> cmdArgs, Float timeout) {
        return getBaseTarget().thenCompose(bt -> bt.executeCdpCmd(cmd, cmdArgs, timeout));
    }

    public CompletableFuture<WebElement> findElement(String by, String value, float timeout) {
        return getCurrentTarget().thenCompose(target -> target.findElement(by, value, timeout));
    }

    public CompletableFuture<List<WebElement>> findElements(String by, String value, float timeout) {
        return getCurrentTarget().thenCompose(target -> target.findElements(by, value, timeout));
    }

    public CompletableFuture<List<WebElement>> searchElements(String query) {
        return getCurrentTarget().thenCompose(target -> target.searchElements(query));
    }

    public CompletableFuture<Void> saveScreenshot(String filename) {
        return getCurrentTarget().thenCompose(t -> t.getScreenshotAsFile(filename));
    }

    public CompletableFuture<String> snapshot() {
        return getCurrentTarget().thenCompose(target -> target.snapshot());
    }

    public CompletableFuture<Void> saveSnapshot(String filename) {
        return getCurrentTarget().thenCompose(target -> target.saveSnapshot(filename));
    }

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
                    (Chrome) this, null, isRemote, timeout, "page", false, maxWsSize);

            Context newContext = new Context(baseTargetForContext, (Chrome) this, contextId, incognito, maxWsSize);

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
     * Contexto isolado (cookies, storage e cache proprios) com saida por proxy
     * PROPRIA: uma ponte local autenticada por contexto, apontada em
     * {@code Target.createBrowserContext.proxyServer}. Permite N "visitantes"
     * independentes no mesmo processo Chrome, cada um com seu IP. Fail-closed:
     * se o CDP recusar o contexto, a ponte e fechada antes de propagar, para
     * nunca restar um socket de proxy sem dono.
     *
     * @param proxy proxy de saida do contexto; sem autenticacao vai direto no
     *              proxyServer; null cria contexto isolado sem proxy proprio
     *              (herda a saida do processo)
     */
    public CompletableFuture<Context> newContext(ProxyConfig proxy) {
        AuthenticatedProxyBridge bridge = null;
        String proxyServer = null;
        if (proxy != null && proxy.hasAuthentication()) {
            try {
                bridge = AuthenticatedProxyBridge.start(proxy);
                long pid = getBrowserPid();
                if (pid > 0) {
                    bridge.bindOwner(pid);
                }
                proxyServer = bridge.asChromeProxyServer();
            } catch (Exception e) {
                if (bridge != null) {
                    bridge.close();
                }
                return CompletableFuture.failedFuture(e);
            }
        } else if (proxy != null) {
            proxyServer = proxy.getType().name().toLowerCase(Locale.ROOT) + "://"
                    + proxy.getHost() + ":" + proxy.getPort();
        }
        final AuthenticatedProxyBridge ponte = bridge;
        Map<String, Object> args = new HashMap<>();
        args.put("disposeOnDetach", true);
        if (proxyServer != null) {
            args.put("proxyServer", proxyServer);
        }
        return getBaseTarget()
                .thenCompose(bt -> bt.executeCdpCmd("Target.createBrowserContext", args, null))
                .thenApply(result -> {
                    String contextId = result.get("browserContextId").asText();
                    Target baseTargetForContext = new Target(
                            host, "context-" + contextId, (Chrome) this, null, isRemote, timeout,
                            "page", false, maxWsSize);
                    Context novo = new Context(baseTargetForContext, (Chrome) this, contextId, true,
                            maxWsSize);
                    novo.setProxyBridge(ponte);
                    contexts.put(contextId, novo);
                    hasIncognitoContexts = true;
                    return novo;
                })
                .whenComplete((ctx, falha) -> {
                    if (falha != null && ponte != null) {
                        ponte.close();
                    }
                });
    }

    /** Janela/aba nova DENTRO do contexto dado, com o perfil de fingerprint
     * aplicado ao target (como {@link #newWindow(String)} faz para o contexto
     * corrente). */
    public CompletableFuture<Target> newWindow(Context context, String typeHint) {
        return context.newWindow(typeHint)
                .thenCompose(target -> {
                    if (isRemote) {
                        return CompletableFuture.completedFuture(target);
                    }
                    return applyFingerprintProfileToTarget(target).thenApply(v -> target);
                });
    }

    public CompletableFuture<Target> newWindow(String typeHint) {
        return currentContext.newWindow(typeHint)
                .thenCompose(target -> {
                    if (isRemote) {
                        return CompletableFuture.completedFuture(target);
                    }
                    return applyFingerprintProfileToTarget(target).thenApply(v -> target);
                });
    }

    public SwitchTo getSwitchTo() {
        return currentContext.switchTo();
    }

    public SwitchTo switchTo() {
        return getSwitchTo();
    }

    public CompletableFuture<Void> setAuth(String username, String password, String hostWithPort) {
        return authSupport.setAuth(username, password, hostWithPort, this::getBaseTarget);
    }

}
