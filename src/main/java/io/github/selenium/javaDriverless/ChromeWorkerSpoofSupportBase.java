package io.github.selenium.javaDriverless;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.selenium.javaDriverless.logging.JavaDriverlessLogger;
import io.github.selenium.javaDriverless.types.BaseTarget;
import io.github.selenium.javaDriverless.types.BrowserFingerprintProfile;
import org.slf4j.Logger;

import java.net.URI;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BiConsumer;
import java.util.function.BiFunction;
import java.util.function.Consumer;
import java.util.function.Function;

/** Shared state, dispatch observation and static target policy for worker spoofing. */
abstract class ChromeWorkerSpoofSupportBase {
    /** Sinal interno: o target deixou de existir, portanto não houve falha de spoof. */
    static final class TargetLifecycleEndedException extends RuntimeException {
        TargetLifecycleEndedException(String event) {
            super("Target encerrado durante instalação do spoof: " + event);
        }
    }

    static final Set<String> PRIVILEGED_SCHEMES = Set.of(
            "chrome-extension", "chrome", "devtools");

    final BrowserFingerprintProfile fingerprintProfile;
    final ObjectMapper objectMapper;
    final Logger logger;
    final Function<String, String> surfaceScriptBuilder;
    final Consumer<Throwable> failureSink;
    final Function<BaseTarget, BaseTarget> auxiliaryBrowserFactory;
    final Map<String, CompletableFuture<Void>> targetInstallations = new ConcurrentHashMap<>();
    final Map<String, CompletableFuture<Void>> pausedSessions = new ConcurrentHashMap<>();
    final Map<String, String> sessionTargets = new ConcurrentHashMap<>();
    final Map<String, String> sessionTargetOrigins = new ConcurrentHashMap<>();
    final Map<String, Long> localeBootstrapQuarantines = new ConcurrentHashMap<>();
    final Set<String> clientHintGuardSessions = ConcurrentHashMap.newKeySet();
    final Set<String> resumeDuringInstallationTargets = ConcurrentHashMap.newKeySet();
    final Map<String, CompletableFuture<Void>> clientHintListenerRegistrations =
            new ConcurrentHashMap<>();
    volatile BaseTarget sharedWorkerBrowser;
    volatile SessionCdp browserRootCdp;

    /** Exec CDP roteado para uma sessão anexada (sessionId), abstraindo Target vs BaseTarget. */
    @FunctionalInterface
    interface SessionCdp {
        CompletableFuture<JsonNode> exec(String cmd, Map<String, Object> args, String sessionId);
    }

    static String privilegedScheme(JsonNode targetInfo) {
        String rawUrl = targetInfo == null ? "" : targetInfo.path("url").asText("");
        if (rawUrl.isBlank()) return "";
        try {
            String scheme = URI.create(rawUrl).getScheme();
            if (scheme == null) return "";
            String normalized = scheme.toLowerCase(Locale.ROOT);
            return PRIVILEGED_SCHEMES.contains(normalized) ? normalized : "";
        } catch (IllegalArgumentException ignored) {
            // URL inválida não recebe bypass: segue o caminho fail-closed normal.
            return "";
        }
    }

    static String safeTargetOrigin(JsonNode targetInfo) {
        String rawUrl = targetInfo == null ? "" : targetInfo.path("url").asText("");
        if (rawUrl.isBlank()) return "<empty>";
        try {
            URI uri = URI.create(rawUrl);
            String scheme = uri.getScheme();
            if (scheme == null || scheme.isBlank()) return "<relative>";
            String normalized = scheme.toLowerCase(Locale.ROOT);
            if (("http".equals(normalized) || "https".equals(normalized))
                    && uri.getHost() != null && !uri.getHost().isBlank()) {
                return "<web>";
            }
            // Opaque/fenced/blob/data targets may carry identifiers or embedded URLs. Keep only
            // a non-reversible category: runtime errors must not reveal visited domains or IPs.
            return "<opaque>";
        } catch (IllegalArgumentException ignored) {
            return "<invalid>";
        }
    }

