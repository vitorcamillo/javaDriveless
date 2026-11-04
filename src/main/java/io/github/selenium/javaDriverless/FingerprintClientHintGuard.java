package io.github.selenium.javaDriverless;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;

import io.github.selenium.javaDriverless.scripts.NetworkInterceptor;
import io.github.selenium.javaDriverless.types.BrowserFingerprintProfile;
import io.github.selenium.javaDriverless.types.Target;

/**
 * Mantém Client Hints de hardware e qualidade de rede coerentes com o perfil sem criar hints que
 * o Chrome não enviaria por conta própria.
 */
final class FingerprintClientHintGuard {
    private static final String GUARD_ID = "fingerprint-client-hints";
    private static final String SEC_CH_DEVICE_MEMORY = "Sec-CH-Device-Memory";
    private static final String DEVICE_MEMORY = "Device-Memory";

    private FingerprintClientHintGuard() {
    }

    static CompletableFuture<Void> install(
            Target target,
            BrowserFingerprintProfile profile,
            Consumer<Throwable> failureSink) {
        Objects.requireNonNull(profile, "profile");
        if (usesMinimalCdpPolicy(profile)) {
            if (profile.connection != null
                    && "spoof".equalsIgnoreCase(profile.connection.mode)) {
                return CompletableFuture.failedFuture(new IllegalArgumentException(
                        "minimal-cdp não suporta connection.mode=spoof sem alterar a superfície JavaScript"));
            }
            // Device-Memory não possui override CDP no Chrome. Preservar o request nativo evita
            // divergir o Client Hint de navigator.deviceMemory e também elimina um Fetch guard
            // desnecessário no caminho mínimo.
            return CompletableFuture.completedFuture(null);
        }
        if (profile.deviceMemory <= 0) {
            return CompletableFuture.failedFuture(
                    new IllegalArgumentException("deviceMemory do perfil deve ser positivo"));
        }
        return NetworkInterceptor.installEssentialRequestHeaderGuard(
                target,
                GUARD_ID,
                headers -> rewritePresentHeaders(headers, profile),
                failureSink);
    }

    static Map<String, String> rewritePresentHeaders(
            Map<String, String> headers, BrowserFingerprintProfile profile) {
        Objects.requireNonNull(headers, "headers");
        Objects.requireNonNull(profile, "profile");
        Map<String, String> rewritten = new LinkedHashMap<>();
        boolean spoofConnection = profile.connection != null
                && "spoof".equalsIgnoreCase(profile.connection.mode);
        boolean spoofDeviceMemory = !usesMinimalCdpPolicy(profile);
        for (Map.Entry<String, String> header : headers.entrySet()) {
            String name = header.getKey();
            String value = header.getValue();
            if (spoofDeviceMemory && isDeviceMemoryHeader(name)) {
                rewritten.put(name, Integer.toString(profile.deviceMemory));
            } else if (spoofConnection && isAnyHeader(name, "Sec-CH-RTT", "RTT")) {
                rewritten.put(name, Integer.toString(profile.connection.rtt));
            } else if (spoofConnection
                    && isAnyHeader(name, "Sec-CH-Downlink", "Downlink")) {
                rewritten.put(name, decimalHeaderValue(profile.connection.downlink));
            } else if (spoofConnection && isAnyHeader(name, "Sec-CH-ECT", "ECT")) {
                rewritten.put(name, profile.connection.effectiveType);
            } else if (spoofConnection && isAnyHeader(name, "Save-Data")) {
                if (profile.connection.saveData) {
                    rewritten.put(name, "on");
                }
            } else {
                rewritten.put(name, value);
            }
        }
        return rewritten;
    }

    private static boolean usesMinimalCdpPolicy(BrowserFingerprintProfile profile) {
        return BrowserFingerprintProfile.RUNTIME_POLICY_MINIMAL_CDP.equals(profile.runtimePolicy);
    }

    private static boolean isDeviceMemoryHeader(String name) {
        return name != null && (SEC_CH_DEVICE_MEMORY.equalsIgnoreCase(name)
                || DEVICE_MEMORY.equalsIgnoreCase(name));
    }

    private static boolean isAnyHeader(String name, String... candidates) {
        if (name == null) {
            return false;
        }
        for (String candidate : candidates) {
            if (candidate.equalsIgnoreCase(name)) {
                return true;
            }
        }
        return false;
    }

    private static String decimalHeaderValue(double value) {
        return BigDecimal.valueOf(value).stripTrailingZeros().toPlainString();
    }
}
