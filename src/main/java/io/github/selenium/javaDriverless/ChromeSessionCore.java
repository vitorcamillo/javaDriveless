// Licensed to the Software Freedom Conservancy (SFC) under one
// or more contributor license agreements.  See the NOTICE file
// distributed with this work for additional information
// regarding copyright ownership.  The SFC licenses this file
// to you under the Apache License, Version 2.0 (the
// "License"); you may not use this file except in compliance
// with the License.  You may obtain a copy of the License at
//
//   http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing,
// software distributed under the License is distributed on an
// "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
// KIND, either express or implied.  See the License for the
// specific language governing permissions and limitations
// under the License.
//
// Ported from: https://github.com/kaliiiiiiiiii/Selenium-Driverless
// Original Author: kaliiiiiiiiii | Aurin Aegerter
// Java Port: Vitor Camillo (io.github.vitorcamillo)
// Logic: Chrome DevTools Protocol (CDP) direct automation without driver

package io.github.selenium.javaDriverless;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import io.github.selenium.javaDriverless.scripts.Prefs;
import io.github.selenium.javaDriverless.logging.JavaDriverlessLogger;
import io.github.selenium.javaDriverless.types.*;
import io.github.selenium.javaDriverless.cdp.pipe.CdpPipeChannel;
import io.github.selenium.javaDriverless.utils.Utils;
import io.github.selenium.javaDriverless.utils.UserAgentResolver;

import java.io.IOException;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

import org.slf4j.Logger;

import io.github.selenium.javaDriverless.profiles.ProfileCatalogException;

abstract class ChromeSessionCore extends ChromeSessionState implements AutoCloseable {
    private boolean ephemeralDebuggingPort;

    protected ChromeSessionCore(ChromeOptions options, float timeout, boolean debug, int maxWsSize) {
        super(options, timeout, debug, maxWsSize);
    }

    public CompletableFuture<Chrome> startSession() {
        return lifecycle.start(started, this::startSessionAttempt);
    }

