package io.github.selenium.javaDriverless.cdp;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.List;
import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

/** Interface comum dos transportes CDP. */
public interface CdpConnection {
    CompletableFuture<JsonNode> exec(String method, Map<String, Object> params, Float timeout);

    CompletableFuture<JsonNode> exec(String method, Map<String, Object> params, Float timeout, String sessionId);

    CompletableFuture<JsonNode> waitFor(String event, Float timeout);

    void addListener(String method, Consumer<JsonNode> callback);

    void removeListener(String method, Consumer<JsonNode> callback);

    void addSessionListener(String method, BiConsumer<String, JsonNode> callback);

    void removeSessionListener(String method, BiConsumer<String, JsonNode> callback);

    BlockingQueue<JsonNode> methodIterator(String method);

    void addOnClosedCallback(Runnable callback);

    List<Runnable> getOnClosed();

    CompletableFuture<Void> closeAsync();

    boolean isConnected();
}
