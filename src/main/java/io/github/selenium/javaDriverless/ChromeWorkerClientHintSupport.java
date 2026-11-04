package io.github.selenium.javaDriverless;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.selenium.javaDriverless.logging.JavaDriverlessLogger;
import io.github.selenium.javaDriverless.scripts.SeleniumProxyWorkerSurfaceScripts;
import io.github.selenium.javaDriverless.types.BaseTarget;
import io.github.selenium.javaDriverless.types.BrowserFingerprintProfile;
import io.github.selenium.javaDriverless.types.FingerprintCdpOverrides;
import io.github.selenium.javaDriverless.types.Target;
import io.github.selenium.javaDriverless.utils.UserAgentResolver;
import org.slf4j.Logger;

import java.net.URI;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BiFunction;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.function.Function;

/** Instala e coordena o spoof em workers e targets fora do processo da página. */
abstract class ChromeWorkerClientHintSupport extends ChromeWorkerSpoofSupportBase {
    ChromeWorkerClientHintSupport(
            BrowserFingerprintProfile fingerprintProfile,
            ObjectMapper objectMapper,
            Logger logger,
            Function<String, String> surfaceScriptBuilder,
            Consumer<Throwable> failureSink,
            Function<BaseTarget, BaseTarget> auxiliaryBrowserFactory) {
        super(fingerprintProfile, objectMapper, logger, surfaceScriptBuilder, failureSink, auxiliaryBrowserFactory);
    }

    abstract CompletableFuture<Void> installOnAttachedTarget(
            SessionCdp cdp,
            String scope,
            String type,
            String targetId,
            String sessionId,
            String primaryPageTargetId,
            boolean pausedBackground,
            String workerScript,
            String surfaceScript,
            CompletableFuture<Void> clientHintListenerReady);

    void terminateTarget(String scope, String targetId, String event) {
        if (targetId.isBlank()) return;
        resumeDuringInstallationTargets.remove(targetId);
        localeBootstrapQuarantines.remove(targetId);
        CompletableFuture<Void> installation = targetInstallations.remove(targetId);
        boolean installationRemoved = installation != null;
        if (installation != null) {
            installation.completeExceptionally(new TargetLifecycleEndedException(event));
        }
        boolean sessionRemoved = false;
        for (Map.Entry<String, String> entry : sessionTargets.entrySet()) {
            if (!targetId.equals(entry.getValue())
                    || !sessionTargets.remove(entry.getKey(), entry.getValue())) {
                continue;
            }
            sessionRemoved = true;
            clientHintGuardSessions.remove(entry.getKey());
            sessionTargetOrigins.remove(entry.getKey());
            completePausedAsTerminated(entry.getKey(), event);
        }
        if (installationRemoved || sessionRemoved) {
            JavaDriverlessLogger.debug(logger,
                    "WorkerSpoof[{}]: lifecycle do target {} encerrado por {}",
                    scope, targetId, event);
        }
    }

    void terminateSession(
            String scope, String sessionId, String eventTargetId, String event) {
        if (sessionId.isBlank()) return;
        String trackedTargetId = sessionTargets.remove(sessionId);
        clientHintGuardSessions.remove(sessionId);
        sessionTargetOrigins.remove(sessionId);
        String targetId = eventTargetId.isBlank() ? trackedTargetId : eventTargetId;
        if (targetId != null) resumeDuringInstallationTargets.remove(targetId);
        if (targetId != null) localeBootstrapQuarantines.remove(targetId);
        CompletableFuture<Void> installation = targetId == null || targetId.isBlank()
                ? null : targetInstallations.remove(targetId);
        boolean installationRemoved = installation != null;
        if (installation != null) {
            installation.completeExceptionally(new TargetLifecycleEndedException(event));
        }
        boolean pausedRemoved = completePausedAsTerminated(sessionId, event);
        if (trackedTargetId != null || installationRemoved || pausedRemoved) {
            JavaDriverlessLogger.debug(logger,
                    "WorkerSpoof[{}]: lifecycle da sessão {} encerrado por {}",
                    scope, sessionId, event);
        }
    }

    private boolean completePausedAsTerminated(String sessionId, String event) {
        CompletableFuture<Void> paused = pausedSessions.remove(sessionId);
        return paused != null
                && paused.completeExceptionally(new TargetLifecycleEndedException(event));
    }

