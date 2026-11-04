package io.github.selenium.javaDriverless.types;

import java.util.Locale;

public final class ChromeDnsPolicy {
    public static final String DEFAULT_DOH_TEMPLATE = "https://dns.google/dns-query";

    private ChromeDnsPolicy() {
    }

    public static void apply(ChromeOptions options, String policy, String dohTemplate) {
        String mode = normalize(policy);
        if ("off".equals(mode)) {
            return;
        }
        if ("secure-doh".equals(mode) || "explicit-doh".equals(mode)) {
            secureDoh(options, blankToDefault(dohTemplate));
            return;
        }
        proxyRouted(options);
    }

    public static void proxyRouted(ChromeOptions options) {
        options.addArgument("--disable-dns-prefetch");
        options.addArgument("--disable-features=AsyncDns,TranslateUI,InterestCohort");
        options.updatePref("net.network_prediction_options", 2);
        options.updatePref("dns_over_https.mode", "off");
    }

    public static void secureDoh(ChromeOptions options, String template) {
        String effectiveTemplate = blankToDefault(template);
        options.addArgument("--disable-dns-prefetch");
        options.addArgument("--enable-features=DnsOverHttps");
        options.addArgument("--dns-over-https-mode=secure");
        options.addArgument("--dns-over-https-templates=" + effectiveTemplate);
        options.updatePref("net.network_prediction_options", 2);
        options.updatePref("dns_over_https.mode", "secure");
        options.updatePref("dns_over_https.templates", effectiveTemplate);
    }

    private static String normalize(String policy) {
        if (policy == null || policy.isBlank()) {
            return "proxy-routed";
        }
        return policy.trim().toLowerCase(Locale.ROOT);
    }

    private static String blankToDefault(String value) {
        return value == null || value.isBlank() ? DEFAULT_DOH_TEMPLATE : value.trim();
    }
}
