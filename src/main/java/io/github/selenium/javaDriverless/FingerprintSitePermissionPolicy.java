package io.github.selenium.javaDriverless;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.List;
import java.util.Locale;

/** Minimal permission allowlist used by the fingerprint mapping tools. */
public final class FingerprintSitePermissionPolicy {
    private FingerprintSitePermissionPolicy() {
    }

    /**
     * Returns only the permissions strictly required to execute a site's contract.
     * Unknown sites deliberately receive no permissions.
     */
    public static List<String> requiredPermissions(String siteId) {
        return switch (siteId == null ? "" : siteId) {
            case "browserleaks-geo", "iphey" -> List.of("geolocation");
            case "printleaks" -> List.of("local-fonts");
            default -> List.of();
        };
    }

    /** Returns a validated HTTP(S) origin without credentials, path, query or fragment. */
    public static String originOf(String url) {
        URI parsed;
        try {
            parsed = new URI(url);
        } catch (URISyntaxException e) {
            throw new IllegalArgumentException("Invalid site URL", e);
        }
        String scheme = parsed.getScheme() == null ? "" : parsed.getScheme().toLowerCase(Locale.ROOT);
        if (!("http".equals(scheme) || "https".equals(scheme))
                || parsed.getHost() == null || parsed.getUserInfo() != null) {
            throw new IllegalArgumentException("Site permission origin must be credential-free HTTP(S)");
        }
        try {
            return new URI(scheme, null, parsed.getHost(), parsed.getPort(), null, null, null).toString();
        } catch (URISyntaxException e) {
            throw new IllegalArgumentException("Invalid site origin", e);
        }
    }
}
