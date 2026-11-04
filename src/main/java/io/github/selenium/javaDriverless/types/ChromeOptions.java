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
// Modificado por kaliiiiiiiiii | Aurin Aegerter
// Conversão para Java: Java Driverless
// Todas as modificações são licenciadas sob a licença fornecida em LICENSE.md

package io.github.selenium.javaDriverless.types;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Classe webdriver.ChromeOptions
 * <p>
 * <strong>Aviso:</strong> as opções não devem ser reutilizadas
 * </p>
 */
public class ChromeOptions extends ChromeOptionsMutableFields {
    @FunctionalInterface
    public interface CatalogLaunchValidator {
        CatalogLaunchLease acquire(ChromeOptions options);
    }

    @FunctionalInterface
    public interface CatalogLaunchLease extends AutoCloseable {
        @Override
        void close();
    }

    private CatalogLaunchValidator catalogLaunchValidator;
    private boolean catalogLaunchLeaseActive;
    private boolean catalogLaunchOptionsSealed;
    private Thread catalogLaunchMutationOwner;
    private boolean profileLaunchArgumentGuardBound;
    private boolean allowedProfileHeadless;
    private final Map<String, Integer> allowedManagedProfileArguments = new HashMap<>();
    private boolean egressLaunchBaselineCaptured;
    private Map<String, Object> egressWebRtcPrefsBaseline = Map.of();
    private final Set<String> egressOwnedWebRtcPrefs = new HashSet<>();
    private List<String> egressWebRtcPolicyArgumentsBaseline = List.of();
    private boolean egressOwnsWebRtcPolicyArgument;

    private static final Set<String> EGRESS_WEBRTC_PREF_KEYS = Set.of(
            "ip_handling_policy",
            "multiple_routes_enabled",
            "nonproxied_udp_enabled");

    /**
     * Define se a extensão Chrome deve ser adicionada por padrão.
     * <p>
     * <strong>Nota:</strong> Este campo não é mais usado para autenticação de proxy.
     * Proxies com autenticação não são suportados.
     * </p>
     */
    @Deprecated
    public boolean useExtension = false;

    /**
     * Construtor padrão que inicializa as opções com valores padrão.
     */
    public ChromeOptions() {
        this.proxySettings = new ChromeOptionsProxySettings();
        this.binaryLocation = null;
        this.env = ChromeOptionsDefaults.localizedEnvironment();
        this.extensionPaths = new ArrayList<>();
        this.debuggerAddress = null;
        this.userDataDir = null;
        this.downloadsDir = null;
        this.arguments = new ArrayList<>();
        this.prefs = new HashMap<>();
        this.autoCleanDirs = true;
        this.headless = false;
        this.startupUrl = "about:blank";
        this.isRemote = false;
        this.fingerprintEnabled = true;
        this.fingerprintProfile = null;

        this.prefs.putAll(ChromeOptionsPrefs.defaultPrefs());
        ChromeOptionsDefaults.addDefaultArguments(this);
        // Retrato dos padroes: setFingerprintEnabled(false) troca so estes e preserva o que o
        // chamador adicionou depois (proxy, sandbox, headless, prefs).
        this.constructorArguments = List.copyOf(this.arguments);
        this.constructorPrefs = ChromeOptionsPrefs.deepCopyMap(this.prefs);
        if (isHeadlessEnvEnabled()) {
            setHeadless(true);
        }
    }

    /**
     * Vincula uma validacao catalog-aware que sera executada imediatamente antes de cada tentativa
     * de startup. O vinculo e one-shot: codigo consumidor nao pode substituir ou remover o guard
     * emitido pelo catalogo depois que as opcoes foram criadas.
     *
     * <p>Perfis ad hoc criados diretamente com {@code new ChromeOptions()} nao precisam desse guard.</p>
     */
    public synchronized void bindCatalogLaunchValidator(CatalogLaunchValidator validator) {
        requireCatalogMutationAllowed();
        if (validator == null) {
            throw new IllegalArgumentException("Catalog launch validator must not be null");
        }
        if (catalogLaunchValidator != null) {
            throw new IllegalStateException("Catalog launch validator is already bound");
        }
        catalogLaunchValidator = validator;
    }

