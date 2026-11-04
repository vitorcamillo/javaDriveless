package io.github.selenium.javaDriverless;

import io.github.selenium.javaDriverless.types.BrowserFingerprintProfile;

import java.util.List;
import java.util.Locale;
import java.util.Set;

/** Fail-fast local host/profile compatibility contract for the wrapper-free runtime policy. */
final class MinimalCdpHostCompatibility {
    private MinimalCdpHostCompatibility() {
    }

    static void requireCompatible(BrowserFingerprintProfile profile, String userAgent, String hostOs) {
        requireCompatible(profile, userAgent, hostOs, System.getProperty("os.arch", ""));
    }

    static void requireCompatible(
            BrowserFingerprintProfile profile,
            String userAgent,
            String hostOs,
            String hostArch) {
        if (profile == null) {
            throw new IllegalArgumentException("Fingerprint profile must not be null");
        }
        if (!BrowserFingerprintProfile.RUNTIME_POLICY_MINIMAL_CDP.equalsIgnoreCase(
                profile.runtimePolicy == null ? "" : profile.runtimePolicy.trim())) {
            return;
        }

        OsFamily host = hostFamily(hostOs);
        if (host == OsFamily.UNKNOWN) {
            throw unsupported("não suporta host OS desconhecido");
        }
        if (!isX86_64(hostArch)) {
            throw unsupported(
                    "suporta somente host desktop x86_64; observado hostArch=" + printable(hostArch));
        }

        OsFamily ua = userAgentFamily(userAgent);
        OsFamily uaPlatform = platformFamily(profile.uaPlatform);
        OsFamily navigatorPlatform = platformFamily(profile.platform);
        boolean android = profile.mobile
                || ua == OsFamily.ANDROID
                || uaPlatform == OsFamily.ANDROID
                || navigatorPlatform == OsFamily.ANDROID;
        if (android) {
            throw unsupported("não aceita perfil Android/mobile");
        }
        if (ua != host || uaPlatform != host || navigatorPlatform != host) {
            throw unsupported(
                    "exige UA, uaPlatform e navigator.platform compatíveis com host=" + host.label
                            + "; observado ua=" + ua.label
                            + ", uaPlatform=" + uaPlatform.label
                            + ", platform=" + navigatorPlatform.label);
        }

        NativeDesktopIdentity expected = NativeDesktopIdentity.forHost(host);
        if (!expected.uaPlatform.equals(trim(profile.uaPlatform))
                || !expected.navigatorPlatform.equals(trim(profile.platform))) {
            throw unsupported(
                    "exige identidade nativa " + host.label + " x86_64"
                            + "; esperado uaPlatform=" + expected.uaPlatform
                            + ", platform=" + expected.navigatorPlatform
                            + "; observado uaPlatform=" + printable(profile.uaPlatform)
                            + ", platform=" + printable(profile.platform));
        }
        if (!expected.matchesUserAgent(userAgent)) {
            throw unsupported(
                    "exige User-Agent nativo " + host.label + " x86_64"
                            + "; o User-Agent observado não contém os marcadores de arquitetura esperados");
        }
        if (!"x86".equals(trim(profile.architecture)) || !"64".equals(trim(profile.bitness))) {
            throw unsupported(
                    "exige architecture=x86 e bitness=64 no host x86_64"
                            + "; observado architecture=" + printable(profile.architecture)
                            + ", bitness=" + printable(profile.bitness));
        }
        if (profile.mobile) {
            throw unsupported("exige mobile=false para identidade desktop");
        }
        if (!trim(profile.model).isEmpty()) {
            throw unsupported(
                    "exige model vazio para identidade desktop; observado model="
                            + printable(profile.model));
        }
        if (profile.wow64) {
            throw unsupported("exige wow64=false para processo nativo x86_64");
        }
        if (!isDesktopFormFactor(profile.formFactors)) {
            throw unsupported(
                    "exige formFactors=[Desktop]; observado formFactors="
                            + String.valueOf(profile.formFactors));
        }
    }

