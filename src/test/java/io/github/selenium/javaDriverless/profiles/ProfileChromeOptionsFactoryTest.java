package io.github.selenium.javaDriverless.profiles;

import io.github.selenium.javaDriverless.types.ChromeOptions;
import io.github.selenium.javaDriverless.types.ProxyConfig;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ProfileChromeOptionsFactoryTest {
    @TempDir
    Path tempDir;

    @Test
    void buildsChromeOptionsFromReadyProfileAndCreatesStorage() {
        SdkProfile profile = SdkProfileGenerator.fromLegacyPreset(
                new SdkProfilePresetRequest("options", "Options", "BR", "linux"));
        profile.storage.userDataDir = tempDir.resolve("user-data").toString();
        profile.storage.downloadsDir = tempDir.resolve("downloads").toString();
        profile.storage.persistent = true;
        profile.browser.startupUrl = "https://example.com";
        profile.browser.headless = true;
        profile.browser.language = "pt-BR";
        profile.browser.userAgent = "ExampleAgent";
        profile.browser.windowWidth = 1280;
        profile.browser.windowHeight = 720;
        profile.proxy = new SdkProfileProxy("127.0.0.1", 8080, ProxyConfig.ProxyType.HTTP);
        profile.extensionPaths.add(tempDir.resolve("extension").toString());
        profile.resourcePolicy.blockedUrlPatterns = List.of("*://ads.example/*");
        profile.resourcePolicy.preferences.put("profile.default_content_setting_values.notifications", 2);

        ProfileLaunchPlan plan = ProfileLaunchPlanner.plan(profile);
        ChromeOptions options = ProfileChromeOptionsFactory.fromProfile(profile, plan);

        assertThat(options.hasCatalogLaunchValidator()).isFalse();
        assertThat(Path.of(options.getUserDataDir())).isDirectory();
        assertThat(Path.of(options.getDownloadsDir())).isDirectory();
        assertThat(options.getStartupUrl()).isEqualTo("https://example.com");
        assertThat(options.isHeadless()).isTrue();
        assertThat(options.getArguments()).contains("--lang=pt-BR", "--user-agent=ExampleAgent", "--window-size=1280,720");
        assertThat(options.getExtensionPaths()).contains(Path.of(profile.extensionPaths.get(0)).toAbsolutePath().normalize().toString());
        assertThat(options.getProxyConfig().getHost()).isEqualTo("127.0.0.1");
        assertThat(options.getFingerprintProfile().locale).isEqualTo("pt-BR");
        @SuppressWarnings("unchecked")
        Map<String, Object> profilePrefs = (Map<String, Object>) options.getPrefs().get("profile");
        @SuppressWarnings("unchecked")
        Map<String, Object> defaultContentSettings =
                (Map<String, Object>) profilePrefs.get("default_content_setting_values");
        assertThat(defaultContentSettings).containsEntry("notifications", 2);
    }

    @Test
    void rejectsBlockedLaunchPlan() {
        SdkProfile profile = new SdkProfile("blocked", "Blocked");
        profile.metadata.locked = true;
        ProfileLaunchPlan plan = ProfileLaunchPlanner.plan(profile);

        assertThatThrownBy(() -> ProfileChromeOptionsFactory.fromProfile(profile, plan))
                .isInstanceOf(ProfileCatalogException.class)
                .hasMessageContaining("blocked profile");
    }

    @Test
    void catalogOptionsValidateStorageWithoutCreatingItBeforeLease() throws Exception {
        Path catalogRoot = Files.createDirectories(tempDir.resolve("validate-only-catalog"));
        Path userData = tempDir.resolve("validate-only-user-data");
        Path downloads = tempDir.resolve("validate-only-downloads");
        SdkProfile profile = new SdkProfile("profile-a", "Profile A");
        profile.storage.userDataDir = userData.toString();
        profile.storage.downloadsDir = downloads.toString();
        SdkProfileCatalog catalog = SdkProfileCatalog.at(catalogRoot);
        Files.writeString(catalogRoot.resolve("profile-a.json"), catalog.toJson(profile), StandardCharsets.UTF_8);

        ChromeOptions options = JavaDriverlessProfiles.at(catalogRoot).options("profile-a");

        assertThat(options.getUserDataDir()).isEqualTo(userData.toAbsolutePath().normalize().toString());
        assertThat(options.getDownloadsDir()).isEqualTo(downloads.toAbsolutePath().normalize().toString());
        assertThat(userData).doesNotExist();
        assertThat(downloads).doesNotExist();
        try (var ignored = options.acquireCatalogLaunchLease()) {
            assertThat(userData).isDirectory();
            assertThat(downloads).isDirectory();
        }
    }

    @Test
    void rejectsExtraArgumentsThatOverrideManagedIsolationSettings() {
        for (String reservedArgument : List.of(
                "--user-data-dir=/tmp/escape",
                "-USER-DATA-DIR /tmp/escape",
                "/user-data-dir=/tmp/escape",
                "--remote-debugging-port=9222",
                "-Remote-Debugging-Address 0.0.0.0",
                "/REMOTE-DEBUGGING-PIPE",
                "--remote-debugging-address=0.0.0.0",
                "--proxy-server=http://attacker.invalid:8080",
                "-Proxy-Server http://sensitive-user:sensitive-password@attacker.invalid:8080",
                "/proxy-bypass-list=<-loopback>",
                "--proxy-pac-url=http://attacker.invalid/pac",
                "--no-proxy-server",
                "-NO-PROXY-SERVER",
                "/no-proxy-server")) {
            SdkProfile profile = new SdkProfile("unsafe", "Unsafe");
            profile.browser.extraArgs = List.of(reservedArgument);
            ProfileLaunchPlan plan = ProfileLaunchPlanner.plan(profile);

            assertThatThrownBy(() -> ProfileChromeOptionsFactory.fromProfile(profile, plan))
                    .as(reservedArgument)
                    .isInstanceOf(ProfileCatalogException.class)
                    .hasMessageContaining("browser.extraArgs")
                    .hasMessageNotContaining(reservedArgument)
                    .hasMessageNotContaining("sensitive-user")
                    .hasMessageNotContaining("sensitive-password");
        }
    }

    @Test
    void safeSdkAndPhantomPassProfilesRejectSandboxBypassesBeforeLaunch() {
        for (String argument : List.of(
                "--no-sandbox",
                "--disable-setuid-sandbox",
                "--disable-seccomp-sandbox",
                "--disable-zygote-sandbox",
                "--no-zygote",
                "--single-process",
                "--in-process-gpu",
                "--disable-web-security",
                "--disable-features=IsolateOrigins,site-per-process")) {
            SdkProfile safe = SdkProfilePresets.linux("safe-args", "Safe args", "BR");
            safe.browser.extraArgs = List.of(argument);
            assertThatThrownBy(() -> ProfileChromeOptionsFactory.fromProfile(safe))
                    .as(argument)
                    .isInstanceOf(ProfileCatalogException.class)
                    .hasMessageContaining("minimal-cdp")
                    .hasMessageNotContaining("IsolateOrigins");

            PhantomPassProfile phantom = SdkProfilePresets.phantomPass(
                    SdkProfilePreset.DOCKER, "phantom-args", "Phantom args", "BR");
            phantom.profile.browser.extraArgs.add(argument);
            assertThatThrownBy(() -> ProfileChromeOptionsFactory.fromPhantomPass(phantom))
                    .as(argument)
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("minimal-cdp")
                    .hasMessageNotContaining("IsolateOrigins");
        }
    }

    @Test
    void seleniumLikeRejectsSandboxBypassFromStructuredExtraArguments() {
        SdkProfile profile = SdkProfilePresets.seleniumLike(
                "seleniumlike-unsafe", "SeleniumLike unsafe");
        profile.browser.extraArgs.add("--no-sandbox");

        assertThatThrownBy(() -> ProfileChromeOptionsFactory.fromProfile(profile))
                .isInstanceOf(ProfileCatalogException.class)
                .hasMessageContaining("minimal-cdp");
    }

    @Test
    void everyPublicStructuredProfileRejectsHeadlessBooleanMutation() {
        for (StructuredProfileCase profileCase : publicStructuredProfiles("headless-boolean")) {
            profileCase.profile().browser.headless = true;

            assertThatThrownBy(profileCase.build()::get)
                    .as(profileCase.name())
                    .isInstanceOf(RuntimeException.class)
                    .hasMessageContaining("Chrome visível");
        }
    }

    @Test
    void everyPublicStructuredProfileRejectsHeadlessExtraArgument() {
        for (StructuredProfileCase profileCase : publicStructuredProfiles("headless-argument")) {
            profileCase.profile().browser.extraArgs.add("--headless=new");

            assertThatThrownBy(profileCase.build()::get)
                    .as(profileCase.name())
                    .isInstanceOf(RuntimeException.class)
                    .hasMessageContaining("Chrome visível");
        }
    }

    @Test
    void everyPublicStructuredProfileRejectsHeadlessAddedAfterFactory() {
        for (String mutation : List.of("argument", "setter")) {
            for (StructuredProfileCase profileCase : publicStructuredProfiles(
                    "late-headless-" + mutation)) {
                ChromeOptions options = profileCase.build().get();
                if ("setter".equals(mutation)) {
                    options.setHeadless(true);
                } else {
                    options.addArgument("--headless=new");
                }

                assertThatThrownBy(options::validateProfileLaunchArguments)
                        .as(profileCase.name() + ":" + mutation)
                        .isInstanceOf(IllegalStateException.class)
                        .hasMessageContaining("Chrome visível");
            }
        }
    }

    @Test
    void everyPublicStructuredProfileRejectsHeadlessRuntimeEnvironment() throws Exception {
        Path javaBinary = Path.of(System.getProperty("java.home"), "bin", "java");
        String testClasspath = System.getProperty("surefire.test.class.path");
        ProcessBuilder builder = new ProcessBuilder(
                javaBinary.toString(),
                "-cp", testClasspath,
                HeadlessProfileEnvironmentMain.class.getName());
        builder.environment().remove("JAVA_DRIVERLESS_CONTAINER_FLAGS");
        builder.environment().remove("JAVA_DRIVERLESS_EXTRA_ARGS");
        builder.environment().remove("JAVA_DRIVERLESS_REMOVE_DEFAULT_ARGS");
        builder.environment().put("JAVA_DRIVERLESS_HEADLESS", "true");
        Process child = builder.redirectErrorStream(true).start();

        assertThat(child.waitFor(10, TimeUnit.SECONDS)).isTrue();
        String output = new String(child.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        assertThat(child.exitValue()).describedAs(output).isZero();
    }

    @Test
    void adHocChromeOptionsDoNotInheritTheStructuredHeadfulGuard() {
        ChromeOptions options = new ChromeOptions();
        options.setHeadless(true);

        assertThat(options.isHeadless()).isTrue();
        assertThatCode(options::validateProfileLaunchArguments).doesNotThrowAnyException();
    }

    @Test
    void everyPublicStructuredProfileRejectsSandboxBypassAddedAfterFactory() {
        List<ChromeOptions> optionsByProfile = new java.util.ArrayList<>();
        optionsByProfile.add(ProfileChromeOptionsFactory.fromProfile(
                SdkProfilePresets.windows("windows-guard", "Windows guard", "US")));
        optionsByProfile.add(ProfileChromeOptionsFactory.fromProfile(
                SdkProfilePresets.linux("linux-guard", "Linux guard", "US")));
        optionsByProfile.add(ProfileChromeOptionsFactory.fromProfile(
                SdkProfilePresets.docker("docker-guard", "Docker guard", "US")));
        optionsByProfile.add(ProfileChromeOptionsFactory.fromProfile(
                SdkProfilePresets.seleniumLike("seleniumlike-guard", "SeleniumLike guard")));
        optionsByProfile.add(ProfileChromeOptionsFactory.fromPhantomPass(
                SdkProfilePresets.phantomPass(
                        SdkProfilePreset.DOCKER, "phantom-guard", "Phantom guard", "US")));
        List<String> bypasses = List.of(
                "--no-sandbox",
                "--disable-setuid-sandbox",
                "--disable-web-security",
                "--disable-features=IsolateOrigins,SitePerProcess",
                "--single-process");

        for (int index = 0; index < optionsByProfile.size(); index++) {
            ChromeOptions options = optionsByProfile.get(index);
            options.addArgument(bypasses.get(index));

            assertThatThrownBy(options::validateProfileLaunchArguments)
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("minimal-cdp")
                    .hasMessageNotContaining("IsolateOrigins");
        }
    }

    @Test
    void structuredBuildRejectsSandboxBypassFromRuntimeEnvironment() throws Exception {
        Path javaBinary = Path.of(System.getProperty("java.home"), "bin", "java");
        String testClasspath = System.getProperty("surefire.test.class.path");
        for (Map<String, String> unsafeEnvironment : List.of(
                Map.of("JAVA_DRIVERLESS_EXTRA_ARGS", "--no-sandbox"),
                Map.of("JAVA_DRIVERLESS_CONTAINER_FLAGS", "true"))) {
            ProcessBuilder builder = new ProcessBuilder(
                    javaBinary.toString(),
                    "-cp", testClasspath,
                    StructuredProfileEnvironmentMain.class.getName());
            builder.environment().remove("JAVA_DRIVERLESS_CONTAINER_FLAGS");
            builder.environment().remove("JAVA_DRIVERLESS_EXTRA_ARGS");
            builder.environment().remove("JAVA_DRIVERLESS_HEADLESS");
            builder.environment().remove("JAVA_DRIVERLESS_REMOVE_DEFAULT_ARGS");
            builder.environment().putAll(unsafeEnvironment);
            Process child = builder.redirectErrorStream(true).start();

            assertThat(child.waitFor(10, TimeUnit.SECONDS)).isTrue();
            String output = new String(child.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            assertThat(child.exitValue()).describedAs(output).isZero();
        }
    }

    @Test
    void rejectsTheSameRawUserDataDirectoryInjectedIntoTwoOfficialProfiles() {
        ChromeOptions first = ProfileChromeOptionsFactory.fromProfile(
                SdkProfilePresets.linux("account-a", "Account A", "BR"));
        ChromeOptions second = ProfileChromeOptionsFactory.fromProfile(
                SdkProfilePresets.linux("account-b", "Account B", "BR"));
        String shared = tempDir.resolve("shared-user-data").toString();

        first.addArgument("--user-data-dir=" + shared);
        second.addArgument("--user-data-dir=" + shared);

        assertThatThrownBy(first::validateProfileLaunchArguments)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("managed")
                .hasMessageNotContaining(shared);
        assertThatThrownBy(second::validateProfileLaunchArguments)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("managed")
                .hasMessageNotContaining(shared);
    }

    public static final class StructuredProfileEnvironmentMain {
        private StructuredProfileEnvironmentMain() {
        }

        public static void main(String[] args) {
            try {
                ProfileChromeOptionsFactory.fromProfile(SdkProfilePresets.seleniumLike(
                        "seleniumlike-env", "SeleniumLike env"));
                System.exit(2);
            } catch (ProfileCatalogException expected) {
                if (!expected.getMessage().contains("minimal-cdp")) {
                    throw expected;
                }
            }
        }
    }

    public static final class HeadlessProfileEnvironmentMain {
        private HeadlessProfileEnvironmentMain() {
        }

        public static void main(String[] args) {
            for (StructuredProfileCase profileCase : publicStructuredProfiles("headless-env")) {
                try {
                    profileCase.build().get();
                    System.err.println("Headless environment accepted by " + profileCase.name());
                    System.exit(2);
                } catch (RuntimeException expected) {
                    if (!expected.getMessage().contains("Chrome visível")) {
                        throw expected;
                    }
                }
            }
        }
    }

    private static List<StructuredProfileCase> publicStructuredProfiles(String suffix) {
        SdkProfile windows = SdkProfilePresets.windows("windows-" + suffix, "Windows", "US");
        SdkProfile linux = SdkProfilePresets.linux("linux-" + suffix, "Linux", "US");
        SdkProfile docker = SdkProfilePresets.docker("docker-" + suffix, "Docker", "US");
        SdkProfile seleniumLike = SdkProfilePresets.seleniumLike(
                "seleniumlike-" + suffix, "SeleniumLike");
        PhantomPassProfile phantomPass = SdkProfilePresets.phantomPass(
                SdkProfilePreset.DOCKER, "phantompass-" + suffix, "PhantomPass", "US");
        return List.of(
                new StructuredProfileCase(
                        "windows", windows, () -> ProfileChromeOptionsFactory.fromProfile(windows)),
                new StructuredProfileCase(
                        "linux", linux, () -> ProfileChromeOptionsFactory.fromProfile(linux)),
                new StructuredProfileCase(
                        "docker", docker, () -> ProfileChromeOptionsFactory.fromProfile(docker)),
                new StructuredProfileCase(
                        "phantompass", phantomPass.profile,
                        () -> ProfileChromeOptionsFactory.fromPhantomPass(phantomPass)),
                new StructuredProfileCase(
                        "seleniumlike", seleniumLike,
                        () -> ProfileChromeOptionsFactory.fromProfile(seleniumLike)));
    }

    private record StructuredProfileCase(
            String name,
            SdkProfile profile,
            java.util.function.Supplier<ChromeOptions> build) {
    }
}
