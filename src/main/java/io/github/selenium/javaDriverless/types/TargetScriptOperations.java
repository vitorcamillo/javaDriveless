package io.github.selenium.javaDriverless.types;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.core.JsonProcessingException;

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

abstract class TargetScriptOperations extends TargetNavigationOperations {

    protected TargetScriptOperations(String host, String targetId, Object driver, Object context,
                     boolean isRemote, float timeout, String type, boolean startSocket, int maxWsSize) {
        super(host, targetId, driver, context, isRemote, timeout, type, startSocket, maxWsSize);
    }

    /**
     * Executa JavaScript na página.
     *
     * @param script código JavaScript
     * @param args argumentos opcionais
     * @param awaitPromise se deve aguardar promises
     * @return CompletableFuture com o resultado
     */
    public CompletableFuture<Object> executeScript(String script, Object[] args, boolean awaitPromise) {
        return executeScript(script, args, awaitPromise, null);
    }

    /**
     * Executa JavaScript na página com suporte a serialização deep.
     *
     * @param script código JavaScript
     * @param args argumentos opcionais
     * @param awaitPromise se deve aguardar promises
     * @param serialization tipo de serialização ("deep" para retornar WebElements)
     * @return CompletableFuture com o resultado
     */
    public CompletableFuture<Object> executeScript(String script, Object[] args, boolean awaitPromise, String serialization) {
        Map<String, Object> cdpArgs = new HashMap<>();

        // FIX: Scripts que contêm 'return' ou 'arguments[' são estilo Selenium
        // e esperam estar dentro de um corpo de função. Runtime.evaluate trata
        // o código como top-level, onde 'return' é ilegal.
        // Solução: wrappá-los dentro de uma IIFE (Immediately Invoked Function Expression).
        String expression = script;
        if (args != null && args.length > 0) {
            expression = "(function(){" + script + "}).apply(globalThis, " + argsJsonArray(args) + ")";
        }
        // FIX BUG-09: Verificação mais precisa — só wrappear se 'return' ou 'arguments['
        // aparecem no nível top-level (não dentro de funções/arrows aninhadas)
        if ((args == null || args.length == 0) && needsIifeWrapping(script)) {
            expression = "(function(){" + script + "})()";
        }

        cdpArgs.put("expression", expression);
        cdpArgs.put("awaitPromise", awaitPromise);

        // Se tem serialização deep, NÃO usar returnByValue
        if ("deep".equals(serialization)) {
            cdpArgs.put("returnByValue", false);
            Map<String, Object> serOpts = new HashMap<>();
            serOpts.put("serialization", "deep");
            serOpts.put("maxDepth", 2);
            cdpArgs.put("serializationOptions", serOpts);
        } else {
            cdpArgs.put("returnByValue", true);
        }

        if (execContextId != null && !execContextId.isEmpty()) {
            cdpArgs.put("contextId", execContextId);
        }

        return executeCdpCmd("Runtime.evaluate", cdpArgs, null)
            .thenApply(result -> {
                if (result.has("exceptionDetails")) {
                    throw new RuntimeException(javaScriptErrorMessage(result.get("exceptionDetails")));
                }

                if (result.has("result")) {
                    JsonNode resultNode = result.get("result");

                    // Se tem deepSerializedValue, processar (retorna WebElements)
                    if (resultNode.has("deepSerializedValue")) {
                        return parseDeepSerializedValue(resultNode.get("deepSerializedValue"));
                    }

                    // Senão, retornar value normal
                    if (resultNode.has("value")) {
                        return objectMapper.convertValue(resultNode.get("value"), Object.class);
                    }
                }

                return null;
            });
    }

