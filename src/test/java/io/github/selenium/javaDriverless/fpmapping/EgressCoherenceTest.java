package io.github.selenium.javaDriverless.fpmapping;

import io.github.selenium.javaDriverless.types.BrowserFingerprintProfile;
import io.github.selenium.javaDriverless.types.EgressCoherence;
import io.github.selenium.javaDriverless.types.EgressInfo;
import org.junit.jupiter.api.Test;

import java.time.ZoneId;
import java.util.Locale;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EgressCoherenceTest {

    @Test
    void connectionTypeIsEmptyByDefault_noFakeWifiOnDesktop() {
        // Fix: desktop real não expõe navigator.connection.type
        assertTrue(new BrowserFingerprintProfile().connection.type.isEmpty());
    }

    @Test
    void applySegueOIpEmTimezoneIdiomaGeoWebrtc() {
        BrowserFingerprintProfile fp = new BrowserFingerprintProfile();
        EgressInfo e = new EgressInfo();
        e.timezone = "America/Manaus";
        e.countryCode = "BR";
        e.latitude = -3.1;
        e.longitude = -60.0;
        e.publicIp = "200.1.2.3";

        EgressCoherence.apply(fp, e);

        assertEquals("America/Manaus", fp.timezoneId);   // tz segue o IP, não SP
        assertEquals("pt-BR", fp.locale);
        assertEquals(List_ptBR(), fp.languages);
        assertEquals(-3.1, fp.geolocation.latitude);
        assertEquals(-60.0, fp.geolocation.longitude);
        assertEquals("override", fp.geolocation.mode);
        assertEquals("200.1.2.3", fp.webRtc.publicIp);
        assertTrue(fp.webRtc.disableUdp);
        assertTrue(fp.webRtc.protectLocalIps);
    }

    @Test
    void semCoordenadasLimpaGeo_naoVazaPaisDefault() {
        BrowserFingerprintProfile fp = new BrowserFingerprintProfile();
        fp.geolocation.latitude = -23.55;   // "país-default" cravado
        fp.geolocation.longitude = -46.63;
        fp.geolocation.mode = "override";

        EgressInfo e = new EgressInfo();
        e.countryCode = "BR";  // sem lat/lon

        EgressCoherence.apply(fp, e);

        assertNull(fp.geolocation.latitude);
        assertNull(fp.geolocation.longitude);
        assertEquals("prompt", fp.geolocation.mode);
    }

    @Test
    void egressMovelDeclaraConexaoCellular() {
        BrowserFingerprintProfile fp = new BrowserFingerprintProfile();
        EgressInfo e = new EgressInfo();
        e.mobile = true;
        e.publicIp = "203.0.113.44";

        EgressCoherence.apply(fp, e);

        assertEquals("spoof", fp.connection.mode);
        assertEquals("cellular", fp.connection.type);
        assertEquals("4g", fp.connection.effectiveType);
        assertEquals("203.0.113.44", fp.webRtc.publicIp);
    }

    @Test
    void egressMovelNaoCriaSurfaceSinteticaNoMinimalCdp() {
        BrowserFingerprintProfile fp = BrowserFingerprintProfile.nativeDefaults();
        fp.runtimePolicy = BrowserFingerprintProfile.RUNTIME_POLICY_MINIMAL_CDP;
        fp.connection.mode = "real";
        fp.connection.type = "";
        EgressInfo e = new EgressInfo();
        e.mobile = true;
        e.publicIp = "203.0.113.44";

        EgressCoherence.apply(fp, e);

        assertEquals("real", fp.connection.mode);
        assertTrue(fp.connection.type.isEmpty());
        assertTrue(fp.webRtc.publicIp.isEmpty());
        assertTrue(fp.webRtc.allowedIps.isEmpty());
    }

    @Test
    void egressFixoNaoForcaTipoDeConexao() {
        BrowserFingerprintProfile fp = new BrowserFingerprintProfile();
        EgressInfo e = new EgressInfo();
        e.mobile = false;

        EgressCoherence.apply(fp, e);

        assertTrue(fp.connection.type.isEmpty());  // mantém coerência desktop
    }

    @Test
    void restoreRemoveTodoEstadoDeUmEgressAnterior() {
        BrowserFingerprintProfile baseline = new BrowserFingerprintProfile();
        baseline.timezoneId = "America/New_York";
        baseline.webRtc.mode = "real";
        baseline.webRtc.disableUdp = false;
        BrowserFingerprintProfile fp = baseline.copy();
        EgressInfo egress = new EgressInfo();
        egress.countryCode = "BR";
        egress.timezone = "America/Sao_Paulo";
        egress.publicIp = "203.0.113.9";
        egress.mobile = true;

        EgressCoherence.apply(fp, egress);
        EgressCoherence.restore(fp, baseline);

        assertEquals("en-US", fp.locale);
        assertEquals(java.util.List.of("en-US", "en"), fp.languages);
        assertEquals("America/New_York", fp.timezoneId);
        assertEquals("real", fp.webRtc.mode);
        assertEquals(false, fp.webRtc.disableUdp);
        assertEquals("", fp.webRtc.publicIp);
        assertEquals("prompt", fp.geolocation.mode);
        assertEquals("real", fp.connection.mode);
        assertEquals("", fp.connection.type);
    }

    @Test
    void paisDesconhecidoNaoMexeNoIdioma() {
        assertTrue(EgressCoherence.languagesForCountry("ZZ").isEmpty());
        assertEquals("pt-BR", EgressCoherence.languagesForCountry("br").get(0));
    }

    @Test
    void applyHostLocaleAplicaFusoIdiomaEPreservaWebRtcGeo() {
        BrowserFingerprintProfile fp = new BrowserFingerprintProfile();
        fp.timezoneId = "America/New_York";
        fp.languages = java.util.List.of("en-US", "en");
        fp.locale = "en-US";
        fp.webRtc.publicIp = "203.0.113.9";
        fp.geolocation.mode = "override";
        String publicIp = fp.webRtc.publicIp;
        String geolocationMode = fp.geolocation.mode;

        EgressCoherence.applyHostLocale(fp, ZoneId.of("America/Sao_Paulo"), new Locale("pt", "BR"));

        assertEquals("America/Sao_Paulo", fp.timezoneId);
        assertEquals(EgressCoherence.languagesForCountry("BR"), fp.languages);
        assertEquals(EgressCoherence.languagesForCountry("BR").get(0), fp.locale);
        assertEquals(publicIp, fp.webRtc.publicIp);
        assertEquals(geolocationMode, fp.geolocation.mode);
    }

    @Test
    void applyHostLocaleSemPaisPreservaIdiomaMasAplicaFuso() {
        BrowserFingerprintProfile fp = new BrowserFingerprintProfile();
        fp.timezoneId = "America/New_York";
        fp.languages = java.util.List.of("en-US", "en");
        fp.locale = "en-US";
        EgressCoherence.applyHostLocale(fp, ZoneId.of("America/Sao_Paulo"), Locale.ENGLISH);

        assertEquals("America/Sao_Paulo", fp.timezoneId);
        assertEquals(java.util.List.of("en"), fp.languages);
        assertEquals("en", fp.locale);
    }

    @Test
    void applyHostLocalePriorizaIdiomaDoLocaleSobrePais() {
        BrowserFingerprintProfile fp = new BrowserFingerprintProfile();

        EgressCoherence.applyHostLocale(fp, null, Locale.CANADA_FRENCH);

        assertEquals("fr-CA", fp.locale);
        assertEquals("fr-CA", fp.languages.get(0));
        assertTrue(fp.languages.contains("en-CA"));
    }

    @Test
    void applyHostLocaleAplicaIdiomaForaDaTabelaDePaises() {
        BrowserFingerprintProfile fp = new BrowserFingerprintProfile();

        EgressCoherence.applyHostLocale(fp, null, Locale.SIMPLIFIED_CHINESE);

        assertEquals("zh-CN", fp.locale);
        assertEquals(java.util.List.of("zh-CN"), fp.languages);
    }

    @Test
    void applyHostLocaleComPerfilNuloNaoLanca() {
        EgressCoherence.applyHostLocale(null, ZoneId.of("America/Sao_Paulo"), new Locale("pt", "BR"));
        BrowserFingerprintProfile fp = new BrowserFingerprintProfile();
        fp.languages = java.util.List.of("en-US", "en");
        EgressCoherence.applyHostLocale(fp, null, Locale.ROOT);
        assertEquals(java.util.List.of("en-US", "en"), fp.languages); // nunca "und"
    }

    @Test
    void tabelaDeIdiomasCobreCadaPaisSuportado() {
        record Caso(String pais, String primaria) {}
        java.util.List<Caso> casos = java.util.List.of(
            new Caso("BR", "pt-BR"), new Caso("PT", "pt-PT"), new Caso("DE", "de-DE"),
            new Caso("FR", "fr-FR"), new Caso("GB", "en-GB"), new Caso("ES", "es-ES"),
            new Caso("MX", "es-MX"), new Caso("AR", "es-AR"), new Caso("CL", "es-CL"),
            new Caso("CO", "es-CO"), new Caso("JP", "ja-JP"), new Caso("CA", "en-CA"),
            new Caso("AU", "en-AU"), new Caso("SG", "en-SG"), new Caso("IN", "en-IN"),
            new Caso("US", "en-US"), new Caso("IT", "it-IT"), new Caso("NL", "nl-NL"),
            new Caso("SE", "sv-SE"), new Caso("PL", "pl-PL"), new Caso("TR", "tr-TR"),
            new Caso("RU", "ru-RU"), new Caso("KR", "ko-KR"));

        for (Caso caso : casos) {
            java.util.List<String> langs = EgressCoherence.languagesForCountry(caso.pais());
            assertTrue(!langs.isEmpty(), "idiomas esperados para " + caso.pais());
            assertEquals(caso.primaria(), langs.get(0),
                caso.pais() + " deve ter o locale local primeiro");
            // país em minúscula também resolve (normalização)
            assertEquals(langs, EgressCoherence.languagesForCountry(caso.pais().toLowerCase()));
        }
        assertTrue(EgressCoherence.languagesForCountry(null).isEmpty());
    }

    @Test
    void tabelaDeTimezonesCobreCadaPaisSuportado() {
        record Caso(String pais, String zona) {}
        java.util.List<Caso> casos = java.util.List.of(
            new Caso("BR", "America/Sao_Paulo"), new Caso("PT", "Europe/Lisbon"),
            new Caso("DE", "Europe/Berlin"), new Caso("FR", "Europe/Paris"),
            new Caso("GB", "Europe/London"), new Caso("ES", "Europe/Madrid"),
            new Caso("MX", "America/Mexico_City"),
            new Caso("AR", "America/Argentina/Buenos_Aires"),
            new Caso("CL", "America/Santiago"), new Caso("CO", "America/Bogota"),
            new Caso("JP", "Asia/Tokyo"), new Caso("CA", "America/Toronto"),
            new Caso("AU", "Australia/Sydney"), new Caso("SG", "Asia/Singapore"),
            new Caso("IN", "Asia/Kolkata"), new Caso("US", "America/New_York"),
            new Caso("IT", "Europe/Rome"), new Caso("NL", "Europe/Amsterdam"),
            new Caso("SE", "Europe/Stockholm"), new Caso("PL", "Europe/Warsaw"),
            new Caso("TR", "Europe/Istanbul"), new Caso("RU", "Europe/Moscow"),
            new Caso("KR", "Asia/Seoul"), new Caso("CN", "Asia/Shanghai"));

        for (Caso caso : casos) {
            assertEquals(caso.zona(), EgressCoherence.timezoneForCountry(caso.pais()),
                "timezone de " + caso.pais());
            assertEquals(caso.zona(), EgressCoherence.timezoneForCountry(caso.pais().toLowerCase()),
                "timezone minúscula de " + caso.pais());
        }
        assertNull(EgressCoherence.timezoneForCountry("ZZ"));
        assertNull(EgressCoherence.timezoneForCountry(null));
    }

    @Test
    void applyENuloNaoAlteraNadaEResolveEgressSeguePrecedencia() throws Exception {
        // no-op defensivo
        EgressCoherence.apply(null, new EgressInfo());
        BrowserFingerprintProfile intacto = new BrowserFingerprintProfile();
        EgressCoherence.apply(intacto, null);

        EgressInfo explicito = new EgressInfo();
        explicito.countryCode = "US";
        assertEquals(explicito, EgressCoherence.resolveEgress(
            explicito, p -> { throw new IllegalStateException("resolver não pode ser chamado"); }, null));
        assertNull(EgressCoherence.resolveEgress(null, null, null));

        EgressInfo resolvido = new EgressInfo();
        resolvido.countryCode = "JP";
        assertEquals(resolvido, EgressCoherence.resolveEgress(null, p -> resolvido, null));
    }

    private static java.util.List<String> List_ptBR() {
        return java.util.List.of("pt-BR", "pt", "en-US", "en");
    }
}
