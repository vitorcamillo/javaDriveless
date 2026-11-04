package io.github.selenium.javaDriverless.types;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

final class WebElementScriptExecutor {
    private WebElementScriptExecutor() {
    }

    static CompletableFuture<Object> executeScript(WebElement element, String script, Object arg,
                                                   String serialization, boolean uniqueContext,
                                                   float timeout) {
        return ensureObjId(element).thenCompose(v -> {
            String wrappedScript = "(function(...arguments){ const obj = this; " + script + " })";
            Object[] args = arg != null ? new Object[]{arg} : new Object[0];

            Map<String, Object> cdpArgs = new HashMap<>();
            cdpArgs.put("functionDeclaration", wrappedScript);
            cdpArgs.put("objectId", element.objId);
            cdpArgs.put("arguments", convertArgsToRemoteObjects(args));
            cdpArgs.put("returnByValue", serialization == null);
            cdpArgs.put("awaitPromise", false);

            if (serialization != null) {
                Map<String, Object> serOpts = new HashMap<>();
                serOpts.put("serialization", serialization);
                serOpts.put("maxDepth", 2);
                cdpArgs.put("serializationOptions", serOpts);
            }

            return element.target.executeCdpCmd("Runtime.callFunctionOn", cdpArgs, timeout)
                .thenApply(result -> result.has("result") ? parseResult(element, result.get("result")) : null);
        });
    }

    private static List<Map<String, Object>> convertArgsToRemoteObjects(Object[] args) {
        List<Map<String, Object>> remoteArgs = new ArrayList<>();
        for (Object arg : args) {
            Map<String, Object> remoteArg = new HashMap<>();
            if (arg instanceof String || arg instanceof Number || arg instanceof Boolean) {
                remoteArg.put("value", arg);
            } else {
                remoteArg.put("value", arg != null ? arg.toString() : null);
            }
            remoteArgs.add(remoteArg);
        }
        return remoteArgs;
    }

    private static CompletableFuture<Void> ensureObjId(WebElement element) {
        if (element.objId != null) {
            return CompletableFuture.completedFuture(null);
        }

        if (element.nodeId != null || element.backendNodeId != null) {
            Map<String, Object> args = new HashMap<>();
            if (element.nodeId != null) {
                args.put("nodeId", element.nodeId);
            } else {
                args.put("backendNodeId", element.backendNodeId);
            }

            return element.target.executeCdpCmd("DOM.resolveNode", args, 5.0f)
                .thenApply(result -> {
                    if (result.has("object") && result.get("object").has("objectId")) {
                        element.objId = result.get("object").get("objectId").asText();
                    }
                    return null;
                });
        }

        return CompletableFuture.completedFuture(null);
    }

    private static Object parseResult(WebElement element, JsonNode resultNode) {
        if (resultNode.has("deepSerializedValue")) {
            return parseDeepSerializedValue(element, resultNode.get("deepSerializedValue"));
        }

        if (resultNode.has("value")) {
            JsonNode valueNode = resultNode.get("value");
            if (valueNode.isArray()) {
                List<Object> list = new ArrayList<>();
                for (JsonNode item : valueNode) {
                    list.add(convertNodeToObject(element, item));
                }
                return list;
            }
            return convertNodeToObject(element, valueNode);
        }
        return null;
    }

    private static Object parseDeepSerializedValue(WebElement element, JsonNode deepValue) {
        if (!deepValue.has("type")) {
            return null;
        }

        String type = deepValue.get("type").asText();

        if (deepValue.has("value") && deepValue.get("value").isArray()) {
            List<Object> list = new ArrayList<>();
            for (JsonNode item : deepValue.get("value")) {
                if (item.has("type") && "node".equals(item.get("type").asText())) {
                    JsonNode nodeValue = item.get("value");
                    if (nodeValue != null && nodeValue.has("backendNodeId")) {
                        int backendNodeId = nodeValue.get("backendNodeId").asInt();
                        list.add(new WebElement(element.target, element.frameId, element.isolatedExecId,
                            null, null, backendNodeId, element.contextId, false));
                    }
                } else {
                    list.add(parseDeepSerializedValue(element, item));
                }
            }
            return list;
        }

        if ("node".equals(type) && deepValue.has("value")) {
            JsonNode nodeValue = deepValue.get("value");
            if (nodeValue.has("backendNodeId")) {
                int backendNodeId = nodeValue.get("backendNodeId").asInt();
                return new WebElement(element.target, element.frameId, element.isolatedExecId,
                    null, null, backendNodeId, element.contextId, false);
            }
        }

        if (deepValue.has("value")) {
            JsonNode valueNode = deepValue.get("value");
            if (valueNode.isValueNode()) {
                return convertNodeToObject(element, valueNode);
            }
        }

        return null;
    }

    private static Object convertNodeToObject(WebElement element, JsonNode node) {
        if (node.isNull()) return null;
        if (node.isBoolean()) return node.asBoolean();
        if (node.isInt()) return node.asInt();
        if (node.isLong()) return node.asLong();
        if (node.isDouble()) return node.asDouble();
        if (node.isTextual()) return node.asText();
        if (node.isArray()) {
            List<Object> list = new ArrayList<>();
            for (JsonNode item : node) {
                list.add(convertNodeToObject(element, item));
            }
            return list;
        }
        if (node.isObject() && node.has("backendNodeId")) {
            int backendNodeId = node.get("backendNodeId").asInt();
            return new WebElement(element.target, element.frameId, element.isolatedExecId,
                null, null, backendNodeId, element.contextId, false);
        }
        return node.toString();
    }
}
