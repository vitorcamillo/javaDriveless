package io.github.selenium.javaDriverless.profiles;

import io.github.selenium.javaDriverless.types.BrowserFingerprintProfile;
import io.github.selenium.javaDriverless.types.BrowserSessionProfile;
import io.github.selenium.javaDriverless.types.ChromeOptions;
import io.github.selenium.javaDriverless.types.MinimalCdpProcessArgumentPolicy;
import io.github.selenium.javaDriverless.types.ProxyConfig;

import java.io.IOException;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.Locale;
import java.util.StringJoiner;

public final class ProfileChromeOptionsFactory {
    private ProfileChromeOptionsFactory() {
    }

    public static ChromeOptions fromProfile(SdkProfile profile) {
        return fromProfile(profile, ProfileLaunchPlanner.plan(profile));
    }

    public static ChromeOptions fromProfile(SdkProfile profile, ProfileLaunchPlan plan) {
        return build(profile, plan, true);
    }

    /** Builds isolated options from the complete, consumer-owned PhantomPass contract. */
    public static ChromeOptions fromPhantomPass(PhantomPassProfile contract) {
        PhantomPassRuntime runtime = defaultPhantomPassRuntime(contract);
        return applyPhantomPass(contract, runtime, new ChromeOptions());
    }

    /** Applies the validated PhantomPass policy to a consumer-owned ChromeOptions subtype. */
    public static <T extends ChromeOptions> T applyPhantomPass(
            PhantomPassProfile contract,
            PhantomPassRuntime physicalRuntime,
            T options) {
        if (options == null) {
            throw new IllegalArgumentException("ChromeOptions não pode ser nulo");
        }
        PhantomPassProfile evaluated = PhantomPassProfilePolicy
                .evaluateForLaunch(contract, physicalRuntime)
                .requireAcceptedProfile();
        SdkProfile runtime = evaluated.profile.copy();
        runtime.preset = SdkProfilePreset.PHANTOMPASS.id();
        ProfileLaunchPlan plan = ProfileLaunchPlanner.plan(runtime);
        build(runtime, plan, true, options);

        BrowserSessionProfile session = new BrowserSessionProfile();
        session.fingerprint = options.getFingerprintProfile();
        session.proxy = effectiveProxy(evaluated, runtime);
        copyLocation(evaluated, session);
        copyDns(evaluated, session);
        copyPermissions(evaluated, session);
        options.setSessionProfile(session);
        // The contract is authoritative for transport state. A caller-owned options subtype may
        // already have served another profile, so absence must clear old proxy/egress state rather
        // than silently reusing another tenant's transport.
        options.setProxyConfig(session.proxy);
        options.enableAuthenticatedProxyBridge(
                session.proxy != null && session.proxy.hasAuthentication());
        options.setEgressInfo(evaluated.egressInfo);
        options.setEgressResolver(evaluated.egressResolver);
        return options;
    }

    private static PhantomPassRuntime defaultPhantomPassRuntime(PhantomPassProfile contract) {
        if (contract == null || contract.profile == null || contract.runtimeBase == null) {
            return PhantomPassRuntime.NATIVE_HOST;
        }
        if (contract.runtimeBase != SdkProfilePreset.DOCKER) {
            return PhantomPassRuntime.NATIVE_HOST;
        }
        BrowserFingerprintProfile.Screen screen = contract.profile.fingerprint == null
                ? null : contract.profile.fingerprint.screen;
        if (screen == null) return PhantomPassRuntime.VIRTUAL_DISPLAY;
        return PhantomPassRuntime.virtualDisplay(new PhantomPassRuntime.Geometry(
                screen.width, screen.height, screen.availWidth, screen.availHeight,
                screen.colorDepth, screen.pixelDepth, screen.deviceScaleFactor));
    }

    private static ChromeOptions build(SdkProfile profile,
                                       ProfileLaunchPlan plan,
                                       boolean createStorage) {
        return build(profile, plan, createStorage, new ChromeOptions());
    }

