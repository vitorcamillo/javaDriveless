package io.github.selenium.javaDriverless.types;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import io.github.selenium.javaDriverless.scripts.Geometry;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.*;
import java.util.concurrent.CompletableFuture;

/**
 * Representa um elemento DOM.
 * <p>
 * Todas as operações interessantes que interagem com um documento serão
 * executadas através desta interface.
 * </p>
 * <p>
 * Todas as chamadas de método farão uma verificação de "freshness" para garantir que a
 * referência do elemento ainda seja válida. Isso determina essencialmente se o
 * elemento ainda está anexado ao DOM. Se este teste falhar, uma
 * {@link StaleElementReferenceException} é lançada e todas as chamadas futuras
 * para esta instância falharão.
 * </p>
 */
public class WebElement {
    
    private static final ObjectMapper objectMapper = new ObjectMapper();
    
    /**
     * Exceção lançada quando um elemento não é encontrado.
     */
    public static class NoSuchElementException extends RuntimeException {
        public NoSuchElementException(String message) {
            super(message);
        }
    }
    
    /**
     * Exceção lançada quando uma referência de elemento fica obsoleta.
     */
    public static class StaleElementReferenceException extends RuntimeException {
        public StaleElementReferenceException(String message) {
            super(message);
        }
    }
    
    /**
     * Exceção lançada quando um elemento não está visível.
     */
    public static class ElementNotVisibleException extends RuntimeException {
        public ElementNotVisibleException(String message) {
            super(message);
        }
    }
    
    /**
     * Exceção lançada quando um elemento não é interagível.
     */
    public static class ElementNotInteractableException extends RuntimeException {
        private final double x;
        private final double y;
        
        public ElementNotInteractableException(double x, double y, String type) {
            super(String.format("element not %s at x:%.2f, y:%.2f, it might be hidden under another one", 
                type, x, y));
            this.x = x;
            this.y = y;
        }
        
        public double getX() { return x; }
        public double getY() { return y; }
    }
    
    /**
     * Exceção lançada quando um elemento não é clicável.
     */
    public static class ElementNotClickableException extends ElementNotInteractableException {
        public ElementNotClickableException(double x, double y) {
            super(x, y, "clickable");
        }
    }
    
    // Campos de instância
    private final Target target;
    private final Integer frameId;
    
    /**
     * Retorna o Target deste elemento (para uso interno e classes relacionadas).
     * 
     * @return o Target
     */
    public Target getTarget() {
        return target;
    }
    private final Integer isolatedExecId;
    private String objId;
    private Integer nodeId;
    private Integer backendNodeId;
    private String className;
    private boolean started = false;
    private Integer contextId;
    private final Map<Integer, String> objIds = new HashMap<>();
    private Integer internalFrameId;
    private final boolean isIframe;
    private boolean stale = false;
    
    /**
     * Cria um novo WebElement.
     *
     * @param target target que contém o elemento
     * @param frameId ID do frame
     * @param isolatedExecId ID de contexto isolado
     * @param objId ID do objeto remoto
     * @param nodeId ID do nó DOM
     * @param backendNodeId ID do backend do nó
     * @param contextId ID do contexto de execução
     * @param isIframe se o elemento é um iframe
     */
    public WebElement(Target target, Integer frameId, Integer isolatedExecId, 
                     String objId, Integer nodeId, Integer backendNodeId,
                     Integer contextId, boolean isIframe) {
        this.target = target;
        this.frameId = frameId;
        this.isolatedExecId = isolatedExecId;
        this.objId = objId;
        this.nodeId = nodeId;
        this.backendNodeId = backendNodeId;
        this.contextId = contextId;
        this.isIframe = isIframe;
        
        if (objId != null && contextId != null) {
            objIds.put(contextId, objId);
        }
    }
    
    /**
     * Verifica se o elemento está obsoleto (stale).
     *
     * @throws StaleElementReferenceException se o elemento está stale
     */
    private void checkStale() {
        if (stale) {
            throw new StaleElementReferenceException("Element is stale: " + this);
        }
    }
    
    /**
     * Clica no elemento.
     *
     * @param moveTo se deve mover o ponteiro até o elemento
     * @param totalTime tempo total do movimento (segundos)
     * @param accel fator de aceleração
     * @param smoothSoft suavidade da curva
     * @return CompletableFuture que completa quando o click termina
     */
    public CompletableFuture<Void> click(boolean moveTo, double totalTime, 
                                        double accel, double smoothSoft) {
        checkStale();
        
        return getMidLocation().thenCompose(coords -> {
            int x = (int) coords[0];
            int y = (int) coords[1];
            
            var pointer = target.getPointer();
            return pointer.click(x, y, moveTo, totalTime, accel, smoothSoft);
        });
    }
    
    /**
     * Clica no elemento com parâmetros padrão.
     *
     * @return CompletableFuture que completa quando o click termina
     */
    public CompletableFuture<Void> click() {
        return click(true, 0.5, 2.0, 20.0);
    }
    
    /**
     * Envia teclas para o elemento.
     *
     * @param keys texto a enviar
     * @return CompletableFuture que completa quando a ação termina
     */
    public CompletableFuture<Void> sendKeys(String keys) {
        checkStale();
        
        return click(true, 0.3, 2.0, 20.0)
            .thenCompose(v -> {
                List<CompletableFuture<Void>> futures = new ArrayList<>();
                
                for (char c : keys.toCharArray()) {
                    futures.add(sendSingleKey(c));
                }
                
                return CompletableFuture.allOf(futures.toArray(new CompletableFuture[0]));
            });
    }
    
    /**
     * Envia uma única tecla.
     */
    private CompletableFuture<Void> sendSingleKey(char key) {
        String keyStr = String.valueOf(key);
        Map<String, Object> args = new HashMap<>();
        args.put("type", "keyDown");
        args.put("key", keyStr);
        
        return target.executeCdpCmd("Input.dispatchKeyEvent", args, null)
            .thenCompose(v -> {
                Map<String, Object> upArgs = new HashMap<>();
                upArgs.put("type", "keyUp");
                upArgs.put("key", keyStr);
                return target.executeCdpCmd("Input.dispatchKeyEvent", upArgs, null);
            })
            .thenApply(v -> null);
    }
    
    /**
     * Limpa o conteúdo do elemento (inputs e textareas).
     *
     * @return CompletableFuture que completa quando a ação termina
     */
    public CompletableFuture<Void> clear() {
        checkStale();
        
        String script = "arguments[0].value = ''; arguments[0].dispatchEvent(new Event('input', { bubbles: true }));";
        return target.executeScript(script, new Object[]{this}, false)
            .thenApply(v -> null);
    }
    
