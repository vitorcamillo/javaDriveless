package io.github.selenium.driverless;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class TestSuiteSafetyPolicyTest {
    @Test
    void defaultMavenGateExcludesTestsWithExternalSideEffects() throws IOException {
        String pom = Files.readString(Path.of("pom.xml"));

        assertThat(pom)
                .contains("<excludedGroups>${surefire.excludedGroups}</excludedGroups>")
                .contains("<surefire.excludedGroups>browser,external,network</surefire.excludedGroups>");
    }

    @Test
    void legacyBrowserSuiteNeverDeletesTheSharedProfilesDirectory() throws IOException {
        String source = Files.readString(Path.of(
                "src/test/java/io/github/selenium/driverless/JavaDriverlessTest.java"));

        assertThat(source)
                .doesNotContain("private static final String PROFILES_DIR = \"profiles\"")
                .doesNotContain("Files.walk(profilesPath)");
    }

    @Test
    void browserActionsCannotDisableFetchOutsideTheEssentialGuardDispatcher() throws IOException {
        String source = Files.readString(Path.of(
                "src/main/java/io/github/selenium/javaDriverless/ChromeBrowserActions.java"));

        assertThat(source)
                .contains("NetworkInterceptor.disableFetch(currentTarget)")
                .doesNotContain("currentTarget.executeCdpCmd(\"Fetch.disable\"");
    }
}