    CompletableFuture<Void> ensureClientHintRequestListener(
            String scope,
            String type,
            String targetId,
            String sessionId,
            BiFunction<String, BiConsumer<String, JsonNode>, CompletableFuture<Void>> addSessionListener,
            SessionCdp cdp) {
        return clientHintListenerRegistrations.computeIfAbsent(scope, ignored -> {
            final CompletableFuture<Void> registration;
            try {
                registration = addSessionListener.apply(
                        "Fetch.requestPaused",
                        (eventSessionId, params) -> handleClientHintRequestPaused(
                                cdp, scope, eventSessionId, params));
            } catch (Throwable failure) {
                return CompletableFuture.failedFuture(reportFailure(
                        scope, type, targetId, sessionId,
                        "Fetch.requestPaused listener", failure));
            }
            if (registration == null) {
                return CompletableFuture.failedFuture(reportFailure(
                        scope, type, targetId, sessionId,
                        "Fetch.requestPaused listener",
                        new IllegalStateException("Registro de listener retornou future nulo")));
            }
            return registration.handle((registered, failure) -> {
                if (failure != null) {
                    throw new java.util.concurrent.CompletionException(reportFailure(
                            scope, type, targetId, sessionId,
                            "Fetch.requestPaused listener", failure));
                }
                return null;
            });
        });
    }

    private void handleClientHintRequestPaused(
            SessionCdp cdp, String scope, String sessionId, JsonNode params) {
        if (sessionId == null
                || sessionId.isBlank()
                || !clientHintGuardSessions.contains(sessionId)) {
            return;
        }
        String targetId = sessionTargets.getOrDefault(sessionId, "");
        try {
            String requestId = params == null ? "" : params.path("requestId").asText("");
            if (requestId.isBlank()) {
                throw new IllegalStateException("Evento Fetch.requestPaused sem requestId");
            }

            Map<String, String> originalHeaders = new LinkedHashMap<>();
            JsonNode headers = params.path("request").path("headers");
            if (headers.isObject()) {
                headers.fields().forEachRemaining(
                        header -> originalHeaders.put(header.getKey(), header.getValue().asText()));
            }
            Map<String, String> rewrittenHeaders =
                    FingerprintClientHintGuard.rewritePresentHeaders(
                            originalHeaders, fingerprintProfile);
            Map<String, Object> continueArgs = new HashMap<>();
            continueArgs.put("requestId", requestId);
            if (!rewrittenHeaders.equals(originalHeaders)) {
                List<Map<String, String>> cdpHeaders = new ArrayList<>(rewrittenHeaders.size());
                rewrittenHeaders.forEach((name, value) -> cdpHeaders.add(
                        Map.of("name", name, "value", value)));
                continueArgs.put("headers", cdpHeaders);
            }
            continueClientHintRequest(
                    cdp, scope, targetId, sessionId, continueArgs);
        } catch (Throwable failure) {
            failClientHintRequestClosed(
                    cdp, scope, targetId, sessionId, "Fetch.requestPaused", failure);
        }
    }

    private void continueClientHintRequest(
            SessionCdp cdp,
            String scope,
            String targetId,
            String sessionId,
            Map<String, Object> continueArgs) {
        final CompletableFuture<JsonNode> continuation;
        try {
            continuation = cdp.exec("Fetch.continueRequest", continueArgs, sessionId);
        } catch (Throwable failure) {
            failClientHintRequestClosed(
                    cdp, scope, targetId, sessionId, "Fetch.continueRequest", failure);
            return;
        }
        if (continuation == null) {
            failClientHintRequestClosed(
                    cdp, scope, targetId, sessionId, "Fetch.continueRequest",
                    new IllegalStateException("Executor CDP retornou future nulo"));
            return;
        }
        continuation.whenComplete((ignored, failure) -> {
            if (failure != null) {
                failClientHintRequestClosed(
                        cdp, scope, targetId, sessionId, "Fetch.continueRequest", failure);
            }
        });
    }

    private void failClientHintRequestClosed(
            SessionCdp cdp,
            String scope,
            String targetId,
            String sessionId,
            String command,
            Throwable failure) {
        // Uma sessão encerrada entre o evento e o continue não deixa request observável nem deve
        // contaminar a próxima sessão com uma falha tardia.
        if (!clientHintGuardSessions.contains(sessionId)) {
            return;
        }
        IllegalStateException contextual = reportFailure(
                scope, "iframe", targetId, sessionId, command, failure);
        closeTargetFailClosed(cdp, scope, "iframe", targetId, sessionId)
                .whenComplete((ignored, closeFailure) -> {
                    if (closeFailure != null) {
                        contextual.addSuppressed(rootCause(closeFailure));
                    }
                });
    }

