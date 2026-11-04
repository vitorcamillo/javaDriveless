package io.github.selenium.javaDriverless.profiles;

import java.util.List;
import java.util.Locale;
import java.util.Map;

public final class ProxyFingerprintPlanner {
    private static final Map<String, CountryDefaults> COUNTRIES = Map.of(
            "BR", new CountryDefaults("pt-BR", List.of("pt-BR", "pt", "en-US", "en"), "America/Sao_Paulo"),
            "US", new CountryDefaults("en-US", List.of("en-US", "en"), "America/New_York"),
            "DE", new CountryDefaults("de-DE", List.of("de-DE", "de", "en"), "Europe/Berlin")
    );

    private ProxyFingerprintPlanner() {
    }

    public static ProxyFingerprintPlan plan(SdkProfile profile, ProxyEvidence evidence) {
        if (profile == null) {
            throw new IllegalArgumentException("Profile must not be null");
        }
        ProxyFingerprintPlan plan = new ProxyFingerprintPlan();
        if (evidence == null) {
            plan.block("Proxy evidence is required for proxy-aware coherence");
            return plan;
        }
        if (evidence.skipped) {
            plan.skipped("Proxy check skipped: " + nullSafe(evidence.skipReason));
            return plan;
        }
        if (!evidence.success) {
            plan.block("Proxy evidence failed or is missing success=true");
            return plan;
        }
        if (evidence.dnsLeak) {
            plan.needsAttention("DNS leak detected");
        }
        if (evidence.webRtcLeak) {
            plan.needsAttention("WebRTC leak detected");
        }
        if (evidence.ipv6Leak) {
            plan.needsAttention("IPv6 leak detected");
        }
        if (evidence.headerLeak) {
            plan.needsAttention("Header leak detected");
        }
        if (evidence.localityMismatch) {
            plan.needsAttention("Proxy locality mismatch detected");
        }

        CountryDefaults defaults = defaultsFor(evidence.countryCode);
        String timezone = evidence.timezoneId == null || evidence.timezoneId.isBlank()
                ? defaults.timezoneId : evidence.timezoneId;
        plan.patches.put("locale", defaults.locale);
        plan.patches.put("languages", defaults.languages);
        plan.patches.put("timezoneId", timezone);
        if (evidence.latitude != null && evidence.longitude != null) {
            plan.patches.put("geolocation.latitude", evidence.latitude);
            plan.patches.put("geolocation.longitude", evidence.longitude);
            plan.patches.put("geolocation.accuracy", 100.0);
        }
        if (evidence.ip != null && !evidence.ip.isBlank()) {
            plan.patches.put("webRtc.publicIp", evidence.ip);
            plan.patches.put("webRtc.protectLocalIps", true);
            plan.patches.put("webRtc.disableUdp", evidence.webRtcLeak);
        }
        plan.patches.put("proxyEvidence.provenance", nullSafe(evidence.provenance));
        plan.patches.put("proxyEvidence.source", nullSafe(evidence.source));
        plan.patches.put("proxyEvidence.checkedBy", nullSafe(evidence.checkedBy));
        plan.patches.put("proxyEvidence.checkedAt", nullSafe(evidence.checkedAt));
        return plan;
    }

    public static SdkProfile apply(SdkProfile profile, ProxyEvidence evidence) {
        ProxyFingerprintPlan plan = plan(profile, evidence);
        if ("blocked_proxy_evidence".equals(plan.status) || "proxy_check_skipped".equals(plan.status)) {
            return profile;
        }
        CountryDefaults defaults = defaultsFor(evidence.countryCode);
        profile.fingerprint.locale = defaults.locale;
        profile.fingerprint.languages = defaults.languages;
        profile.fingerprint.timezoneId = evidence.timezoneId == null || evidence.timezoneId.isBlank()
                ? defaults.timezoneId : evidence.timezoneId;
        if (evidence.latitude != null && evidence.longitude != null) {
            profile.fingerprint.geolocation.mode = "override";
            profile.fingerprint.geolocation.latitude = evidence.latitude;
            profile.fingerprint.geolocation.longitude = evidence.longitude;
            profile.fingerprint.geolocation.accuracy = 100.0;
        }
        if (evidence.ip != null && !evidence.ip.isBlank()) {
            profile.fingerprint.webRtc.mode = "altered";
            profile.fingerprint.webRtc.publicIp = evidence.ip;
            profile.fingerprint.webRtc.protectLocalIps = true;
            profile.fingerprint.webRtc.disableUdp = evidence.webRtcLeak;
        }
        return profile;
    }

    private static CountryDefaults defaultsFor(String countryCode) {
        String key = countryCode == null ? "" : countryCode.toUpperCase(Locale.ROOT);
        CountryDefaults defaults = COUNTRIES.get(key);
        if (defaults == null) {
            throw new IllegalArgumentException("Unsupported proxy evidence country: " + countryCode);
        }
        return defaults;
    }

    private static String nullSafe(String value) {
        return value == null ? "" : value;
    }

    private record CountryDefaults(String locale, List<String> languages, String timezoneId) {}
}
