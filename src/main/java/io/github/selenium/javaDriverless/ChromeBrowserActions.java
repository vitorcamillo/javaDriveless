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
import io.github.selenium.javaDriverless.scripts.NetworkInterceptor;
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
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

import org.slf4j.Logger;

abstract class ChromeBrowserActions extends ChromeTargetActions {
    protected ChromeBrowserActions(ChromeOptions options, float timeout, boolean debug, int maxWsSize) {
        super(options, timeout, debug, maxWsSize);
    }

    public CompletableFuture<Void> sleep(double seconds) {
        // delayedExecutor: a espera não ocupa thread do ForkJoinPool comum.
        return CompletableFuture.runAsync(() -> { }, CompletableFuture.delayedExecutor(
                (long) (seconds * 1000), TimeUnit.MILLISECONDS));
    }

    public CompletableFuture<Void> back() {
        return getCurrentTarget().thenCompose(Target::back);
    }

    public CompletableFuture<Void> forward() {
        return getCurrentTarget().thenCompose(Target::forward);
    }

    public CompletableFuture<Void> refresh(boolean ignoreCache) {
        return getCurrentTarget().thenCompose(t -> t.refresh(ignoreCache));
    }

    public CompletableFuture<Void> refresh() {
        return refresh(false);
    }

    public CompletableFuture<List<Map<String, Object>>> getCookies() {
        return getCurrentTarget().thenCompose(Target::getCookies);
    }

    public CompletableFuture<Map<String, Object>> getCookie(String name) {
        return getCurrentTarget().thenCompose(t -> t.getCookie(name));
    }

    public CompletableFuture<Void> addCookie(Map<String, Object> cookieDict) {
        return getCurrentTarget().thenCompose(t -> t.addCookie(cookieDict));
    }

    public CompletableFuture<Void> deleteCookie(String name) {
        return getCurrentTarget().thenCompose(t -> t.deleteCookie(name));
    }

    public CompletableFuture<Void> deleteAllCookies() {
        return getCurrentTarget().thenCompose(Target::deleteAllCookies);
    }

    public CompletableFuture<byte[]> getScreenshotAsPng() {
        return getCurrentTarget().thenCompose(Target::getScreenshotAsPng);
    }

    public CompletableFuture<Void> getScreenshotAsFile(String filename) {
        return getCurrentTarget().thenCompose(t -> t.getScreenshotAsFile(filename));
    }

    public CompletableFuture<Void> sendKeys(String text) {
        return getCurrentTarget().thenCompose(t -> t.sendKeys(text));
    }

    public CompletableFuture<List<String>> getWindowHandles() {
        return getTargets().thenApply(targets -> {
            List<String> handles = new ArrayList<>();
            for (Target target : targets) {
                handles.add(target.getId());
            }
            return handles;
        });
    }

    public CompletableFuture<String> getCurrentWindowHandle() {
        return CompletableFuture.completedFuture(currentTarget.getId());
    }

    public CompletableFuture<Integer> getCurrentWindowId() {
        return currentTarget.getWindowId();
    }

    public CompletableFuture<Void> maximizeWindow() {
        return setWindowState("maximized");
    }

    public CompletableFuture<Void> minimizeWindow() {
        return setWindowState("minimized");
    }

    public CompletableFuture<Void> fullscreenWindow() {
        return setWindowState("fullscreen");
    }

    public CompletableFuture<Void> normalizeWindow() {
        return setWindowState("normal");
    }

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

    public CompletableFuture<Map<String, Object>> getWindowPosition() {
        return getWindowRect().thenApply(rect -> {
            Map<String, Object> position = new HashMap<>();
            position.put("x", rect.get("x"));
            position.put("y", rect.get("y"));
            return position;
        });
    }

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

    public CompletableFuture<Map<String, Object>> getWindowSize() {
        return getWindowRect().thenApply(rect -> {
            Map<String, Object> size = new HashMap<>();
            size.put("width", rect.get("width"));
            size.put("height", rect.get("height"));
            return size;
        });
    }

    public CompletableFuture<Void> setWindowSize(int width, int height) {
        return getWindowPosition().thenCompose(position -> {
            int x = (Integer) position.get("x");
            int y = (Integer) position.get("y");
            return setWindowRect(x, y, width, height);
        });
    }

    public CompletableFuture<Void> removeContext(Context context) {
        return context.quit().thenRun(() -> {
            contexts.remove(context.getContextId());
        });
    }

    public CompletableFuture<Void> setPermissions(String origin, List<String> permissions) {
        Map<String, Object> args = new HashMap<>();
        args.put("origin", origin);
        args.put("permissions", permissions);
        if (currentContext != null && currentContext.getContextId() != null) {
            args.put("browserContextId", currentContext.getContextId());
        }

        return baseTarget.executeCdpCmd("Browser.grantPermissions", args, null)
                .thenApply(v -> null);
    }

