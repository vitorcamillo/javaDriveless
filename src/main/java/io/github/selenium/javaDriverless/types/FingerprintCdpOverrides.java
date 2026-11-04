package io.github.selenium.javaDriverless.types;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

public final class FingerprintCdpOverrides {
    private FingerprintCdpOverrides() {}

    public static Map<String, Object> deviceMetrics(BrowserFingerprintProfile profile) {
        profile.applyDefaults();
        Map<String, Object> metrics = new HashMap<>();
        metrics.put("width", positive(profile.screen.width, 1920));
        metrics.put("height", positive(profile.screen.height, 1080));
        metrics.put("deviceScaleFactor", profile.screen.deviceScaleFactor > 0
                ? profile.screen.deviceScaleFactor : 1);
        metrics.put("mobile", profile.mobile);
        metrics.put("screenWidth", positive(profile.screen.width, 1920));
        metrics.put("screenHeight", positive(profile.screen.height, 1080));
        metrics.put("positionX", 0);
        metrics.put("positionY", 0);
        metrics.put("dontSetVisibleSize", true);
        return metrics;
    }

    public static Map<String, Object> headlessDeviceMetrics(BrowserFingerprintProfile profile) {
        profile.applyDefaults();
        Map<String, Object> metrics = new HashMap<>();
        metrics.put("width", positive(profile.screen.width, 1920));
        metrics.put("height", positive(profile.screen.availHeight - 95, 945));
        metrics.put("deviceScaleFactor", profile.screen.deviceScaleFactor > 0
                ? profile.screen.deviceScaleFactor : 1);
        metrics.put("mobile", profile.mobile);
        metrics.put("screenWidth", positive(profile.screen.width, 1920));
        metrics.put("screenHeight", positive(profile.screen.height, 1080));
        metrics.put("positionX", 0);
        metrics.put("positionY", 0);
        metrics.put("dontSetVisibleSize", false);
        return metrics;
    }

    /** Overrides de media queries que precisam ser repetidos em cada target OOPIF. */
    public static Map<String, Object> emulatedMedia(BrowserFingerprintProfile profile) {
        profile.applyDefaults();
        return Map.of("features", List.of(
                Map.of("name", "prefers-color-scheme", "value", profile.colorScheme),
                Map.of("name", "prefers-reduced-motion", "value", profile.prefersReducedMotion),
                Map.of("name", "prefers-reduced-transparency", "value", profile.prefersReducedTransparency),
                Map.of("name", "prefers-contrast", "value", profile.prefersContrast),
                Map.of("name", "forced-colors", "value", profile.forcedColors)));
    }

    /** Coordenadas CDP do perfil, compartilhadas pelo target principal e pelos OOPIFs. */
    public static Map<String, Object> geolocation(BrowserFingerprintProfile profile) {
        profile.applyDefaults();
        if (profile.geolocation == null
                || profile.geolocation.latitude == null
                || profile.geolocation.longitude == null) {
            throw new IllegalArgumentException("Fingerprint geolocation requires latitude and longitude");
        }
        Map<String, Object> values = new HashMap<>();
        values.put("latitude", profile.geolocation.latitude);
        values.put("longitude", profile.geolocation.longitude);
        values.put("accuracy", profile.geolocation.accuracy == null
                ? 100.0 : profile.geolocation.accuracy);
        return values;
    }

    /**
     * Valor de {@code acceptLanguage} esperado por {@code Network.setUserAgentOverride}.
     *
     * <p>O CDP recebe a lista limpa de idiomas do navegador e o próprio Chromium adiciona os
     * q-values ao montar o header HTTP. Enviar pesos aqui produz valores duplicados, por exemplo
     * {@code en;q=0.9;q=0.9}, além de contaminar {@code navigator.languages} nativo.</p>
     */
    public static String acceptLanguage(List<String> languages) {
        if (languages == null || languages.isEmpty()) {
            return "en-US,en";
        }
        StringBuilder value = new StringBuilder();
        int count = 0;
        for (String language : languages) {
            if (language == null || language.isBlank()) {
                continue;
            }
            String tag = language.trim();
            if (tag.indexOf(',') >= 0 || tag.indexOf(';') >= 0) {
                throw new IllegalArgumentException(
                        "Fingerprint language must be a single tag without q-values: " + tag);
            }
            if (count >= 6) {
                break;
            }
            if (!value.isEmpty()) {
                value.append(',');
            }
            value.append(tag);
            count++;
        }
        return value.isEmpty() ? "en-US,en" : value.toString();
    }

    /** Valor HTTP que o Chromium atual produz para a lista limpa usada no override CDP. */
    public static String acceptLanguageHttpHeader(List<String> languages) {
        String[] tags = acceptLanguage(languages).split(",");
        StringBuilder header = new StringBuilder(tags[0]);
        for (int index = 1; index < tags.length; index++) {
            double quality = Math.max(0.1, 1.0 - index * 0.1);
            header.append(',').append(tags[index]).append(";q=").append(
                    java.math.BigDecimal.valueOf(quality)
                            .setScale(1, java.math.RoundingMode.HALF_UP)
                            .stripTrailingZeros().toPlainString());
        }
        return header.toString();
    }

    private static int positive(int value, int fallback) {
        return value > 0 ? value : fallback;
    }
}
