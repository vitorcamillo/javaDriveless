package io.github.selenium.javaDriverless.types.deserialize;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import io.github.selenium.javaDriverless.types.Target;
import io.github.selenium.javaDriverless.types.TypesExceptions;

import java.util.*;
import java.util.concurrent.CompletableFuture;

/**
 * Exceção lançada quando uma referência de objeto JS fica obsoleta.
 */
class StaleJSRemoteObjReferenceException extends RuntimeException {
    private final JSRemoteObj remoteObj;
    
    public StaleJSRemoteObjReferenceException(JSRemoteObj obj, String message) {
        super(message != null ? message : 
            String.format("Página ou Frame foi recarregado, ou o objeto deletado, %s", obj));
        this.remoteObj = obj;
    }
    
    public JSRemoteObj getRemoteObj() {
        return remoteObj;
    }
}

/**
 * Representa um objeto JavaScript remoto via CDP.
 * <p>
 * Classe base para todos os objetos JavaScript que são mantidos remotamente
 * no navegador e acessados via Runtime.callFunctionOn.
 * </p>
 */
public class JSRemoteObj {
    
    protected static final ObjectMapper objectMapper = new ObjectMapper();
    
    protected final String objId;
    protected final Target target;
    protected final Integer frameId;
    protected final Integer isolatedExecId;
    
    /**
     * Cria um novo objeto remoto JavaScript.
     *
     * @param objId ID do objeto remoto
     * @param target target que contém o objeto
     * @param frameId ID do frame
     * @param isolatedExecId ID do contexto isolado
     */
    public JSRemoteObj(String objId, Target target, Integer frameId, Integer isolatedExecId) {
        this.objId = objId;
        this.target = target;
        this.frameId = frameId;
        this.isolatedExecId = isolatedExecId;
    }
    
    /**
     * Retorna o ID do objeto.
     *
     * @return ID do objeto remoto
     */
    public String getObjId() {
        return objId;
    }
    
    /**
     * Retorna o target.
     *
     * @return target
     */
    public Target getTarget() {
        return target;
    }
    
    /**
     * Retorna o ID do contexto de execução baseado no objId.
     *
     * @return ID do contexto
     */
    public Integer getContextId() {
        if (objId != null && objId.contains(".")) {
            return Integer.parseInt(objId.split("\\.")[1]);
        }
        return null;
    }
    
    /**
     * Retorna o ID do frame.
     *
     * @return CompletableFuture com o ID do frame
     */
    public CompletableFuture<Integer> getFrameId() {
        return CompletableFuture.completedFuture(frameId);
    }
    
    /**
     * Retorna o ID do contexto isolado, criando um se necessário.
     *
     * @return CompletableFuture com o ID do contexto isolado
     */
    public CompletableFuture<Integer> getIsolatedExecId() {
        if (isolatedExecId != null) {
            return CompletableFuture.completedFuture(isolatedExecId);
        }
        
        return getFrameId().thenCompose(fId -> {
            Map<String, Object> args = new HashMap<>();
            args.put("frameId", fId);
            args.put("grantUniveralAccess", true);  // Sim, o typo está no Chrome mesmo!
            args.put("worldName", "Isolated execution context with DOM-access, You got here hehe:)");
            
            return target.executeCdpCmd("Page.createIsolatedWorld", args, null)
                .thenApply(result -> result.get("executionContextId").asInt());
        });
    }
    
