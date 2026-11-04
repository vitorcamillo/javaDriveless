package io.github.selenium.javaDriverless.fpmapping;

import io.github.selenium.javaDriverless.Chrome;
import io.github.selenium.javaDriverless.JavaDriverless;
import io.github.selenium.javaDriverless.types.ChromeOptions;
import org.junit.jupiter.api.Test;

import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class FingerprintMappingProxyTest {
    @Test
    void appliesDnsPolicyWithoutProxyWhenExplicitlyRequested() {
        ChromeOptions options = new ChromeOptions();

        FingerprintMappingProxy.applyStartupProxy(
                options, null, "secure-doh", "https://cloudflare-dns.com/dns-query");

        assertFalse(options.getArguments().stream().anyMatch(arg -> arg.startsWith("--proxy-server=")));
        assertTrue(options.getArguments().contains("--enable-features=DnsOverHttps"));
        assertEquals("secure", nested(options, "dns_over_https", "mode"));
    }

    @Test
    void fullProxyStringSetsProxyServerAndWebrtcHardening() {
        ChromeOptions options = new ChromeOptions();

        FingerprintMappingProxy.applyStartupProxy(
                options, "http://proxyuser:proxypass@10.0.0.1:3128", null, null);

        assertEquals("http://10.0.0.1:3128", options.getSingleProxy());
        assertTrue(options.getArguments().contains("--proxy-server=http://10.0.0.1:3128"));
        assertTrue(options.getArguments()
                .contains("--webrtc-ip-handling-policy=disable_non_proxied_udp"));
        assertEquals("altered", options.getFingerprintProfile().webRtc.mode);
        assertTrue(options.getFingerprintProfile().webRtc.protectLocalIps);
        assertTrue(options.getFingerprintProfile().webRtc.disableUdp);
    }

    @Test
    void proxyWithoutSchemeDefaultsToHttp() {
        ChromeOptions options = new ChromeOptions();

        FingerprintMappingProxy.applyStartupProxy(options, "10.0.0.2:8080", null, null);

        assertEquals("http://10.0.0.2:8080", options.getSingleProxy());
    }

    @Test
    void proxyWithoutPortIsRejected() {
        ChromeOptions options = new ChromeOptions();

        assertThrows(IllegalArgumentException.class,
                () -> FingerprintMappingProxy.applyStartupProxy(options, "http://10.0.0.3", null, null));
        assertThrows(IllegalArgumentException.class,
                () -> FingerprintMappingProxy.applyStartupProxy(options, "sem host válido", null, null));
    }

    @Test
    void nullOrBlankProxyWithoutDnsPolicyDoesNothing() {
        ChromeOptions options = new ChromeOptions();

        FingerprintMappingProxy.applyStartupProxy(options, null, null, null);
        assertEquals(null, options.getSingleProxy());
        assertFalse(options.getArguments().stream().anyMatch(a -> a.startsWith("--proxy-server=")));

        FingerprintMappingProxy.applyStartupProxy(options, "  ", null, null);
        assertEquals(null, options.getSingleProxy());
    }

    @Test
    void applyAuthConfiguresProxyAndCredentialsOnChrome() {
        JavaDriverless driver = mock(JavaDriverless.class);
        Chrome chrome = mock(Chrome.class);
        when(driver.getChrome()).thenReturn(chrome);
        when(chrome.setProxy(eq("http://10.0.0.5:3128"), anyList()))
                .thenReturn(CompletableFuture.completedFuture(null));
        when(chrome.setAuth(eq("user1"), eq("pass1"), eq("http://10.0.0.5:3128")))
                .thenReturn(CompletableFuture.completedFuture(null));

        // placeholders locais, não credenciais reais
        FingerprintMappingProxy.applyAuth(driver, "http://user1:pass1@10.0.0.5:3128");

        verify(chrome).setProxy(eq("http://10.0.0.5:3128"), anyList());
        verify(chrome).setAuth("user1", "pass1", "http://10.0.0.5:3128");
    }

    @Test
    void applyAuthWithoutCredentialsSkipsChromeEntirely() {
        JavaDriverless driver = mock(JavaDriverless.class);
        Chrome chrome = mock(Chrome.class);
        when(driver.getChrome()).thenReturn(chrome);

        FingerprintMappingProxy.applyAuth(driver, "http://10.0.0.6:3128");

        verify(chrome, never()).setProxy(anyString(), anyList());
        verify(chrome, never()).setAuth(anyString(), anyString(), anyString());
    }

    @Test
    void applyAuthWithNullProxyIsNoOp() {
        JavaDriverless driver = mock(JavaDriverless.class);

        FingerprintMappingProxy.applyAuth(driver, null);

        verify(driver, never()).getChrome();
    }

    @SuppressWarnings("unchecked")
    private static Object nested(ChromeOptions options, String parent, String child) {
        return ((java.util.Map<String, Object>) options.getPrefs().get(parent)).get(child);
    }
}
