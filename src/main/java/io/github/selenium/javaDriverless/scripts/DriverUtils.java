package io.github.selenium.javaDriverless.scripts;

import com.fasterxml.jackson.databind.JsonNode;

import io.github.selenium.javaDriverless.types.Target;

import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.function.Function;

/**
 * Utilitários auxiliares para drivers e targets.
 * <p>
 * Este módulo fornece funções helper para:
 * - Gerenciamento de targets
 * - Operações com cookies
 * - Navegação entre contextos
 * </p>
 */
public class DriverUtils {
    
    /**
     * Informações sobre um target.
     */
    public static class TargetInfo {
        public final String targetId;
        public final String type;
        public final String title;
        public final String url;
        public final boolean attached;
        public final String browserContextId;
        public final Target target;
        
        public TargetInfo(JsonNode info, Target target) {
            this.targetId = info.get("targetId").asText();
            this.type = info.get("type").asText();
            this.title = info.has("title") ? info.get("title").asText() : "";
            this.url = info.has("url") ? info.get("url").asText() : "";
            this.attached = info.has("attached") && info.get("attached").asBoolean();
            this.browserContextId = info.has("browserContextId") ? 
                info.get("browserContextId").asText() : null;
            this.target = target;
        }
    }
    
    /**
     * Obtém todos os targets.
     *
     * @param cdpExec função para executar comandos CDP
     * @param targetGetter função para obter Target por ID
     * @param type filtrar por tipo (null para todos)
     * @param contextId filtrar por contexto (null para todos)
     * @param maxWsSize tamanho máximo de WebSocket
     * @return CompletableFuture com mapa de TargetInfo
     */
    public static CompletableFuture<Map<String, TargetInfo>> getTargets(
            Function<Map<String, Object>, CompletableFuture<JsonNode>> cdpExec,
            Function<String, CompletableFuture<Target>> targetGetter,
            String type, String contextId, int maxWsSize) {
        
        return cdpExec.apply(Collections.singletonMap("cmd", "Target.getTargets"))
            .thenCompose(result -> {
                JsonNode targetInfos = result.get("targetInfos");
                Map<String, TargetInfo> infos = new HashMap<>();
                List<CompletableFuture<Void>> futures = new ArrayList<>();
                
                for (JsonNode info : targetInfos) {
                    String targetId = info.get("targetId").asText();
                    
                    CompletableFuture<Void> future = targetGetter.apply(targetId)
                        .thenAccept(target -> {
                            TargetInfo targetInfo = new TargetInfo(info, target);
                            
                            boolean typeMatches = (type == null || type.equals(targetInfo.type));
                            boolean contextMatches = (contextId == null || 
                                contextId.equals(targetInfo.browserContextId));
                            
                            if (typeMatches && contextMatches) {
                                infos.put(targetId, targetInfo);
                            }
                        });
                    
                    futures.add(future);
                }
                
                return CompletableFuture.allOf(futures.toArray(new CompletableFuture[0]))
                    .thenApply(v -> infos);
            });
    }
    
    /**
     * Obtém um target por ID.
     *
     * @param targetId ID do target
     * @param host endereço do host
     * @param driver instância do driver
     * @param context contexto do navegador
     * @param isRemote se é conexão remota
     * @param timeout timeout em segundos
     * @param maxWsSize tamanho máximo de WebSocket
     * @return CompletableFuture com o Target
     */
    public static CompletableFuture<Target> getTarget(String targetId, String host, Object driver,
                                                      Object context, boolean isRemote, 
                                                      float timeout, int maxWsSize) {
        Target target = new Target(host, targetId, driver, context, isRemote, 
            timeout, null, false, maxWsSize);
        return target.init();
    }
    
