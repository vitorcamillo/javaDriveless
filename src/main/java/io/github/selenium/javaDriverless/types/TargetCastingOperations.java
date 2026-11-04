package io.github.selenium.javaDriverless.types;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import io.github.selenium.javaDriverless.cdp.CDPCommandExecutor;
import io.github.selenium.javaDriverless.cdp.CDPSocket;
import io.github.selenium.javaDriverless.cdp.exceptions.CDPException;
import io.github.selenium.javaDriverless.input.Pointer;
import io.github.selenium.javaDriverless.logging.JavaDriverlessLogger;
import io.github.selenium.javaDriverless.types.TypesExceptions.NoSuchIframe;

import org.slf4j.Logger;

import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Consumer;
import java.util.Base64;

abstract class TargetCastingOperations extends TargetRequestOperations {

    protected TargetCastingOperations(String host, String targetId, Object driver, Object context,
                     boolean isRemote, float timeout, String type, boolean startSocket, int maxWsSize) {
        super(host, targetId, driver, context, isRemote, timeout, type, startSocket, maxWsSize);
    }

    /**
     * Obtém os sinks disponíveis para casting (ChromeCast, etc).
     *
     * @return CompletableFuture com a lista de sinks
     */
    public CompletableFuture<List<Map<String, Object>>> getSinks() {
        return executeCdpCmd("Cast.enable", null, null)
            .thenCompose(v -> waitForCdp("Cast.sinksUpdated", 5.0f))
            .thenApply(event -> {
                JsonNode sinksNode = event.get("sinks");
                List<Map<String, Object>> sinks = new ArrayList<>();
                for (JsonNode sink : sinksNode) {
                    sinks.add(objectMapper.convertValue(sink, Map.class));
                }
                return sinks;
            });
    }

    /**
     * Obtém mensagem de issue.
     *
     * @return CompletableFuture com a mensagem
     */
    public CompletableFuture<String> getIssueMessage() {
        return waitForCdp("Cast.issueUpdated", 5.0f)
            .thenApply(event -> event.get("issueMessage").asText());
    }

    /**
     * Define qual sink usar para casting.
     *
     * @param sinkName nome do sink
     * @return CompletableFuture com o resultado
     */
    public CompletableFuture<Map<String, Object>> setSinkToUse(String sinkName) {
        Map<String, Object> args = new HashMap<>();
        args.put("sinkName", sinkName);

        return executeCdpCmd("Cast.setSinkToUse", args, null)
            .thenApply(result -> objectMapper.convertValue(result, Map.class));
    }

    /**
     * Inicia mirroring de desktop para sink.
     *
     * @param sinkName nome do sink
     * @return CompletableFuture com o resultado
     */
    public CompletableFuture<Map<String, Object>> startDesktopMirroring(String sinkName) {
        Map<String, Object> args = new HashMap<>();
        args.put("sinkName", sinkName);

        return executeCdpCmd("Cast.startDesktopMirroring", args, null)
            .thenApply(result -> objectMapper.convertValue(result, Map.class));
    }

    /**
     * Inicia mirroring de tab para sink.
     *
     * @param sinkName nome do sink
     * @return CompletableFuture com o resultado
     */
    public CompletableFuture<Map<String, Object>> startTabMirroring(String sinkName) {
        Map<String, Object> args = new HashMap<>();
        args.put("sinkName", sinkName);

        return executeCdpCmd("Cast.startTabMirroring", args, null)
            .thenApply(result -> objectMapper.convertValue(result, Map.class));
    }

    /**
     * Para o casting para um sink.
     *
     * @param sinkName nome do sink
     * @return CompletableFuture com o resultado
     */
    public CompletableFuture<Map<String, Object>> stopCasting(String sinkName) {
        Map<String, Object> args = new HashMap<>();
        args.put("sinkName", sinkName);

        return executeCdpCmd("Cast.stopCasting", args, null)
            .thenApply(result -> objectMapper.convertValue(result, Map.class));
    }

}
