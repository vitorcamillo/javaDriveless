package io.github.selenium.javaDriverless.profiles;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.selenium.javaDriverless.types.BrowserFingerprintProfile;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Product-neutral saved browser profile definition for SDK consumers.
 */
public class SdkProfile {
    public static final int CURRENT_SCHEMA_VERSION = 1;
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    public int schemaVersion = CURRENT_SCHEMA_VERSION;
    public String id;
    public String name;
    /** Canonical template id; empty means a legacy/custom persisted profile. */
    public String preset = "";
    public BrowserSettings browser = new BrowserSettings();
    public BrowserFingerprintProfile fingerprint = BrowserFingerprintProfile.fromEnvironment();
    public SdkProfileProxy proxy;
    public StoragePolicy storage = new StoragePolicy();
    public List<String> extensionPaths = new ArrayList<>();
    public ResourcePolicy resourcePolicy = new ResourcePolicy();
    public List<String> runtimeSurfaces = new ArrayList<>();
    public SdkProfileMetadata metadata = new SdkProfileMetadata();

    public SdkProfile() {
    }

    public SdkProfile(String id, String name) {
        this.id = id;
        this.name = name;
    }

    public SdkProfile applyDefaults() {
        if (schemaVersion <= 0) {
            schemaVersion = CURRENT_SCHEMA_VERSION;
        }
        if (browser == null) {
            browser = new BrowserSettings();
        }
        if (fingerprint == null) {
            fingerprint = BrowserFingerprintProfile.fromEnvironment();
        }
        fingerprint.applyDefaults();
        if (storage == null) {
            storage = new StoragePolicy();
        }
        if (extensionPaths == null) {
            extensionPaths = new ArrayList<>();
        }
        if (resourcePolicy == null) {
            resourcePolicy = new ResourcePolicy();
        }
        if (runtimeSurfaces == null) {
            runtimeSurfaces = new ArrayList<>();
        }
        if (metadata == null) {
            metadata = new SdkProfileMetadata();
        }
        metadata.applyDefaults();
        return this;
    }

    /** @return a deep, independent copy suitable for a launch plan or consumer adapter */
    public SdkProfile copy() {
        try {
            return OBJECT_MAPPER.readValue(OBJECT_MAPPER.writeValueAsBytes(this), SdkProfile.class);
        } catch (Exception e) {
            throw new ProfileCatalogException("Failed to copy SDK profile", e);
        }
    }

    public static class BrowserSettings {
        public String channel = "chrome";
        public String binaryPath;
        public boolean headless = false;
        public String startupUrl;
        public String userAgent;
        public String language;
        public Integer windowWidth;
        public Integer windowHeight;
        /** Chrome flags adicionais aplicados após os defaults. Nulo/vazio = sem efeito. */
        public List<String> extraArgs = new ArrayList<>();
    }

    public static class StoragePolicy {
        public String userDataDir;
        public String downloadsDir;
        public boolean persistent = true;
        public boolean clearOnClose = false;
    }

    public static class ResourcePolicy {
        public List<String> blockedUrlPatterns = new ArrayList<>();
        public Map<String, Object> preferences = new LinkedHashMap<>();
    }
}