    /** Adquire o lease catalog-aware, ou um lease vazio para opcoes ad hoc. */
    public synchronized CatalogLaunchLease acquireCatalogLaunchLease() {
        CatalogLaunchValidator validator = catalogLaunchValidator;
        if (validator == null) return () -> { };
        if (catalogLaunchLeaseActive) {
            throw new IllegalStateException("Catalog launch lease is already active");
        }
        CatalogLaunchLease delegate = validator.acquire(this);
        catalogLaunchLeaseActive = true;
        catalogLaunchOptionsSealed = false;
        catalogLaunchMutationOwner = Thread.currentThread();
        return new GuardedCatalogLaunchLease(delegate);
    }

    public synchronized boolean hasCatalogLaunchValidator() {
        return catalogLaunchValidator != null;
    }

    /**
     * Seals the managed-argument baseline created by a structured SDK profile. Subsequent raw
     * user-data/debugger/proxy arguments must not bypass the profile isolation contract.
     */
    public synchronized void bindProfileLaunchArgumentGuard() {
        requireCatalogMutationAllowed();
        if (profileLaunchArgumentGuardBound) {
            throw new IllegalStateException("Profile launch argument guard is already bound");
        }
        profileLaunchArgumentGuardBound = true;
        allowedProfileHeadless = isHeadless();
        allowedManagedProfileArguments.clear();
        arguments.stream()
                .filter(ChromeOptions::isManagedLaunchArgument)
                .forEach(this::allowManagedProfileArgument);
    }

    /** Indica se as opcoes estao vinculadas ao guard de argumentos de profile estruturado. */
    public synchronized boolean isProfileLaunchArgumentGuardBound() {
        return profileLaunchArgumentGuardBound;
    }

    /** Validates managed isolation and process-security arguments immediately before launch. */
    public synchronized void validateProfileLaunchArguments() {
        if (!profileLaunchArgumentGuardBound) return;
        try {
            MinimalCdpProcessArgumentPolicy.requireSafe(arguments);
        } catch (IllegalArgumentException unsafe) {
            throw new IllegalStateException(unsafe.getMessage());
        }
        if (!allowedProfileHeadless && isHeadless()) {
            throw new IllegalStateException(
                    "Structured profile exige Chrome visível; --headless foi adicionado após a factory");
        }
        Map<String, Integer> observed = new HashMap<>();
        arguments.stream()
                .filter(ChromeOptions::isManagedLaunchArgument)
                .forEach(argument -> observed.merge(argument, 1, Integer::sum));
        if (!observed.equals(allowedManagedProfileArguments)) {
            throw new IllegalStateException(
                    "Structured profile rejected a managed browser argument override; "
                            + "use the dedicated profile/proxy/storage API");
        }
    }

    /** Internal startup path for arguments derived from validated structured state. */
    public synchronized void addManagedLaunchArgument(String argument) {
        requireCatalogMutationAllowed();
        if (!isManagedLaunchArgument(argument)) {
            throw new IllegalArgumentException("Argument is not managed by the launch engine");
        }
        super.addArgument(argument);
        if (profileLaunchArgumentGuardBound) allowManagedProfileArgument(argument);
    }

    /** Internal startup path for a freshly allocated isolated user-data directory. */
    public synchronized void setManagedLaunchUserDataDir(String dir) {
        requireCatalogMutationAllowed();
        String argument = "--user-data-dir=" + dir;
        super.setUserDataDir(dir);
        if (profileLaunchArgumentGuardBound) allowManagedProfileArgument(argument);
    }

    private void allowManagedProfileArgument(String argument) {
        allowedManagedProfileArguments.merge(argument, 1, Integer::sum);
    }

    private static boolean isManagedLaunchArgument(String argument) {
        String normalized = argument == null ? "" : argument.trim().toLowerCase(Locale.ROOT);
        int prefixLength = normalized.startsWith("--") ? 2
                : normalized.startsWith("-") || normalized.startsWith("/") ? 1 : 0;
        if (prefixLength == 0) return false;
        String body = normalized.substring(prefixLength);
        int end = body.length();
        for (int index = 0; index < body.length(); index++) {
            char current = body.charAt(index);
            if (current == '=' || Character.isWhitespace(current)) {
                end = index;
                break;
            }
        }
        String name = body.substring(0, end);
        return name.equals("user-data-dir")
                || name.startsWith("remote-debugging-")
                || name.startsWith("proxy-")
                || name.equals("no-proxy-server");
    }

