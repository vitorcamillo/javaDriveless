package io.github.selenium.javaDriverless.types;

import com.fasterxml.jackson.databind.JsonNode;

import io.github.selenium.javaDriverless.cdp.CDPCommandExecutor;
import io.github.selenium.javaDriverless.cdp.CDPSocket;
import io.github.selenium.javaDriverless.input.Pointer;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;
import java.util.function.BiConsumer;

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
public class Target extends TargetCastingOperations implements CDPCommandExecutor {

    // Futures de X.enable por instância (usado por WebElementAttributes); morre junto com o
    // Target; lazy porque mocks não rodam inicializadores.
    volatile java.util.Map<String, CompletableFuture<Void>> cdpDomainEnables;

    public Target(String host, String targetId, Object driver, Object context,
                  boolean isRemote, float timeout, String type, boolean startSocket, int maxWsSize) {
        super(host, targetId, driver, context, isRemote, timeout, type, startSocket, maxWsSize);
    }

    /*
     * Keep the 1.11.0 public API declared on this facade. Besides preserving source
     * compatibility, explicit typed overrides retain reflective callers that use
     * Target#getDeclaredMethod and generic return metadata after the implementation
     * was split into responsibility-specific superclasses.
     */
    @Override public String getId() { return super.getId(); }
    @Override public CompletableFuture<Boolean> getAttached() { return super.getAttached(); }
    @Override public CompletableFuture<String> getOpenerId() { return super.getOpenerId(); }
    @Override public CompletableFuture<String> getOpenerFrameId() { return super.getOpenerFrameId(); }
    @Override public CompletableFuture<Boolean> getCanAccessOpener() { return super.getCanAccessOpener(); }
    @Override public CompletableFuture<String> getSubtype() { return super.getSubtype(); }
    @Override public CDPSocket getSocket() { return super.getSocket(); }
    @Override public Pointer getPointer() { return super.getPointer(); }
    @Override public CompletableFuture<Target> init() { return super.init(); }

    @Override
    public CompletableFuture<Map<String, Object>> get(String url, String referrer,
                                                       boolean waitLoad, float timeout) {
        return super.get(url, referrer, waitLoad, timeout);
    }

    @Override public CompletableFuture<Map<String, Object>> get(String url, boolean waitLoad) {
        return super.get(url, waitLoad);
    }
    @Override public CompletableFuture<Void> back() { return super.back(); }
    @Override public CompletableFuture<Void> forward() { return super.forward(); }
    @Override public CompletableFuture<Void> refresh(boolean ignoreCache) { return super.refresh(ignoreCache); }
    @Override public CompletableFuture<String> getCurrentUrl() { return super.getCurrentUrl(); }
    @Override public CompletableFuture<String> getTitle() { return super.getTitle(); }
    @Override public CompletableFuture<String> getPageSource() { return super.getPageSource(); }

    @Override
    public CompletableFuture<Object> executeScript(String script, Object[] args, boolean awaitPromise) {
        return super.executeScript(script, args, awaitPromise);
    }

    @Override
    public CompletableFuture<Object> executeScript(String script, Object[] args, boolean awaitPromise,
                                                   String serialization) {
        return super.executeScript(script, args, awaitPromise, serialization);
    }

    @Override
    public CompletableFuture<Object> executeRawScript(String script, boolean awaitRes,
                                                      String serialization, Object... args) {
        return super.executeRawScript(script, awaitRes, serialization, args);
    }

    @Override
    public CompletableFuture<Object> executeAsyncScript(String script, int maxDepth,
                                                        String serialization, Object... args) {
        return super.executeAsyncScript(script, maxDepth, serialization, args);
    }

    @Override
    public CompletableFuture<Object> evalAsync(String script, float timeout,
                                               boolean uniqueContext, Object... args) {
        return super.evalAsync(script, timeout, uniqueContext, args);
    }

    @Override public CompletableFuture<Alert> getAlert(float timeout) { return super.getAlert(timeout); }
    @Override public CompletableFuture<WebElement> findElement(String by, String value, Float timeout) {
        return super.findElement(by, value, timeout);
    }
    @Override public CompletableFuture<List<WebElement>> findElements(String by, String value, float timeout) {
        return super.findElements(by, value, timeout);
    }
    @Override public CompletableFuture<List<WebElement>> searchElements(String query) {
        return super.searchElements(query);
    }
    @Override public CompletableFuture<List<Target>> getTargetsForIframes(List<WebElement> iframes) {
        return super.getTargetsForIframes(iframes);
    }
    @Override public CompletableFuture<Target> getTargetForIframe(WebElement iframe) {
        return super.getTargetForIframe(iframe);
    }

    @Override public CompletableFuture<byte[]> getScreenshotAsPng() { return super.getScreenshotAsPng(); }
    @Override public CompletableFuture<Void> getScreenshotAsFile(String filename) {
        return super.getScreenshotAsFile(filename);
    }
    @Override public CompletableFuture<Void> saveScreenshot(String filename) {
        return super.saveScreenshot(filename);
    }
    @Override public CompletableFuture<String> snapshot() { return super.snapshot(); }
    @Override public CompletableFuture<Void> saveSnapshot(String filename) { return super.saveSnapshot(filename); }

    @Override
    public CompletableFuture<Void> setNetworkConditions(boolean offline, int latency,
                                                        int downloadThroughput, int uploadThroughput,
                                                        String connectionType) {
        return super.setNetworkConditions(offline, latency, downloadThroughput, uploadThroughput, connectionType);
    }