    /**
     * Atualiza uma unica permissao sem redefinir as demais permissoes da origem.
     */
    public CompletableFuture<Void> setPermission(String origin, String permission, String setting) {
        Map<String, Object> args = new HashMap<>();
        args.put("origin", origin);
        args.put("permission", Map.of("name", permission));
        args.put("setting", setting);
        if (currentContext != null && currentContext.getContextId() != null) {
            args.put("browserContextId", currentContext.getContextId());
        }
        return baseTarget.executeCdpCmd("Browser.setPermission", args, null)
                .thenApply(v -> null);
    }

    public CompletableFuture<Void> setGeolocationOverride(Double latitude, Double longitude, Double accuracy) {
        Map<String, Object> args = new HashMap<>();
        args.put("latitude", latitude);
        args.put("longitude", longitude);
        args.put("accuracy", accuracy == null ? 100.0 : accuracy);
        return getCurrentTarget()
                .thenCompose(target -> target.executeCdpCmd("Emulation.setGeolocationOverride", args, null))
                .thenApply(v -> null);
    }

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

    public CompletableFuture<Void> setRealisticDeviceMetrics() {
        // FIX BUG-03: Parâmetros devem ser flat, não aninhados em "metrics"
        Map<String, Object> args = new HashMap<>();
        args.put("width", 1920);
        args.put("height", 1032);
        args.put("deviceScaleFactor", 1);
        args.put("mobile", false);
        args.put("screenWidth", 1920);
        args.put("screenHeight", 1080);

        return currentTarget.executeCdpCmd("Emulation.setDeviceMetricsOverride", args, null)
                .thenApply(v -> null);
    }

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

    public CompletableFuture<Void> clearProxy() {
        // Nunca desabilitar Fetch por fora do dispatcher: o mesmo domain mantém o guard
        // obrigatório que impede Client Hints físicos de escaparem após esta chamada.
        return NetworkInterceptor.disableFetch(currentTarget);
    }

    public CompletableFuture<Map<String, Object>> waitDownload(float timeout) {
        return currentTarget.waitDownload(timeout);
    }

    public CompletableFuture<Map<String, Object>> fetch(
            String url, String method, Map<String, String> headers, Object body, float timeout) {
        return currentTarget.fetch(url, method, headers, body, timeout);
    }

    public CompletableFuture<Map<String, Object>> xhr(
            String url, String method, Map<String, String> headers, Object body, float timeout) {
        return currentTarget.xhr(url, method, headers, body, timeout);
    }

    public CompletableFuture<String> printPage() {
        return currentTarget.printPage();
    }

    public CompletableFuture<List<Map<String, Object>>> getSinks() {
        return currentTarget.getSinks();
    }

    public CompletableFuture<String> getIssueMessage() {
        return currentTarget.getIssueMessage();
    }

    public CompletableFuture<Map<String, Object>> setSinkToUse(String sinkName) {
        return currentTarget.setSinkToUse(sinkName);
    }

    public CompletableFuture<Map<String, Object>> startDesktopMirroring(String sinkName) {
        return currentTarget.startDesktopMirroring(sinkName);
    }

    public CompletableFuture<Map<String, Object>> startTabMirroring(String sinkName) {
        return currentTarget.startTabMirroring(sinkName);
    }

    public CompletableFuture<Map<String, Object>> stopCasting(String sinkName) {
        return currentTarget.stopCasting(sinkName);
    }

    public CompletableFuture<Void> quit(boolean cleanDirs) {
        return coordinateClose(() -> CompletableFuture.runAsync(() -> {
            Target target = currentTarget;
            currentTarget = null;
            BaseTarget browserTarget = baseTarget;
            baseTarget = null;
            BaseTarget contextTarget = baseContext;
            baseContext = null;
            currentContext = null;
            // Pontes de proxy dos contextos isolados: sem isto cada contexto
            // vivo no quit deixaria um ServerSocket loopback orfao.
            List<Context> contextosVivos = new ArrayList<>(contexts.values());
            contexts.clear();
            for (Context contexto : contextosVivos) {
                contexto.closeProxyBridge();
            }
            Process ownedProcess = process;
            List<ProcessHandle> ownedProcessTree = captureProcessTree(ownedProcess);
            process = null;
            browserPid = null;

            try {
                ChromeStartupResourceCleaner.cleanup(
                        logger,
                        target,
                        browserTarget,
                        contextTarget,
                        ownedProcess,
                        cleanDirs ? tempDir : null,
                        !isRemote,
                        () -> { closeAuthenticatedProxyBridge(); closePipeChannel(); });
                restoreEgressDerivedWebRtcState();
            } finally {
                releaseCatalogLaunchLeaseAfterProcessExit(ownedProcessTree);
            }
        }));
    }

    public CompletableFuture<Void> quit() {
        return quit(options.isAutoCleanDirs());
    }

    @Override
    public void close() {
        quit().join();
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