    /**
     * Sela as opcoes catalog-aware imediatamente depois do spawn. A partir daqui nem mesmo a
     * thread de startup pode alterar o comando que representa a sessao em execucao.
     */
    public synchronized void sealCatalogLaunchOptions() {
        if (!catalogLaunchLeaseActive) return;
        requireCatalogMutationAllowed();
        catalogLaunchOptionsSealed = true;
        catalogLaunchMutationOwner = null;
    }

    @Override
    void requireCatalogMutationAllowed() {
        if (!catalogLaunchLeaseActive) return;
        if (catalogLaunchOptionsSealed || Thread.currentThread() != catalogLaunchMutationOwner) {
            throw new IllegalStateException("ChromeOptions cannot change during an active catalog launch");
        }
    }

    private final class GuardedCatalogLaunchLease implements CatalogLaunchLease {
        private final CatalogLaunchLease delegate;
        private boolean closed;

        private GuardedCatalogLaunchLease(CatalogLaunchLease delegate) {
            this.delegate = delegate;
        }

        @Override
        public void close() {
            synchronized (ChromeOptions.this) {
                if (closed) return;
                closed = true;
                try {
                    delegate.close();
                } finally {
                    catalogLaunchLeaseActive = false;
                    catalogLaunchOptionsSealed = false;
                    catalogLaunchMutationOwner = null;
                }
            }
        }
    }

    private static boolean isHeadlessEnvEnabled() {
        String value = System.getenv("JAVA_DRIVERLESS_HEADLESS");
        if (value == null) {
            return false;
        }
        String normalized = value.trim().toLowerCase(Locale.ROOT);
        return normalized.equals("true") || normalized.equals("1") || normalized.equals("yes");
    }

    @Override
    public List<String> getArguments() { return super.getArguments(); }

    @Override
    public synchronized void addArgument(String argument) {
        requireCatalogMutationAllowed();
        if (argument != null && (argument.startsWith("--webrtc-ip-handling-policy=")
                || argument.startsWith("--force-webrtc-ip-handling-policy="))) {
            egressOwnsWebRtcPolicyArgument = false;
        }
        super.addArgument(argument);
    }

    @Override
    public synchronized void addArguments(String... arguments) {
        requireCatalogMutationAllowed();
        if (arguments != null && java.util.Arrays.stream(arguments)
                .filter(java.util.Objects::nonNull)
                .anyMatch(argument -> argument.startsWith("--webrtc-ip-handling-policy=")
                        || argument.startsWith("--force-webrtc-ip-handling-policy="))) {
            egressOwnsWebRtcPolicyArgument = false;
        }
        super.addArguments(arguments);
    }

    @Override
    public String getUserAgent() { return super.getUserAgent(); }

    /** Aplica a política interna de argumentos do runtime minimal-cdp antes do launch. */
    public synchronized void applyMinimalCdpProcessArgumentPolicy() {
        requireCatalogMutationAllowed();
        ChromeOptionsMinimalCdpArguments.apply(this);
    }

    /** Restaura resíduos da tentativa anterior e captura o baseline da tentativa atual. */
    public synchronized void prepareEgressDerivedLaunchBaseline() {
        requireCatalogMutationAllowed();
        restoreEgressDerivedLaunchBaselineInternal();
        egressWebRtcPrefsBaseline = snapshotManagedWebRtcPrefs();
        egressWebRtcPolicyArgumentsBaseline = arguments.stream()
                .filter(argument -> argument.startsWith("--webrtc-ip-handling-policy=")
                        || argument.startsWith("--force-webrtc-ip-handling-policy="))
                .toList();
        egressLaunchBaselineCaptured = true;
    }

    /** Remove somente prefs/flags ainda pertencentes ao egress e encerra o baseline atual. */
    public synchronized void restoreEgressDerivedLaunchBaseline() {
        requireCatalogMutationAllowed();
        restoreEgressDerivedLaunchBaselineInternal();
    }

    /** Cleanup interno após o processo encerrar; permitido mesmo com options catalog-aware seladas. */
    public synchronized void restoreEgressDerivedLaunchBaselineAfterSession() {
        restoreEgressDerivedLaunchBaselineInternal();
    }

