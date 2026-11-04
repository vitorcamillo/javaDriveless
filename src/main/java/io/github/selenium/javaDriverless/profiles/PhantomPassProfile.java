package io.github.selenium.javaDriverless.profiles;

import io.github.selenium.javaDriverless.types.BrowserSessionProfile;
import io.github.selenium.javaDriverless.types.EgressInfo;
import io.github.selenium.javaDriverless.types.EgressResolver;
import io.github.selenium.javaDriverless.types.ProxyConfig;

import java.util.HashMap;

/**
 * Complete, consumer-owned PhantomPass launch contract.
 *
 * <p>The nested {@link #profile} controls browser, fingerprint, storage, extensions and resource
 * policy. The remaining fields complete the session with authenticated proxy, location, DNS,
 * permissions and egress coherence without persisting proxy credentials in {@link SdkProfile}.</p>
 */
public class PhantomPassProfile {
    public SdkProfilePreset runtimeBase;
    public SdkProfile profile;
    public ProxyConfig proxy;
    public BrowserSessionProfile.Location location = new BrowserSessionProfile.Location();
    public BrowserSessionProfile.Dns dns = new BrowserSessionProfile.Dns();
    public BrowserSessionProfile.Permissions permissions = new BrowserSessionProfile.Permissions();
    public EgressInfo egressInfo;
    public EgressResolver egressResolver;

    public PhantomPassProfile applyDefaults() {
        if (profile == null) {
            throw new IllegalArgumentException("PhantomPassProfile.profile não pode ser nulo");
        }
        if (runtimeBase == null || !runtimeBase.isRuntimeBase()) {
            throw new IllegalArgumentException(
                    "PhantomPassProfile.runtimeBase deve ser windows, linux ou docker");
        }
        profile.applyDefaults();
        if (location == null) {
            location = new BrowserSessionProfile.Location();
        }
        if (dns == null) {
            dns = new BrowserSessionProfile.Dns();
        }
        if (permissions == null) {
            permissions = new BrowserSessionProfile.Permissions();
        }
        return this;
    }

    /** Returns an independent contract for policy evaluation or adapter assembly. */
    public PhantomPassProfile copy() {
        PhantomPassProfile copy = new PhantomPassProfile();
        copy.runtimeBase = runtimeBase;
        copy.profile = profile == null ? null : profile.copy();
        copy.proxy = proxy == null ? null : proxy.copy();
        copy.location.countryCode = location == null ? null : location.countryCode;
        copy.location.region = location == null ? null : location.region;
        copy.location.city = location == null ? null : location.city;
        copy.dns.policy = dns == null ? null : dns.policy;
        copy.dns.dohTemplate = dns == null ? null : dns.dohTemplate;
        copy.permissions.geolocation = permissions == null ? null : permissions.geolocation;
        copy.permissions.notifications = permissions == null ? null : permissions.notifications;
        copy.permissions.extra = permissions == null || permissions.extra == null
                ? new HashMap<>() : new HashMap<>(permissions.extra);
        copy.egressInfo = egressInfo == null ? null : egressInfo.copy();
        copy.egressResolver = egressResolver;
        return copy;
    }
}
