package io.github.selenium.javaDriverless.types;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/** Environment boundary used when spawning a local Chrome process. */
public final class ChromeOptionsEnvironment {
    private static final Set<String> DEFAULT_ALLOWLIST = Set.of(
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

    private static final Set<String> PROXY_VARIABLES = Set.of(
            "HTTP_PROXY", "HTTPS_PROXY", "ALL_PROXY", "FTP_PROXY", "NO_PROXY"
    );

    private ChromeOptionsEnvironment() {
    }

    static Map<String, String> defaultsFrom(Map<String, String> source) {
        Map<String, String> filtered = new LinkedHashMap<>();
        if (source == null) return filtered;

        source.forEach((name, value) -> {
            if (name != null && value != null
                    && DEFAULT_ALLOWLIST.contains(name.toUpperCase(Locale.ROOT))) {
                filtered.put(name, value);
            }
        });
        return filtered;
    }

    static void removeProxyVariables(Map<String, String> environment) {
        if (environment == null) return;
        environment.keySet().removeIf(name -> name != null
                && PROXY_VARIABLES.contains(name.toUpperCase(Locale.ROOT)));
    }

    /** Replaces the inherited JVM environment instead of merging into it. */
    public static void replaceProcessEnvironment(ProcessBuilder processBuilder,
                                                  Map<String, String> configuredEnvironment) {
        Map<String, String> childEnvironment = processBuilder.environment();
        childEnvironment.clear();
        if (configuredEnvironment == null) return;
        configuredEnvironment.forEach((name, value) -> {
            if (name != null && !name.isBlank() && value != null) {
                childEnvironment.put(name, value);
            }
        });
    }
}