    private void restoreEgressDerivedLaunchBaselineInternal() {
        if (!egressLaunchBaselineCaptured) return;

        Map<String, Object> webRtc = mutableWebRtcPrefs(false);
        for (String key : Set.copyOf(egressOwnedWebRtcPrefs)) {
            if (egressWebRtcPrefsBaseline.containsKey(key)) {
                if (webRtc == null) webRtc = mutableWebRtcPrefs(true);
                webRtc.put(key, ChromeOptionsPrefs.deepCopyValue(egressWebRtcPrefsBaseline.get(key)));
            } else if (webRtc != null) {
                webRtc.remove(key);
            }
        }
        if (webRtc != null && webRtc.isEmpty()) {
            prefs.remove("webrtc");
        }
        if (egressOwnsWebRtcPolicyArgument) {
            arguments.removeIf(argument -> argument.startsWith("--webrtc-ip-handling-policy=")
                    || argument.startsWith("--force-webrtc-ip-handling-policy="));
            arguments.addAll(egressWebRtcPolicyArgumentsBaseline);
        }
        egressOwnedWebRtcPrefs.clear();
        egressOwnsWebRtcPolicyArgument = false;
        egressWebRtcPrefsBaseline = Map.of();
        egressWebRtcPolicyArgumentsBaseline = List.of();
        egressLaunchBaselineCaptured = false;
    }

    /** Aplica e marca somente os três prefs WebRTC que pertencem ao egress desta tentativa. */
    public synchronized void applyEgressDerivedWebRtcPrefs() {
        requireCatalogMutationAllowed();
        super.updatePref("webrtc.ip_handling_policy", "disable_non_proxied_udp");
        super.updatePref("webrtc.multiple_routes_enabled", false);
        super.updatePref("webrtc.nonproxied_udp_enabled", false);
        egressOwnedWebRtcPrefs.addAll(EGRESS_WEBRTC_PREF_KEYS);
    }

