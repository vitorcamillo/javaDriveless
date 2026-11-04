package io.github.selenium.driverless;

import io.github.selenium.javaDriverless.types.BrowserFingerprintProfile;
import io.github.selenium.javaDriverless.types.BrowserSessionProfile;
import io.github.selenium.javaDriverless.types.ChromeOptions;
import io.github.selenium.javaDriverless.types.ProxyConfig;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BrowserSessionProfileTest {
    @Test
    void completeSessionProfileAppliesProxyDnsAndFingerprintTogether() {
        BrowserSessionProfile session = new BrowserSessionProfile();
        session.proxy = new ProxyConfig("127.0.0.1", 8899, ProxyConfig.ProxyType.HTTP);
        session.fingerprint = BrowserFingerprintProfile.brLinux();
        session.location.countryCode = "US";
        session.dns.policy = "secure-doh";
        session.dns.dohTemplate = "https://cloudflare-dns.com/dns-query";
        session.permissions.geolocation = "deny";
        session.permissions.notifications = "denied";
        session.permissions.extra.put("camera", "denied");
        session.fingerprint.webRtc.protectLocalIps = false;
        session.fingerprint.webRtc.disableUdp = false;

        ChromeOptions options = new ChromeOptions();
        options.setSessionProfile(session);

        BrowserSessionProfile storedSession = options.getSessionProfile();
        assertNotSame(session, storedSession);
        assertNotSame(session.proxy, storedSession.proxy);
        assertNotSame(session.fingerprint, storedSession.fingerprint);
        BrowserFingerprintProfile appliedFingerprint = options.getFingerprintProfile();
        assertNotSame(session.fingerprint, appliedFingerprint);
        assertEquals(storedSession.fingerprint.toJsonString(), appliedFingerprint.toJsonString());
        assertEquals("http://127.0.0.1:8899/", options.getSingleProxy());
        assertTrue(options.getArguments().contains("--enable-features=DnsOverHttps"));
        assertTrue(options.getArguments().contains("--dns-over-https-mode=secure"));
        assertTrue(options.getArguments().contains("--lang=en-US"));
        assertTrue(options.getArguments().contains("--accept-lang=en-US,en"));
        assertEquals("en_US.UTF-8", options.getEnv().get("LANG"));
        assertEquals("America/New_York", storedSession.fingerprint.timezoneId);
        assertEquals("deny", storedSession.fingerprint.permissions.geolocation);
        assertEquals("denied", storedSession.fingerprint.permissions.notifications);
        assertEquals("prompt", storedSession.fingerprint.geolocation.mode);
        Map<?, ?> profilePrefs = (Map<?, ?>) options.getPrefs().get("profile");
        Map<?, ?> contentSettings = (Map<?, ?>) profilePrefs.get("default_content_setting_values");
        assertEquals(2, contentSettings.get("geolocation"));
        assertEquals(2, contentSettings.get("notifications"));
        assertEquals(2, contentSettings.get("media_stream_camera"));
        assertTrue(storedSession.fingerprint.webRtc.protectLocalIps);
        assertTrue(storedSession.fingerprint.webRtc.disableUdp);
        assertTrue(appliedFingerprint.webRtc.protectLocalIps);
        assertTrue(appliedFingerprint.webRtc.disableUdp);
        assertFalse(session.fingerprint.webRtc.protectLocalIps);
        assertFalse(session.fingerprint.webRtc.disableUdp);

        session.fingerprint.locale = "mutated-after-apply";
        assertNotEquals(session.fingerprint.locale, appliedFingerprint.locale);
    }

    @Test
    void defaultsFillEverySessionSectionForExternalCallers() {
        BrowserSessionProfile session = new BrowserSessionProfile();
        session.fingerprint = null;
        session.location = null;
        session.dns = null;
        session.permissions = null;

        session.applyDefaults();

        assertEquals("proxy-routed", session.dns.policy);
        assertEquals("", session.location.countryCode);
        assertEquals("profile", session.permissions.geolocation);
        assertEquals("default", session.permissions.notifications);
        assertTrue(session.fingerprint.hardwareConcurrency > 0);
    }
}
