package io.github.selenium.driverless;

import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.TestMethodOrder;

/**
 * Suite DEFINITIVA de stealth fingerprint.
 *
 * Os checks ficam separados por responsabilidade nas bases package-private,
 * mas esta classe publica continua sendo a entrada unica da suite.
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@Tag("external")
public class StealthFingerprintSuiteTest extends StealthFingerprintMissingSiteChecks {
}
