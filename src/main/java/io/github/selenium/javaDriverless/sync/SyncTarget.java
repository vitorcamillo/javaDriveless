package io.github.selenium.javaDriverless.sync;

import com.fasterxml.jackson.databind.JsonNode;

import io.github.selenium.javaDriverless.input.Pointer;
import io.github.selenium.javaDriverless.types.Target;
import io.github.selenium.javaDriverless.types.WebElement;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Versão síncrona (bloqueante) do Target.
 */
public class SyncTarget {
    
    private final Target asyncTarget;
    
    public SyncTarget(Target asyncTarget) {
        this.asyncTarget = asyncTarget;
    }
    
    public Map<String, Object> get(String url, boolean waitLoad) {
        return asyncTarget.get(url, waitLoad).join();
    }
    
    public void get(String url) {
        get(url, true);
    }
    
    public void back() {
        asyncTarget.back().join();
    }
    
    public void forward() {
        asyncTarget.forward().join();
    }
    
    public void refresh(boolean ignoreCache) {
        asyncTarget.refresh(ignoreCache).join();
    }
    
    public void refresh() {
        refresh(false);
    }
    
    public String getCurrentUrl() {
        return asyncTarget.getCurrentUrl().join();
    }
    
    public String getTitle() {
        return asyncTarget.getTitle().join();
    }
    
    public String getPageSource() {
        return asyncTarget.getPageSource().join();
    }
    
    public Object executeScript(String script, Object[] args, boolean awaitPromise) {
        return asyncTarget.executeScript(script, args, awaitPromise).join();
    }
    
    public Object executeScript(String script) {
        return executeScript(script, null, false);
    }
    
    public SyncWebElement findElement(String by, String value, float timeout) {
        WebElement elem = asyncTarget.findElement(by, value, timeout).join();
        return new SyncWebElement(elem);
    }
    
    public List<SyncWebElement> findElements(String by, String value, float timeout) {
        List<WebElement> elems = asyncTarget.findElements(by, value, timeout).join();
        return elems.stream().map(SyncWebElement::new).collect(Collectors.toList());
    }
    
    public JsonNode waitForCdp(String event, Float timeout) {
        return asyncTarget.waitForCdp(event, timeout).join();
    }
    
    public void sleep(double seconds) {
        asyncTarget.sleep(seconds).join();
    }
    
    public Pointer getPointer() {
        return asyncTarget.getPointer();
    }
    
    public List<Map<String, Object>> getCookies() {
        return asyncTarget.getCookies().join();
    }
    
    public Map<String, Object> getCookie(String name) {
        return asyncTarget.getCookie(name).join();
    }
    
    public void addCookie(Map<String, Object> cookieDict) {
        asyncTarget.addCookie(cookieDict).join();
    }
    
    public void deleteCookie(String name) {
        asyncTarget.deleteCookie(name).join();
    }
    
    public void deleteAllCookies() {
        asyncTarget.deleteAllCookies().join();
    }
    
    public byte[] getScreenshotAsPng() {
        return asyncTarget.getScreenshotAsPng().join();
    }
    
    public void getScreenshotAsFile(String filename) {
        asyncTarget.getScreenshotAsFile(filename).join();
    }
    
    public void saveScreenshot(String filename) {
        asyncTarget.saveScreenshot(filename).join();
    }
    
    public String snapshot() {
        return asyncTarget.snapshot().join();
    }
    
    public void saveSnapshot(String filename) {
        asyncTarget.saveSnapshot(filename).join();
    }
    
    public void sendKeys(String text) {
        asyncTarget.sendKeys(text).join();
    }
    
    public void focus() {
        asyncTarget.focus().join();
    }
    
    public void activate() {
        asyncTarget.activate().join();
    }
    
    public void setNetworkConditions(boolean offline, int latency,
                                     int downloadThroughput, int uploadThroughput,
                                     String connectionType) {
        asyncTarget.setNetworkConditions(offline, latency, 
            downloadThroughput, uploadThroughput, connectionType).join();
    }
    
    public void close() {
        asyncTarget.close().join();
    }
}

