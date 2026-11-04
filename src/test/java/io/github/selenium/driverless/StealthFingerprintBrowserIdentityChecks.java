package io.github.selenium.driverless;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.*;

import io.github.selenium.javaDriverless.sync.SyncChrome;
import io.github.selenium.javaDriverless.types.BrowserFingerprintProfile;
import io.github.selenium.javaDriverless.types.ChromeDnsPolicy;
import io.github.selenium.javaDriverless.types.ChromeOptions;
import io.github.selenium.javaDriverless.types.ProxyConfig;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import static org.assertj.core.api.Assertions.*;

/**
 * Suite DEFINITIVA de stealth fingerprint.
 * 
 * Mapeia TODOS os vetores de detecção anti-bot e fingerprint.
 * Se algo quebrar, aparece AQUI com nome claro do que falhou.
 * 
 * Usa uma instância compartilhada para os checks internos e uma sessão isolada
 * para cada contrato externo, evitando degradação longitudinal entre sites.
 * 
 * Categorias:
 *   1-9:   AUTOMAÇÃO (webdriver, CDP vars, UA)
 *   10-19: NAVIGATOR (vendor, cores, memory, plugins, languages)
 *   20-29: SCREEN & VIEWPORT (dimensões, colorDepth, inner vs outer)
 *   30-39: WEBGL & CANVAS (vendor, renderer, estabilidade)
 *   40-49: AUDIO & MEDIA (AudioContext, mediaDevices)
 *   50-59: WINDOW & DOM (chrome object, permissions, prototypes)
 *   60-69: CONSISTÊNCIA (UA vs platform, screen vs viewport, leaks)
 *   70-79: SITES REAIS (sannysoft, nowsecure)
 */
abstract class StealthFingerprintBrowserIdentityChecks {
    protected static SyncChrome driver;
    protected static BrowserFingerprintProfile fingerprintProfile;
    protected static int protectedPortProbePort;
    protected static final ConcurrentLinkedQueue<String> protectedPortProbePaths = new ConcurrentLinkedQueue<>();
    private static HttpServer protectedPortProbeServer;
    private static ExecutorService protectedPortProbeExecutor;

    @BeforeAll
    public static void setUp() {
        StealthFingerprintExternalSiteSupport.resetCrossSiteEvidence();
        ChromeOptions options = new ChromeOptions();
        fingerprintProfile = ExternalFingerprintProfiles.loadBase(
                System.getProperty("java.driverless.externalFingerprintProfile"),
                System.getenv("JAVA_DRIVERLESS_EXTERNAL_FINGERPRINT_PROFILE"));
        startProtectedPortProbe();
        fingerprintProfile.ports.mode = "protect";
        ArrayList<Integer> protectedPorts = new ArrayList<>(fingerprintProfile.ports.protectedPorts);
        if (!protectedPorts.contains(protectedPortProbePort)) {
            protectedPorts.add(protectedPortProbePort);
        }
        fingerprintProfile.ports.protectedPorts = java.util.List.copyOf(protectedPorts);
        options.setFingerprintProfile(fingerprintProfile);
        configureExternalProxy(
                options,
                System.getProperty("java.driverless.externalProxy"),
                System.getenv("JAVA_DRIVERLESS_EXTERNAL_PROXY"));
        configureExternalDns(options,
                System.getProperty("java.driverless.externalDnsPolicy"),
                System.getenv("JAVA_DRIVERLESS_EXTERNAL_DNS_POLICY"),
                System.getProperty("java.driverless.externalDohTemplate"),
                System.getenv("JAVA_DRIVERLESS_EXTERNAL_DOH_TEMPLATE"));
        try {
            driver = new SyncChrome(options);
            assertThat(driver).isNotNull();
            // Navegar para site real — surface scripts não injetam em about:blank
            driver.get("https://bot.sannysoft.com");
            driver.sleep(4.0);
        } catch (RuntimeException | Error failure) {
            stopProtectedPortProbe();
            throw failure;
        }
    }

    @AfterAll
    public static void tearDown() {
        try {
            if (driver != null) driver.quit();
        } finally {
            stopProtectedPortProbe();
        }
    }

    @AfterEach
    void assertSharedDriverFingerprintRuntimeHealthy() {
        if (driver != null) {
            StealthFingerprintExternalSiteSupport.assertNoFingerprintRuntimeFailure(
                    driver, "shared fingerprint driver");
        }
    }

    // helper
    protected Object js(String script) {
        return driver.executeScript(script);
    }

