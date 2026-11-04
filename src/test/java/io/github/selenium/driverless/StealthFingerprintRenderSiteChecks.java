package io.github.selenium.driverless;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;

abstract class StealthFingerprintRenderSiteChecks extends StealthFingerprintNetworkLeakChecks {
    @Test @Order(89) @DisplayName("[IDENTITY] CreepJS: relatório estrutural e zero detecção")
    void creepjsLoad() { assertExternalContract("creepjs", "https://abrahamjuliot.github.io/creepjs/", 38.0); }

    @Test @Order(110) @DisplayName("[GEO-SITE] BrowserLeaks Geo: resultado executado")
    void browserleaksGeo() { assertExternalContract("browserleaks-geo", "https://browserleaks.com/geo", 5.0); }

    @Test @Order(111) @DisplayName("[RENDER] BrowserLeaks Fonts: relatório completo")
    void browserleaksFonts() { assertExternalContract("browserleaks-fonts", "https://browserleaks.com/fonts", 30.0); }

    @Test @Order(112) @DisplayName("[RENDER] WebGL Report: contrato canônico")
    void webglReport() { assertExternalContract("webgl-report", "https://webglreport.com", 15.0); }

    @Test @Order(113) @DisplayName("[IDENTITY] BrowserLeaks Client Hints: relatório completo")
    void browserleaksClientHints() { assertExternalContract("browserleaks-client-hints", "https://browserleaks.com/client-hints", 10.0); }

    @Test @Order(114) @DisplayName("[IDENTITY] AmIUnique: relatório executado")
    void amiunique() { assertExternalContract("amiunique", "https://amiunique.org/fingerprint", 5.0); }
}
