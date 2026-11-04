package io.github.selenium.javaDriverless.profiles;

import io.github.selenium.javaDriverless.types.ChromeOptions;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

public class RuntimeSurfaceRegistry {
    private final Map<String, RuntimeSurfaceInstaller> installers = new LinkedHashMap<>();

    public static RuntimeSurfaceRegistry defaults() {
        RuntimeSurfaceRegistry registry = new RuntimeSurfaceRegistry();
        registry.register("fingerprint", (profile, plan, options, result) ->
                options.setFingerprintProfile(plan.normalizedFingerprint == null
                        ? profile.fingerprint : plan.normalizedFingerprint));
        registry.register("proxy", (profile, plan, options, result) -> {
            if (profile.proxy == null) {
                result.warnings.add("Proxy surface requested without profile proxy");
            } else {
                options.setProxyConfig(profile.proxy.toProxyConfig());
            }
        });
        registry.register("extensions", (profile, plan, options, result) -> {
            if (profile.extensionPaths == null || profile.extensionPaths.isEmpty()) {
                result.warnings.add("Extensions surface requested without extension paths");
            }
        });
        registry.register("resource-policy", (profile, plan, options, result) -> {
            if (profile.resourcePolicy == null) {
                result.warnings.add("Resource policy surface requested without resource policy");
            }
        });
        registry.register("network", (profile, plan, options, result) -> {
            if (plan.proxyPlan != null && !"ready".equals(plan.proxyPlan.status)) {
                result.warnings.add("Network surface has proxy plan status: " + plan.proxyPlan.status);
            }
        });
        return registry;
    }

    public RuntimeSurfaceRegistry register(String name, RuntimeSurfaceInstaller installer) {
        String key = normalize(name);
        if (installer == null) {
            throw new IllegalArgumentException("Runtime surface installer must not be null");
        }
        installers.put(key, installer);
        return this;
    }

    public boolean contains(String name) {
        return installers.containsKey(normalize(name));
    }

    public Set<String> names() {
        return Set.copyOf(installers.keySet());
    }

    public RuntimeSurfaceInstallResult install(SdkProfile profile, ProfileLaunchPlan plan, ChromeOptions options) {
        RuntimeSurfaceInstallResult result = new RuntimeSurfaceInstallResult();
        if (profile.runtimeSurfaces == null || profile.runtimeSurfaces.isEmpty()) {
            return result;
        }
        for (String surface : profile.runtimeSurfaces) {
            String key = normalize(surface);
            RuntimeSurfaceInstaller installer = installers.get(key);
            if (installer == null) {
                result.missing.add(key);
                continue;
            }
            installer.install(profile, plan, options, result);
            result.installed.add(key);
        }
        return result;
    }

    private static String normalize(String name) {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("Runtime surface name must not be blank");
        }
        return name.trim().toLowerCase(Locale.ROOT);
    }
}