    void resumePrivilegedTargetIfPaused(
            SessionCdp cdp,
            String scope,
            String sessionId,
            String scheme,
            boolean waitingForDebugger) {
        JavaDriverlessLogger.debug(logger,
                "WorkerSpoof[{}]: target privilegiado fora da superfície web ignorado "
                        + "(scheme={}, waitingForDebugger={})",
                scope, scheme, waitingForDebugger);
        if (!waitingForDebugger) return;
        if (sessionId.isBlank()) {
            JavaDriverlessLogger.debug(logger,
                    "WorkerSpoof[{}]: target privilegiado pausado sem sessão; scheme={}",
                    scope, scheme);
            return;
        }
        try {
            CompletableFuture<JsonNode> resume = cdp.exec(
                    "Runtime.runIfWaitingForDebugger", new HashMap<>(), sessionId);
            if (resume == null) {
                JavaDriverlessLogger.debug(logger,
                        "WorkerSpoof[{}]: resume de target privilegiado retornou future nulo; scheme={}",
                        scope, scheme);
                return;
            }
            resume.whenComplete((ignored, failure) -> {
                if (failure != null) {
                    JavaDriverlessLogger.debug(logger,
                            "WorkerSpoof[{}]: resume de target privilegiado falhou; scheme={}, error={}",
                            scope, scheme, rootCause(failure).getClass().getSimpleName());
                }
            });
        } catch (Throwable failure) {
            JavaDriverlessLogger.debug(logger,
                    "WorkerSpoof[{}]: resume de target privilegiado lançou erro; scheme={}, error={}",
                    scope, scheme, rootCause(failure).getClass().getSimpleName());
        }
    }

    static List<Map<String, Object>> autoAttachFilter(String scope) {
        if ("browser-shared".equals(scope)) {
            return List.of(
                    Map.of("type", "shared_worker"),
                    Map.of("exclude", true));
        }
        if ("browser-native-pages".equals(scope)) {
            return List.of(Map.of("type", "page"), Map.of("exclude", true));
        }
        if ("browser-native-oopif".equals(scope)) {
            return List.of(
                    Map.of("type", "service_worker"),
                    Map.of("type", "iframe"),
                    Map.of("exclude", true));
        }
        if (scope.startsWith("browser")) {
            return List.of(
                    Map.of("type", "service_worker"),
                    Map.of("type", "iframe"),
                    Map.of("exclude", true));
        }
        return List.of(
                Map.of("type", "service_worker", "exclude", true),
                Map.of("type", "shared_worker", "exclude", true),
                Map.of("type", "iframe", "exclude", true),
                Map.of());
    }

    CompletableFuture<Void> thenDispatch(
            CompletableFuture<Void> previous,
            SessionCdp cdp,
            String scope,
            String type,
            String targetId,
            String sessionId,
            String command,
            Map<String, Object> arguments) {
        return previous.thenCompose(ignored -> {
            if (trackedLifecycleEnded(targetId, sessionId)) {
                return CompletableFuture.failedFuture(
                        new TargetLifecycleEndedException(command + " cancelado"));
            }
            return dispatchObserved(cdp, scope, type, targetId, sessionId, command, arguments);
        });
    }

    CompletableFuture<Void> dispatchObserved(
            SessionCdp cdp,
            String scope,
            String type,
            String targetId,
            String sessionId,
            String command,
            Map<String, Object> arguments) {
        return dispatchObservedResult(cdp, scope, type, targetId, sessionId, command, arguments)
                .thenApply(ignored -> null);
    }