    private static <T extends ChromeOptions> T build(
            SdkProfile profile,
            ProfileLaunchPlan plan,
            boolean createStorage,
            T options) {
        if (profile == null) {
            throw new IllegalArgumentException("Profile must not be null");
        }
        if (plan == null) {
            throw new IllegalArgumentException("Launch plan must not be null");
        }
        if ("blocked".equals(plan.status)) {
            throw new ProfileCatalogException("Cannot build ChromeOptions for blocked profile: "
                    + String.join("; ", plan.errors));
        }
        validateMinimalBrowserConfiguration(profile);
        applyStorage(profile, options, createStorage);
        applyBrowser(profile, options);
        applyExtensions(profile, options);
        applyResourcePolicy(profile, options);
        validateStructuredProcessArguments(options);
        if (profile.proxy != null) {
            options.setProxyConfig(profile.proxy.toProxyConfig());
        }
        options.setFingerprintProfile(plan.normalizedFingerprint == null
                ? profile.fingerprint : plan.normalizedFingerprint);
        if (BrowserFingerprintProfile.RUNTIME_POLICY_MINIMAL_CDP.equalsIgnoreCase(
                profile.fingerprint.runtimePolicy == null
                        ? "" : profile.fingerprint.runtimePolicy.trim())) {
            try {
                rejectSyntheticGraphicsArguments(options.getArguments());
            } catch (IllegalArgumentException unsafe) {
                throw new ProfileCatalogException(unsafe.getMessage());
            }
        }
        if (!profile.browser.headless && options.isHeadless()) {
            throw new ProfileCatalogException(
                    "Perfil exige Chrome visível, mas JAVA_DRIVERLESS_HEADLESS/argumentos ativaram --headless");
        }
        options.bindProfileLaunchArgumentGuard();
        return options;
    }

    private static void validateStructuredProcessArguments(ChromeOptions options) {
        try {
            MinimalCdpProcessArgumentPolicy.requireSafe(options.getArguments());
        } catch (IllegalArgumentException unsafe) {
            throw new ProfileCatalogException(unsafe.getMessage());
        }
    }

    private static void validateMinimalBrowserConfiguration(SdkProfile profile) {
        if (profile.fingerprint == null
                || !BrowserFingerprintProfile.RUNTIME_POLICY_MINIMAL_CDP.equalsIgnoreCase(
                        profile.fingerprint.runtimePolicy == null
                                ? "" : profile.fingerprint.runtimePolicy.trim())) {
            if (isPublicStructuredProfile(profile) && profile.browser.headless) {
                throw new ProfileCatalogException(
                        "Perfis oficiais exigem Chrome visível; no Docker use Chrome headful sob Xvfb");
            }
            return;
        }
        if (profile.browser.headless || containsHeadless(profile.browser.extraArgs)) {
            throw new ProfileCatalogException(
                    "runtimePolicy=minimal-cdp exige Chrome visível; use Xvfb no Docker");
        }
        if (profile.browser.userAgent != null && !profile.browser.userAgent.isBlank()) {
            throw new ProfileCatalogException(
                    "runtimePolicy=minimal-cdp exige User-Agent nativo do Chrome");
        }
        try {
            MinimalCdpProcessArgumentPolicy.requireSafe(profile.browser.extraArgs);
            rejectSyntheticGraphicsArguments(profile.browser.extraArgs);
        } catch (IllegalArgumentException unsafe) {
            throw new ProfileCatalogException(unsafe.getMessage());
        }
    }

    private static boolean isPublicStructuredProfile(SdkProfile profile) {
        String preset = profile.preset == null ? "" : profile.preset.trim().toLowerCase(Locale.ROOT);
        return SdkProfilePreset.supportedIds().contains(preset);
    }

    private static boolean containsHeadless(java.util.List<String> arguments) {
        return arguments != null && arguments.stream()
                .filter(java.util.Objects::nonNull)
                .map(value -> value.trim().toLowerCase(Locale.ROOT))
                .anyMatch(value -> value.equals("--headless") || value.startsWith("--headless="));
    }

    private static void rejectSyntheticGraphicsArguments(java.util.List<String> arguments) {
        if (arguments == null) {
            return;
        }
        boolean unsafe = arguments.stream()
                .filter(java.util.Objects::nonNull)
                .map(value -> value.trim().toLowerCase(Locale.ROOT))
                .anyMatch(value -> value.contains("swiftshader")
                        || value.equals("--disable-gpu")
                        || value.startsWith("--disable-gpu="));
        if (unsafe) {
            throw new IllegalArgumentException(
                    "runtimePolicy=minimal-cdp não aceita SwiftShader ou GPU sintética/desativada");
        }
    }

