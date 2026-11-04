package io.github.selenium.javaDriverless.types;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Caracterizacao do codec JSON de perfis de fingerprint: round-trip em
 * disco, copia profunda independente e serializacao estavel.
 */
class BrowserFingerprintJsonCodecRoundTripTest {

    @TempDir
    Path temp;

    private BrowserFingerprintProfile configuredProfile() {
        BrowserFingerprintProfile profile = new BrowserFingerprintProfile();
        profile.name = "round-trip-profile";
        profile.locale = "pt-BR";
        profile.timezoneId = "America/Sao_Paulo";
        profile.platform = "Windows NT 10.0; Win64; x64";
        profile.uaPlatform = "Windows";
        profile.architecture = "arm";
        profile.bitness = "32";
        profile.hardwareConcurrency = 4;
        profile.deviceMemory = 2;
        profile.languages = java.util.List.of("pt-BR", "pt");
        profile.screen.mode = "spoof";
        profile.screen.width = 1366;
        profile.screen.height = 768;
        profile.screen.availHeight = 728;
        profile.webgl.vendor = "MockVendor";
        profile.webgl.renderer = "MockRenderer";
        profile.webRtc.publicIp = "203.0.113.10";
        profile.geolocation.latitude = -23.55;
        profile.geolocation.longitude = -46.63;
        profile.geolocation.accuracy = 42.0;
        profile.fonts.mode = "filter";
        profile.fonts.families = java.util.List.of("Arial", "Verdana");
        profile.ports.protectedPorts = java.util.List.of(22, 80);
        return profile;
    }

    @Test
    void writeThenReadPreservesConfiguredSurfaces() throws Exception {
        BrowserFingerprintProfile original = configuredProfile();
        Path file = temp.resolve("profile.json");

        BrowserFingerprintJsonCodec.write(file, original);
        BrowserFingerprintProfile restored = BrowserFingerprintJsonCodec.read(file);

        assertThat(restored.name).isEqualTo("round-trip-profile");
        assertThat(restored.locale).isEqualTo("pt-BR");
        assertThat(restored.timezoneId).isEqualTo("America/Sao_Paulo");
        assertThat(restored.platform).isEqualTo("Windows NT 10.0; Win64; x64");
        assertThat(restored.uaPlatform).isEqualTo("Windows");
        assertThat(restored.architecture).isEqualTo("arm");
        assertThat(restored.bitness).isEqualTo("32");
        assertThat(restored.hardwareConcurrency).isEqualTo(4);
        assertThat(restored.deviceMemory).isEqualTo(2);
        assertThat(restored.languages).containsExactly("pt-BR", "pt");
        assertThat(restored.screen.width).isEqualTo(1366);
        assertThat(restored.screen.height).isEqualTo(768);
        assertThat(restored.screen.availHeight).isEqualTo(728);
        assertThat(restored.webgl.vendor).isEqualTo("MockVendor");
        assertThat(restored.webgl.renderer).isEqualTo("MockRenderer");
        assertThat(restored.webRtc.publicIp).isEqualTo("203.0.113.10");
        assertThat(restored.geolocation.latitude).isEqualTo(-23.55);
        assertThat(restored.geolocation.longitude).isEqualTo(-46.63);
        assertThat(restored.geolocation.accuracy).isEqualTo(42.0);
        assertThat(restored.fonts.families).containsExactly("Arial", "Verdana");
        assertThat(restored.ports.protectedPorts).containsExactly(22, 80);
    }

    @Test
    void copyIsDeepAndIndependentFromSource() {
        BrowserFingerprintProfile original = configuredProfile();

        BrowserFingerprintProfile duplicate = BrowserFingerprintJsonCodec.copy(original);
        duplicate.screen.width = 999;
        duplicate.fonts.families = java.util.List.of("Wingdings");
        duplicate.languages = java.util.List.of("de-DE");

        assertThat(original.screen.width).isEqualTo(1366);
        assertThat(original.fonts.families).containsExactly("Arial", "Verdana");
        assertThat(original.languages).containsExactly("pt-BR", "pt");
    }

    @Test
    void writeStringIsDeterministicAndPrettyPrinted() throws Exception {
        BrowserFingerprintProfile profile = configuredProfile();

        String first = BrowserFingerprintJsonCodec.writeString(profile);
        String second = BrowserFingerprintJsonCodec.writeString(profile);

        assertThat(first).isEqualTo(second);
        assertThat(first).contains("\n");
        assertThat(first).contains("\"name\" : \"round-trip-profile\"");
    }
}
