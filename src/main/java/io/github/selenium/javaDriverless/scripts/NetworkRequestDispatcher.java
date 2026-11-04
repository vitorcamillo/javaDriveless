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
final class NetworkRequestDispatcher {
        final WeakReference<Object> target;
        final Map<String, EssentialGuard> essentialGuards = new ConcurrentHashMap<>();
        final List<Consumer<NetworkInterceptor.InterceptedRequest>> publicListeners = new CopyOnWriteArrayList<>();
        CompletableFuture<Void> listenerInstallation;
        List<Map<String, Object>> publicPatterns = List.of();
        boolean publicFetchConfigured;

        NetworkRequestDispatcher(Object target) {
            this.target = new WeakReference<>(target);
        }

        void putEssentialGuard(
                String id,
                UnaryOperator<Map<String, String>> guard,
                Consumer<Throwable> failureSink) {
            essentialGuards.put(id, new EssentialGuard(guard, failureSink));
        }

        void addPublicListener(Consumer<NetworkInterceptor.InterceptedRequest> listener) {
            publicListeners.add(listener);
        }

        boolean hasEssentialGuards() {
            return !essentialGuards.isEmpty();
        }

        synchronized CompletableFuture<Void> ensureListenerInstalled() {
            if (listenerInstallation != null) {
                return listenerInstallation;
            }
            Object liveTarget = requireTarget();
            listenerInstallation = NetworkInterceptor.addRawRequestListener(liveTarget, this::dispatch);
            return listenerInstallation;
        }

        synchronized CompletableFuture<Void> enablePublicFetch(
                List<Map<String, Object>> requestedPatterns) {
            publicPatterns = immutablePatterns(requestedPatterns);
            publicFetchConfigured = true;
            return applyFetchConfiguration();
        }

        synchronized CompletableFuture<Void> enableEssentialFetch() {
            return applyFetchConfiguration();
        }

        CompletableFuture<Void> applyFetchConfiguration() {
            List<Map<String, Object>> effectivePatterns = new ArrayList<>();
            if (hasEssentialGuards()) {
                effectivePatterns.add(NetworkInterceptor.RequestPattern.ANY_REQUEST);
            }
            if (publicFetchConfigured) {
                for (Map<String, Object> pattern : publicPatterns) {
                    if (!effectivePatterns.contains(pattern)) {
                        effectivePatterns.add(pattern);
                    }
                }
            }
            if (effectivePatterns.isEmpty()) {
                effectivePatterns.add(NetworkInterceptor.RequestPattern.ANY_REQUEST);
            }

            Map<String, Object> arguments = new HashMap<>();
            arguments.put("patterns", effectivePatterns);
            if (publicFetchConfigured) {
                arguments.put("handleAuthRequests", true);
            }
            return NetworkInterceptor.execute(requireTarget(), "Fetch.enable", arguments);
        }

        static List<Map<String, Object>> immutablePatterns(
                List<Map<String, Object>> requestedPatterns) {
            if (requestedPatterns == null || requestedPatterns.isEmpty()) {
                return List.of(NetworkInterceptor.RequestPattern.ANY_REQUEST);
            }
            List<Map<String, Object>> copy = new ArrayList<>(requestedPatterns.size());
            for (Map<String, Object> pattern : requestedPatterns) {
                copy.add(Collections.unmodifiableMap(new LinkedHashMap<>(pattern)));
            }
            return Collections.unmodifiableList(copy);
        }

