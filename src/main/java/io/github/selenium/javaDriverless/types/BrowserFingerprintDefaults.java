package io.github.selenium.javaDriverless.types;

import java.lang.management.ManagementFactory;
import java.lang.management.OperatingSystemMXBean;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

final class BrowserFingerprintDefaults {
    private BrowserFingerprintDefaults() {}

    static BrowserFingerprintProfile fromEnvironment() {
        BrowserFingerprintProfile profile = nativeDefaults();
        profile.name = env("JAVA_DRIVERLESS_PROFILE_NAME", profile.name);
        profile.browserVersion = env("JAVA_DRIVERLESS_BROWSER_VERSION", profile.browserVersion);
        profile.locale = env("JAVA_DRIVERLESS_LOCALE", profile.locale);
        profile.timezoneId = env("JAVA_DRIVERLESS_TIMEZONE", profile.timezoneId);
        profile.platform = env("JAVA_DRIVERLESS_PLATFORM", profile.platform);
        profile.uaPlatform = env("JAVA_DRIVERLESS_UA_PLATFORM", profile.uaPlatform);
        profile.platformVersion = env("JAVA_DRIVERLESS_PLATFORM_VERSION", profile.platformVersion);
        profile.architecture = env("JAVA_DRIVERLESS_ARCHITECTURE", profile.architecture);
        profile.bitness = env("JAVA_DRIVERLESS_BITNESS", profile.bitness);
        profile.vendor = env("JAVA_DRIVERLESS_VENDOR", profile.vendor);
        profile.hardwareConcurrency = envInt("JAVA_DRIVERLESS_HARDWARE_CONCURRENCY", profile.hardwareConcurrency);
        profile.deviceMemory = envInt("JAVA_DRIVERLESS_DEVICE_MEMORY", profile.deviceMemory);
        profile.maxTouchPoints = envInt("JAVA_DRIVERLESS_MAX_TOUCH_POINTS", profile.maxTouchPoints);
        profile.screen.width = envInt("JAVA_DRIVERLESS_SCREEN_WIDTH", profile.screen.width);
        profile.screen.height = envInt("JAVA_DRIVERLESS_SCREEN_HEIGHT", profile.screen.height);
        profile.screen.availWidth = envInt("JAVA_DRIVERLESS_SCREEN_AVAIL_WIDTH", profile.screen.availWidth);
        profile.screen.availHeight = envInt("JAVA_DRIVERLESS_SCREEN_AVAIL_HEIGHT", profile.screen.availHeight);
        profile.webgl.vendor = env("JAVA_DRIVERLESS_WEBGL_VENDOR", profile.webgl.vendor);
        profile.webgl.renderer = env("JAVA_DRIVERLESS_WEBGL_RENDERER", profile.webgl.renderer);
        // Opt-in PC real: webgl.mode=real (pass-through, getParameter nativo). Default continua
        // 'spoof' (Docker/datacenter esconde llvmpipe). Docker NAO seta esta env => inalterado.
        profile.webgl.mode = env("JAVA_DRIVERLESS_WEBGL_MODE", profile.webgl.mode);
        // Coerencia de idioma: lista separada por virgula (ex: "pt-BR,pt,en-US,en"). Default
        // (en-US,en) e o server path por-conta seguem intactos quando a env nao e setada.
        List<String> envLanguages = envList("JAVA_DRIVERLESS_LANGUAGES");
        if (envLanguages != null) profile.languages = envLanguages;
        // PC real (taskbar): JAVA_DRIVERLESS_SCREEN_WORKAREA=off desliga o normalizador de work-area
        // da surface, que engancharia document.createElement / window.setTimeout (tell de automação).
        if ("off".equalsIgnoreCase(env("JAVA_DRIVERLESS_SCREEN_WORKAREA", "on"))) {
            profile.screen.workAreaPatch = false;
        }
        profile.applyDefaults();
        return profile;
    }

