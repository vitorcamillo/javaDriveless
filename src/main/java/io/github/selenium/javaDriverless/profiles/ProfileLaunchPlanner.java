package io.github.selenium.javaDriverless.profiles;

import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;

public final class ProfileLaunchPlanner {
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    private ProfileLaunchPlanner() {
    }

    public static ProfileLaunchPlan plan(SdkProfile profile) {
        return planInternal(profile, null, null);
    }

    public static ProfileLaunchPlan plan(SdkProfile profile, ProxyEvidence evidence) {
        return planInternal(profile, evidence, null);
    }

    static ProfileLaunchPlan planCatalogProfile(SdkProfile profile,
                                                ProxyEvidence evidence,
                                                SdkProfileCatalog catalog) {
        return planInternal(profile, evidence, catalog);
    }

    private static ProfileLaunchPlan planInternal(SdkProfile profile,
                                                  ProxyEvidence evidence,
                                                  SdkProfileCatalog catalog) {
        if (profile == null) {
            throw new IllegalArgumentException("Profile must not be null");
        }
        profile.applyDefaults();
        ProfileLaunchPlan plan = new ProfileLaunchPlan();
        plan.profileId = profile.id;
        plan.runtimeSurfaces.addAll(profile.runtimeSurfaces);
        plan.optionsPlan.put("browserChannel", profile.browser.channel);
        plan.optionsPlan.put("headless", profile.browser.headless);
        plan.optionsPlan.put("startupUrl", redactedIfConfigured(profile.browser.startupUrl));
        plan.optionsPlan.put("userDataDir", configuredMarker(profile.storage.userDataDir));
        plan.optionsPlan.put("downloadsDir", configuredMarker(profile.storage.downloadsDir));
        plan.optionsPlan.put("extensionCount", profile.extensionPaths.size());
        plan.proxyPlan = evidence == null ? null : ProxyFingerprintPlanner.plan(profile, evidence);
        if (plan.proxyPlan != null && "blocked_proxy_evidence".equals(plan.proxyPlan.status)) {
            plan.error("Proxy evidence blocks launch planning");
        } else if (plan.proxyPlan != null && !"ready".equals(plan.proxyPlan.status)) {
            plan.warning("Proxy plan status: " + plan.proxyPlan.status);
        }

        if (profile.metadata.locked) {
            plan.error("Profile is locked");
        }
        if ("deleted".equalsIgnoreCase(profile.metadata.status) || profile.metadata.deletedAt != null) {
            plan.error("Profile is deleted");
        }
        validateStartupUrl(profile, plan);
        validatePaths(profile, plan);
        if (catalog != null) {
            try {
                catalog.validateStorageOwnership(profile);
            } catch (ProfileCatalogException e) {
                plan.error(e.getMessage());
            }
        }
        validateExtensions(profile, plan);

        SdkProfile launchProfile = copy(profile);
        // The legacy validation path calls clearDiversity when diversityEnabled=false. Validate an
        // independent copy so a profile that already carries explicit/generated noise is not
        // silently downgraded before the launch plan reaches ChromeOptions.
        SdkProfileNormalizationResult normalization = SdkProfileNormalizer.normalize(
                copy(launchProfile), false, null);
        plan.normalizedFingerprint = launchProfile.fingerprint;
        if (!normalization.isReady()) {
            normalization.conflicts.forEach(plan::error);
        }
        return plan;
    }

    private static void validateStartupUrl(SdkProfile profile, ProfileLaunchPlan plan) {
        String url = profile.browser.startupUrl;
        if (url == null || url.isBlank()) {
            return;
        }
        String lower = url.toLowerCase(Locale.ROOT);
        if (!(lower.startsWith("http://") || lower.startsWith("https://") || lower.startsWith("about:"))) {
            plan.error("Startup URL is unresolved or unsupported");
        }
    }

    private static void validatePaths(SdkProfile profile, ProfileLaunchPlan plan) {
        if (profile.storage.userDataDir == null || profile.storage.userDataDir.isBlank()) {
            plan.warning("Profile user data dir is not configured");
        } else {
            Path userDataDir;
            try {
                userDataDir = SdkProfileCatalog.requireSafeStoragePath(
                        profile.id, "userDataDir", profile.storage.userDataDir);
            } catch (ProfileCatalogException e) {
                plan.error(e.getMessage());
                userDataDir = null;
            }
            if (userDataDir != null && !Files.exists(userDataDir, java.nio.file.LinkOption.NOFOLLOW_LINKS)) {
                plan.warning("Profile user data dir does not exist");
            }
            if (userDataDir != null && (Files.exists(userDataDir.resolve("SingletonLock"), java.nio.file.LinkOption.NOFOLLOW_LINKS)
                    || Files.exists(userDataDir.resolve("SingletonSocket"), java.nio.file.LinkOption.NOFOLLOW_LINKS)
                    || Files.exists(userDataDir.resolve("SingletonCookie"), java.nio.file.LinkOption.NOFOLLOW_LINKS))) {
                plan.warning("Profile data dir contains stale Chrome lock files");
            }
        }
        if (profile.storage.downloadsDir != null && !profile.storage.downloadsDir.isBlank()) {
            try {
                Path downloads = SdkProfileCatalog.requireSafeStoragePath(
                        profile.id, "downloadsDir", profile.storage.downloadsDir);
                if (Files.exists(downloads, java.nio.file.LinkOption.NOFOLLOW_LINKS)
                        && !Files.isDirectory(downloads, java.nio.file.LinkOption.NOFOLLOW_LINKS)) {
                    plan.error("Downloads path is not a directory");
                }
            } catch (ProfileCatalogException e) {
                plan.error(e.getMessage());
            }
        }
    }

    private static void validateExtensions(SdkProfile profile, ProfileLaunchPlan plan) {
        boolean isChromeLike = profile.browser.channel == null
                || profile.browser.channel.isBlank()
                || profile.browser.channel.toLowerCase(Locale.ROOT).contains("chrome");
        for (String extensionPath : profile.extensionPaths) {
            if (extensionPath == null || extensionPath.isBlank()) {
                plan.error("Extension path is blank");
                continue;
            }
            Path path = Path.of(extensionPath).toAbsolutePath().normalize();
            if (extensionPath.contains("..")) {
                plan.error("Extension path is unsafe");
            } else if (!Files.exists(path)) {
                plan.warning("Extension path does not exist");
            }
            if (!isChromeLike) {
                plan.warning("Extension browser compatibility should be verified for " + safe(profile.browser.channel));
            }
        }
    }

    private static String safe(String value) {
        return value == null ? "" : value;
    }

    private static String redactedIfConfigured(String value) {
        return value == null || value.isBlank() ? "" : "<redacted>";
    }

    private static String configuredMarker(String value) {
        return value == null || value.isBlank() ? "" : "<configured>";
    }

    private static SdkProfile copy(SdkProfile profile) {
        try {
            return OBJECT_MAPPER.readValue(OBJECT_MAPPER.writeValueAsString(profile), SdkProfile.class);
        } catch (IOException e) {
            throw new ProfileCatalogException("Failed to copy profile for launch planning", e);
        }
    }
}
