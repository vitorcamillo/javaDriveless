package io.github.selenium.javaDriverless.types;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import io.github.selenium.javaDriverless.scripts.Geometry;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.*;
import java.util.concurrent.CompletableFuture;

/**
 * Representa um elemento DOM.
 * <p>
 * Todas as operações interessantes que interagem com um documento serão
 * executadas através desta interface.
 * </p>
 * <p>
 * Todas as chamadas de método farão uma verificação de "freshness" para garantir que a
 * referência do elemento ainda seja válida. Isso determina essencialmente se o
 * elemento ainda está anexado ao DOM. Se este teste falhar, uma
 * {@link StaleElementReferenceException} é lançada e todas as chamadas futuras
 * para esta instância falharão.
 * </p>
 */
public class WebElement {

    static final ObjectMapper objectMapper = new ObjectMapper();

    public static class NoSuchElementException extends RuntimeException {
        public NoSuchElementException(String message) {
            super(message);
        }
    }

    public static class StaleElementReferenceException extends RuntimeException {
        public StaleElementReferenceException(String message) {
            super(message);
        }
    }

    public static class ElementNotVisibleException extends RuntimeException {
        public ElementNotVisibleException(String message) {
            super(message);
        }
    }

    public static class ElementNotInteractableException extends RuntimeException {
        private final double x;
        private final double y;

        public ElementNotInteractableException(double x, double y, String type) {
            super(String.format("element not %s at x:%.2f, y:%.2f, it might be hidden under another one",
                type, x, y));
            this.x = x;
            this.y = y;
        }

        public double getX() { return x; }
        public double getY() { return y; }
    }

    public static class ElementNotClickableException extends ElementNotInteractableException {
        public ElementNotClickableException(double x, double y) {
            super(x, y, "clickable");
        }
    }

    final Target target;
    final Integer frameId;
    final Integer isolatedExecId;
    String objId;
    Integer nodeId;
    Integer backendNodeId;
    String className;
    boolean started = false;
    Integer contextId;
    final Map<Integer, String> objIds = new HashMap<>();
    Integer internalFrameId;
    final boolean isIframe;
    boolean stale = false;

    public WebElement(Target target, Integer frameId, Integer isolatedExecId,
                     String objId, Integer nodeId, Integer backendNodeId,
                     Integer contextId, boolean isIframe) {
        this.target = target;
        this.frameId = frameId;
        this.isolatedExecId = isolatedExecId;
        this.objId = objId;
        this.nodeId = nodeId;
        this.backendNodeId = backendNodeId;
        this.contextId = contextId;
        this.isIframe = isIframe;

        if (objId != null && contextId != null) {
            objIds.put(contextId, objId);
        }
    }

    public Target getTarget() {
        return target;
    }

    void checkStale() {
        if (stale) {
            throw new StaleElementReferenceException("Element is stale: " + this);
        }
    }

    CompletableFuture<JsonNode> describe() {
        return WebElementNode.describe(this);
    }

    CompletableFuture<Object> executeScript(String script, Object arg, String serialization,
                                            boolean uniqueContext, float timeout) {
        return WebElementScriptExecutor.executeScript(this, script, arg, serialization, uniqueContext, timeout);
    }

    public CompletableFuture<Void> click(boolean moveTo, double totalTime, double accel, double smoothSoft) {
        return WebElementInteractions.click(this, moveTo, totalTime, accel, smoothSoft);
    }

    public CompletableFuture<Void> click() {
        return click(true, 0.5, 2.0, 20.0);
    }

    public CompletableFuture<Void> sendKeys(String keys) {
        return WebElementInteractions.sendKeys(this, keys);
    }

    public CompletableFuture<Void> clear() {
        return WebElementInteractions.clear(this);
    }

    public CompletableFuture<String> getText() {
        return WebElementAttributes.getText(this);
    }

    public CompletableFuture<Boolean> isDisplayed() {
        return WebElementAttributes.isDisplayed(this);
    }