    protected void openExternalSite(String url, double settleSeconds) {
        StealthFingerprintExternalSiteSupport.open(driver, url, settleSeconds);
    }

    protected String currentTitle() {
        return StealthFingerprintExternalSiteSupport.currentTitle(driver);
    }

    protected int waitForBodyTextLength(int minimumLength) {
        return StealthFingerprintExternalSiteSupport.waitForBodyTextLength(driver, minimumLength);
    }

    protected void assertExternalContract(String id, String url, double settleSeconds) {
        BrowserFingerprintProfile isolatedProfile = externalProfileForSite(id);
        SyncChrome isolatedDriver = null;
        try {
            isolatedDriver = createExternalDriver(isolatedProfile);
            StealthFingerprintExternalSiteSupport.assertContract(
                    isolatedDriver, isolatedProfile, id, url, settleSeconds);
        } finally {
            if (isolatedDriver != null) {
                isolatedDriver.quit();
            }
        }
    }

    protected void assertBrowserCheckDifferentialContract() {
        BrowserFingerprintProfile profile = externalProfileForSite("browsercheck");
        Map<String, Object> raw = captureRawExternalContract(
                profile, "browsercheck", "https://browsercheck.net/", 30.0);
        Map<String, Object> profiled = captureProfiledExternalContract(
                profile, "browsercheck", "https://browsercheck.net/", 30.0);
        assertBrowserCheckDifferentialResult(raw, profiled);
        StealthFingerprintExternalSiteSupport.recordBrowserCheckDifferentialEvidence(profile, profiled);
    }

    protected void assertIpheyDifferentialContract() {
        BrowserFingerprintProfile profile = externalProfileForSite("iphey");
        Map<String, Object> raw = captureRawExternalContract(
                profile, "iphey", "https://iphey.com/", 45.0);
        Map<String, Object> profiled = captureProfiledExternalContract(
                profile, "iphey", "https://iphey.com/", 45.0);
        assertIpheyDifferentialResult(raw, profiled, profile.webRtc.disableUdp);
    }

    private Map<String, Object> captureRawExternalContract(
            BrowserFingerprintProfile profile, String id, String url, double settleSeconds) {
        try (SyncChrome baseline = new SyncChrome(rawExternalTransportOptions())) {
            return StealthFingerprintExternalSiteSupport.captureContract(
                    baseline, profile, id, url, settleSeconds, false);
        }
    }

    private Map<String, Object> captureProfiledExternalContract(
            BrowserFingerprintProfile profile, String id, String url, double settleSeconds) {
        SyncChrome isolated = createExternalDriver(profile);
        try {
            return StealthFingerprintExternalSiteSupport.captureContract(
                    isolated, profile, id, url, settleSeconds, false);
        } finally {
            isolated.quit();
        }
    }

    @SuppressWarnings("unchecked")
    static void assertBrowserCheckDifferentialResult(
            Map<String, Object> raw, Map<String, Object> profiled) {
        if ("pass".equals(profiled.get("status"))) return;
        assertDifferentialCoreIsClean(profiled);
        assertThat((java.util.Collection<String>) profiled.get("decisiveExpectedMismatches"))
                .allMatch(value -> value.startsWith("expected-mismatch:browsercheck.realness ")
                        || value.startsWith("expected-mismatch:browsercheck.proxyDetected "));
        Map<String, Object> rawExtracted = (Map<String, Object>) raw.get("extracted");
        Map<String, Object> profiledExtracted = (Map<String, Object>) profiled.get("extracted");
        assertThat(profiledExtracted.get("publicIp")).isEqualTo(rawExtracted.get("publicIp"));
        assertThat(profiledExtracted.get("realness")).isEqualTo(rawExtracted.get("realness"));
        assertThat(profiledExtracted.get("proxyDetected")).isEqualTo(rawExtracted.get("proxyDetected"));
        assertThat(profiledExtracted.get("automationDetected")).isEqualTo("No");
        Set<String> profileHits = forbiddenHits(profiled);
        Set<String> rawHits = forbiddenHits(raw);
        assertThat(profileHits).isSubsetOf(
                "browsercheck-suspicious", "browsercheck-privacy-risks",
                "browsercheck-proxy-detected");
        assertThat(rawHits).containsAll(profileHits);
        System.out.println("  BrowserCheck baseline-equivalent: reputacao do egress nao piorou; "
                + "IP, timezone e automacao permaneceram exatos.");
    }

