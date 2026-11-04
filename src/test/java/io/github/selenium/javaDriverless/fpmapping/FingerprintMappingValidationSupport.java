package io.github.selenium.javaDriverless.fpmapping;

import java.util.List;
import java.util.Map;

/** Shared DOM evidence builders for fingerprint mapping contract tests. */
abstract class FingerprintMappingValidationSupport {
    static Map<String, String> browserLeaksDnsExpected(String publicIp) {
        return Map.of(
                "webRtc.publicIp", publicIp,
                "browserleaks-dns.testResultsPattern", "Found\\s+[1-9]\\d*\\s+Servers?",
                "browserleaks-dns.positiveCountPattern", "^[1-9]\\d*$",
                "browserleaks-dns.countConsistency", "matched");
    }

    static String browserLeaksDnsBody(String publicIp, int reportedCount, int observedCount) {
        StringBuilder rows = new StringBuilder();
        for (int i = 0; i < observedCount; i++) {
            rows.append(i == 0 ? "1.1.1.1" : "2606:4700:4700::1111")
                    .append("\tExample Resolver\tExample Country, Example City\n");
        }
        return """
                DNS Leak Test
                This test resolves randomly generated hostnames and reports the recursive DNS servers.
                Your IP Address
                IP Address\t%s
                ISP\tExample Proxy Network
                Location\tExample Country, Example City
                DNS Leak Test
                Test Results\tFound %d Servers, 1 ISP, 1 Location
                Your DNS Servers
                IP Address :\tISP :\tLocation :
                %s
                Leave a Comment
                BrowserLeaks © 2011-2026 All Rights Reserved
                report report report report report report report report report report report report
                """.formatted(publicIp, reportedCount, rows);
    }

    static Map<String, Object> matchedNode(String text) {
        return Map.of("matched", true, "tag", "td", "selector", "#value", "text", text);
    }

    static Map<String, Object> domEvidence(String id) {
        FingerprintSiteContracts.Site contract = FingerprintSiteContracts.site(id);
        Map<String, Object> evidence = new java.util.LinkedHashMap<>();
        for (FingerprintSiteContracts.RequiredDom field : contract.requiredDom) {
            String text = switch (field.matchPolicy == null ? "terms" : field.matchPolicy) {
                case "numeric" -> "1";
                case "hex32" -> "0123456789abcdef0123456789abcdef";
                case "hex8" -> "01234567";
                case "canvasHash" -> "CVS-E53FA041-BBC6";
                case "fontMetricsReport" -> "124 fonts and 83 unique metrics found";
                case "nonEmpty" -> "measured value";
                default -> field.terms.isEmpty() ? "measured value" : field.terms.get(0);
            };
            evidence.put(field.id, Map.of(
                    "matched", true,
                    "tag", "td",
                    "selector", "table tr > td",
                    "text", text
            ));
        }
        return evidence;
    }    static Map<String, String> creepExpectedProfile() {
        return Map.of(
                "creepjs.worker.gpuVendor", "Google Inc. (Mesa)",
                "creepjs.worker.gpuRenderer",
                "ANGLE (Mesa, llvmpipe (LLVM 20.1.2 256 bits), OpenGL 4.5)",
                "creepjs.canvas.renderingPattern", "^(?:0%\\s+rgba\\s+noise|none)$");
    }
}
