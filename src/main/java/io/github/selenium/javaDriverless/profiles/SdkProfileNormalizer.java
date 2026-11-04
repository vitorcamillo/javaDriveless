package io.github.selenium.javaDriverless.profiles;

import java.util.Locale;

public final class SdkProfileNormalizer {
    private SdkProfileNormalizer() {
    }

    public static SdkProfileNormalizationResult normalize(SdkProfile profile) {
        return normalize(profile, true, null);
    }

    public static SdkProfileNormalizationResult normalize(SdkProfile profile, boolean diversityEnabled, Long seed) {
        if (profile == null) {
            throw new IllegalArgumentException("Profile must not be null");
        }
        if (profile.id == null || profile.id.isBlank()) {
            throw new IllegalArgumentException("Profile id must not be blank");
        }
        profile.applyDefaults();
        if (diversityEnabled) {
            SdkProfileGenerator.applyDiversity(
                    profile, seed == null ? SdkProfileGenerator.stableSeed(profile.id) : seed);
        } else {
            SdkProfileGenerator.clearDiversity(profile);
        }

        SdkProfileNormalizationResult result = new SdkProfileNormalizationResult();
        result.profile = profile;
        detectConflicts(profile, result);
        return result;
    }

    private static void detectConflicts(SdkProfile profile, SdkProfileNormalizationResult result) {
        String uaPlatform = lower(profile.fingerprint.uaPlatform);
        String platform = lower(profile.fingerprint.platform);
        if (uaPlatform.contains("windows") && !platform.contains("win")) {
            result.addConflict("uaPlatform=Windows conflicts with navigator platform=" + profile.fingerprint.platform);
        }
        if (uaPlatform.contains("linux") && platform.contains("win")) {
            result.addConflict("uaPlatform=Linux conflicts with navigator platform=" + profile.fingerprint.platform);
        }
        if (uaPlatform.contains("mac") && !platform.contains("mac")) {
            result.addConflict("uaPlatform=macOS conflicts with navigator platform=" + profile.fingerprint.platform);
        }
        if (profile.fingerprint.mobile && (profile.fingerprint.model == null || profile.fingerprint.model.isBlank())) {
            result.addConflict("mobile profile requires a device model");
        }
        if (!profile.fingerprint.mobile && profile.fingerprint.model != null && !profile.fingerprint.model.isBlank()) {
            result.addConflict("desktop profile must not define a mobile model");
        }
        if (profile.fingerprint.locale != null && profile.fingerprint.languages != null
                && !profile.fingerprint.languages.isEmpty()
                && profile.fingerprint.languages.stream().noneMatch(profile.fingerprint.locale::equalsIgnoreCase)) {
            result.addConflict("locale must be present in accepted languages");
        }
        String renderer = lower(profile.fingerprint.webgl.renderer);
        if (uaPlatform.contains("windows") && renderer.contains("apple")) {
            result.addConflict("Windows profile conflicts with Apple WebGL renderer");
        }
        if (uaPlatform.contains("mac") && renderer.contains("direct3d")) {
            result.addConflict("macOS profile conflicts with Direct3D WebGL renderer");
        }
    }

    private static String lower(String value) {
        return value == null ? "" : value.toLowerCase(Locale.ROOT);
    }

}