    @SuppressWarnings("unchecked")
    static void assertIpheyDifferentialResult(
            Map<String, Object> raw, Map<String, Object> profiled, boolean udpDisabled) {
        if ("pass".equals(profiled.get("status"))) return;
        assertDifferentialCoreIsClean(profiled);
        assertThat((java.util.Collection<String>) profiled.get("decisiveExpectedMismatches"))
                .allMatch(value -> value.startsWith("expected-mismatch:iphey.verdict ")
                        || udpDisabled && value.startsWith(
                                "expected-mismatch:iphey.network.WebRTC IP "));
        Map<String, Object> rawExtracted = (Map<String, Object>) raw.get("extracted");
        Map<String, Object> profiledExtracted = (Map<String, Object>) profiled.get("extracted");
        assertThat(profiledExtracted.get("verdict")).isEqualTo(rawExtracted.get("verdict"));
        Map<String, Object> bot = (Map<String, Object>) profiledExtracted.get("bot");
        assertThat(bot.get("hasWebdriver")).isEqualTo("false");
        Set<String> profileHits = forbiddenHits(profiled);
        Set<String> rawHits = forbiddenHits(raw);
        assertThat(profileHits).isSubsetOf("iphey-unreliable", "iphey-more-login");
        assertThat(rawHits).containsAll(profileHits.stream()
                .filter(value -> !"iphey-more-login".equals(value)).toList());
        if (profileHits.contains("iphey-more-login")) {
            assertThat(udpDisabled).isTrue();
            Map<String, Object> network = (Map<String, Object>) profiledExtracted.get("network");
            assertThat(String.valueOf(network.get("WebRTC IP"))).isIn("", "null");
        }
        System.out.println("  IPHey baseline-equivalent: o perfil nao adicionou sinal ao Chrome cru; "
                + "WebRTC protegido continua fail-closed.");
    }

    private static void assertDifferentialCoreIsClean(Map<String, Object> result) {
        assertThat(result.get("status")).isIn(
                "forbidden-signal", "value-mismatch", "privacy-policy-conflict");
        assertThat((java.util.Collection<?>) result.get("missing")).isEmpty();
        assertThat((java.util.Collection<?>) result.get("blockers")).isEmpty();
        assertThat((java.util.Collection<?>) result.get("decisiveMissing")).isEmpty();
        assertThat((java.util.Collection<?>) result.get("navigationBlockers")).isEmpty();
        assertThat(result.get("expected")).isInstanceOf(Map.class);
        Map<?, ?> expected = (Map<?, ?>) result.get("expected");
        assertThat((java.util.Collection<?>) expected.get("missing")).isEmpty();
    }

    private static Set<String> forbiddenHits(Map<String, Object> result) {
        Object forbidden = result.get("forbidden");
        assertThat(forbidden).isInstanceOf(Map.class);
        Object rawHits = ((Map<?, ?>) forbidden).get("hits");
        assertThat(rawHits).isInstanceOf(java.util.Collection.class);
        java.util.Collection<?> hits = (java.util.Collection<?>) rawHits;
        return hits.stream().map(String::valueOf)
                .collect(java.util.stream.Collectors.toUnmodifiableSet());
    }

    @SuppressWarnings("unchecked")
    protected void assertPixelScanDifferentialContract() {
        PixelScanState raw;
        try (SyncChrome baseline = new SyncChrome(rawExternalTransportOptions())) {
            baseline.get("https://pixelscan.net/fingerprint-check");
            StealthFingerprintExternalSiteActions.waitForPixelScanFinalReport(baseline);
            raw = pixelScanState(baseline);
        }

        BrowserFingerprintProfile profile = externalProfileForSite("pixelscan");
        SyncChrome isolated = createExternalDriver(profile);
        Map<String, Object> result;
        try {
            result = StealthFingerprintExternalSiteSupport.captureContract(
                    isolated, profile, "pixelscan",
                    "https://pixelscan.net/fingerprint-check", 20.0, false);
        } finally {
            isolated.quit();
        }
        assertPixelScanDifferentialResult(raw, result);
    }

