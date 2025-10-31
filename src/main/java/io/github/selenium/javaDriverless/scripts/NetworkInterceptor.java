package io.github.selenium.javaDriverless.scripts;

import com.fasterxml.jackson.databind.JsonNode;

import io.github.selenium.javaDriverless.types.BaseTarget;
import io.github.selenium.javaDriverless.types.Target;

import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;

/**
 * Interceptação e modificação de requisições de rede.
 * <p>
 * Este módulo permite interceptar, modificar, bloquear ou continuar requisições HTTP/HTTPS.
 * Usa o domain Fetch do CDP.
 * </p>
 */
public class NetworkInterceptor {
    
    /**
     * Exceção lançada quando uma requisição já foi tratada.
     */
    public static class RequestDoneException extends RuntimeException {
        private final Object data;
        
        public RequestDoneException(Object data, String url) {
            super(String.format("Requisição ou desafio de autenticação com url:\"%s\" já foi retomado", url));
            this.data = data;
        }
        
        public Object getRequest() {
            return data;
        }
    }
    
    /**
     * Exceção lançada quando autenticação já foi tratada externamente.
     */
    public static class AuthAlreadyHandledException extends RuntimeException {
        private final Object data;
        
        public AuthAlreadyHandledException(Object data, String url) {
            super(String.format("Auth para url\"%s\" já foi tratado por aplicação externa (ex: chrome-extension)", url));
            this.data = data;
        }
        
        public Object getRequest() {
            return data;
        }
    }
    
    /**
     * Estágios de requisição.
     */
    public static class RequestStages {
        public static final int REQUEST = 0;
        public static final int RESPONSE = 1;
    }
    
    /**
     * Padrões de requisição.
     */
    public static class RequestPattern {
        public static final Map<String, Object> ANY_REQUEST = 
            Map.of("urlPattern", "*", "requestStage", "Request");
        
        public static final Map<String, Object> ANY_RESPONSE = 
            Map.of("urlPattern", "*", "requestStage", "Response");
        
        /**
         * Cria um novo padrão de requisição.
         *
         * @param urlPattern padrão de URL (ex: "*", "*.js")
         * @param resourceType tipo de recurso (Document, Script, XHR, etc)
         * @param requestStage estágio da requisição (Request ou Response)
         * @return mapa com o padrão
         */
        public static Map<String, Object> create(String urlPattern, String resourceType, 
                                                 String requestStage) {
            Map<String, Object> pattern = new HashMap<>();
            
            if (urlPattern != null) {
                pattern.put("urlPattern", urlPattern);
            }
            if (resourceType != null) {
                pattern.put("resourceType", resourceType);
            }
            if (requestStage != null) {
                pattern.put("requestStage", requestStage);
            }
            
            return pattern;
        }
    }
    
    /**
     * Representa uma requisição HTTP.
     */
    public static class Request {
        protected final JsonNode params;
        protected final Object target;
        
        public Request(JsonNode params, Object target) {
            this.params = params;
            this.target = target;
        }
        
        public Object getTarget() {
            return target;
        }
        
        public JsonNode getParams() {
            return params;
        }
        
        public String getUrl() {
            return params.get("url").asText();
        }
        
        public String getMethod() {
            return params.has("method") ? params.get("method").asText() : null;
        }
        
        public Map<String, String> getHeaders() {
            Map<String, String> headers = new HashMap<>();
            if (params.has("headers")) {
                JsonNode headersNode = params.get("headers");
                headersNode.fields().forEachRemaining(entry -> 
                    headers.put(entry.getKey(), entry.getValue().asText())
                );
            }
            return headers;
        }
        
        public String getPostData() {
            return params.has("postData") ? params.get("postData").asText() : null;
        }
        
        @Override
        public String toString() {
            return params.toString();
        }
    }
    
    /**
     * Desafio de autenticação.
     */
    public static class AuthChallenge {
        protected final JsonNode params;
        protected final Object target;
        
        public AuthChallenge(JsonNode params, Object target) {
            this.params = params;
            this.target = target;
        }
        
        public String getSource() {
            return params.has("source") ? params.get("source").asText() : null;
        }
        
        public String getOrigin() {
            return params.get("origin").asText();
        }
        
        public String getScheme() {
            return params.get("scheme").asText();
        }
        
        public String getRealm() {
            return params.get("realm").asText();
        }
    }
    
    /**
     * Requisição interceptada.
     */
    public static class InterceptedRequest extends Request {
        private final String requestId;
        private final String frameId;
        private boolean done = false;
        
        public InterceptedRequest(String requestId, String frameId, JsonNode request, Object target) {
            super(request, target);
            this.requestId = requestId;
            this.frameId = frameId;
        }
        
        public String getRequestId() {
            return requestId;
        }
        
        public String getFrameId() {
            return frameId;
        }
        
        public boolean isDone() {
            return done;
        }
        
        /**
         * Continua a requisição sem modificações.
         *
         * @return CompletableFuture que completa quando a requisição continua
         */
        public CompletableFuture<Void> continueRequest() {
            if (done) {
                throw new RequestDoneException(this, getUrl());
            }
            
            Map<String, Object> args = new HashMap<>();
            args.put("requestId", requestId);
            
            done = true;
            
            if (target instanceof Target) {
                return ((Target) target).executeCdpCmd("Fetch.continueRequest", args, null)
                    .thenApply(v -> null);
            } else if (target instanceof BaseTarget) {
                return ((BaseTarget) target).executeCdpCmd("Fetch.continueRequest", args, null)
                    .thenApply(v -> null);
            }
            
            return CompletableFuture.failedFuture(
                new IllegalStateException("Target inválido")
            );
        }
        