    /** Aplica e marca o argumento WebRTC que pertence ao egress desta tentativa. */
    public synchronized void applyEgressDerivedWebRtcPolicyArgument() {
        requireCatalogMutationAllowed();
        super.addArgument("--webrtc-ip-handling-policy=disable_non_proxied_udp");
        egressOwnsWebRtcPolicyArgument = true;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> mutableWebRtcPrefs(boolean create) {
        Object current = prefs.get("webrtc");
        if (current instanceof Map<?, ?> map) {
            return (Map<String, Object>) map;
        }
        if (!create) return null;
        Map<String, Object> created = new HashMap<>();
        prefs.put("webrtc", created);
        return created;
    }

    private Map<String, Object> snapshotManagedWebRtcPrefs() {
        Map<String, Object> current = mutableWebRtcPrefs(false);
        if (current == null) return Map.of();
        Map<String, Object> snapshot = new HashMap<>();
        for (String key : EGRESS_WEBRTC_PREF_KEYS) {
            if (current.containsKey(key)) {
                snapshot.put(key, ChromeOptionsPrefs.deepCopyValue(current.get(key)));
            }
        }
        return snapshot;
    }

    @Override
    void transferManagedWebRtcOwnership(String pref, Object value) {
        if (pref == null) return;
        if ("webrtc".equals(pref)) {
            if (value instanceof Map<?, ?> map) {
                for (String key : EGRESS_WEBRTC_PREF_KEYS) {
                    if (map.containsKey(key)) egressOwnedWebRtcPrefs.remove(key);
                }
            } else {
                egressOwnedWebRtcPrefs.clear();
            }
            return;
        }
        if (pref.startsWith("webrtc.")) {
            egressOwnedWebRtcPrefs.remove(pref.substring("webrtc.".length()));
        }
    }

    // Preserve the original reflection contract of this public facade. The package-private
    // implementation superclass remains an internal line-count split only.
    @Override public void setUserAgent(String value) { super.setUserAgent(value); }
    @Override public Map<String, Object> getPrefs() { return super.getPrefs(); }
    @Override public void updatePref(String pref, Object value) { super.updatePref(pref, value); }
    @Override public String getUserDataDir() { return super.getUserDataDir(); }
    @Override public void setUserDataDir(String dir) { super.setUserDataDir(dir); }
    @Override public String getDownloadsDir() { return super.getDownloadsDir(); }
    @Override public void setDownloadsDir(String path) { super.setDownloadsDir(path); }
    @Override public void setDeferredCatalogStorage(String userDataPath, String downloadsPath) {
        super.setDeferredCatalogStorage(userDataPath, downloadsPath);
    }
    @Override public boolean isHeadless() { return super.isHeadless(); }
    @Override public void setHeadless(boolean value) { super.setHeadless(value); }
    @Override public String getStartupUrl() { return super.getStartupUrl(); }
    @Override public void setStartupUrl(String url) { super.setStartupUrl(url); }
    @Override public String getSingleProxy() { return super.getSingleProxy(); }
    @Override public void setSingleProxy(String proxy) { super.setSingleProxy(proxy); }
    @Override public void enableAuthenticatedProxyBridge(boolean enabled) {
        super.enableAuthenticatedProxyBridge(enabled);
    }
    @Override public boolean isAuthenticatedProxyBridgeEnabled() {
        return super.isAuthenticatedProxyBridgeEnabled();
    }
    @Override public void setAuthenticatedProxyBridge(String proxyUrl) {
        super.setAuthenticatedProxyBridge(proxyUrl);
    }
    @Override public void setProxyConfig(ProxyConfig proxyConfig) { super.setProxyConfig(proxyConfig); }
    @Override public ProxyConfig getProxyConfig() { return super.getProxyConfig(); }
    @Override public void setProxyFromString(String proxyString, ProxyConfig.ProxyType type) {
        super.setProxyFromString(proxyString, type);
    }
    @Override public void setHttpProxy(String proxyString) { super.setHttpProxy(proxyString); }
    @Override public void setHttpsProxy(String proxyString) { super.setHttpsProxy(proxyString); }
    @Override public void setSocks5Proxy(String proxyString) { super.setSocks5Proxy(proxyString); }
    @Override public String getBinaryLocation() { return super.getBinaryLocation(); }
    @Override public void setBinaryLocation(String value) { super.setBinaryLocation(value); }
    @Override public Map<String, String> getEnv() { return super.getEnv(); }
    @Override public void setEnv(Map<String, String> env) { super.setEnv(env); }
    @Override public void addExtension(String path) { super.addExtension(path); }
    @Override public String getDebuggerAddress() { return super.getDebuggerAddress(); }
    @Override public void setDebuggerAddress(String value) { super.setDebuggerAddress(value); }
    @Override public CdpTransport getCdpTransport() { return super.getCdpTransport(); }
    @Override public void setCdpTransport(CdpTransport value) { super.setCdpTransport(value); }
    @Override public boolean isAutoCleanDirs() { return super.isAutoCleanDirs(); }
    @Override public void setAutoCleanDirs(boolean enabled) { super.setAutoCleanDirs(enabled); }
    @Override public void enableMobile(String androidPackage, String androidActivity, String deviceSerial) {
        super.enableMobile(androidPackage, androidActivity, deviceSerial);
    }
    @Override public void ignoreLocalProxyEnvironmentVariables() {
        super.ignoreLocalProxyEnvironmentVariables();
    }
    @Deprecated
    @Override public void addExperimentalOption(String name, Object value) {
        super.addExperimentalOption(name, value);
    }
    @Override public List<String> getExtensionPaths() { return super.getExtensionPaths(); }
    @Override public boolean isRemote() { return super.isRemote(); }
    @Override public BrowserFingerprintProfile getFingerprintProfile() { return super.getFingerprintProfile(); }
    @Override public boolean isFingerprintEnabled() { return super.isFingerprintEnabled(); }
    @Override public void setFingerprintEnabled(boolean enabled) { super.setFingerprintEnabled(enabled); }
    @Override public void setFingerprintProfile(BrowserFingerprintProfile profile) {
        super.setFingerprintProfile(profile);
    }
    @Override public void setEgressInfo(EgressInfo egressInfo) { super.setEgressInfo(egressInfo); }
    @Override public void setEgressResolver(EgressResolver resolver) { super.setEgressResolver(resolver); }
    @Override public void setSessionProfile(BrowserSessionProfile profile) { super.setSessionProfile(profile); }
    @Override public BrowserSessionProfile getSessionProfile() { return super.getSessionProfile(); }
    @Override public EgressResolver getEgressResolver() { return super.getEgressResolver(); }
    @Override public EgressInfo getEgressInfo() { return super.getEgressInfo(); }
}