    @SuppressWarnings("unchecked")
    static void assertPixelScanDifferentialResult(PixelScanState raw, Map<String, Object> result) {
        if ("pass".equals(result.get("status"))) return;

        assertThat(result.get("status")).as("PixelScan differential status")
                .isEqualTo("forbidden-signal");
        assertThat((java.util.Collection<?>) result.get("missing")).isEmpty();
        assertThat((java.util.Collection<?>) result.get("blockers")).isEmpty();

        Map<String, Object> extracted = (Map<String, Object>) result.get("extracted");
        PixelScanState profiled = pixelScanState(extracted);
        assertThat(profiled).as("PixelScan must not add a detector signal over raw Chrome")
                .isEqualTo(raw);

        java.util.List<String> allowedMismatchKeys = new java.util.ArrayList<>(java.util.List.of(
                "pixelscan.verdict", "pixelscan.fingerprintCard"));
        if ("detected".equals(raw.proxy())) {
            allowedMismatchKeys.add("pixelscan.proxyCard");
        }
        java.util.List<String> mismatchKeys = pixelScanMismatchKeys(result);
        assertThat(mismatchKeys).as("PixelScan differential mismatch keys")
                .containsExactlyInAnyOrderElementsOf(allowedMismatchKeys);
        int minimumExactMatches = 35 - allowedMismatchKeys.size();
        assertThat(((Number) result.get("matchedCount")).intValue())
                .as("PixelScan exact coverage mismatchKeys=%s", mismatchKeys)
                .isGreaterThanOrEqualTo(minimumExactMatches);
        System.out.println("  PixelScan baseline-equivalent: raw e perfil têm os mesmos sinais; "
                + minimumExactMatches + "+ valores exatos continuam coerentes.");
    }

    static java.util.List<String> pixelScanMismatchKeys(Map<String, Object> result) {
        Object raw = result.get("decisiveExpectedMismatches");
        if (!(raw instanceof java.util.Collection<?> mismatches)) return java.util.List.of();
        return mismatches.stream().map(String::valueOf).map(value -> {
            String prefix = value.startsWith("expected-mismatch:")
                    ? "expected-mismatch:" : value.startsWith("actual-missing:")
                    ? "actual-missing:" : "";
            if (prefix.isEmpty()) return "redacted-mismatch";
            int end = value.indexOf(" policy=", prefix.length());
            if (end < 0) end = value.length();
            return value.substring(prefix.length(), end).trim();
        }).distinct().sorted().toList();
    }

    private static PixelScanState pixelScanState(Map<String, Object> extracted) {
        return new PixelScanState(
                verdictStatus(String.valueOf(extracted.get("verdict"))),
                cardStatus(String.valueOf(extracted.get("fingerprintCard")), "masking"),
                cardStatus(String.valueOf(extracted.get("botCard")), "automated behavior"),
                cardStatus(String.valueOf(extracted.get("proxyCard")), "proxy"));
    }

    @SuppressWarnings("unchecked")
    private static PixelScanState pixelScanState(SyncChrome chrome) {
        Map<String, Object> raw = (Map<String, Object>) chrome.executeScript("""
                (() => ({
                  report: document.querySelector('pxlscn-consistency-check')?.innerText || '',
                  fingerprint: document.querySelector('pxlscn-fingerprint-masking')?.innerText || '',
                  bot: document.querySelector('pxlscn-bot-detection')?.innerText || '',
                  proxy: document.querySelector('pxlscn-proxy')?.innerText || ''
                }))()
                """);
        return new PixelScanState(
                verdictStatus(String.valueOf(raw.get("report"))),
                cardStatus(String.valueOf(raw.get("fingerprint")), "masking"),
                cardStatus(String.valueOf(raw.get("bot")), "automated behavior"),
                cardStatus(String.valueOf(raw.get("proxy")), "proxy"));
    }

    private static String verdictStatus(String value) {
        String normalized = value.toLowerCase(java.util.Locale.ROOT);
        if (normalized.contains("fingerprint is inconsistent")) return "inconsistent";
        if (normalized.contains("fingerprint is consistent")) return "consistent";
        return "unknown";
    }

    private static String cardStatus(String value, String signal) {
        String normalized = value.toLowerCase(java.util.Locale.ROOT);
        if (normalized.contains("no " + signal + " detected")) return "clean";
        if (normalized.contains(signal + " detected")) return "detected";
        return "unknown";
    }

    static record PixelScanState(
            String verdict, String fingerprint, String bot, String proxy) {
    }

    protected BrowserFingerprintProfile externalProfileForSite(String id) {
        return ExternalFingerprintProfiles.forSite(id, fingerprintProfile);
    }

    protected SyncChrome createExternalDriver(BrowserFingerprintProfile isolatedProfile) {
        ChromeOptions options = externalTransportOptions();
        options.setFingerprintProfile(isolatedProfile);
        return new SyncChrome(options);
    }

