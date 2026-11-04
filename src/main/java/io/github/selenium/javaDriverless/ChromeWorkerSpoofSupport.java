package io.github.selenium.javaDriverless;

import com.fasterxml.jackson.databind.JsonNode;
import io.github.selenium.javaDriverless.cdp.exceptions.CDPException;
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
final class ChromeWorkerSpoofSupport extends ChromeWorkerClientHintSupport {
    private static final int MAX_LOCALE_BOOTSTRAP_QUARANTINES = 16;
    private static final int MAX_LOCALE_BOOTSTRAP_ATTEMPTS = 8;
    private static final long MAX_LOCALE_BOOTSTRAP_RETRY_DELAY_MILLIS = 1_000L;
    private final boolean nativeOopifOnly;
    private final boolean nativeProcessLocaleBaselineConfigured;
    long localeBootstrapTtlMillis = 5_000L;

    ChromeWorkerSpoofSupport(
            BrowserFingerprintProfile fingerprintProfile,
            ObjectMapper objectMapper,
            Logger logger,
            Function<String, String> surfaceScriptBuilder,
            Consumer<Throwable> failureSink) {
        this(fingerprintProfile, objectMapper, logger, surfaceScriptBuilder, failureSink,
                browser -> browser, false, false);
    }

    ChromeWorkerSpoofSupport(
            BrowserFingerprintProfile fingerprintProfile,
            ObjectMapper objectMapper,
            Logger logger,
            Function<String, String> surfaceScriptBuilder,
            Consumer<Throwable> failureSink, Function<BaseTarget, BaseTarget> auxiliaryBrowserFactory) {
        this(fingerprintProfile, objectMapper, logger, surfaceScriptBuilder, failureSink,
                auxiliaryBrowserFactory, false, false);
    }

    ChromeWorkerSpoofSupport(
            BrowserFingerprintProfile fingerprintProfile,
            ObjectMapper objectMapper,
            Logger logger,
            Function<String, String> surfaceScriptBuilder,
            Consumer<Throwable> failureSink,
            Function<BaseTarget, BaseTarget> auxiliaryBrowserFactory,
            boolean nativeProcessLocaleBaselineConfigured) {
        this(fingerprintProfile, objectMapper, logger, surfaceScriptBuilder, failureSink,
                auxiliaryBrowserFactory, false, nativeProcessLocaleBaselineConfigured);
    }

    private ChromeWorkerSpoofSupport(
            BrowserFingerprintProfile fingerprintProfile,
            ObjectMapper objectMapper,
            Logger logger,
            Function<String, String> surfaceScriptBuilder,
            Consumer<Throwable> failureSink,
            Function<BaseTarget, BaseTarget> auxiliaryBrowserFactory,
            boolean nativeOopifOnly,
            boolean nativeProcessLocaleBaselineConfigured) {
        super(fingerprintProfile, objectMapper, logger, surfaceScriptBuilder, failureSink, auxiliaryBrowserFactory);
        this.nativeOopifOnly = nativeOopifOnly;
        this.nativeProcessLocaleBaselineConfigured = nativeProcessLocaleBaselineConfigured;
    }

    static ChromeWorkerSpoofSupport nativeOopifOverrides(
            BrowserFingerprintProfile fingerprintProfile,
            ObjectMapper objectMapper,
            Logger logger,
            Consumer<Throwable> failureSink,
            Function<BaseTarget, BaseTarget> auxiliaryBrowserFactory,
            boolean nativeProcessLocaleBaselineConfigured) {
        return new ChromeWorkerSpoofSupport(
                fingerprintProfile, objectMapper, logger, ignored -> "", failureSink,
                auxiliaryBrowserFactory, true, nativeProcessLocaleBaselineConfigured);
    }

