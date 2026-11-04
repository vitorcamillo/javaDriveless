package io.github.selenium.javaDriverless.fpmapping;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

/**
 * Matriz de decisão do status e dos blockers por site: cada regra de parse com
 * corpo/texto/página sintéticos — determinístico, sem browser.
 */
class FingerprintMappingStatusMatrixTest {

    private static String longBody(String marker) {
        return marker + " ".repeat(300);
    }

    private static List<String> blockers(String id, String body, String page, String text,
                                         Map<String, String> expected) {
        return FingerprintMappingStatus.blockersFor(id, body, page, text, expected);
    }

    // ---------- statusFor: ordem de prioridade ----------

    @Test
    void statusPriorityBrowserErrorBeatsEverything() {
        assertEquals("browser-error", FingerprintMappingStatus.statusFor(
            List.of("browser-or-access-error", "creepjs-lies-detected"),
            List.of("campo"), List.of(), List.of(), List.of()));
    }

    @Test
    void statusPriorityCaptureErrorThenContractBrokenByMissing() {
        assertEquals("capture-error", FingerprintMappingStatus.statusFor(
            List.of("body-too-short"), List.of("campo"), List.of(), List.of(), List.of()));
        assertEquals("contract-broken", FingerprintMappingStatus.statusFor(
            List.of(), List.of("campo"), List.of(), List.of(), List.of()));
    }

    @Test
    void unknownBlockerIsContractBroken() {
        assertEquals("contract-broken", FingerprintMappingStatus.statusFor(
            List.of("blocker-novo-desconhecido"), List.of(), List.of(), List.of(), List.of()));
    }

    @Test
    void detectionBlockersAndForbiddenHitsAreForbiddenSignal() {
        assertEquals("forbidden-signal", FingerprintMappingStatus.statusFor(
            List.of("creepjs-lies-detected"), List.of(), List.of(), List.of(), List.of()));
        assertEquals("forbidden-signal", FingerprintMappingStatus.statusFor(
            List.of(), List.of(), List.of(), List.of(), List.of("regex-proibido")));
    }

    @Test
    void expectedMissingAndValueMismatchComeBeforeUnresolved() {
        assertEquals("expected-missing", FingerprintMappingStatus.statusFor(
            List.of("ip-timezone-not-rendered"), List.of(), List.of("esperado"), List.of(),
            List.of()));
        assertEquals("value-mismatch", FingerprintMappingStatus.statusFor(
            List.of("browserscan-dns-unresolved"), List.of(), List.of(),
            List.of("webgl.renderer"), List.of()));
    }

    @Test
    void unresolvedOnlyWithMixOfUnresolvedAndPrivacyPolicyIsSiteUnresolved() {
        assertEquals("site-unresolved", FingerprintMappingStatus.statusFor(
            List.of("browserscan-dns-unresolved", "browserscan-webrtc-privacy-policy-conflict"),
            List.of(), List.of(), List.of(), List.of()));
    }

    @Test
    void unresolvedMixedWithDetectionIsForbiddenNotUnresolved() {
        assertEquals("forbidden-signal", FingerprintMappingStatus.statusFor(
            List.of("browserscan-dns-unresolved", "creepjs-detection-nonzero"),
            List.of(), List.of(), List.of(), List.of()));
    }

    @Test
    void privacyPolicyBlockerAloneStillPassesAndCleanInputPasses() {
        // privacy-policy conflita apenas com webrtc do browserscan: sozinho não reprova
        assertEquals("pass", FingerprintMappingStatus.statusFor(
            List.of("browserscan-webrtc-privacy-policy-conflict"),
            List.of(), List.of(), List.of(), List.of()));
        assertEquals("pass", FingerprintMappingStatus.statusFor(
            List.of(), List.of(), List.of(), List.of(), List.of()));
    }

    // ---------- blockersFor: regra por regra ----------

    @Test
    void shortBodyFlagsCaptureErrorUnlessNowsecureShortButComplete() {
        assertTrue(blockers("generic", "curto", "", "", null).contains("body-too-short"));
        assertTrue(blockers("creepjs", "curto", "", "", null).contains("body-too-short"));

        List<String> nowsecureOk = blockers("nowsecure", "curto", "",
            "nowsecure status nodriver ok", null);
        assertFalse(nowsecureOk.contains("body-too-short"), "" + nowsecureOk);

        List<String> nowsecureIncomplete = blockers("nowsecure", "curto", "", "nowsecure", null);
        assertTrue(nowsecureIncomplete.contains("body-too-short"));
    }

    @Test
    void accessErrorMarkersFlagBrowserOrAccessError() {
        assertTrue(blockers("x", longBody("ok"), "",
            "this site can't be reached", null).contains("browser-or-access-error"));
        assertTrue(blockers("x", longBody("ok"), "",
            "err_connection_refused", null).contains("browser-or-access-error"));
        assertTrue(blockers("x", longBody("ok"), "",
            "checking your browser before accessing", null).contains("browser-or-access-error"));
    }