    static ChromeOptions externalTransportOptions() {
        return externalTransportOptions(
                System.getProperty("java.driverless.externalProxy"),
                System.getenv("JAVA_DRIVERLESS_EXTERNAL_PROXY"),
                System.getProperty("java.driverless.externalDnsPolicy"),
                System.getenv("JAVA_DRIVERLESS_EXTERNAL_DNS_POLICY"),
                System.getProperty("java.driverless.externalDohTemplate"),
                System.getenv("JAVA_DRIVERLESS_EXTERNAL_DOH_TEMPLATE"));
    }

    static ChromeOptions rawExternalTransportOptions() {
        return rawExternalTransportOptions(
                System.getProperty("java.driverless.externalProxy"),
                System.getenv("JAVA_DRIVERLESS_EXTERNAL_PROXY"),
                System.getProperty("java.driverless.externalDnsPolicy"),
                System.getenv("JAVA_DRIVERLESS_EXTERNAL_DNS_POLICY"),
                System.getProperty("java.driverless.externalDohTemplate"),
                System.getenv("JAVA_DRIVERLESS_EXTERNAL_DOH_TEMPLATE"));
    }

    static ChromeOptions rawExternalTransportOptions(
            String proxyProperty, String proxyEnvironment,
            String dnsPolicyProperty, String dnsPolicyEnvironment,
            String dohTemplateProperty, String dohTemplateEnvironment) {
        ChromeOptions options = new ChromeOptions();
        options.setFingerprintEnabled(false);
        configureExternalProxy(options, proxyProperty, proxyEnvironment);
        configureExternalDns(options,
                dnsPolicyProperty, dnsPolicyEnvironment,
                dohTemplateProperty, dohTemplateEnvironment);
        return options;
    }

    static ChromeOptions externalTransportOptions(
            String proxyProperty, String proxyEnvironment,
            String dnsPolicyProperty, String dnsPolicyEnvironment,
            String dohTemplateProperty, String dohTemplateEnvironment) {
        ChromeOptions options = new ChromeOptions();
        configureExternalProxy(
                options,
                proxyProperty,
                proxyEnvironment);
        configureExternalDns(options,
                dnsPolicyProperty,
                dnsPolicyEnvironment,
                dohTemplateProperty,
                dohTemplateEnvironment);
        return options;
    }

    static void configureExternalProxy(ChromeOptions options,
                                       String propertyValue,
                                       String environmentValue) {
        String configured = propertyValue;
        if (configured == null || configured.isBlank()) {
            configured = environmentValue;
        }
        if (configured == null || configured.isBlank()) {
            return;
        }
        try {
            ProxyConfig proxy = ProxyConfig.fromUrl(configured.trim());
            if (proxy.getType() != ProxyConfig.ProxyType.HTTP
                    && proxy.getType() != ProxyConfig.ProxyType.HTTPS
                    && proxy.getType() != ProxyConfig.ProxyType.SOCKS5) {
                throw new IllegalArgumentException();
            }
            options.setProxyConfig(proxy);
        } catch (RuntimeException invalid) {
            // Do not retain the raw value or parser cause: proxy URLs can contain credentials.
            throw new IllegalArgumentException("External proxy configuration is invalid");
        }
    }

    static void configureExternalDns(ChromeOptions options,
                                     String policyProperty,
                                     String policyEnvironment,
                                     String templateProperty,
                                     String templateEnvironment) {
        String policy = firstNonBlank(policyProperty, policyEnvironment);
        if (policy == null) {
            return;
        }
        ChromeDnsPolicy.apply(options, policy, firstNonBlank(templateProperty, templateEnvironment));
    }

    private static String firstNonBlank(String first, String second) {
        if (first != null && !first.isBlank()) {
            return first.trim();
        }
        return second == null || second.isBlank() ? null : second.trim();
    }