    /**
     * Parseia deepSerializedValue do CDP para WebElements.
     */
    private Object parseDeepSerializedValue(JsonNode deepValue) {
        if (!deepValue.has("type")) {
            return null;
        }

        String type = deepValue.get("type").asText();

        // Array/List de elementos
        if (deepValue.has("value") && deepValue.get("value").isArray()) {
            List<Object> list = new ArrayList<>();
            for (JsonNode item : deepValue.get("value")) {
                if (item.has("type") && "node".equals(item.get("type").asText())) {
                    JsonNode nodeValue = item.get("value");
                    if (nodeValue != null && nodeValue.has("backendNodeId")) {
                        int backendNodeId = nodeValue.get("backendNodeId").asInt();
                        WebElement elem = new WebElement((Target) this, null, null,
                            null, null, backendNodeId, null, false);
                        list.add(elem);
                    }
                } else {
                    list.add(parseDeepSerializedValue(item));
                }
            }
            return list;
        }

        // Nó único
        if ("node".equals(type) && deepValue.has("value")) {
            JsonNode nodeValue = deepValue.get("value");
            if (nodeValue.has("backendNodeId")) {
                int backendNodeId = nodeValue.get("backendNodeId").asInt();
                return new WebElement((Target) this, null, null,
                    null, null, backendNodeId, null, false);
            }
        }

        // Valores primitivos
        if (deepValue.has("value")) {
            JsonNode valueNode = deepValue.get("value");
            if (valueNode.isValueNode()) {
                if (valueNode.isBoolean()) return valueNode.asBoolean();
                if (valueNode.isInt()) return valueNode.asInt();
                if (valueNode.isLong()) return valueNode.asLong();
                if (valueNode.isDouble()) return valueNode.asDouble();
                if (valueNode.isTextual()) return valueNode.asText();
            }
        }

        return null;
    }

    /**
     * Verifica se o script precisa ser envolvido em uma IIFE.
     * <p>
     * Retorna true se 'return' ou 'arguments[' aparecem no nível top-level
     * do script (fora de funções/arrows aninhadas).
     * </p>
     *
     * @param script código JavaScript
     * @return true se precisa de IIFE wrapping
     */
    private static boolean needsIifeWrapping(String script) {
        // Verificação rápida: se não contém nenhuma keyword relevante, não precisa
        if (!script.contains("return") && !script.contains("arguments[")) {
            return false;
        }

        // Contar profundidade de aninhamento de funções
        // Simplificação: contar { } e verificar se return/arguments aparecem no nível 0
        int depth = 0;
        int len = script.length();

        for (int i = 0; i < len; i++) {
            char c = script.charAt(i);

            // Pular strings entre aspas
            if (c == '\'' || c == '"' || c == '`') {
                char quote = c;
                i++;
                while (i < len && script.charAt(i) != quote) {
                    if (script.charAt(i) == '\\') i++; // escapar
                    i++;
                }
                continue;
            }

            // Pular comentários de linha
            if (c == '/' && i + 1 < len && script.charAt(i + 1) == '/') {
                while (i < len && script.charAt(i) != '\n') i++;
                continue;
            }

            // Pular comentários de bloco
            if (c == '/' && i + 1 < len && script.charAt(i + 1) == '*') {
                i += 2;
                while (i + 1 < len && !(script.charAt(i) == '*' && script.charAt(i + 1) == '/')) i++;
                i++;
                continue;
            }

            if (c == '{') {
                depth++;
            } else if (c == '}') {
                depth--;
            }

            // Verificar keywords no nível top-level (depth 0)
            if (depth == 0) {
                if (script.startsWith("return", i) &&
                    (i == 0 || !Character.isLetterOrDigit(script.charAt(i - 1))) &&
                    (i + 6 >= len || !Character.isLetterOrDigit(script.charAt(i + 6)))) {
                    return true;
                }
                if (script.startsWith("arguments[", i) &&
                    (i == 0 || !Character.isLetterOrDigit(script.charAt(i - 1)))) {
                    return true;
                }
            }
        }

        return false;
    }