    CompletableFuture<Void> install(Target page, BaseTarget browser) {
        if (Boolean.parseBoolean(System.getenv().getOrDefault("JDL_WORKER_AUTOATTACH_DISABLE", "false"))) {
            return CompletableFuture.failedFuture(new IllegalStateException(
                    "Worker auto-attach é etapa essencial e não pode ser desativado em sessão saudável"));
        }
        browserRootCdp = (cmd, args, sessionId) -> browser.executeCdpCmd(cmd, args, null, sessionId);
        if (nativeOopifOnly) {
            return ChromeMinimalCdpOopifOverrides.installScopes(this, page, browser);
        }
        final String profileJson;
        try {
            profileJson = objectMapper.writeValueAsString(fingerprintProfile);
        } catch (Exception e) {
            JavaDriverlessLogger.warn(logger,
                    "WorkerSpoof: falha ao serializar profile; worker scope ficará sem spoof: {}", e.getMessage());
            return CompletableFuture.failedFuture(
                    new RuntimeException("Falha ao serializar profile para o spoof de workers", e));
        }
        final String workerScript = SeleniumProxyWorkerSurfaceScripts.build(profileJson);
        // Surface script completo para iframes OOP / popups, que são targets/processos separados e
        // NÃO herdam o Page.addScriptToEvaluateOnNewDocument do top.
        final String surfaceScript = surfaceScriptBuilder.apply(profileJson);
        final String primaryPageTargetId = page.getId() == null ? "" : page.getId();

        // Browser-related: service workers e OOPIFs são anunciados nessa conexão. O teste
        // live com site-per-process comprovou que setAutoAttach apenas no page target não recebe
        // Target.attachedToTarget do iframe cross-site.
        CompletableFuture<Void> browserScope = installWorkerSpoofOnScope(
                "browser", primaryPageTargetId, workerScript, surfaceScript,
                browser::addCdpListener,
                browser::addCdpSessionListener,
                (cmd, args, sessionId) -> browser.executeCdpCmd(cmd, args, null, sessionId));
        // SharedWorker criado de blob não é reportado por Target.autoAttachRelated no Chrome 150.
        // Target.setAutoAttach global o captura, mas substituiria a política related/OOPIF se fosse
        // enviado pela mesma conexão. Uma conexão CDP independente mantém os dois gates ativos.
        BaseTarget sharedBrowser = auxiliaryBrowserFactory.apply(browser);
        if (sharedBrowser == null) {
            return CompletableFuture.failedFuture(new IllegalStateException(
                    "Conexão auxiliar ausente para o spoof de SharedWorker"));
        }
        if (sharedBrowser != browser) {
            sharedWorkerBrowser = sharedBrowser;
        }
        CompletableFuture<Void> sharedWorkerScope = installWorkerSpoofOnScope(
                "browser-shared", primaryPageTargetId, workerScript, surfaceScript,
                sharedBrowser::addCdpListener,
                sharedBrowser::addCdpSessionListener,
                (cmd, args, sessionId) -> sharedBrowser.executeCdpCmd(cmd, args, null, sessionId));
        // Page-level: alcança dedicated workers e demais targets relacionados à página.
        CompletableFuture<Void> pageScope = installWorkerSpoofOnScope(
                "page", primaryPageTargetId, workerScript, surfaceScript,
                page::addCdpListener,
                page::addCdpSessionListener,
                (cmd, args, sessionId) -> page.executeCdpCmd(cmd, args, null, sessionId));
        CompletableFuture<Void> installed = CompletableFuture.allOf(
                browserScope, sharedWorkerScope, pageScope);
        installed.whenComplete((ignored, failure) -> {
            if (failure != null && sharedBrowser != browser) {
                sharedBrowser.close();
            }
        });
        return installed;
    }