    @Test
    void coverYourTracksStuckFlagsReportNotRendered() {
        List<String> stuck = blockers("coveryourtracks", longBody("ok"), "",
            "testing your browser takes a second", null);
        assertTrue(stuck.contains("report-not-rendered"));

        List<String> rendered = blockers("coveryourtracks", longBody("ok"), "",
            "your fingerprint is protected; tracking protection on", null);
        assertFalse(rendered.contains("report-not-rendered"));
    }

    @Test
    void checkup000rPlaceholderFlagsOnlyPlaceholderSections() {
        List<String> placeholders = blockers("checkup-000r", longBody("ok"), "",
            "start webrtc test only placeholders here", null);
        assertTrue(placeholders.contains("only-placeholder-sections-rendered"));

        List<String> complete = blockers("checkup-000r", longBody("ok"), "",
            "start webrtc test plus canvas fingerprint and webgl renderer detected", null);
        assertFalse(complete.contains("only-placeholder-sections-rendered"));
    }

    @Test
    void antidetectTimezoneNotRenderedFlagsUnresolved() {
        List<String> unresolved = blockers("antidetect-timezone", longBody("ok"), "",
            "unable to detect — unable to fetch ip location", null);
        assertTrue(unresolved.contains("ip-timezone-not-rendered"));
    }

    @Test
    void trustmyipTimezoneDistinguishesNotResolvedFromUnableToVerify() {
        List<String> notResolved = blockers("trustmyip-timezone", longBody("ok"), "",
            "temporal security analysis — current timezone detecting…", null);
        assertTrue(notResolved.contains("trustmyip-timezone-not-resolved"));

        List<String> unable = blockers("trustmyip-timezone", longBody("ok"), "",
            "temporal forensic audit log — dissonance result unable to verify", null);
        assertTrue(unable.contains("trustmyip-timezone-unable-to-verify"));
    }

    @Test
    void printLeaksCollectorSummaryDrivesTheRightBlocker() {
        String valid = "3\nTOTAL COLLECTORS\n3\nSUCCEEDED\n0\nERRORS\n" + "x".repeat(300);
        assertFalse(blockers("printleaks", valid, "", "", null)
            .contains("collector-summary-invalid"));

        String withErrors = "3\nTOTAL COLLECTORS\n2\nSUCCEEDED\n1\nERRORS\n" + "x".repeat(300);
        assertTrue(blockers("printleaks", withErrors, "", "", null).contains("collector-errors"));

        String malformed = "3\nTOTAL COLLECTORS\n\nSUCCEEDED\n0\nERRORS\n" + "x".repeat(300);
        assertTrue(blockers("printleaks", malformed, "", "", null)
            .contains("collector-summary-invalid"));

        String absent = "x".repeat(300);
        assertTrue(blockers("printleaks", absent, "", "", null)
            .contains("collector-summary-invalid"));

        // label na primeira linha: não há número antes -> summary inválido
        String labelFirst = "TOTAL COLLECTORS\n1\nSUCCEEDED\n0\nERRORS\n" + "x".repeat(300);
        assertTrue(blockers("printleaks", labelFirst, "", "", null)
            .contains("collector-summary-invalid"));
    }

    @Test
    void creepJsHeadlessStealthPercentagesOnlyFlagWhenNonZero() {
        assertFalse(blockers("creepjs", longBody("0% headless 0% stealth"), "", "", null)
            .contains("creepjs-detection-nonzero"));
        assertTrue(blockers("creepjs", longBody("73% stealth"), "", "", null)
            .contains("creepjs-detection-nonzero"));
    }

    @Test
    void creepJsLikeHeadlessRiskySignalsFlagPerPage() {
        assertTrue(blockers("creepjs", longBody("ok"),
            "<html> noChrome : true </html>", "", null)
            .contains("creepjs-like-headless-risk"));
        assertTrue(blockers("creepjs", longBody("ok"),
            "hasSwiftShader: true renderer", "", null)
            .contains("creepjs-like-headless-risk"));
        assertFalse(blockers("creepjs", longBody("ok"),
            "noChrome: false noPlugins: false", "", null)
            .contains("creepjs-like-headless-risk"));
    }

    @Test
    void creepJsCanvasRgbaNoiseFlagsOnlyNonZero() {
        assertFalse(blockers("creepjs", longBody("rendering: 0% rgba noise"), "", "", null)
            .contains("creepjs-canvas-rgba-noise"));
        assertTrue(blockers("creepjs", longBody("rendering: 5% rgba noise"), "", "", null)
            .contains("creepjs-canvas-rgba-noise"));
    }

