package io.github.selenium.javaDriverless.profiles;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public class ProxyFingerprintPlan {
    public String status = "ready";
    public Map<String, Object> patches = new LinkedHashMap<>();
    public List<String> warnings = new ArrayList<>();
    public List<String> errors = new ArrayList<>();

    public boolean isReady() {
        return "ready".equals(status);
    }

    void block(String error) {
        status = "blocked_proxy_evidence";
        errors.add(error);
    }

    void needsAttention(String warning) {
        if (!"blocked_proxy_evidence".equals(status)) {
            status = "needs_attention";
        }
        warnings.add(warning);
    }

    void skipped(String warning) {
        if (!"blocked_proxy_evidence".equals(status)) {
            status = "proxy_check_skipped";
        }
        warnings.add(warning);
    }
}