    /** Visível pelo critério frouxo (display≠none && visibility≠hidden, sem box/opacity) — útil para
     *  containers de OTP/2FA que renderizam com box 0×0. Paridade com NativeDomElement (bet365). */
    public CompletableFuture<Boolean> isDisplayedLoose() {
        return WebElementAttributes.isDisplayedLoose(this);
    }

    /** true se este elemento é o activeElement (foco), via Accessibility nativo. */
    public CompletableFuture<Boolean> isFocused() {
        return WebElementAttributes.isFocused(this);
    }

    public CompletableFuture<Boolean> isEnabled() {
        return WebElementAttributes.isEnabled(this);
    }

    public CompletableFuture<Boolean> isSelected() {
        return WebElementAttributes.isSelected(this);
    }

    public CompletableFuture<String> getTagName() {
        return WebElementAttributes.getTagName(this);
    }

    public CompletableFuture<String> getFrameId() {
        return WebElementNode.getFrameId(this);
    }

    public CompletableFuture<String> getDocumentUrl() {
        return WebElementNode.getDocumentUrl(this);
    }

    public CompletableFuture<Integer> getBackendNodeId() {
        return WebElementNode.getBackendNodeId(this);
    }

    public CompletableFuture<Integer> getContextId() {
        return WebElementNode.getContextId(this);
    }

    public CompletableFuture<Boolean> isClickable(int listenerDepth, Map<String, Object> boxModel) {
        return WebElementGeometry.isClickable(this, listenerDepth, boxModel);
    }

    public CompletableFuture<Object[]> pVisible(Map<String, Object> boxModel) {
        return WebElementGeometry.pVisible(this, boxModel);
    }

    public CompletableFuture<Object[]> getCssMetrics() {
        return WebElementGeometry.getCssMetrics(this);
    }

    public CompletableFuture<String> getAttribute(String name) {
        return WebElementAttributes.getAttribute(this, name);
    }

    public CompletableFuture<Object> getProperty(String name) {
        return WebElementAttributes.getProperty(this, name);
    }

    public CompletableFuture<double[]> getLocation() {
        return WebElementGeometry.getLocation(this);
    }

    public CompletableFuture<double[]> getSize() {
        return WebElementGeometry.getSize(this);
    }

    public CompletableFuture<double[][]> getQuads() {
        return WebElementGeometry.getQuads(this);
    }

    public CompletableFuture<double[]> getMidLocation() {
        return WebElementGeometry.getMidLocation(this);
    }

    public CompletableFuture<WebElement> findElement(String by, String value, Float timeout) {
        return WebElementFinder.findElement(this, by, value, timeout);
    }

    public CompletableFuture<List<WebElement>> findElements(String by, String value) {
        return WebElementFinder.findElements(this, by, value);
    }

    public CompletableFuture<String> getSource() {
        return WebElementDom.getSource(this);
    }

    public CompletableFuture<Void> setSource(String value) {
        return WebElementDom.setSource(this, value);
    }

    public CompletableFuture<List<Map<String, Object>>> getListeners(int depth) {
        return WebElementDom.getListeners(this, depth);
    }

    public CompletableFuture<String> getObjId() {
        return WebElementNode.getObjId(this);
    }

    public CompletableFuture<Integer> getNodeId() {
        return WebElementNode.getNodeId(this);
    }

    public CompletableFuture<List<WebElement>> getShadowRoots() {
        return WebElementNode.getShadowRoots(this);
    }

    public CompletableFuture<WebElement> getShadowRoot() {
        return WebElementNode.getShadowRoot(this);
    }

    public CompletableFuture<WebElement> getContentDocument() {
        return WebElementNode.getContentDocument(this);
    }

    public CompletableFuture<Void> remove() {
        return WebElementDom.remove(this);
    }

    public CompletableFuture<Void> highlight(boolean highlight) {
        return WebElementDom.highlight(this, highlight);
    }

    public CompletableFuture<Void> moveTo(Float timeout) {
        return WebElementInteractions.moveTo(this, timeout);
    }

