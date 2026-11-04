package io.github.selenium.javaDriverless.profiles;

import io.github.selenium.javaDriverless.types.ChromeOptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class JavaDriverlessProfilesTest {
    @TempDir
    Path tempDir;

    @Test
    void providesShortPathForCreateSelectPlanAndOptions() {
        JavaDriverlessProfiles profiles = JavaDriverlessProfiles.at(tempDir);
        profiles.create(new SdkProfilePresetRequest("quick", "Quick", "BR", "linux"));

        SdkProfile selected = profiles.select("Quick");
        ProfileLaunchPlan plan = profiles.plan("quick");
        ChromeOptions options = profiles.options("quick");

        assertThat(selected.id).isEqualTo("quick");
        assertThat(plan.profileId).isEqualTo("quick");
        assertThat(options.getFingerprintProfile().locale).isEqualTo("pt-BR");
    }

    @Test
    void exposesLifecycleOperationsThroughFacade() {
        JavaDriverlessProfiles profiles = JavaDriverlessProfiles.at(tempDir);
        profiles.create(new SdkProfilePresetRequest("base", "Base", "US", "windows"));
        SdkProfileCloneRequest request = new SdkProfileCloneRequest("base", "copy", "Copy", 1);

        SdkProfileCloneResult result = profiles.cloneProfiles(request);

        assertThat(result.items).extracting(item -> item.status).containsExactly("planned");
        assertThat(profiles.select("copy-1").name).isEqualTo("Copy 1");
    }
}
