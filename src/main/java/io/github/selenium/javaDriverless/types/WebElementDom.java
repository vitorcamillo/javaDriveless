package io.github.selenium.javaDriverless.types;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

final class WebElementDom {
    private WebElementDom() {
    }

    static CompletableFuture<String> getSource(WebElement element) {
        element.checkStale();

        return element.target.executeCdpCmd("DOM.getOuterHTML", nodeReferenceArgs(element), null)
            .thenApply(result -> result.get("outerHTML").asText())
            .exceptionally(e -> {
                if (e.getMessage() != null && e.getMessage().contains("Could not find node")) {
                    throw new WebElement.StaleElementReferenceException("Element is stale");
                }
                throw new RuntimeException(e);
            });
    }

    static CompletableFuture<Void> setSource(WebElement element, String value) {
        element.checkStale();

        Map<String, Object> args = nodeReferenceArgs(element);
        args.put("outerHTML", value);

        return element.target.executeCdpCmd("DOM.setOuterHTML", args, null).thenApply(v -> null);
    }

    static CompletableFuture<List<Map<String, Object>>> getListeners(WebElement element, int depth) {
        element.checkStale();

        return element.getObjId().thenCompose(objId -> {
            Map<String, Object> args = new HashMap<>();
            args.put("objectId", objId);
            args.put("depth", depth);
            args.put("pierce", true);

            return element.target.executeCdpCmd("DOMDebugger.getEventListeners", args, null);
        }).thenApply(result -> {
            JsonNode listeners = result.get("listeners");
            List<Map<String, Object>> listenerList = new java.util.ArrayList<>();

            for (JsonNode listener : listeners) {
                Map<String, Object> listenerMap = new HashMap<>();
                listener.fields().forEachRemaining(entry ->
                    listenerMap.put(entry.getKey(), entry.getValue())
                );
                listenerList.add(listenerMap);
            }

            return listenerList;
        });
    }

    static CompletableFuture<Void> remove(WebElement element) {
        element.checkStale();
        // Nativo: DOM.removeNode(nodeId), sem this.remove() via JS.
        return element.getNodeId().thenCompose(nodeId ->
                element.target.executeCdpCmd("DOM.removeNode", Map.of("nodeId", nodeId), null))
            .thenApply(v -> null);
    }

    static CompletableFuture<Void> highlight(WebElement element, boolean highlight) {
        return element.getNodeId().thenCompose(nodeId -> {
            if (highlight) {
                Map<String, Object> config = new HashMap<>();
                config.put("contentColor", Map.of("r", 255, "g", 0, "b", 0, "a", 0.5));

                Map<String, Object> args = new HashMap<>();
                args.put("nodeId", nodeId);
                args.put("highlightConfig", config);

                return element.target.executeCdpCmd("Overlay.highlightNode", args, null)
                    .thenApply(v -> null);
            }
            return element.target.executeCdpCmd("Overlay.hideHighlight", null, null).thenApply(v -> null);
        });
    }

    static CompletableFuture<Void> setFile(WebElement element, String path) {
        return setFiles(element, List.of(path));
    }

    static CompletableFuture<Void> setFiles(WebElement element, List<String> paths) {
        return element.getNodeId().thenCompose(nodeId -> {
            Map<String, Object> args = new HashMap<>();
            args.put("nodeId", nodeId);
            args.put("files", paths);

            return element.target.executeCdpCmd("DOM.setFileInputFiles", args, null)
                .thenApply(v -> null);
        });
    }

    static CompletableFuture<Map<String, String>> getDomAttributes(WebElement element) {
        return element.getNodeId().thenCompose(nodeId -> {
            Map<String, Object> args = new HashMap<>();
            args.put("nodeId", nodeId);

            return element.target.executeCdpCmd("DOM.getAttributes", args, null);
        }).thenApply(result -> {
            JsonNode attributesNode = result.get("attributes");
            Map<String, String> attributes = new HashMap<>();

            if (attributesNode.isArray()) {
                for (int i = 0; i < attributesNode.size(); i += 2) {
                    String key = attributesNode.get(i).asText();
                    String value = i + 1 < attributesNode.size() ? attributesNode.get(i + 1).asText() : "";
                    attributes.put(key, value);
                }
            }

            return attributes;
        });
    }

    static CompletableFuture<String> getDomAttribute(WebElement element, String name) {
        return getDomAttributes(element).thenApply(attrs -> attrs.get(name));
    }

    static CompletableFuture<Void> setDomAttribute(WebElement element, String name, String value) {
        return element.getNodeId().thenCompose(nodeId -> {
            Map<String, Object> args = new HashMap<>();
            args.put("nodeId", nodeId);
            args.put("name", name);
            args.put("value", value);

            return element.target.executeCdpCmd("DOM.setAttributeValue", args, null)
                .thenApply(v -> null);
        });
    }

    static CompletableFuture<String> getAriaRole(WebElement element) {
        return getDomAttribute(element, "role");
    }

    static CompletableFuture<String> getAccessibleName(WebElement element) {
        return getDomAttribute(element, "aria-label");
    }

    private static Map<String, Object> nodeReferenceArgs(WebElement element) {
        Map<String, Object> args = new HashMap<>();
        if (element.nodeId != null) {
            args.put("nodeId", element.nodeId);
        } else if (element.objId != null) {
            args.put("objectId", element.objId);
        } else if (element.backendNodeId != null) {
            args.put("backendNodeId", element.backendNodeId);
        }
        return args;
    }
}
