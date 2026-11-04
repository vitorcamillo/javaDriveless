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
import io.github.selenium.javaDriverless.cdp.pipe.CdpPipeRegistry;
import io.github.selenium.javaDriverless.utils.SafeDiagnostics;
import io.github.selenium.javaDriverless.utils.Utils;
import io.github.selenium.javaDriverless.utils.UserAgentResolver;

import io.github.selenium.javaDriverless.cdp.DevToolsHttp;

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

abstract class ChromeSessionState {

    static final Set<String> EGRESS_WEBRTC_PREF_PATHS = Set.of(
            "webrtc.ip_handling_policy",
            "webrtc.multiple_routes_enabled",
            "webrtc.nonproxied_udp_enabled");
    protected static final Logger logger = JavaDriverlessLogger.getLogger(Chrome.class);
    protected static final ObjectMapper objectMapper = new ObjectMapper();
    protected final ChromeOptions options;
    protected final float timeout;
    protected final boolean debug;
    protected final int maxWsSize;
    protected final BrowserFingerprintProfile fingerprintProfile;
    protected String sessionUserAgent;

    protected Map<String, Object> prefs = new HashMap<>();
    protected BaseTarget baseContext;
    protected Process process;
    protected Target currentTarget;
    protected String host;
    protected Integer browserPid;
    protected BaseTarget baseTarget;
    protected Context currentContext;
    protected final Map<String, Context> contexts = new ConcurrentHashMap<>();
    protected Path tempDir;
    protected boolean isRemote;
    protected boolean hasIncognitoContexts;
    protected volatile boolean started = false;
    final ChromeSessionLifecycle lifecycle = new ChromeSessionLifecycle();
    Boolean attachModeFromCaller;
    protected AuthenticatedProxyBridge authenticatedProxyBridge;
    CdpPipeChannel pipeChannel;
    String authenticatedProxyBridgeArgument;
    final ChromeStartupOutput startupOutput = new ChromeStartupOutput();
    final ChromeFingerprintSupport fingerprintSupport;
    ChromeOptions.CatalogLaunchLease catalogLaunchLease;
    Map<String, Object> egressWebRtcDiskBaseline;
    boolean egressWebRtcPrefsDerived;

    /**
     * Cria uma nova instância do Chrome.
     * <p>
     * Inicia o serviço e então cria uma nova instância do target Chrome.
     * </p>
     *
     * @param options   instância de ChromeOptions
     * @param timeout   timeout em segundos para iniciar o Chrome
     * @param debug     redirecionar erros do processo Chrome para console
     * @param maxWsSize tamanho máximo para mensagens websocket em bytes (padrão:
     *                  2^27 ~= 130 MB)
     */
    protected ChromeSessionState(ChromeOptions options, float timeout, boolean debug, int maxWsSize) {
        this.options = (options != null) ? options : new ChromeOptions();
        this.timeout = timeout;
        this.debug = debug;
        this.maxWsSize = maxWsSize;
        this.fingerprintProfile = this.options.getFingerprintProfile();
        this.fingerprintSupport = new ChromeFingerprintSupport(
                this.options,
                this.fingerprintProfile,
                objectMapper,
                logger,
                this::resolveSessionUserAgent);

        // Garantir que o binário do Chrome está configurado
        if (this.options.getBinaryLocation() == null) {
            this.options.setBinaryLocation(Utils.findChromeExecutable());
        }
    }

    void navigateDeferredStartupUrl(String startupUrl) {
        JsonNode result = currentTarget.executeCdpCmd(
                "Page.navigate", Map.of("url", startupUrl), null).join();
        String errorText = result == null ? "" : result.path("errorText").asText("").trim();
        if (!errorText.isEmpty()) {
            throw new IllegalStateException("StartupUrl navigation failed");
        }
    }

    static String resolveInitialPageTargetId(
            JsonNode targets, boolean attachMode, Supplier<JsonNode> localTargetCreator) {
        if (targets != null && targets.isArray()) {
            for (JsonNode target : targets) {
                if ("page".equals(target.path("type").asText())) {
                    String targetId = target.path("id").asText("").trim();
                    if (!targetId.isEmpty()) {
                        return targetId;
                    }
                }
            }
        }
        if (attachMode) {
            throw new IllegalStateException("Chrome externo sem target page disponível");
        }
        JsonNode created = localTargetCreator.get();
        String targetId = created == null ? "" : created.path("targetId").asText("").trim();
        if (targetId.isEmpty()) {
            throw new IllegalStateException("Chrome local não criou target page inicial");
        }
        return targetId;
    }

