package io.github.selenium.javaDriverless.fpmapping;

import io.github.selenium.javaDriverless.JavaDriverless;
import io.github.selenium.javaDriverless.types.ChromeOptions;
import io.github.selenium.javaDriverless.types.BrowserFingerprintProfile;
import io.github.selenium.javaDriverless.types.ChromeDnsPolicy;

import java.net.URI;
import java.util.List;

final class FingerprintMappingProxy {
    private FingerprintMappingProxy() {
    }

    static void applyStartupProxy(ChromeOptions options, String proxy) {
        applyStartupProxy(options, proxy, System.getenv("JAVA_DRIVERLESS_DNS_POLICY"),
                System.getenv("JAVA_DRIVERLESS_DOH_TEMPLATE"));
    }

    static void applyStartupProxy(ChromeOptions options, String proxy, String dnsPolicy, String dohTemplate) {
        ParsedProxy parsed = parse(proxy);
        if (parsed == null && dnsPolicy != null && !dnsPolicy.isBlank()) {
            ChromeDnsPolicy.apply(options, dnsPolicy, dohTemplate);
            return;
        }
        if (parsed != null) {
            options.setSingleProxy(parsed.proxyServer());
            options.addArgument("--proxy-server=" + parsed.proxyServer());
            options.addArgument("--webrtc-ip-handling-policy=disable_non_proxied_udp");
            options.updatePref("webrtc.ip_handling_policy", "disable_non_proxied_udp");
            options.updatePref("webrtc.multiple_routes_enabled", false);
            options.updatePref("webrtc.nonproxied_udp_enabled", false);
            ChromeDnsPolicy.apply(options, dnsPolicy, dohTemplate);
            BrowserFingerprintProfile profile = options.getFingerprintProfile();
            profile.webRtc.mode = "altered";
            profile.webRtc.protectLocalIps = true;
            profile.webRtc.disableUdp = true;
            options.setFingerprintProfile(profile);
        }
    }

    static void applyAuth(JavaDriverless driver, String proxy) {
        ParsedProxy parsed = parse(proxy);
        if (parsed != null && parsed.username() != null && parsed.password() != null) {
            driver.getChrome().setProxy(parsed.proxyServer(), List.of()).join();
            driver.getChrome().setAuth(parsed.username(), parsed.password(), parsed.proxyServer()).join();
        }
    }

    private static ParsedProxy parse(String proxy) {
        if (proxy == null || proxy.isBlank()) {
            return null;
        }
        URI uri = URI.create(proxy.contains("://") ? proxy : "http://" + proxy);
        String scheme = uri.getScheme() == null ? "http" : uri.getScheme();
        String host = uri.getHost();
        int port = uri.getPort();
        if (host == null || port < 1) {
            throw new IllegalArgumentException("Proxy must include host and port");
        }
        String username = null;
        String password = null;
        String userInfo = uri.getUserInfo();
        if (userInfo != null) {
            String[] parts = userInfo.split(":", 2);
            username = parts[0];
            password = parts.length > 1 ? parts[1] : "";
        }
        return new ParsedProxy(scheme, host, port, username, password);
    }

    private record ParsedProxy(String scheme, String host, int port, String username, String password) {
        String hostWithPort() {
            return host + ":" + port;
        }

        String proxyServer() {
            return scheme + "://" + hostWithPort();
        }
    }
}