    /**
     * Adiciona uma page raiz criada depois do startup sem duplicar os listeners da conexão
     * browser-level. {@code Target.autoAttachRelated} mantém as raízes monitoradas, enquanto o
     * page target recebe seu auto-attach próprio para dedicated workers.
     */
    CompletableFuture<Void> installRelatedPage(Target page, BaseTarget browser) {
        String targetId = page == null || page.getId() == null ? "" : page.getId().trim();
        if (targetId.isBlank() || browser == null) {
            return CompletableFuture.failedFuture(
                    new IllegalArgumentException("Nova page sem targetId para worker/OOPIF spoof"));
        }
        if (nativeOopifOnly) {
            return ChromeMinimalCdpOopifOverrides.installRelatedPage(
                    this, page, browser, targetId);
        }
        final String profileJson;
        try {
            profileJson = objectMapper.writeValueAsString(fingerprintProfile);
        } catch (Exception failure) {
            return CompletableFuture.failedFuture(
                    new IllegalStateException("Falha ao serializar perfil da nova page", failure));
        }
        String workerScript = SeleniumProxyWorkerSurfaceScripts.build(profileJson);
        String surfaceScript = surfaceScriptBuilder.apply(profileJson);

        CompletableFuture<Void> pageScope = installWorkerSpoofOnScope(
                "page:" + targetId, targetId, workerScript, surfaceScript,
                page::addCdpListener,
                page::addCdpSessionListener,
                (cmd, args, sessionId) -> page.executeCdpCmd(cmd, args, null, sessionId));

        Map<String, Object> related = new HashMap<>();
        related.put("targetId", targetId);
        related.put("waitForDebuggerOnStart", true);
        related.put("filter", autoAttachFilter("browser"));
        CompletableFuture<Void> browserRoot = browser.executeCdpCmd(
                        "Target.autoAttachRelated", related, null, null)
                .thenApply(ignored -> null);
        return CompletableFuture.allOf(browserRoot, pageScope);
    }