        void dispatch(JsonNode params) {
            String requestId = textOrNull(params, "requestId");
            JsonNode request = params == null ? null : params.get("request");
            if (requestId == null || request == null || !request.isObject()) {
                recordSafeFailure("Evento Fetch.requestPaused inválido", null);
                failPausedRequestIfPossible(requestId);
                return;
            }

            try {
                Map<String, String> originalHeaders = readHeaders(request.get("headers"));
                boolean responseStage = params.has("responseStatusCode")
                        || params.has("responseErrorReason");
                Map<String, String> guardedHeaders = responseStage
                        ? originalHeaders : applyEssentialGuards(originalHeaders);
                Map<String, String> override = originalHeaders.equals(guardedHeaders)
                        ? null : guardedHeaders;
                UnaryOperator<Map<String, String>> activeHeaderGuard = responseStage
                        ? headers -> new LinkedHashMap<>(headers)
                        : this::applyEssentialGuards;
                String frameId = textOrNull(params, "frameId");
                NetworkInterceptor.InterceptedRequest intercepted = new NetworkInterceptor.InterceptedRequest(
                        requestId,
                        frameId,
                        request,
                        requireTarget(),
                        override,
                        activeHeaderGuard,
                        failure -> recordSafeFailure(
                                "Falha ao concluir request interceptado", failure));

                if (publicListeners.isEmpty()) {
                    intercepted.continueRequest();
                    return;
                }
                for (Consumer<NetworkInterceptor.InterceptedRequest> listener : publicListeners) {
                    if (intercepted.isDone()) {
                        break;
                    }
                    listener.accept(intercepted);
                }
            } catch (Throwable failure) {
                recordSafeFailure("Falha ao aplicar guard obrigatório de request", failure);
                failPausedRequestIfPossible(requestId);
            }
        }

        Map<String, String> applyEssentialGuards(Map<String, String> input) {
            Map<String, String> current = new LinkedHashMap<>(input);
            for (EssentialGuard essential : essentialGuards.values()) {
                Map<String, String> transformed = essential.guard.apply(
                        Collections.unmodifiableMap(current));
                if (transformed == null) {
                    throw new IllegalStateException("Guard obrigatório retornou headers nulos");
                }
                current = new LinkedHashMap<>(transformed);
            }
            return current;
        }

        RuntimeException recordSafeFailure(String message, Throwable ignoredCause) {
            // Não preservar a causa: falhas de transporte/callback podem carregar URL, cookies ou
            // headers no texto. O runtime recebe apenas um diagnóstico estável e sanitizado.
            RuntimeException safe = new IllegalStateException(message);
            for (EssentialGuard guard : essentialGuards.values()) {
                try {
                    guard.failureSink.accept(safe);
                } catch (Throwable ignored) {
                    // O sink é somente diagnóstico; nunca substituir nem expor a falha segura.
                }
            }
            return safe;
        }

        void failPausedRequestIfPossible(String requestId) {
            if (requestId == null) {
                return;
            }
            Map<String, Object> arguments = Map.of(
                    "requestId", requestId,
                    "errorReason", "BlockedByClient");
            NetworkInterceptor.execute(requireTarget(), "Fetch.failRequest", arguments)
                    .whenComplete((ignored, failure) -> {
                        if (failure != null) {
                            recordSafeFailure("Falha ao bloquear request inseguro", failure);
                        }
                    });
        }

        Object requireTarget() {
            Object liveTarget = target.get();
            if (liveTarget == null) {
                throw new IllegalStateException("Target de interceptação não está mais disponível");
            }
            return liveTarget;
        }

        static Map<String, String> readHeaders(JsonNode headersNode) {
            Map<String, String> headers = new LinkedHashMap<>();
            if (headersNode == null || !headersNode.isObject()) {
                return headers;
            }
            headersNode.fields().forEachRemaining(entry ->
                    headers.put(entry.getKey(), entry.getValue().asText()));
            return headers;
        }

        static String textOrNull(JsonNode node, String field) {
            if (node == null || !node.hasNonNull(field)) {
                return null;
            }
            String value = node.get(field).asText();
            return value.isBlank() ? null : value;
        }

    }

    final class EssentialGuard {
        final UnaryOperator<Map<String, String>> guard;
        final Consumer<Throwable> failureSink;

        EssentialGuard(
                UnaryOperator<Map<String, String>> guard, Consumer<Throwable> failureSink) {
            this.guard = guard;
            this.failureSink = failureSink;
        }
    }