    @Override public CompletableFuture<Void> focus() { return super.focus(); }
    @Override public CompletableFuture<Void> activate() { return super.activate(); }
    @Override public CompletableFuture<List<Map<String, Object>>> getCookies() { return super.getCookies(); }
    @Override public CompletableFuture<Map<String, Object>> getCookie(String name) { return super.getCookie(name); }
    @Override public CompletableFuture<Void> addCookie(Map<String, Object> cookieDict) {
        return super.addCookie(cookieDict);
    }
    @Override public CompletableFuture<Void> deleteCookie(String name) { return super.deleteCookie(name); }
    @Override public CompletableFuture<Void> deleteAllCookies() { return super.deleteAllCookies(); }
    @Override public CompletableFuture<Void> sendKeys(String text) { return super.sendKeys(text); }
    @Override public CompletableFuture<Void> unfocus() { return super.unfocus(); }
    @Override public CompletableFuture<String> printPage() { return super.printPage(); }
    @Override public CompletableFuture<Map<String, Object>> getHistory() { return super.getHistory(); }
    @Override public CompletableFuture<Void> setSource(String source, float timeout) {
        return super.setSource(source, timeout);
    }
    @Override public CompletableFuture<Map<String, Object>> waitDownload(float timeout) {
        return super.waitDownload(timeout);
    }
    @Override public CompletableFuture<Map<String, Object>> getInfo() { return super.getInfo(); }
    @Override public CompletableFuture<Map<String, Object>> getFrameTree() { return super.getFrameTree(); }
    @Override public CompletableFuture<Map<String, Object>> getBaseFrame() { return super.getBaseFrame(); }
    @Override public CompletableFuture<String> getType() { return super.getType(); }
    @Override public CompletableFuture<String> getTitleViaCDP() { return super.getTitleViaCDP(); }
    @Override public CompletableFuture<String> getUrlViaCDP() { return super.getUrlViaCDP(); }
    @Override public CompletableFuture<Integer> getWindowId() { return super.getWindowId(); }
    @Override public CompletableFuture<Map<String, Object>> getNetworkConditions() {
        return super.getNetworkConditions();
    }
    @Override public CompletableFuture<Void> deleteNetworkConditions() {
        return super.deleteNetworkConditions();
    }

    @Override
    public CompletableFuture<Map<String, Object>> fetch(String url, String method,
                                                        Map<String, String> headers, Object body,
                                                        float timeout) {
        return super.fetch(url, method, headers, body, timeout);
    }

    @Override
    public CompletableFuture<Map<String, Object>> xhr(String url, String method,
                                                      Map<String, String> headers, Object body,
                                                      float timeout) {
        return super.xhr(url, method, headers, body, timeout);
    }

    @Override public CompletableFuture<List<Map<String, Object>>> getSinks() { return super.getSinks(); }
    @Override public CompletableFuture<String> getIssueMessage() { return super.getIssueMessage(); }
    @Override public CompletableFuture<Map<String, Object>> setSinkToUse(String sinkName) {
        return super.setSinkToUse(sinkName);
    }
    @Override public CompletableFuture<Map<String, Object>> startDesktopMirroring(String sinkName) {
        return super.startDesktopMirroring(sinkName);
    }
    @Override public CompletableFuture<Map<String, Object>> startTabMirroring(String sinkName) {
        return super.startTabMirroring(sinkName);
    }
    @Override public CompletableFuture<Map<String, Object>> stopCasting(String sinkName) {
        return super.stopCasting(sinkName);
    }

    @Override public CompletableFuture<Void> close() { return super.close(); }
    @Override public CompletableFuture<Void> close(float timeout) { return super.close(timeout); }
    @Override public CompletableFuture<Void> disconnect() { return super.disconnect(); }
    @Override public CompletableFuture<JsonNode> waitForCdp(String event, Float timeout) {
        return super.waitForCdp(event, timeout);
    }
    @Override public CompletableFuture<Void> addCdpListener(String event, Consumer<JsonNode> callback) {
        return super.addCdpListener(event, callback);
    }
    @Override public CompletableFuture<Void> addCdpSessionListener(
            String event, BiConsumer<String, JsonNode> callback) {
        return super.addCdpSessionListener(event, callback);
    }
    @Override public CompletableFuture<Void> removeCdpListener(String event, Consumer<JsonNode> callback) {
        return super.removeCdpListener(event, callback);
    }
    @Override public CompletableFuture<List<JsonNode>> getCdpEventIter(String event) {
        return super.getCdpEventIter(event);
    }
    @Override public CompletableFuture<JsonNode> executeCdpCmd(String cmd, Map<String, Object> cmdArgs,
                                                              Float timeout) {
        return super.executeCdpCmd(cmd, cmdArgs, timeout);
    }
    @Override public CompletableFuture<JsonNode> executeCdpCmd(String cmd, Map<String, Object> cmdArgs,
                                                              Float timeout, String sessionId) {
        return super.executeCdpCmd(cmd, cmdArgs, timeout, sessionId);
    }
    @Override public CompletableFuture<Void> sleep(double seconds) { return super.sleep(seconds); }
    @Override public String toString() { return super.toString(); }
    @Override public boolean equals(Object obj) { return super.equals(obj); }
    @Override public int hashCode() { return super.hashCode(); }

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