    CompletableFuture<Void> installWorkerSpoofOnScope(
            String scope, String primaryPageTargetId, String workerScript, String surfaceScript,
            BiFunction<String, Consumer<JsonNode>, CompletableFuture<Void>> addListener,
            BiFunction<String, BiConsumer<String, JsonNode>, CompletableFuture<Void>> addSessionListener,
            SessionCdp cdp) {
        CompletableFuture<Void> pausedRegistration = addSessionListener.apply(
                "Debugger.paused", (eventSessionId, params) -> {
                    CompletableFuture<Void> waiter = pausedSessions.remove(eventSessionId);
                    if (waiter != null) waiter.complete(null);
                });
        CompletableFuture<Void> attachedRegistration = addListener.apply("Target.attachedToTarget", params -> {
            JsonNode sessionNode = params.get("sessionId");
            JsonNode info = params.get("targetInfo");
            String type = info != null ? info.path("type").asText("") : "";
            String sessionId = sessionNode == null ? "" : sessionNode.asText("");
            boolean waitingForDebugger = params.path("waitingForDebugger").asBoolean(false);
            String privilegedScheme = privilegedScheme(info);
            if (!privilegedScheme.isBlank()) {
                resumePrivilegedTargetIfPaused(
                        cdp, scope, sessionId, privilegedScheme, waitingForDebugger);
                return;
            }
            String targetId = info != null ? info.path("targetId").asText("") : "";
            String openerId = info != null ? info.path("openerId").asText("") : "";
            if (sessionNode == null || sessionNode.asText("").isBlank()) {
                reportFailure(scope, type, targetId, "", "Target.attachedToTarget",
                        new IllegalStateException("Evento attachedToTarget sem sessionId"));
                return;
            }
            sessionTargets.put(sessionId, targetId);
            sessionTargetOrigins.put(sessionId, safeTargetOrigin(info));
            JavaDriverlessLogger.debug(logger,
                    "WorkerSpoof[{}]: attached type={} targetId={} waitingForDebugger={}",
                    scope, type, targetId, waitingForDebugger);
            if (nativeOopifOnly && !ChromeMinimalCdpOopifOverrides.isManagedTarget(
                    type, targetId, primaryPageTargetId, openerId)) {
                CompletableFuture<Void> resume = ChromeMinimalCdpOopifOverrides.resumeUnmodifiedTarget(
                        this, cdp, scope, type, targetId, sessionId, primaryPageTargetId, waitingForDebugger);
                containInstallationFailure(cdp, scope, type, targetId, sessionId, resume);
                return;
            }
            boolean resumeDuringInstallation = nativeOopifOnly && waitingForDebugger
                    && ChromeMinimalCdpOopifOverrides.requiresResumeDuringInstallation(type, info);
            if (resumeDuringInstallation) resumeDuringInstallationTargets.add(targetId);
            boolean backgroundWorker = "service_worker".equals(type) || "shared_worker".equals(type);
            boolean dedicatedWorker = "worker".equals(type);
            CompletableFuture<Void> clientHintListenerReady = "iframe".equals(type)
                    && !nativeOopifOnly
                    ? ensureClientHintRequestListener(
                            scope, type, targetId, sessionId, addSessionListener, cdp)
                    : CompletableFuture.completedFuture(null);
            if (backgroundWorker) {
                if (!waitingForDebugger) {
                    installationForTarget(
                            cdp, scope, type, targetId, sessionId, primaryPageTargetId,
                            false, workerScript, surfaceScript, clientHintListenerReady);
                    return;
                }
                // Enviar Runtime.evaluate diretamente neste instante derruba a sessão (-32001).
                // O gate de debugger cria o contexto e para antes do primeiro script observável.
                installationForTarget(
                        cdp, scope, type, targetId, sessionId, primaryPageTargetId,
                        true, workerScript, surfaceScript, clientHintListenerReady);
                return;
            }
            if (dedicatedWorker && waitingForDebugger) {
                installationForTarget(
                        cdp, scope, type, targetId, sessionId, primaryPageTargetId,
                        true, workerScript, surfaceScript, clientHintListenerReady);
                return;
            }
            CompletableFuture<Void> installation = installationForTarget(
                    cdp, scope, type, targetId, sessionId, primaryPageTargetId,
                    false, workerScript, surfaceScript, clientHintListenerReady);
            if (!waitingForDebugger) {
                return;
            }
            if (resumeDuringInstallation) {
                installation.whenComplete((ignored, failure) ->
                        resumeDuringInstallationTargets.remove(targetId));
                return;
            }
            // Cada sessão observadora pausada recebe exatamente um resume, somente depois da
            // instalação compartilhada; qualquer rejeição é contida fechando o target.
            resumeAfterInstallation(cdp, scope, type, targetId, sessionId, installation);
        });
        CompletableFuture<Void> destroyedRegistration = addListener.apply("Target.targetDestroyed", params -> {
            String targetId = params == null ? "" : params.path("targetId").asText("");
            terminateTarget(scope, targetId, "Target.targetDestroyed");
        });
        CompletableFuture<Void> detachedRegistration = addListener.apply("Target.detachedFromTarget", params -> {
            String sessionId = params == null ? "" : params.path("sessionId").asText("");
            String targetId = params == null ? "" : params.path("targetId").asText("");
            terminateSession(scope, sessionId, targetId, "Target.detachedFromTarget");
        });
        CompletableFuture<Void> targetInfoRegistration = addListener.apply(
                "Target.targetInfoChanged", params -> {
                    JsonNode info = params == null ? null : params.get("targetInfo");
                    String targetId = info == null ? "" : info.path("targetId").asText("");
                    String originCategory = safeTargetOrigin(info);
                    if (targetId.isBlank() || "<empty>".equals(originCategory)) return;
                    for (Map.Entry<String, String> entry : sessionTargets.entrySet()) {
                        if (targetId.equals(entry.getValue())) {
                            sessionTargetOrigins.put(entry.getKey(), originCategory);
                        }
                    }
                });
        return CompletableFuture.allOf(
                pausedRegistration, attachedRegistration, destroyedRegistration,
                detachedRegistration, targetInfoRegistration)
                .thenCompose(v -> {
            Map<String, Object> autoAttach = new HashMap<>();
            // true: pausa o worker no início para que enable→evaluate(spoof)→runIfWaitingForDebugger
            // (resume) apliquem o spoof ANTES do código do worker rodar. Com false havia corrida —
            // service/module/nested workers liam GPU/cores reais antes da injeção (mismatch detectável).
            // Sessões não-worker (página/iframe OOP) também pausam e são resumidas no ramo `else` acima.
            autoAttach.put("waitForDebuggerOnStart", true);
            // O browser related cuida de service workers/OOPIFs, a conexão browser-shared cuida
            // só de SharedWorker e o page target cuida de dedicated workers. Sem filtros, um
            // mesmo target pode receber
            // duas sessões pausadas e dois resumes concorrentes, invalidando a sessão
            // do service worker antes da injeção terminar.
            autoAttach.put("filter", autoAttachFilter(scope));
            String command;
            if ("browser-shared".equals(scope) || "browser-native-pages".equals(scope)) {
                autoAttach.put("autoAttach", true);
                autoAttach.put("flatten", true);
                command = "Target.setAutoAttach";
            } else if (scope.startsWith("browser")) {
                // setAutoAttach no browser target não acompanha os filhos OOPIF da página. O CDP
                // fornece autoAttachRelated exatamente para monitorar frames/workers relacionados
                // a um target raiz e pausá-los antes da primeira execução observável.
                if (primaryPageTargetId == null || primaryPageTargetId.isBlank()) {
                    return CompletableFuture.failedFuture(new IllegalStateException(
                            "Target principal ausente para Target.autoAttachRelated"));
                }
                autoAttach.put("targetId", primaryPageTargetId);
                command = "Target.autoAttachRelated";
            } else {
                autoAttach.put("autoAttach", true);
                autoAttach.put("flatten", true);
                command = "Target.setAutoAttach";
            }
            return cdp.exec(command, autoAttach, null)
                    .<Void>thenApply(r -> {
                        JavaDriverlessLogger.debug(logger, "WorkerSpoof[{}]: {} ativo", scope, command);
                        return null;
                    })
                    .whenComplete((ignored, ex) -> {
                        if (ex != null) {
                            JavaDriverlessLogger.warn(logger,
                                    "WorkerSpoof[{}]: {} indisponível: {}",
                                    scope, command, ex.getMessage());
                        }
                    });
        });
    }