    private static ProxyConfig effectiveProxy(PhantomPassProfile contract, SdkProfile runtime) {
        if (contract.proxy != null) {
            return contract.proxy.copy();
        }
        return runtime.proxy == null ? null : runtime.proxy.toProxyConfig();
    }

    private static void copyLocation(PhantomPassProfile contract, BrowserSessionProfile session) {
        session.location.countryCode = contract.location.countryCode;
        session.location.region = contract.location.region;
        session.location.city = contract.location.city;
    }

    private static void copyDns(PhantomPassProfile contract, BrowserSessionProfile session) {
        session.dns.policy = contract.dns.policy;
        session.dns.dohTemplate = contract.dns.dohTemplate;
    }

    private static void copyPermissions(PhantomPassProfile contract, BrowserSessionProfile session) {
        session.permissions.geolocation = contract.permissions.geolocation;
        session.permissions.notifications = contract.permissions.notifications;
        session.permissions.extra = contract.permissions.extra == null
                ? new java.util.HashMap<>() : new java.util.HashMap<>(contract.permissions.extra);
    }

    /**
     * Construcao para perfil persistido. Alem do plano, vincula um token imutavel de ownership
     * revalidado pelo catalogo no startup real. O overload publico sem catalogo permanece ad hoc.
     */
    static ChromeOptions fromCatalogProfile(SdkProfile profile,
                                            ProfileLaunchPlan plan,
                                            SdkProfileCatalog catalog) {
        if (catalog == null) {
            throw new IllegalArgumentException("Profile catalog must not be null");
        }
        ChromeOptions options = build(profile, plan, false);
        SdkProfileCatalog.LaunchOwnershipToken token = catalog.issueLaunchOwnershipToken(profile);
        options.bindCatalogLaunchValidator(current -> catalog.acquireLaunchOwnership(
                token, current.getUserDataDir(), current.getDownloadsDir()));
        return options;
    }

    private static void applyStorage(SdkProfile profile,
                                     ChromeOptions options,
                                     boolean createStorage) {
        String deferredUserData = null;
        String deferredDownloads = null;
        if (profile.storage.userDataDir != null && !profile.storage.userDataDir.isBlank()) {
            Path dir = createStorage
                    ? ensureDirectory(profile.id, "userDataDir", profile.storage.userDataDir)
                    : SdkProfileCatalog.requireSafeStoragePath(
                            profile.id, "userDataDir", profile.storage.userDataDir);
            if (createStorage) options.setUserDataDir(dir.toString());
            else deferredUserData = dir.toString();
            options.setAutoCleanDirs(!profile.storage.persistent || profile.storage.clearOnClose);
        }
        if (profile.storage.downloadsDir != null && !profile.storage.downloadsDir.isBlank()) {
            Path dir = createStorage
                    ? ensureDirectory(profile.id, "downloadsDir", profile.storage.downloadsDir)
                    : SdkProfileCatalog.requireSafeStoragePath(
                            profile.id, "downloadsDir", profile.storage.downloadsDir);
            if (createStorage) options.setDownloadsDir(dir.toString());
            else deferredDownloads = dir.toString();
        }
        if (!createStorage) options.setDeferredCatalogStorage(deferredUserData, deferredDownloads);
    }

    private static void applyBrowser(SdkProfile profile, ChromeOptions options) {
        if (profile.browser.binaryPath != null && !profile.browser.binaryPath.isBlank()) {
            options.setBinaryLocation(profile.browser.binaryPath);
        }
        if (profile.browser.startupUrl != null && !profile.browser.startupUrl.isBlank()) {
            options.setStartupUrl(profile.browser.startupUrl);
        }
        if (profile.browser.headless) {
            options.setHeadless(true);
        }
        if (profile.browser.language != null && !profile.browser.language.isBlank()) {
            options.addArgument("--lang=" + profile.browser.language);
        }
        if (profile.browser.userAgent != null && !profile.browser.userAgent.isBlank()) {
            options.addArgument("--user-agent=" + profile.browser.userAgent);
        }
        if (profile.browser.windowWidth != null && profile.browser.windowHeight != null) {
            options.addArgument("--window-size=" + profile.browser.windowWidth + "," + profile.browser.windowHeight);
        }
        if (profile.browser.extraArgs != null) {
            for (String arg : profile.browser.extraArgs) {
                if (arg != null && !arg.isBlank()) {
                    rejectManagedArgument(arg);
                    options.addArgument(arg);
                }
            }
        }
    }

