package io.github.selenium.driverless;

import io.github.selenium.javaDriverless.types.BrowserFingerprintProfile;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.HashSet;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

class StealthFingerprintProfileTest {
    @TempDir
    Path tempDir;

    @Test
    @DisplayName("[BUILDER] Fluent API cria profile consistente")
    void builderFluentApi() {
        var profile = BrowserFingerprintProfile.builder()
                .name("test-builder")
                .locale("en-US")
                .timezone("America/New_York")
                .platform("Win32", "Windows", "10.0.0")
                .architecture("x86", "64")
                .languages("en-US", "en")
                .hardware(8, 16, 0)
                .screen(1920, 1080)
                .webgl("real", "Google Inc. (NVIDIA)", "ANGLE (NVIDIA, test)")
                .webrtc("altered", true, "1.2.3.4")
                .geolocation(40.7, -74.0)
                .connection("4g", 10.0, 50)
                .build();

        assertThat(profile.name).isEqualTo("test-builder");
        assertThat(profile.locale).isEqualTo("en-US");
        assertThat(profile.timezoneId).isEqualTo("America/New_York");
        assertThat(profile.platform).isEqualTo("Win32");
        assertThat(profile.hardwareConcurrency).isEqualTo(8);
        assertThat(profile.deviceMemory).isEqualTo(16);
        assertThat(profile.screen.width).isEqualTo(1920);
        assertThat(profile.screen.mode).isEqualTo("spoof");
        assertThat(profile.webgl.vendor).contains("NVIDIA");
        assertThat(profile.webRtc.publicIp).isEqualTo("1.2.3.4");
        assertThat(profile.geolocation.latitude).isEqualTo(40.7);
    }

    @Test
    @DisplayName("[PRESETS] Todos 7 presets criam profiles válidos")
    void presetsValid() {
        var presets = new BrowserFingerprintProfile[] {
                BrowserFingerprintProfile.usWindows10(),
                BrowserFingerprintProfile.usWindows11(),
                BrowserFingerprintProfile.brLinux(),
                BrowserFingerprintProfile.brWindows(),
                BrowserFingerprintProfile.euMacOS(),
                BrowserFingerprintProfile.deMacOS(),
                BrowserFingerprintProfile.jpWindows()
        };

        for (var profile : presets) {
            assertThat(profile.name).isNotBlank();
            assertThat(profile.locale).isNotBlank();
            assertThat(profile.timezoneId).isNotBlank();
            assertThat(profile.hardwareConcurrency).isBetween(2, 32);
            assertThat(profile.deviceMemory).isBetween(2, 64);
            assertThat(profile.screen.width).isGreaterThan(0);
            assertThat(profile.webgl.vendor).isNotBlank();
        }
    }

    @Test
    @DisplayName("[RANDOM] randomProfile gera profiles reproduzíveis e distintos por seed")
    void randomProfileConsistency() {
        var windows = BrowserFingerprintProfile.randomProfile("windows", 101L);
        var linux = BrowserFingerprintProfile.randomProfile("linux", 202L);
        var macos = BrowserFingerprintProfile.randomProfile("macos", 303L);

        assertThat(windows.platform).isEqualTo("Win32");
        assertThat(windows.webgl.vendor).containsAnyOf("NVIDIA", "AMD", "Intel");
        assertThat(linux.platform).isEqualTo("Linux x86_64");
        assertThat(macos.platform).isEqualTo("MacIntel");
        assertThat(macos.webgl.vendor).contains("Apple");
        assertThat(BrowserFingerprintProfile.randomProfile("windows", 101L).name)
                .isEqualTo(windows.name);

        var names = new HashSet<String>();
        for (long seed = 1; seed <= 10; seed++) {
            names.add(BrowserFingerprintProfile.randomProfile("windows", seed).name);
        }
        assertThat(names).hasSize(10);
    }

    @Test
    @DisplayName("[GEO] forCountry auto-configura 20 países")
    void forCountryAutoConfig() {
        var brazil = BrowserFingerprintProfile.forCountry("BR")
                .hardware(8, 16, 0)
                .screen(1920, 1080)
                .webgl("real", "t", "t")
                .build();

        assertThat(brazil.locale).isEqualTo("pt-BR");
        assertThat(brazil.timezoneId).isEqualTo("America/Sao_Paulo");
        assertThat(brazil.geolocation.latitude).isCloseTo(-23.5505, within(0.01));
        assertThat(brazil.languages).contains("pt-BR");
        assertThat(BrowserFingerprintProfile.supportedCountries()).hasSizeGreaterThanOrEqualTo(20);
    }

    @Test
    @DisplayName("[SERIAL] toJson round-trip preserva campos")
    void jsonRoundTrip() throws Exception {
        var original = BrowserFingerprintProfile.usWindows10();
        String json = original.toJsonString();
        assertThat(json).contains("\"name\"", "\"us-windows-10\"", "\"locale\"");

        Path profileFile = tempDir.resolve("profile.json");
        original.toJson(profileFile);
        var loaded = BrowserFingerprintProfile.fromJson(profileFile);

        assertThat(loaded.name).isEqualTo(original.name);
        assertThat(loaded.locale).isEqualTo(original.locale);
        assertThat(loaded.screen.width).isEqualTo(original.screen.width);
    }

    @Test
    @DisplayName("[GEO+RAND] randomForCountry combina geo + random")
    void randomForCountryCombo() {
        var profile = BrowserFingerprintProfile.randomForCountry("DE", "windows", 404L);

        assertThat(profile.locale).isEqualTo("de-DE");
        assertThat(profile.timezoneId).isEqualTo("Europe/Berlin");
        assertThat(profile.geolocation.latitude).isCloseTo(52.52, within(0.01));
        assertThat(profile.platform).isEqualTo("Win32");
        assertThat(profile.hardwareConcurrency).isBetween(4, 16);
        assertThat(BrowserFingerprintProfile.randomForCountry("DE", "windows", 404L).name)
                .isEqualTo(profile.name);
    }
}
