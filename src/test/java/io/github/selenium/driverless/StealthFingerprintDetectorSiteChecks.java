package io.github.selenium.driverless;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;

abstract class StealthFingerprintDetectorSiteChecks extends StealthFingerprintLocationChecks {
    @Test @Order(88) @DisplayName("[SITE] BrowserLeaks IP: contrato oficial")
    void browserleaksIp() { assertExternalContract("browserleaks-ip", "https://browserleaks.com/ip", 10.0); }

    @Test @Order(90) @DisplayName("[SITE] BrowserLeaks JavaScript: contrato oficial")
    void browserleaksJs() { assertExternalContract("browserleaks-javascript", "https://browserleaks.com/javascript", 10.0); }

    @Test @Order(91) @DisplayName("[SITE] BrowserLeaks Canvas: contrato oficial")
    void browserleaksCanvas() { assertExternalContract("browserleaks-canvas", "https://browserleaks.com/canvas", 10.0); }

    @Test @Order(92) @DisplayName("[SITE] BrowserLeaks WebGL: contrato e renderer presentes")
    void browserleaksWebgl() { assertExternalContract("browserleaks-webgl", "https://browserleaks.com/webgl", 10.0); }

    @Test @Order(95) @DisplayName("[SITE] PixelScan: veredito exato e contrato oficial")
    void pixelscanLoad() { assertPixelScanDifferentialContract(); }

    @Test @Order(96) @DisplayName("[SITE] Cover Your Tracks: relatório executado")
    void coverYourTracks() { assertExternalContract("coveryourtracks", "https://coveryourtracks.eff.org/", 3.0); }
}
