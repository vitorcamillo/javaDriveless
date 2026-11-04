package io.github.selenium.javaDriverless.profiles;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public class SdkProfileMetadata {
    public List<String> tags = new ArrayList<>();
    public String folder = "";
    public String status = "active";
    public String notes = "";
    public String mainWebsite = "";
    public boolean pinned = false;
    public boolean locked = false;
    /** Whether this template is recommended for normal production sessions. */
    public boolean recommended = true;
    /** Machine-readable guarantee label, e.g. wrapper-free or compatibility-only. */
    public String securityGuarantee = "custom";
    public String createdAt = Instant.now().toString();
    public String updatedAt = createdAt;
    public String deletedAt;
    public Map<String, Long> seeds = new LinkedHashMap<>();

    public SdkProfileMetadata applyDefaults() {
        if (tags == null) {
            tags = new ArrayList<>();
        }
        if (folder == null) {
            folder = "";
        }
        if (status == null || status.isBlank()) {
            status = "active";
        }
        if (notes == null) {
            notes = "";
        }
        if (mainWebsite == null) {
            mainWebsite = "";
        }
        if (securityGuarantee == null || securityGuarantee.isBlank()) {
            securityGuarantee = "custom";
        }
        if (createdAt == null || createdAt.isBlank()) {
            createdAt = Instant.now().toString();
        }
        if (updatedAt == null || updatedAt.isBlank()) {
            updatedAt = createdAt;
        }
        if (seeds == null) {
            seeds = new LinkedHashMap<>();
        }
        return this;
    }
}
