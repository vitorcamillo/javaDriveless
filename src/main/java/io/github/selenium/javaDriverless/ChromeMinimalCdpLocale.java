package io.github.selenium.javaDriverless;

import com.fasterxml.jackson.databind.JsonNode;
import io.github.selenium.javaDriverless.types.BrowserFingerprintProfile;
import io.github.selenium.javaDriverless.types.Target;

import java.util.List;
import java.util.Locale;
import java.util.Map;

/** Resolve o locale nativo efetivo do Chrome antes da primeira navegacao minimal-CDP. */
final class ChromeMinimalCdpLocale {
    private static final String PROBE_EXPRESSION = """
            ({intlLocale: Intl.NumberFormat().resolvedOptions().locale,
              navigatorLanguage: navigator.language})
            """;

    private ChromeMinimalCdpLocale() {}

    static String captureAndValidate(Target target, BrowserFingerprintProfile profile) {
        JsonNode response = target.executeCdpCmd("Runtime.evaluate", Map.of(
                "expression", PROBE_EXPRESSION,
                "returnByValue", true,
                "awaitPromise", false), null).join();
        JsonNode value = response.path("result").path("value");
        String effective = value.path("intlLocale").asText("").trim();
        String navigatorLanguage = value.path("navigatorLanguage").asText("").trim();
        List<String> languages = profile.languages == null ? List.of() : profile.languages;
        String primary = languages.isEmpty() ? profile.locale : languages.get(0);

        if (effective.isBlank() || navigatorLanguage.isBlank()) {
            throw new IllegalStateException(
                    "runtimePolicy=minimal-cdp nao conseguiu observar o locale nativo no about:blank");
        }
        if (!language(effective).equals(language(profile.locale))) {
            throw new IllegalStateException(
                    "runtimePolicy=minimal-cdp rejeitou fallback de Intl fora da familia do perfil");
        }
        if (!canonical(navigatorLanguage).equals(canonical(primary))) {
            throw new IllegalStateException(
                    "runtimePolicy=minimal-cdp rejeitou navigator.language diferente do idioma primario");
        }
        return effective;
    }

    private static String language(String tag) {
        String language = locale(tag).getLanguage();
        if (language.isBlank()) {
            throw new IllegalStateException("Locale BCP-47 sem idioma valido");
        }
        return language;
    }

    private static String canonical(String tag) {
        return locale(tag).toLanguageTag();
    }

    private static Locale locale(String tag) {
        if (tag == null || tag.isBlank()) {
            throw new IllegalStateException("Locale BCP-47 ausente");
        }
        return Locale.forLanguageTag(tag.replace('_', '-'));
    }
}
