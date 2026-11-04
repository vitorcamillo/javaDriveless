package io.github.selenium.javaDriverless;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Locale;

/** Environment and filesystem defaults used during JavaDriverless construction. */
final class JavaDriverlessConfiguration {
    private JavaDriverlessConfiguration() {}

    static Path resolveProfilesRoot(String property, String defaultDirectory) {
        String configured = System.getProperty(property);
        Path root = configured == null || configured.isBlank()
                ? Paths.get(System.getProperty("user.dir")).resolve(defaultDirectory)
                : Paths.get(configured);
        return root.toAbsolutePath().normalize();
    }

    static boolean envBool(String key, boolean defaultValue) {
        String raw = System.getenv(key);
        if (raw == null || raw.isBlank()) return defaultValue;
        String value = raw.trim().toLowerCase(Locale.ROOT);
        return value.equals("1") || value.equals("true") || value.equals("yes") || value.equals("on");
    }

    static float envFloat(String key, float defaultValue) {
        String raw = System.getenv(key);
        if (raw == null || raw.isBlank()) return defaultValue;
        try {
            return Float.parseFloat(raw.trim());
        } catch (RuntimeException ignored) {
            return defaultValue;
        }
    }
}
