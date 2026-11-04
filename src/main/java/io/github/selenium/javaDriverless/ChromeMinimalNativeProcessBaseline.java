package io.github.selenium.javaDriverless;

import io.github.selenium.javaDriverless.types.BrowserFingerprintProfile;
import io.github.selenium.javaDriverless.types.ChromeOptions;

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

/** Baseline nativo Linux herdado por todos os renderers, inclusive popup noopener. */
final class ChromeMinimalNativeProcessBaseline {
    private static final String FORCE_DARK_MODE = "--force-dark-mode";

    private ChromeMinimalNativeProcessBaseline() {}

    static void configure(ChromeOptions options, BrowserFingerprintProfile profile) {
        configure(options, profile, System.getProperty("os.name", ""));
    }

    static void configure(
            ChromeOptions options, BrowserFingerprintProfile profile, String osName) {
        if (!isLinux(osName)) return;
        requireNativeMedia(profile);
        Map<String, String> environment = new HashMap<>(options.getEnv());
        environment.put("TZ", ChromeFingerprintTimezone.resolve(profile));
        options.setEnv(environment);

        boolean forceDark = options.getArguments().stream()
                .anyMatch(FORCE_DARK_MODE::equals);
        if ("dark".equals(normalize(profile.colorScheme))) {
            if (!forceDark) options.addArgument(FORCE_DARK_MODE);
        } else if (forceDark) {
            throw new IllegalStateException(
                    "runtimePolicy=minimal-cdp recebeu --force-dark-mode com colorScheme=light");
        }
        configureDeviceScaleFactor(options, profile);
    }

    static boolean coversRendererNew(BrowserFingerprintProfile profile) {
        return coversRendererNew(profile, System.getProperty("os.name", ""));
    }

    static boolean coversRendererNew(
            BrowserFingerprintProfile profile, String osName) {
        if (!isLinux(osName) || profile == null || profile.timezoneId == null
                || profile.timezoneId.isBlank()) {
            return false;
        }
        try {
            requireNativeMedia(profile);
            return true;
        } catch (IllegalStateException unsupported) {
            return false;
        }
    }

    private static void requireNativeMedia(BrowserFingerprintProfile profile) {
        requireOneOf("colorScheme", profile.colorScheme, "light", "dark");
        requireExact("prefersReducedMotion", profile.prefersReducedMotion, "no-preference");
        requireExact("prefersReducedTransparency",
                profile.prefersReducedTransparency, "no-preference");
        requireExact("prefersContrast", profile.prefersContrast, "no-preference");
        requireExact("forcedColors", profile.forcedColors, "none");
    }

    private static void configureDeviceScaleFactor(
            ChromeOptions options, BrowserFingerprintProfile profile) {
        if (profile.screen == null || !"spoof".equals(normalize(profile.screen.mode))) return;
        double dpr = profile.screen.deviceScaleFactor;
        if (!Double.isFinite(dpr) || dpr <= 0) {
            throw new IllegalStateException(
                    "runtimePolicy=minimal-cdp recebeu deviceScaleFactor invalido");
        }
        String expected = "--force-device-scale-factor="
                + BigDecimal.valueOf(dpr).stripTrailingZeros().toPlainString();
        String configured = options.getArguments().stream()
                .filter(argument -> argument.startsWith("--force-device-scale-factor="))
                .findFirst().orElse("");
        if (!configured.isEmpty() && !configured.equals(expected)) {
            throw new IllegalStateException(
                    "runtimePolicy=minimal-cdp rejeitou deviceScaleFactor conflitante");
        }
        if (configured.isEmpty()) options.addArgument(expected);
    }

    private static void requireExact(String field, String observed, String supported) {
        requireOneOf(field, observed, supported);
    }

    private static void requireOneOf(String field, String observed, String... supported) {
        String normalized = normalize(observed);
        for (String value : supported) {
            if (value.equals(normalized)) return;
        }
        throw new IllegalStateException(
                "runtimePolicy=minimal-cdp no Linux nao possui baseline nativo global para "
                        + field + "=" + (normalized.isEmpty() ? "<vazio>" : normalized)
                        + "; use o valor nativo suportado ou runtimePolicy=legacy-spoof");
    }

    private static String normalize(String value) {
        return value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
    }

    private static boolean isLinux(String osName) {
        return osName != null && osName.toLowerCase(Locale.ROOT).contains("linux");
    }
}
