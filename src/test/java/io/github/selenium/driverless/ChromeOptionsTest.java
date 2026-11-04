package io.github.selenium.driverless;

import io.github.selenium.javaDriverless.types.ChromeOptions;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ChromeOptionsTest {
    @Test
    void fingerprintIsEnabledByDefaultAndDiagnosticBaselineCanOptOutExplicitly() {
        ChromeOptions options = new ChromeOptions();

        assertTrue(options.isFingerprintEnabled());
        options.setFingerprintEnabled(false);
        options.getFingerprintProfile();

        assertFalse(options.isFingerprintEnabled());
        assertFalse(options.getArguments().contains(
                "--disable-blink-features=AutomationControlled"));
        assertFalse(options.getArguments().contains("--disable-accelerated-2d-canvas"));
        assertFalse(options.getArguments().contains("--ignore-gpu-blocklist"));
        assertFalse(options.getArguments().contains("--lang=en-US"));
        assertFalse(options.getArguments().contains("--accept-lang=en-US,en"));
        assertTrue(options.getPrefs().isEmpty());
    }

    @Test
    void diagnosticBaselinePreservesArgumentsAndPrefsAddedByCaller() {
        // Ate a 1.12.1 setFingerprintEnabled(false) depois dos addArgument apagava a proxy e o
        // sandbox keeps the browser process isolated from the host.
        ChromeOptions options = new ChromeOptions();
        options.addArgument("--no-sandbox");
        options.addArgument("--window-size=1280,900");
        options.addArgument("--proxy-server=http://127.0.0.1:18080");
        options.addArgument("--disable-blink-features=AutomationControlled");
        options.addArgument("--lang=pt-BR");
        options.setHeadless(true);
        options.updatePref("profile.default_content_setting_values.notifications", 2);

        options.setFingerprintEnabled(false);

        List<String> arguments = options.getArguments();
        assertFalse(options.isFingerprintEnabled());
        assertTrue(arguments.containsAll(List.of(
                "--no-sandbox",
                "--window-size=1280,900",
                "--proxy-server=http://127.0.0.1:18080",
                "--disable-blink-features=AutomationControlled",
                "--lang=pt-BR",
                "--headless=new")), arguments::toString);
        assertTrue(options.isHeadless());
        assertFalse(arguments.contains("--ignore-gpu-blocklist"));
        assertFalse(arguments.contains("--accept-lang=en-US,en"));
        assertEquals(1, arguments.stream()
                .filter("--disable-blink-features=AutomationControlled"::equals).count());
        assertEquals(Map.of("profile", Map.of("default_content_setting_values",
                Map.of("notifications", 2))), options.getPrefs());
    }

    @Test
    void diagnosticBaselineGivesSameArgumentsBeforeOrAfterCallerArguments() {
        ChromeOptions antes = new ChromeOptions();
        antes.setFingerprintEnabled(false);
        antes.addArgument("--no-sandbox");
        antes.addArgument("--proxy-server=http://127.0.0.1:18080");

        ChromeOptions depois = new ChromeOptions();
        depois.addArgument("--no-sandbox");
        depois.addArgument("--proxy-server=http://127.0.0.1:18080");
        depois.setFingerprintEnabled(false);

        assertEquals(new LinkedHashSet<>(antes.getArguments()), new LinkedHashSet<>(depois.getArguments()));
        assertEquals(antes.getPrefs(), depois.getPrefs());
    }

    @Test
    void adHocOptionsRemainCompatibleUntilCallerOptsIntoMinimalPolicy() {
        ChromeOptions options = new ChromeOptions();
        options.addArgument("--no-sandbox");

        assertDoesNotThrow(options::validateProfileLaunchArguments);
        assertThrows(IllegalStateException.class, options::applyMinimalCdpProcessArgumentPolicy);
    }

    @Test
    void defaultsUseNativeMaximizedWindowMetrics() {
        ChromeOptions options = new ChromeOptions();
        List<String> arguments = options.getArguments();

        assertTrue(arguments.contains("--start-maximized"));
        assertFalse(arguments.contains("--window-size=1920,1032"));
        // Default seguro: esconde navigator.webdriver no nível do engine (método nativo padrão de
        // anti-detect, não observável em JS). Antes vinha como true por default (checagem #1 de bot).
        assertTrue(arguments.contains("--disable-blink-features=AutomationControlled"));
        assertFalse(arguments.contains("--no-sandbox"));
        assertFalse(arguments.contains("--disable-setuid-sandbox"));
        assertFalse(arguments.contains("--disable-dev-shm-usage"));
        assertFalse(arguments.contains("--enable-speech-dispatcher"));
    }

    @Test
    void updatePrefMergesNestedPrefsWithoutDroppingDefaults() {
        ChromeOptions options = new ChromeOptions();

        options.updatePref("profile.default_content_setting_values.notifications", 2);

        Map<String, Object> prefs = options.getPrefs();
        assertEquals(false, prefs.get("credentials_enable_service"));

        @SuppressWarnings("unchecked")
        Map<String, Object> profile = assertInstanceOf(Map.class, prefs.get("profile"));
        assertEquals(false, profile.get("password_manager_enabled"));

        @SuppressWarnings("unchecked")
        Map<String, Object> defaultContentSettings =
            assertInstanceOf(Map.class, profile.get("default_content_setting_values"));
        assertEquals(2, defaultContentSettings.get("notifications"));
    }

    @Test
    void authoritativeWebRtcPolicyReplacesLegacyAndConflictingSwitches() {
        ChromeOptions options = new ChromeOptions();
        options.addArgument("--force-webrtc-ip-handling-policy=default");
        options.addArgument("--webrtc-ip-handling-policy=default_public_interface_only");
        options.addArgument("--webrtc-ip-handling-policy=disable_non_proxied_udp");

        assertEquals(1, options.getArguments().stream()
                .filter(argument -> argument.contains("webrtc-ip-handling-policy="))
                .count());
        assertTrue(options.getArguments().contains(
                "--webrtc-ip-handling-policy=disable_non_proxied_udp"));
        assertFalse(options.getArguments().stream()
                .anyMatch(argument -> argument.startsWith("--force-webrtc-ip-handling-policy=")));
    }

    @Test
    void localeArgumentsAreSingleValuedLastWriteWinsAndRejectEmptyValues() {
        ChromeOptions options = new ChromeOptions();

        options.addArgument("--lang=pt-BR");
        options.addArgument("--accept-lang=pt-BR,pt,en-US,en");

        assertEquals(1, options.getArguments().stream()
                .filter(argument -> argument.startsWith("--lang="))
                .count());
        assertEquals(1, options.getArguments().stream()
                .filter(argument -> argument.startsWith("--accept-lang="))
                .count());
        assertTrue(options.getArguments().contains("--lang=pt-BR"));
        assertTrue(options.getArguments().contains("--accept-lang=pt-BR,pt,en-US,en"));
        assertThrows(IllegalArgumentException.class, () -> options.addArgument("--lang=  "));
        assertThrows(IllegalArgumentException.class, () -> options.addArgument("--accept-lang="));
    }

    @Test
    void retryRestoresCallerWebRtcPrefsAndArgumentBaseline() {
        ChromeOptions options = new ChromeOptions();
        options.updatePref("webrtc.caller_setting", "preserve");
        options.addArgument("--webrtc-ip-handling-policy=default_public_interface_only");
        options.prepareEgressDerivedLaunchBaseline();

        options.applyEgressDerivedWebRtcPrefs();
        options.applyEgressDerivedWebRtcPolicyArgument();
        options.updatePref("webrtc.added_after_launch", "keep");
        options.updatePref("webrtc", Map.of(
                "ip_handling_policy", "caller_changed_after_launch"));
        options.addExperimentalOption("prefs", Map.of(
                "webrtc.multiple_routes_enabled", true));
        options.addArguments("--webrtc-ip-handling-policy=caller_changed_after_launch");
        options.restoreEgressDerivedLaunchBaseline();

        @SuppressWarnings("unchecked")
        Map<String, Object> webRtc = assertInstanceOf(
                Map.class, options.getPrefs().get("webrtc"));
        assertEquals(Map.of(
                "caller_setting", "preserve",
                "added_after_launch", "keep",
                "ip_handling_policy", "caller_changed_after_launch",
                "multiple_routes_enabled", true), webRtc);
        assertTrue(options.getArguments().contains(
                "--webrtc-ip-handling-policy=caller_changed_after_launch"));
        assertFalse(options.getArguments().contains(
                "--webrtc-ip-handling-policy=disable_non_proxied_udp"));
    }
}
