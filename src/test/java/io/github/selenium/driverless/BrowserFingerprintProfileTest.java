package io.github.selenium.driverless;

import io.github.selenium.javaDriverless.types.BrowserFingerprintProfile;
import io.github.selenium.javaDriverless.types.FingerprintCdpOverrides;
import io.github.selenium.javaDriverless.scripts.SeleniumProxySurfaceScripts;
import io.github.selenium.javaDriverless.scripts.SeleniumProxyWorkerHookScripts;
import io.github.selenium.javaDriverless.scripts.SeleniumProxyWorkerSurfaceScripts;
import io.github.selenium.javaDriverless.utils.UserAgentResolver;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Locale;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BrowserFingerprintProfileTest {
    @Test
    void canvasNoiseScopeDefaultsToFullIsolationAndRejectsUnknownValues() {
        BrowserFingerprintProfile profile = new BrowserFingerprintProfile();
        profile.canvas.scope = "";

        profile.applyDefaults();

        assertEquals("all", profile.canvas.scope);
        profile.canvas.scope = "detached";
        profile.applyDefaults();
        assertEquals("detached", profile.canvas.scope);
        profile.canvas.scope = "visible-only";
        assertThrows(IllegalArgumentException.class, profile::applyDefaults);
    }

    @Test
    void colorSchemeIsAlwaysProfileOwnedAndSyntheticProfilesVaryDeterministically() {
        BrowserFingerprintProfile blank = new BrowserFingerprintProfile();
        blank.colorScheme = "";
        blank.prefersReducedMotion = "";
        blank.prefersReducedTransparency = "";
        blank.prefersContrast = "";
        blank.forcedColors = "";
        blank.applyDefaults();

        assertEquals("light", blank.colorScheme);
        assertEquals("no-preference", blank.prefersReducedMotion);
        assertEquals("no-preference", blank.prefersReducedTransparency);
        assertEquals("no-preference", blank.prefersContrast);
        assertEquals("none", blank.forcedColors);
        assertEquals(
                BrowserFingerprintProfile.randomProfile("linux", 1234L).colorScheme,
                BrowserFingerprintProfile.randomProfile("linux", 1234L).colorScheme);
        assertTrue(java.util.stream.LongStream.rangeClosed(1, 64)
                .mapToObj(seed -> BrowserFingerprintProfile.randomProfile("linux", seed).colorScheme)
                .collect(java.util.stream.Collectors.toSet())
                .containsAll(java.util.Set.of("light", "dark")));
    }

    @Test
    void generatedProfilesEnableSeededWebglPixelNoise() {
        List<BrowserFingerprintProfile> profiles = List.of(
                BrowserFingerprintProfile.usWindows10(),
                BrowserFingerprintProfile.brLinux(),
                BrowserFingerprintProfile.euMacOS(),
                BrowserFingerprintProfile.randomProfile("windows", 1234L),
                BrowserFingerprintProfile.randomProfile("linux", 5678L),
                BrowserFingerprintProfile.randomProfile("macos", 9012L));

        profiles.forEach(profile -> assertEquals("spoof_noise", profile.webgl.mode, profile.name));
    }

    @Test
    void workAreaNormalizationDoesNotReplaceNativeTriggerApis() {
        String script = SeleniumProxySurfaceScripts.build("""
                {"screen":{"mode":"real","workAreaPatch":true,"width":1920,"height":1080,
                "availWidth":1920,"availHeight":1040}}
                """);

        List<String> forbidden = List.of(
                "Object.defineProperty(document, 'createElement'",
                "Object.defineProperty(window, 'setTimeout'",
                "Object.defineProperty(serviceWorkerProto, 'register'",
                "wrapWorkerConstructor('Worker')",
                "wrapWorkerConstructor('SharedWorker')");
        forbidden.forEach(fragment -> assertFalse(script.contains(fragment), fragment));
        assertTrue(script.contains("new MutationObserver"));
        assertTrue(script.contains("DOMContentLoaded"));
    }

    @Test
    void fromEnvironmentDefaultsToNativeRuntimeSurface() {
        BrowserFingerprintProfile profile = BrowserFingerprintProfile.fromEnvironment();
        String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);

        if (os.contains("win")) {
            assertEquals("Windows", profile.uaPlatform);
            assertTrue(profile.platform.startsWith("Win"));
        } else if (os.contains("mac") || os.contains("darwin")) {
            assertEquals("macOS", profile.uaPlatform);
            assertEquals("MacIntel", profile.platform);
        } else {
            assertEquals("Linux", profile.uaPlatform);
            assertTrue(profile.platform.startsWith("Linux"));
        }
        assertTrue(profile.hardwareConcurrency > 0);
        assertTrue(profile.deviceMemory > 0);
        assertEquals("real", profile.screen.mode);
        // WebGL agora é spoof por default (não vazar SwiftShader/llvmpipe de datacenter) com
        // renderer coerente à plataforma.
        assertEquals("spoof", profile.webgl.mode);
        assertFalse(profile.webgl.renderer == null || profile.webgl.renderer.isBlank());
    }

    @Test
    void deviceMetricsUseProfileScreenValues() {
        BrowserFingerprintProfile profile = new BrowserFingerprintProfile();
        profile.mobile = true;
        profile.screen.width = 1366;
        profile.screen.height = 768;
        profile.screen.deviceScaleFactor = 1.25;

        Map<String, Object> metrics = FingerprintCdpOverrides.deviceMetrics(profile);

        assertEquals(1366, metrics.get("width"));
        assertEquals(768, metrics.get("height"));
        assertEquals(1.25, metrics.get("deviceScaleFactor"));
        assertEquals(true, metrics.get("mobile"));
        assertEquals(true, metrics.get("dontSetVisibleSize"));
    }

    @Test
    void cdpAcceptLanguageUsesCleanTagsAndRejectsPreweightedValues() {
        assertEquals("pt-BR,pt,en-US,en", FingerprintCdpOverrides.acceptLanguage(
                List.of("pt-BR", "pt", "en-US", "en")));
        assertEquals("pt-BR,pt;q=0.9,en-US;q=0.8,en;q=0.7",
                FingerprintCdpOverrides.acceptLanguageHttpHeader(
                        List.of("pt-BR", "pt", "en-US", "en")));
        assertEquals("en-US,en", FingerprintCdpOverrides.acceptLanguage(List.of()));
        assertThrows(IllegalArgumentException.class,
                () -> FingerprintCdpOverrides.acceptLanguage(List.of("en-US", "en;q=0.9")));
    }

    @Test
    void userAgentUsesReducedChromeVersionButClientHintsKeepFullVersion() {
        BrowserFingerprintProfile profile = new BrowserFingerprintProfile();
        profile.browserVersion = "147.0.7727.137";
        profile.uaPlatform = "Windows";
        profile.platform = "Win32";

        String userAgent = UserAgentResolver.buildForProfile(profile);
        Map<String, Object> metadata = profile.toUserAgentMetadata(
                UserAgentResolver.extractChromeMajor(userAgent));

        assertTrue(userAgent.contains("Chrome/147.0.0.0"));
        assertFalse(userAgent.contains("Chrome/147.0.7727.137"));
        assertTrue(UserAgentResolver.isCompatible(profile, userAgent));
        assertEquals("147.0.7727.137", metadata.get("fullVersion"));
        assertEquals(List.of("Desktop"), metadata.get("formFactors"));

        BrowserFingerprintProfile mobile = BrowserFingerprintProfile.builder()
                .mobile(true, "Pixel 9")
                .formFactors("Mobile")
                .build();
        assertEquals(List.of("Mobile"), mobile.toUserAgentMetadata("147").get("formFactors"));
    }

    @Test
    void builderExposesTheRuntimePolicyUsedByTheNativeFingerprintPath() {
        BrowserFingerprintProfile profile = BrowserFingerprintProfile.builder()
                .runtimePolicy(" MINIMAL-CDP ")
                .build();

        assertEquals(BrowserFingerprintProfile.RUNTIME_POLICY_MINIMAL_CDP, profile.runtimePolicy);
    }

    @Test
    void fullChromeVersionUserAgentIsNotCompatibleWithReducedProfile() {
        BrowserFingerprintProfile profile = new BrowserFingerprintProfile();
        profile.browserVersion = "147.0.7727.137";
        profile.uaPlatform = "Linux";
        profile.bitness = "64";

        String fullVersionUserAgent = "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 "
                + "(KHTML, like Gecko) Chrome/147.0.7727.137 Safari/537.36";

        assertFalse(UserAgentResolver.isCompatible(profile, fullVersionUserAgent));
    }

    @Test
    void surfaceScriptUsesClosureStorageWithoutPageVisibleMarker() {
        String script = SeleniumProxySurfaceScripts.build("""
                {"locale":"pt-BR","platform":"Win32","vendor":"Google Inc.",
                "languages":["pt-BR","pt"],"hardwareConcurrency":12,"deviceMemory":16,
                "maxTouchPoints":2,"doNotTrack":true,
                "screen":{"width":1366,"height":768,"availWidth":1366,"availHeight":728,
                "colorDepth":30,"pixelDepth":30},
                "webgl":{"mode":"spoof","vendor":"Phantom Vendor","renderer":"Phantom Renderer"},
                "speech":{"mode":"auto","voices":[{"name":"Portuguese Brazil","lang":"pt-BR",
                "voiceURI":"Portuguese Brazil","localService":true,"isDefault":true}]},
                "canvas":{"mode":"noise","noise":0.01},
                "audio":{"mode":"noise","noise":0.002},
                "clientRects":{"mode":"noise","noise":0.05},
                "connection":{"effectiveType":"4g","downlink":42.5,"rtt":70,
                "saveData":true,"type":"cellular"}}""");

        // Storage interno em closure (const _storage), sem guard registrado no realm da página.
        List<String> expectedFragments = List.of(
                "const _storage =",
                "OffscreenCanvas.prototype.getContext",
                "speechSynthesis.getVoices",
                "define(window, 'outerHeight'");

        expectedFragments.forEach(fragment -> assertTrue(script.contains(fragment), fragment));

        // Não pode conter nomes expostos antigos NEM os marcadores Symbol suspeitos do design antigo
        // (__jdl_internal__/__jdl_storage__) — o design atual é mais escondido (closure + Symbol disfarçado).
        List<String> forbiddenFragments = List.of(
                "__java_driverless_surface_installed__",
                "__java_driverless_profile__",
                "__java_driverless_computed_style_installed__",
                "__worker_logs__",
                "__get_fingerprint_probe__",
                "__jdl_internal__",
                "__jdl_storage__",
                "Symbol.for(");

        forbiddenFragments.forEach(fragment ->
                assertTrue(!script.contains(fragment),
                        "Script must not contain exposed global: " + fragment));
    }

    @Test
    void pageOopifAndWorkerScriptsContainNoRegisteredMarkerSymbols() {
        String profile = "{\"locale\":\"pt-BR\",\"hardwareConcurrency\":8}";
        List<String> scripts = List.of(
                SeleniumProxySurfaceScripts.build(profile),
                SeleniumProxyWorkerSurfaceScripts.build(profile),
                SeleniumProxyWorkerHookScripts.build("\"(() => {})();\""));

        scripts.forEach(script -> {
            assertFalse(script.contains("Symbol.for("), "script não deve registrar Symbol global");
            assertFalse(script.contains("__phantompass_"), "script não deve expor marcador PhantomPass");
        });
    }

    @Test
    void workerHookLeavesTheSingleInjectionToPausedCdpAutoAttach() {
        String hook = SeleniumProxyWorkerHookScripts.build("\"(() => { self.__probe = true; })();\"");

        assertTrue(hook.isBlank(),
                "hook de página não deve prefixar o spoof que o auto-attach CDP já injeta");
    }

    @Test
    void pageAndWorkerScriptsCarryProfileIntoOffscreenPaths() {
        String profile = """
                {"locale":"en-US","timezoneId":"America/New_York",
                "screen":{"mode":"spoof","width":1920,"height":1080},
                "webgl":{"mode":"spoof_noise","vendor":"Vendor","renderer":"Renderer"}}
                """;
        String pageScript = SeleniumProxySurfaceScripts.build(profile);
        String workerScript = SeleniumProxyWorkerSurfaceScripts.build(profile);

        assertTrue(pageScript.contains("OffscreenCanvas.prototype.getContext"));
        assertTrue(workerScript.contains("OffscreenCanvas.prototype.getContext"));
        assertTrue(workerScript.contains("\"locale\":\"en-US\""));
        assertTrue(workerScript.contains("\"timezoneId\":\"America/New_York\""));
    }

    @Test
    void surfaceScriptPreservesNativeNotificationPermission() {
        String script = SeleniumProxySurfaceScripts.build("""
                {"permissions":{"notifications":"denied"}}""");

        assertFalse(script.contains("Object.defineProperty(window.Notification"));
        assertFalse(script.contains("Promise.resolve(notificationState)"));
        assertFalse(script.contains("permissionsProto.query ="));
    }

    @Test
    void screenOverridesUseCapturedNativeFallbacks() {
        String script = SeleniumProxySurfaceScripts.build("""
                {"screen":{"mode":"spoof","width":1920,"height":1080,
                "availWidth":1920,"availHeight":1040}}""");

        assertTrue(script.contains("const nativeScreenWidth = screen.width;"));
        assertTrue(script.contains("const nativeScreenHeight = screen.height;"));
        assertFalse(script.contains("intOr(screenProfile.width, screen.width)"));
        assertFalse(script.contains("intOr(screenProfile.height, screen.height)"));
    }

    @Test
    void webRtcProtectionFiltersProxyLocalNetworkLeaks() {
        String script = SeleniumProxySurfaceScripts.build("""
                {"webRtc":{"mode":"altered","protectLocalIps":true,
                "disableUdp":true,"publicIp":"203.0.113.121"}}""");

        assertTrue(script.contains("100 && parts[1] >= 64 && parts[1] <= 127"));
        // Patch da INSTANCIA via Proxy (NAO own-property nos metodos — Chrome real expoe
        // setLocalDescription/createOffer/createAnswer/onicecandidate no PROTOTYPE). O get/set trap
        // mascara sem criar own-prop detectavel (pc.hasOwnProperty(...) === false, como no nativo).
        assertTrue(script.contains("return new Proxy(pc, {"));
        assertTrue(script.contains("return t.createOffer(...a).then(sanitizeDesc)"));
        assertTrue(script.contains("return t.createAnswer(...a).then(sanitizeDesc)"));
        assertTrue(script.contains("prop === 'onicecandidate'"));
        // Trava a correcao da anomalia: NUNCA redefinir own-property nos metodos da instancia.
        assertFalse(script.contains("pc.setLocalDescription = function"));
        assertFalse(script.contains("pc.createOffer = function"));
        assertFalse(script.contains("Object.defineProperty(pc, 'onicecandidate'"));
        assertTrue(script.contains("return { type: desc.type, sdp: sanitizeText(desc.sdp) }"));
        // Sem publicIp não cai mais para o anômalo '0.0.0.0'; a proteção real é relay-only +
        // supressão type-aware de candidatos host/srflx que não sejam o IP do proxy.
        assertTrue(script.contains("replacementIp = webRtcProfile.publicIp || ''"));
        assertTrue(script.contains("config.iceTransportPolicy = 'relay'"));
        assertTrue(script.contains("candidateType(candidate) === 'relay'"));
        assertTrue(script.contains("!webRtcProfile.disableUdp"));
        // Patch via Proxy sobre o construtor nativo: herda toString/length/name nativos (sem o tell
        // de uma função JS distinta).
        assertTrue(script.contains("new Proxy(OrigRTC, handler)"));
        // REGRESSÃO: a sanitização do SDP é LINHA A LINHA, tocando só c=/o=/a=candidate — nunca
        // a linha a=fingerprint (cujos bytes hex AB:CD:.. pareciam IPv6 e eram corrompidos, o que
        // quebrava o handshake DTLS e todo o WebRTC legítimo).
        assertTrue(script.contains("replace(/[^\\r\\n]+/g"));
        assertTrue(script.contains("/^a=candidate/i.test(line)"));
    }
}
