package io.github.selenium.javaDriverless.profiles;

import io.github.selenium.javaDriverless.types.ChromeOptions;

@FunctionalInterface
public interface RuntimeSurfaceInstaller {
    void install(SdkProfile profile, ProfileLaunchPlan plan, ChromeOptions options, RuntimeSurfaceInstallResult result);
}