    private static IllegalStateException unsupported(String reason) {
        return new IllegalStateException(
                "runtimePolicy=minimal-cdp " + reason + "; use runtimePolicy=legacy-spoof");
    }

    private static boolean isX86_64(String value) {
        String normalized = normalize(value).replaceAll("[^a-z0-9]", "");
        return Set.of("amd64", "x8664", "x64").contains(normalized);
    }

    private static boolean isDesktopFormFactor(List<String> values) {
        // Vazio/null é o valor declarativo "não informado" que applyDefaults normaliza para
        // Desktop antes do launch. Qualquer valor explícito, porém, deve coincidir exatamente.
        return values == null || values.isEmpty() || List.of("Desktop").equals(values);
    }

    private static OsFamily hostFamily(String value) {
        String normalized = normalize(value);
        if (normalized.contains("mac") || normalized.contains("darwin")) {
            return OsFamily.MAC;
        }
        if (normalized.contains("win")) {
            return OsFamily.WINDOWS;
        }
        if (normalized.contains("linux") || normalized.contains("docker")) {
            return OsFamily.LINUX;
        }
        return OsFamily.UNKNOWN;
    }

    private static OsFamily userAgentFamily(String value) {
        String normalized = normalize(value);
        if (normalized.contains("android")) {
            return OsFamily.ANDROID;
        }
        if (normalized.contains("windows")) {
            return OsFamily.WINDOWS;
        }
        if (normalized.contains("macintosh") || normalized.contains("mac os x")) {
            return OsFamily.MAC;
        }
        if (normalized.contains("linux") || normalized.contains("x11")) {
            return OsFamily.LINUX;
        }
        return OsFamily.UNKNOWN;
    }

    private static OsFamily platformFamily(String value) {
        String normalized = normalize(value);
        if (normalized.contains("android")) {
            return OsFamily.ANDROID;
        }
        if (normalized.contains("mac") || normalized.contains("darwin")) {
            return OsFamily.MAC;
        }
        if (normalized.contains("win")) {
            return OsFamily.WINDOWS;
        }
        if (normalized.contains("linux")) {
            return OsFamily.LINUX;
        }
        return OsFamily.UNKNOWN;
    }

    private static String normalize(String value) {
        return value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
    }

    private static String trim(String value) {
        return value == null ? "" : value.trim();
    }

    private static String printable(String value) {
        String trimmed = trim(value);
        return trimmed.isEmpty() ? "<empty>" : trimmed;
    }

    private record NativeDesktopIdentity(
            String uaPlatform,
            String navigatorPlatform,
            List<String> requiredUserAgentMarkers,
            List<String> forbiddenUserAgentMarkers) {
        static NativeDesktopIdentity forHost(OsFamily host) {
            return switch (host) {
                case LINUX -> new NativeDesktopIdentity(
                        "Linux", "Linux x86_64",
                        List.of("x11", "linux x86_64"),
                        List.of("android", "aarch64", "i686"));
                case WINDOWS -> new NativeDesktopIdentity(
                        "Windows", "Win32",
                        List.of("windows nt", "win64", "x64"),
                        List.of("wow64", "arm64"));
                case MAC -> new NativeDesktopIdentity(
                        "macOS", "MacIntel",
                        List.of("macintosh", "intel mac os x"),
                        List.of("arm64", "aarch64"));
                default -> throw new IllegalArgumentException(
                        "Host sem identidade desktop nativa suportada: " + host.label);
            };
        }

        boolean matchesUserAgent(String userAgent) {
            String normalized = normalize(userAgent);
            return requiredUserAgentMarkers.stream().allMatch(normalized::contains)
                    && forbiddenUserAgentMarkers.stream().noneMatch(normalized::contains);
        }
    }

    private enum OsFamily {
        LINUX("linux"),
        WINDOWS("windows"),
        MAC("mac"),
        ANDROID("android"),
        UNKNOWN("unknown");

        private final String label;

        OsFamily(String label) {
            this.label = label;
        }
    }
}
