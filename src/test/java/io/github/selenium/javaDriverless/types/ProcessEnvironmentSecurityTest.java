package io.github.selenium.javaDriverless.types;

import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

class ProcessEnvironmentSecurityTest {

    private static final Set<String> SAFE_DEFAULT_NAMES = Set.of(
            "PATH", "HOME",
            "LANG", "LANGUAGE", "LC_ALL", "LC_CTYPE", "LC_MESSAGES", "TZ",
            "TMPDIR", "TMP", "TEMP",
            "DISPLAY", "WAYLAND_DISPLAY", "XAUTHORITY", "XDG_RUNTIME_DIR",
            "XDG_DATA_HOME", "XDG_CONFIG_HOME", "XDG_CACHE_HOME",
            "XDG_SESSION_TYPE", "XDG_CURRENT_DESKTOP", "DESKTOP_SESSION",
            "DBUS_SESSION_BUS_ADDRESS", "PULSE_SERVER", "PIPEWIRE_REMOTE",
            "JAVA_DRIVERLESS_NATIVE_SCREEN_PROVISIONED",
            "JAVA_DRIVERLESS_NATIVE_SCREEN_WIDTH", "JAVA_DRIVERLESS_NATIVE_SCREEN_HEIGHT",
            "JAVA_DRIVERLESS_NATIVE_SCREEN_AVAIL_WIDTH", "JAVA_DRIVERLESS_NATIVE_SCREEN_AVAIL_HEIGHT",
            "JAVA_DRIVERLESS_NATIVE_SCREEN_COLOR_DEPTH", "JAVA_DRIVERLESS_NATIVE_SCREEN_PIXEL_DEPTH",
            "JAVA_DRIVERLESS_NATIVE_SCREEN_DPR",
            "JAVA_DRIVERLESS_NATIVE_DEVICE_MEMORY_PROVISIONED",
            "JAVA_DRIVERLESS_NATIVE_DEVICE_MEMORY_GB",
            "SYSTEMROOT", "WINDIR", "COMSPEC", "PATHEXT", "USERPROFILE",
            "APPDATA", "LOCALAPPDATA", "PROGRAMDATA"
    );

    @Test
    void defaultChromeEnvironmentContainsOnlyCompatibilityAllowlist() {
        ChromeOptions options = new ChromeOptions();

        Set<String> normalizedNames = options.getEnv().keySet().stream()
                .map(name -> name.toUpperCase(Locale.ROOT))
                .collect(Collectors.toSet());
        assertThat(normalizedNames).isSubsetOf(SAFE_DEFAULT_NAMES);
    }

    @Test
    void explicitlyConfiguredEnvironmentIsKeptWhileLocalProxyVariablesAreRemoved() {
        ChromeOptions options = new ChromeOptions();
        options.setEnv(new LinkedHashMap<>(Map.of(
                "JDL_EXPLICIT_SENTINEL", "configured-only",
                "HTTP_PROXY", "http://proxy-user:proxy-secret@proxy.invalid:8080",
                "https_proxy", "http://proxy-user:proxy-secret@proxy.invalid:8080",
                "ALL_PROXY", "socks5://proxy-user:proxy-secret@proxy.invalid:1080",
                "NO_PROXY", "localhost"
        )));

        options.ignoreLocalProxyEnvironmentVariables();

        assertThat(options.getEnv())
                .containsEntry("JDL_EXPLICIT_SENTINEL", "configured-only")
                .doesNotContainKeys("HTTP_PROXY", "https_proxy", "ALL_PROXY", "NO_PROXY");
    }

    @Test
    void processEnvironmentIsReplacedInsteadOfMergedWithJvmEnvironment() {
        ProcessBuilder processBuilder = new ProcessBuilder("true");
        processBuilder.environment().put("INHERITED_SECRET_SENTINEL", "must-not-survive");

        ChromeOptionsEnvironment.replaceProcessEnvironment(
                processBuilder, Map.of("JDL_EXPLICIT_SENTINEL", "configured-only"));

        assertThat(processBuilder.environment())
                .containsOnly(Map.entry("JDL_EXPLICIT_SENTINEL", "configured-only"));
    }

