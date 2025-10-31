package io.github.selenium.javaDriverless.sync;

import io.github.selenium.javaDriverless.types.Alert;

/**
 * Versão síncrona (bloqueante) do Alert.
 */
public class SyncAlert {
    
    private final Alert asyncAlert;
    
    public SyncAlert(Alert asyncAlert) {
        this.asyncAlert = asyncAlert;
    }
    
    public String getText() {
        return asyncAlert.getText().join();
    }
    
    public String getUrl() {
        return asyncAlert.getUrl().join();
    }
    
    public String getType() {
        return asyncAlert.getType().join();
    }
    
    public boolean hasBrowserHandler() {
        return asyncAlert.hasBrowserHandler().join();
    }
    
    public String getDefaultPrompt() {
        return asyncAlert.getDefaultPrompt().join();
    }
    
    public void dismiss() {
        asyncAlert.dismiss().join();
    }
    
    public void accept() {
        asyncAlert.accept().join();
    }
    
    public void sendKeys(String keysToSend) {
        asyncAlert.sendKeys(keysToSend).join();
    }
}