    @Override
    CompletableFuture<Void> installOnAttachedTarget(
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
        CompletableFuture<Void> chain = clientHintListenerReady;
        if (nativeOopifOnly) {
            return ChromeMinimalCdpOopifOverrides.install(
                    this, cdp, scope, type, targetId, sessionId, chain);
        }
        // O target principal já recebeu os overrides essenciais e o surface script durante
        // startSession(). O auto-attach no escopo browser pode observar esse mesmo target de
        // novo; reaplicar locale/timezone nele faz o Chrome rejeitar a sessão com -32000.
        // O target continua sendo resumido pelo caller, sem instalar wrappers em duplicidade.
        if ("page".equals(type)
                && primaryPageTargetId != null
                && !primaryPageTargetId.isBlank()
                && primaryPageTargetId.equals(targetId)) {
            JavaDriverlessLogger.debug(logger,
                    "WorkerSpoof[{}]: target principal {} já inicializado; apenas resume",
                    scope, targetId);
            return chain;
        }
        boolean isWorker = "worker".equals(type)
                || "service_worker".equals(type)
                || "shared_worker".equals(type);
        if (isWorker) {
            Map<String, Object> evalArgs = new HashMap<>();
            evalArgs.put("expression", workerScript);
            evalArgs.put("includeCommandLineAPI", false);
            evalArgs.put("awaitPromise", false);
            if (pausedBackground) {
                // Workers podem ainda não possuir um Runtime global utilizável no evento
                // attachedToTarget. Um instrumentation breakpoint cria o contexto e para antes da
                // primeira instrução do script do site; só então o spoof é avaliado e o worker solto.
                chain = thenDispatch(chain, cdp, scope, type, targetId, sessionId,
                        "Debugger.enable", new HashMap<>());
                CompletableFuture<Void> paused = new CompletableFuture<>();
                pausedSessions.put(sessionId, paused);
                CompletableFuture<JsonNode> breakpoint = chain.thenCompose(ignored -> dispatchObservedResult(
                        cdp, scope, type, targetId, sessionId,
                        "Debugger.setInstrumentationBreakpoint",
                        new HashMap<>(Map.of("instrumentation", "beforeScriptExecution"))));
                chain = breakpoint.thenApply(ignored -> null);
                chain = thenDispatch(chain, cdp, scope, type, targetId, sessionId,
                        "Runtime.runIfWaitingForDebugger", new HashMap<>());
                chain = chain.thenCompose(ignored -> paused.orTimeout(10, java.util.concurrent.TimeUnit.SECONDS)
                        .exceptionallyCompose(failure -> {
                            Throwable root = rootCause(failure);
                            if (root instanceof TargetLifecycleEndedException) {
                                return CompletableFuture.failedFuture(root);
                            }
                            return CompletableFuture.failedFuture(reportFailure(
                                    scope, type, targetId, sessionId, "Debugger.paused", failure));
                        }));
                if (workerScript != null && !workerScript.isBlank()) {
                    chain = thenDispatch(chain, cdp, scope, type, targetId, sessionId,
                            "Runtime.evaluate", evalArgs);
                }
                if (fingerprintProfile.timezoneId != null && !fingerprintProfile.timezoneId.isBlank()) {
                    chain = thenDispatch(chain, cdp, scope, type, targetId, sessionId,
                            "Emulation.setTimezoneOverride",
                            Map.of("timezoneId", fingerprintProfile.timezoneId));
                }
                chain = chain.thenCompose(ignored -> breakpoint.thenCompose(result -> {
                    String breakpointId = result == null ? "" : result.path("breakpointId").asText("");
                    if (breakpointId.isBlank()) {
                        return CompletableFuture.failedFuture(reportFailure(
                                scope, type, targetId, sessionId, "Debugger.removeBreakpoint",
                                new IllegalStateException("Instrumentation breakpoint sem id")));
                    }
                    return dispatchObserved(cdp, scope, type, targetId, sessionId,
                            "Debugger.removeBreakpoint", new HashMap<>(Map.of("breakpointId", breakpointId)));
                }));
                chain = thenDispatch(chain, cdp, scope, type, targetId, sessionId,
                        "Debugger.resume", new HashMap<>());
                CompletableFuture<Void> completed = thenDispatch(chain, cdp, scope, type, targetId, sessionId,
                        "Debugger.disable", new HashMap<>());
                completed.whenComplete((ignored, failure) -> pausedSessions.remove(sessionId, paused));
                return completed;
            }
            // Chrome 150 pode manter service/shared workers pausados sem responder a
            // Runtime.enable, criando um deadlock: a página aguarda a ativação do worker enquanto
            // o gate aguarda o comando expirar. Runtime.evaluate não exige Runtime.enable; este só
            // é mantido para dedicated workers, onde também habilita os eventos de contexto.
            if ("worker".equals(type)) {
                chain = thenDispatch(chain, cdp, scope, type, targetId, sessionId,
                        "Runtime.enable", new HashMap<>());
            }
            if (workerScript != null && !workerScript.isBlank()) {
                chain = thenDispatch(chain, cdp, scope, type, targetId, sessionId,
                        "Runtime.evaluate", evalArgs);
            }
            if (fingerprintProfile.timezoneId != null && !fingerprintProfile.timezoneId.isBlank()) {
                chain = thenDispatch(chain, cdp, scope, type, targetId, sessionId,
                        "Emulation.setTimezoneOverride",
                        Map.of("timezoneId", fingerprintProfile.timezoneId));
            }
            return chain;
        }

        boolean isFrame = "iframe".equals(type) || "page".equals(type);
        if (surfaceScript == null || !isFrame) {
            return chain;
        }
        boolean guardedOopFrame = "iframe".equals(type);
        if (guardedOopFrame) {
            // O Fetch domain do target principal não cobre requests iniciados depois que o frame
            // cross-site migra para seu processo próprio. O listener por sessão já está pronto;
            // marque a sessão antes do enable para que nenhum request pausado escape sem rewrite.
            chain = chain.thenRun(() -> clientHintGuardSessions.add(sessionId));
            Map<String, Object> fetchArgs = new HashMap<>();
            fetchArgs.put("patterns", List.of(
                    Map.of("urlPattern", "*", "requestStage", "Request")));
            chain = thenDispatch(chain, cdp, scope, type, targetId, sessionId,
                    "Fetch.enable", fetchArgs);
        }
        Map<String, Object> scriptParams = new HashMap<>();
        scriptParams.put("source", surfaceScript);
        scriptParams.put("runImmediately", true);
        String userAgent = UserAgentResolver.buildForProfile(fingerprintProfile);
        Map<String, Object> uaArgs = new HashMap<>();
        uaArgs.put("userAgent", userAgent);
        uaArgs.put("acceptLanguage", FingerprintCdpOverrides.acceptLanguage(fingerprintProfile.languages));
        uaArgs.put("platform", fingerprintProfile.platform);
        uaArgs.put("userAgentMetadata",
                fingerprintProfile.toUserAgentMetadata(UserAgentResolver.extractChromeMajor(userAgent)));
        chain = thenDispatch(chain, cdp, scope, type, targetId, sessionId,
                "Network.setUserAgentOverride", uaArgs);
        // Locale e timezone precisam ser definidos antes de Page.enable e de qualquer script
        // runImmediately. Criar o realm primeiro torna a aceitação dos overrides dependente de
        // timing: Chrome 150 rejeitou locale com -32000 em OOPIFs reais já materializados.
        if (fingerprintProfile.timezoneId != null && !fingerprintProfile.timezoneId.isBlank()) {
            Map<String, Object> tzArgs = new HashMap<>();
            tzArgs.put("timezoneId", fingerprintProfile.timezoneId);
            chain = thenDispatch(chain, cdp, scope, type, targetId, sessionId,
                    "Emulation.setTimezoneOverride", tzArgs);
        }
        if (fingerprintProfile.locale != null && !fingerprintProfile.locale.isBlank()) {
            Map<String, Object> localeArgs = new HashMap<>();
            localeArgs.put("locale", fingerprintProfile.locale);
            chain = thenDispatchLocaleAfterBootstrap(chain, cdp, scope, type, targetId, sessionId,
                    "Emulation.setLocaleOverride", localeArgs);
        }
        chain = thenDispatch(chain, cdp, scope, type, targetId, sessionId,
                "Page.enable", new HashMap<>());
        // runImmediately cobre o realm atual e o registro cobre documentos futuros; um
        // Runtime.evaluate adicional aplicaria todos os wrappers duas vezes no mesmo OOPIF.
        chain = thenDispatch(chain, cdp, scope, type, targetId, sessionId,
                "Page.addScriptToEvaluateOnNewDocument", scriptParams);
        if (guardedOopFrame) {
            CompletableFuture<Void> guardedInstallation = chain;
            guardedInstallation.whenComplete((ignored, failure) -> {
                if (failure != null) {
                    clientHintGuardSessions.remove(sessionId);
                }
            });
        }
        return chain;
    }

