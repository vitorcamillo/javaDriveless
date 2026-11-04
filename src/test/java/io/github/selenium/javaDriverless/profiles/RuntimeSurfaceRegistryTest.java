package io.github.selenium.javaDriverless.profiles;

import io.github.selenium.javaDriverless.types.ChromeOptions;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class RuntimeSurfaceRegistryTest {
    @Test
    void installsDefaultRuntimeSurfacesAndReportsMissingOnes() {
        SdkProfile profile = new SdkProfile("surfaces", "Surfaces");
        profile.fingerprint.locale = "pt-BR";
        profile.runtimeSurfaces.add("fingerprint");
        profile.runtimeSurfaces.add("proxy");
        profile.runtimeSurfaces.add("missing-surface");
        profile.proxy = new SdkProfileProxy("127.0.0.1", 8080,
                io.github.selenium.javaDriverless.types.ProxyConfig.ProxyType.HTTP);
        ProfileLaunchPlan plan = ProfileLaunchPlanner.plan(profile);
        ChromeOptions options = new ChromeOptions();

        RuntimeSurfaceInstallResult result = RuntimeSurfaceRegistry.defaults().install(profile, plan, options);

        assertThat(result.installed).containsExactly("fingerprint", "proxy");
        assertThat(result.missing).containsExactly("missing-surface");
        assertThat(options.getFingerprintProfile().locale).isEqualTo("pt-BR");
        assertThat(options.getProxyConfig().getHost()).isEqualTo("127.0.0.1");
    }

    @Test
    void supportsCustomInstallers() {
        SdkProfile profile = new SdkProfile("custom", "Custom");
        profile.runtimeSurfaces.add("custom");
        RuntimeSurfaceRegistry registry = new RuntimeSurfaceRegistry()
                .register("custom", (p, plan, options, result) -> options.addArgument("--custom-surface"));

        RuntimeSurfaceInstallResult result = registry.install(profile, ProfileLaunchPlanner.plan(profile), new ChromeOptions());

        assertThat(result.isComplete()).isTrue();
        assertThat(result.installed).containsExactly("custom");
    }

    @Test
    void proxyAndExtensionsSurfacesWarnWhenTheProfileLacksInputs() {
        SdkProfile profile = new SdkProfile("sem-insumos", "Sem Insumos");
        profile.runtimeSurfaces.add("proxy");
        profile.runtimeSurfaces.add("extensions");
        profile.runtimeSurfaces.add("resource-policy");
        profile.runtimeSurfaces.add("fingerprint");
        ProfileLaunchPlan plano = ProfileLaunchPlanner.plan(profile);
        // applyDefaults do planner recria a política: anula só na véspera do install
        profile.resourcePolicy = null;

        RuntimeSurfaceInstallResult result =
                RuntimeSurfaceRegistry.defaults().install(profile, plano, new ChromeOptions());

        assertThat(result.warnings)
                .contains("Proxy surface requested without profile proxy")
                .contains("Extensions surface requested without extension paths")
                .contains("Resource policy surface requested without resource policy");
        assertThat(result.installed).containsExactly("proxy", "extensions", "resource-policy", "fingerprint");
    }

    @Test
    void networkSurfaceWarnsWhenProxyPlanIsNotReady() {
        SdkProfile profile = new SdkProfile("rede", "Rede");
        profile.runtimeSurfaces.add("network");
        ProfileLaunchPlan plan = ProfileLaunchPlanner.plan(profile);

        RuntimeSurfaceInstallResult result =
                RuntimeSurfaceRegistry.defaults().install(profile, plan, new ChromeOptions());

        if (plan.proxyPlan != null && !"ready".equals(plan.proxyPlan.status)) {
            assertThat(result.warnings).anyMatch(warning ->
                    warning.startsWith("Network surface has proxy plan status: "));
        } else {
            assertThat(result.warnings).isEmpty();
        }
    }

    @Test
    void normalizedFingerprintWinsOverTheRawProfileFingerprint() {
        SdkProfile profile = new SdkProfile("normalizado", "Normalizado");
        profile.runtimeSurfaces.add("fingerprint");
        io.github.selenium.javaDriverless.types.BrowserFingerprintProfile normalizado =
                new io.github.selenium.javaDriverless.types.BrowserFingerprintProfile();
        normalizado.locale = "de-DE";
        ProfileLaunchPlan plan = ProfileLaunchPlanner.plan(profile);
        plan.normalizedFingerprint = normalizado;

        ChromeOptions options = new ChromeOptions();
        RuntimeSurfaceRegistry.defaults().install(profile, plan, options);

        assertThat(options.getFingerprintProfile().locale).isEqualTo("de-DE");
    }

    @Test
    void emptySurfaceListAndRegistryQueriesBehavePredictably() {
        SdkProfile profile = new SdkProfile("vazio", "Vazio");
        RuntimeSurfaceRegistry registry = RuntimeSurfaceRegistry.defaults();

        assertThat(registry.install(profile, ProfileLaunchPlanner.plan(profile), new ChromeOptions())
                .installed).isEmpty();
        assertThat(registry.contains("FINGERPRINT")).isTrue();
        assertThat(registry.contains("não-existe")).isFalse();
        assertThat(registry.names()).contains("fingerprint", "proxy", "extensions", "resource-policy", "network");

        org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException.class,
                () -> registry.register("x", null));
        org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException.class,
                () -> registry.contains("  "));
        org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException.class,
                () -> registry.register(null, (p, plan, options, result) -> { }));
    }
}
