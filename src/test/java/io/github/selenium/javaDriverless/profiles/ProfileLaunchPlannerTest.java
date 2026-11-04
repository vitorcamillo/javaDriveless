package io.github.selenium.javaDriverless.profiles;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class ProfileLaunchPlannerTest {
    @TempDir
    Path tempDir;

    @Test
    void readyProfileProducesCompletePlanWithoutMutation() throws Exception {
        Path userDataDir = Files.createDirectory(tempDir.resolve("profile"));
        SdkProfile profile = SdkProfileGenerator.fromPreset(
                new SdkProfilePresetRequest("ready-launch", "Ready Launch", "BR", "linux"));
        profile.storage.userDataDir = userDataDir.toString();
        profile.browser.startupUrl = "https://example.com";

        ProfileLaunchPlan plan = ProfileLaunchPlanner.plan(profile);

        assertThat(plan.status).isEqualTo("ready");
        assertThat(plan.mutated).isFalse();
        assertThat(plan.profileId).isEqualTo("ready-launch");
        assertThat(plan.normalizedFingerprint).isNotNull();
        assertThat(plan.errors).isEmpty();
    }

    @Test
    void preservesGeneratedDiversityInNormalizedLaunchFingerprint() throws Exception {
        SdkProfilePresetRequest request = new SdkProfilePresetRequest(
                "diverse-launch", "Diverse Launch", "US", "windows");
        request.diversityEnabled = true;
        request.seed = 42L;
        SdkProfile profile = SdkProfileGenerator.fromLegacyPreset(request);
        profile.storage.userDataDir = Files.createDirectory(tempDir.resolve("diverse-profile")).toString();

        ProfileLaunchPlan plan = ProfileLaunchPlanner.plan(profile);

        assertThat(plan.status).isEqualTo("ready");
        assertThat(plan.normalizedFingerprint.canvas.mode).isEqualTo("noise");
        assertThat(plan.normalizedFingerprint.audio.mode).isEqualTo("noise");
        assertThat(plan.normalizedFingerprint.clientRects.mode).isEqualTo("noise");
        assertThat(plan.normalizedFingerprint.webgl.mode).isEqualTo("spoof_noise");
        assertThat(plan.normalizedFingerprint.canvas.noise).isEqualTo(profile.fingerprint.canvas.noise);
        assertThat(plan.normalizedFingerprint.audio.noise).isEqualTo(profile.fingerprint.audio.noise);
        assertThat(plan.normalizedFingerprint.clientRects.noise)
                .isEqualTo(profile.fingerprint.clientRects.noise);
        assertThat(plan.normalizedFingerprint.noiseSeed).isEqualTo(profile.fingerprint.noiseSeed);
    }

    @Test
    void staticConflictsAndLifecycleStateBlockBeforeChromeOpens() {
        SdkProfile profile = new SdkProfile("blocked", "Blocked");
        profile.metadata.locked = true;
        profile.browser.startupUrl = "notaurl";
        profile.fingerprint.uaPlatform = "Windows";
        profile.fingerprint.platform = "MacIntel";

        ProfileLaunchPlan plan = ProfileLaunchPlanner.plan(profile);

        assertThat(plan.status).isEqualTo("blocked");
        assertThat(plan.errors).contains("Profile is locked", "Startup URL is unresolved or unsupported");
        assertThat(plan.errors).anyMatch(error -> error.contains("uaPlatform=Windows"));
    }

    @Test
    void filesystemFactsProduceWarningsAndErrors() throws Exception {
        Path userDataDir = Files.createDirectory(tempDir.resolve("profile"));
        Files.createFile(userDataDir.resolve("SingletonLock"));
        Path downloadsFile = Files.createFile(tempDir.resolve("downloads-file"));
        SdkProfile profile = SdkProfileGenerator.fromPreset(
                new SdkProfilePresetRequest("facts", "Facts", "US", "windows"));
        profile.storage.userDataDir = userDataDir.toString();
        profile.storage.downloadsDir = downloadsFile.toString();
        profile.extensionPaths.add("../unsafe-extension");

        ProfileLaunchPlan plan = ProfileLaunchPlanner.plan(profile);

        assertThat(plan.status).isEqualTo("blocked");
        assertThat(plan.warnings).contains("Profile data dir contains stale Chrome lock files");
        assertThat(plan.errors).contains("Downloads path is not a directory", "Extension path is unsafe");
    }

    @Test
    void skippedProxyPlanIsVisibleWithoutBlocking() {
        SdkProfile profile = SdkProfileGenerator.fromPreset(
                new SdkProfilePresetRequest("proxy-skip", "Proxy Skip", "US", "windows"));
        ProxyEvidence evidence = new ProxyEvidence();
        evidence.skipped = true;
        evidence.skipReason = "operator";

        ProfileLaunchPlan plan = ProfileLaunchPlanner.plan(profile, evidence);

        assertThat(plan.status).isEqualTo("needs_attention");
        assertThat(plan.proxyPlan.status).isEqualTo("proxy_check_skipped");
        assertThat(plan.warnings).contains("Profile user data dir is not configured", "Proxy plan status: proxy_check_skipped");
    }
}
