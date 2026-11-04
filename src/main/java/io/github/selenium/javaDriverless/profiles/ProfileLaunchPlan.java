package io.github.selenium.javaDriverless.profiles;

import io.github.selenium.javaDriverless.types.BrowserFingerprintProfile;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public class ProfileLaunchPlan {
    public String profileId;
    public String status = "ready";
    public boolean mutated = false;
    public BrowserFingerprintProfile normalizedFingerprint;
    public ProxyFingerprintPlan proxyPlan;
    public Map<String, Object> optionsPlan = new LinkedHashMap<>();
    public List<String> runtimeSurfaces = new ArrayList<>();
    public List<String> warnings = new ArrayList<>();
    public List<String> errors = new ArrayList<>();

    public boolean isReady() {
        return "ready".equals(status);
    }

    void error(String value) {
        status = "blocked";
        errors.add(value);
    }

    void warning(String value) {
        if (!"blocked".equals(status)) {
            status = "needs_attention";
        }
        warnings.add(value);
    }
}