    private static void rejectManagedArgument(String argument) {
        String name = canonicalArgumentName(argument);
        boolean reserved = name.equals("user-data-dir")
                || name.startsWith("remote-debugging-")
                || name.startsWith("proxy-")
                || name.equals("no-proxy-server");
        if (reserved) {
            throw new ProfileCatalogException("browser.extraArgs contains managed browser argument");
        }
    }

    private static String canonicalArgumentName(String argument) {
        String normalized = argument.trim().toLowerCase(Locale.ROOT);
        int prefixLength;
        if (normalized.startsWith("--")) {
            prefixLength = 2;
        } else if (normalized.startsWith("-") || normalized.startsWith("/")) {
            prefixLength = 1;
        } else {
            return "";
        }

        String body = normalized.substring(prefixLength);
        int end = body.length();
        for (int index = 0; index < body.length(); index++) {
            char current = body.charAt(index);
            if (current == '=' || Character.isWhitespace(current)) {
                end = index;
                break;
            }
        }
        return body.substring(0, end);
    }

    private static void applyExtensions(SdkProfile profile, ChromeOptions options) {
        if (profile.extensionPaths == null || profile.extensionPaths.isEmpty()) {
            return;
        }
        StringJoiner joiner = new StringJoiner(",");
        for (String path : profile.extensionPaths) {
            if (path != null && !path.isBlank()) {
                joiner.add(Path.of(path).toAbsolutePath().normalize().toString());
            }
        }
        String extensions = joiner.toString();
        if (!extensions.isBlank()) {
            options.addArgument("--load-extension=" + extensions);
        }
    }

    private static void applyResourcePolicy(SdkProfile profile, ChromeOptions options) {
        if (profile.resourcePolicy == null) {
            return;
        }
        if (profile.resourcePolicy.preferences != null) {
            profile.resourcePolicy.preferences.forEach(options::updatePref);
        }
        if (profile.resourcePolicy.blockedUrlPatterns != null
                && !profile.resourcePolicy.blockedUrlPatterns.isEmpty()) {
            options.updatePref("profile.managed_default_content_settings.images", 2);
            options.updatePref("javaDriverless.blockedUrlPatterns", profile.resourcePolicy.blockedUrlPatterns);
        }
    }

    private static Path ensureDirectory(String profileId, String field, String value) {
        Path dir = SdkProfileCatalog.requireSafeStoragePath(profileId, field, value);
        Path current = dir.getRoot();
        if (current == null) {
            throw new ProfileCatalogException("Profile storage path has no filesystem root");
        }
        try {
            for (Path component : dir) {
                current = current.resolve(component);
                BasicFileAttributes attributes;
                try {
                    attributes = Files.readAttributes(
                            current, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
                } catch (NoSuchFileException missing) {
                    try {
                        Files.createDirectory(current);
                    } catch (FileAlreadyExistsException raced) {
                        // Re-read with NOFOLLOW below; a concurrently inserted symlink is rejected.
                    }
                    attributes = Files.readAttributes(
                            current, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
                }
                if (attributes.isSymbolicLink()) {
                    throw new ProfileCatalogException("Profile '" + profileId + "' storage " + field
                            + " contains a symbolic link component");
                }
                if (!attributes.isDirectory()) {
                    throw new ProfileCatalogException("Profile '" + profileId + "' storage " + field
                            + " is not a directory");
                }
            }
            SdkProfileCatalog.requireSafeStoragePath(profileId, field, value);
            return dir;
        } catch (IOException e) {
            throw new ProfileCatalogException("Failed to create profile storage directory", e);
        }
    }
}
