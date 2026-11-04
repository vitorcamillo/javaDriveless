package io.github.selenium.javaDriverless;

import io.github.selenium.javaDriverless.types.ChromeOptions;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Cobre a allowlist de scheme e o fail-closed do proxy (evita DNS leak / launch direto). */
class ProxyArgumentConfiguratorTest {

    private static final Logger LOG = LoggerFactory.getLogger(ProxyArgumentConfiguratorTest.class);

    @Test
    void acceptsSafeSchemesAndSchemelessHostPort() {
        String[] safe = {
                "http://1.2.3.4:8080",
                "https://1.2.3.4:8080",
                "socks5://1.2.3.4:1080",
                "1.2.3.4:8080" // sem scheme → Chrome assume http
        };
        for (String proxy : safe) {
            ChromeOptions options = new ChromeOptions();
            ProxyArgumentConfigurator.configure(options, proxy, LOG);
            assertTrue(
                    options.getArguments().stream().anyMatch(a -> a.startsWith("--proxy-server=")),
                    "esperava --proxy-server para proxy seguro: " + proxy);
        }
    }

    @Test
    void rejectsDnsLeakingOrInvalidSchemes() {
        // socks4/socks resolvem DNS localmente (DNS leak); ssh não é proxy de Chrome.
        String[] unsafe = {
                "socks4://1.2.3.4:1080",
                "socks://1.2.3.4:1080",
                "ssh://1.2.3.4:22"
        };
        for (String proxy : unsafe) {
            ChromeOptions options = new ChromeOptions();
            assertThrows(RuntimeException.class,
                    () -> ProxyArgumentConfigurator.configure(options, proxy, LOG),
                    "esperava falha (fail-closed) para scheme inseguro: " + proxy);
        }
    }
}
