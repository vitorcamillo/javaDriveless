package io.github.selenium.driverless;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;

abstract class StealthFingerprintRealSiteChecks extends StealthFingerprintConsistencyChecks {
    @Test @Order(70)
    @DisplayName("[REAL] SannySoft: contrato estrutural completo")
    void sannysoftContract() {
        assertExternalContract("sannysoft", "https://bot.sannysoft.com", 5.0);
    }

    @Test @Order(71)
    @DisplayName("[REAL] NowSecure: origem, título, challenge e conteúdo")
    void nowSecureContract() {
        assertExternalContract("nowsecure", "https://nowsecure.nl", 5.0);
    }
}