    CompletableFuture<JsonNode> dispatchObservedResult(
            SessionCdp cdp,
            String scope,
            String type,
            String targetId,
            String sessionId,
            String command,
            Map<String, Object> arguments) {
        if (trackedLifecycleEnded(targetId, sessionId)) {
            return CompletableFuture.failedFuture(
                    new TargetLifecycleEndedException(command + " cancelado"));
        }
        try {
            CompletableFuture<JsonNode> operation = cdp.exec(command, arguments, sessionId);
            if (operation == null) {
                return CompletableFuture.failedFuture(
                        reportFailure(scope, type, targetId, sessionId, command,
                                new IllegalStateException("Executor CDP retornou future nulo")));
            }
            CompletableFuture<JsonNode> observed = new CompletableFuture<>();
            operation.orTimeout(10, java.util.concurrent.TimeUnit.SECONDS)
                    .whenComplete((result, failure) -> {
                if (trackedLifecycleEnded(targetId, sessionId)) {
                    observed.completeExceptionally(
                            new TargetLifecycleEndedException(command + " concluído após lifecycle"));
                } else if (failure != null) {
                    observed.completeExceptionally(
                            reportFailure(scope, type, targetId, sessionId, command, failure));
                } else if ("Runtime.evaluate".equals(command)
                        && result != null
                        && result.hasNonNull("exceptionDetails")) {
                    observed.completeExceptionally(
                            reportFailure(scope, type, targetId, sessionId, command,
                                    new IllegalStateException(
                                            "Runtime.evaluate retornou exceptionDetails")));
                } else {
                    observed.complete(result);
                }
            });
            return observed;
        } catch (Throwable failure) {
            if (trackedLifecycleEnded(targetId, sessionId)) {
                return CompletableFuture.failedFuture(
                        new TargetLifecycleEndedException(command + " lançou após lifecycle"));
            }
            return CompletableFuture.failedFuture(
                    reportFailure(scope, type, targetId, sessionId, command, failure));
        }
    }

    boolean isTargetSessionActive(String targetId, String sessionId) {
        return targetId != null
                && !targetId.isBlank()
                && sessionId != null
                && !sessionId.isBlank()
                && targetId.equals(sessionTargets.get(sessionId));
    }

    private boolean trackedLifecycleEnded(String targetId, String sessionId) {
        return targetId != null
                && !targetId.isBlank()
                && sessionId != null
                && !sessionId.isBlank()
                && !isTargetSessionActive(targetId, sessionId);
    }

    IllegalStateException reportFailure(
            String scope,
            String type,
            String targetId,
            String sessionId,
            String command,
            Throwable failure) {
        Throwable root = rootCause(failure);
        IllegalStateException contextual = new IllegalStateException(
                "WorkerSpoof[" + scope + "] falhou em " + command
                        + " (type=" + type + ", targetId=" + targetId
                        + ", sessionId=" + sessionId
                        + ", origin=" + sessionTargetOrigins.getOrDefault(sessionId, "<unknown>")
                        + ")",
                root);
        JavaDriverlessLogger.error(logger, "{}: {}", contextual.getMessage(), root.getMessage());
        try {
            failureSink.accept(contextual);
        } catch (Throwable sinkFailure) {
            JavaDriverlessLogger.error(logger, "WorkerSpoof: failure sink falhou: {}", sinkFailure.getMessage());
        }
        return contextual;
    }

    CompletableFuture<Void> close() {
        TargetLifecycleEndedException closed =
                new TargetLifecycleEndedException("ChromeWorkerSpoofSupport.close");
        targetInstallations.values().forEach(gate -> gate.completeExceptionally(closed));
        pausedSessions.values().forEach(gate -> gate.completeExceptionally(closed));
        targetInstallations.clear();
        pausedSessions.clear();
        sessionTargets.clear();
        sessionTargetOrigins.clear();
        localeBootstrapQuarantines.clear();
        clientHintGuardSessions.clear();
        resumeDuringInstallationTargets.clear();
        clientHintListenerRegistrations.clear();
        BaseTarget auxiliary = sharedWorkerBrowser;
        sharedWorkerBrowser = null;
        browserRootCdp = null;
        return auxiliary == null ? CompletableFuture.completedFuture(null) : auxiliary.close();
    }

    static Throwable rootCause(Throwable failure) {
        Throwable current = failure;
        while (current.getCause() != null && current.getCause() != current) {
            current = current.getCause();
        }
        return current;
    }



    ChromeWorkerSpoofSupportBase(
            BrowserFingerprintProfile fingerprintProfile,
            ObjectMapper objectMapper,
            Logger logger,
            Function<String, String> surfaceScriptBuilder,
            Consumer<Throwable> failureSink,
            Function<BaseTarget, BaseTarget> auxiliaryBrowserFactory) {
        this.fingerprintProfile = fingerprintProfile;
        this.objectMapper = objectMapper;
        this.logger = logger;
        this.surfaceScriptBuilder = surfaceScriptBuilder;
        this.failureSink = failureSink;
        this.auxiliaryBrowserFactory = auxiliaryBrowserFactory;
    }
}
