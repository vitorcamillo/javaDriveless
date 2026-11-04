package io.github.selenium.javaDriverless.scripts;

import io.github.selenium.javaDriverless.types.BrowserFingerprintProfile;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class FingerprintRuntimePolicyTest {
    @Test
    void minimalCdpOmitsPageWrappersWhileLegacySpoofRemainsTheDefault() {
        BrowserFingerprintProfile minimal = new BrowserFingerprintProfile();
        minimal.runtimePolicy = BrowserFingerprintProfile.RUNTIME_POLICY_MINIMAL_CDP;

        String minimalScript = SeleniumProxySurfaceScripts.build(minimal.toJsonString());

        assertThat(minimalScript).isBlank();

        BrowserFingerprintProfile legacy = new BrowserFingerprintProfile();
        String legacyScript = SeleniumProxySurfaceScripts.build(legacy.toJsonString());

        assertThat(legacy.runtimePolicy)
                .isEqualTo(BrowserFingerprintProfile.RUNTIME_POLICY_LEGACY_SPOOF);
        assertThat(legacyScript)
                .contains("const profile =", "NAVIGATOR OVERRIDES", "SYSTEM COLOR NORMALIZATION");
    }

    @Test
    void minimalCdpOmitsWorkerWrappersWhileLegacySpoofStillInstallsThem() {
        BrowserFingerprintProfile minimal = new BrowserFingerprintProfile();
        minimal.runtimePolicy = BrowserFingerprintProfile.RUNTIME_POLICY_MINIMAL_CDP;

        String minimalScript = SeleniumProxyWorkerSurfaceScripts.build(minimal.toJsonString());
        String legacyScript = SeleniumProxyWorkerSurfaceScripts.build(
                new BrowserFingerprintProfile().toJsonString());

        assertThat(minimalScript).isBlank();
        assertThat(legacyScript).contains("WORKER NAVIGATOR", "WORKER USER-AGENT");
    }

    @Test
    void runtimePolicyNormalizesSupportedValuesAndRejectsUnknownOnes() {
        BrowserFingerprintProfile minimal = new BrowserFingerprintProfile();
        minimal.runtimePolicy = " MINIMAL-CDP ";

        minimal.applyDefaults();

        assertThat(minimal.runtimePolicy)
                .isEqualTo(BrowserFingerprintProfile.RUNTIME_POLICY_MINIMAL_CDP);

        BrowserFingerprintProfile missing = new BrowserFingerprintProfile();
        missing.runtimePolicy = null;

        missing.applyDefaults();

        assertThat(missing.runtimePolicy)
                .isEqualTo(BrowserFingerprintProfile.RUNTIME_POLICY_LEGACY_SPOOF);

        BrowserFingerprintProfile unknown = new BrowserFingerprintProfile();
        unknown.runtimePolicy = "experimental";

        assertThatThrownBy(unknown::applyDefaults)
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("runtimePolicy");
    }
}
