package io.github.selenium.javaDriverless.types;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

final class WebElementNode {
    private WebElementNode() {
    }

    static CompletableFuture<JsonNode> describe(WebElement element) {
        return getNodeId(element).thenCompose(nodeId -> {
            Map<String, Object> args = new HashMap<>();
            args.put("nodeId", nodeId);
            args.put("depth", 1);
            args.put("pierce", true);

            return element.target.executeCdpCmd("DOM.describeNode", args, null)
                .thenApply(result -> result.get("node"));
        });
    }

    static CompletableFuture<String> getFrameId(WebElement element) {
        return element.describe().thenApply(description -> {
            JsonNode frameId = description.get("frameId");
            return frameId != null ? frameId.asText() : null;
        });
    }

    static CompletableFuture<String> getDocumentUrl(WebElement element) {
        return element.describe().thenApply(description -> {
            JsonNode docUrl = description.get("documentURL");
            return docUrl != null ? docUrl.asText() : null;
        });
    }

    static CompletableFuture<Integer> getBackendNodeId(WebElement element) {
        if (element.backendNodeId != null) {
            return CompletableFuture.completedFuture(element.backendNodeId);
        }

        return element.describe().thenApply(description -> {
            JsonNode backendNode = description.get("backendNodeId");
            int id = backendNode != null ? backendNode.asInt() : 0;
            element.backendNodeId = id;
            return id;
        });
    }

    static CompletableFuture<Integer> getContextId(WebElement element) {
        return element.getObjId().thenCompose(objId -> {
            Map<String, Object> args = new HashMap<>();
            args.put("objectId", objId);

            return element.target.executeCdpCmd("Runtime.getProperties", args, null);
        }).thenApply(result -> {
            JsonNode internalProps = result.get("internalProperties");
            if (internalProps != null && internalProps.isArray()) {
                for (JsonNode prop : internalProps) {
                    if ("[[ContextId]]".equals(prop.get("name").asText())) {
                        return prop.get("value").get("value").asInt();
                    }
                }
            }
            return element.isolatedExecId != null ? element.isolatedExecId : 0;
        });
    }

    static CompletableFuture<String> getObjId(WebElement element) {
        element.checkStale();
        if (element.objId != null) {
            return CompletableFuture.completedFuture(element.objId);
        }

        Map<String, Object> args = new HashMap<>();
        if (element.backendNodeId != null) {
            args.put("backendNodeId", element.backendNodeId);
        } else if (element.nodeId != null) {
            args.put("nodeId", element.nodeId);
        } else {
            return CompletableFuture.failedFuture(new IllegalStateException("Missing remote element IDs"));
        }

        if (element.contextId != null) {
            args.put("executionContextId", element.contextId);
        }

        return element.target.executeCdpCmd("DOM.resolveNode", args, null)
            .thenApply(result -> {
                JsonNode object = result.get("object");
                String resolvedObjId = object.has("objectId") ? object.get("objectId").asText() : null;

                if (resolvedObjId != null) {
                    element.objId = resolvedObjId;
                    if (object.has("className")) {
                        element.className = object.get("className").asText();
                    }
                }

                return resolvedObjId;
            })
            .exceptionally(e -> {
                if (e.getMessage() != null && e.getMessage().contains("No node with given id")) {
                    throw new WebElement.StaleElementReferenceException("Element is stale");
                }
                throw new RuntimeException(e);
            });
    }

    static CompletableFuture<Integer> getNodeId(WebElement element) {
        element.checkStale();
        if (element.nodeId != null) {
            return CompletableFuture.completedFuture(element.nodeId);
        }

        return element.getObjId().thenCompose(objId -> {
            Map<String, Object> args = new HashMap<>();
            args.put("objectId", objId);

            return element.target.executeCdpCmd("DOM.requestNode", args, null);
        }).thenApply(result -> {
            int resolvedNodeId = result.get("nodeId").asInt();
            element.nodeId = resolvedNodeId;
            return resolvedNodeId;
        });
    }

    static CompletableFuture<List<WebElement>> getShadowRoots(WebElement element) {
        return element.describe().thenApply(description -> {
            List<WebElement> roots = new ArrayList<>();
            JsonNode shadowRootsNode = description.get("shadowRoots");
            if (shadowRootsNode != null && shadowRootsNode.isArray()) {
                for (JsonNode rootNode : shadowRootsNode) {
                    roots.add(fromBackendNode(element, rootNode.get("backendNodeId").asInt()));
                }
            }
            return roots;
        });
    }

    static CompletableFuture<WebElement> getShadowRoot(WebElement element) {
        return getShadowRoots(element).thenApply(roots -> roots.isEmpty() ? null : roots.get(0));
    }

    static CompletableFuture<WebElement> getContentDocument(WebElement element) {
        return element.describe().thenApply(description -> {
            JsonNode contentDocNode = description.get("contentDocument");
            if (contentDocNode != null) {
                return fromBackendNode(element, contentDocNode.get("backendNodeId").asInt());
            }
            return null;
        });
    }

    static CompletableFuture<WebElement> getParent(WebElement element) {
        return element.describe().thenApply(description -> {
            JsonNode parentNode = description.get("parentNode");
            if (parentNode != null) {
                return fromBackendNode(element, parentNode.get("backendNodeId").asInt());
            }
            return null;
        });
    }

    static CompletableFuture<List<WebElement>> getChildren(WebElement element) {
        return element.describe().thenApply(description -> {
            List<WebElement> children = new ArrayList<>();
            JsonNode childrenNode = description.get("children");
            if (childrenNode != null && childrenNode.isArray()) {
                for (JsonNode childNode : childrenNode) {
                    children.add(fromBackendNode(element, childNode.get("backendNodeId").asInt()));
                }
            }
            return children;
        });
    }

    // Package-visível: o finder nativo (WebElementFinder) constrói resultados pela mesma via de
    // identidade estável (backendNodeId) que getChildren/getParent/getShadowRoots usam.
    static WebElement fromBackendNode(WebElement element, int backendNodeId) {
        return new WebElement(element.target, element.frameId, element.isolatedExecId,
            null, null, backendNodeId, null, false);
    }
}
