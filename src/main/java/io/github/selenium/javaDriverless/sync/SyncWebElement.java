package io.github.selenium.javaDriverless.sync;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import io.github.selenium.javaDriverless.types.WebElement;

/**
 * Versão síncrona (bloqueante) do WebElement.
 */
public class SyncWebElement {
    
    private final WebElement asyncElement;
    
    public SyncWebElement(WebElement asyncElement) {
        this.asyncElement = asyncElement;
    }
    
    public void click(boolean moveTo, double totalTime, double accel, double smoothSoft) {
        asyncElement.click(moveTo, totalTime, accel, smoothSoft).join();
    }
    
    public void click() {
        click(true, 0.5, 2.0, 20.0);
    }
    
    public void sendKeys(String keys) {
        asyncElement.sendKeys(keys).join();
    }
    
    public void clear() {
        asyncElement.clear().join();
    }
    
    public String getText() {
        return asyncElement.getText().join();
    }
    
    public String getTagName() {
        return asyncElement.getTagName().join();
    }
    
    public String getAttribute(String name) {
        return asyncElement.getAttribute(name).join();
    }
    
    public boolean isDisplayed() {
        return asyncElement.isDisplayed().join();
    }
    
    public boolean isEnabled() {
        return asyncElement.isEnabled().join();
    }
    
    public boolean isSelected() {
        return asyncElement.isSelected().join();
    }
    
    public double[] getLocation() {
        return asyncElement.getLocation().join();
    }
    
    public double[] getSize() {
        return asyncElement.getSize().join();
    }
    
    public double[] getMidLocation() {
        return asyncElement.getMidLocation().join();
    }
    
    public SyncWebElement findElement(String by, String value, float timeout) {
        WebElement elem = asyncElement.findElement(by, value, timeout).join();
        return new SyncWebElement(elem);
    }
    
    public List<SyncWebElement> findElements(String by, String value) {
        List<WebElement> elems = asyncElement.findElements(by, value).join();
        return elems.stream().map(SyncWebElement::new).collect(Collectors.toList());
    }
    
    public String getSource() {
        return asyncElement.getSource().join();
    }
    
    public void setSource(String value) {
        asyncElement.setSource(value).join();
    }
    
    public List<Map<String, Object>> getListeners(int depth) {
        return asyncElement.getListeners(depth).join();
    }
    
    public String getObjId() {
        return asyncElement.getObjId().join();
    }
    
    public Integer getNodeId() {
        return asyncElement.getNodeId().join();
    }
}

