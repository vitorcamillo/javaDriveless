package io.github.selenium.javaDriverless.scripts;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.selenium.javaDriverless.types.BrowserFingerprintProfile;

public final class SeleniumProxySurfaceScripts {
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    private SeleniumProxySurfaceScripts() {}

    public static String build(String profileJson) {
        if (!javascriptSurfaceWrappersEnabled(profileJson)) {
            return "";
        }
        return (SeleniumProxySurfaceBootstrapScripts.SCRIPT
                + SeleniumProxyNavigatorSurfaceScripts.SCRIPT
                + SeleniumProxyScreenSurfaceScripts.SCRIPT
                + SeleniumProxyRenderingSurfaceScripts.SCRIPT
                + SeleniumProxyWebRtcSurfaceScripts.SCRIPT
                + SeleniumProxyMediaSurfaceScripts.SCRIPT)
                .replace("__PROFILE_JSON__", profileJson);
    }

    static boolean javascriptSurfaceWrappersEnabled(String profileJson) {
        if (profileJson == null || profileJson.isBlank()) {
            return true;
        }
        try {
            JsonNode profile = OBJECT_MAPPER.readTree(profileJson);
            String policy = profile.path("runtimePolicy").asText("").trim();
            return !BrowserFingerprintProfile.RUNTIME_POLICY_MINIMAL_CDP.equalsIgnoreCase(policy);
        } catch (Exception ignored) {
            // Compatibilidade: antes da política, qualquer payload seguia pelo modo legacy.
            return true;
        }
    }
}
