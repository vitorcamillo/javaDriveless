package io.github.selenium.javaDriverless;

import io.github.selenium.javaDriverless.types.BrowserFingerprintProfile;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Caracterizacao da resolucao autoritativa de timezone antes do launch.
 */
class ChromeFingerprintTimezoneTest {

    @Test
    void explicitTimezoneWinsAndIsTrimmed() {
        BrowserFingerprintProfile profile = new BrowserFingerprintProfile();
        profile.timezoneId = "  America/Sao_Paulo  ";

        String resolved = ChromeFingerprintTimezone.resolve(profile);

        assertThat(resolved).isEqualTo("America/Sao_Paulo");
        assertThat(profile.timezoneId).isEqualTo("America/Sao_Paulo");
    }

    @Test
    void blankTimezoneDerivesFromLocaleCountry() {
        BrowserFingerprintProfile profile = new BrowserFingerprintProfile();
        profile.timezoneId = "";
        profile.locale = "pt-BR";

        String resolved = ChromeFingerprintTimezone.resolve(profile);

        assertThat(resolved).isEqualTo("America/Sao_Paulo");
        assertThat(profile.timezoneId).isEqualTo("America/Sao_Paulo");
    }

    @Test
    void localeWithUnderscoreSeparatorAlsoDerives() {
        BrowserFingerprintProfile profile = new BrowserFingerprintProfile();
        profile.timezoneId = "";
        profile.locale = "ja_JP";

        assertThat(ChromeFingerprintTimezone.resolve(profile)).isEqualTo("Asia/Tokyo");
    }

    @Test
    void regionlessLocaleFallsBackToFirstLanguage() {
        BrowserFingerprintProfile profile = new BrowserFingerprintProfile();
        profile.timezoneId = "";
        profile.locale = "de";
        profile.languages = java.util.List.of("de-DE", "de");

        assertThat(ChromeFingerprintTimezone.resolve(profile)).isEqualTo("Europe/Berlin");
    }

    @Test
    void underivableProfileFailsFast() {
        BrowserFingerprintProfile profile = new BrowserFingerprintProfile();
        profile.timezoneId = "";
        profile.locale = "de";
        profile.languages = java.util.List.of();

        assertThatThrownBy(() -> ChromeFingerprintTimezone.resolve(profile))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("sem timezoneId");
    }

    @Test
    void scriptSubtagLocaleDerivesRegionFromRegionSubtag() {
        // BCP-47: "zh-Hans-CN" tem script ("Hans") entre idioma e regiao;
        // a derivacao precisa achar a subtag de regiao "CN", nao "Hans-CN".
        BrowserFingerprintProfile profile = new BrowserFingerprintProfile();
        profile.timezoneId = "";
        profile.locale = "zh-Hans-CN";
        profile.languages = java.util.List.of();

        assertThat(ChromeFingerprintTimezone.resolve(profile)).isEqualTo("Asia/Shanghai");
    }

    @Test
    void extensionSingletonStopsRegionSearch() {
        // "de-u-ca-gregory": -u- inicia extensao; "ca" NAO e regiao — a
        // derivacao precisa ignorar e cair no fallback de languages.
        BrowserFingerprintProfile profile = new BrowserFingerprintProfile();
        profile.timezoneId = "";
        profile.locale = "de-u-ca-gregory";
        profile.languages = java.util.List.of("de-DE");

        assertThat(ChromeFingerprintTimezone.resolve(profile)).isEqualTo("Europe/Berlin");
    }

    @Test
    void privateUseSingletonStopsRegionSearch() {
        // "en-x-us": -x- e uso privado (RFC 5646); "us" nao pode virar regiao.
        BrowserFingerprintProfile profile = new BrowserFingerprintProfile();
        profile.timezoneId = "";
        profile.locale = "en-x-us";
        profile.languages = java.util.List.of("en-GB");

        assertThat(ChromeFingerprintTimezone.resolve(profile)).isEqualTo("Europe/London");
    }

    @Test
    void nonAsciiSubtagIsNotARegion() {
        BrowserFingerprintProfile profile = new BrowserFingerprintProfile();
        profile.timezoneId = "";
        profile.locale = "it-İT";
        profile.languages = java.util.List.of();

        assertThatThrownBy(() -> ChromeFingerprintTimezone.resolve(profile))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("sem timezoneId");
    }

    @Test
    void numericRegionSubtagDerivesRegion() {
        BrowserFingerprintProfile profile = new BrowserFingerprintProfile();
        profile.timezoneId = "";
        profile.locale = "es-419";
        profile.languages = java.util.List.of("es-AR");

        assertThat(ChromeFingerprintTimezone.resolve(profile)).isEqualTo("America/Argentina/Buenos_Aires");
    }
}
