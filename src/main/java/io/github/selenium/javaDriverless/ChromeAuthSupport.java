package io.github.selenium.javaDriverless;

import com.fasterxml.jackson.databind.JsonNode;
import io.github.selenium.javaDriverless.logging.JavaDriverlessLogger;
import io.github.selenium.javaDriverless.types.BaseTarget;
import org.slf4j.Logger;

import java.net.IDN;
import java.net.URI;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

/** Coordena HTTP/proxy auth por origem exata e instala um único listener Fetch por sessão. */
final class ChromeAuthSupport {
    private final Logger logger;
    private final Map<AuthOrigin, Credentials> credentials = new ConcurrentHashMap<>();
    private CompletableFuture<Void> installation;
    private volatile boolean enabled;
    private long generation;

    ChromeAuthSupport(Logger logger) {
        this.logger = logger;
    }

    CompletableFuture<Void> setAuth(
            String username,
            String password,
            String configuredOrigin,
            Supplier<CompletableFuture<BaseTarget>> baseTargetSupplier) {
        if (username == null || username.isBlank() || password == null) {
            return CompletableFuture.failedFuture(
                    new IllegalArgumentException("Credenciais de autenticação inválidas"));
        }
        final AuthOrigin origin;
        try {
            origin = normalizeOrigin(configuredOrigin, true);
        } catch (IllegalArgumentException invalid) {
            return CompletableFuture.failedFuture(invalid);
        }
        credentials.put(origin, new Credentials(username, password));
        return ensureInstalled(baseTargetSupplier);
    }

    private synchronized CompletableFuture<Void> ensureInstalled(
            Supplier<CompletableFuture<BaseTarget>> baseTargetSupplier) {
        if (enabled) {
            return CompletableFuture.completedFuture(null);
        }
        if (installation != null) {
            return installation;
        }

        final CompletableFuture<Void> created;
        final long installationGeneration = generation;
        try {
            CompletableFuture<BaseTarget> baseTargetFuture = Objects.requireNonNull(
                    baseTargetSupplier.get(), "baseTargetSupplier retornou future nulo");
            created = baseTargetFuture.thenCompose(baseTarget ->
                    baseTarget.executeCdpCmd(
                                    "Fetch.enable",
                                    Map.of("handleAuthRequests", true),
                                    null)
                            .thenCompose(ignored -> baseTarget.addCdpListener(
                                    "Fetch.authRequired",
                                    event -> handleAuthRequired(baseTarget, event))));
        } catch (Throwable failure) {
            return CompletableFuture.failedFuture(failure);
        }

        installation = created;
        created.whenComplete((ignored, failure) -> {
            synchronized (ChromeAuthSupport.this) {
                if (generation == installationGeneration && installation == created) {
                    installation = null;
                    enabled = failure == null;
                }
            }
        });
        return created;
    }

    synchronized void resetForNewSession() {
        generation++;
        installation = null;
        enabled = false;
        credentials.clear();
    }

    private void handleAuthRequired(BaseTarget baseTarget, JsonNode event) {
        String requestId = event.path("requestId").asText("");
        if (requestId.isBlank()) {
            JavaDriverlessLogger.error(logger, "Fetch.authRequired sem requestId; desafio não pode ser concluído");
            return;
        }

        Map<String, Object> challengeResponse = new HashMap<>();
        challengeResponse.put("response", "CancelAuth");
        try {
            String rawOrigin = event.path("authChallenge").path("origin").asText("");
            Optional<AuthOrigin> origin = normalizeChallengeOrigin(rawOrigin);
            Credentials match = origin.map(credentials::get).orElse(null);
            if (match != null) {
                challengeResponse.put("response", "ProvideCredentials");
                challengeResponse.put("username", match.username());
                challengeResponse.put("password", match.password());
            }
        } catch (RuntimeException invalidChallenge) {
            JavaDriverlessLogger.warn(logger, "Fetch.authRequired recebeu origem inválida; desafio cancelado");
        }

        Map<String, Object> arguments = Map.of(
                "requestId", requestId,
                "authChallengeResponse", challengeResponse);
        try {
            CompletableFuture<JsonNode> dispatched = baseTarget.executeCdpCmd(
                    "Fetch.continueWithAuth", arguments, null);
            if (dispatched == null) {
                JavaDriverlessLogger.error(logger,
                        "Fetch.continueWithAuth retornou future nulo; desafio pode permanecer pausado");
                return;
            }
            dispatched.whenComplete((ignored, failure) -> {
                if (failure != null) {
                    JavaDriverlessLogger.error(logger, "Falha ao concluir desafio Fetch auth");
                }
            });
        } catch (Throwable failure) {
            JavaDriverlessLogger.error(logger, "Falha síncrona ao concluir desafio Fetch auth");
        }
    }

