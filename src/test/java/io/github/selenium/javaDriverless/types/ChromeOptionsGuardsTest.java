package io.github.selenium.javaDriverless.types;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Map;

import org.junit.jupiter.api.Test;

/**
 * Guardas catálogo-aware das ChromeOptions (validador, lease, guard de argumentos)
 * e o ciclo de baseline/prefs WebRTC derivadas do egress entre tentativas de launch.
 */
class ChromeOptionsGuardsTest {

    @SuppressWarnings("unchecked")
    private static Map<String, Object> webrtcPrefs(ChromeOptions options) {
        return (Map<String, Object>) options.getPrefs().get("webrtc");
    }

    @Test
    void catalogValidatorMustBeNotNullAndBindsExactlyOnce() {
        ChromeOptions options = new ChromeOptions();

        assertThrows(IllegalArgumentException.class,
            () -> options.bindCatalogLaunchValidator(null));
        assertFalse(options.hasCatalogLaunchValidator());

        options.bindCatalogLaunchValidator(ignored -> () -> { });
        assertTrue(options.hasCatalogLaunchValidator());

        assertThrows(IllegalStateException.class,
            () -> options.bindCatalogLaunchValidator(ignored -> () -> { }));
    }

    @Test
    void catalogLaunchLeaseCannotBeAcquiredTwice() {
        ChromeOptions options = new ChromeOptions();
        options.bindCatalogLaunchValidator(ignored -> () -> { });

        var primeiroLease = options.acquireCatalogLaunchLease();
        assertThrows(IllegalStateException.class, options::acquireCatalogLaunchLease);
        primeiroLease.close();

        // depois de liberar, um novo lease é adquirível
        try (var segundo = options.acquireCatalogLaunchLease()) {
            assertNotNull(segundo);
        }
    }

    @Test
    void profileLaunchArgumentGuardBindsOnce() {
        ChromeOptions options = new ChromeOptions();

        options.bindProfileLaunchArgumentGuard();
        assertThrows(IllegalStateException.class, options::bindProfileLaunchArgumentGuard);
    }

    @Test
    void managedLaunchArgumentRejectsRawUserArguments() {
        ChromeOptions options = new ChromeOptions();

        // qualquer flag fora do vocabulário gerenciado pelo launch engine é rejeitada
        assertThrows(IllegalArgumentException.class,
            () -> options.addManagedLaunchArgument("--flag-inexistente-no-launch-engine"));
    }

    @Test
    void egressWebrtcPrefsAreRestoredToTheCapturedBaseline() {
        ChromeOptions options = new ChromeOptions();
        options.updatePref("webrtc.ip_handling_policy", "default-public");

        options.prepareEgressDerivedLaunchBaseline();
        options.applyEgressDerivedWebRtcPrefs();
        assertEquals("disable_non_proxied_udp", webrtcPrefs(options).get("ip_handling_policy"));

        options.restoreEgressDerivedLaunchBaseline();
        assertEquals("default-public", webrtcPrefs(options).get("ip_handling_policy"));
    }

    @Test
    void egressWebrtcPrefsWithoutBaselineAreRemovedCompletely() {
        ChromeOptions options = new ChromeOptions();

        options.prepareEgressDerivedLaunchBaseline();
        options.applyEgressDerivedWebRtcPrefs();
        assertNotNull(webrtcPrefs(options));

        options.restoreEgressDerivedLaunchBaseline();
        assertNull(options.getPrefs().get("webrtc"), "prefs WebRTC vazias são removidas");
    }

    @Test
    void egressOwnedPolicyArgumentIsRemovedOnRestore() {
        ChromeOptions options = new ChromeOptions();

        options.prepareEgressDerivedLaunchBaseline();
        options.applyEgressDerivedWebRtcPolicyArgument();
        assertTrue(options.getArguments()
            .contains("--webrtc-ip-handling-policy=disable_non_proxied_udp"));

        options.restoreEgressDerivedLaunchBaseline();
        assertFalse(options.getArguments().stream()
            .anyMatch(argument -> argument.startsWith("--webrtc-ip-handling-policy=")));
    }

    @Test
    void transferringTheWholeWebrtcPrefAsNonMapClearsEgressOwnership() {
        ChromeOptions options = new ChromeOptions();

        options.prepareEgressDerivedLaunchBaseline();
        options.applyEgressDerivedWebRtcPrefs();
        options.updatePref("webrtc", "pref-descartada");

        options.restoreEgressDerivedLaunchBaseline();
        // pref não pertence mais ao egress: restore não pode sobrescrevê-la
        assertEquals("pref-descartada", options.getPrefs().get("webrtc"));
    }

    @Test
    void afterSessionRestoreIsAllowedEvenForSealedCatalogOptions() {
        ChromeOptions options = new ChromeOptions();
        options.prepareEgressDerivedLaunchBaseline();
        options.applyEgressDerivedWebRtcPolicyArgument();

        options.sealCatalogLaunchOptions();
        // cleanup pós-processo: permitido mesmo com opções seladas
        options.restoreEgressDerivedLaunchBaselineAfterSession();

        assertFalse(options.getArguments().stream()
            .anyMatch(argument -> argument.startsWith("--webrtc-ip-handling-policy=")));
    }

    @Test
    void facadeDelegatesKeepThePublicApiShape(@org.junit.jupiter.api.io.TempDir java.nio.file.Path dir)
        throws java.io.IOException {
        ChromeOptions options = new ChromeOptions();
        java.nio.file.Path extensao = dir.resolve("extensao");
        java.nio.file.Files.createDirectories(extensao);

        options.setProxyFromString("proxy.internal:8080", ProxyConfig.ProxyType.HTTP);
        assertNotNull(options.getProxyConfig());
        options.setHttpProxy("http-proxy.internal:3128");
        options.setHttpsProxy("https-proxy.internal:3129");
        options.setSocks5Proxy("socks.internal:1080");

        options.setDebuggerAddress("127.0.0.1:9222");
        assertEquals("127.0.0.1:9222", options.getDebuggerAddress());

        options.setAutoCleanDirs(true);
        assertTrue(options.isAutoCleanDirs());

        // enableMobile ainda não tem implementação: a fachada falha explícito
        assertThrows(UnsupportedOperationException.class,
            () -> options.enableMobile("com.app.test", ".MainActivity", "emulator-5554"));
        options.ignoreLocalProxyEnvironmentVariables();
        options.setEnv(Map.of("CHROME_LOG_FILE", "/tmp/chrome.log"));
        assertEquals("/tmp/chrome.log", options.getEnv().get("CHROME_LOG_FILE"));
        options.addExtension(extensao.toString());
        assertTrue(options.getExtensionPaths().contains(extensao.toString()));
    }
}