    static Throwable sanitizedFailure(Throwable failure) {
        if (failure == null) return null;
        Throwable nested = failure.getCause() == failure ? null : sanitizedFailure(failure.getCause());
        String message = sanitizeFailureMessage(failure.getMessage());
        Throwable sanitized;
        if (failure instanceof ProfileCatalogException) {
            sanitized = new ProfileCatalogException(message, nested);
        } else if (failure instanceof IOException) {
            sanitized = new IOException(message, nested);
        } else if (failure instanceof IllegalArgumentException) {
            sanitized = new IllegalArgumentException(message, nested);
        } else if (failure instanceof IllegalStateException) {
            sanitized = new IllegalStateException(message, nested);
        } else if (failure instanceof SecurityException) {
            sanitized = new SecurityException(message, nested);
        } else if (failure instanceof CompletionException) {
            sanitized = new CompletionException(message, nested);
        } else if (failure instanceof RuntimeException) {
            sanitized = new RuntimeException(message, nested);
        } else {
            sanitized = new Exception(message, nested);
        }
        sanitized.setStackTrace(failure.getStackTrace());
        return sanitized;
    }

    private static String sanitizeFailureMessage(String message) {
        return SafeDiagnostics.sanitizeMessage(message);
    }

    protected void configureDownloadBehavior(boolean attachMode) {
        if (attachMode || options.getDownloadsDir() == null) return;
        Map<String, Object> args = new HashMap<>();
        args.put("behavior", "allowAndName");
        args.put("downloadPath", options.getDownloadsDir());
        currentTarget.executeCdpCmd("Browser.setDownloadBehavior", args, null).join();
    }

    /**
     * Libera todos os recursos que podem ter sido adquiridos parcialmente pelo startup.
     * Cada etapa é independente e idempotente para que uma falha de fechamento não impeça
     * processo, sockets, proxy ou diretório temporário de serem tratados.
     */
    void cleanupAfterStartupFailure() {
        started = false;
        resetSessionScopedState();

        Target target = currentTarget;
        currentTarget = null;
        BaseTarget browserTarget = baseTarget;
        baseTarget = null;
        BaseTarget contextTarget = baseContext;
        baseContext = null;
        currentContext = null;
        contexts.clear();
        Process ownedProcess = process;
        List<ProcessHandle> ownedProcessTree = captureProcessTree(ownedProcess);
        try {
            ChromeStartupResourceCleaner.cleanup(
                    logger,
                    target,
                    browserTarget,
                    contextTarget,
                    ownedProcess,
                    tempDir,
                    !isRemote,
                    () -> { closeAuthenticatedProxyBridge(); closePipeChannel(); });
            restoreEgressDerivedWebRtcState();
        } finally {
            releaseCatalogLaunchLeaseAfterProcessExit(ownedProcessTree);
        }
    }

    /**
     * Restaura no profile persistente somente os leaves WebRTC derivados desta sessão. Deve ser
     * chamado depois de o processo Chrome encerrar; siblings escritos pelo Chrome/chamador ficam
     * intactos e diretórios temporários já removidos não são recriados.
     */
    protected void restoreEgressDerivedWebRtcState() {
        if (!egressWebRtcPrefsDerived) return;
        Map<String, Object> baseline = egressWebRtcDiskBaseline == null
                ? Map.of() : egressWebRtcDiskBaseline;
        try {
            if (options.getUserDataDir() != null) {
                Path prefsPath = Paths.get(
                        options.getUserDataDir(), "Default", "Preferences");
                if (Files.isRegularFile(prefsPath)) {
                    Prefs.mergeAndWritePrefsReplacingPaths(
                            Map.of(), prefsPath, EGRESS_WEBRTC_PREF_PATHS, baseline).join();
                }
            }
        } catch (Exception failure) {
            throw new IllegalStateException(
                    "Falha ao restaurar prefs WebRTC derivados do egress", failure);
        }
        egressWebRtcDiskBaseline = null;
        egressWebRtcPrefsDerived = false;
        options.restoreEgressDerivedLaunchBaselineAfterSession();
    }

