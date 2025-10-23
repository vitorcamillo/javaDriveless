package io.github.selenium.javaDriverless.sync;

import com.fasterxml.jackson.databind.JsonNode;

import io.github.selenium.javaDriverless.types.BaseTarget;

import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.function.Consumer;

/**
 * Versão síncrona (bloqueante) do BaseTarget.
 */
public class SyncBaseTarget {
    
    private final BaseTarget asyncBaseTarget;
    
    public SyncBaseTarget(BaseTarget asyncBaseTarget) {
        this.asyncBaseTarget = asyncBaseTarget;
    }
    
    public String getId() {
        return asyncBaseTarget.getId();
    }
    
    public String getType() {
        return asyncBaseTarget.getType().join();
    }
    
    public JsonNode waitForCdp(String event, Float timeout) {
        return asyncBaseTarget.waitForCdp(event, timeout).join();
    }
    
    public void addCdpListener(String event, Consumer<JsonNode> callback) {
        asyncBaseTarget.addCdpListener(event, callback).join();
    }
    
    public void removeCdpListener(String event, Consumer<JsonNode> callback) {
        asyncBaseTarget.removeCdpListener(event, callback).join();
    }
    
    public BlockingQueue<JsonNode> getCdpEventIter(String event) {
        return asyncBaseTarget.getCdpEventIter(event).join();
    }
    
    public JsonNode executeCdpCmd(String cmd, Map<String, Object> cmdArgs, Float timeout) {
        return asyncBaseTarget.executeCdpCmd(cmd, cmdArgs, timeout).join();
    }
    
    public String downloadsDirForContext(String contextId) {
        return asyncBaseTarget.downloadsDirForContext(contextId);
    }
    
    public void close() {
        asyncBaseTarget.close().join();
    }
}

