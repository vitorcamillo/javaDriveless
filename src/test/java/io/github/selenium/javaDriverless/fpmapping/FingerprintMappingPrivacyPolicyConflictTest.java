package io.github.selenium.javaDriverless.fpmapping;

import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

class FingerprintMappingPrivacyPolicyConflictTest {
    @Test
    void reportsProvenIpheyWebRtcPrivacyConflictWithoutWeakeningUdpProtection() {
        Map<String, Object> result = validate(ipheyBody(""), protectedWebRtc(), completeDomEvidence());

        assertEquals("privacy-policy-conflict", result.get("status"));
        assertEquals(List.of("iphey-unreliable", "iphey-more-login"), forbiddenHits(result));
    }

    @Test
    void keepsPrivacyConflictClassificationAcrossNormalGeoIpDatabaseVariation() {
        String body = ipheyBody("").replace(
                "Geolocation\n-23.1857,-46.8978",
                "Geolocation\n-23.0000,-46.6000");

        Map<String, Object> result = validate(body, protectedWebRtc(), completeDomEvidence());

        assertEquals("privacy-policy-conflict", result.get("status"), result.toString());
    }

    @Test
    void keepsPrivacyConflictWhenIpheySplitsTheUnreliableVerdictAcrossLines() {
        String body = ipheyBody("").replace(
                "Your Digital Identity Looks Unreliable",
                "Your Digital Identity Looks\nUnreliable");

        Map<String, Object> result = validate(body, protectedWebRtc(), completeDomEvidence());

        assertEquals("privacy-policy-conflict", result.get("status"), result.toString());
    }

    @Test
    void geoIpDifferenceBeyondFiftyKilometersRemainsARealMismatch() {
        String body = ipheyBody("").replace(
                "Geolocation\n-23.1857,-46.8978",
                "Geolocation\n-24.1857,-47.8978");

        Map<String, Object> result = validate(body, protectedWebRtc(), completeDomEvidence());

        assertEquals("forbidden-signal", result.get("status"), result.toString());
    }

    @Test
    void keepsAdditionalIpheyDetectionAsForbiddenSignal() {
        String additionalDetection = "Detected the following processes on your device: Dolphin Anty";
        Map<String, Object> result = validate(
                ipheyBody(additionalDetection), protectedWebRtc(), completeDomEvidence());

        assertEquals("forbidden-signal", result.get("status"));
    }

    @Test
    void doesNotClaimConflictWhenUdpProtectionIsDisabled() {
        Map<String, String> realWebRtc = new LinkedHashMap<>(FingerprintMappingIpheyFixtures.expected());
        realWebRtc.put("webRtc.mode", "real");
        realWebRtc.put("webRtc.disableUdp", "false");

        Map<String, Object> result = validate(ipheyBody(""), realWebRtc, completeDomEvidence());

        assertEquals("forbidden-signal", result.get("status"));
    }

    @Test
    void doesNotClaimConflictWithoutExactWebRtcNullObservation() {
        String body = ipheyBody("").replace("WebRTC IP\nnull", "WebRTC IP\n203.0.113.10");

        Map<String, Object> result = validate(body, protectedWebRtc(), completeDomEvidence());

        assertEquals("forbidden-signal", result.get("status"));
    }

    @Test
    void missingContractEvidenceStillFailsAsContractBroken() {
        Map<String, Object> incomplete = new LinkedHashMap<>(completeDomEvidence());
        incomplete.remove("iphey-bot-webdriver");

        Map<String, Object> result = validate(ipheyBody(""), protectedWebRtc(), incomplete);

        assertEquals("contract-broken", result.get("status"));
    }

    private static Map<String, Object> validate(String body, Map<String, String> expected,
                                                 Map<String, Object> domEvidence) {
        return FingerprintMappingValidation.validate(
                "iphey", "Iphey", "https://iphey.com/", body, body, "", expected, domEvidence);
    }

    private static Map<String, String> protectedWebRtc() {
        Map<String, String> expected = new LinkedHashMap<>(FingerprintMappingIpheyFixtures.expected());
        expected.put("webRtc.mode", "altered");
        expected.put("webRtc.disableUdp", "true");
        return expected;
    }

    @SuppressWarnings("unchecked")
    private static List<String> forbiddenHits(Map<String, Object> result) {
        return (List<String>) ((Map<String, Object>) result.get("forbidden")).get("hits");
    }

    private static Map<String, Object> completeDomEvidence() {
        return FingerprintMappingIpheyFixtures.domEvidence();
    }

    private static String ipheyBody(String additionalDetection) {
        return FingerprintMappingIpheyFixtures.detailedBody(
                        "America/Sao_Paulo", FingerprintMappingIpheyFixtures.GPU,
                        FingerprintMappingIpheyFixtures.USER_AGENT, "null")
                .replace("Your Digital Identity Looks Trustworthy",
                        "Your Digital Identity Looks Unreliable")
                + "\nDetected anti-detect browser environment (more-login)\n"
                + additionalDetection;
    }
}
