package io.github.selenium.javaDriverless.scripts;

import com.fasterxml.jackson.databind.JsonNode;

import io.github.selenium.javaDriverless.types.BaseTarget;
import io.github.selenium.javaDriverless.types.Target;

import java.lang.ref.WeakReference;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;
import java.util.function.UnaryOperator;

/**
 * Interceptação e modificação de requisições de rede.
 * <p>
 * Este módulo permite interceptar, modificar, bloquear ou continuar requisições HTTP/HTTPS.
 * Usa o domain Fetch do CDP.
 * </p>
 */
public class NetworkInterceptor {
    private static final Map<Object, NetworkRequestDispatcher> REQUEST_DISPATCHERS =
            Collections.synchronizedMap(new WeakHashMap<>());

    /**
     * Registra uma transformação obrigatória de headers no dispatcher único do target.
     *
     * <p>O listener é instalado antes de {@code Fetch.enable}. Enquanto existir um guard
     * obrigatório, {@link #disableFetch(Object)} falha fechado para que uma API pública não abra
     * uma janela em que requests escapariam sem a transformação.</p>
     */
    public static CompletableFuture<Void> installEssentialRequestHeaderGuard(
            Object target,
            String guardId,
            UnaryOperator<Map<String, String>> guard,
            Consumer<Throwable> failureSink) {
        Objects.requireNonNull(guardId, "guardId");
        Objects.requireNonNull(guard, "guard");
        Objects.requireNonNull(failureSink, "failureSink");

        NetworkRequestDispatcher dispatcher = requestDispatcher(target);
        dispatcher.putEssentialGuard(guardId, guard, failureSink);
        return dispatcher.ensureListenerInstalled()
                .thenCompose(ignored -> dispatcher.enableEssentialFetch())
                .handle((ignored, failure) -> {
                    if (failure != null) {
                        RuntimeException safeFailure = dispatcher.recordSafeFailure(
                                "Falha ao instalar guard obrigatório de requests", failure);
                        throw new CompletionException(safeFailure);
                    }
                    return null;
                });
    }

    /**
     * Exceção lançada quando uma requisição já foi tratada.
     */
    public static class RequestDoneException extends RuntimeException {
        private final Object data;

        public RequestDoneException(Object data, String url) {
            super("Requisição ou desafio de autenticação com url:<redacted> já foi retomado");
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
            super("Auth para url:<redacted> já foi tratado por aplicação externa (ex: chrome-extension)");
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
            int headerCount = params.has("headers") && params.get("headers").isObject()
                    ? params.get("headers").size()
                    : 0;
            return "Request{method=" + safeMethodForDiagnostics(getMethod())
                    + ", url=<redacted>"
                    + ", headerCount=" + headerCount
                    + ", hasPostData=" + params.has("postData")
                    + "}";
        }

        private static String safeMethodForDiagnostics(String method) {
            if (method == null) return "<absent>";
            String normalized = method.toUpperCase(Locale.ROOT);
            return Set.of("GET", "HEAD", "POST", "PUT", "PATCH", "DELETE",
                            "OPTIONS", "CONNECT", "TRACE")
                    .contains(normalized) ? normalized : "<other>";
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
        private final Map<String, String> guardedHeaders;
        private final UnaryOperator<Map<String, String>> headerGuard;
        private final Consumer<Throwable> failureSink;
        private boolean done = false;

        public InterceptedRequest(String requestId, String frameId, JsonNode request, Object target) {
            this(requestId, frameId, request, target, null, UnaryOperator.identity(), ignored -> {
            });
        }

        InterceptedRequest(
                String requestId,
                String frameId,
                JsonNode request,
                Object target,
                Map<String, String> guardedHeaders,
                UnaryOperator<Map<String, String>> headerGuard,
                Consumer<Throwable> failureSink) {
            super(request, target);
            this.requestId = requestId;
            this.frameId = frameId;
            this.guardedHeaders = guardedHeaders;
            this.headerGuard = headerGuard;
            this.failureSink = failureSink;
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
            if (guardedHeaders != null) {
                args.put("headers", toHeaderEntries(guardedHeaders));
            }

            done = true;
            return observeFailure(execute(target, "Fetch.continueRequest", args));
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
            return observeFailure(execute(target, "Fetch.failRequest", args));
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
                Map<String, String> guarded = headerGuard.apply(new LinkedHashMap<>(headers));
                args.put("headers", toHeaderEntries(guarded));
            } else if (guardedHeaders != null) {
                // Um interceptor público pode alterar apenas URL/método/body. Mesmo nesse caso, a
                // continuação precisa carregar a transformação obrigatória calculada sobre os
                // headers originais; omiti-la reintroduziria o valor físico do host.
                args.put("headers", toHeaderEntries(guardedHeaders));
            }

            done = true;

            return observeFailure(execute(target, "Fetch.continueRequest", args));
        }

