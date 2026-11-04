package io.github.selenium.javaDriverless.types;

import java.util.HashMap;
import java.util.Map;

/**
 * Full browser session contract expected from PhantomPass.
 */
public class BrowserSessionProfile {
    public ProxyConfig proxy;
    public BrowserFingerprintProfile fingerprint = BrowserFingerprintProfile.fromEnvironment();
    public Location location = new Location();
    public Dns dns = new Dns();
    public Permissions permissions = new Permissions();

    public void applyDefaults() {
        if (fingerprint == null) fingerprint = BrowserFingerprintProfile.fromEnvironment();
        fingerprint.applyDefaults();
        if (location == null) location = new Location();
        if (dns == null) dns = new Dns();
        if (permissions == null) permissions = new Permissions();
    }

    /** @return copia profunda e independente do contrato de sessao */
    public BrowserSessionProfile copy() {
        BrowserSessionProfile copy = new BrowserSessionProfile();
        copy.proxy = proxy == null ? null : proxy.copy();
        copy.fingerprint = fingerprint == null ? null : fingerprint.copy();
        copy.location = copyLocation(location);
        copy.dns = copyDns(dns);
        copy.permissions = copyPermissions(permissions);
        return copy;
    }

    private static Location copyLocation(Location source) {
        if (source == null) return null;
        Location copy = new Location();
        copy.countryCode = source.countryCode;
        copy.region = source.region;
        copy.city = source.city;
        return copy;
    }

    private static Dns copyDns(Dns source) {
        if (source == null) return null;
        Dns copy = new Dns();
        copy.policy = source.policy;
        copy.dohTemplate = source.dohTemplate;
        return copy;
    }

    private static Permissions copyPermissions(Permissions source) {
        if (source == null) return null;
        Permissions copy = new Permissions();
        copy.geolocation = source.geolocation;
        copy.notifications = source.notifications;
        copy.extra = source.extra == null ? new HashMap<>() : new HashMap<>(source.extra);
        return copy;
    }

    public static class Location {
        public String countryCode = "";
        public String region = "";
        public String city = "";
    }

    public static class Dns {
        public String policy = "proxy-routed";
        public String dohTemplate = ChromeDnsPolicy.DEFAULT_DOH_TEMPLATE;
    }

    public static class Permissions {
        public String geolocation = "profile";
        public String notifications = "default";
        public Map<String, String> extra = new HashMap<>();
    }
}
