package io.github.selenium.javaDriverless.types;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Map;

import org.junit.jupiter.api.Test;

class ChromeOptionsRuntimeArgumentOverridesTest {
    @Test
    void removesDefaultArgumentsByPrefixAndAddsExtraArguments() {
        ChromeOptions options = new ChromeOptions();

        ChromeOptionsDefaults.applyRuntimeArgumentOverrides(options, Map.of(
            "JAVA_DRIVERLESS_REMOVE_DEFAULT_ARGS", "--disable-accelerated-2d-canvas,--mute-audio",
            "JAVA_DRIVERLESS_EXTRA_ARGS", "--enable-accelerated-2d-canvas;--use-angle=swiftshader"
        ));

        assertFalse(options.getArguments().contains("--disable-accelerated-2d-canvas"));
        assertFalse(options.getArguments().contains("--mute-audio"));
        assertTrue(options.getArguments().contains("--enable-accelerated-2d-canvas"));
        assertTrue(options.getArguments().contains("--use-angle=swiftshader"));
    }

    @Test
    void leavesDefaultsUntouchedWhenOverridesAreAbsent() {
        ChromeOptions options = new ChromeOptions();

        ChromeOptionsDefaults.applyRuntimeArgumentOverrides(options, Map.of());

        assertTrue(options.getArguments().contains("--disable-accelerated-2d-canvas"));
        assertTrue(options.getArguments().contains("--mute-audio"));
    }

    @Test
    void rejectsManagedArgumentsFromGlobalExtraArguments() {
        for (String argument : java.util.List.of(
                "--user-data-dir=/tmp/shared",
                "--remote-debugging-address=0.0.0.0",
                "--remote-debugging-port=9222",
                "--proxy-server=http://sensitive-user:sensitive-password@proxy.invalid:8080",
                "--no-proxy-server")) {
            ChromeOptions options = new ChromeOptions();

            IllegalArgumentException failure = assertThrows(IllegalArgumentException.class,
                    () -> ChromeOptionsDefaults.applyRuntimeArgumentOverrides(options, Map.of(
                            "JAVA_DRIVERLESS_EXTRA_ARGS", argument)));

            assertTrue(failure.getMessage().contains("managed"));
            assertFalse(failure.getMessage().contains(argument));
            assertFalse(failure.getMessage().contains("sensitive-user"));
            assertFalse(failure.getMessage().contains("sensitive-password"));
        }
    }

    @Test
    void minimalPolicyRejectsSyntheticGraphicsAddedThroughRuntimeOverrides() {
        ChromeOptions options = new ChromeOptions();
        ChromeOptionsDefaults.applyRuntimeArgumentOverrides(options, Map.of(
                "JAVA_DRIVERLESS_EXTRA_ARGS",
                "--use-gl=angle;--use-angle=swiftshader;--enable-unsafe-swiftshader"));

        IllegalStateException failure = assertThrows(
                IllegalStateException.class,
                options::applyMinimalCdpProcessArgumentPolicy);

        assertTrue(failure.getMessage().contains("minimal-cdp"));
        assertTrue(failure.getMessage().contains("SwiftShader"));
    }

    @Test
    void minimalPolicyRejectsSandboxAndSiteIsolationBypasses() {
        for (String argument : java.util.List.of(
                "--no-sandbox",
                "--disable-seccomp-sandbox",
                "--disable-zygote-sandbox",
                "--no-zygote",
                "--single-process",
                "--in-process-gpu",
                "--disable-web-security",
                "--disable-features=IsolateOrigins,site-per-process")) {
            ChromeOptions options = new ChromeOptions();
            options.addArgument(argument);

            IllegalStateException failure = assertThrows(IllegalStateException.class,
                    options::applyMinimalCdpProcessArgumentPolicy, argument);

            assertTrue(failure.getMessage().contains("minimal-cdp"), argument);
            assertFalse(failure.getMessage().contains("IsolateOrigins"), argument);
        }
    }

    @Test
    void minimalPolicyForcesExactlyOneSitePerProcessArgument() {
        ChromeOptions options = new ChromeOptions();
        options.addArgument("--site-per-process");
        options.addArgument("--site-per-process");

        options.applyMinimalCdpProcessArgumentPolicy();

        assertEquals(1, options.getArguments().stream()
                .filter("--site-per-process"::equals)
                .count());
    }
}