    static BrowserFingerprintProfile nativeDefaults() {
        BrowserFingerprintProfile profile = new BrowserFingerprintProfile();
        profile.name = "java-driverless-native";

        String osName = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        String osArch = System.getProperty("os.arch", "").toLowerCase(Locale.ROOT);
        boolean isArm = osArch.contains("aarch") || osArch.contains("arm");
        boolean is64 = osArch.contains("64") || osArch.equals("amd64") || osArch.equals("x86_64");

        if (osName.contains("win")) {
            profile.platform = "Win32";
            profile.uaPlatform = "Windows";
            profile.platformVersion = "10.0.0";
            profile.architecture = isArm ? "arm" : "x86";
        } else if (osName.contains("mac") || osName.contains("darwin")) {
            profile.platform = "MacIntel";
            profile.uaPlatform = "macOS";
            profile.platformVersion = "14.0.0";
            profile.architecture = isArm ? "arm" : "x86";
        } else {
            profile.platform = is64 ? "Linux x86_64" : "Linux i686";
            profile.uaPlatform = "Linux";
            // NAO vazar o kernel real do host (System.getProperty("os.version")): seria um
            // identificador estavel do servidor e criaria mismatch. Usar uma versao Linux plausivel
            // fixa; perfis sinteticos (randomForCountry) sobrescrevem com valor deterministico/conta.
            profile.platformVersion = "6.6.0";
            profile.architecture = isArm ? "arm" : "x86";
        }

        profile.bitness = is64 ? "64" : "32";
        profile.hardwareConcurrency = detectHardwareConcurrency();
        profile.deviceMemory = detectDeviceMemoryGb();
        profile.maxTouchPoints = 0;
        profile.screen.mode = "real";
        // WebGL spoof por DEFAULT com renderer coerente à plataforma: em headless/datacenter o
        // renderer real é SwiftShader/llvmpipe (assinatura inequívoca de bot). 'real' vazava isso.
        String[] gpu = BrowserFingerprintProfiles.defaultWebgl(profile.uaPlatform);
        profile.webgl.mode = "spoof";
        profile.webgl.vendor = gpu[0];
        profile.webgl.renderer = gpu[1];
        profile.webgl.unmaskedVendor = gpu[0];
        profile.webgl.unmaskedRenderer = gpu[1];
        profile.connection.mode = "real";
        return profile;
    }

    private static String env(String key, String fallback) {
        String value = System.getenv(key);
        return value == null || value.isBlank() ? fallback : value.trim();
    }

    private static List<String> envList(String key) {
        String value = System.getenv(key);
        if (value == null || value.isBlank()) return null;
        List<String> out = new ArrayList<>();
        for (String part : value.split(",")) {
            String trimmed = part.trim();
            if (!trimmed.isEmpty()) out.add(trimmed);
        }
        return out.isEmpty() ? null : out;
    }

    private static int envInt(String key, int fallback) {
        try {
            String value = System.getenv(key);
            return value == null || value.isBlank() ? fallback : Integer.parseInt(value.trim());
        } catch (Exception ignored) {
            return fallback;
        }
    }

    private static int detectHardwareConcurrency() {
        int cpus = Runtime.getRuntime().availableProcessors();
        try {
            Path cpuInfo = Path.of("/proc/cpuinfo");
            if (Files.isRegularFile(cpuInfo)) {
                long processors;
                try (java.util.stream.Stream<String> lines = Files.lines(cpuInfo)) {
                    processors = lines
                            .filter(line -> line.startsWith("processor"))
                            .count();
                }
                if (processors > 0 && processors <= Integer.MAX_VALUE) {
                    cpus = (int) processors;
                }
            }
        } catch (Exception ignored) {
            // Runtime fallback is good enough outside Linux.
        }
        return Math.max(1, cpus);
    }

    private static int detectDeviceMemoryGb() {
        long bytes = -1;
        try {
            Path memInfo = Path.of("/proc/meminfo");
            if (Files.isRegularFile(memInfo)) {
                String line;
                try (java.util.stream.Stream<String> lines = Files.lines(memInfo)) {
                    line = lines
                            .filter(value -> value.startsWith("MemTotal:"))
                            .findFirst()
                            .orElse("");
                }
                String digits = line.replaceAll("\\D+", "");
                if (!digits.isBlank()) {
                    bytes = Long.parseLong(digits) * 1024L;
                }
            }
        } catch (Exception ignored) {
            // Try MXBean below.
        }
        if (bytes <= 0) {
            bytes = detectPhysicalMemoryBytes();
        }
        if (bytes <= 0) {
            return 8;
        }
        long gb = Math.max(1, Math.round(bytes / 1024.0 / 1024.0 / 1024.0));
        int rounded = 1;
        while (rounded < gb && rounded < 64) {
            rounded *= 2;
        }
        return rounded;
    }

    private static long detectPhysicalMemoryBytes() {
        try {
            OperatingSystemMXBean bean = ManagementFactory.getOperatingSystemMXBean();
            Object value = bean.getClass().getMethod("getTotalMemorySize").invoke(bean);
            if (value instanceof Number number) {
                return number.longValue();
            }
        } catch (Exception ignored) {
            try {
                OperatingSystemMXBean bean = ManagementFactory.getOperatingSystemMXBean();
                Object value = bean.getClass().getMethod("getTotalPhysicalMemorySize").invoke(bean);
                if (value instanceof Number number) {
                    return number.longValue();
                }
            } catch (Exception ignoredAgain) {
                // No portable physical memory API is guaranteed.
            }
        }
        return -1;
    }
}