    private CompletableFuture<Void> thenDispatchLocaleAfterBootstrap(
            CompletableFuture<Void> previous,
            SessionCdp cdp,
            String scope,
            String type,
            String targetId,
            String sessionId,
            String command,
            Map<String, Object> arguments) {
        return previous.thenCompose(ignored -> dispatchLocaleAfterBootstrap(
                cdp, scope, type, targetId, sessionId, command, arguments, 0));
    }

    private CompletableFuture<Void> dispatchLocaleAfterBootstrap(
            SessionCdp cdp,
            String scope,
            String type,
            String targetId,
            String sessionId,
            String command,
            Map<String, Object> arguments,
            int attempt) {
        if (!isTargetSessionActive(targetId, sessionId)) {
            return CompletableFuture.failedFuture(
                    new TargetLifecycleEndedException("locale bootstrap retry cancelado"));
        }
        boolean emptyBootstrapTarget = "<empty>".equals(
                sessionTargetOrigins.getOrDefault(sessionId, "<unknown>"));
        if (emptyBootstrapTarget && nativeProcessLocaleBaselineConfigured) {
            JavaDriverlessLogger.debug(logger,
                    "WorkerSpoof[{}]: target vazio usa locale nativo do processo",
                    scope);
            return CompletableFuture.completedFuture(null);
        }
        if (emptyBootstrapTarget && attempt == 0) {
            localeBootstrapQuarantines.putIfAbsent(targetId, System.nanoTime());
            if (localeBootstrapQuarantines.size() > MAX_LOCALE_BOOTSTRAP_QUARANTINES) {
                localeBootstrapQuarantines.remove(targetId);
                return CompletableFuture.failedFuture(reportFailure(
                        scope, type, targetId, sessionId, command,
                        new IllegalStateException(
                                "limite global de quarentena de locale excedido")));
            }
        }
        long remainingMillis = localeBootstrapRemainingMillis(targetId);
        if (emptyBootstrapTarget && remainingMillis <= 0) {
            localeBootstrapQuarantines.remove(targetId);
            return CompletableFuture.failedFuture(reportFailure(
                    scope, type, targetId, sessionId, command,
                    new CDPException(-32000,
                            "locale recusado durante bootstrap vazio até o TTL absoluto")));
        }
        final CompletableFuture<JsonNode> operation;
        try {
            operation = cdp.exec(command, arguments, sessionId);
        } catch (Throwable failure) {
            if (!isTargetSessionActive(targetId, sessionId)) {
                return CompletableFuture.failedFuture(
                        new TargetLifecycleEndedException(
                                "locale bootstrap lançou após lifecycle"));
            }
            return retryOrFailLocaleBootstrap(
                    cdp, scope, type, targetId, sessionId, command, arguments, attempt, failure);
        }
        if (operation == null) {
            localeBootstrapQuarantines.remove(targetId);
            return CompletableFuture.failedFuture(reportFailure(
                    scope, type, targetId, sessionId, command,
                    new IllegalStateException("Executor CDP retornou future nulo")));
        }
        long operationTimeoutMillis = emptyBootstrapTarget
                ? Math.max(1L, remainingMillis)
                : java.util.concurrent.TimeUnit.SECONDS.toMillis(10);
        return operation.orTimeout(operationTimeoutMillis, java.util.concurrent.TimeUnit.MILLISECONDS)
                .handle((result, failure) -> failure)
                .thenCompose(failure -> {
                    if (!isTargetSessionActive(targetId, sessionId)) {
                        return CompletableFuture.failedFuture(
                                new TargetLifecycleEndedException(
                                        "locale bootstrap concluído após lifecycle"));
                    }
                    if (failure == null) {
                        localeBootstrapQuarantines.remove(targetId);
                        return CompletableFuture.completedFuture(null);
                    }
                    return retryOrFailLocaleBootstrap(
                                    cdp, scope, type, targetId, sessionId, command,
                                    arguments, attempt, failure);
                });
    }