    /**
     * Retorna um conjunto de dicionários correspondentes aos cookies visíveis na sessão atual.
     *
     * @param target target para executar o comando
     * @return CompletableFuture com a lista de cookies
     */
    public static CompletableFuture<List<Map<String, Object>>> getCookies(Target target) {
        return target.executeCdpCmd("Network.getCookies", null, null)
            .thenApply(result -> {
                JsonNode cookies = result.get("cookies");
                List<Map<String, Object>> cookieList = new ArrayList<>();
                
                for (JsonNode cookie : cookies) {
                    Map<String, Object> cookieMap = new HashMap<>();
                    cookie.fields().forEachRemaining(entry -> 
                        cookieMap.put(entry.getKey(), convertJsonValue(entry.getValue()))
                    );
                    cookieList.add(cookieMap);
                }
                
                return cookieList;
            });
    }
    
    /**
     * Obtém um único cookie pelo nome.
     *
     * @param target target para executar o comando
     * @param name nome do cookie
     * @return CompletableFuture com o cookie ou null se não encontrado
     */
    public static CompletableFuture<Map<String, Object>> getCookie(Target target, String name) {
        return getCookies(target).thenApply(cookies -> {
            for (Map<String, Object> cookie : cookies) {
                if (name.equals(cookie.get("name"))) {
                    return cookie;
                }
            }
            return null;
        });
    }
    
    /**
     * Deleta um único cookie com o nome dado.
     *
     * @param target target para executar o comando
     * @param name nome do cookie
     * @param url URL opcional
     * @param domain domínio opcional
     * @param path caminho opcional
     * @return CompletableFuture que completa quando o cookie é deletado
     */
    public static CompletableFuture<Void> deleteCookie(Target target, String name, 
                                                       String url, String domain, String path) {
        Map<String, Object> args = new HashMap<>();
        args.put("name", name);
        
        if (url != null) args.put("url", url);
        if (domain != null) args.put("domain", domain);
        if (path != null) args.put("path", path);
        
        return target.executeCdpCmd("Network.deleteCookies", args, null)
            .thenApply(v -> null);
    }
    
    /**
     * Deleta todos os cookies no escopo da sessão.
     *
     * @param target target para executar o comando
     * @return CompletableFuture que completa quando os cookies são deletados
     */
    public static CompletableFuture<Void> deleteAllCookies(Target target) {
        return target.executeCdpCmd("Network.clearBrowserCookies", null, null)
            .thenApply(v -> null);
    }
    
    /**
     * Adiciona um cookie à sessão atual.
     *
     * @param target target para executar o comando
     * @param cookieDict dicionário com chaves obrigatórias "name" e "value";
     *                  chaves opcionais: "path", "domain", "secure", "httpOnly", "expiry", "sameSite"
     * @param contextId ID do contexto do navegador (opcional)
     * @return CompletableFuture que completa quando o cookie é adicionado
     */
    public static CompletableFuture<Void> addCookie(Target target, Map<String, Object> cookieDict,
                                                    String contextId) {
        if (cookieDict.containsKey("sameSite")) {
            String sameSite = cookieDict.get("sameSite").toString();
            if (!Arrays.asList("Strict", "Lax", "None").contains(sameSite)) {
                throw new IllegalArgumentException(
                    "sameSite deve ser 'Strict', 'Lax' ou 'None', recebido: " + sameSite
                );
            }
        }
        
        Map<String, Object> args = new HashMap<>();
        args.put("cookies", Collections.singletonList(cookieDict));
        
        if (contextId != null) {
            args.put("browserContextId", contextId);
        }
        
        return target.executeCdpCmd("Storage.setCookies", args, null)
            .thenApply(v -> null);
    }
    
    /**
     * Converte JsonNode para valor Java.
     */
    private static Object convertJsonValue(JsonNode node) {
        if (node.isNull()) return null;
        if (node.isBoolean()) return node.asBoolean();
        if (node.isInt()) return node.asInt();
        if (node.isLong()) return node.asLong();
        if (node.isDouble()) return node.asDouble();
        if (node.isTextual()) return node.asText();
        return node.toString();
    }
}

