package io.github.selenium.javaDriverless.fpmapping;

import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

abstract class FingerprintMappingFalsePositiveSupport {
    static Map<String, Object> validatePrintLeaks(String total, String succeeded, String errors) {
        String body = """
                PrintLeaks
                Browser Fingerprint Analysis Tool
                Collection complete
                %s
                TOTAL COLLECTORS
                %s
                SUCCEEDED
                %s
                ERRORS
                System Font Colors CanvasText audio audioinput audiooutput
                Canvas WebGL WebRTC Navigator Permissions Plugins Storage
                GPU hardware concurrency device memory
                report report report report report report report report report report report report
                """.formatted(total, succeeded, errors);
        return FingerprintMappingValidation.validate(
                "printleaks", "PrintLeaks", "https://www.printleaks.com/",
                body, body, "", Map.of(), domEvidence("printleaks"));
    }

    static Map<String, Object> domEvidence(String id) {
        FingerprintSiteContracts.Site contract = FingerprintSiteContracts.site(id);
        Map<String, Object> evidence = new LinkedHashMap<>();
        for (FingerprintSiteContracts.RequiredDom field : contract.requiredDom) {
            String text = field.terms.isEmpty() ? "measured value" : field.terms.get(0);
            evidence.put(field.id, Map.of(
                    "matched", true,
                    "tag", "td",
                    "selector", "table tr > td",
                    "text", text));
        }
        return evidence;
    }

    static Map<String, String> browserScanExpected(boolean disableUdp) {
        Map<String, String> expected = new LinkedHashMap<>();
        expected.put("webRtc.publicIp", "203.0.113.10");
        expected.put("browserMajor", "150");
        expected.put("uaPlatform", "Linux");
        expected.put("run.profileCountry", "BR");
        expected.put("browserscan.webrtc.Status", "SAFE");
        expected.put("browserscan.dns.Status", "SAFE");
        expected.put("webRtc.disableUdp", Boolean.toString(disableUdp));
        return expected;
    }

    static String browserScanUnknownBody() {
        return """
                Browser Fingerprint & Trust Score Analyzer
                Scan complete
                Risk assessment complete
                98
                A+
                STATUS
                Low Risk
                Powered by BrowserScan Trust Algorithm
                IDENTITY
                IP ADDRESS
                203.0.113.10
                ASN
                AS64500 Example Network
                LOCATION
                Example City, BR
                BROWSER
                Chrome 150
                OS
                Linux
                DEVICE
                Desktop
                RISK BOARD
                PROTOCOL FINGERPRINTS
                TLS JA3
                sanitized-ja3
                TLS VERSION
                TLSv1.3
                HTTP VERSION
                HTTP/2
                TCP OS GUESS
                Linux
                WEBRTC
                Status: UNKNOWN
                IP:
                Region:
                DNS
                Status: UNKNOWN
                Servers:
                CONSISTENCY MATRIX
                Timezone vs IP
                Language vs Locale
                HARDWARE SURFACE
                Canvas Hash
                WebGL Renderer
                SOFTWARE SURFACE
                Fonts Hash
                Timezone
                Languages
                filler filler filler filler filler filler filler filler filler filler filler filler
                """;
    }
}
