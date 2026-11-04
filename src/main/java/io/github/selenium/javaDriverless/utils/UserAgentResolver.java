package io.github.selenium.javaDriverless.utils;

import io.github.selenium.javaDriverless.types.BrowserFingerprintProfile;

import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Builds and validates the classic Chrome User-Agent string for a fingerprint profile.
 */
public final class UserAgentResolver {
    private static final String FALLBACK_VERSION = "133.0.0.0";
    private static final String FALLBACK_MAJOR = "133";
    private static final String FALLBACK_ANDROID_VERSION = "13";
    private static final String FALLBACK_ANDROID_MODEL = "SM-G991B";
    private static final Pattern CHROME_MAJOR_PATTERN = Pattern.compile("Chrome/(\\d+)");

    private UserAgentResolver() {
    }

    public static void ensureBrowserVersion(BrowserFingerprintProfile profile) {
        if (profile.browserVersion != null && !profile.browserVersion.isBlank()) {
            return;
        }

        String detectedVersion = Utils.detectChromeVersion();
        if (detectedVersion != null && !detectedVersion.isBlank()) {
            profile.browserVersion = detectedVersion;
        }
    }

    public static boolean isCompatible(BrowserFingerprintProfile profile, String userAgent) {
        if (userAgent == null || userAgent.isBlank()) {
            return false;
        }
        String ua = userAgent.trim();
        if (ua.contains("HeadlessChrome") || ua.contains("Firefox") || ua.contains("Edg/")
                || !ua.contains("Chrome/")) {
            return false;
        }

        String profileMajor = profile.browserVersion == null ? "" : profile.browserVersion.split("\\.")[0];
        if (!profileMajor.isBlank() && !profileMajor.equals(extractChromeMajor(ua))) {
            return false;
        }
        if (!profileMajor.isBlank() && !ua.contains("Chrome/" + profileMajor + ".0.0.0")) {
            return false;
        }

        String uaPlatform = profile.uaPlatform == null ? "" : profile.uaPlatform.toLowerCase(Locale.ROOT);
        boolean recognizedPlatform = profile.mobile
                || uaPlatform.contains("android")
                || uaPlatform.contains("windows")
                || uaPlatform.contains("mac")
                || uaPlatform.contains("linux");
        if (!recognizedPlatform) {
            return false;
        }

        // O UA clássico e os Client Hints são publicados juntos. Validar somente a família do
        // sistema operacional ainda aceitaria Android/model/version diferentes (ou tokens extras)
        // dos valores enviados em userAgentMetadata. A forma canônica é deliberadamente estrita:
        // customização parcial falha fechado em vez de criar uma identidade contraditória.
        return ua.equals(buildForProfile(profile));
    }

    public static String buildForProfile(BrowserFingerprintProfile profile) {
        String version = profile.browserVersion;
        if (version == null || version.isBlank()) {
            version = Utils.detectChromeVersion();
        }
        if (version == null || version.isBlank()) {
            version = FALLBACK_VERSION;
        }
        version = reduceChromeVersionForUserAgent(version);

        String uaPlatform = profile.uaPlatform == null ? "" : profile.uaPlatform.toLowerCase(Locale.ROOT);
        // Perfil mobile/Android: gerar um UA real do Chrome Android (com tokens Android + Mobile),
        // antes do ramo Linux porque Android também reporta uaPlatform/platform baseado em Linux.
        if (profile.mobile || uaPlatform.contains("android")) {
            String androidVersion = androidMajorVersion(profile.platformVersion);
            String model = profile.model == null || profile.model.isBlank()
                    ? FALLBACK_ANDROID_MODEL
                    : profile.model.trim();
            return "Mozilla/5.0 (Linux; Android " + androidVersion + "; " + model + ") "
                    + "AppleWebKit/537.36 (KHTML, like Gecko) Chrome/" + version + " Mobile Safari/537.36";
        }
        if (uaPlatform.contains("windows")) {
            return "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 "
                    + "(KHTML, like Gecko) Chrome/" + version + " Safari/537.36";
        }
        if (uaPlatform.contains("mac")) {
            return "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 "
                    + "(KHTML, like Gecko) Chrome/" + version + " Safari/537.36";
        }
        String linuxArch = "64".equals(profile.bitness) ? "Linux x86_64" : "Linux i686";
        return "Mozilla/5.0 (X11; " + linuxArch + ") AppleWebKit/537.36 "
                + "(KHTML, like Gecko) Chrome/" + version + " Safari/537.36";
    }

    /**
     * Extrai o major da versão do Android (ex.: "13.0.0" → "13"). Fallback plausível
     * quando não informada, para evitar UA Android sem versão.
     */
    private static String androidMajorVersion(String platformVersion) {
        if (platformVersion == null || platformVersion.isBlank()) {
            return FALLBACK_ANDROID_VERSION;
        }
        String major = platformVersion.trim().split("\\.")[0];
        return major.isBlank() ? FALLBACK_ANDROID_VERSION : major;
    }

    public static String extractChromeMajor(String userAgent) {
        if (userAgent == null) {
            return FALLBACK_MAJOR;
        }
        Matcher matcher = CHROME_MAJOR_PATTERN.matcher(userAgent);
        return matcher.find() ? matcher.group(1) : FALLBACK_MAJOR;
    }

    public static String reduceChromeVersionForUserAgent(String version) {
        if (version == null || version.isBlank()) {
            return FALLBACK_VERSION;
        }
        String major = version.split("\\.")[0];
        return major + ".0.0.0";
    }
}
