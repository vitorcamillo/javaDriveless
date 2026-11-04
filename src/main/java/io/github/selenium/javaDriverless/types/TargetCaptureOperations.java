package io.github.selenium.javaDriverless.types;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import io.github.selenium.javaDriverless.utils.OwnerOnlyFiles;
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

abstract class TargetCaptureOperations extends TargetElementOperations {

    protected TargetCaptureOperations(String host, String targetId, Object driver, Object context,
                     boolean isRemote, float timeout, String type, boolean startSocket, int maxWsSize) {
        super(host, targetId, driver, context, isRemote, timeout, type, startSocket, maxWsSize);
    }

    /**
     * Captura screenshot da janela atual como PNG.
     *
     * @return CompletableFuture com os bytes da imagem PNG
     */
    public CompletableFuture<byte[]> getScreenshotAsPng() {
        Map<String, Object> args = new HashMap<>();
        args.put("format", "png");

        return executeCdpCmd("Page.captureScreenshot", args, 30.0f)
            .thenApply(result -> {
                String base64Data = result.get("data").asText();
                return Base64.getDecoder().decode(base64Data);
            });
    }

    /**
     * Salva screenshot da janela atual em arquivo PNG.
     *
     * @param filename caminho completo do arquivo (deve terminar com .png)
     * @return CompletableFuture que completa quando o arquivo é salvo
     */
    public CompletableFuture<Void> getScreenshotAsFile(String filename) {
        if (!filename.toLowerCase().endsWith(".png")) {
            JavaDriverlessLogger.warn(logger,
                "Nome usado para screenshot não corresponde ao tipo de arquivo. Deve terminar com .png");
        }

        return getScreenshotAsPng().thenCompose(pngBytes -> {
            return CompletableFuture.runAsync(() -> {
                try {
                    OwnerOnlyFiles.writeBytes(java.nio.file.Paths.get(filename), pngBytes);
                } catch (java.io.IOException e) {
                    throw new RuntimeException("Erro ao salvar screenshot", e);
                }
            });
        });
    }

    /**
     * Alias para getScreenshotAsFile.
     *
     * @param filename caminho do arquivo
     * @return CompletableFuture que completa quando o arquivo é salvo
     */
    public CompletableFuture<Void> saveScreenshot(String filename) {
        return getScreenshotAsFile(filename);
    }

    /**
     * Obtém o snapshot atual como MHTML.
     *
     * @return CompletableFuture com o MHTML
     */
    public CompletableFuture<String> snapshot() {
        return executeCdpCmd("Page.captureSnapshot", null, null)
            .thenApply(result -> result.get("data").asText());
    }

    /**
     * Salva snapshot da janela atual em arquivo MHTML.
     *
     * @param filename caminho completo do arquivo (deve terminar com .mhtml)
     * @return CompletableFuture que completa quando o arquivo é salvo
     */
    public CompletableFuture<Void> saveSnapshot(String filename) {
        if (!filename.endsWith(".mhtml")) {
            JavaDriverlessLogger.warn(logger,
                "Nome usado para snapshot não corresponde ao tipo de arquivo. Deve terminar com .mhtml");
        }

        return snapshot().thenCompose(mhtml -> {
            return CompletableFuture.runAsync(() -> {
                try {
                    OwnerOnlyFiles.writeString(java.nio.file.Paths.get(filename), mhtml);
                } catch (java.io.IOException e) {
                    throw new RuntimeException("Erro ao salvar snapshot", e);
                }
            });
        });
    }

    /**
     * Define condições de emulação de rede do Chromium.
     *
     * @param offline se está offline
     * @param latency latência adicional em ms
     * @param downloadThroughput throughput máximo de download
     * @param uploadThroughput throughput máximo de upload
     * @param connectionType tipo de conexão
     * @return CompletableFuture que completa quando as condições são definidas
     */
    public CompletableFuture<Void> setNetworkConditions(boolean offline, int latency,
                                                        int downloadThroughput, int uploadThroughput,
                                                        String connectionType) {
        String[] validTypes = {"none", "cellular2g", "cellular3g", "cellular4g",
            "bluetooth", "ethernet", "wifi", "wimax", "other"};

        if (connectionType != null && !Arrays.asList(validTypes).contains(connectionType)) {
            return CompletableFuture.failedFuture(
                new IllegalArgumentException("connectionType inválido: " + connectionType)
            );
        }

        Map<String, Object> args = new HashMap<>();
        args.put("offline", offline);
        args.put("latency", latency);
        args.put("downloadThroughput", downloadThroughput);
        args.put("uploadThroughput", uploadThroughput);

        if (connectionType != null) {
            args.put("connectionType", connectionType);
        }

        return executeCdpCmd("Network.emulateNetworkConditions", args, null)
            .thenApply(v -> null);
    }

    /**
     * Foca no target (ativa e traz para frente).
     *
     * @return CompletableFuture que completa quando o target está focado
     */
    public CompletableFuture<Void> focus() {
        Map<String, Object> args = new HashMap<>();
        args.put("targetId", id);

        // 100% NATIVO: ativa o target e emula o foco do documento via Emulation.setFocusEmulationEnabled
        // (antes window.focus() via Runtime.evaluate, detectável). Paridade com selenium_driverless.
        return executeCdpCmd("Target.activateTarget", args, null)
            .thenCompose(v -> executeCdpCmd("Emulation.setFocusEmulationEnabled",
                Map.of("enabled", true), null))
            .thenApply(v -> null);
    }

    /**
     * Ativa o target (traz para frente).
     *
     * @return CompletableFuture que completa quando o target está ativado
     */
    public CompletableFuture<Void> activate() {
        Map<String, Object> args = new HashMap<>();
        args.put("targetId", id);

        return executeCdpCmd("Target.activateTarget", args, null)
            .thenApply(v -> null);
    }

}
