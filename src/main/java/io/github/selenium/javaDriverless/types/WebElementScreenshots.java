package io.github.selenium.javaDriverless.types;

import com.fasterxml.jackson.databind.JsonNode;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.Base64;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

final class WebElementScreenshots {
    private WebElementScreenshots() {
    }

    static CompletableFuture<String> getScreenshotAsBase64(WebElement element) {
        return element.getNodeId().thenCompose(nodeId -> {
            Map<String, Object> args = new HashMap<>();
            args.put("nodeId", nodeId);

            return element.target.executeCdpCmd("DOM.getBoxModel", args, null);
        }).thenCompose(boxModelResult -> {
            JsonNode modelNode = boxModelResult.get("model");
            JsonNode borderNode = modelNode.get("border");

            double minX = Double.MAX_VALUE, minY = Double.MAX_VALUE;
            double maxX = Double.MIN_VALUE, maxY = Double.MIN_VALUE;

            for (int i = 0; i < borderNode.size(); i += 2) {
                double x = borderNode.get(i).asDouble();
                double y = borderNode.get(i + 1).asDouble();
                minX = Math.min(minX, x);
                minY = Math.min(minY, y);
                maxX = Math.max(maxX, x);
                maxY = Math.max(maxY, y);
            }

            Map<String, Object> clip = new HashMap<>();
            clip.put("x", minX);
            clip.put("y", minY);
            clip.put("width", maxX - minX);
            clip.put("height", maxY - minY);
            clip.put("scale", 1.0);

            Map<String, Object> args = new HashMap<>();
            args.put("clip", clip);
            args.put("captureBeyondViewport", true);

            return element.target.executeCdpCmd("Page.captureScreenshot", args, null);
        }).thenApply(result -> result.get("data").asText());
    }

    static CompletableFuture<byte[]> getScreenshotAsPng(WebElement element) {
        return getScreenshotAsBase64(element).thenApply(base64 -> Base64.getDecoder().decode(base64));
    }

    static CompletableFuture<Boolean> screenshot(WebElement element, String filename) {
        return getScreenshotAsPng(element).thenApply(pngBytes -> {
            try {
                Files.write(Paths.get(filename), pngBytes);
                return true;
            } catch (IOException e) {
                throw new RuntimeException("Erro ao salvar screenshot: " + e.getMessage(), e);
            }
        });
    }
}