    protected void closeAuthenticatedProxyBridge() {
        authenticatedProxyBridgeArgument = null;
        AuthenticatedProxyBridge bridge = authenticatedProxyBridge;
        authenticatedProxyBridge = null;
        if (bridge != null) {
            try {
                bridge.close();
            } catch (Exception e) {
                JavaDriverlessLogger.warn(logger,
                        "Erro ao fechar ponte local de proxy autenticado: {}",
                        e.getClass().getSimpleName());
            }
        }
    }

    void closePipeChannel() {
        if (pipeChannel != null) {
            try {
                pipeChannel.close();
            } catch (Exception ignored) {
                // Cleanup must continue even if the pipe transport fails to close.
            } finally {
                pipeChannel = null;
            }
        }
    }

    protected CompletableFuture<Void> coordinateClose(
            Supplier<CompletableFuture<Void>> closeOperation) {
        return lifecycle.close(closeOperation, () -> {
            started = false;
            resetSessionScopedState();
            releaseCatalogLaunchLeaseAfterProcessExit(captureProcessTree(process));
        });
    }

    protected List<ProcessHandle> captureProcessTree(Process ownedProcess) {
        if (ownedProcess == null) return List.of();
        List<ProcessHandle> handles = new ArrayList<>();
        ProcessHandle root = ownedProcess.toHandle();
        handles.add(root);
        try {
            handles.addAll(root.descendants().toList());
        } catch (Exception ignored) {
        }
        return List.copyOf(handles);
    }

    protected synchronized void releaseCatalogLaunchLeaseAfterProcessExit(List<ProcessHandle> processTree) {
        ChromeOptions.CatalogLaunchLease lease = catalogLaunchLease;
        catalogLaunchLease = null;
        if (lease == null) return;
        List<ProcessHandle> alive = processTree == null ? List.of()
                : processTree.stream().filter(ProcessHandle::isAlive).toList();
        if (!alive.isEmpty()) {
            CompletableFuture<?>[] exits = alive.stream()
                    .map(ProcessHandle::onExit)
                    .toArray(CompletableFuture[]::new);
            CompletableFuture.allOf(exits)
                    .whenComplete((ignored, failure) -> closeCatalogLaunchLease(lease));
            return;
        }
        closeCatalogLaunchLease(lease);
    }

    private void closeCatalogLaunchLease(ChromeOptions.CatalogLaunchLease lease) {
        try {
            lease.close();
        } catch (Exception e) {
            JavaDriverlessLogger.warn(logger,
                    "Erro ao liberar lease de storage do catalogo: {}",
                    e.getClass().getSimpleName());
        }
    }

    /** Hook para helpers vinculados ao target/conexão atual. */
    protected void resetSessionScopedState() {
        try {
            fingerprintSupport.closeWorkerSpoofSupport().join();
        } catch (Exception failure) {
            JavaDriverlessLogger.warn(logger,
                    "Erro ao fechar conexão auxiliar de fingerprint: {}",
                    failure.getClass().getSimpleName());
        }
        fingerprintSupport.resetRuntimeFailure();
    }

    public boolean hasActiveAuthenticatedProxyBridge() {
        return authenticatedProxyBridge != null;
    }

    /**
     * Indica se alguma injeção posterior em worker/OOPIF falhou depois do startup.
     * O startup ainda falha imediatamente para os estágios síncronos essenciais.
     */
    public boolean hasFingerprintRuntimeFailure() {
        return fingerprintSupport.hasRuntimeFailure();
    }

    /** Retorna um diagnóstico sanitizado da primeira falha de fingerprint em runtime. */
    public Optional<String> getFingerprintRuntimeFailure() {
        return fingerprintSupport.runtimeFailureMessage();
    }

    protected boolean shouldStartAuthenticatedProxyBridge() {
        return options.isAuthenticatedProxyBridgeEnabled()
                && !isRemote
                && options.getProxyConfig() != null
                && options.getProxyConfig().hasAuthentication();
    }

    // Persiste a origem: setDebuggerAddress interno não pode transformar retry local em attach.
    synchronized boolean resolveAttachModeForStartup(String configuredDebuggerAddress) {
        if (attachModeFromCaller == null) {
            attachModeFromCaller = options.isRemote()
                    && configuredDebuggerAddress != null
                    && !configuredDebuggerAddress.isBlank();
        }
        return attachModeFromCaller;
    }

    /** Mantém somente nomes de flags; valores e argumentos posicionais nunca chegam ao log. */
    static List<String> safeChromeArgumentFlags(List<String> arguments) {
        return SafeDiagnostics.safeChromeArgumentFlags(arguments);
    }