        private CompletableFuture<Void> observeFailure(CompletableFuture<Void> command) {
            return command.whenComplete((ignored, failure) -> {
                if (failure != null) {
                    failureSink.accept(failure);
                }
            });
        }

        static CompletableFuture<Void> execute(
                Object target, String command, Map<String, Object> arguments) {
            if (target instanceof Target) {
                return ((Target) target).executeCdpCmd(command, arguments, null).thenApply(v -> null);
            }
            if (target instanceof BaseTarget) {
                return ((BaseTarget) target).executeCdpCmd(command, arguments, null).thenApply(v -> null);
            }
            return CompletableFuture.failedFuture(new IllegalStateException("Target inválido"));
        }

        private static List<Map<String, String>> toHeaderEntries(Map<String, String> headers) {
            List<Map<String, String>> entries = new ArrayList<>(headers.size());
            headers.forEach((name, value) -> entries.add(Map.of("name", name, "value", value)));
            return entries;
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
        NetworkRequestDispatcher dispatcher;
        try {
            dispatcher = requestDispatcher(target);
        } catch (RuntimeException invalidTarget) {
            return CompletableFuture.failedFuture(invalidTarget);
        }
        return dispatcher.ensureListenerInstalled()
                .thenCompose(ignored -> dispatcher.enablePublicFetch(patterns));
    }

    /**
     * Desabilita interceptação de rede.
     *
     * @param target target para desabilitar interceptação
     * @return CompletableFuture que completa quando a interceptação é desabilitada
     */
    public static CompletableFuture<Void> disableFetch(Object target) {
        NetworkRequestDispatcher dispatcher;
        try {
            dispatcher = requestDispatcher(target);
        } catch (RuntimeException invalidTarget) {
            return CompletableFuture.failedFuture(invalidTarget);
        }
        if (dispatcher.hasEssentialGuards()) {
            return CompletableFuture.failedFuture(dispatcher.recordSafeFailure(
                    "Fetch não pode ser desabilitado enquanto há guards obrigatórios", null));
        }
        return execute(target, "Fetch.disable", null);
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
        Objects.requireNonNull(callback, "callback");
        NetworkRequestDispatcher dispatcher;
        try {
            dispatcher = requestDispatcher(target);
        } catch (RuntimeException invalidTarget) {
            return CompletableFuture.failedFuture(invalidTarget);
        }
        dispatcher.addPublicListener(callback);
        return dispatcher.ensureListenerInstalled();
    }

    private static NetworkRequestDispatcher requestDispatcher(Object target) {
        validateTarget(target);
        synchronized (REQUEST_DISPATCHERS) {
            return REQUEST_DISPATCHERS.computeIfAbsent(target, NetworkRequestDispatcher::new);
        }
    }

    private static void validateTarget(Object target) {
        if (!(target instanceof Target) && !(target instanceof BaseTarget)) {
            throw new IllegalArgumentException("Target deve ser Target ou BaseTarget");
        }
    }

    static CompletableFuture<Void> addRawRequestListener(
            Object target, Consumer<JsonNode> listener) {
        if (target instanceof Target) {
            return ((Target) target).addCdpListener("Fetch.requestPaused", listener);
        }
        return ((BaseTarget) target).addCdpListener("Fetch.requestPaused", listener);
    }

    static CompletableFuture<Void> execute(
            Object target, String command, Map<String, Object> arguments) {
        if (target instanceof Target) {
            return ((Target) target).executeCdpCmd(command, arguments, null).thenApply(v -> null);
        }
        if (target instanceof BaseTarget) {
            return ((BaseTarget) target).executeCdpCmd(command, arguments, null).thenApply(v -> null);
        }
        return CompletableFuture.failedFuture(
                new IllegalArgumentException("Target deve ser Target ou BaseTarget"));
    }

}
