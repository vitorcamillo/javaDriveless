package io.github.selenium.javaDriverless;

import io.github.selenium.javaDriverless.types.BrowserFingerprintProfile;
import io.github.selenium.javaDriverless.types.EgressCoherence;

/** Resolve o timezone autoritativo antes de criar o processo Chrome. */
final class ChromeFingerprintTimezone {
    private ChromeFingerprintTimezone() {}

    static String resolve(BrowserFingerprintProfile profile) {
        String configured = profile.timezoneId == null ? "" : profile.timezoneId.trim();
        if (!configured.isEmpty()) {
            profile.timezoneId = configured;
            return configured;
        }
        String derived = EgressCoherence.timezoneForCountry(region(profile.locale));
        if (derived == null && profile.languages != null && !profile.languages.isEmpty()) {
            derived = EgressCoherence.timezoneForCountry(region(profile.languages.get(0)));
        }
        if (derived == null || derived.isBlank()) {
            throw new IllegalStateException(
                    "Perfil de fingerprint sem timezoneId e sem regiao derivavel");
        }
        profile.timezoneId = derived;
        return derived;
    }

    private static String region(String locale) {
        if (locale == null) return "";
        String[] subtags = locale.replace('_', '-').split("-");
        for (int index = 1; index < subtags.length; index++) {
            String subtag = subtags[index];
            if (subtag.length() == 1) {
                // Singleton (RFC 5646 2.2.1) inicia extensao (-u-) ou uso
                // privado (-x-); a regiao, se existir, vem sempre antes dele.
                return "";
            }
            if (isRegionSubtag(subtag)) {
                return subtag;
            }
        }
        return "";
    }

    /**
     * Subtag de regiao BCP-47: 2 letras ASCII (ISO 3166-1 alfa-2) ou 3 digitos
     * ASCII (UN M49). Exclui script ("Hans", 4 letras) e variantes, permitindo
     * derivar "zh-Hans-CN" -> "CN". ASCII estrito: Character.isLetter aceitaria
     * subtags unicode como "İT".
     */
    private static boolean isRegionSubtag(String subtag) {
        if (subtag.length() == 2) {
            return isAsciiAlpha(subtag.charAt(0)) && isAsciiAlpha(subtag.charAt(1));
        }
        if (subtag.length() == 3) {
            return subtag.chars().allMatch(ChromeFingerprintTimezone::isAsciiDigit);
        }
        return false;
    }

    private static boolean isAsciiAlpha(int codePoint) {
        return (codePoint >= 'a' && codePoint <= 'z') || (codePoint >= 'A' && codePoint <= 'Z');
    }

    private static boolean isAsciiDigit(int codePoint) {
        return codePoint >= '0' && codePoint <= '9';
    }
}