    @Test
    void writableXdgDirectoriesReachSandboxedChrome() {
        Map<String, String> filtered = ChromeOptionsEnvironment.defaultsFrom(Map.of(
                "XDG_DATA_HOME", "/tmp/jdl-data",
                "XDG_CONFIG_HOME", "/tmp/jdl-config",
                "XDG_CACHE_HOME", "/tmp/jdl-cache",
                "UNRELATED_SECRET", "must-not-survive"));

        assertThat(filtered).containsOnly(
                Map.entry("XDG_DATA_HOME", "/tmp/jdl-data"),
                Map.entry("XDG_CONFIG_HOME", "/tmp/jdl-config"),
                Map.entry("XDG_CACHE_HOME", "/tmp/jdl-cache"));
    }

    @Test
    void validatedNativeScreenHandshakeReachesTheConfiguredChromeEnvironment() {
        Map<String, String> filtered = ChromeOptionsEnvironment.defaultsFrom(Map.ofEntries(
                Map.entry("DISPLAY", ":99"),
                Map.entry("JAVA_DRIVERLESS_NATIVE_SCREEN_PROVISIONED", "1"),
                Map.entry("JAVA_DRIVERLESS_NATIVE_SCREEN_WIDTH", "1365"),
                Map.entry("JAVA_DRIVERLESS_NATIVE_SCREEN_HEIGHT", "900"),
                Map.entry("JAVA_DRIVERLESS_NATIVE_SCREEN_AVAIL_WIDTH", "1365"),
                Map.entry("JAVA_DRIVERLESS_NATIVE_SCREEN_AVAIL_HEIGHT", "860"),
                Map.entry("JAVA_DRIVERLESS_NATIVE_SCREEN_COLOR_DEPTH", "24"),
                Map.entry("JAVA_DRIVERLESS_NATIVE_SCREEN_PIXEL_DEPTH", "24"),
                Map.entry("JAVA_DRIVERLESS_NATIVE_SCREEN_DPR", "1"),
                Map.entry("JAVA_DRIVERLESS_NATIVE_DEVICE_MEMORY_PROVISIONED", "1"),
                Map.entry("JAVA_DRIVERLESS_NATIVE_DEVICE_MEMORY_GB", "32"),
                Map.entry("UNRELATED_SECRET", "must-not-survive")));

        assertThat(filtered)
                .containsEntry("DISPLAY", ":99")
                .containsEntry("JAVA_DRIVERLESS_NATIVE_SCREEN_PROVISIONED", "1")
                .containsEntry("JAVA_DRIVERLESS_NATIVE_SCREEN_WIDTH", "1365")
                .containsEntry("JAVA_DRIVERLESS_NATIVE_SCREEN_HEIGHT", "900")
                .containsEntry("JAVA_DRIVERLESS_NATIVE_SCREEN_AVAIL_WIDTH", "1365")
                .containsEntry("JAVA_DRIVERLESS_NATIVE_SCREEN_AVAIL_HEIGHT", "860")
                .containsEntry("JAVA_DRIVERLESS_NATIVE_SCREEN_COLOR_DEPTH", "24")
                .containsEntry("JAVA_DRIVERLESS_NATIVE_SCREEN_PIXEL_DEPTH", "24")
                .containsEntry("JAVA_DRIVERLESS_NATIVE_SCREEN_DPR", "1")
                .containsEntry("JAVA_DRIVERLESS_NATIVE_DEVICE_MEMORY_PROVISIONED", "1")
                .containsEntry("JAVA_DRIVERLESS_NATIVE_DEVICE_MEMORY_GB", "32")
                .doesNotContainKey("UNRELATED_SECRET");
    }
}