    public CompletableFuture<Void> write(String text) {
        return WebElementInteractions.write(this, text);
    }

    public CompletableFuture<Void> setFile(String path) {
        return WebElementDom.setFile(this, path);
    }

    public CompletableFuture<Void> setFiles(List<String> paths) {
        return WebElementDom.setFiles(this, paths);
    }

    public CompletableFuture<Map<String, Object>> getMidLocation(
        float spreadA, float spreadB, float biasA, float biasB, float timeout
    ) {
        return WebElementGeometry.getMidLocation(this, spreadA, spreadB, biasA, biasB, timeout);
    }

    public CompletableFuture<Void> submit() {
        return WebElementInteractions.submit(this);
    }

    public CompletableFuture<Map<String, String>> getDomAttributes() {
        return WebElementDom.getDomAttributes(this);
    }

    public CompletableFuture<String> getDomAttribute(String name) {
        return WebElementDom.getDomAttribute(this, name);
    }

    public CompletableFuture<Void> setDomAttribute(String name, String value) {
        return WebElementDom.setDomAttribute(this, name, value);
    }

    public CompletableFuture<Boolean> isVisible() {
        return WebElementGeometry.isVisible(this);
    }

    public CompletableFuture<Map<String, Object>> locationOnceScrolledIntoView() {
        return WebElementGeometry.locationOnceScrolledIntoView(this);
    }

    public CompletableFuture<Void> scrollTo(Map<String, Object> rect) {
        return WebElementGeometry.scrollTo(this, rect);
    }

    public CompletableFuture<String> getValueOfCssProperty(String propertyName) {
        return WebElementAttributes.getValueOfCssProperty(this, propertyName);
    }

    public CompletableFuture<Map<String, Object>> getRect() {
        return WebElementGeometry.getRect(this);
    }

    public CompletableFuture<Map<String, Object>> getBoxModel() {
        return WebElementGeometry.getBoxModel(this);
    }

    public CompletableFuture<String> getAriaRole() {
        return WebElementDom.getAriaRole(this);
    }

    public CompletableFuture<String> getAccessibleName() {
        return WebElementDom.getAccessibleName(this);
    }

    public CompletableFuture<String> getScreenshotAsBase64() {
        return WebElementScreenshots.getScreenshotAsBase64(this);
    }

    public CompletableFuture<byte[]> getScreenshotAsPng() {
        return WebElementScreenshots.getScreenshotAsPng(this);
    }

    public CompletableFuture<Boolean> screenshot(String filename) {
        return WebElementScreenshots.screenshot(this, filename);
    }

    public CompletableFuture<WebElement> getParent() {
        return WebElementNode.getParent(this);
    }

    public CompletableFuture<List<WebElement>> getChildren() {
        return WebElementNode.getChildren(this);
    }

    public CompletableFuture<Object> executeRawScript(String script, boolean awaitResult, Object... args) {
        return WebElementScriptBridge.executeRawScript(this, script, awaitResult, args);
    }

    public CompletableFuture<Object> executeAsyncScript(String script, int maxDepth, Object... args) {
        return WebElementScriptBridge.executeAsyncScript(this, script, maxDepth, args);
    }

    public CompletableFuture<Object> evalAsync(String script, int maxDepth, Object... args) {
        return WebElementScriptBridge.evalAsync(this, script, maxDepth, args);
    }

    @Override
    public String toString() {
        return String.format("<WebElement (obj_id=\"%s\", node_id=%d)>", objId, nodeId);
    }

    @Override
    public boolean equals(Object obj) {
        if (this == obj) return true;
        if (!(obj instanceof WebElement)) return false;
        WebElement other = (WebElement) obj;
        return Objects.equals(objId, other.objId) &&
               Objects.equals(nodeId, other.nodeId) &&
               Objects.equals(backendNodeId, other.backendNodeId);
    }

    @Override
    public int hashCode() {
        return Objects.hash(objId, nodeId, backendNodeId);
    }
}
