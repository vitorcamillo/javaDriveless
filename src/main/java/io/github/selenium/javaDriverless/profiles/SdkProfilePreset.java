package io.github.selenium.javaDriverless.profiles;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** The complete public preset catalog supported by JavaDriverless. */
public enum SdkProfilePreset {
    WINDOWS("windows"),
    LINUX("linux"),
    DOCKER("docker"),
    PHANTOMPASS("phantompass"),
    SELENIUMLIKE("seleniumlike");

    public static final String PRESET_ENV = "JAVA_DRIVERLESS_PROFILE_PRESET";

    private final String id;

    SdkProfilePreset(String id) {
        this.id = id;
    }

    public String id() {
        return id;
    }

    public boolean isRuntimeBase() {
        return this == WINDOWS || this == LINUX || this == DOCKER;
    }

    public static List<String> supportedIds() {
        return Arrays.stream(values()).map(SdkProfilePreset::id).toList();
    }

    public static SdkProfilePreset parse(String value) {
        String normalized = value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
        if ("selenium-like".equals(normalized)) {
            return SELENIUMLIKE;
        }
        return Arrays.stream(values())
                .filter(preset -> preset.id.equals(normalized))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException(
                        "Perfil não suportado: use somente " + String.join(", ", supportedIds())));
    }

    /** Selects one of the three runtime presets; it does not create a sixth AUTO preset. */
    public static SdkProfilePreset defaultRuntime() {
        return detectDefaultRuntime(
                System.getProperty("os.name", ""), System.getenv(), Files.exists(Path.of("/.dockerenv")));
    }

    static SdkProfilePreset detectDefaultRuntime(
            String osName,
            Map<String, String> environment,
            boolean dockerMarker) {
        String explicit = environment == null ? null : environment.get(PRESET_ENV);
        if (explicit != null && !explicit.isBlank()) {
            SdkProfilePreset selected = parse(explicit);
            if (!selected.isRuntimeBase()) {
                throw new IllegalArgumentException(
                        PRESET_ENV + " aceita como padrão somente windows, linux ou docker; "
                                + selected.id + " exige seleção explícita pelo consumidor");
            }
            return selected;
        }
        boolean container = dockerMarker || truthy(environment == null
                ? null : environment.get("JAVA_DRIVERLESS_CONTAINER_FLAGS"));
        if (container) {
            return DOCKER;
        }
        String host = osName == null ? "" : osName.toLowerCase(Locale.ROOT);
        if (host.contains("win")) {
            return WINDOWS;
        }
        if (host.contains("linux")) {
            return LINUX;
        }
        throw new IllegalStateException(
                "Host sem preset seguro: configure explicitamente windows, linux ou docker");
    }

    private static boolean truthy(String value) {
        if (value == null) {
            return false;
        }
        return switch (value.trim().toLowerCase(Locale.ROOT)) {
            case "1", "true", "yes", "on" -> true;
            default -> false;
        };
    }
}