    private static void startProtectedPortProbe() {
        try {
            protectedPortProbePaths.clear();
            protectedPortProbeServer = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            protectedPortProbePort = protectedPortProbeServer.getAddress().getPort();
            protectedPortProbeServer.createContext("/", exchange -> {
                protectedPortProbePaths.add(exchange.getRequestURI().getPath());
                byte[] response = """
                        <!doctype html><html><head><link rel="icon" href="data:,"></head>
                        <body>java-driverless protected-port probe</body></html>
                        """.getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().set("Content-Type", "text/html; charset=utf-8");
                exchange.sendResponseHeaders(200, response.length);
                try (var body = exchange.getResponseBody()) {
                    body.write(response);
                }
            });
            protectedPortProbeExecutor = Executors.newFixedThreadPool(2, runnable -> {
                Thread thread = new Thread(runnable, "jdl-port-probe");
                thread.setDaemon(true);
                return thread;
            });
            protectedPortProbeServer.setExecutor(protectedPortProbeExecutor);
            protectedPortProbeServer.start();
        } catch (java.io.IOException e) {
            throw new IllegalStateException("Could not start protected-port probe", e);
        }
    }

    private static void stopProtectedPortProbe() {
        if (protectedPortProbeServer != null) {
            protectedPortProbeServer.stop(0);
            protectedPortProbeServer = null;
        }
        if (protectedPortProbeExecutor != null) {
            protectedPortProbeExecutor.shutdownNow();
            protectedPortProbeExecutor = null;
        }
    }

    // ════════════════════════════════════════════════════════════════
    // 1-9: AUTOMAÇÃO — Marcadores que gritam "bot"
    // ════════════════════════════════════════════════════════════════

    @Test @Order(1)
    @DisplayName("[AUTO] navigator.webdriver != true")
    void webdriverFalse() {
        Object r = js("navigator.webdriver");
        System.out.println("  navigator.webdriver = " + r);
        assertThat(r).isEqualTo(false);
    }

    @Test @Order(2)
    @DisplayName("[AUTO] User-Agent limpo (sem Headless)")
    void userAgentClean() {
        String ua = js("navigator.userAgent").toString();
        System.out.println("  UA = " + ua.substring(0, Math.min(80, ua.length())) + "...");
        assertThat(ua).doesNotContain("HeadlessChrome").doesNotContain("Headless").contains("Chrome/");
        String platformToken = switch (fingerprintProfile.uaPlatform) {
            case "Windows" -> "Windows NT";
            case "macOS" -> "Macintosh";
            default -> "X11; Linux";
        };
        assertThat(ua).contains(platformToken);
    }

    @Test @Order(3)
    @DisplayName("[AUTO] Sem variáveis CDP expostas no window")
    void noCdpLeaks() {
        Object r = js("""
            (function(){
              var leaks = [];
              var suspects = ['cdc_adoQpoasnfa76pfcZLmcfl_Array','cdc_adoQpoasnfa76pfcZLmcfl_Promise',
                'cdc_adoQpoasnfa76pfcZLmcfl_Symbol','$chrome_asyncScriptInfo',
                '__webdriver_script_function','__selenium_evaluate','__webdriver_evaluate',
                'selenium','webdriver','__nightmare','__detect_nightmare',
                '__phantompass_','__java_driverless_'];
              for (var i=0; i<suspects.length; i++) {
                if (typeof window[suspects[i]] !== 'undefined') leaks.push(suspects[i]);
              }
              // Scan ALL window keys for automation markers
              for (var key of Object.getOwnPropertyNames(window)) {
                if (key.startsWith('cdc_') || key.startsWith('__selenium') || key.startsWith('__webdriver')
                    || key.startsWith('__phantompass') || key.startsWith('__java_driverless')) {
                  if (leaks.indexOf(key) === -1) leaks.push(key);
                }
              }
              return leaks.length === 0 ? 'CLEAN' : leaks.join(',');
            })()""");
        System.out.println("  Window leaks = " + r);
        assertThat(r.toString()).isEqualTo("CLEAN");
    }

    @Test @Order(4)
    @DisplayName("[AUTO] Symbol storage invisível (não enumerable)")
    void symbolStorageHidden() {
        Object r = js("""
            (function(){
              var keys = Object.getOwnPropertyNames(window);
              var symbolKeys = Object.getOwnPropertySymbols(window);
              var jdlFound = symbolKeys.some(s => s.toString().includes('__jdl'));
              var jdlEnumerable = keys.some(k => k.includes('__jdl'));
              if (jdlEnumerable) return 'LEAKED_ENUMERABLE';
              if (!jdlFound) return 'NO_SYMBOL_OK';
              return 'SYMBOL_HIDDEN_OK';
            })()""");
        System.out.println("  Symbol storage = " + r);
        assertThat(r.toString()).isEqualTo("NO_SYMBOL_OK");
    }

    // ════════════════════════════════════════════════════════════════
    // 10-19: NAVIGATOR — Propriedades core do navegador
    // ════════════════════════════════════════════════════════════════