    /**
     * Executa script JavaScript raw (forma bruta) no GlobalThis.
     *
     * @param script script JavaScript
     * @param awaitRes se deve aguardar resultado
     * @param serialization tipo de serialização (null, "deep", "json", "idOnly")
     * @param args argumentos para o script
     * @return CompletableFuture com o resultado
     */
    public CompletableFuture<Object> executeRawScript(String script, boolean awaitRes,
                                                      String serialization, Object... args) {
        return getGlobalThis(null).thenCompose(globalThis -> {
            // Em uma implementação completa, usaríamos JSRemoteObj.__exec_raw__
            // Por ora, usamos Runtime.callFunctionOn
            Map<String, Object> cdpArgs = new HashMap<>();
            cdpArgs.put("functionDeclaration", script);
            cdpArgs.put("awaitPromise", awaitRes);

            if (args != null && args.length > 0) {
                cdpArgs.put("arguments", argsAsCallArguments(args));
            }

            if (serialization != null) {
                cdpArgs.put("serializationOptions", Map.of("serialization", serialization));
            }

            if (isolatedContextId != null) {
                cdpArgs.put("executionContextId", isolatedContextId);
            } else {
                cdpArgs.put("objectId", globalThis.get("objectId"));
            }

            return executeCdpCmd("Runtime.callFunctionOn", cdpArgs, null)
                .thenApply(result -> {
                    if (result.has("exceptionDetails")) {
                        throw new RuntimeException(javaScriptErrorMessage(result.get("exceptionDetails")));
                    }
                    if (result.has("result")) {
                        JsonNode resultNode = result.get("result");
                        if (resultNode.has("value")) {
                            return objectMapper.convertValue(resultNode.get("value"), Object.class);
                        }
                    }
                    return null;
                });
        });
    }

    /**
     * Executa script JavaScript assíncrono no GlobalThis.
     * Similar a execute_async_script do Python.
     *
     * @param script script JavaScript
     * @param maxDepth profundidade máxima de serialização
     * @param serialization tipo de serialização
     * @param args argumentos para o script
     * @return CompletableFuture com o resultado
     */
    public CompletableFuture<Object> executeAsyncScript(String script, int maxDepth,
                                                        String serialization, Object... args) {
        return CompletableFuture.supplyAsync(() -> {
            long startNanos = System.nanoTime();
            float timeout = 2.0f;
            String cacheKey = "default"; // getGlobalThis(null) abaixo sempre usa esta chave

            while (true) {
                try {
                    return getGlobalThis(null).thenCompose(globalThis -> {
                        Map<String, Object> cdpArgs = new HashMap<>();
                        cdpArgs.put("functionDeclaration",
                            "async function() { " + script + " }");
                        cdpArgs.put("awaitPromise", true);
                        cdpArgs.put("returnByValue", true);

                        if (args != null && args.length > 0) {
                            cdpArgs.put("arguments", argsAsCallArguments(args));
                        }

                        if (isolatedContextId != null) {
                            cdpArgs.put("executionContextId", isolatedContextId);
                        } else {
                            cdpArgs.put("objectId", globalThis.get("objectId"));
                        }

                        return executeCdpCmd("Runtime.callFunctionOn", cdpArgs, null)
                            .thenApply(result -> {
                                if (result.has("exceptionDetails")) {
                                    throw new RuntimeException(javaScriptErrorMessage(result.get("exceptionDetails")));
                                }
                                if (result.has("result")) {
                                    JsonNode resultNode = result.get("result");
                                    if (resultNode.has("value")) {
                                        return objectMapper.convertValue(resultNode.get("value"), Object.class);
                                    }
                                }
                                return null;
                            });
                    }).join();
                } catch (Exception e) {
                    if (!isStaleContext(e)) {
                        throw propagate(e);
                    }
                    // StaleJSRemoteObjReference - tentar novamente
                    double elapsed = (System.nanoTime() - startNanos) / 1_000_000_000.0;
                    if (elapsed > timeout) {
                        throw new RuntimeException(
                            "Não foi possível executar script devido a referência stale dentro de " +
                            timeout + "s, possivelmente devido a loop de reload", e);
                    }
                    globalThis.remove(cacheKey);
                    sleepBeforeRetry();
                }
            }
        });
    }

