package io.github.selenium.javaDriverless;

import io.github.selenium.javaDriverless.types.BrowserFingerprintProfile;
import io.github.selenium.javaDriverless.types.ChromeOptions;
import io.github.selenium.javaDriverless.utils.Utils;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.nio.file.Files;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ProfileIsolationCoreHardeningTest {
    private static final String LINUX_UA = "Mozilla/5.0 (X11; Linux x86_64) "
            + "AppleWebKit/537.36 (KHTML, like Gecko) Chrome/150.0.0.0 Safari/537.36";
    private static final String WINDOWS_UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) "
            + "AppleWebKit/537.36 (KHTML, like Gecko) Chrome/150.0.0.0 Safari/537.36";

    @TempDir Path tempDir;

    @Test
    void runtimeVersionMatchesPublishedArtifact() {
        assertThat(Utils.VERSION).isEqualTo("1.11.7");
    }

    @Test
    void unsafeProfileNamesStayInsideRootAndDoNotCollide() {
        Path root = tempDir.resolve("profiles");

        Path traversal = ProfileKey.resolveUnder(root, "../../outside-canary");
        Path slash = ProfileKey.resolveUnder(root, "tenant/a");
        Path colon = ProfileKey.resolveUnder(root, "tenant:a");

        Path normalizedRoot = root.toAbsolutePath().normalize();
        assertThat(traversal.startsWith(normalizedRoot)).isTrue();
        assertThat(slash.startsWith(normalizedRoot)).isTrue();
        assertThat(colon.startsWith(normalizedRoot)).isTrue();
        assertThat(slash).isNotEqualTo(colon);
        assertThat(ProfileKey.safeComponent("safe-profile_01")).isEqualTo("safe-profile_01");
    }

    @Test
    void pidFilesForFormerlyCollidingNamesRemainIndependent() throws Exception {
        ProfileManager manager = new ProfileManager(tempDir.resolve("pids").toString());

        manager.savePid("tenant/a", 101L);
        manager.savePid("tenant:a", 202L);

        assertThat(manager.getPidFilePath("tenant/a")).isNotEqualTo(manager.getPidFilePath("tenant:a"));
        assertThat(manager.loadPid("tenant/a")).contains(101L);
        assertThat(manager.loadPid("tenant:a")).contains(202L);
    }

    @Test
    void ambiguousLegacyPidFileIsNeverClaimedByEitherProfile() throws Exception {
        Path pidRoot = tempDir.resolve("legacy-pids");
        Files.createDirectories(pidRoot);
        Files.writeString(pidRoot.resolve("tenant_a.pid"), "999\n0");
        ProfileManager manager = new ProfileManager(pidRoot.toString());

        assertThat(manager.loadPid("tenant/a")).isEmpty();
        assertThat(manager.loadPid("tenant:a")).isEmpty();
    }

    @Test
    void preExistingProfileSymlinkIsRejected() throws Exception {
        Path root = Files.createDirectories(tempDir.resolve("symlink-profiles"));
        Path outside = Files.createDirectories(tempDir.resolve("outside-profile"));
        Files.createSymbolicLink(root.resolve("linked-profile"), outside);

        assertThatThrownBy(() -> ProfileKey.resolveUnder(root, "linked-profile"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("link simbólico");
    }

    @Test
    void profileNamesDifferingOnlyByCaseCannotAliasExistingStorageOrPid() throws Exception {
        Path profileRoot = Files.createDirectories(tempDir.resolve("case-profiles"));
        Files.createDirectories(profileRoot.resolve("Foo"));
        assertThatThrownBy(() -> ProfileKey.resolveUnder(profileRoot, "foo"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("maiúsculas/minúsculas");

        Path pidRoot = Files.createDirectories(tempDir.resolve("case-pids"));
        Files.writeString(pidRoot.resolve("Foo.pid"), "100\n0");
        ProfileManager manager = new ProfileManager(pidRoot.toString());
        assertThatThrownBy(() -> manager.getPidFilePath("foo"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("maiúsculas/minúsculas");
    }

    @Test
    void firstUseCaseVariantsHaveDifferentStorageAndPidKeysWithoutFilesystemRaces() {
        Path profileRoot = tempDir.resolve("new-case-profiles");
        String upperStorage = ProfileKey.resolveUnder(profileRoot, "Foo").getFileName().toString();
        String lowerStorage = ProfileKey.resolveUnder(profileRoot, "foo").getFileName().toString();
        assertThat(upperStorage).isNotEqualToIgnoringCase(lowerStorage);

        Path pidRoot = tempDir.resolve("new-case-pids");
        ProfileManager manager = new ProfileManager(pidRoot.toString());
        String upperPid = manager.getPidFilePath("Foo").getFileName().toString();
        String lowerPid = manager.getPidFilePath("foo").getFileName().toString();
        assertThat(upperPid).isNotEqualToIgnoringCase(lowerPid);
    }

    @Test
    void generatedStorageNamespaceCannotBeClaimedByALiteralProfileId() {
        Path profileRoot = tempDir.resolve("namespace-profiles");
        String generatedCaseKey = ProfileKey.resolveUnder(profileRoot, "Foo").getFileName().toString();
        String literalCaseKey = ProfileKey.resolveUnder(profileRoot, generatedCaseKey).getFileName().toString();
        assertThat(generatedCaseKey).isNotEqualToIgnoringCase(literalCaseKey);

        String generatedUnsafeKey = ProfileKey.resolveUnder(profileRoot, "tenant/a").getFileName().toString();
        String literalUnsafeKey = ProfileKey.resolveUnder(profileRoot, generatedUnsafeKey).getFileName().toString();
        assertThat(generatedUnsafeKey).isNotEqualToIgnoringCase(literalUnsafeKey);

        ProfileManager manager = new ProfileManager(tempDir.resolve("namespace-pids").toString());
        String generatedPid = manager.getPidFilePath("Foo").getFileName().toString();
        String literalPid = manager.getPidFilePath(generatedPid.substring(0, generatedPid.length() - 4))
                .getFileName().toString();
        assertThat(generatedPid).isNotEqualToIgnoringCase(literalPid);
    }

    @Test
    void userAgentIsPerOptionsAndDuplicateArgumentsAreReplaced() {
        ChromeOptions first = options(LINUX_UA, fingerprint(BrowserFingerprintProfile.brLinux()));
        ChromeOptions second = options(WINDOWS_UA, fingerprint(BrowserFingerprintProfile.usWindows11()));
        first.addArgument("--user-agent=" + LINUX_UA);

        Chrome chromeA = new Chrome(first, 0.25f, false, 1 << 20);
        Chrome chromeB = new Chrome(second, 0.25f, false, 1 << 20);

        assertThat(first.getUserAgent()).isEqualTo(LINUX_UA);
        assertThat(first.getArguments().stream().filter(value -> value.startsWith("--user-agent=")).toList())
                .containsExactly("--user-agent=" + LINUX_UA);
        assertThat(chromeA.resolveSessionUserAgent()).isEqualTo(LINUX_UA);
        assertThat(chromeB.resolveSessionUserAgent()).isEqualTo(WINDOWS_UA);
    }

    @Test
    void explicitUserAgentMustMatchFingerprintAndClientHints() {
        ChromeOptions mismatch = options(LINUX_UA, fingerprint(BrowserFingerprintProfile.usWindows11()));

        assertThatThrownBy(() -> new Chrome(mismatch, 0.25f, false, 1 << 20)
                .resolveSessionUserAgent())
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("incompatível");
    }

    @Test
    void explicitAndroidUserAgentMustMatchPlatformVersionAndModelClientHints() {
        BrowserFingerprintProfile android = new BrowserFingerprintProfile();
        android.browserVersion = "150.0.0.0";
        android.platform = "Linux armv8l";
        android.uaPlatform = "Android";
        android.platformVersion = "13.0.0";
        android.architecture = "arm";
        android.bitness = "64";
        android.mobile = true;
        android.model = "SM-G991B";
        String differentDevice = "Mozilla/5.0 (Linux; Android 8; Pixel 2) "
                + "AppleWebKit/537.36 (KHTML, like Gecko) Chrome/150.0.0.0 Mobile Safari/537.36";

        assertThatThrownBy(() -> new Chrome(options(differentDevice, android), 0.25f, false, 1 << 20)
                .resolveSessionUserAgent())
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("incompatível");
    }

    @Test
    void generatedUserAgentsRemainPerFingerprintWithoutGlobalFileState() {
        ChromeOptions linux = options(null, BrowserFingerprintProfile.brLinux());
        ChromeOptions windows = options(null, BrowserFingerprintProfile.usWindows11());

        String linuxUa = new Chrome(linux, 0.25f, false, 1 << 20).resolveSessionUserAgent();
        String windowsUa = new Chrome(windows, 0.25f, false, 1 << 20).resolveSessionUserAgent();

        assertThat(linuxUa).contains("X11; Linux");
        assertThat(windowsUa).contains("Windows NT");
        assertThat(linuxUa).isNotEqualTo(windowsUa);
    }

    private ChromeOptions options(String userAgent, BrowserFingerprintProfile fingerprint) {
        ChromeOptions options = new ChromeOptions();
        options.setFingerprintProfile(fingerprint);
        if (userAgent != null) options.setUserAgent(userAgent);
        return options;
    }

    private BrowserFingerprintProfile fingerprint(BrowserFingerprintProfile value) {
        value.browserVersion = "150.0.0.0";
        return value;
    }
}