    static Optional<AuthOrigin> normalizeChallengeOrigin(String rawOrigin) {
        try {
            return Optional.of(normalizeOrigin(rawOrigin, false));
        } catch (IllegalArgumentException invalid) {
            return Optional.empty();
        }
    }

    static AuthOrigin normalizeOrigin(String rawOrigin, boolean allowImplicitHttp) {
        if (rawOrigin == null || rawOrigin.isBlank()) {
            throw invalidOrigin();
        }
        String trimmed = rawOrigin.trim();
        String candidate = trimmed.contains("://")
                ? trimmed
                : allowImplicitHttp ? "http://" + trimmed : trimmed;
        try {
            URI uri = URI.create(candidate);
            if (uri.getHost() == null
                    && uri.getRawAuthority() != null
                    && !uri.getRawAuthority().contains("@")
                    && !uri.getRawAuthority().contains("[")) {
                StringBuilder asciiCandidate = new StringBuilder()
                        .append(uri.getScheme())
                        .append("://")
                        .append(asciiAuthority(uri.getRawAuthority()));
                if (uri.getRawPath() != null) {
                    asciiCandidate.append(uri.getRawPath());
                }
                if (uri.getRawQuery() != null) {
                    asciiCandidate.append('?').append(uri.getRawQuery());
                }
                if (uri.getRawFragment() != null) {
                    asciiCandidate.append('#').append(uri.getRawFragment());
                }
                uri = URI.create(asciiCandidate.toString());
            }
            String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
            if (!(scheme.equals("http") || scheme.equals("https"))
                    || uri.getRawUserInfo() != null
                    || uri.getHost() == null
                    || uri.getRawQuery() != null
                    || uri.getRawFragment() != null
                    || (uri.getRawPath() != null
                            && !uri.getRawPath().isEmpty()
                            && !uri.getRawPath().equals("/"))) {
                throw invalidOrigin();
            }
            int port = uri.getPort();
            if (port < 0) {
                port = scheme.equals("https") ? 443 : 80;
            }
            if (port < 1 || port > 65_535) {
                throw invalidOrigin();
            }
            String rawHost = uri.getHost();
            if (rawHost.startsWith("[") && rawHost.endsWith("]")) {
                rawHost = rawHost.substring(1, rawHost.length() - 1);
            }
            String host = rawHost.contains(":")
                    ? rawHost.toLowerCase(Locale.ROOT)
                    : IDN.toASCII(rawHost).toLowerCase(Locale.ROOT);
            return new AuthOrigin(scheme, host, port);
        } catch (RuntimeException invalid) {
            throw invalidOrigin();
        }
    }

    private static String asciiAuthority(String authority) {
        int portSeparator = authority.lastIndexOf(':');
        String host = portSeparator < 0 ? authority : authority.substring(0, portSeparator);
        String port = portSeparator < 0 ? "" : authority.substring(portSeparator);
        if (host.chars().anyMatch(c -> c == 0x00DF || c == 0x03C2 || c == 0x200C || c == 0x200D)) { // desvios IDNA2003 x UTS46: falha fechada
            throw invalidOrigin();
        }
        return IDN.toASCII(host) + port;
    }

    private static IllegalArgumentException invalidOrigin() {
        return new IllegalArgumentException(
                "Origem de autenticação inválida; use scheme://host:port ou host:port");
    }

    record AuthOrigin(String scheme, String host, int port) {}

    private record Credentials(String username, String password) {}
}
