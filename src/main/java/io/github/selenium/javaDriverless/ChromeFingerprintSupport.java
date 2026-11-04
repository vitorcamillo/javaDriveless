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
import io.github.selenium.javaDriverless.logging.JavaDriverlessLogger;
import io.github.selenium.javaDriverless.scripts.AntiDetectionScripts;
import io.github.selenium.javaDriverless.scripts.SeleniumProxySurfaceScripts;
import io.github.selenium.javaDriverless.types.BaseTarget;
import io.github.selenium.javaDriverless.types.BrowserFingerprintProfile;
import io.github.selenium.javaDriverless.types.ChromeOptions;
import io.github.selenium.javaDriverless.types.EgressCoherence;
import io.github.selenium.javaDriverless.types.EgressInfo;
import io.github.selenium.javaDriverless.types.FingerprintCdpOverrides;
import io.github.selenium.javaDriverless.types.Target;
import io.github.selenium.javaDriverless.utils.UserAgentResolver;
import io.github.selenium.javaDriverless.utils.Utils;
import org.slf4j.Logger;
import java.time.ZoneId;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Supplier;
final class ChromeFingerprintSupport {
    private final ChromeOptions options;
    private final BrowserFingerprintProfile fingerprintProfile;
    private final BrowserFingerprintProfile egressBaseline;
    private final ObjectMapper objectMapper;
    private final Logger logger;
    private final Supplier<String> sessionUserAgent;
    private final Function<BaseTarget, BaseTarget> auxiliaryBrowserFactory;
    private final AtomicReference<Throwable> runtimeFailure = new AtomicReference<>();
    private volatile ChromeWorkerSpoofSupport workerSpoofSupport;
    private volatile String effectiveMinimalIntlLocale;
    private volatile boolean nativeProcessLocaleBaselineConfigured;
    ChromeFingerprintSupport(ChromeOptions options,
            BrowserFingerprintProfile fingerprintProfile,
            ObjectMapper objectMapper,
            Logger logger,
            Supplier<String> sessionUserAgent) {
        this(options, fingerprintProfile, objectMapper, logger, sessionUserAgent,
                BaseTarget::newIndependentConnection);
    }
    ChromeFingerprintSupport(ChromeOptions options,
            BrowserFingerprintProfile fingerprintProfile,
            ObjectMapper objectMapper,
            Logger logger,
            Supplier<String> sessionUserAgent,
            Function<BaseTarget, BaseTarget> auxiliaryBrowserFactory) {
        this.options = options;
        this.fingerprintProfile = fingerprintProfile;
        this.egressBaseline = fingerprintProfile.copy();
        this.objectMapper = objectMapper;
        this.logger = logger;
        this.sessionUserAgent = sessionUserAgent;
        this.auxiliaryBrowserFactory = auxiliaryBrowserFactory;
    }
    void configureHeadlessFingerprintArgs() {
        // Valide exatamente o ambiente explícito que será entregue ao Chrome. Usar o ambiente
        // global da JVM aqui permitia um handshake Xvfb passar no gate e desaparecer no spawn,
        // pois ChromeOptions aplica uma fronteira/allowlist própria antes de criar o processo.
        configureHeadlessFingerprintArgs(options.getEnv());
    }
    void configureHeadlessFingerprintArgs(Map<String, String> environment) {
        nativeProcessLocaleBaselineConfigured = false;
        boolean minimalCdp = usesMinimalCdpPolicy();
        if (minimalCdp) {
            requireNativeUserAgentConfiguration();
            if (options.isHeadless()) {
                // Rejeitar antes de consultar o binário: o processo não pode ser tocado nem para
                // --version quando a configuração já é comprovadamente insegura.
                MinimalCdpCapabilityContract.requireSupported(
                        fingerprintProfile, true, environment);
            }
            requireSelectedChromeVersion();
            MinimalCdpHostCompatibility.requireCompatible(
                    fingerprintProfile, sessionUserAgent.get(), System.getProperty("os.name", ""));
            MinimalCdpCapabilityContract.requireSupported(
                    fingerprintProfile, options.isHeadless(), environment);
            removeSyntheticLegacyProcessArguments();
            configureMinimalProcessLocaleArguments();
            boolean spoofedScreen = "spoof".equalsIgnoreCase(fingerprintProfile.screen.mode);
            if (!spoofedScreen) {
                return;
            }
            int width = Math.max(1, fingerprintProfile.screen.width);
            int outerHeight = Math.max(1, fingerprintProfile.screen.availHeight - 8);
            addChromeArgumentIfMissing(
                    "--window-size=", "--window-size=" + width + "," + outerHeight);
            // webgl.mode=real é pass-through no modo mínimo: não selecionar ANGLE, SwiftShader ou
            // qualquer backend de GPU por argumento. A geometria spoof usa apenas CDP/Xvfb.
            return;
        }
        configureNativeProcessLocaleBaseline();
        boolean spoofedScreen = fingerprintProfile.screen.mode != null
                && !"real".equalsIgnoreCase(fingerprintProfile.screen.mode);
        if (!options.isHeadless() && !spoofedScreen) {
            return;
        }
        if (options.isHeadless()) {
            fingerprintProfile.screen.mode = "spoof";
        }
        int width = Math.max(1, fingerprintProfile.screen.width);
        int outerHeight = Math.max(1, fingerprintProfile.screen.availHeight - 8);
        addChromeArgumentIfMissing("--window-size=", "--window-size=" + width + "," + outerHeight);
        if (!options.isHeadless()) {
            return;
        }
        String glBackend = environment == null ? null : environment.get("JAVA_DRIVERLESS_HEADLESS_GL");
        if ("swiftshader".equalsIgnoreCase(glBackend)) {
            addChromeArgumentIfMissing("--use-gl=", "--use-gl=angle");
            addChromeArgumentIfMissing("--use-angle=", "--use-angle=swiftshader");
            addChromeArgumentIfMissing("--enable-unsafe-swiftshader", "--enable-unsafe-swiftshader");
        } else {
            addChromeArgumentIfMissing("--use-gl=", "--use-gl=angle");
            addChromeArgumentIfMissing("--use-angle=", "--use-angle=gl");
            addChromeArgumentIfMissing("--enable-gpu", "--enable-gpu");
            addChromeArgumentIfMissing("--disable-software-rasterizer", "--disable-software-rasterizer");
        }
    }
    private void addChromeArgumentIfMissing(String prefix, String argument) {
        for (String existing : options.getArguments()) {
            if (existing.equals(prefix) || existing.startsWith(prefix)) {
                return;
            }
        }
        options.addArgument(argument);
    }
    private void removeSyntheticLegacyProcessArguments() {
        // Probes A/B no Chrome 150/Linux/Xvfb mantiveram o contrato CreepJS, UA-CH completo e
        // WebGL funcional ao remover estes defaults; o Canvas passou ao caminho acelerado sem
        // lies. A política preserva separadamente hardenings de egress/telemetria, o mute
        // operacional e --ignore-gpu-blocklist, ainda necessário para WebGL no Xvfb atual.
        options.applyMinimalCdpProcessArgumentPolicy();
    }
    private void requireNativeUserAgentConfiguration() {
        String explicitUserAgent = options.getUserAgent();
        if (explicitUserAgent != null && !explicitUserAgent.isBlank()) {
            throw new IllegalStateException(
                    "runtimePolicy=minimal-cdp exige User-Agent nativo do Chrome; remova "
                            + "--user-agent/browser.userAgent. No Chrome 150, esse argumento zera "
                            + "campos UA-CH de alta entropia em page e workers; use "
                            + "runtimePolicy=legacy-spoof para uma identidade sintética");
        }
    }
    private void requireSelectedChromeVersion() {
        String binary = options.getBinaryLocation();
        if (binary == null || binary.isBlank()) {
            // ChromeSessionCore sempre resolve o binário antes deste gate. A ausência só ocorre em
            // testes unitários diretos de ChromeFingerprintSupport, sem processo a iniciar.
            return;
        }
        String detectedVersion = Utils.detectChromeVersion(binary);
        if (detectedVersion == null || detectedVersion.isBlank()) {
            throw new IllegalStateException(
                    "runtimePolicy=minimal-cdp não conseguiu detectar a versão do binário Chrome "
                            + "selecionado; a versão nativa precisa ser conhecida antes do launch");
        }
        String configuredVersion = fingerprintProfile.browserVersion == null
                ? "" : fingerprintProfile.browserVersion.trim();
        if (!configuredVersion.isEmpty() && !configuredVersion.equals(detectedVersion)) {
            throw new IllegalStateException(
                    "runtimePolicy=minimal-cdp exige browserVersion igual ao binário selecionado; "
                            + "perfil=" + configuredVersion + ", Chrome=" + detectedVersion);
        }
        fingerprintProfile.browserVersion = detectedVersion;
    }
    private void configureMinimalProcessLocaleArguments() {
        configureNativeProcessLocaleBaseline();
        ChromeMinimalNativeProcessBaseline.configure(options, fingerprintProfile);
    }
    private void configureNativeProcessLocaleBaseline() {
        List<String> languages = fingerprintProfile.languages == null
                ? List.of() : fingerprintProfile.languages;
        if (fingerprintProfile.locale == null || fingerprintProfile.locale.isBlank()
                || languages.isEmpty()) {
            throw new IllegalStateException(
                    "runtimePolicy=minimal-cdp exige locale e languages para configurar "
                            + "o idioma nativo de todos os workers");
        }
        // O perfil é autoritativo inclusive no baseline nativo. ChromeOptions trata ambos
        // como argumentos singleton, então uma configuração manual conflitante é substituída.
        options.addArgument("--lang=" + fingerprintProfile.locale);
        options.addArgument("--accept-lang=" + String.join(",", languages));
        options.updatePref("intl.accept_languages", String.join(",", languages));
        Map<String, String> processEnvironment = new HashMap<>(options.getEnv());
        // O Chrome/ICU canonicaliza alguns idiomas regionais (de-DE -> de, ja-JP -> ja).
        // C.UTF-8 evita depender de locales POSIX instalados; LANGUAGE seleciona a familia.
        // O valor efetivo e validado no about:blank antes de qualquer navegacao externa.
        processEnvironment.put("LANG", "C.UTF-8");
        processEnvironment.put("LC_ALL", "C.UTF-8");
        processEnvironment.put("LC_MESSAGES", "C.UTF-8");
        processEnvironment.put("LANGUAGE", String.join(":", languages));
        options.setEnv(processEnvironment);
        nativeProcessLocaleBaselineConfigured = true;
    }
    CompletableFuture<Void> injectAntiDetectionScript(Target target) {
        if (usesMinimalCdpPolicy()) {
            // O domínio Page continua necessário para o lifecycle/nova navegação do target; o
            // modo mínimo remove somente a injeção JavaScript, não a ativação do domínio CDP.
            return enablePageForPersistentScript(target);
        }
        // FIX GAP-05: runImmediately garante injeção em iframes e antes de qualquer JS
        HashMap<String, Object> scriptParams = new HashMap<>();
        scriptParams.put("source", antiDetectionScript());
        scriptParams.put("runImmediately", true);
        // 100% NATIVO no caminho de sucesso: só registra o init-script (runImmediately cobre o realm
        // existente). O Runtime.evaluate imediato foi REMOVIDO — detectável e redundante. O fallback
        // abaixo (escape-hatch) mantém a injeção via executeScript se o addScript falhar.
        return enablePageForPersistentScript(target).thenCompose(ignored ->
            target.executeCdpCmd("Page.addScriptToEvaluateOnNewDocument", scriptParams, null)
                .<Void>thenApply(v -> {
                    JavaDriverlessLogger.debug(logger,
                            "Anti-detection script registrado para novos documentos e iframes");
                    return null;
                })
                .exceptionally(ex -> {
                    JavaDriverlessLogger.warn(logger,
                            "Page.addScriptToEvaluateOnNewDocument indisponível, usando fallback");
                    try {
                        target.executeScript(antiDetectionScript(), null, true).join();
                        JavaDriverlessLogger.debug(logger, "Anti-detection script injetado via Runtime.evaluate");
                    } catch (Exception fallbackFailure) {
                        JavaDriverlessLogger.error(logger,
                                "Falha completa ao injetar anti-detection script: {}",
                                fallbackFailure.getMessage());
                        RuntimeException failure = new RuntimeException(
                                "Falha no registro persistente e no fallback do anti-detection script",
                                fallbackFailure);
                        failure.addSuppressed(ex);
                        throw failure;
                    }
                    return null;
                }));
    }
    private static String antiDetectionScript() {
        return AntiDetectionScripts.comprehensive();
    }
    /**
     * Torna o perfil ativo coerente com o IP de egress (timezone/idioma/geo/WebRTC seguem o IP do
     * proxy). Deve rodar ANTES dos overrides CDP e da injeção do surface script, pois muta o perfil
     * que ambos consomem. No-op seguro quando {@code egress} é nulo.
     */
    void applyEgressCoherence(EgressInfo egress) {
        effectiveMinimalIntlLocale = null;
        EgressCoherence.apply(fingerprintProfile, egress);
    }
    void applyHostLocale() {
        effectiveMinimalIntlLocale = null;
        EgressCoherence.applyHostLocale(fingerprintProfile, ZoneId.systemDefault(), Locale.getDefault());
    }
    String profileTimezoneId() {
        return fingerprintProfile == null ? "" : fingerprintProfile.timezoneId;
    }
    void restoreEgressBaseline() {
        effectiveMinimalIntlLocale = null;
        EgressCoherence.restore(fingerprintProfile, egressBaseline);
    }
    /**
     * Estende o contrato de fingerprint a workers, service workers e frames fora do processo.
     */
    CompletableFuture<Void> installWorkerSpoofAutoAttach(Target page, BaseTarget browser) {
        ChromeWorkerSpoofSupport support = usesMinimalCdpPolicy()
                ? ChromeWorkerSpoofSupport.nativeOopifOverrides(
                        fingerprintProfile, objectMapper, logger, this::recordRuntimeFailure,
                        auxiliaryBrowserFactory, nativeProcessLocaleBaselineConfigured)
                : new ChromeWorkerSpoofSupport(
                        fingerprintProfile, objectMapper, logger, this::buildSurfaceScript,
                        this::recordRuntimeFailure, auxiliaryBrowserFactory,
                        nativeProcessLocaleBaselineConfigured);
        workerSpoofSupport = support;
        return support.install(page, browser);
    }
    CompletableFuture<Void> installWorkerSpoofForRelatedPage(Target page, BaseTarget browser) {
        ChromeWorkerSpoofSupport support = workerSpoofSupport;
        if (support == null) {
            return CompletableFuture.failedFuture(new IllegalStateException(
                    "Worker/OOPIF spoof principal ainda não foi instalado"));
        }
        return support.installRelatedPage(page, browser);
    }
    boolean hasRuntimeFailure() {
        return runtimeFailure.get() != null;
    }
    Optional<String> runtimeFailureMessage() {
        Throwable failure = runtimeFailure.get();
        return failure == null ? Optional.empty() : Optional.ofNullable(failure.getMessage());
    }
    CompletableFuture<Void> closeWorkerSpoofSupport() {
        ChromeWorkerSpoofSupport support = workerSpoofSupport;
        workerSpoofSupport = null;
        return support == null ? CompletableFuture.completedFuture(null) : support.close();
    }
    void resetRuntimeFailure() {
        runtimeFailure.set(null);
    }
    private void recordRuntimeFailure(Throwable failure) {
        runtimeFailure.compareAndSet(null, failure);
    }
    CompletableFuture<Void> applyFingerprintProfileCdpOverrides(Target target) {
        return CompletableFuture.runAsync(() -> {
            boolean minimalCdp = usesMinimalCdpPolicy();
            // O Chrome calcula Device-Memory e os hints de qualidade da rede a partir do host e não
            // oferece override CDP nativo. Interceptamos antes de habilitar/navegar e alteramos
            // somente os hints que o próprio Chrome decidiu enviar; headers ausentes continuam
            // ausentes.
            try {
                FingerprintClientHintGuard.install(
                        target, fingerprintProfile, this::recordRuntimeFailure).join();
            } catch (Exception failure) {
                throw new IllegalStateException(
                        "Falha no guard essencial de Client Hints de memória", failure);
            }
            if (!minimalCdp) {
                try {
                    target.executeCdpCmd("Network.enable", null, null).join();
                } catch (Exception ignored) {
                    // Network may already be enabled.
                }
                String userAgent = sessionUserAgent.get();
                Map<String, Object> uaArgs = new HashMap<>();
                uaArgs.put("userAgent", userAgent);
                uaArgs.put("acceptLanguage",
                        FingerprintCdpOverrides.acceptLanguage(fingerprintProfile.languages));
                uaArgs.put("platform", fingerprintProfile.platform);
                uaArgs.put("userAgentMetadata",
                        fingerprintProfile.toUserAgentMetadata(
                                UserAgentResolver.extractChromeMajor(userAgent)));
                executeEssentialOverride(target, "Network.setUserAgentOverride", uaArgs);
            }
            if (fingerprintProfile.locale == null || fingerprintProfile.locale.isBlank()) {
                throw new IllegalStateException("Perfil de fingerprint sem locale configurado");
            }
            if (minimalCdp) {
                // O Chrome Linux canonicaliza regioes conforme seus locale packs (de-DE -> de,
                // ja-JP -> ja). Preservar esse valor nativo em todos os renderers evita um top
                // artificialmente diferente de popup/OOPIF. O preflight ainda rejeita fallback
                // para outra familia antes da primeira navegacao.
                effectiveMinimalIntlLocale(target);
            } else {
                executeEssentialOverride(target, "Emulation.setLocaleOverride",
                        Map.of("locale", fingerprintProfile.locale));
            }
            // Fail-closed: se o perfil não cravou timezoneId, derivar um coerente com o locale para
            // que o override CDP SEMPRE rode. Sem isto, o Chrome reporta o fuso REAL do host (UTC do
            // datacenter), incoerente com locale/IP. Em produção, EgressCoherence.apply deve
            // sobrescrever com o timezone do IP de egress.
            ChromeFingerprintTimezone.resolve(fingerprintProfile);
            executeEssentialOverride(target, "Emulation.setTimezoneOverride",
                    Map.of("timezoneId", fingerprintProfile.timezoneId));
            // Todas as media queries expostas como Client Hints pertencem ao perfil. Se apenas o
            // esquema de cores for fixado, reduced-motion/contrast/forced-colors continuam podendo
            // herdar o desktop físico e contradizer os headers observados pelo site.
            executeEssentialOverride(target, "Emulation.setEmulatedMedia",
                    FingerprintCdpOverrides.emulatedMedia(fingerprintProfile));
            // O override é target-local: a página muda, mas Service/Shared/DedicatedWorker podem
            // continuar expondo os cores nativos. minimal-cdp exige o baseline nativo e não emite
            // este comando; legacy mantém o comportamento histórico para compatibilidade.
            if (!minimalCdp) {
                executeEssentialOverride(target, "Emulation.setHardwareConcurrencyOverride",
                        Map.of("hardwareConcurrency", Math.max(1, fingerprintProfile.hardwareConcurrency)));
            }
            if (!minimalCdp) {
                // Compatibilidade do modo legacy: mantém a geometria sintética histórica.
                executeEssentialOverride(target, "Emulation.setDeviceMetricsOverride",
                        FingerprintCdpOverrides.headlessDeviceMetrics(fingerprintProfile));
            }
            if (minimalCdp) {
                if (MinimalCdpCapabilityContract.requiresGeolocationOverride(
                        fingerprintProfile.geolocation)) {
                    applyConfiguredGeolocationOverride(target);
                }
            } else if (fingerprintProfile.geolocation != null) {
                Double latitude = fingerprintProfile.geolocation.latitude;
                Double longitude = fingerprintProfile.geolocation.longitude;
                if ((latitude == null) != (longitude == null)) {
                    throw new IllegalStateException(
                            "Geolocalização do perfil deve informar latitude e longitude juntas");
                }
                if (latitude != null) {
                    applyConfiguredGeolocationOverride(target);
                }
            }
        });
    }
    private synchronized String effectiveMinimalIntlLocale(Target target) {
        if (effectiveMinimalIntlLocale == null) {
            effectiveMinimalIntlLocale = ChromeMinimalCdpLocale.captureAndValidate(
                    target, fingerprintProfile);
        }
        return effectiveMinimalIntlLocale;
    }
    private void applyConfiguredGeolocationOverride(Target target) {
        executeEssentialOverride(target, "Emulation.setGeolocationOverride",
                FingerprintCdpOverrides.geolocation(fingerprintProfile));
    }
    private void executeEssentialOverride(Target target, String command, Map<String, Object> arguments) {
        try {
            target.executeCdpCmd(command, arguments, null).join();
        } catch (Exception failure) {
            JavaDriverlessLogger.error(logger, "Fingerprint: override essencial {} falhou: {}",
                    command, failure.getMessage());
            throw new RuntimeException("Falha no override essencial de fingerprint: " + command, failure);
        }
    }
    CompletableFuture<Void> injectSeleniumProxySurfaceScript(Target target) {
        if (usesMinimalCdpPolicy()) {
            return CompletableFuture.completedFuture(null);
        }
        return CompletableFuture.supplyAsync(() -> {
            try {
                return objectMapper.writeValueAsString(fingerprintProfile);
            } catch (Exception e) {
                throw new RuntimeException("Falha ao serializar BrowserFingerprintProfile", e);
            }
        }).thenCompose(profileJson -> {
            String script = buildSurfaceScript(profileJson);
            Map<String, Object> scriptParams = new HashMap<>();
            scriptParams.put("source", script);
            scriptParams.put("runImmediately", true);
            // O registro persistente é obrigatório: Runtime.evaluate cobriria apenas o realm atual
            // (about:blank no startup) e desapareceria na primeira navegação.
            return enablePageForPersistentScript(target)
                    .thenCompose(ignored -> target.executeCdpCmd(
                            "Page.addScriptToEvaluateOnNewDocument", scriptParams, null))
                    .<Void>thenApply(v -> null);
        });
    }
    /**
     * Habilita o domínio Page antes de registrar um preload persistente.
     *
     * <p>No Chrome 150, {@code addScriptToEvaluateOnNewDocument(runImmediately=true)} sem
     * {@code Page.enable} aplica o script ao {@code about:blank} atual, mas o registro não alcança
     * a navegação seguinte feita sem espera de load. Isso cria um falso sucesso no startup e deixa
     * o documento real expor as superfícies nativas do host.</p>
     */
    private CompletableFuture<Void> enablePageForPersistentScript(Target target) {
        return target.executeCdpCmd("Page.enable", Map.of(), null).thenApply(ignored -> null);
    }
    /** Monta somente a superfície da página; workers pertencem ao auto-attach CDP. */
    private String buildSurfaceScript(String profileJson) {
        return SeleniumProxySurfaceScripts.build(profileJson);
    }
    private boolean usesMinimalCdpPolicy() {
        return BrowserFingerprintProfile.RUNTIME_POLICY_MINIMAL_CDP.equalsIgnoreCase(
                fingerprintProfile.runtimePolicy == null ? "" : fingerprintProfile.runtimePolicy.trim());
    }
}
