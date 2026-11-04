package io.github.selenium.javaDriverless.types;

import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

final class ChromeOptionsSessionProfiles {
    private static final Pattern CONTENT_SETTING_NAME =
            Pattern.compile("[a-z][a-z0-9_-]{0,63}");

    private ChromeOptionsSessionProfiles() {
    }

    static void apply(ChromeOptionsFingerprintOptions options, BrowserSessionProfile sessionProfile) {
        if (sessionProfile == null) {
            return;
        }
        sessionProfile.applyDefaults();
        applyLocation(sessionProfile);
        applyPermissions(options, sessionProfile);
        if (sessionProfile.proxy != null) {
            protectProxyWebRtc(sessionProfile.fingerprint);
        }
        options.setFingerprintProfile(sessionProfile.fingerprint);
        ChromeOptionsLocaleProfiles.apply(options, sessionProfile.fingerprint);
        if (sessionProfile.proxy != null) {
            options.setProxyConfig(sessionProfile.proxy);
        }
        ChromeDnsPolicy.apply((ChromeOptions) options, sessionProfile.dns.policy, sessionProfile.dns.dohTemplate);
    }

    private static void protectProxyWebRtc(BrowserFingerprintProfile profile) {
        profile.webRtc.mode = "altered";
        profile.webRtc.protectLocalIps = true;
        profile.webRtc.disableUdp = true;
    }

    private static void applyLocation(BrowserSessionProfile sessionProfile) {
        String countryCode = sessionProfile.location.countryCode;
        if (countryCode == null || countryCode.isBlank()) {
            return;
        }
        BrowserFingerprintProfile location = BrowserFingerprintProfile.forCountry(countryCode).build();
        sessionProfile.fingerprint.locale = location.locale;
        sessionProfile.fingerprint.timezoneId = location.timezoneId;
        sessionProfile.fingerprint.languages = location.languages;
        sessionProfile.fingerprint.geolocation = location.geolocation;
    }

    private static void applyPermissions(
            ChromeOptionsFingerprintOptions options,
            BrowserSessionProfile sessionProfile) {
        sessionProfile.fingerprint.permissions.geolocation = sessionProfile.permissions.geolocation;
        sessionProfile.fingerprint.permissions.notifications = sessionProfile.permissions.notifications;
        Integer geolocation = contentSettingValue(
                "geolocation", sessionProfile.permissions.geolocation);
        Integer notifications = contentSettingValue(
                "notifications", sessionProfile.permissions.notifications);
        applyContentSetting(options, "geolocation", geolocation);
        applyContentSetting(options, "notifications", notifications);
        if (Integer.valueOf(2).equals(geolocation)) {
            // Permission blocking is a native Chrome preference. `deny` is not a valid
            // minimal-cdp geolocation transport and must not leak into the capability contract.
            sessionProfile.fingerprint.geolocation.mode = "prompt";
            sessionProfile.fingerprint.geolocation.latitude = null;
            sessionProfile.fingerprint.geolocation.longitude = null;
            sessionProfile.fingerprint.geolocation.accuracy = null;
        }
        if (sessionProfile.permissions.extra == null) {
            return;
        }
        for (Map.Entry<String, String> permission : sessionProfile.permissions.extra.entrySet()) {
            String name = contentSettingName(permission.getKey());
            if ("geolocation".equals(name) || "notifications".equals(name)) {
                throw new IllegalArgumentException(
                        "permissions.extra não pode duplicar geolocation/notifications");
            }
            applyContentSetting(options, name,
                    contentSettingValue(permission.getKey(), permission.getValue()));
        }
    }

    private static void applyContentSetting(
            ChromeOptionsFingerprintOptions options,
            String name,
            Integer value) {
        if (value != null) {
            ((ChromeOptions) options).updatePref(
                    "profile.default_content_setting_values." + name, value);
        }
    }

    private static Integer contentSettingValue(String permission, String value) {
        String normalized = value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
        return switch (normalized) {
            case "", "profile", "default", "prompt", "ask" -> null;
            case "allow", "allowed", "grant", "granted" -> 1;
            case "block", "blocked", "deny", "denied" -> 2;
            default -> throw new IllegalArgumentException(
                    "Estado de permissão inválido para " + permission
                            + ": use granted, denied, prompt ou profile");
        };
    }

    private static String contentSettingName(String permission) {
        String normalized = permission == null
                ? "" : permission.trim().toLowerCase(Locale.ROOT);
        if (!CONTENT_SETTING_NAME.matcher(normalized).matches()) {
            throw new IllegalArgumentException(
                    "Nome inválido em permissions.extra");
        }
        return switch (normalized) {
            case "camera" -> "media_stream_camera";
            case "microphone" -> "media_stream_mic";
            case "clipboard-read", "clipboard-write" -> "clipboard";
            default -> normalized.replace('-', '_');
        };
    }
}