    CompletableFuture<String> getTargetsJson(String host) {
        if (CdpPipeRegistry.isPipeHost(host)) {
            try {
                CdpPipeChannel channel = CdpPipeRegistry.lookup(host)
                        .orElseThrow(() -> new RuntimeException("Erro ao buscar targets"));
                JsonNode targets = channel.rootView()
                        .exec("Target.getTargets", Map.of(), timeout)
                        .join();
                var result = objectMapper.createArrayNode();
                JsonNode targetInfos = targets == null ? null : targets.path("targetInfos");
                if (targetInfos != null && targetInfos.isArray()) {
                    for (JsonNode target : targetInfos) {
                        var item = objectMapper.createObjectNode();
                        item.put("id", target.path("targetId").asText(""));
                        item.put("type", target.path("type").asText(""));
                        item.put("url", target.path("url").asText(""));
                        item.put("title", target.path("title").asText(""));
                        result.add(item);
                    }
                }
                return CompletableFuture.completedFuture(objectMapper.writeValueAsString(result));
            } catch (Exception e) {
                if (e instanceof RuntimeException runtime
                        && "Erro ao buscar targets".equals(runtime.getMessage())
                        && runtime.getCause() == null) {
                    throw runtime;
                }
                throw new RuntimeException("Erro ao buscar targets", e);
            }
        }
        return CompletableFuture.supplyAsync(() -> {
            try {
                // Timeout <= 0 usa 10 s; frações de segundo viram 10 s no DevToolsHttp.
                long timeoutSeconds = timeout <= 0 ? 10 : (long) timeout;
                try {
                    return DevToolsHttp.get(
                            "http://" + host + "/json", TimeUnit.SECONDS.toNanos(timeoutSeconds));
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    throw new RuntimeException("Erro ao buscar targets", ie);
                }
            } catch (Exception e) {
                throw new RuntimeException("Erro ao buscar targets", e);
            }
        });
    }

    public CompletableFuture<Void> injectAntiDetectionScript() {
        if (!options.isFingerprintEnabled()) return CompletableFuture.completedFuture(null);
        return fingerprintSupport.injectAntiDetectionScript(currentTarget);
    }

    protected CompletableFuture<Void> applyFingerprintProfileToTarget(Target target) {
        if (!options.isFingerprintEnabled()) return CompletableFuture.completedFuture(null);
        // Target.createTarget cria uma page irmã sem openerId; ela não passa pelo auto-attach da
        // page atual. Portanto a nova aba precisa dos overrides e do preload persistente antes de
        // ser devolvida ao chamador, que pode navegar imediatamente após newWindow().
        return fingerprintSupport.applyFingerprintProfileCdpOverrides(target)
                .thenCompose(ignored -> fingerprintSupport.injectSeleniumProxySurfaceScript(target))
                .thenCompose(ignored -> installWorkerSpoofForRelatedPage(target));
    }

    protected CompletableFuture<Void> installWorkerSpoofForRelatedPage(Target target) {
        if (!options.isFingerprintEnabled()) return CompletableFuture.completedFuture(null);
        return fingerprintSupport.installWorkerSpoofForRelatedPage(target, baseTarget);
    }

    synchronized String resolveSessionUserAgent() {
        if (sessionUserAgent != null) {
            return sessionUserAgent;
        }

        UserAgentResolver.ensureBrowserVersion(fingerprintProfile);
        String configuredUserAgent = options.getUserAgent();
        if (configuredUserAgent != null && !configuredUserAgent.isBlank()) {
            String candidate = configuredUserAgent.trim();
            if (!UserAgentResolver.isCompatible(fingerprintProfile, candidate)) {
                throw new IllegalArgumentException(
                        "User-Agent explícito incompatível com fingerprint/browserVersion");
            }
            sessionUserAgent = candidate;
            return sessionUserAgent;
        }

        sessionUserAgent = UserAgentResolver.buildForProfile(fingerprintProfile);
        return sessionUserAgent;
    }

    boolean usesMinimalCdpPolicy() {
        return BrowserFingerprintProfile.RUNTIME_POLICY_MINIMAL_CDP.equalsIgnoreCase(
                fingerprintProfile.runtimePolicy == null
                        ? "" : fingerprintProfile.runtimePolicy.trim());
    }

}
