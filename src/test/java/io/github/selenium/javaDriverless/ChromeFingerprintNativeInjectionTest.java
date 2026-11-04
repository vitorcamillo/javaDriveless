package io.github.selenium.javaDriverless;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.util.Map;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.NullNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.slf4j.LoggerFactory;

import io.github.selenium.javaDriverless.types.BaseTarget;
import io.github.selenium.javaDriverless.types.BrowserFingerprintProfile;
import io.github.selenium.javaDriverless.types.ChromeOptions;
import io.github.selenium.javaDriverless.types.Target;
import io.github.selenium.javaDriverless.utils.UserAgentResolver;

/**
 * Contrato da nativização das injeções de fingerprint (regra de ouro: provar o transporte CDP sem
 * subir Chrome). Prova que, no caminho de SUCESSO, o spoof é registrado SÓ via
 * Page.enable + Page.addScriptToEvaluateOnNewDocument (runImmediately) e NENHUM
 * Runtime.evaluate/executeScript imediato é emitido; falha no preparo ou no registro persistente
 * aborta em vez de fingir sucesso apenas no realm atual. A injeção pausada de iframe/popup usa um
 * único registro com runImmediately.
 */
class ChromeFingerprintNativeInjectionTest extends ChromeFingerprintNativeInjectionSupport {
    @Test
    void headfulSpoofedScreenPinsTheNativeWindowSizeToTheProfile() {
        ChromeOptions options = new ChromeOptions();
        BrowserFingerprintProfile profile = BrowserFingerprintProfile.fromEnvironment();
        profile.screen.mode = "spoof";
        profile.screen.width = 1365;
        profile.screen.availHeight = 860;

        new ChromeFingerprintSupport(options, profile, new ObjectMapper(),
                LoggerFactory.getLogger(ChromeFingerprintNativeInjectionTest.class), () -> "Chrome/150")
                .configureHeadlessFingerprintArgs();

        assertTrue(options.getArguments().contains("--window-size=1365,852"));
    }

    @Test
    void headfulRealScreenDoesNotForceAWindowSize() {
        ChromeOptions options = new ChromeOptions();
        BrowserFingerprintProfile profile = BrowserFingerprintProfile.fromEnvironment();
        profile.screen.mode = "real";

        new ChromeFingerprintSupport(options, profile, new ObjectMapper(),
                LoggerFactory.getLogger(ChromeFingerprintNativeInjectionTest.class), () -> "Chrome/150")
                .configureHeadlessFingerprintArgs();

        assertTrue(options.getArguments().stream().noneMatch(value -> value.startsWith("--window-size=")));
    }