    @Test @Order(10)
    @DisplayName("[NAV] navigator.vendor corresponde ao perfil")
    void navigatorVendor() {
        Object r = js("navigator.vendor");
        System.out.println("  vendor = " + r);
        assertThat(r.toString()).isEqualTo(fingerprintProfile.vendor);
    }

    @Test @Order(11)
    @DisplayName("[NAV] navigator.platform corresponde ao perfil")
    void navigatorPlatform() {
        Object r = js("navigator.platform");
        System.out.println("  platform = " + r);
        assertThat(r.toString()).isEqualTo(fingerprintProfile.platform);
    }

    @Test @Order(12)
    @DisplayName("[NAV] hardwareConcurrency corresponde ao perfil")
    void navigatorCores() {
        int cores = ((Number) js("navigator.hardwareConcurrency")).intValue();
        System.out.println("  hardwareConcurrency = " + cores);
        assertThat(cores).isEqualTo(fingerprintProfile.hardwareConcurrency);
    }

    @Test @Order(13)
    @DisplayName("[NAV] deviceMemory corresponde ao perfil")
    void navigatorMemory() {
        Object r = js("typeof navigator.deviceMemory==='undefined'?-1:navigator.deviceMemory");
        int mem = ((Number) r).intValue();
        System.out.println("  deviceMemory = " + mem);
        assertThat(mem).isEqualTo(fingerprintProfile.deviceMemory);
    }

    @Test @Order(14)
    @DisplayName("[NAV] navigator.plugins preserva o PluginArray nativo do Chrome")
    void navigatorPlugins() {
        Object r = js("""
                (function(){
                  var plugins = navigator.plugins;
                  var proto = plugins && Object.getPrototypeOf(plugins);
                  var names = plugins ? Array.from(plugins, function(plugin){ return plugin.name; }) : [];
                  return {
                    tag: Object.prototype.toString.call(plugins),
                    instance: typeof PluginArray !== 'undefined' && plugins instanceof PluginArray,
                    array: Array.isArray(plugins),
                    length: plugins ? plugins.length : -1,
                    names: names.join('|'),
                    pluginsNative: !!plugins && Array.from(plugins).every(function(plugin){
                      return plugin instanceof Plugin && Object.prototype.toString.call(plugin) === '[object Plugin]';
                    }),
                    itemNative: !!proto && Function.prototype.toString.call(proto.item).includes('[native code]'),
                    namedItemNative: !!proto && Function.prototype.toString.call(proto.namedItem).includes('[native code]')
                  };
                })()
                """);
        System.out.println("  plugins = " + r);
        assertThat(r).isInstanceOf(java.util.Map.class);
        @SuppressWarnings("unchecked")
        java.util.Map<String, Object> plugins = (java.util.Map<String, Object>) r;
        assertThat(plugins.get("tag")).isEqualTo("[object PluginArray]");
        assertThat(plugins.get("instance")).isEqualTo(true);
        assertThat(plugins.get("array")).isEqualTo(false);
        assertThat(((Number) plugins.get("length")).intValue()).isEqualTo(5);
        assertThat(plugins.get("names")).isEqualTo(
                "PDF Viewer|Chrome PDF Viewer|Chromium PDF Viewer|Microsoft Edge PDF Viewer|WebKit built-in PDF");
        assertThat(plugins.get("pluginsNative")).isEqualTo(true);
        assertThat(plugins.get("itemNative")).isEqualTo(true);
        assertThat(plugins.get("namedItemNative")).isEqualTo(true);
    }

    @Test @Order(15)
    @DisplayName("[NAV] navigator.languages corresponde ao perfil")
    void navigatorLanguages() {
        Object r = js("Array.isArray(navigator.languages)&&navigator.languages.length>0?navigator.languages.join(','):'FAIL'");
        System.out.println("  languages = " + r);
        assertThat(r.toString()).isEqualTo(String.join(",", fingerprintProfile.languages));
    }

    @Test @Order(16)
    @DisplayName("[NAV] navigator.maxTouchPoints corresponde ao perfil")
    void navigatorTouch() {
        assertThat(js("'maxTouchPoints' in navigator && typeof navigator.maxTouchPoints === 'number'"))
                .isEqualTo(true);
        int tp = ((Number) js("navigator.maxTouchPoints")).intValue();
        System.out.println("  maxTouchPoints = " + tp);
        assertThat(tp).isEqualTo(fingerprintProfile.maxTouchPoints);
    }

