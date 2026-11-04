package io.github.selenium.javaDriverless.profiles;

import io.github.selenium.javaDriverless.types.BrowserFingerprintProfile;
import io.github.selenium.javaDriverless.types.ChromeOptions;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;

/** Public facade for the five supported JavaDriverless profile templates. */
public final class SdkProfilePresets {
    private static final String LEGACY_CONTAINER_CHROME = "/usr/bin/google-chrome-stable";
    private static final int LEGACY_BET365_WIDTH = 1440;
    private static final int LEGACY_BET365_HEIGHT = 1000;

    /**
     * @deprecated legacy compatibility helper; deliberately excluded from supportedPresets().
     */
    @Deprecated
    public static final List<String> FLAGS_BET365_BR = List.of(
            "--disable-blink-features=AutomationControlled",
            "--no-sandbox",
            "--disable-dev-shm-usage",
            "--disable-setuid-sandbox",
            "--disable-sync",
            "--disable-component-update",
            "--disable-default-apps",
            "--disable-crash-reporter",
            "--disable-crashpad",
            "--remote-debugging-address=127.0.0.1",
            "--window-size=" + LEGACY_BET365_WIDTH + "," + LEGACY_BET365_HEIGHT);

    private static final List<SdkProfilePreset> SUPPORTED = List.of(
            SdkProfilePreset.WINDOWS,
            SdkProfilePreset.LINUX,
            SdkProfilePreset.DOCKER,
            SdkProfilePreset.PHANTOMPASS,
            SdkProfilePreset.SELENIUMLIKE);

    private SdkProfilePresets() {
    }

    /** @return the complete and stable public catalog; site fixtures are intentionally excluded */
    public static List<SdkProfilePreset> supportedPresets() {
        return SUPPORTED;
    }

    public static SdkProfile windows(String id, String name, String countryCode) {
        return create(SdkProfilePreset.WINDOWS, id, name, countryCode);
    }

    public static SdkProfile linux(String id, String name, String countryCode) {
        return create(SdkProfilePreset.LINUX, id, name, countryCode);
    }

    public static SdkProfile docker(String id, String name, String countryCode) {
        return create(SdkProfilePreset.DOCKER, id, name, countryCode);
    }

    public static SdkProfile seleniumLike(String id, String name) {
        return create(SdkProfilePreset.SELENIUMLIKE, id, name, "US");
    }

    /** Uses host/container detection to select one of Windows, Linux or Docker. */
    public static SdkProfile defaultRuntime(String id, String name, String countryCode) {
        return create(SdkProfilePreset.defaultRuntime(), id, name, countryCode);
    }

    /**
     * Creates the consumer-owned PhantomPass contract over one validated runtime base.
     * Every nested field remains editable before it is converted to isolated ChromeOptions.
     */
    public static PhantomPassProfile phantomPass(
            SdkProfilePreset runtimeBase,
            String id,
            String name,
            String countryCode) {
        if (runtimeBase == null || !runtimeBase.isRuntimeBase()) {
            throw new IllegalArgumentException("runtimeBase deve ser windows, linux ou docker");
        }
        PhantomPassProfile contract = new PhantomPassProfile();
        contract.runtimeBase = runtimeBase;
        contract.profile = create(runtimeBase, id, name, countryCode);
        contract.profile.preset = SdkProfilePreset.PHANTOMPASS.id();
        contract.profile.metadata.tags.remove("preset:" + runtimeBase.id());
        contract.profile.metadata.tags.add("preset:phantompass");
        contract.profile.metadata.tags.add("runtime-base:" + runtimeBase.id());
        contract.profile.runtimeSurfaces.add("phantompass-configurable");
        contract.location.countryCode = countryCode == null ? "" : countryCode.trim();
        contract.permissions.geolocation = contract.profile.fingerprint.permissions.geolocation;
        contract.permissions.notifications = contract.profile.fingerprint.permissions.notifications;
        return contract;
    }

    private static SdkProfile create(
            SdkProfilePreset preset,
            String id,
            String name,
            String countryCode) {
        SdkProfilePresetRequest request = new SdkProfilePresetRequest(
                id, name, countryCode, preset.id());
        return SdkProfileGenerator.fromPreset(request);
    }

    /** @deprecated explicit legacy helper outside the five-profile public catalog. */
    @Deprecated
    public static SdkProfile bet365Br() {
        SdkProfile profile = new SdkProfile("bet365-br", "Bet365 BR");
        profile.fingerprint = BrowserFingerprintProfile.brLinux();
        profile.browser.headless = headlessAtivo();
        profile.browser.windowWidth = LEGACY_BET365_WIDTH;
        profile.browser.windowHeight = LEGACY_BET365_HEIGHT;
        profile.browser.extraArgs = FLAGS_BET365_BR;
        resolveLegacyBinary(profile);
        profile.storage.userDataDir = null;
        profile.storage.persistent = false;
        profile.metadata.recommended = false;
        profile.metadata.securityGuarantee = "legacy-compatibility";
        return profile.applyDefaults();
    }

    /** @deprecated explicit legacy helper outside the five-profile public catalog. */
    @Deprecated
    public static ChromeOptions bet365BrContainerOptions() {
        ChromeOptions options = new ChromeOptions();
        options.setFingerprintProfile(BrowserFingerprintProfile.brLinux());
        FLAGS_BET365_BR.forEach(options::addArgument);
        resolveLegacyBinary(options);
        if (headlessAtivo()) options.setHeadless(true);
        return options;
    }

    /** @deprecated explicit legacy helper outside the five-profile public catalog. */
    @Deprecated
    public static ChromeOptions bet365BrContainerOptions(BrowserFingerprintProfile fingerprint) {
        ChromeOptions options = bet365BrContainerOptions();
        if (fingerprint != null) options.setFingerprintProfile(fingerprint);
        return options;
    }

    private static void resolveLegacyBinary(SdkProfile profile) {
        String configured = System.getenv("JAVA_DRIVERLESS_BROWSER_BINARY");
        if (configured != null && !configured.isBlank() && Files.isExecutable(Path.of(configured))) {
            profile.browser.binaryPath = configured;
        } else if (Files.isExecutable(Path.of(LEGACY_CONTAINER_CHROME))) {
            profile.browser.binaryPath = LEGACY_CONTAINER_CHROME;
        }
    }

    private static void resolveLegacyBinary(ChromeOptions options) {
        String configured = System.getenv("JAVA_DRIVERLESS_BROWSER_BINARY");
        if (configured != null && !configured.isBlank() && Files.isExecutable(Path.of(configured))) {
            options.setBinaryLocation(configured);
        } else if (Files.isExecutable(Path.of(LEGACY_CONTAINER_CHROME))) {
            options.setBinaryLocation(LEGACY_CONTAINER_CHROME);
        }
    }

    static boolean headlessAtivo() {
        String value = System.getenv("JAVA_DRIVERLESS_HEADLESS");
        if (value == null || value.isBlank()) return true;
        String normalized = value.trim().toLowerCase(Locale.ROOT);
        return !(normalized.equals("false") || normalized.equals("0") || normalized.equals("no"));
    }
}