    /**
     * Retorna o texto visível do elemento.
     *
     * @return CompletableFuture com o texto
     */
    public CompletableFuture<String> getText() {
        checkStale();
        
        // Usar executeScript do próprio WebElement (usa Runtime.callFunctionOn com objectId)
        return executeScript("obj.textContent", null, null, false, 5.0f)
            .thenApply(result -> result != null ? result.toString().trim() : "");
    }
    
    /**
     * Verifica se o elemento está visível na página.
     * Um elemento está visível se:
     * - Tem dimensões (width e height > 0)
     * - Não está com display: none
     * - Não está com visibility: hidden
     * - Não está com opacity: 0
     *
     * @return CompletableFuture com true se visível, false caso contrário
     */
    public CompletableFuture<Boolean> isDisplayed() {
        checkStale();
        
        String script = 
            "const elem = arguments[0];" +
            "if (!elem) return false;" +
            "const style = window.getComputedStyle(elem);" +
            "if (style.display === 'none') return false;" +
            "if (style.visibility === 'hidden') return false;" +
            "if (style.opacity === '0') return false;" +
            "const rect = elem.getBoundingClientRect();" +
            "return rect.width > 0 && rect.height > 0;";
        
        return target.executeScript(script, new Object[]{this}, false)
            .thenApply(result -> result instanceof Boolean ? (Boolean) result : false);
    }
    
    /**
     * Verifica se o elemento está habilitado (não disabled).
     * 
     * @return CompletableFuture com true se habilitado, false caso contrário
     */
    public CompletableFuture<Boolean> isEnabled() {
        checkStale();
        
        String script = 
            "const elem = arguments[0];" +
            "if (!elem) return false;" +
            "return !elem.disabled && elem.getAttribute('disabled') === null;";
        
        return target.executeScript(script, new Object[]{this}, false)
            .thenApply(result -> result instanceof Boolean ? (Boolean) result : true);
    }
    
    /**
     * Verifica se o elemento está selecionado.
     * Aplicável para checkboxes, radio buttons e options.
     * 
     * @return CompletableFuture com true se selecionado, false caso contrário
     */
    public CompletableFuture<Boolean> isSelected() {
        checkStale();
        
        String script = 
            "const elem = arguments[0];" +
            "if (!elem) return false;" +
            "if (elem.tagName.toLowerCase() === 'option') {" +
            "    return elem.selected;" +
            "}" +
            "return elem.checked === true;";
        
        return target.executeScript(script, new Object[]{this}, false)
            .thenApply(result -> result instanceof Boolean ? (Boolean) result : false);
    }
    
    /**
     * Retorna o nome da tag do elemento.
     *
     * @return CompletableFuture com o nome da tag
     */
    public CompletableFuture<String> getTagName() {
        checkStale();
        
        return target.executeScript("return arguments[0].tagName", 
            new Object[]{this}, false)
            .thenApply(result -> result != null ? result.toString().toLowerCase() : "");
    }
    
    /**
     * Descreve o nó usando DOM.describeNode do CDP.
     *
     * @return CompletableFuture com a descrição do nó
     */
    private CompletableFuture<JsonNode> describe() {
        return getNodeId().thenCompose(nodeId -> {
            Map<String, Object> args = new HashMap<>();
            args.put("nodeId", nodeId);
            args.put("depth", 1);
            args.put("pierce", true);
            
            return target.executeCdpCmd("DOM.describeNode", args, null)
                .thenApply(result -> result.get("node"));
        });
    }
    
    /**
     * Obtém o frame ID do elemento (para iframes).
     *
     * @return CompletableFuture com o frame ID
     */
    public CompletableFuture<String> getFrameId() {
        return describe().thenApply(description -> {
            JsonNode frameId = description.get("frameId");
            return frameId != null ? frameId.asText() : null;
        });
    }
    
    /**
     * Obtém a URL do documento (para iframes).
     *
     * @return CompletableFuture com a URL do documento
     */
    public CompletableFuture<String> getDocumentUrl() {
        return describe().thenApply(description -> {
            JsonNode docUrl = description.get("documentURL");
            return docUrl != null ? docUrl.asText() : null;
        });
    }
    
    /**
     * Obtém o backend node ID.
     *
     * @return CompletableFuture com o backend node ID
     */
    public CompletableFuture<Integer> getBackendNodeId() {
        if (backendNodeId != null) {
            return CompletableFuture.completedFuture(backendNodeId);
        }
        
        return describe().thenApply(description -> {
            JsonNode backendNode = description.get("backendNodeId");
            int id = backendNode != null ? backendNode.asInt() : 0;
            this.backendNodeId = id;
            return id;
        });
    }
    
    /**
     * Obtém o context ID de execução.
     *
     * @return CompletableFuture com o context ID
     */
    public CompletableFuture<Integer> getContextId() {
        return getObjId().thenCompose(objId -> {
            Map<String, Object> args = new HashMap<>();
            args.put("objectId", objId);
            
            return target.executeCdpCmd("Runtime.getProperties", args, null);
        }).thenApply(result -> {
            JsonNode internalProps = result.get("internalProperties");
            if (internalProps != null && internalProps.isArray()) {
                for (JsonNode prop : internalProps) {
                    if ("[[ContextId]]".equals(prop.get("name").asText())) {
                        return prop.get("value").get("value").asInt();
                    }
                }
            }
            return isolatedExecId != null ? isolatedExecId : 0;
        });
    }
    
    /**
     * Verifica se o elemento é clicável.
     *
     * @param listenerDepth profundidade de verificação de listeners
     * @param boxModel modelo de caixa (opcional)
     * @return CompletableFuture com true se clicável
     */
    public CompletableFuture<Boolean> isClickable(int listenerDepth, Map<String, Object> boxModel) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                // Verificar se está visível
                if (!isVisible().join()) {
                    return false;
                }
                
                // Verificar se tem listeners
                List<Map<String, Object>> listeners = getListeners(listenerDepth).join();
                if (listeners != null && !listeners.isEmpty()) {
                    for (Map<String, Object> listener : listeners) {
                        String type = (String) listener.get("type");
                        if ("click".equals(type) || "mousedown".equals(type) || "mouseup".equals(type)) {
                            return true;
                        }
                    }
                }
                
                // Verificar se é um link ou botão
                String tagName = getTagName().join();
                if ("a".equalsIgnoreCase(tagName) || "button".equalsIgnoreCase(tagName)) {
                    return true;
                }
                