    @Test
    void minimalPureHeadlessFailsBeforeMutatingChromeArguments() {
        ChromeOptions options = new ChromeOptions();
        options.setHeadless(true);
        BrowserFingerprintProfile profile = supportedMinimalProfile();
        profile.screen.mode = "real";
        java.util.List<String> argumentsBefore = java.util.List.copyOf(options.getArguments());

        ChromeFingerprintSupport support = new ChromeFingerprintSupport(options, profile, new ObjectMapper(),
                LoggerFactory.getLogger(ChromeFingerprintNativeInjectionTest.class),
                () -> UserAgentResolver.buildForProfile(profile));

        org.assertj.core.api.Assertions.assertThatThrownBy(() ->
                support.configureHeadlessFingerprintArgs(Map.of()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("--headless")
                .hasMessageContaining("HeadlessChrome")
                .hasMessageContaining("Xvfb");
        assertEquals(argumentsBefore, options.getArguments());
        assertTrue(options.getArguments().stream().noneMatch(value -> value.startsWith("--use-gl=")));
        assertTrue(options.getArguments().stream().noneMatch(value -> value.startsWith("--use-angle=")));
    }

    @Test
    void minimalCdpRejectsExplicitUserAgentInsteadOfSuppressingNativeHighEntropyHints() {
        ChromeOptions options = new ChromeOptions();
        options.setUserAgent("Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 "
                + "(KHTML, like Gecko) Chrome/150.0.0.0 Safari/537.36");
        BrowserFingerprintProfile profile = supportedMinimalProfile();
        java.util.List<String> argumentsBefore = java.util.List.copyOf(options.getArguments());
        ChromeFingerprintSupport support = new ChromeFingerprintSupport(
                options, profile, new ObjectMapper(),
                LoggerFactory.getLogger(ChromeFingerprintNativeInjectionTest.class),
                () -> UserAgentResolver.buildForProfile(profile));

        org.assertj.core.api.Assertions.assertThatThrownBy(() ->
                support.configureHeadlessFingerprintArgs(Map.of()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("User-Agent nativo")
                .hasMessageContaining("--user-agent/browser.userAgent")
                .hasMessageContaining("UA-CH de alta entropia");
        assertEquals(argumentsBefore, options.getArguments());
    }

    @Test
    void minimalCdpPinsAndValidatesTheExactSelectedChromeBinaryVersion(
            @TempDir Path tempDir) throws Exception {
        Path fakeChrome = tempDir.resolve("versioned-chrome.sh");
        Files.writeString(fakeChrome, """
                #!/bin/sh
                printf 'Google Chrome 150.0.7871.114\\n'
                """);
        assertTrue(fakeChrome.toFile().setExecutable(true));

        BrowserFingerprintProfile detected = supportedMinimalProfile();
        detected.browserVersion = "";
        ChromeOptions detectedOptions = new ChromeOptions();
        detectedOptions.setBinaryLocation(fakeChrome.toString());
        new ChromeFingerprintSupport(
                detectedOptions, detected, new ObjectMapper(),
                LoggerFactory.getLogger(ChromeFingerprintNativeInjectionTest.class),
                () -> UserAgentResolver.buildForProfile(detected))
                .configureHeadlessFingerprintArgs(Map.of());
        assertEquals("150.0.7871.114", detected.browserVersion);

        BrowserFingerprintProfile stale = supportedMinimalProfile();
        stale.browserVersion = "149.0.0.1";
        ChromeOptions staleOptions = new ChromeOptions();
        staleOptions.setBinaryLocation(fakeChrome.toString());
        ChromeFingerprintSupport staleSupport = new ChromeFingerprintSupport(
                staleOptions, stale, new ObjectMapper(),
                LoggerFactory.getLogger(ChromeFingerprintNativeInjectionTest.class),
                () -> UserAgentResolver.buildForProfile(stale));

        org.assertj.core.api.Assertions.assertThatThrownBy(() ->
                staleSupport.configureHeadlessFingerprintArgs(Map.of()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("browserVersion igual ao binário selecionado")
                .hasMessageContaining("perfil=149.0.0.1")
                .hasMessageContaining("Chrome=150.0.7871.114");

        Path failedVersionProbe = tempDir.resolve("failed-version-probe.sh");
        Files.writeString(failedVersionProbe, """
                #!/bin/sh
                printf 'Google Chrome 150.0.7871.114\\n'
                exit 7
                """);
        assertTrue(failedVersionProbe.toFile().setExecutable(true));
        BrowserFingerprintProfile failed = supportedMinimalProfile();
        ChromeOptions failedOptions = new ChromeOptions();
        failedOptions.setBinaryLocation(failedVersionProbe.toString());
        ChromeFingerprintSupport failedSupport = new ChromeFingerprintSupport(
                failedOptions, failed, new ObjectMapper(),
                LoggerFactory.getLogger(ChromeFingerprintNativeInjectionTest.class),
                () -> UserAgentResolver.buildForProfile(failed));
        org.assertj.core.api.Assertions.assertThatThrownBy(() ->
                failedSupport.configureHeadlessFingerprintArgs(Map.of()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("não conseguiu detectar a versão")
                .hasMessageContaining("antes do launch");
    }

    @Test
    void minimalCdpDefaultGateUsesTheSameExplicitEnvironmentThatChromeWillReceive(
            @TempDir Path tempDir) throws Exception {
        Path fakeChrome = tempDir.resolve("versioned-chrome.sh");
        Files.writeString(fakeChrome, """
                #!/bin/sh
                printf 'Google Chrome 150.0.7871.114\\n'
                """);
        assertTrue(fakeChrome.toFile().setExecutable(true));

        BrowserFingerprintProfile profile = supportedMinimalProfile();
        profile.screen.mode = "spoof";
        profile.screen.width = 1365;
        profile.screen.height = 900;
        profile.screen.availWidth = 1365;
        profile.screen.availHeight = 860;
        profile.screen.colorDepth = 24;
        profile.screen.pixelDepth = 24;
        profile.screen.deviceScaleFactor = 1.0;
        ChromeOptions options = new ChromeOptions();
        options.setBinaryLocation(fakeChrome.toString());
        options.setEnv(Map.of(
                "JAVA_DRIVERLESS_NATIVE_SCREEN_PROVISIONED", "1",
                "JAVA_DRIVERLESS_NATIVE_SCREEN_WIDTH", "1365",
                "JAVA_DRIVERLESS_NATIVE_SCREEN_HEIGHT", "900",
                "JAVA_DRIVERLESS_NATIVE_SCREEN_AVAIL_WIDTH", "1365",
                "JAVA_DRIVERLESS_NATIVE_SCREEN_AVAIL_HEIGHT", "860",
                "JAVA_DRIVERLESS_NATIVE_SCREEN_COLOR_DEPTH", "24",
                "JAVA_DRIVERLESS_NATIVE_SCREEN_PIXEL_DEPTH", "24",
                "JAVA_DRIVERLESS_NATIVE_SCREEN_DPR", "1"));

        new ChromeFingerprintSupport(
                options, profile, new ObjectMapper(),
                LoggerFactory.getLogger(ChromeFingerprintNativeInjectionTest.class),
                () -> UserAgentResolver.buildForProfile(profile))
                .configureHeadlessFingerprintArgs();

        assertEquals("150.0.7871.114", profile.browserVersion);
        assertTrue(options.getArguments().contains("--window-size=1365,852"));
    }

    @Test
    void minimalCdpSelectsRequestedLanguageFamilyWithoutInstalledPosixLocale(
            @TempDir Path tempDir) throws Exception {
        ChromeOptions options = new ChromeOptions();
        options.setBinaryLocation(versionedChrome(tempDir).toString());
        BrowserFingerprintProfile profile = supportedMinimalProfile();
        profile.locale = "pt-BR";
        profile.languages = java.util.List.of("pt-BR", "pt", "en-US", "en");

        new ChromeFingerprintSupport(options, profile, new ObjectMapper(),
                LoggerFactory.getLogger(ChromeFingerprintNativeInjectionTest.class),
                () -> UserAgentResolver.buildForProfile(profile))
                .configureHeadlessFingerprintArgs(Map.of());

        assertTrue(options.getArguments().contains("--lang=pt-BR"));
        assertTrue(options.getArguments().contains("--accept-lang=pt-BR,pt,en-US,en"));
        assertEquals(1, options.getArguments().stream()
                .filter(value -> value.startsWith("--lang="))
                .count());
        assertEquals(1, options.getArguments().stream()
                .filter(value -> value.startsWith("--accept-lang="))
                .count());
        assertFalse(options.getArguments().contains("--lang=en-US"));
        assertFalse(options.getArguments().contains("--accept-lang=en-US,en"));
        assertEquals("C.UTF-8", options.getEnv().get("LANG"));
        assertEquals("C.UTF-8", options.getEnv().get("LC_ALL"));
        assertEquals("C.UTF-8", options.getEnv().get("LC_MESSAGES"));
        assertEquals("pt-BR:pt:en-US:en", options.getEnv().get("LANGUAGE"));
        assertTrue(options.getArguments().stream().noneMatch(value -> value.startsWith("--use-gl=")));
        assertTrue(options.getArguments().stream().noneMatch(value -> value.startsWith("--use-angle=")));
    }

    @Test
    void legacyProfileConfiguresLocaleAsNativeProcessBaseline() {
        ChromeOptions options = new ChromeOptions();
        BrowserFingerprintProfile profile = BrowserFingerprintProfile.brLinux();
        profile.locale = "pt-BR";
        profile.languages = java.util.List.of("pt-BR", "pt", "en-US", "en");
        options.setFingerprintProfile(profile);

        new ChromeFingerprintSupport(options, profile, new ObjectMapper(),
                LoggerFactory.getLogger(ChromeFingerprintNativeInjectionTest.class),
                () -> UserAgentResolver.buildForProfile(profile))
                .configureHeadlessFingerprintArgs(Map.of());

        assertTrue(options.getArguments().contains("--lang=pt-BR"));
        assertTrue(options.getArguments().contains("--accept-lang=pt-BR,pt,en-US,en"));
        assertEquals("C.UTF-8", options.getEnv().get("LANG"));
        assertEquals("pt-BR:pt:en-US:en", options.getEnv().get("LANGUAGE"));
        @SuppressWarnings("unchecked")
        Map<String, Object> intl = (Map<String, Object>) options.getPrefs().get("intl");
        assertEquals("pt-BR,pt,en-US,en", intl.get("accept_languages"));
    }

    @Test
    void minimalCdpNativeLocaleReplacesConflictingManualArguments(
            @TempDir Path tempDir) throws Exception {
        ChromeOptions options = new ChromeOptions();
        options.setBinaryLocation(versionedChrome(tempDir).toString());
        options.addArgument("--lang=pt-BR");
        options.addArgument("--accept-lang=pt-BR,pt");
        BrowserFingerprintProfile profile = supportedMinimalProfile();
        profile.locale = "en-US";
        profile.languages = java.util.List.of("en-US", "en");

        new ChromeFingerprintSupport(options, profile, new ObjectMapper(),
                LoggerFactory.getLogger(ChromeFingerprintNativeInjectionTest.class),
                () -> UserAgentResolver.buildForProfile(profile))
                .configureHeadlessFingerprintArgs(Map.of());

        org.assertj.core.api.Assertions.assertThat(options.getArguments())
                .contains("--lang=en-US", "--accept-lang=en-US,en")
                .doesNotContain("--lang=pt-BR", "--accept-lang=pt-BR,pt");
        assertEquals(1, options.getArguments().stream()
                .filter(value -> value.startsWith("--lang="))
                .count());
        assertEquals(1, options.getArguments().stream()
                .filter(value -> value.startsWith("--accept-lang="))
                .count());
    }

    @Test
    void minimalCdpDropsProvenSyntheticDefaultsButPreservesWorkingWebglSwitches(
            @TempDir Path tempDir) throws Exception {
        ChromeOptions options = new ChromeOptions();
        options.setBinaryLocation(versionedChrome(tempDir).toString());
        options.addArgument("--disable-features=AsyncDns,TranslateUI,InterestCohort");
        BrowserFingerprintProfile profile = supportedMinimalProfile();

        new ChromeFingerprintSupport(options, profile, new ObjectMapper(),
                LoggerFactory.getLogger(ChromeFingerprintNativeInjectionTest.class),
                () -> UserAgentResolver.buildForProfile(profile))
                .configureHeadlessFingerprintArgs(Map.of());

        org.assertj.core.api.Assertions.assertThat(options.getArguments())
                .doesNotContain(
                        "--disable-auto-reload",
                        "--no-service-autorun",
                        "--disable-backgrounding-occluded-windows",
                        "--disable-renderer-backgrounding",
                        "--disable-background-timer-throttling",
                        "--wm-window-animations-disabled",
                        "--animation-duration-scale=0",
                        "--enable-privacy-sandbox-ads-apis",
                        "--enable-webgl",
                        "--disable-accelerated-2d-canvas",
                        "--disable-blink-features=AutomationControlled",
                        "--disable-hang-monitor",
                        "--disable-ipc-flooding-protection",
                        "--disable-features=TranslateUI,InterestCohort")
                .contains(
                        "--ignore-gpu-blocklist",
                        "--no-pings",
                        "--mute-audio",
                        "--metrics-recording-only",
                        "--disable-features=AsyncDns");
        org.assertj.core.api.Assertions.assertThat(options.getArguments())
                .noneMatch(argument -> argument.contains("TranslateUI")
                        || argument.contains("InterestCohort"));
    }

    @Test
    void minimalCdpRejectsCrossOsProfileBeforePreparingLocalChromeArguments(
            @TempDir Path tempDir) throws Exception {
        Path fakeChrome = tempDir.resolve("versioned-chrome.sh");
        Files.writeString(fakeChrome, """
                #!/bin/sh
                printf 'Google Chrome 150.0.7871.114\\n'
                """);
        assertTrue(fakeChrome.toFile().setExecutable(true));
        String hostOs = System.getProperty("os.name", "").toLowerCase(java.util.Locale.ROOT);
        BrowserFingerprintProfile profile = hostOs.contains("win") && !hostOs.contains("darwin")
                ? BrowserFingerprintProfile.brLinux()
                : BrowserFingerprintProfile.usWindows11();
        profile.runtimePolicy = BrowserFingerprintProfile.RUNTIME_POLICY_MINIMAL_CDP;
        ChromeOptions options = new ChromeOptions();
        options.setBinaryLocation(fakeChrome.toString());
        java.util.List<String> argumentsBeforeValidation = java.util.List.copyOf(options.getArguments());
        ChromeFingerprintSupport support = new ChromeFingerprintSupport(
                options, profile, new ObjectMapper(),
                LoggerFactory.getLogger(ChromeFingerprintNativeInjectionTest.class),
                () -> UserAgentResolver.buildForProfile(profile));

        org.assertj.core.api.Assertions.assertThatThrownBy(support::configureHeadlessFingerprintArgs)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("minimal-cdp")
                .hasMessageContaining("host");
        org.assertj.core.api.Assertions.assertThat(options.getArguments())
                .containsExactlyElementsOf(argumentsBeforeValidation);
    }

    private static Path versionedChrome(Path tempDir) throws Exception {
        Path fakeChrome = tempDir.resolve("versioned-chrome.sh");
        Files.writeString(fakeChrome, """
                #!/bin/sh
                printf 'Google Chrome 150.0.7871.114\\n'
                """);
        assertTrue(fakeChrome.toFile().setExecutable(true));
        return fakeChrome;
    }

}