    private CompletableFuture<Void> retryOrFailLocaleBootstrap(
            SessionCdp cdp,
            String scope,
            String type,
            String targetId,
            String sessionId,
            String command,
            Map<String, Object> arguments,
            int attempt,
            Throwable failure) {
        Throwable root = rootCause(failure);
        boolean emptyBootstrapTarget = "<empty>".equals(
                sessionTargetOrigins.getOrDefault(sessionId, "<unknown>"));
        boolean bootstrapRejection = root instanceof CDPException cdpFailure
                && cdpFailure.getCode() == -32000
                && cdpFailure.hasKind(CDPException.Kind.COMMAND_REJECTED);
        if (emptyBootstrapTarget && bootstrapRejection) {
            long retryDelayMillis = Math.min(
                    MAX_LOCALE_BOOTSTRAP_RETRY_DELAY_MILLIS,
                    50L << Math.min(attempt, 4));
            if (attempt + 1 < MAX_LOCALE_BOOTSTRAP_ATTEMPTS
                    && localeBootstrapRemainingMillis(targetId) > retryDelayMillis) {
                return CompletableFuture.runAsync(
                                () -> {},
                                CompletableFuture.delayedExecutor(
                                        retryDelayMillis, java.util.concurrent.TimeUnit.MILLISECONDS))
                        .thenCompose(ignored -> dispatchLocaleAfterBootstrap(
                                cdp, scope, type, targetId, sessionId, command,
                                arguments, attempt + 1));
            }
            localeBootstrapQuarantines.remove(targetId);
            return CompletableFuture.failedFuture(reportFailure(
                    scope, type, targetId, sessionId, command,
                    new CDPException(-32000,
                            "locale recusado durante bootstrap vazio até o TTL absoluto após "
                                    + (attempt + 1) + " tentativas")));
        }
        localeBootstrapQuarantines.remove(targetId);
        return CompletableFuture.failedFuture(reportFailure(
                scope, type, targetId, sessionId, command, failure));
    }

    private long localeBootstrapRemainingMillis(String targetId) {
        Long startedNanos = localeBootstrapQuarantines.get(targetId);
        if (startedNanos == null) return localeBootstrapTtlMillis;
        long elapsedNanos = Math.max(0L, System.nanoTime() - startedNanos);
        return localeBootstrapTtlMillis
                - java.util.concurrent.TimeUnit.NANOSECONDS.toMillis(elapsedNanos);
    }

}