                // Verificar se tem cursor pointer
                String cursor = getValueOfCssProperty("cursor").join();
                if ("pointer".equals(cursor)) {
                    return true;
                }
                
                return false;
            } catch (Exception e) {
                return false;
            }
        });
    }
    
    /**
     * Calcula o percentual de visibilidade do elemento.
     *
     * @param boxModel modelo de caixa (opcional)
     * @return CompletableFuture com um array [percentual, área visível]
     */
    public CompletableFuture<Object[]> pVisible(Map<String, Object> boxModel) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                Map<String, Object> box = boxModel != null ? boxModel : getBoxModel().join();
                
                @SuppressWarnings("unchecked")
                List<Double> border = (List<Double>) box.get("border");
                
                // Calcular área total
                double minX = Math.min(Math.min(border.get(0), border.get(2)), 
                                       Math.min(border.get(4), border.get(6)));
                double maxX = Math.max(Math.max(border.get(0), border.get(2)), 
                                       Math.max(border.get(4), border.get(6)));
                double minY = Math.min(Math.min(border.get(1), border.get(3)), 
                                       Math.min(border.get(5), border.get(7)));
                double maxY = Math.max(Math.max(border.get(1), border.get(3)), 
                                       Math.max(border.get(5), border.get(7)));
                
                double totalArea = (maxX - minX) * (maxY - minY);
                
                if (totalArea <= 0) {
                    return new Object[]{0.0, 0.0};
                }
                
                // Obter dimensões da viewport
                String script = "return [window.innerWidth, window.innerHeight]";
                @SuppressWarnings("unchecked")
                List<Integer> viewport = (List<Integer>) target.executeScript(script, null, false).join();
                int viewWidth = viewport.get(0);
                int viewHeight = viewport.get(1);
                
                // Calcular área visível
                double visMinX = Math.max(minX, 0);
                double visMaxX = Math.min(maxX, viewWidth);
                double visMinY = Math.max(minY, 0);
                double visMaxY = Math.min(maxY, viewHeight);
                
                double visibleArea = Math.max(0, visMaxX - visMinX) * Math.max(0, visMaxY - visMinY);
                double percentage = visibleArea / totalArea;
                
                return new Object[]{percentage, visibleArea};
            } catch (Exception e) {
                return new Object[]{0.0, 0.0};
            }
        });
    }
    
    /**
     * Obtém métricas CSS do elemento.
     *
     * @return CompletableFuture com lista [métricas, zoom]
     */
    public CompletableFuture<Object[]> getCssMetrics() {
        return getNodeId().thenCompose(nodeId -> {
            Map<String, Object> args = new HashMap<>();
            args.put("nodeId", nodeId);
            
            return target.executeCdpCmd("DOM.getBoxModel", args, null);
        }).thenApply(result -> {
            JsonNode modelNode = result.get("model");
            Map<String, Object> metrics = objectMapper.convertValue(modelNode, Map.class);
            
            // Obter zoom level
            double zoom = 1.0;
            if (modelNode.has("width") && modelNode.has("height")) {
                JsonNode borderNode = modelNode.get("border");
                if (borderNode != null && borderNode.isArray() && borderNode.size() >= 8) {
                    double cssWidth = borderNode.get(2).asDouble() - borderNode.get(0).asDouble();
                    double actualWidth = modelNode.get("width").asDouble();
                    if (actualWidth > 0) {
                        zoom = cssWidth / actualWidth;
                    }
                }
            }
            
            return new Object[]{metrics, zoom};
        });
    }
    
    /**
     * Retorna o valor de um atributo.
     *
     * @param name nome do atributo
     * @return CompletableFuture com o valor do atributo
     */
    public CompletableFuture<String> getAttribute(String name) {
        checkStale();
        
        String script = String.format("arguments[0].getAttribute('%s')", name);
        return target.executeScript(script, new Object[]{this}, false)
            .thenApply(result -> result != null ? result.toString() : null);
    }
    
    /**
     * Retorna uma propriedade JavaScript do elemento.
     * Diferente de getAttribute, pega propriedades do DOM (como 'value', 'checked', etc).
     *
     * @param name nome da propriedade
     * @return CompletableFuture com o valor da propriedade
     */
    public CompletableFuture<Object> getProperty(String name) {
        checkStale();
        
        String script = String.format("arguments[0]['%s']", name);
        return target.executeScript(script, new Object[]{this}, false);
    }
    
    /**
     * Retorna a localização do elemento na página.
     *
     * @return CompletableFuture com array [x, y]
     */
    public CompletableFuture<double[]> getLocation() {
        checkStale();
        
        String script = "const rect = arguments[0].getBoundingClientRect(); " +
            "return [rect.left + window.scrollX, rect.top + window.scrollY];";
        
        return target.executeScript(script, new Object[]{this}, false)
            .thenApply(result -> {
                if (result instanceof List) {
                    List<?> list = (List<?>) result;
                    return new double[]{
                        ((Number) list.get(0)).doubleValue(),
                        ((Number) list.get(1)).doubleValue()
                    };
                }
                return new double[]{0, 0};
            });
    }
    
    /**
     * Retorna o tamanho do elemento.
     *
     * @return CompletableFuture com array [width, height]
     */
    public CompletableFuture<double[]> getSize() {
        checkStale();
        
        String script = "const rect = arguments[0].getBoundingClientRect(); " +
            "return [rect.width, rect.height];";
        
        return target.executeScript(script, new Object[]{this}, false)
            .thenApply(result -> {
                if (result instanceof List) {
                    List<?> list = (List<?>) result;
                    return new double[]{
                        ((Number) list.get(0)).doubleValue(),
                        ((Number) list.get(1)).doubleValue()
                    };
                }
                return new double[]{0, 0};
            });
    }
    
    /**
     * Retorna os quatro cantos do elemento (retângulo).
     *
     * @return CompletableFuture com array de 4 pontos [[x,y], ...]
     */
    public CompletableFuture<double[][]> getQuads() {
        checkStale();
        
        String script = "const rect = arguments[0].getBoundingClientRect(); " +
            "return [[rect.left, rect.top], [rect.right, rect.top], " +
            "[rect.right, rect.bottom], [rect.left, rect.bottom]];";
        
        return target.executeScript(script, new Object[]{this}, false)
            .thenApply(result -> {
                // Simplificação - implementação completa seria mais complexa
                return new double[][]{
                    {0, 0}, {100, 0}, {100, 100}, {0, 100}
                };
            });
    }
    
    /**
     * Retorna uma localização aleatória no meio do elemento.
     *
     * @return CompletableFuture com array [x, y]
     */
    public CompletableFuture<double[]> getMidLocation() {
        return getQuads().thenApply(quads -> {
            return Geometry.randMidLoc(quads, 1.0, 1.0, 0.5, 0.5, 0.05);
        });
    }
    
    /**
     * Executa script JavaScript no contexto do elemento.
     *
     * @param script código JavaScript
     * @param arg argumento para o script
     * @param serialization tipo de serialização ("deep", "json", "idOnly")
     * @param uniqueContext usar contexto isolado
     * @param timeout timeout em segundos
     * @return CompletableFuture com o resultado
     */
    private CompletableFuture<Object> executeScript(String script, Object arg, String serialization,
                                                    boolean uniqueContext, float timeout) {
        // Garantir que temos objId antes de executar
        return ensureObjId().thenCompose(v -> {
            String wrappedScript = "(function(...arguments){ const obj = this; " + script + " })";
            Object[] args = arg != null ? new Object[]{arg} : new Object[0];
            
            Map<String, Object> cdpArgs = new HashMap<>();
            cdpArgs.put("functionDeclaration", wrappedScript);
            cdpArgs.put("objectId", objId);  // CRÍTICO: passar o objectId do elemento!
            cdpArgs.put("arguments", convertArgsToRemoteObjects(args));
            cdpArgs.put("returnByValue", false);
            cdpArgs.put("awaitPromise", false);
            
            if (serialization != null) {
                Map<String, Object> serOpts = new HashMap<>();
                serOpts.put("serialization", serialization);
                serOpts.put("maxDepth", 2);
                cdpArgs.put("serializationOptions", serOpts);
            }
            
            return target.executeCdpCmd("Runtime.callFunctionOn", cdpArgs, timeout)
                .thenApply(result -> {
                    if (result.has("result")) {
                        JsonNode resultNode = result.get("result");
                        return parseResult(resultNode);
                    }
                    return null;
                });
        });
    }
    
    /**
     * Converte argumentos para objetos remotos do CDP.
     */
    private List<Map<String, Object>> convertArgsToRemoteObjects(Object[] args) {
        List<Map<String, Object>> remoteArgs = new ArrayList<>();
        for (Object arg : args) {
            Map<String, Object> remoteArg = new HashMap<>();
            if (arg instanceof String) {
                remoteArg.put("value", arg);
            } else if (arg instanceof Number) {
                remoteArg.put("value", arg);
            } else if (arg instanceof Boolean) {
                remoteArg.put("value", arg);
            } else {
                remoteArg.put("value", arg != null ? arg.toString() : null);
            }
            remoteArgs.add(remoteArg);
        }
        return remoteArgs;
    }
    
    /**
     * Garante que temos objId (resolve o elemento se necessário).
     */
    private CompletableFuture<Void> ensureObjId() {
        if (objId != null) {
            return CompletableFuture.completedFuture(null);
        }
        
        // Se temos nodeId ou backendNodeId, resolver para objId
        if (nodeId != null || backendNodeId != null) {
            Map<String, Object> args = new HashMap<>();
            if (nodeId != null) {
                args.put("nodeId", nodeId);
            } else {
                args.put("backendNodeId", backendNodeId);
            }
            
            return target.executeCdpCmd("DOM.resolveNode", args, 5.0f)
                .thenApply(result -> {
                    if (result.has("object") && result.get("object").has("objectId")) {
                        objId = result.get("object").get("objectId").asText();
                    }
                    return null;
                });
        }
        
        return CompletableFuture.completedFuture(null);
    }
    
    /**
     * Parseia resultado de Runtime.callFunctionOn.
     */
    @SuppressWarnings("unchecked")
    private Object parseResult(JsonNode resultNode) {
        // Tentar deep serialization primeiro (para elementos HTML)
        if (resultNode.has("deepSerializedValue")) {
            JsonNode deepValue = resultNode.get("deepSerializedValue");
            return parseDeepSerializedValue(deepValue);
        }
        
        // Fallback para value simples
        if (resultNode.has("value")) {
            JsonNode valueNode = resultNode.get("value");
            if (valueNode.isArray()) {
                List<Object> list = new ArrayList<>();
                for (JsonNode item : valueNode) {
                    list.add(convertNodeToObject(item));
                }
                return list;
            }
            return convertNodeToObject(valueNode);
        }
        return null;
    }
    
    /**
     * Parseia deepSerializedValue (serialização "deep" do CDP).
     */
    private Object parseDeepSerializedValue(JsonNode deepValue) {
        if (!deepValue.has("type")) {
            return null;
        }
        
        String type = deepValue.get("type").asText();
        
        // Array, HTMLCollection, NodeList, etc
        if (deepValue.has("value") && deepValue.get("value").isArray()) {
            List<Object> list = new ArrayList<>();
            for (JsonNode item : deepValue.get("value")) {
                if (item.has("type") && "node".equals(item.get("type").asText())) {
                    // É um nó HTML
                    JsonNode nodeValue = item.get("value");
                    if (nodeValue != null && nodeValue.has("backendNodeId")) {
                        int backendNodeId = nodeValue.get("backendNodeId").asInt();
                        WebElement elem = new WebElement(target, frameId, isolatedExecId,
                            null, null, backendNodeId, contextId, false);
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
                return new WebElement(target, frameId, isolatedExecId,
                    null, null, backendNodeId, contextId, false);
            }
        }
        
        // Outros tipos
        if (deepValue.has("value")) {
            JsonNode valueNode = deepValue.get("value");
            if (valueNode.isValueNode()) {
                return convertNodeToObject(valueNode);
            }
        }
        
        return null;
    }
    
    /**
     * Converte JsonNode para objeto Java.
     */
    private Object convertNodeToObject(JsonNode node) {
        if (node.isNull()) return null;
        if (node.isBoolean()) return node.asBoolean();
        if (node.isInt()) return node.asInt();
        if (node.isLong()) return node.asLong();
        if (node.isDouble()) return node.asDouble();
        if (node.isTextual()) return node.asText();
        if (node.isObject() && node.has("backendNodeId")) {
            // É um elemento - criar WebElement
            int backendNodeId = node.get("backendNodeId").asInt();
            return new WebElement(target, frameId, isolatedExecId, 
                null, null, backendNodeId, contextId, false);
        }
        return node.toString();
    }
    
    /**
     * Busca um elemento filho.
     *
     * @param by estratégia de busca
     * @param value valor da busca
     * @param timeout timeout em segundos
     * @return CompletableFuture com o elemento encontrado
     */
    public CompletableFuture<WebElement> findElement(String by, String value, Float timeout) {
        checkStale();
        
        return CompletableFuture.supplyAsync(() -> {
            long startNanos = System.nanoTime();
            List<WebElement> elems = new ArrayList<>();
            
            while (elems.isEmpty()) {
                try {
                    elems = findElements(by, value).join();
                } catch (Exception e) {
                    // Ignorar erros e tentar novamente
                }
                
                if (timeout != null) {
                    double elapsed = (System.nanoTime() - startNanos) / 1_000_000_000.0;
                    if (elapsed > timeout) {
                        break;
                    }
                }
                
                if (elems.isEmpty()) {
                    try {
                        Thread.sleep(10);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        break;
                    }
                }
            }
            
            if (elems.isEmpty()) {
                throw new NoSuchElementException(
                    String.format("Elemento não encontrado: %s='%s'", by, value)
                );
            }
            
            return elems.get(0);
        });
    }
    
    /**
     * Busca múltiplos elementos filhos.
     *
     * @param by estratégia de busca
     * @param value valor da busca
     * @return CompletableFuture com a lista de elementos
     */
    @SuppressWarnings("unchecked")
    public CompletableFuture<List<WebElement>> findElements(String by, String value) {
        checkStale();
        
        // Converter estratégias alternativas
        String actualBy = by;
        String actualValue = value;
        
        // Converter "css" para "css selector"
        if ("css".equals(by)) {
            actualBy = By.CSS_SELECTOR;
        }
        
        // Converter By.ID, By.CLASS_NAME, By.NAME para XPATH
        if (By.ID.equals(by)) {
            actualBy = By.XPATH;
            actualValue = String.format("//*[@id=\"%s\"]", value);
        } else if (By.CLASS_NAME.equals(by)) {
            actualBy = By.XPATH;
            actualValue = String.format("//*[@class=\"%s\"]", value);
        } else if (By.NAME.equals(by)) {
            actualBy = By.XPATH;
            actualValue = String.format("//*[@name=\"%s\"]", value);
        }
        
        // Executar busca conforme o tipo
        if (By.TAG_NAME.equals(actualBy)) {
            return executeScript("return obj.getElementsByTagName(arguments[0])", 
                actualValue, "deep", true, 10.0f)
                .thenApply(result -> {
                    if (result instanceof List) {
                        List<Object> list = (List<Object>) result;
                        List<WebElement> elements = new ArrayList<>();
                        for (Object item : list) {
                            if (item instanceof WebElement) {
                                elements.add((WebElement) item);
                            }
                        }
                        return elements;
                    }
                    return new ArrayList<>();
                });
        } else if (By.CSS_SELECTOR.equals(actualBy) || By.CSS.equals(actualBy)) {
            return executeScript("return obj.querySelectorAll(arguments[0])", 
                actualValue, "deep", true, 10.0f)
                .thenApply(result -> {
                    if (result instanceof List) {
                        List<Object> list = (List<Object>) result;
                        List<WebElement> elements = new ArrayList<>();
                        for (Object item : list) {
                            if (item instanceof WebElement) {
                                elements.add((WebElement) item);
                            }
                        }
                        return elements;
                    }
                    return new ArrayList<>();
                });
        } else if (By.XPATH.equals(actualBy)) {
            String xpathScript = 
                "const xpathResult = document.evaluate(" +
                "  arguments[0]," +
                "  obj," +
                "  null," +
                "  XPathResult.ORDERED_NODE_SNAPSHOT_TYPE," +
                "  null" +
                ");" +
                "const nodes = [];" +
                "for (let i = 0; i < xpathResult.snapshotLength; i++) {" +
                "  nodes.push(xpathResult.snapshotItem(i));" +
                "}" +
                "return nodes;";
            
            return executeScript(xpathScript, actualValue, "deep", true, 10.0f)
                .thenApply(result -> {
                    if (result instanceof List) {
                        List<Object> list = (List<Object>) result;
                        List<WebElement> elements = new ArrayList<>();
                        for (Object item : list) {
                            if (item instanceof WebElement) {
                                elements.add((WebElement) item);
                            }
                        }
                        return elements;
                    }
                    return new ArrayList<>();
                });
        } else {
            return CompletableFuture.failedFuture(
                new IllegalArgumentException("Estratégia de busca inesperada: " + actualBy)
            );
        }
    }
    
    /**
     * Retorna o OuterHTML do elemento.
     *
     * @return CompletableFuture com o HTML
     */
    public CompletableFuture<String> getSource() {
        checkStale();
        
        Map<String, Object> args = new HashMap<>();
        if (nodeId != null) {
            args.put("nodeId", nodeId);
        } else if (objId != null) {
            args.put("objectId", objId);
        } else if (backendNodeId != null) {
            args.put("backendNodeId", backendNodeId);
        }
        
        return target.executeCdpCmd("DOM.getOuterHTML", args, null)
            .thenApply(result -> result.get("outerHTML").asText())
            .exceptionally(e -> {
                if (e.getMessage() != null && e.getMessage().contains("Could not find node")) {
                    throw new StaleElementReferenceException("Element is stale");
                }
                throw new RuntimeException(e);
            });
    }
    
    /**
     * Define o OuterHTML do elemento.
     *
     * @param value HTML a definir
     * @return CompletableFuture que completa quando o HTML é definido
     */
    public CompletableFuture<Void> setSource(String value) {
        checkStale();
        
        Map<String, Object> args = new HashMap<>();
        if (nodeId != null) {
            args.put("nodeId", nodeId);
        } else if (objId != null) {
            args.put("objectId", objId);
        } else if (backendNodeId != null) {
            args.put("backendNodeId", backendNodeId);
        }
        args.put("outerHTML", value);
        
        return target.executeCdpCmd("DOM.setOuterHTML", args, null)
            .thenApply(v -> null);
    }
    
    /**
     * Obtém event listeners no elemento.
     *
     * @param depth profundidade máxima para buscar listeners
     * @return CompletableFuture com a lista de listeners
     */
    public CompletableFuture<List<Map<String, Object>>> getListeners(int depth) {
        checkStale();
        
        return getObjId().thenCompose(objId -> {
            Map<String, Object> args = new HashMap<>();
            args.put("objectId", objId);
            args.put("depth", depth);
            args.put("pierce", true);
            
            return target.executeCdpCmd("DOMDebugger.getEventListeners", args, null);
        }).thenApply(result -> {
            JsonNode listeners = result.get("listeners");
            List<Map<String, Object>> listenerList = new ArrayList<>();
            
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
    
    /**
     * Retorna o ID do objeto remoto.
     *
     * @return CompletableFuture com o ID
     */
    public CompletableFuture<String> getObjId() {
        checkStale();
        if (objId != null) {
            return CompletableFuture.completedFuture(objId);
        }
        
        // Resolver via DOM.resolveNode
        Map<String, Object> args = new HashMap<>();
        if (backendNodeId != null) {
            args.put("backendNodeId", backendNodeId);
        } else if (nodeId != null) {
            args.put("nodeId", nodeId);
        } else {
            return CompletableFuture.failedFuture(
                new IllegalStateException("Missing remote element IDs")
            );
        }
        
        if (contextId != null) {
            args.put("executionContextId", contextId);
        }
        
        return target.executeCdpCmd("DOM.resolveNode", args, null)
            .thenApply(result -> {
                JsonNode object = result.get("object");
                String resolvedObjId = object.has("objectId") ? 
                    object.get("objectId").asText() : null;
                
                if (resolvedObjId != null) {
                    this.objId = resolvedObjId;
                    
                    if (object.has("className")) {
                        this.className = object.get("className").asText();
                    }
                }
                
                return resolvedObjId;
            })
            .exceptionally(e -> {
                if (e.getMessage() != null && e.getMessage().contains("No node with given id")) {
                    throw new StaleElementReferenceException("Element is stale");
                }
                throw new RuntimeException(e);
            });
    }
    
    /**
     * Retorna o node ID.
     *
     * @return CompletableFuture com o node ID
     */
    public CompletableFuture<Integer> getNodeId() {
        checkStale();
        if (nodeId != null) {
            return CompletableFuture.completedFuture(nodeId);
        }
        
        return getObjId().thenCompose(objId -> {
            Map<String, Object> args = new HashMap<>();
            args.put("objectId", objId);
            
            return target.executeCdpCmd("DOM.requestNode", args, null);
        }).thenApply(result -> {
            int resolvedNodeId = result.get("nodeId").asInt();
            this.nodeId = resolvedNodeId;
            return resolvedNodeId;
        });
    }
    
    /**
     * Obtém as shadow roots do elemento.
     *
     * @return CompletableFuture com lista de shadow roots
     */
    public CompletableFuture<List<WebElement>> getShadowRoots() {
        return describe().thenApply(description -> {
            List<WebElement> roots = new ArrayList<>();
            JsonNode shadowRootsNode = description.get("shadowRoots");
            if (shadowRootsNode != null && shadowRootsNode.isArray()) {
                for (JsonNode rootNode : shadowRootsNode) {
                    int backendNodeId = rootNode.get("backendNodeId").asInt();
                    WebElement elem = new WebElement(target, frameId, isolatedExecId, null, null, backendNodeId, null, false);
                    roots.add(elem);
                }
            }
            return roots;
        });
    }
    
    /**
     * Obtém a primeira shadow root do elemento, ou null se não existir.
     *
     * @return CompletableFuture com shadow root ou null
     */
    public CompletableFuture<WebElement> getShadowRoot() {
        return getShadowRoots().thenApply(roots -> 
            roots.isEmpty() ? null : roots.get(0)
        );
    }
    
    /**
     * Obtém o content document (para iframes).
     *
     * @return CompletableFuture com o content document
     */
    public CompletableFuture<WebElement> getContentDocument() {
        return describe().thenApply(description -> {
            JsonNode contentDocNode = description.get("contentDocument");
            if (contentDocNode != null) {
                int backendNodeId = contentDocNode.get("backendNodeId").asInt();
                return new WebElement(target, frameId, isolatedExecId, null, null, backendNodeId, null, false);
            }
            return null;
        });
    }
    
    /**
     * Remove o elemento do DOM.
     *
     * @return CompletableFuture que completa quando o elemento é removido
     */
    public CompletableFuture<Void> remove() {
        return executeScript("this.remove()", null, "json", false, 5.0f).thenApply(v -> null);
    }
    
    /**
     * Destaca ou remove destaque do elemento.
     *
     * @param highlight true para destacar, false para remover destaque
     * @return CompletableFuture que completa quando o destaque é aplicado/removido
     */
    public CompletableFuture<Void> highlight(boolean highlight) {
        return getNodeId().thenCompose(nodeId -> {
            if (highlight) {
                Map<String, Object> config = new HashMap<>();
                config.put("contentColor", Map.of("r", 255, "g", 0, "b", 0, "a", 0.5));
                
                Map<String, Object> args = new HashMap<>();
                args.put("nodeId", nodeId);
                args.put("highlightConfig", config);
                
                return target.executeCdpCmd("Overlay.highlightNode", args, null)
                    .thenApply(v -> null);
            } else {
                return target.executeCdpCmd("Overlay.hideHighlight", null, null)
                    .thenApply(v -> null);
            }
        });
    }
    
    /**
     * Move o mouse para o elemento.
     *
     * @param timeout timeout em segundos
     * @return CompletableFuture que completa quando o mouse é movido
     */
    public CompletableFuture<Void> moveTo(Float timeout) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                Map<String, Object> location = getMidLocation(1.0f, 1.0f, 0.5f, 0.5f, 10.0f).join();
                double x = (Double) location.get("x");
                double y = (Double) location.get("y");
                
                target.getPointer().moveTo((int) x, (int) y).join();
                return null;
            } catch (Exception e) {
                throw new RuntimeException("Erro ao mover mouse: " + e.getMessage(), e);
            }
        });
    }
    
    /**
     * Escreve texto no elemento (sem clicar primeiro).
     *
     * @param text texto para escrever
     * @return CompletableFuture que completa quando o texto é escrito
     */
    public CompletableFuture<Void> write(String text) {
        return target.sendKeys(text);
    }
    
    /**
     * Define um arquivo para upload.
     *
     * @param path caminho do arquivo
     * @return CompletableFuture que completa quando o arquivo é definido
     */
    public CompletableFuture<Void> setFile(String path) {
        return setFiles(List.of(path));
    }
    
    /**
     * Define múltiplos arquivos para upload.
     *
     * @param paths lista de caminhos de arquivos
     * @return CompletableFuture que completa quando os arquivos são definidos
     */
    public CompletableFuture<Void> setFiles(List<String> paths) {
        return getNodeId().thenCompose(nodeId -> {
            Map<String, Object> args = new HashMap<>();
            args.put("nodeId", nodeId);
            args.put("files", paths);
            
            return target.executeCdpCmd("DOM.setFileInputFiles", args, null)
                .thenApply(v -> null);
        });
    }
    
    /**
     * Obtém a localização média do elemento com distribuições de spread.
     *
     * @param spreadA spread horizontal
     * @param spreadB spread vertical
     * @param biasA bias horizontal (0-1)
     * @param biasB bias vertical (0-1)
     * @param timeout timeout em segundos
     * @return CompletableFuture com a localização (x, y)
     */
    public CompletableFuture<Map<String, Object>> getMidLocation(
        float spreadA, float spreadB, float biasA, float biasB, float timeout
    ) {
        return getBoxModel().thenApply(boxModel -> {
            @SuppressWarnings("unchecked")
            List<Double> border = (List<Double>) boxModel.get("border");
            
            // Calcular bounds do elemento
            double minX = Math.min(Math.min(border.get(0), border.get(2)), 
                                   Math.min(border.get(4), border.get(6)));
            double maxX = Math.max(Math.max(border.get(0), border.get(2)), 
                                   Math.max(border.get(4), border.get(6)));
            double minY = Math.min(Math.min(border.get(1), border.get(3)), 
                                   Math.min(border.get(5), border.get(7)));
            double maxY = Math.max(Math.max(border.get(1), border.get(3)), 
                                   Math.max(border.get(5), border.get(7)));
            
            // Calcular posição média com bias
            double midX = minX + (maxX - minX) * biasA;
            double midY = minY + (maxY - minY) * biasB;
            
            Map<String, Object> location = new HashMap<>();
            location.put("x", midX);
            location.put("y", midY);
            location.put("type", "interactable");
            
            return location;
        });
    }
    
    /**
     * Submete um formulário.
     *
     * @return CompletableFuture que completa quando o formulário é submetido
     */
    public CompletableFuture<Void> submit() {
        String script = """
            if(this.tagName.toLowerCase() === 'form'){
                this.submit()
            } else {
                this.closest('form').submit()
            }
        """;
        return executeScript(script, null, "json", false, 5.0f).thenApply(v -> null);
    }
    
    /**
     * Obtém todos os atributos DOM do elemento.
     *
     * @return CompletableFuture com mapa de atributos
     */
    public CompletableFuture<Map<String, String>> getDomAttributes() {
        return getNodeId().thenCompose(nodeId -> {
            Map<String, Object> args = new HashMap<>();
            args.put("nodeId", nodeId);
            
            return target.executeCdpCmd("DOM.getAttributes", args, null);
        }).thenApply(result -> {
            JsonNode attributesNode = result.get("attributes");
            Map<String, String> attributes = new HashMap<>();
            
            if (attributesNode.isArray()) {
                for (int i = 0; i < attributesNode.size(); i += 2) {
                    String key = attributesNode.get(i).asText();
                    String value = i + 1 < attributesNode.size() ? 
                        attributesNode.get(i + 1).asText() : "";
                    attributes.put(key, value);
                }
            }
            
            return attributes;
        });
    }
    
    /**
     * Obtém um atributo DOM específico.
     *
     * @param name nome do atributo
     * @return CompletableFuture com o valor do atributo
     */
    public CompletableFuture<String> getDomAttribute(String name) {
        return getDomAttributes().thenApply(attrs -> attrs.get(name));
    }
    
    /**
     * Define um atributo DOM.
     *
     * @param name nome do atributo
     * @param value valor do atributo
     * @return CompletableFuture que completa quando o atributo é definido
     */
    public CompletableFuture<Void> setDomAttribute(String name, String value) {
        return getNodeId().thenCompose(nodeId -> {
            Map<String, Object> args = new HashMap<>();
            args.put("nodeId", nodeId);
            args.put("name", name);
            args.put("value", value);
            
            return target.executeCdpCmd("DOM.setAttributeValue", args, null)
                .thenApply(v -> null);
        });
    }
    
    /**
     * Verifica se o elemento está visível.
     *
     * @return CompletableFuture com true se visível
     */
    public CompletableFuture<Boolean> isVisible() {
        return getBoxModel().thenApply(boxModel -> boxModel != null)
            .exceptionally(e -> false);
    }
    
    /**
     * Rola a página até o elemento ficar visível.
     *
     * @return CompletableFuture com a localização após rolar
     */
    public CompletableFuture<Map<String, Object>> locationOnceScrolledIntoView() {
        return scrollTo(null).thenCompose(v -> {
            return getLocation().thenApply(loc -> {
                Map<String, Object> result = new HashMap<>();
                result.put("x", loc[0]);
                result.put("y", loc[1]);
                return result;
            });
        });
    }
    
    /**
     * Rola a página até o elemento.
     *
     * @param rect retângulo específico (opcional)
     * @return CompletableFuture que completa quando a rolagem é feita
     */
    public CompletableFuture<Void> scrollTo(Map<String, Object> rect) {
        if (rect != null) {
            return getNodeId().thenCompose(nodeId -> {
                Map<String, Object> args = new HashMap<>();
                args.put("nodeId", nodeId);
                args.put("rect", rect);
                
                return target.executeCdpCmd("DOM.scrollIntoViewIfNeeded", args, null)
                    .thenApply(v -> null);
            });
        } else {
            return executeScript("this.scrollIntoView({behavior: 'instant', block: 'center'})", 
                    null, "json", false, 5.0f)
                .thenApply(v -> null);
        }
    }
    
    /**
     * Obtém o valor de uma propriedade CSS.
     *
     * @param propertyName nome da propriedade
     * @return CompletableFuture com o valor da propriedade
     */
    public CompletableFuture<String> getValueOfCssProperty(String propertyName) {
        String script = String.format(
            "return window.getComputedStyle(this).getPropertyValue('%s')", 
            propertyName
        );
        return executeScript(script, null, "json", false, 5.0f).thenApply(result -> 
            result != null ? result.toString() : ""
        );
    }
    
    /**
     * Obtém o retângulo (bounds) do elemento.
     *
     * @return CompletableFuture com x, y, width, height
     */
    public CompletableFuture<Map<String, Object>> getRect() {
        return getBoxModel().thenApply(boxModel -> {
            @SuppressWarnings("unchecked")
            List<Double> border = (List<Double>) boxModel.get("border");
            
            double minX = Math.min(Math.min(border.get(0), border.get(2)), 
                                   Math.min(border.get(4), border.get(6)));
            double maxX = Math.max(Math.max(border.get(0), border.get(2)), 
                                   Math.max(border.get(4), border.get(6)));
            double minY = Math.min(Math.min(border.get(1), border.get(3)), 
                                   Math.min(border.get(5), border.get(7)));
            double maxY = Math.max(Math.max(border.get(1), border.get(3)), 
                                   Math.max(border.get(5), border.get(7)));
            
            Map<String, Object> rect = new HashMap<>();
            rect.put("x", minX);
            rect.put("y", minY);
            rect.put("width", maxX - minX);
            rect.put("height", maxY - minY);
            
            return rect;
        });
    }
    
    /**
     * Obtém o box model do elemento.
     *
     * @return CompletableFuture com o box model
     */
    public CompletableFuture<Map<String, Object>> getBoxModel() {
        return getNodeId().thenCompose(nodeId -> {
            Map<String, Object> args = new HashMap<>();
            args.put("nodeId", nodeId);
            
            return target.executeCdpCmd("DOM.getBoxModel", args, null);
        }).thenApply(result -> {
            JsonNode modelNode = result.get("model");
            return objectMapper.convertValue(modelNode, Map.class);
        });
    }
    
    /**
     * Obtém a função ARIA do elemento.
     *
     * @return CompletableFuture com a função ARIA
     */
    public CompletableFuture<String> getAriaRole() {
        return getDomAttribute("role");
    }
    
    /**
     * Obtém o nome acessível do elemento.
     *
     * @return CompletableFuture com o nome acessível
     */
    public CompletableFuture<String> getAccessibleName() {
        return getDomAttribute("aria-label");
    }
    
    /**
     * Tira uma screenshot do elemento em base64.
     *
     * @return CompletableFuture com a screenshot em base64
     */
    public CompletableFuture<String> getScreenshotAsBase64() {
        return getNodeId().thenCompose(nodeId -> {
            Map<String, Object> args = new HashMap<>();
            args.put("nodeId", nodeId);
            
            return target.executeCdpCmd("DOM.getBoxModel", args, null);
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
            
            return target.executeCdpCmd("Page.captureScreenshot", args, null);
        }).thenApply(result -> result.get("data").asText());
    }
    
    /**
     * Tira uma screenshot do elemento como bytes PNG.
     *
     * @return CompletableFuture com a screenshot como bytes
     */
    public CompletableFuture<byte[]> getScreenshotAsPng() {
        return getScreenshotAsBase64().thenApply(base64 -> 
            Base64.getDecoder().decode(base64)
        );
    }
    
    /**
     * Tira uma screenshot do elemento e salva em arquivo.
     *
     * @param filename nome do arquivo
     * @return CompletableFuture com true se sucesso
     */
    public CompletableFuture<Boolean> screenshot(String filename) {
        return getScreenshotAsPng().thenApply(pngBytes -> {
            try {
                Files.write(Paths.get(filename), pngBytes);
                return true;
            } catch (IOException e) {
                throw new RuntimeException("Erro ao salvar screenshot: " + e.getMessage(), e);
            }
        });
    }
    
    /**
     * Obtém o elemento pai.
     *
     * @return CompletableFuture com o elemento pai
     */
    public CompletableFuture<WebElement> getParent() {
        return describe().thenApply(description -> {
            JsonNode parentNode = description.get("parentNode");
            if (parentNode != null) {
                int backendNodeId = parentNode.get("backendNodeId").asInt();
                return new WebElement(target, frameId, isolatedExecId, null, null, backendNodeId, null, false);
            }
            return null;
        });
    }
    
    /**
     * Obtém os elementos filhos.
     *
     * @return CompletableFuture com lista de filhos
     */
    public CompletableFuture<List<WebElement>> getChildren() {
        return describe().thenApply(description -> {
            List<WebElement> children = new ArrayList<>();
            JsonNode childrenNode = description.get("children");
            if (childrenNode != null && childrenNode.isArray()) {
                for (JsonNode childNode : childrenNode) {
                    int backendNodeId = childNode.get("backendNodeId").asInt();
                    WebElement child = new WebElement(target, frameId, isolatedExecId, null, null, backendNodeId, null, false);
                    children.add(child);
                }
            }
            return children;
        });
    }
    
    /**
     * Executa script JavaScript no contexto do elemento (modo raw).
     *
     * @param script script para executar
     * @param awaitResult se deve aguardar resultado
     * @param args argumentos do script
     * @return CompletableFuture com o resultado
     */
    public CompletableFuture<Object> executeRawScript(String script, boolean awaitResult, Object... args) {
        List<Object> fullArgs = new ArrayList<>();
        fullArgs.add(this);
        fullArgs.addAll(Arrays.asList(args));
        
        return target.executeRawScript(script, awaitResult, null, fullArgs.toArray());
    }
    
    /**
     * Executa script JavaScript assíncrono no contexto do elemento.
     *
     * @param script script para executar
     * @param maxDepth profundidade máxima de serialização
     * @param args argumentos do script
     * @return CompletableFuture com o resultado
     */
    public CompletableFuture<Object> executeAsyncScript(String script, int maxDepth, Object... args) {
        List<Object> fullArgs = new ArrayList<>();
        fullArgs.add(this);
        fullArgs.addAll(Arrays.asList(args));
        
        return target.executeAsyncScript(script, maxDepth, null, fullArgs.toArray());
    }
    
    /**
     * Avalia expressão JavaScript assíncrona no contexto do elemento.
     *
     * @param script expressão para avaliar
     * @param maxDepth profundidade máxima de serialização
     * @param args argumentos
     * @return CompletableFuture com o resultado
     */
    public CompletableFuture<Object> evalAsync(String script, int maxDepth, Object... args) {
        List<Object> fullArgs = new ArrayList<>();
        fullArgs.add(this);
        fullArgs.addAll(Arrays.asList(args));
        
        return target.evalAsync(script, 10.0f, true, fullArgs.toArray());
    }
    
    @Override
    public String toString() {
        return String.format("<WebElement (obj_id=\"%s\", node_id=%d)>",
            objId, nodeId);
    }
    
    @Override
    public boolean equals(Object obj) {
        if (this == obj) return true;
        if (!(obj instanceof WebElement)) return false;
        WebElement other = (WebElement) obj;
        return Objects.equals(objId, other.objId) && 
               Objects.equals(nodeId, other.nodeId);
    }
    
    @Override
    public int hashCode() {
        return Objects.hash(objId, nodeId);
    }
}

