package io.github.selenium.driverless;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;

abstract class StealthFingerprintNetworkLeakChecks extends StealthFingerprintDetectorSiteChecks {
    @Test @Order(100) @DisplayName("[LEAK] BrowserLeaks DNS: relatório resolvido")
    void browserleaksDns() { assertExternalContract("browserleaks-dns", "https://browserleaks.com/dns", 5.0); }

    @Test @Order(101) @DisplayName("[LEAK] BrowserLeaks WebRTC: resultado presente, sem falso NOT_FOUND")
    void browserleaksWebrtc() { assertExternalContract("browserleaks-webrtc", "https://browserleaks.com/webrtc", 20.0); }

    @Test @Order(102) @DisplayName("[LEAK] IPLeak: contrato oficial")
    void ipleakNet() { assertExternalContract("ipleak", "https://ipleak.net", 60.0); }

    @Test @Order(103) @DisplayName("[LEAK] BrowserLeaks TLS: relatório completo")
    void browserleaksTls() { assertExternalContract("browserleaks-tls", "https://browserleaks.com/tls", 15.0); }
}
