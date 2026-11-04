package io.github.selenium.javaDriverless.types;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Caracterizacao do contrato do builder de perfis de fingerprint:
 * atalhos de superficie, defaults aplicados no build() e validacoes.
 */
class BrowserFingerprintProfileBuilderContractTest {

    @Test
    void screenSetsSpoofModeAndDerivedAvailableArea() {
        BrowserFingerprintProfile profile = BrowserFingerprintProfile.builder()
                .screen(1920, 1080)
                .build();

        assertThat(profile.screen.mode).isEqualTo("spoof");
        assertThat(profile.screen.width).isEqualTo(1920);
        assertThat(profile.screen.height).isEqualTo(1080);
        assertThat(profile.screen.availWidth).isEqualTo(1920);
        assertThat(profile.screen.availHeight).isEqualTo(1040);
    }

    @Test
    void screenOverloadCarriesDepthAndDeviceScaleFactor() {
        BrowserFingerprintProfile profile = BrowserFingerprintProfile.builder()
                .screen(3840, 2160, 30, 2.0)
                .build();

        assertThat(profile.screen.colorDepth).isEqualTo(30);
        assertThat(profile.screen.pixelDepth).isEqualTo(30);
        assertThat(profile.screen.deviceScaleFactor).isEqualTo(2.0);
    }

    @Test
    void webglMirrorsMaskedValuesIntoUnmaskedFields() {
        BrowserFingerprintProfile profile = BrowserFingerprintProfile.builder()
                .webgl("spoof", "VendorX", "RendererY")
                .build();

        assertThat(profile.webgl.mode).isEqualTo("spoof");
        assertThat(profile.webgl.vendor).isEqualTo("VendorX");
        assertThat(profile.webgl.renderer).isEqualTo("RendererY");
        assertThat(profile.webgl.unmaskedVendor).isEqualTo("VendorX");
        assertThat(profile.webgl.unmaskedRenderer).isEqualTo("RendererY");
    }

    @Test
    void geolocationDefaultsToHundredMeterAccuracy() {
        BrowserFingerprintProfile defaultAccuracy = BrowserFingerprintProfile.builder()
                .geolocation(10.5, -20.25)
                .build();
        BrowserFingerprintProfile explicitAccuracy = BrowserFingerprintProfile.builder()
                .geolocation(10.5, -20.25, 7.5)
                .build();

        assertThat(defaultAccuracy.geolocation.mode).isEqualTo("override");
        assertThat(defaultAccuracy.geolocation.accuracy).isEqualTo(100.0);
        assertThat(explicitAccuracy.geolocation.accuracy).isEqualTo(7.5);
    }

    @Test
    void noiseShortcutsSelectNoiseMode() {
        BrowserFingerprintProfile profile = BrowserFingerprintProfile.builder()
                .audioNoise(1e-5)
                .canvasNoise(2e-5)
                .clientRectsNoise(3e-5)
                .build();

        assertThat(profile.audio.mode).isEqualTo("noise");
        assertThat(profile.audio.noise).isEqualTo(1e-5);
        assertThat(profile.canvas.mode).isEqualTo("noise");
        assertThat(profile.canvas.noise).isEqualTo(2e-5);
        assertThat(profile.clientRects.mode).isEqualTo("noise");
        assertThat(profile.clientRects.noise).isEqualTo(3e-5);
    }

    @Test
    void fontsShortcutsSelectBlockAndFilterModes() {
        BrowserFingerprintProfile blocked = BrowserFingerprintProfile.builder()
                .fontsBlock()
                .build();
        BrowserFingerprintProfile filtered = BrowserFingerprintProfile.builder()
                .fontsAllow("Arial", "Tahoma")
                .build();

        assertThat(blocked.fonts.mode).isEqualTo("block");
        assertThat(filtered.fonts.mode).isEqualTo("filter");
        assertThat(filtered.fonts.families).containsExactly("Arial", "Tahoma");
    }

    @Test
    void buildKeepsExplicitValuesAndAppliesDefaults() {
        BrowserFingerprintProfile profile = BrowserFingerprintProfile.builder()
                .name("custom-name")
                .colorScheme("DARK")
                .build();

        assertThat(profile.name).isEqualTo("custom-name");
        assertThat(profile.colorScheme).isEqualTo("dark");
        assertThat(profile.formFactors).containsExactly("Desktop");
        assertThat(profile.languages).containsExactly("en-US", "en");
        assertThat(profile.storage.mode).isEqualTo("spoof");
        assertThat(profile.canvas.scope).isEqualTo("all");
    }

    @Test
    void buildRejectsInvalidColorScheme() {
        assertThatThrownBy(() -> BrowserFingerprintProfile.builder()
                .colorScheme("sepia")
                .build())
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("colorScheme");
    }

    @Test
    void buildRejectsInvalidRuntimePolicy() {
        assertThatThrownBy(() -> BrowserFingerprintProfile.builder()
                .runtimePolicy("turbo-mode")
                .build())
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("runtimePolicy");
    }

    @Test
    void buildRejectsInvalidFormFactor() {
        assertThatThrownBy(() -> BrowserFingerprintProfile.builder()
                .formFactors("Toaster")
                .build())
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("formFactors");
    }

    @Test
    void buildNormalizesEmptyRuntimePolicyToLegacySpoof() {
        BrowserFingerprintProfile profile = BrowserFingerprintProfile.builder()
                .runtimePolicy("")
                .build();

        assertThat(profile.runtimePolicy)
                .isEqualTo(BrowserFingerprintProfile.RUNTIME_POLICY_LEGACY_SPOOF);
    }

    @Test
    void mobileFlagSelectsMobileFormFactorDefault() {
        BrowserFingerprintProfile profile = BrowserFingerprintProfile.builder()
                .mobile(true, "Pixel 8")
                .build();

        assertThat(profile.mobile).isTrue();
        assertThat(profile.model).isEqualTo("Pixel 8");
        assertThat(profile.formFactors).containsExactly("Mobile");
    }
}