    private CompletableFuture<Chrome> startSessionAttempt() {
        return CompletableFuture.supplyAsync(() -> {
            try {
                final String configuredDebuggerAddress = options.getDebuggerAddress();
                final boolean attachMode = resolveAttachModeForStartup(configuredDebuggerAddress);
                final boolean explicitPortArgument = options.getArguments().stream()
                        .anyMatch(argument -> argument.startsWith("--remote-debugging-port="));
                final boolean pipeTransport = ChromePipeLaunch.resolvePipe(
                        options.getCdpTransport() == CdpTransport.PIPE, attachMode, configuredDebuggerAddress,
                        explicitPortArgument, System.getProperty("os.name", ""), logger);
                closePipeChannel();
                ephemeralDebuggingPort |= options.getArguments().stream()
                        .anyMatch(argument -> argument.equals("--remote-debugging-port=0"));
                if (attachMode && usesMinimalCdpPolicy()) {
                    throw new IllegalStateException(
                            "runtimePolicy=minimal-cdp não suporta attach a Chrome externo: "
                                    + "as flags de launch e o primeiro statement de pages/workers "
                                    + "já existentes não podem ser validados; use launch local "
                                    + "visível/Xvfb ou runtimePolicy=legacy-spoof");
                }
                // Attach pertence ao chamador externo e nunca reserva/muta storage local. Launch local
                // mantem a lease desde antes de porta/prefs ate o processo efetivamente encerrar.
                if (!attachMode) catalogLaunchLease = options.acquireCatalogLaunchLease();
                options.validateProfileLaunchArguments();

                // Verificar primeira execução
                Utils.isFirstRun().join();

                // ATTACH MODE: o usuário forneceu um debugger_address (Chrome externo, ex.: PhantomPass).
                // Capturar ANTES de a lib setar o próprio endereço ao lançar o Chrome (abaixo) — senão
                // launch mode também ficaria com debuggerAddress != null e o spoof seria pulado.
                // Configurar porta de debug se não especificada
                if (pipeTransport) {
                    if (!options.getArguments().contains(ChromePipeLaunch.PIPE_ARGUMENT)) {
                        options.addManagedLaunchArgument(ChromePipeLaunch.PIPE_ARGUMENT);
                    }
                } else if (configuredDebuggerAddress == null || configuredDebuggerAddress.isBlank()) {
                    // Porta 0: o próprio Chrome binda uma porta efêmera e a publica em DevToolsActivePort.
                    // Pré-escolher a porta deixava uma janela para outro processo local ocupá-la.
                    ephemeralDebuggingPort = true;
                    options.setDebuggerAddress("127.0.0.1:0");
                    options.addManagedLaunchArgument("--remote-debugging-port=0");
                } else if (ephemeralDebuggingPort && !attachMode) {
                    // debuggerAddress guarda a última porta resolvida; retry precisa voltar ao modo 0.
                    options.setDebuggerAddress("127.0.0.1:0");
                }
                // A origem do debugger address, e não o estado mutável de ChromeOptions, define
                // ownership. O endereço criado acima pertence ao processo local desta sessão.
                isRemote = attachMode;

                // Resolver o egress antes de qualquer argumento/pref derivado do perfil e, acima
                // de tudo, antes de criar o processo. --lang/--accept-lang são consumidos pelo
                // Chrome no startup e definem também workers que não recebem override de página.
                boolean egressCoherenceApplied = false;
                if (!isRemote && options.isFingerprintEnabled()) {
                    fingerprintSupport.restoreEgressBaseline();
                    options.prepareEgressDerivedLaunchBaseline();
                    try {
                        var egress = EgressCoherence.resolveEgress(
                                options.getEgressInfo(), options.getEgressResolver(),
                                options.getProxyConfig());
                        if (egress != null) {
                            fingerprintSupport.applyEgressCoherence(egress);
                            egressCoherenceApplied = true;
                        } else if (!shouldStartAuthenticatedProxyBridge()
                                && options.getSingleProxy() == null
                                && options.getProxyConfig() == null) {
                            // Sem proxy o IP de saída é o do host: fuso/idioma do preset (default US) denunciariam.
                            fingerprintSupport.applyHostLocale();
                            JavaDriverlessLogger.info(logger,
                                    "Sem proxy/egress: fuso e idioma herdados do host ({})",
                                    fingerprintSupport.profileTimezoneId());
                        } else {
                            JavaDriverlessLogger.warn(logger,
                                    "Proxy sem egress verificado: fuso {} do perfil não conferido contra o IP de saída; "
                                            + "use ChromeOptions.setEgressInfo ou setEgressResolver",
                                    fingerprintSupport.profileTimezoneId());
                        }
                    } catch (Exception e) {
                        throw new IllegalStateException(
                                "EgressCoherence failed before browser launch; refusing an unverified egress",
                                e);
                    }

                    // Configurar user-agent coerente com o perfil e com o Chrome instalado.
                    if (!usesMinimalCdpPolicy()) {
                        options.addArgument("--user-agent=" + resolveSessionUserAgent());
                    }
                    fingerprintSupport.configureHeadlessFingerprintArgs();
                }

                if (!attachMode) {
                    // Somente o launch local possui e pode preparar profile, prefs e argumentos.
                    // Attach conecta a um Chrome do chamador e nunca deve mutar seu user-data-dir.
                    if (options.getUserDataDir() == null) {
                        tempDir = Files.createTempDirectory("selenium_driverless_");
                        options.setManagedLaunchUserDataDir(tempDir.toString());
                    }
                    if (!ProfileStartupFiles.unlockStaleLocks(Paths.get(options.getUserDataDir()))) {
                        throw new IllegalStateException(
                                "Profile em uso ou com lock ambiguo; auto-unlock stale e opt-in via "
                                        + "JAVA_DRIVERLESS_UNLOCK_STALE_PROFILE_LOCKS=true");
                    }

                    boolean disableNonProxiedWebRtcUdp = options.isFingerprintEnabled()
                            && fingerprintProfile.webRtc != null
                            && fingerprintProfile.webRtc.mode != null
                            && !"real".equalsIgnoreCase(fingerprintProfile.webRtc.mode)
                            && fingerprintProfile.webRtc.disableUdp;
                    if (disableNonProxiedWebRtcUdp) {
                        // Chrome completo consulta este pref/flag. O switch historico
                        // --force-webrtc-ip-handling-policy pertence a outros embedders e nao
                        // bloqueia STUN no google-chrome-stable.
                        if (egressCoherenceApplied) {
                            options.applyEgressDerivedWebRtcPrefs();
                        } else {
                            options.updatePref(
                                    "webrtc.ip_handling_policy", "disable_non_proxied_udp");
                            // Compatibilidade com Chromes antigos; o pref acima e a autoridade atual.
                            options.updatePref("webrtc.multiple_routes_enabled", false);
                            options.updatePref("webrtc.nonproxied_udp_enabled", false);
                        }
                    }

                    Path prefsPath = Paths.get(options.getUserDataDir(), "Default", "Preferences");
                    Files.createDirectories(prefsPath.getParent());
                    prefs.clear();
                    prefs.putAll(options.getPrefs());
                    if (egressCoherenceApplied) {
                        if (!egressWebRtcPrefsDerived) {
                            egressWebRtcDiskBaseline = Files.isRegularFile(prefsPath)
                                    ? Prefs.readPrefs(prefsPath).join()
                                    : Map.of();
                        }
                        // Marcar antes da escrita: se o write atomico falhar depois de trocar o
                        // arquivo, o cleanup ainda restaura o baseline em vez de deixar resíduo.
                        egressWebRtcPrefsDerived = true;
                        Prefs.mergeAndWritePrefsReplacingPaths(
                                prefs, prefsPath, EGRESS_WEBRTC_PREF_PATHS, Map.of()).join();
                    } else if (egressWebRtcPrefsDerived) {
                        Prefs.mergeAndWritePrefsReplacingPaths(
                                prefs,
                                prefsPath,
                                EGRESS_WEBRTC_PREF_PATHS,
                                egressWebRtcDiskBaseline == null ? Map.of() : egressWebRtcDiskBaseline)
                                .join();
                        egressWebRtcDiskBaseline = null;
                        egressWebRtcPrefsDerived = false;
                    } else {
                        Prefs.mergeAndWritePrefs(prefs, prefsPath).join();
                    }

                    // Configurar proxy ANTES de iniciar o Chrome (via argumentos de linha de
                    // comando). O bridge autenticado é opt-in e preserva o fluxo padrão.
                    if (shouldStartAuthenticatedProxyBridge()) {
                        authenticatedProxyBridge = AuthenticatedProxyBridge.start(options.getProxyConfig());
                        authenticatedProxyBridgeArgument = "--proxy-server=" + authenticatedProxyBridge.asChromeProxyServer();
                        JavaDriverlessLogger.info(logger, "Proxy autenticado isolado via ponte local configurado");
                    } else if (options.getSingleProxy() != null) {
                        try {
                            ProxyArgumentConfigurator.configure(options, options.getSingleProxy(), logger);
                        } catch (Exception e) {
                            // Fail-closed: um proxy foi solicitado mas não pôde ser aplicado. Lançar o
                            // Chrome sem proxy o faria conectar DIRETO, vazando o IP real — abortar a
                            // sessão é o correto para um anti-detect.
                            throw new RuntimeException("Proxy solicitado não pôde ser configurado; "
                                    + "abortando para não vazar o IP real via conexão direta"
                                    + " | causeType=" + e.getClass().getSimpleName());
                        }
                    }

                    // Defense-in-depth no nível do engine. A política WebRTC pertence ao perfil e
                    // deve valer também sem proxy: assim o modo fail-closed não precisa substituir
                    // RTCPeerConnection por JavaScript (assinatura detectável) para bloquear UDP/STUN.
                    boolean proxyActive = shouldStartAuthenticatedProxyBridge()
                            || options.getSingleProxy() != null
                            || options.getProxyConfig() != null;
                    if (disableNonProxiedWebRtcUdp) {
                        if (egressCoherenceApplied) {
                            options.applyEgressDerivedWebRtcPolicyArgument();
                        } else {
                            options.addArgument(
                                    "--webrtc-ip-handling-policy=disable_non_proxied_udp");
                        }
                    }
                    if (proxyActive) {
                        options.addArgument("--disable-dns-prefetch");
                    }
                }

                // Startup URL: lançar SEMPRE uma unica page about:blank e adiar a navegacao real ate
                // DEPOIS de o spoof estar instalado. O plano tambem remove URLs posicionais passadas
                // por addArgument, que de outro modo fariam o Chrome navegar antes do preflight.
                String deferredStartupUrl = null;
                ChromeStartupNavigationPlan.Plan startupNavigation = null;
                if (attachMode) {
                    JavaDriverlessLogger.debug(logger,
                            "Attach mode: startupUrl ignorada; navegacao pertence ao Chrome externo");
                } else {
                    startupNavigation = ChromeStartupNavigationPlan.local(
                            options.getStartupUrl(), options.getArguments());
                    deferredStartupUrl = startupNavigation.deferredUrl();
                }

                // Iniciar processo Chrome se não for remoto
                String resolvedDebuggerAddress = options.getDebuggerAddress();
                if (!isRemote) {
                    options.validateProfileLaunchArguments();
                    MinimalCdpProcessArgumentPolicy.requireLoopbackDebuggingAddress(options.getArguments());
                    List<String> command = new ArrayList<>();
                    command.add(options.getBinaryLocation());
                    command.addAll(startupNavigation.launchArguments());
                    if (authenticatedProxyBridgeArgument != null) {
                        command.add(authenticatedProxyBridgeArgument);
                    }

                    JavaDriverlessLogger.info(logger,
                            "Iniciando Chrome local. debuggerAddressConfigured={}, argumentCount={}, cdpTransport={}",
                            options.getDebuggerAddress() != null && !options.getDebuggerAddress().isBlank(),
                            Math.max(0, command.size() - 1), pipeTransport ? "pipe" : "tcp");
                    JavaDriverlessLogger.debug(logger, "Flags do Chrome: {}",
                            safeChromeArgumentFlags(command.subList(1, command.size())));

                    if (pipeTransport) {
                        // Binário ausente falha aqui com IOException, como no TCP (sh sairia 127).
                        // PATH aplicável: o env configurado substitui integralmente o herdado.
                        ChromePipeLaunch.requireExecutable(command.get(0),
                                options.getEnv().getOrDefault("PATH", System.getenv("PATH")));
                        command = ChromePipeLaunch.wrapCommand(command);
                    }
                    ProcessBuilder pb = new ProcessBuilder(command);
                    ChromeOptionsEnvironment.replaceProcessEnvironment(pb, options.getEnv());
                    pb.redirectErrorStream(!pipeTransport);

                    if (!pipeTransport) {
                        DevToolsActivePortWaiter.deleteStale(Paths.get(options.getUserDataDir()));
                    }
                    process = pb.start();
                    browserPid = (int) process.pid();
                    if (authenticatedProxyBridge != null) {
                        authenticatedProxyBridge.bindOwner(browserPid.longValue());
                    }
                    if (pipeTransport) {
                        startupOutput.start(process.getErrorStream(), debug, logger);
                        pipeChannel = new CdpPipeChannel(
                                process.getInputStream(), process.getOutputStream(), timeout, maxWsSize, process);
                        pipeChannel.start();
                        resolvedDebuggerAddress = pipeChannel.getHost();
                    } else {
                        startupOutput.start(process, debug, logger);
                    }

                    // Porta 0 (padrão da lib): a porta real vem do DevToolsActivePort gravado pelo Chrome
                    // depois do bind. Porta fixa pedida pelo chamador segue sem esse arquivo (o Chrome
                    // não o grava nesse modo).
                    if (!pipeTransport) {
                        String[] parts = resolvedDebuggerAddress.split(":");
                        if (ephemeralDebuggingPort || Integer.parseInt(parts[1]) == 0) {
                            int port = DevToolsActivePortWaiter.await(
                                    Paths.get(options.getUserDataDir()), 0, process, timeout);
                            resolvedDebuggerAddress = "127.0.0.1:" + port;
                            options.setDebuggerAddress(resolvedDebuggerAddress);
                        }
                    }
                }

                // Conectar ao Chrome via CDP
                options.sealCatalogLaunchOptions();
                host = resolvedDebuggerAddress;
                baseTarget = new BaseTarget(host, isRemote, timeout, maxWsSize);
                baseTarget.init().join();

                // Buscar target inicial (primeira aba) ANTES de injetar script.
                String targetsJson = getTargetsJson(host).join();
                JsonNode targets = objectMapper.readTree(targetsJson);
                String initialTargetId = resolveInitialPageTargetId(targets, attachMode, () ->
                        baseTarget.executeCdpCmd(
                                "Target.createTarget", Map.of("url", "about:blank"), null).join());
                currentTarget = new Target(host, initialTargetId, (Chrome) this, null,
                        isRemote, timeout, "page", false, maxWsSize);
                currentTarget.init().join();

                configureDownloadBehavior(attachMode);

                // Inicializar contexto padrão (fix para newWindow)
                if (currentContext == null && currentTarget != null) {
                    Context defaultContext = new Context(
                            currentTarget, (Chrome) this, null, false, maxWsSize);
                    currentContext = defaultContext;
                }

                // Em ATTACH MODE (debugger_address), o Chrome é gerido externamente (ex.: PhantomPass)
                // e já tem identidade/fingerprint próprios — NÃO aplicar o spoof da lib por cima
                // (evita conflito/double-spoof). O spoof (CDP overrides + surface script + worker
                // auto-attach) só roda quando a própria lib lança o Chrome.
                if (!attachMode && options.isFingerprintEnabled()) {
                    // O spoof inicial é um contrato de identidade, não um recurso opcional. Se uma
                    // destas etapas falhar, a sessão não pode ser publicada como iniciada: continuar
                    // exporia a identidade real do host com aparência de sessão anti-detect saudável.
                    requireEssentialFingerprintStage("cdp-overrides",
                            fingerprintSupport.applyFingerprintProfileCdpOverrides(currentTarget));
                    requireEssentialFingerprintStage("surface-script",
                            fingerprintSupport.injectSeleniumProxySurfaceScript(currentTarget));

                    // Estende o spoof ao escopo de worker/service-worker/shared-worker via a conexão
                    // browser-level (baseTarget). Shared/service workers são targets de nível browser e
                    // NÃO seriam alcançados por auto-attach de uma sessão de página. Sem isso, o realm
                    // de worker vaza o renderer WebGL e o hardwareConcurrency/deviceMemory reais,
                    // criando mismatch main-vs-worker (trivialmente detectável por CreepJS etc.).
                    requireEssentialFingerprintStage("worker-auto-attach",
                            fingerprintSupport.installWorkerSpoofAutoAttach(currentTarget, baseTarget));

                    // Navegar para a startupUrl real agora que o spoof está 100% instalado — o
                    // addScriptToEvaluateOnNewDocument já registrado roda antes do JS da página alvo.
                    if (deferredStartupUrl != null) {
                        navigateDeferredStartupUrl(deferredStartupUrl);
                    }
                } else if (attachMode) {
                    JavaDriverlessLogger.info(logger,
                        "Attach mode (debugger_address): fingerprint gerido externamente; spoof da lib nao aplicado.");
                } else {
                    JavaDriverlessLogger.info(logger,
                            "Baseline mode: fingerprint da lib explicitamente desabilitado.");
                    if (deferredStartupUrl != null) {
                        navigateDeferredStartupUrl(deferredStartupUrl);
                    }
                }

                started = true;
                return (Chrome) this;

            } catch (Exception e) {
                String diagnostics = ChromeStartupFailureDiagnostics.build(
                        options, isRemote, process, startupOutput);
                Throwable sanitized = sanitizedFailure(e);
                try {
                    cleanupAfterStartupFailure();
                } catch (Exception cleanupFailure) {
                    diagnostics += " | cleanupFailureType="
                            + cleanupFailure.getClass().getSimpleName();
                    sanitized.addSuppressed(sanitizedFailure(cleanupFailure));
                }
                throw new RuntimeException(diagnostics
                        + " | failureType=" + e.getClass().getSimpleName(),
                        sanitized);
            }
        });
    }

    private void requireEssentialFingerprintStage(String stage, CompletableFuture<Void> operation) {
        try {
            operation.join();
        } catch (Exception e) {
            throw new FingerprintStartupException(stage, e);
        }
    }

}
