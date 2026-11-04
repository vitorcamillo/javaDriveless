package io.github.selenium.driverless;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;

abstract class StealthFingerprintMissingSiteChecks extends StealthFingerprintProfileChecks {
    @Test @Order(120) @DisplayName("[SITE] BrowserScan: nota ancorada e contrato oficial")
    void browserscanLoad() { assertExternalContract("browserscan", "https://browserscan.org/", 10.0); }

    @Test @Order(121) @DisplayName("[SITE] IPhey: #hero-status e relatório presentes")
    void ipheyLoad() { assertIpheyDifferentialContract(); }

    @Test @Order(122) @DisplayName("[SITE] TrustMyIP Timezone: teste acionado e resolvido")
    void trustmyipTimezoneLoad() { assertExternalContract("trustmyip-timezone", "https://trustmyip.com/timezone-dissonance", 5.0); }

    @Test @Order(123) @DisplayName("[SITE] TrustMyIP Proxy: resultado negativo prevalece sobre marketing")
    void trustmyipProxyLoad() { assertExternalContract("trustmyip-proxy", "https://trustmyip.com/proxy-checker", 15.0); }

    @Test @Order(124) @DisplayName("[SITE] TrustMyIP Canvas: saída real ancorada")
    void trustmyipCanvasLoad() { assertExternalContract("trustmyip-canvas", "https://trustmyip.com/canvas", 15.0); }

    @Test @Order(125) @DisplayName("[SITE] WhatsMyBrowser: contrato oficial")
    void whatsmybrowserLoad() { assertExternalContract("whatsmybrowser", "https://www.whatsmybrowser.org", 10.0); }

    @Test @Order(126) @DisplayName("[SITE] PrintLeaks: coletores concluídos sem erro")
    void printleaksLoad() { assertExternalContract("printleaks", "https://www.printleaks.com/", 3.0); }

    @Test @Order(119) @DisplayName("[SITE] BrowserCheck: mudança de conteúdo não vira PASS")
    void browsercheckLoad() { assertBrowserCheckDifferentialContract(); }

    @Test @Order(128) @DisplayName("[SITE] 000r: redirecionamento não vira PASS")
    void checkup000rLoad() { assertExternalContract("checkup-000r", "https://checkup.000r.ovh/", 15.0); }

    @Test @Order(129) @DisplayName("[SITE] AntiDetect Timezone: resultado resolvido")
    void antidetectTimezoneLoad() { assertExternalContract("antidetect-timezone", "https://antidetect.net/timezone-checker.html", 15.0); }
}