    /**
     * Avalia expressão JavaScript assíncrona com await.
     * Permite usar await diretamente no código.
     *
     * @param script expressão JavaScript (pode usar await)
     * @param timeout timeout em segundos
     * @param uniqueContext se deve usar contexto isolado
     * @param args argumentos
     * @return CompletableFuture com o resultado
     */
    public CompletableFuture<Object> evalAsync(String script, float timeout,
                                               boolean uniqueContext, Object... args) {
        return CompletableFuture.supplyAsync(() -> {
            long startNanos = System.nanoTime();

            while (true) {
                // fora do try: o catch precisa da mesma variável para evictar a chave correta
                // do cache (o campo herdado contextId é outro valor e invalidaria "default")
                String contextId = uniqueContext && isolatedContextId != null ?
                    isolatedContextId : null;
                try {
                    return getGlobalThis(contextId).thenCompose(globalThis -> {
                        // Wrapper para permitir await no código
                        String wrappedScript = args != null && args.length > 0
                            ? "(async function() { " + script + " }).apply(globalThis, " + argsJsonArray(args) + ")"
                            : "(async function() { " + script + " })()";

                        Map<String, Object> cdpArgs = new HashMap<>();
                        cdpArgs.put("expression", wrappedScript);
                        cdpArgs.put("awaitPromise", true);
                        cdpArgs.put("returnByValue", true);

                        if (contextId != null) {
                            cdpArgs.put("executionContextId", contextId);
                        }

                        return executeCdpCmd("Runtime.evaluate", cdpArgs, timeout)
                            .thenApply(result -> {
                                if (result.has("exceptionDetails")) {
                                    throw new RuntimeException(javaScriptErrorMessage(result.get("exceptionDetails")));
                                }

                                if (result.has("result")) {
                                    JsonNode resultNode = result.get("result");
                                    if (resultNode.has("value")) {
                                        return objectMapper.convertValue(resultNode.get("value"), Object.class);
                                    }
                                }
                                return null;
                            });
                    }).join();
                } catch (Exception e) {
                    if (!isStaleContext(e)) {
                        throw propagate(e);
                    }
                    String cacheKey = contextId != null ? contextId : "default";
                    double elapsed = (System.nanoTime() - startNanos) / 1_000_000_000.0;
                    if (elapsed > timeout) {
                        throw new RuntimeException(
                            "Não foi possível executar script dentro de " + timeout + "s", e);
                    }
                    globalThis.remove(cacheKey);
                    sleepBeforeRetry();
                }
            }
        });
    }

    /**
     * Obtém o GlobalThis object para execução de JavaScript.
     *
     * @param contextId ID do contexto (opcional)
     * @return CompletableFuture com o objeto GlobalThis
     */
    private CompletableFuture<Map<String, Object>> getGlobalThis(String contextId) {
        String key = contextId != null ? contextId : "default";

        if (globalThis.containsKey(key)) {
            @SuppressWarnings("unchecked")
            Map<String, Object> cached = (Map<String, Object>) globalThis.get(key);
            return CompletableFuture.completedFuture(cached);
        }

        Map<String, Object> args = new HashMap<>();
        args.put("expression", "globalThis");

        if (contextId != null) {
            args.put("executionContextId", contextId);
        }

        return executeCdpCmd("Runtime.evaluate", args, null)
            .thenApply(result -> {
                Map<String, Object> globalThisObj = new HashMap<>();
                if (result.has("result")) {
                    JsonNode resultNode = result.get("result");
                    globalThisObj.put("objectId", resultNode.get("objectId").asText());
                }
                globalThis.put(key, globalThisObj);
                return globalThisObj;
            });
    }

    private static String argsJsonArray(Object[] args) {
        Object[] values = args == null ? new Object[0] : args;
        rejectWebElements(values);
        try {
            // JSON.parse: em literal JavaScript uma chave como "__proto__" alteraria
            // o protótipo do objeto em vez de permanecer propriedade própria
            return "JSON.parse(" + objectMapper.writeValueAsString(
                objectMapper.writeValueAsString(values)) + ")";
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("Argumento de script não serializável em JSON", e);
        }
    }

    private static List<Map<String, Object>> argsAsCallArguments(Object[] args) {
        Object[] values = args == null ? new Object[0] : args;
        List<Map<String, Object>> callArguments = new ArrayList<>(values.length);
        for (Object value : values) {
            if (value instanceof WebElement element) {
                if (element.objId == null) {
                    throw new IllegalArgumentException(
                        "WebElement sem objectId resolvido; use WebElement.executeRawScript");
                }
                callArguments.add(Collections.singletonMap("objectId", element.objId));
                continue;
            }
            try {
                callArguments.add(Collections.singletonMap("value", objectMapper.valueToTree(value)));
            } catch (IllegalArgumentException e) {
                throw new IllegalArgumentException("Argumento de script não serializável em JSON", e);
            }
        }
        return callArguments;
    }