        /**
         * Falha a requisição com um erro.
         *
         * @param errorReason razão do erro (ex: "Failed", "Aborted", "TimedOut")
         * @return CompletableFuture que completa quando a requisição falha
         */
        public CompletableFuture<Void> failRequest(String errorReason) {
            if (done) {
                throw new RequestDoneException(this, getUrl());
            }
            
            Map<String, Object> args = new HashMap<>();
            args.put("requestId", requestId);
            args.put("errorReason", errorReason != null ? errorReason : "Failed");
            
            done = true;
            
            if (target instanceof Target) {
                return ((Target) target).executeCdpCmd("Fetch.failRequest", args, null)
                    .thenApply(v -> null);
            } else if (target instanceof BaseTarget) {
                return ((BaseTarget) target).executeCdpCmd("Fetch.failRequest", args, null)
                    .thenApply(v -> null);
            }
            
            return CompletableFuture.failedFuture(
                new IllegalStateException("Target inválido")
            );
        }
        
        /**
         * Continua a requisição com modificações.
         *
         * @param url URL modificada (null para manter original)
         * @param method método modificado (null para manter original)
         * @param postData POST data modificado (null para manter original)
         * @param headers headers modificados (null para manter originais)
         * @return CompletableFuture que completa quando a requisição continua
         */
        public CompletableFuture<Void> continueRequest(String url, String method, 
                                                       String postData, Map<String, String> headers) {
            if (done) {
                throw new RequestDoneException(this, getUrl());
            }
            
            Map<String, Object> args = new HashMap<>();
            args.put("requestId", requestId);
            
            if (url != null) args.put("url", url);
            if (method != null) args.put("method", method);
            if (postData != null) args.put("postData", postData);
            
            if (headers != null) {
                List<Map<String, String>> headersList = new ArrayList<>();
                headers.forEach((name, value) -> {
                    Map<String, String> header = new HashMap<>();
                    header.put("name", name);
                    header.put("value", value);
                    headersList.add(header);
                });
                args.put("headers", headersList);
            }
            
            done = true;
            
            if (target instanceof Target) {
                return ((Target) target).executeCdpCmd("Fetch.continueRequest", args, null)
                    .thenApply(v -> null);
            } else if (target instanceof BaseTarget) {
                return ((BaseTarget) target).executeCdpCmd("Fetch.continueRequest", args, null)
                    .thenApply(v -> null);
            }
            
            return CompletableFuture.failedFuture(
                new IllegalStateException("Target inválido")
            );
        }
    }
    
    /**
     * Habilita interceptação de rede em um target.
     *
     * @param target target para habilitar interceptação
     * @param patterns padrões de requisição para interceptar
     * @return CompletableFuture que completa quando a interceptação é habilitada
     */
    public static CompletableFuture<Void> enableFetch(Object target, List<Map<String, Object>> patterns) {
        Map<String, Object> args = new HashMap<>();
        if (patterns != null && !patterns.isEmpty()) {
            args.put("patterns", patterns);
        } else {
            args.put("patterns", Collections.singletonList(RequestPattern.ANY_REQUEST));
        }
        args.put("handleAuthRequests", true);
        
        if (target instanceof Target) {
            return ((Target) target).executeCdpCmd("Fetch.enable", args, null)
                .thenApply(v -> null);
        } else if (target instanceof BaseTarget) {
            return ((BaseTarget) target).executeCdpCmd("Fetch.enable", args, null)
                .thenApply(v -> null);
        }
        
        return CompletableFuture.failedFuture(
            new IllegalArgumentException("Target deve ser Target ou BaseTarget")
        );
    }
    
    /**
     * Desabilita interceptação de rede.
     *
     * @param target target para desabilitar interceptação
     * @return CompletableFuture que completa quando a interceptação é desabilitada
     */
    public static CompletableFuture<Void> disableFetch(Object target) {
        if (target instanceof Target) {
            return ((Target) target).executeCdpCmd("Fetch.disable", null, null)
                .thenApply(v -> null);
        } else if (target instanceof BaseTarget) {
            return ((BaseTarget) target).executeCdpCmd("Fetch.disable", null, null)
                .thenApply(v -> null);
        }
        
        return CompletableFuture.failedFuture(
            new IllegalArgumentException("Target deve ser Target ou BaseTarget")
        );
    }
    
    /**
     * Adiciona listener para interceptar requisições.
     *
     * @param target target para adicionar listener
     * @param callback função a ser chamada para cada requisição interceptada
     * @return CompletableFuture que completa quando o listener é adicionado
     */
    public static CompletableFuture<Void> addRequestListener(Object target, 
                                                             Consumer<InterceptedRequest> callback) {
        Consumer<JsonNode> listener = params -> {
            String requestId = params.get("requestId").asText();
            String frameId = params.has("frameId") ? params.get("frameId").asText() : null;
            JsonNode request = params.get("request");
            
            InterceptedRequest interceptedRequest = new InterceptedRequest(
                requestId, frameId, request, target
            );
            
            callback.accept(interceptedRequest);
        };
        
        if (target instanceof Target) {
            return ((Target) target).addCdpListener("Fetch.requestPaused", listener);
        } else if (target instanceof BaseTarget) {
            return ((BaseTarget) target).addCdpListener("Fetch.requestPaused", listener);
        }
        
        return CompletableFuture.failedFuture(
            new IllegalArgumentException("Target deve ser Target ou BaseTarget")
        );
    }
}