    @Test @Order(17)
    @DisplayName("[NAV] navigator.connection corresponde ao perfil quando spoofado")
    void navigatorConnection() {
        Object r = js("""
                navigator.connection ? ({
                  effectiveType:navigator.connection.effectiveType,
                  downlink:navigator.connection.downlink,
                  rtt:navigator.connection.rtt,
                  saveData:navigator.connection.saveData,
                  type:('type' in navigator.connection ? navigator.connection.type : '')
                }) : null
                """);
        System.out.println("  connection.effectiveType = " + r);
        assertThat(r).isInstanceOf(java.util.Map.class);
        if ("spoof".equalsIgnoreCase(fingerprintProfile.connection.mode)) {
            @SuppressWarnings("unchecked")
            java.util.Map<String, Object> connection = (java.util.Map<String, Object>) r;
            String derivedType = fingerprintProfile.connection.downlink >= 10 && fingerprintProfile.connection.rtt <= 60
                    ? "4g" : fingerprintProfile.connection.downlink >= 1.5 && fingerprintProfile.connection.rtt <= 280
                    ? "3g" : fingerprintProfile.connection.downlink >= 0.4 ? "2g" : "slow-2g";
            assertThat(fingerprintProfile.connection.effectiveType)
                    .as("profile effectiveType must agree with downlink/rtt")
                    .isEqualTo(derivedType);
            assertThat(connection.get("effectiveType")).isEqualTo(derivedType);
            assertThat(((Number) connection.get("downlink")).doubleValue())
                    .isEqualTo(fingerprintProfile.connection.downlink);
            assertThat(((Number) connection.get("rtt")).intValue()).isEqualTo(fingerprintProfile.connection.rtt);
            assertThat(connection.get("saveData")).isEqualTo(fingerprintProfile.connection.saveData);
            assertThat(connection.get("type")).isEqualTo(fingerprintProfile.connection.type);
        }
    }

    @Test @Order(18)
    @DisplayName("[NAV] UA Client Hints de alta entropia correspondem ao perfil")
    void userAgentClientHintsMatchProfile() {
        assertThat(js("Boolean(navigator.userAgentData)")).isEqualTo(true);
        Object raw = driver.executeScript("""
                navigator.userAgentData.getHighEntropyValues(
                  ['architecture','bitness','model','platformVersion','wow64','uaFullVersion','fullVersionList']
                ).then(value => ({...value, classicMajor:
                  (navigator.userAgent.match(/Chrome\\/(\\d+)/) || [,''])[1]}))
                """, null, true);
        assertThat(raw).isInstanceOf(java.util.Map.class);
        @SuppressWarnings("unchecked")
        java.util.Map<String, Object> hints = (java.util.Map<String, Object>) raw;
        assertThat(hints.get("platform")).isEqualTo(fingerprintProfile.uaPlatform);
        assertThat(hints.get("mobile")).isEqualTo(fingerprintProfile.mobile);
        assertThat(hints.get("architecture")).isEqualTo(fingerprintProfile.architecture);
        assertThat(hints.get("bitness")).isEqualTo(fingerprintProfile.bitness);
        assertThat(hints.get("model")).isEqualTo(fingerprintProfile.model);
        assertThat(hints.get("platformVersion")).isEqualTo(fingerprintProfile.platformVersion);
        assertThat(hints.get("wow64")).isEqualTo(fingerprintProfile.wow64);
        String major = String.valueOf(hints.get("classicMajor"));
        assertThat(major).matches("\\d+");
        @SuppressWarnings("unchecked")
        java.util.List<java.util.Map<String, Object>> brands =
                (java.util.List<java.util.Map<String, Object>>) hints.get("brands");
        assertThat(brands).anySatisfy(brand -> {
            assertThat(brand.get("brand")).isEqualTo("Chromium");
            assertThat(brand.get("version")).isEqualTo(major);
        });
        assertThat(String.valueOf(hints.get("uaFullVersion"))).startsWith(major + ".");
        @SuppressWarnings("unchecked")
        java.util.List<java.util.Map<String, Object>> fullVersions =
                (java.util.List<java.util.Map<String, Object>>) hints.get("fullVersionList");
        assertThat(fullVersions).anySatisfy(brand -> {
            assertThat(brand.get("brand")).isEqualTo("Chromium");
            assertThat(String.valueOf(brand.get("version"))).startsWith(major + ".");
        });
    }

    // ════════════════════════════════════════════════════════════════
}