    private static void rejectWebElements(Object[] args) {
        for (Object arg : args) {
            if (arg instanceof WebElement) {
                throw new IllegalArgumentException(
                    "Argumento WebElement não suportado em executeScript da página; use WebElement.executeScript");
            }
        }
    }

    private boolean isStaleContext(Throwable error) {
        for (Throwable cause = error; cause != null; cause = cause.getCause()) {
            if (cause instanceof CDPException cdpException
                    && cdpException.getKind() == CDPException.Kind.STALE_CONTEXT) {
                return true;
            }
        }
        return false;
    }

    private RuntimeException propagate(Exception error) {
        Throwable cause = error;
        while ((cause instanceof java.util.concurrent.CompletionException
                || cause instanceof java.util.concurrent.ExecutionException) && cause.getCause() != null) {
            cause = cause.getCause();
        }
        return cause instanceof RuntimeException runtimeException
            ? runtimeException : new RuntimeException(cause);
    }

    private void sleepBeforeRetry() {
        try {
            Thread.sleep(50);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException(e);
        }
    }

    /**
     * Obtém o alerta JavaScript atual (alert, confirm, prompt).
     *
     * @param timeout timeout em segundos
     * @return CompletableFuture com o Alert
     */
    public CompletableFuture<Alert> getAlert(float timeout) {
        if (pageEnabled == null || !pageEnabled) {
            return executeCdpCmd("Page.enable", null, timeout)
                .thenCompose(v -> {
                    pageEnabled = true;
                    return CompletableFuture.completedFuture(new Alert((Target) this, timeout));
                });
        }
        return CompletableFuture.completedFuture(new Alert((Target) this, timeout));
    }

    // Lista fechada: nome de classe customizada (class X extends Error) pode carregar dado da página.
    private static final Set<String> JS_ERROR_TYPES = Set.of(
            "Error", "EvalError", "RangeError", "ReferenceError", "SyntaxError", "TypeError",
            "URIError", "AggregateError", "DOMException");

    /**
     * Monta a mensagem de erro de Runtime.evaluate sem texto livre: apenas o tipo (lista fechada
     * de erros nativos do JS) e linha/coluna. Nunca inclui description, value, preview, stackTrace nem JSON bruto.
     */
    static String javaScriptErrorMessage(JsonNode exceptionDetails) {
        JsonNode exception = exceptionDetails == null ? null : exceptionDetails.get("exception");
        String type = validIdentifier(textOrNull(exception, "className"));
        if (type == null) {
            type = typeBeforeColon(textOrNull(exception, "description"));
        }
        if (type == null) {
            type = typeBeforeColon(textOrNull(exceptionDetails, "text"));
        }
        StringBuilder message = new StringBuilder("JavaScript error: ")
                .append(type == null ? "exception" : type);
        if (exceptionDetails != null && exceptionDetails.path("lineNumber").isNumber()) {
            message.append(" (line ").append(exceptionDetails.get("lineNumber").asInt());
            if (exceptionDetails.path("columnNumber").isNumber()) {
                message.append(", column ").append(exceptionDetails.get("columnNumber").asInt());
            }
            message.append(')');
        }
        return message.toString();
    }

    private static String typeBeforeColon(String text) {
        if (text == null) return null;
        String trimmed = text.strip();
        int newline = trimmed.indexOf('\n');
        String firstLine = newline >= 0 ? trimmed.substring(0, newline) : trimmed;
        int colon = firstLine.indexOf(':');
        return colon < 0 ? null : validIdentifier(firstLine.substring(0, colon).strip());
    }

    private static String validIdentifier(String candidate) {
        return candidate != null && JS_ERROR_TYPES.contains(candidate) ? candidate : null;
    }

    private static String textOrNull(JsonNode node, String field) {
        if (node == null) return null;
        JsonNode value = node.get(field);
        if (value == null || !value.isTextual() || value.asText().isBlank()) return null;
        return value.asText();
    }
}
