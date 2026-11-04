package io.github.selenium.driverless;

import io.github.selenium.javaDriverless.types.ChromeDnsPolicy;
import io.github.selenium.javaDriverless.types.ChromeOptions;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ChromeDnsPolicyTest {
    @Test
    void proxyRoutedDisablesLocalBrowserDnsPrefetch() {
        ChromeOptions options = new ChromeOptions();

        ChromeDnsPolicy.apply(options, "proxy-routed", null);

        assertTrue(options.getArguments().contains("--disable-dns-prefetch"));
        assertTrue(options.getArguments().contains("--disable-features=AsyncDns,TranslateUI,InterestCohort"));
        assertEquals("off", nested(options, "dns_over_https", "mode"));
        assertEquals(2, nested(options, "net", "network_prediction_options"));
    }

    @Test
    void secureDohAppliesExplicitResolverTemplate() {
        ChromeOptions options = new ChromeOptions();

        ChromeDnsPolicy.apply(options, "secure-doh", "https://cloudflare-dns.com/dns-query");

        assertTrue(options.getArguments().contains("--enable-features=DnsOverHttps"));
        assertTrue(options.getArguments().contains("--dns-over-https-mode=secure"));
        assertTrue(options.getArguments().contains(
                "--dns-over-https-templates=https://cloudflare-dns.com/dns-query"));
        assertEquals("secure", nested(options, "dns_over_https", "mode"));
        assertEquals("https://cloudflare-dns.com/dns-query",
                nested(options, "dns_over_https", "templates"));
    }

    @SuppressWarnings("unchecked")
    private static Object nested(ChromeOptions options, String parent, String child) {
        return ((java.util.Map<String, Object>) options.getPrefs().get(parent)).get(child);
    }
}
