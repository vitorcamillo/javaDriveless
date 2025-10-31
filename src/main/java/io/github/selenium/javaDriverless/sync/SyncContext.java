package io.github.selenium.javaDriverless.sync;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import io.github.selenium.javaDriverless.types.Context;
import io.github.selenium.javaDriverless.types.WebElement;

/**
 * Versão síncrona (bloqueante) do Context.
 */
public class SyncContext {
    
    private final Context asyncContext;
    
    public SyncContext(Context asyncContext) {
        this.asyncContext = asyncContext;
    }
    
    public SyncTarget getCurrentTarget() {
        return new SyncTarget(asyncContext.getCurrentTarget());
    }
    
    public Map<String, Object> get(String url, boolean waitLoad) {
        return asyncContext.get(url, waitLoad).join();
    }
    
    public void get(String url) {
        get(url, true);
    }
    
    public String getTitle() {
        return asyncContext.getTitle().join();
    }
    
    public String getCurrentUrl() {
        return asyncContext.getCurrentUrl().join();
    }
    
    public String getPageSource() {
        return asyncContext.getPageSource().join();
    }
    
    public Object executeScript(String script, Object[] args, boolean awaitPromise) {
        return asyncContext.executeScript(script, args, awaitPromise).join();
    }
    
    public SyncWebElement findElement(String by, String value, float timeout) {
        WebElement elem = asyncContext.findElement(by, value, timeout).join();
        return new SyncWebElement(elem);
    }
    
    public List<SyncWebElement> findElements(String by, String value, float timeout) {
        List<WebElement> elems = asyncContext.findElements(by, value, timeout).join();
        return elems.stream().map(SyncWebElement::new).collect(Collectors.toList());
    }
    
    public List<Map<String, Object>> getCookies() {
        return asyncContext.getCookies().join();
    }
    
    public Map<String, Object> getCookie(String name) {
        return asyncContext.getCookie(name).join();
    }
    
    public void addCookie(Map<String, Object> cookieDict) {
        asyncContext.addCookie(cookieDict).join();
    }
    
    public void deleteCookie(String name) {
        asyncContext.deleteCookie(name).join();
    }
    
    public void deleteAllCookies() {
        asyncContext.deleteAllCookies().join();
    }
    
    public void sleep(double seconds) {
        asyncContext.sleep(seconds).join();
    }
    
    public void back() {
        asyncContext.back().join();
    }
    
    public void forward() {
        asyncContext.forward().join();
    }
    
    public void refresh(boolean ignoreCache) {
        asyncContext.refresh(ignoreCache).join();
    }
    
    public void quit() {
        asyncContext.quit().join();
    }
    
    public byte[] getScreenshotAsPng() {
        return asyncContext.getScreenshotAsPng().join();
    }
    
    public void getScreenshotAsFile(String filename) {
        asyncContext.getScreenshotAsFile(filename).join();
    }
    
    public void saveScreenshot(String filename) {
        asyncContext.saveScreenshot(filename).join();
    }
    
    public String snapshot() {
        return asyncContext.snapshot().join();
    }
    
    public void saveSnapshot(String filename) {
        asyncContext.saveSnapshot(filename).join();
    }
    
    public void setNetworkConditions(boolean offline, int latency,
                                     int downloadThroughput, int uploadThroughput,
                                     String connectionType) {
        asyncContext.setNetworkConditions(offline, latency,
            downloadThroughput, uploadThroughput, connectionType).join();
    }
    
    public void sendKeys(String text) {
        asyncContext.sendKeys(text).join();
    }
    
    public void close() {
        quit();
    }
}

