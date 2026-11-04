package io.github.selenium.driverless;

import org.junit.jupiter.api.*;

import static org.assertj.core.api.Assertions.*;

abstract class StealthFingerprintLocationChecks extends StealthFingerprintRealSiteChecks {
    // 80-89: INTL/TIMEZONE/GEO — Consistência de localização
    //        (crítico quando usando proxy de outro país)
    // ════════════════════════════════════════════════════════════════

    @Test @Order(80)
    @DisplayName("[GEO] Intl.locale corresponde ao perfil")
    void intlLocale() {
        Object r = js("Intl.DateTimeFormat().resolvedOptions().locale");
        System.out.println("  intl.locale = " + r);
        assertThat(r.toString()).isEqualTo(fingerprintProfile.locale);
    }

    @Test @Order(81)
    @DisplayName("[GEO] Intl.timeZone corresponde ao perfil")
    void intlTimezone() {
        Object r = js("Intl.DateTimeFormat().resolvedOptions().timeZone");
        System.out.println("  intl.timeZone = " + r);
        assertThat(r.toString()).isEqualTo(fingerprintProfile.timezoneId);
    }

    @Test @Order(82)
    @DisplayName("[GEO] navigator.language e Intl.locale repetem o perfil")
    void languageVsIntl() {
        Object r = js("""
            (function(){
              var navLang = navigator.language.split('-')[0];
              var intlLang = Intl.DateTimeFormat().resolvedOptions().locale.split('-')[0];
              return navLang === intlLang ? 'MATCH:'+navLang : 'MISMATCH:'+navLang+'!='+intlLang;
            })()""");
        System.out.println("  lang vs intl = " + r);
        assertThat(r.toString()).startsWith("MATCH");
        assertThat(js("navigator.language").toString()).isEqualTo(fingerprintProfile.languages.get(0));
    }

    @Test @Order(83)
    @DisplayName("[GEO] Date timezone offset corresponde ao timezone do perfil")
    void timezoneOffset() {
        int offset = ((Number) js("new Date().getTimezoneOffset()")).intValue();
        System.out.println("  timezoneOffset = " + offset + " min");
        int expected = -java.time.ZonedDateTime.now(java.time.ZoneId.of(fingerprintProfile.timezoneId))
                .getOffset().getTotalSeconds() / 60;
        assertThat(offset).isEqualTo(expected);
    }
}