    /**
     * Executa JavaScript bruto no objeto.
     *
     * @param script código JavaScript como função
     * @param args argumentos para a função
     * @param awaitRes se deve aguardar o resultado (promises)
     * @param serialization tipo de serialização ("deep", "json", "idOnly")
     * @param maxDepth profundidade máxima de serialização
     * @param timeout timeout em segundos
     * @param executionContextId ID do contexto de execução específico
     * @param uniqueContext se deve usar contexto isolado
     * @return CompletableFuture com o resultado
     */
    public CompletableFuture<Object> execRaw(String script, Object[] args, boolean awaitRes,
                                            String serialization, Integer maxDepth, float timeout,
                                            Integer executionContextId, boolean uniqueContext) {
        CompletableFuture<Integer> execContextFuture;
        CompletableFuture<String> baseObjIdFuture = CompletableFuture.completedFuture(objId);
        
        if (executionContextId != null && uniqueContext) {
            System.err.println("AVISO: executionContextId e uniqueContext=true, usando executionContextId");
        }
        
        if (executionContextId != null) {
            execContextFuture = CompletableFuture.completedFuture(executionContextId);
            baseObjIdFuture = CompletableFuture.completedFuture(null);
        } else if (uniqueContext) {
            execContextFuture = getIsolatedExecId();
            baseObjIdFuture = CompletableFuture.completedFuture(null);
        } else {
            execContextFuture = CompletableFuture.completedFuture(getContextId());
        }
        
        return execContextFuture.thenCombine(baseObjIdFuture, (execCtx, baseObjId) -> {
            List<Map<String, Object>> cdpArgs = new ArrayList<>();
            
            if (args != null) {
                for (Object arg : args) {
                    Map<String, Object> argMap = new HashMap<>();
                    
                    if (arg instanceof JSRemoteObj) {
                        JSRemoteObj jsObj = (JSRemoteObj) arg;
                        String remoteObjId = jsObj.getObjId();
                        
                        if (remoteObjId != null) {
                            argMap.put("objectId", remoteObjId);
                        } else {
                            argMap.put("value", arg);
                        }
                    } else {
                        argMap.put("value", arg);
                    }
                    
                    cdpArgs.add(argMap);
                }
            }
            
            Map<String, Object> serOpts = new HashMap<>();
            serOpts.put("serialization", serialization != null ? serialization : "deep");
            serOpts.put("maxDepth", maxDepth != null ? maxDepth : 2);
            
            Map<String, Object> additionalParams = new HashMap<>();
            additionalParams.put("includeShadowTree", "all");
            additionalParams.put("maxNodeDepth", maxDepth != null ? maxDepth : 2);
            serOpts.put("additionalParameters", additionalParams);
            
            Map<String, Object> callArgs = new HashMap<>();
            callArgs.put("functionDeclaration", script);
            callArgs.put("arguments", cdpArgs);
            callArgs.put("userGesture", true);
            callArgs.put("awaitPromise", awaitRes);
            callArgs.put("serializationOptions", serOpts);
            callArgs.put("generatePreview", true);
            
            if (baseObjId != null) {
                callArgs.put("objectId", baseObjId);
            } else {
                callArgs.put("executionContextId", execCtx);
            }
            
            return callArgs;
        }).thenCompose(callArgs -> 
            target.executeCdpCmd("Runtime.callFunctionOn", callArgs, timeout)
        ).thenCompose(result -> {
            if (result.has("exceptionDetails")) {
                throw new TypesExceptions.JSEvalException(result.get("exceptionDetails"));
            }
            
            JsonNode resultNode = result.get("result");
            return parseDeep(
                resultNode.get("deepSerializedValue"),
                resultNode.get("subtype"),
                resultNode.get("className"),
                resultNode.get("value"),
                resultNode.get("description"),
                target,
                resultNode.get("objectId") != null ? resultNode.get("objectId").asText() : null,
                execContextFuture.join(),
                isolatedExecId,
                frameId
            );
        });
    }
    
    /**
     * Executa JavaScript no objeto (síncrono).
     *
     * @param script código JavaScript (ex: "return obj.click()")
     * @param args argumentos
     * @param maxDepth profundidade de serialização
     * @param serialization tipo de serialização
     * @param timeout timeout em segundos
     * @param uniqueContext usar contexto isolado
     * @return CompletableFuture com o resultado
     */
    public CompletableFuture<Object> exec(String script, Object[] args, int maxDepth,
                                         String serialization, float timeout, boolean uniqueContext) {
        return getContextId() != null && objId != null ?
            execWithContext(script, args, maxDepth, serialization, timeout, uniqueContext) :
            execWithoutContext(script, args, maxDepth, serialization, timeout, uniqueContext);
    }
    
    private CompletableFuture<Object> execWithContext(String script, Object[] args, int maxDepth,
                                                     String serialization, float timeout, boolean uniqueContext) {
        String wrappedScript = "(function(...arguments){ const obj = arguments.shift(); " + script + "})";
        Object[] allArgs = new Object[args.length + 1];
        allArgs[0] = this;
        System.arraycopy(args, 0, allArgs, 1, args.length);
        
        return execRaw(wrappedScript, allArgs, false, serialization, maxDepth, timeout, null, uniqueContext);
    }
    
    private CompletableFuture<Object> execWithoutContext(String script, Object[] args, int maxDepth,
                                                        String serialization, float timeout, boolean uniqueContext) {
        String wrappedScript = "(function(...arguments){ const obj = this; " + script + "})";
        return execRaw(wrappedScript, args, false, serialization, maxDepth, timeout, null, uniqueContext);
    }
    