    @Test
    void creepJsWebrtcUnsafeCandidateRespectsAllowedAndNeutralAddresses() {
        String candidateReal = longBody(
            "candidate:1 1 udp 100 203.0.113.9 1234 typ srflx");
        assertTrue(blockers("creepjs", candidateReal, "", "",
                Map.of("webRtc.publicIp", "198.51.100.7"))
            .contains("creepjs-webrtc-unsafe-candidate"));
        assertFalse(blockers("creepjs", candidateReal, "", "",
                Map.of("webRtc.publicIp", "203.0.113.9"))
            .contains("creepjs-webrtc-unsafe-candidate"));

        // IPv6 público com colchetes e zona: normaliza para o IP literal e conta como inseguro
        String ipv6Bracketed = longBody(
            "candidate:3 1 udp 100 [2001:db8::5%1] 5678 typ host");
        assertTrue(blockers("creepjs", ipv6Bracketed, "", "", null)
            .contains("creepjs-webrtc-unsafe-candidate"));

        // e é permitido quando está na allowlist (já normalizado)
        assertFalse(blockers("creepjs", ipv6Bracketed, "", "",
                Map.of("webRtc.allowedIps", "2001:db8::5"))
            .contains("creepjs-webrtc-unsafe-candidate"));

        String relay = longBody("a=candidate:9 1 udp 100 203.0.113.9 1234 typ relay");
        assertFalse(blockers("creepjs", relay, "", "", null)
            .contains("creepjs-webrtc-unsafe-candidate"));

        String local = longBody("candidate:2 1 udp 100 abc.local 1234 typ host");
        assertFalse(blockers("creepjs", local, "", "", null)
            .contains("creepjs-webrtc-unsafe-candidate"));
    }

    @Test
    void trustmyIpProxyNegativeVerdictsFlagDetection() {
        assertTrue(blockers("trustmyip-proxy", longBody("ok"), "", "vpn likely", null)
            .contains("trustmyip-proxy-negative-result"));
        assertTrue(blockers("trustmyip-proxy", longBody("ok"), "", "webrtc shows different ip", null)
            .contains("trustmyip-proxy-negative-result"));
        assertTrue(blockers("trustmyip-proxy", longBody("ok"), "", "leak! detected", null)
            .contains("trustmyip-proxy-negative-result"));
    }

    @Test
    void sannysoftRequiresRenderedVerdicts() {
        assertTrue(blockers("sannysoft", longBody("ok"), "", "sem veredito aqui", null)
            .contains("sannysoft-results-not-rendered"));
        assertFalse(blockers("sannysoft", longBody("ok"), "", "WebDriver passed", null)
            .contains("sannysoft-results-not-rendered"));
    }

    @Test
    void browserLeaksWebglNeedsRendererValueAfterLabel() {
        String missing = longBody("Unmasked Renderer\n?");
        assertTrue(blockers("browserleaks-webgl", missing, "", "", null)
            .contains("webgl-renderer-missing"));

        String present = longBody("Unmasked Renderer\nANGLE (Intel)");
        assertFalse(blockers("browserleaks-webgl", present, "", "", null)
            .contains("webgl-renderer-missing"));

        // label como última linha: sem valor seguinte -> missing
        String labelLast = "x".repeat(300) + "\nUnmasked Renderer";
        assertTrue(blockers("browserleaks-webgl", labelLast, "", "", null)
            .contains("webgl-renderer-missing"));
    }

    @Test
    void browserLeaksWebrtcRequiresARenderedResult() {
        assertTrue(blockers("browserleaks-webrtc", longBody("aguardando"), "", "", null)
            .contains("webrtc-result-not-rendered"));
        assertFalse(blockers("browserleaks-webrtc", longBody("no leak detected"), "", "", null)
            .contains("webrtc-result-not-rendered"));
        assertFalse(blockers("browserleaks-webrtc",
                longBody("public ip address\n203.0.113.9"), "", "", null)
            .contains("webrtc-result-not-rendered"));
    }

    @Test
    void browserScanUnsafeGradeIsFlaggedFromTrustAlgorithmMarker() {
        String unsafe = "C\nx\nMEDIUM RISK\nPowered by BrowserScan Trust Algorithm\n" + "x".repeat(300);
        assertTrue(blockers("browserscan", unsafe, "", "", null)
            .contains("browserscan-unsafe-grade"));

        String safe = "A+\nx\nLOW RISK\nPowered by BrowserScan Trust Algorithm\n" + "x".repeat(300);
        assertFalse(blockers("browserscan", safe, "", "", null)
            .contains("browserscan-unsafe-grade"));

        String noMarker = "x".repeat(300);
        assertTrue(blockers("browserscan", noMarker, "", "", null)
            .contains("browserscan-unsafe-grade"));
    }
}
