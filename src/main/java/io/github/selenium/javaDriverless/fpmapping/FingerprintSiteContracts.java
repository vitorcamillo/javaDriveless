package io.github.selenium.javaDriverless.fpmapping;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

final class FingerprintSiteContracts {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Path DEFAULT_PATH = Path.of("docs", "FINGERPRINT_SITE_CONTRACTS.json");
    private static volatile Catalog cached;

    private FingerprintSiteContracts() {
    }

    static Site site(String id) {
        return loadDefault().sites.get(id);
    }

    private static Catalog loadDefault() {
        Catalog local = cached;
        if (local != null) {
            return local;
        }
        synchronized (FingerprintSiteContracts.class) {
            if (cached == null) {
                cached = read(DEFAULT_PATH);
            }
            return cached;
        }
    }

    private static Catalog read(Path path) {
        if (!Files.exists(path)) {
            return new Catalog();
        }
        try {
            Catalog catalog = JSON.readValue(path.toFile(), Catalog.class);
            return catalog == null ? new Catalog() : catalog;
        } catch (Exception e) {
            throw new IllegalStateException("Could not load fingerprint site contracts from " + path, e);
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    static class Catalog {
        public int version = 1;
        public Map<String, Site> sites = new LinkedHashMap<>();
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    static class Site {
        public List<String> allowedOrigins = new ArrayList<>();
        public List<String> titleTerms = new ArrayList<>();
        public List<RequiredDom> requiredDom = new ArrayList<>();
        public List<ExpectedValue> expectedValues = new ArrayList<>();
        public List<ForbiddenSignal> forbiddenSignals = new ArrayList<>();
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    static class RequiredDom {
        public String id = "";
        public List<String> terms = new ArrayList<>();
        public List<String> selectors = new ArrayList<>();
        public String matchPolicy = "terms";
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    static class ExpectedValue {
        public String key = "";
        public String expectedKey = "";
        public String policy = "contains";
        public Double tolerance;
        public Double distance;
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    static class ForbiddenSignal {
        public String id = "";
        public List<String> terms = new ArrayList<>();
    }
}