    /**
     * Parseia resultado "deep serialized" do CDP para objetos Java.
     *
     * @return CompletableFuture com o objeto parseado
     */
    private static CompletableFuture<Object> parseDeep(JsonNode deep, JsonNode subtype, 
                                                      JsonNode className, JsonNode value,
                                                      JsonNode description, Target target,
                                                      String objId, Integer contextId,
                                                      Integer isolatedExecId, Integer frameId) {
        if (deep == null || deep.isNull()) {
            if (value != null && !value.isNull()) {
                return CompletableFuture.completedFuture(convertValue(value));
            }
            return CompletableFuture.completedFuture(null);
        }
        
        String type = deep.get("type").asText();
        JsonNode val = deep.get("value");
        
        // Tipos simples
        if ("number".equals(type) || "string".equals(type) || "boolean".equals(type)) {
            return CompletableFuture.completedFuture(convertValue(val));
        }
        
        if ("undefined".equals(type) || "null".equals(type)) {
            return CompletableFuture.completedFuture(null);
        }
        
        // Arrays
        if ("array".equals(type)) {
            if (val == null || val.isNull()) {
                return CompletableFuture.completedFuture(new ArrayList<>());
            }
            
            List<CompletableFuture<Object>> futures = new ArrayList<>();
            for (JsonNode element : val) {
                futures.add(parseDeep(element, null, null, null, null, 
                    target, null, contextId, isolatedExecId, frameId));
            }
            
            return CompletableFuture.allOf(futures.toArray(new CompletableFuture[0]))
                .thenApply(v -> {
                    List<Object> result = new ArrayList<>();
                    for (CompletableFuture<Object> f : futures) {
                        result.add(f.join());
                    }
                    return result;
                });
        }
        
        // Objetos
        if ("object".equals(type)) {
            if (val == null || val.isNull()) {
                return CompletableFuture.completedFuture(new HashMap<>());
            }
            
            Map<String, Object> result = new HashMap<>();
            List<CompletableFuture<Void>> futures = new ArrayList<>();
            
            Iterator<Map.Entry<String, JsonNode>> fields = val.fields();
            while (fields.hasNext()) {
                Map.Entry<String, JsonNode> entry = fields.next();
                String key = entry.getKey();
                futures.add(
                    parseDeep(entry.getValue(), null, null, null, null,
                        target, null, contextId, isolatedExecId, frameId)
                    .thenAccept(parsedValue -> result.put(key, parsedValue))
                );
            }
            
            return CompletableFuture.allOf(futures.toArray(new CompletableFuture[0]))
                .thenApply(v -> result);
        }
        
        // Node (WebElement)
        if ("node".equals(type)) {
            Integer backendNodeId = null;
            if (val != null && val.has("backendNodeId")) {
                backendNodeId = val.get("backendNodeId").asInt();
            }
            
            String classNameStr = className != null ? className.asText() : null;
            
            // Retornar WebElement será implementado na classe WebElement
            return CompletableFuture.completedFuture(
                Map.of(
                    "type", "node",
                    "backendNodeId", backendNodeId,
                    "objId", objId,
                    "className", classNameStr,
                    "contextId", contextId
                )
            );
        }
        
        // Outros tipos não serializáveis
        return CompletableFuture.completedFuture(
            String.format("JSUnserializable(type=%s, value=%s)", type, val)
        );
    }
    
    /**
     * Converte JsonNode para valor Java primitivo.
     */
    private static Object convertValue(JsonNode node) {
        if (node == null || node.isNull()) return null;
        if (node.isBoolean()) return node.asBoolean();
        if (node.isInt()) return node.asInt();
        if (node.isLong()) return node.asLong();
        if (node.isDouble()) return node.asDouble();
        if (node.isTextual()) return node.asText();
        if (node.isArray() || node.isObject()) return node;
        return node.toString();
    }
    
    @Override
    public String toString() {
        return String.format("%s(obj_id=%s, context_id=%d)",
            getClass().getSimpleName(), objId, getContextId());
    }
    
    @Override
    public boolean equals(Object obj) {
        if (this == obj) return true;
        if (!(obj instanceof JSRemoteObj)) return false;
        JSRemoteObj other = (JSRemoteObj) obj;
        if (objId != null && other.objId != null) {
            return objId.split("\\.")[0].equals(other.objId.split("\\.")[0]);
        }
        return false;
    }
    
    @Override
    public int hashCode() {
        return Objects.hash(objId, getClass());
    }
}