    CompletableFuture<Void> installationForTarget(
            SessionCdp cdp,
            String scope,
            String type,
            String targetId,
            String sessionId,
            String primaryPageTargetId,
            boolean pausedBackground,
            String workerScript,
            String surfaceScript,
            CompletableFuture<Void> clientHintListenerReady) {
        if (targetId.isBlank()) {
            return containInstallationFailure(
                    cdp, scope, type, targetId, sessionId,
                    installOnAttachedTarget(
                            cdp, scope, type, targetId, sessionId, primaryPageTargetId,
                            pausedBackground, workerScript, surfaceScript,
                            clientHintListenerReady));
        }

        CompletableFuture<Void> gate = new CompletableFuture<>();
        CompletableFuture<Void> existing = targetInstallations.putIfAbsent(targetId, gate);
        if (existing != null) {
            JavaDriverlessLogger.debug(logger,
                    "WorkerSpoof[{}]: target {} já possui instalação; sessão {} aguardará o mesmo gate",
                    scope, targetId, sessionId);
            return existing;
        }

        CompletableFuture<Void> attempt;
        try {
            attempt = installOnAttachedTarget(
                    cdp, scope, type, targetId, sessionId, primaryPageTargetId,
                    pausedBackground, workerScript, surfaceScript,
                    clientHintListenerReady);
        } catch (Throwable failure) {
            attempt = CompletableFuture.failedFuture(
                    reportFailure(scope, type, targetId, sessionId,
                            "attachedToTarget callback", failure));
        }
        containInstallationFailure(cdp, scope, type, targetId, sessionId, attempt)
                .whenComplete((ignored, failure) -> {
                    if (failure == null) {
                        gate.complete(null);
                    } else {
                        gate.completeExceptionally(failure);
                    }
                });
        return gate;
    }

    CompletableFuture<Void> containInstallationFailure(
            SessionCdp cdp,
            String scope,
            String type,
            String targetId,
            String sessionId,
            CompletableFuture<Void> installation) {
        CompletableFuture<Void> contained = new CompletableFuture<>();
        installation.whenComplete((ignored, failure) -> {
            if (failure == null) {
                contained.complete(null);
                return;
            }
            if (rootCause(failure) instanceof TargetLifecycleEndedException) {
                // Um worker efêmero pode encerrar legitimamente antes de Debugger.paused.
                // Não há target restante para injetar, resumir ou fechar, nem falha para publicar.
                contained.complete(null);
                return;
            }
            closeTargetFailClosed(cdp, scope, type, targetId, sessionId)
                    .whenComplete((closeIgnored, closeFailure) -> {
                        if (closeFailure != null) {
                            failure.addSuppressed(rootCause(closeFailure));
                        }
                        contained.completeExceptionally(failure);
                    });
        });
        return contained;
    }

    void resumeAfterInstallation(
            SessionCdp cdp, String scope, String type, String targetId, String sessionId,
            CompletableFuture<Void> installation) {
        installation.whenComplete((ignored, failure) -> {
            if (failure != null) return;
            CompletableFuture<Void> resume = dispatchObserved(
                    cdp, scope, type, targetId, sessionId,
                    "Runtime.runIfWaitingForDebugger", new HashMap<>());
            containInstallationFailure(cdp, scope, type, targetId, sessionId, resume);
        });
    }

    private CompletableFuture<Void> closeTargetFailClosed(
            SessionCdp cdp,
            String scope,
            String type,
            String targetId,
            String sessionId) {
        if (targetId.isBlank()) {
            JavaDriverlessLogger.error(logger,
                    "WorkerSpoof[{}]: target sem id permanecerá pausado após falha (type={}, sessionId={})",
                    scope, type, sessionId);
            return CompletableFuture.completedFuture(null);
        }
        Map<String, Object> closeArgs = new HashMap<>();
        closeArgs.put("targetId", targetId);
        try {
            SessionCdp closeCdp = browserRootCdp == null ? cdp : browserRootCdp;
            CompletableFuture<JsonNode> close = closeCdp.exec("Target.closeTarget", closeArgs, null);
            if (close == null) {
                return CompletableFuture.failedFuture(
                        new IllegalStateException("Executor CDP retornou future nulo ao fechar target " + targetId));
            }
            return close.<Void>thenApply(ignored -> {
                JavaDriverlessLogger.warn(logger,
                        "WorkerSpoof[{}]: target {} fechado sem resume após falha de spoof", scope, targetId);
                return null;
            });
        } catch (Throwable closeFailure) {
            return CompletableFuture.failedFuture(closeFailure);
        }
    }

}
