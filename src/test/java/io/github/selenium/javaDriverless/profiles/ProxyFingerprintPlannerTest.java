package io.github.selenium.javaDriverless.profiles;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ProxyFingerprintPlannerTest {
    @Test
    void successfulCountryEvidenceProducesLocaleTimezoneGeoAndWebRtcPatch() {
        SdkProfile profile = new SdkProfile("proxy-br", "Proxy BR");
        ProxyEvidence evidence = success("BR", "198.51.100.10");
        evidence.latitude = -23.5505;
        evidence.longitude = -46.6333;

        ProxyFingerprintPlan plan = ProxyFingerprintPlanner.plan(profile, evidence);

        assertThat(plan.status).isEqualTo("ready");
        assertThat(plan.patches).containsEntry("locale", "pt-BR");
        assertThat(plan.patches).containsEntry("timezoneId", "America/Sao_Paulo");
        assertThat(plan.patches).containsEntry("webRtc.publicIp", "198.51.100.10");
    }

    @Test
    void missingOrFailedEvidenceBlocksAutomaticCoherence() {
        SdkProfile profile = new SdkProfile("profile", "Profile");
        ProxyEvidence failed = new ProxyEvidence();
        failed.success = false;

        assertThat(ProxyFingerprintPlanner.plan(profile, null).status).isEqualTo("blocked_proxy_evidence");
        assertThat(ProxyFingerprintPlanner.plan(profile, failed).status).isEqualTo("blocked_proxy_evidence");
    }

    @Test
    void leakFlagsRequireAttentionAndSkippedChecksAreDistinct() {
        SdkProfile profile = new SdkProfile("profile", "Profile");
        ProxyEvidence leak = success("US", "203.0.113.5");
        leak.webRtcLeak = true;
        leak.dnsLeak = true;
        ProxyEvidence skipped = new ProxyEvidence();
        skipped.skipped = true;
        skipped.skipReason = "disabled by operator";

        assertThat(ProxyFingerprintPlanner.plan(profile, leak).status).isEqualTo("needs_attention");
        assertThat(ProxyFingerprintPlanner.plan(profile, leak).warnings).contains("DNS leak detected");
        assertThat(ProxyFingerprintPlanner.plan(profile, skipped).status).isEqualTo("proxy_check_skipped");
    }

    @Test
    void applyUpdatesFingerprintWithoutProxyCredentials() {
        SdkProfile profile = new SdkProfile("proxy-de", "Proxy DE");
        ProxyEvidence evidence = success("DE", "203.0.113.9");

        ProxyFingerprintPlanner.apply(profile, evidence);

        assertThat(profile.fingerprint.locale).isEqualTo("de-DE");
        assertThat(profile.fingerprint.languages).contains("de-DE", "de");
        assertThat(profile.fingerprint.webRtc.publicIp).isEqualTo("203.0.113.9");
        assertThat(profile.proxy).isNull();
    }

    @Test
    void nullProfileIsRejectedBeforeAnythingElse() {
        org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException.class,
                () -> ProxyFingerprintPlanner.plan(null, success("BR", "203.0.113.1")));
    }

    @Test
    void everyLeakFlagSurfacesItsOwnWarning() {
        SdkProfile profile = new SdkProfile("profile", "Profile");

        ProxyEvidence ipv6 = success("US", "203.0.113.5");
        ipv6.ipv6Leak = true;
        ProxyFingerprintPlan planoIpv6 = ProxyFingerprintPlanner.plan(profile, ipv6);
        assertThat(planoIpv6.warnings).contains("IPv6 leak detected");

        ProxyEvidence header = success("US", "203.0.113.5");
        header.headerLeak = true;
        assertThat(ProxyFingerprintPlanner.plan(profile, header).warnings)
                .contains("Header leak detected");

        ProxyEvidence localidade = success("US", "203.0.113.5");
        localidade.localityMismatch = true;
        assertThat(ProxyFingerprintPlanner.plan(profile, localidade).warnings)
                .contains("Proxy locality mismatch detected");
    }

    @Test
    void evidenceTimezoneWinsAndBlankTimezoneFallsBackToTheCountryDefault() {
        SdkProfile profile = new SdkProfile("profile", "Profile");

        ProxyEvidence comFuso = success("US", "203.0.113.5");
        comFuso.timezoneId = "America/Chicago";
        ProxyFingerprintPlan planoComFuso = ProxyFingerprintPlanner.plan(profile, comFuso);
        assertThat(planoComFuso.patches).containsEntry("timezoneId", "America/Chicago");

        ProxyEvidence semFuso = success("US", "203.0.113.5");
        semFuso.timezoneId = "  ";
        assertThat(ProxyFingerprintPlanner.plan(profile, semFuso).patches)
                .containsEntry("timezoneId", "America/New_York");
    }

    @Test
    void geoPatchesOnlyWhenBothCoordinatesArePresent() {
        SdkProfile profile = new SdkProfile("profile", "Profile");

        ProxyEvidence sozinha = success("US", "203.0.113.5");
        sozinha.latitude = 40.7;
        assertThat(ProxyFingerprintPlanner.plan(profile, sozinha).patches)
                .doesNotContainKey("geolocation.latitude");

        ProxyEvidence par = success("US", "203.0.113.5");
        par.latitude = 40.7;
        par.longitude = -74.0;
        ProxyFingerprintPlan plano = ProxyFingerprintPlanner.plan(profile, par);
        assertThat(plano.patches)
                .containsEntry("geolocation.latitude", 40.7)
                .containsEntry("geolocation.longitude", -74.0)
                .containsEntry("geolocation.accuracy", 100.0);
    }

    @Test
    void provenanceAndAuditFieldsFlowIntoThePlanPatches() {
        SdkProfile profile = new SdkProfile("profile", "Profile");
        ProxyEvidence evidence = success("BR", "198.51.100.10");
        evidence.source = "probe-http";
        evidence.checkedBy = "sdk";
        evidence.checkedAt = "2026-10-19T12:00:00Z";
        evidence.provenance = null;

        ProxyFingerprintPlan plan = ProxyFingerprintPlanner.plan(profile, evidence);

        assertThat(plan.patches)
                .containsEntry("proxyEvidence.source", "probe-http")
                .containsEntry("proxyEvidence.checkedBy", "sdk")
                .containsEntry("proxyEvidence.checkedAt", "2026-10-19T12:00:00Z")
                .containsEntry("proxyEvidence.provenance", "");
    }

    @Test
    void unsupportedCountryFailsClosed() {
        SdkProfile profile = new SdkProfile("profile", "Profile");
        ProxyEvidence evidence = success("ZZ", "203.0.113.5");

        org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException.class,
                () -> ProxyFingerprintPlanner.plan(profile, evidence));
    }

    @Test
    void blockedOrSkippedPlansLeaveTheProfileUntouched() {
        SdkProfile bloqueado = new SdkProfile("proxy-bl", "Bloqueado");
        String localeOriginal = bloqueado.fingerprint.locale;

        ProxyEvidence falha = new ProxyEvidence();
        falha.success = false;
        assertThat(ProxyFingerprintPlanner.apply(bloqueado, falha)).isSameAs(bloqueado);
        assertThat(bloqueado.fingerprint.locale).isEqualTo(localeOriginal);

        SdkProfile pulado = new SdkProfile("proxy-sk", "Pulado");
        ProxyEvidence skip = new ProxyEvidence();
        skip.skipped = true;
        assertThat(ProxyFingerprintPlanner.apply(pulado, skip)).isSameAs(pulado);
    }

    @Test
    void applyOverwritesGeoToOverrideAndTogglesUdpOnlyOnLeak() {
        SdkProfile comLeak = new SdkProfile("proxy-geo1", "Geo Leak");
        ProxyEvidence leak = success("US", "203.0.113.5");
        leak.latitude = 40.7;
        leak.longitude = -74.0;
        leak.webRtcLeak = true;
        ProxyFingerprintPlanner.apply(comLeak, leak);

        assertThat(comLeak.fingerprint.geolocation.mode).isEqualTo("override");
        assertThat(comLeak.fingerprint.geolocation.latitude).isEqualTo(40.7);
        assertThat(comLeak.fingerprint.geolocation.longitude).isEqualTo(-74.0);
        assertThat(comLeak.fingerprint.geolocation.accuracy).isEqualTo(100.0);
        assertThat(comLeak.fingerprint.webRtc.mode).isEqualTo("altered");
        assertThat(comLeak.fingerprint.webRtc.protectLocalIps).isTrue();
        assertThat(comLeak.fingerprint.webRtc.disableUdp).isTrue();

        SdkProfile semLeak = new SdkProfile("proxy-geo2", "Geo Limpo");
        ProxyEvidence limpo = success("US", "203.0.113.6");
        limpo.latitude = 40.7;
        limpo.longitude = -74.0;
        ProxyFingerprintPlanner.apply(semLeak, limpo);

        assertThat(semLeak.fingerprint.webRtc.disableUdp).isFalse();
    }

    private static ProxyEvidence success(String country, String ip) {
        ProxyEvidence evidence = new ProxyEvidence();
        evidence.success = true;
        evidence.countryCode = country;
        evidence.ip = ip;
        evidence.provenance = "live_check";
        return evidence;
    }
}
