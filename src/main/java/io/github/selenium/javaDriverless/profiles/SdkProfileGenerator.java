package io.github.selenium.javaDriverless.profiles;

import io.github.selenium.javaDriverless.types.BrowserFingerprintProfile;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Locale;
import java.util.Random;

public final class SdkProfileGenerator {
    private SdkProfileGenerator() {
    }

    /** Generates one of the five official templates. */
    public static SdkProfile fromPreset(SdkProfilePresetRequest request) {
        if (request == null) {
            throw new IllegalArgumentException("Preset request must not be null");
        }
        SdkProfilePreset preset = SdkProfilePreset.parse(request.selectedPreset());
        if (preset == SdkProfilePreset.PHANTOMPASS) {
            throw new IllegalArgumentException(
                    "O preset phantompass exige PhantomPassProfile com runtimeBase "
                            + "windows, linux ou docker; use SdkProfilePresets.phantomPass(...)");
        }
        validateOfficialRequest(request, preset);
        SdkProfile profile = new SdkProfile(
                SdkProfileCatalog.normalizeId(request.id),
                request.name == null || request.name.isBlank() ? request.id : request.name.trim());
        profile.preset = preset.id();
        profile.browser.channel = "chrome";
        profile.browser.headless = false;
        profile.metadata.tags.add("preset:" + preset.id());
        if (preset == SdkProfilePreset.SELENIUMLIKE) {
            profile.fingerprint = seleniumLikeFingerprint();
            profile.metadata.recommended = false;
            profile.metadata.securityGuarantee = "compatibility-only";
            profile.metadata.notes = "Perfil explícito de diagnóstico; não oferece garantia anti-leak.";
        } else {
            profile.fingerprint = safeFingerprint(profile.id, request.countryCode, preset);
            applyMinimalRuntimePolicy(profile.fingerprint);
            profile.metadata.securityGuarantee = "wrapper-free";
            if (preset == SdkProfilePreset.DOCKER) {
                configureDocker(profile);
            }
        }
        // Jackson materializes every nested collection as mutable ArrayList/LinkedHashMap so all
        // five templates remain consumer-editable without sharing state.
        return SdkProfileNormalizer.normalize(profile, false, null).profile.copy();
    }

    private static void validateOfficialRequest(
            SdkProfilePresetRequest request,
            SdkProfilePreset preset) {
        String channel = request.browserChannel == null ? "chrome" : request.browserChannel.trim();
        if (!channel.isEmpty() && !"chrome".equalsIgnoreCase(channel)) {
            throw new IllegalArgumentException("Os perfis oficiais suportam somente browserChannel=chrome");
        }
        if (request.headless) {
            throw new IllegalArgumentException(
                    "runtimePolicy=minimal-cdp exige Chrome visível; em servidor/Docker use Chrome "
                            + "headful sob Xvfb, nunca --headless");
        }
        if (request.mobile) {
            throw new IllegalArgumentException("Os perfis oficiais não suportam mobile/Android");
        }
        if (request.cleanBaseline) {
            throw new IllegalArgumentException("cleanBaseline não é um dos cinco perfis suportados");
        }
        if (request.diversityEnabled) {
            throw new IllegalArgumentException(
                    "runtimePolicy=minimal-cdp não aceita diversityEnabled: ruído de canvas/audio/"
                            + "DOMRect/WebGL exige wrappers JavaScript detectáveis");
        }
        String requestedPolicy = request.runtimePolicy == null
                ? "" : request.runtimePolicy.trim().toLowerCase(Locale.ROOT);
        String requiredPolicy = preset == SdkProfilePreset.SELENIUMLIKE
                ? BrowserFingerprintProfile.RUNTIME_POLICY_LEGACY_SPOOF
                : BrowserFingerprintProfile.RUNTIME_POLICY_MINIMAL_CDP;
        if (!requestedPolicy.isEmpty() && !requiredPolicy.equals(requestedPolicy)) {
            throw new IllegalArgumentException(
                    "O preset " + preset.id() + " exige runtimePolicy=" + requiredPolicy);
        }
    }

    private static BrowserFingerprintProfile safeFingerprint(
            String id,
            String countryCode,
            SdkProfilePreset preset) {
        String country = countryCode == null || countryCode.isBlank()
                ? "US" : countryCode.trim().toUpperCase(Locale.ROOT);
        String os = preset == SdkProfilePreset.WINDOWS ? "windows" : "linux";
        BrowserFingerprintProfile fingerprint = BrowserFingerprintProfile.randomForCountry(
                country, os, stableSeed(id + ":" + preset.id()));
        fingerprint.name = preset.id() + "-safe-" + country.toLowerCase(Locale.ROOT);
        return fingerprint;
    }

    private static void applyMinimalRuntimePolicy(BrowserFingerprintProfile fingerprint) {
        fingerprint.runtimePolicy = BrowserFingerprintProfile.RUNTIME_POLICY_MINIMAL_CDP;
        // Sem wrappers, estas superfícies só podem refletir o Chrome/host que executará o perfil.
        BrowserFingerprintProfile nativeBaseline = BrowserFingerprintProfile.nativeDefaults();
        fingerprint.browserVersion = "";
        fingerprint.platformVersion = "";
        fingerprint.hardwareConcurrency = nativeBaseline.hardwareConcurrency;
        fingerprint.deviceMemory = nativeBaseline.deviceMemory;
        fingerprint.vendor = nativeBaseline.vendor;
        fingerprint.doNotTrack = nativeBaseline.doNotTrack;
        fingerprint.webgl.mode = "real";
        fingerprint.webRtc.mode = "altered";
        fingerprint.webRtc.protectLocalIps = true;
        fingerprint.webRtc.disableUdp = true;
        fingerprint.mediaDevices.mode = "real";
        fingerprint.speech.mode = "auto";
        fingerprint.audio.mode = "real";
        fingerprint.audio.noise = 0;
        fingerprint.canvas.mode = "real";
        fingerprint.canvas.noise = 0;
        fingerprint.clientRects.mode = "real";
        fingerprint.clientRects.noise = 0;
        fingerprint.storage.mode = "real";
        fingerprint.fonts.mode = "real";
        fingerprint.ports.mode = "real";
        fingerprint.connection.mode = "real";
        fingerprint.maxTouchPoints = 0;
        fingerprint.architecture = "x86";
        fingerprint.bitness = "64";
        fingerprint.mobile = false;
        fingerprint.model = "";
        fingerprint.wow64 = false;
        fingerprint.formFactors = java.util.List.of("Desktop");
        fingerprint.screen.mode = "real";
    }

    private static void configureDocker(SdkProfile profile) {
        profile.fingerprint.colorScheme = "dark";
        profile.fingerprint.screen.mode = "spoof";
        profile.fingerprint.screen.width = 1365;
        profile.fingerprint.screen.height = 900;
        profile.fingerprint.screen.availWidth = 1365;
        profile.fingerprint.screen.availHeight = 860;
        profile.fingerprint.screen.colorDepth = 24;
        profile.fingerprint.screen.pixelDepth = 24;
        profile.fingerprint.screen.deviceScaleFactor = 1.0;
        profile.runtimeSurfaces.add("xvfb-visible");
        profile.browser.extraArgs = java.util.List.of("--disable-dev-shm-usage");
        String binary = System.getenv("JAVA_DRIVERLESS_BROWSER_BINARY");
        if (binary != null && !binary.isBlank()) {
            profile.browser.binaryPath = binary.trim();
        } else if (java.nio.file.Files.isExecutable(
                java.nio.file.Path.of("/usr/bin/google-chrome-stable"))) {
            profile.browser.binaryPath = "/usr/bin/google-chrome-stable";
        }
    }

    private static BrowserFingerprintProfile seleniumLikeFingerprint() {
        BrowserFingerprintProfile profile = new BrowserFingerprintProfile();
        profile.name = "selenium-like-minimal-surfaces";
        profile.runtimePolicy = BrowserFingerprintProfile.RUNTIME_POLICY_LEGACY_SPOOF;
        profile.browserVersion = "";
        profile.locale = "en-US";
        profile.timezoneId = "";
        profile.platform = "Linux x86_64";
        profile.uaPlatform = "Linux";
        profile.platformVersion = "";
        profile.architecture = "x86";
        profile.bitness = "64";
        profile.mobile = false;
        profile.model = "";
        profile.wow64 = false;
        profile.formFactors = java.util.List.of("Desktop");
        profile.languages = java.util.List.of("en-US", "en");
        profile.hardwareConcurrency = 8;
        profile.deviceMemory = 8;
        profile.maxTouchPoints = 0;
        profile.vendor = "Google Inc.";
        profile.doNotTrack = false;
        profile.colorScheme = "light";
        profile.screen.mode = "real";
        profile.webgl.mode = "real";
        profile.webRtc.mode = "real";
        profile.webRtc.protectLocalIps = false;
        profile.webRtc.disableUdp = false;
        profile.geolocation.mode = "prompt";
        profile.permissions.geolocation = "prompt";
        profile.permissions.notifications = "default";
        profile.mediaDevices.mode = "real";
        profile.speech.mode = "real";
        profile.audio.mode = "real";
        profile.audio.noise = 0;
        profile.canvas.mode = "real";
        profile.canvas.noise = 0;
        profile.clientRects.mode = "real";
        profile.clientRects.noise = 0;
        // Intentionally matches docs/site-profiles/selenium-like.json, where storage is omitted.
        profile.storage.mode = "spoof";
        profile.fonts.mode = "real";
        profile.ports.mode = "real";
        profile.ports.protectedPorts = java.util.List.of();
        profile.connection.mode = "real";
        profile.applyDefaults();
        return profile;
    }

    /**
     * Compatibility-only generator for old saved templates. It is deliberately excluded from the
     * official five-profile catalog.
     */
    @Deprecated
    public static SdkProfile fromLegacyPreset(SdkProfilePresetRequest request) {
        if (request == null) {
            throw new IllegalArgumentException("Preset request must not be null");
        }
        SdkProfile profile = new SdkProfile(
                SdkProfileCatalog.normalizeId(request.id),
                request.name == null || request.name.isBlank() ? request.id : request.name.trim());
        profile.browser.channel = request.browserChannel == null ? "chrome" : request.browserChannel;
        profile.browser.headless = request.headless;
        profile.fingerprint = legacyFingerprintFor(request);
        profile.fingerprint.runtimePolicy = BrowserFingerprintProfile.RUNTIME_POLICY_LEGACY_SPOOF;
        Long effectiveSeed = request.seed == null ? stableSeed(profile.id) : request.seed;
        if (request.diversityEnabled) {
            applyDiversity(profile, effectiveSeed);
        } else {
            clearDiversity(profile);
        }
        profile.metadata.recommended = false;
        profile.metadata.securityGuarantee = "legacy-compatibility";
        return SdkProfileNormalizer.normalize(profile, request.diversityEnabled,
                request.diversityEnabled ? effectiveSeed : request.seed).profile;
    }

    private static BrowserFingerprintProfile legacyFingerprintFor(SdkProfilePresetRequest request) {
        if (request.cleanBaseline) {
            BrowserFingerprintProfile clean = BrowserFingerprintProfile.nativeDefaults();
            clean.name = "clean-baseline";
            return clean;
        }
        String country = request.countryCode == null ? "US" : request.countryCode.toUpperCase(Locale.ROOT);
        String selector = request.preset != null && !request.preset.isBlank()
                ? request.preset : request.os;
        String os = selector == null || selector.isBlank()
                ? "windows" : selector.toLowerCase(Locale.ROOT);
        if (request.mobile) {
            return androidLike(country);
        }
        if ("br".equalsIgnoreCase(country) && "linux".equals(os)) {
            return BrowserFingerprintProfile.brLinux();
        }
        if ("us".equalsIgnoreCase(country) && os.startsWith("win")) {
            return BrowserFingerprintProfile.usWindows11();
        }
        if ("de".equalsIgnoreCase(country) && ("macos".equals(os) || "mac".equals(os))) {
            return BrowserFingerprintProfile.deMacOS();
        }
        if ("linux-headless".equals(os) || request.headless) {
            BrowserFingerprintProfile profile = BrowserFingerprintProfile.randomForCountry(country, "linux");
            profile.name = country.toLowerCase(Locale.ROOT) + "-linux-headless";
            profile.screen.width = 1366;
            profile.screen.height = 768;
            profile.screen.availWidth = 1366;
            profile.screen.availHeight = 728;
            return profile;
        }
        return BrowserFingerprintProfile.randomForCountry(country, normalizeOs(os));
    }

    private static BrowserFingerprintProfile androidLike(String country) {
        BrowserFingerprintProfile profile = BrowserFingerprintProfile.randomForCountry(country, "linux");
        profile.name = country.toLowerCase(Locale.ROOT) + "-android-mobile";
        profile.platform = "Linux armv8l";
        profile.uaPlatform = "Android";
        profile.platformVersion = "13.0.0";
        profile.architecture = "arm";
        profile.bitness = "64";
        profile.mobile = true;
        profile.model = "SM-A135M";
        profile.maxTouchPoints = 5;
        profile.screen.mode = "spoof";
        profile.screen.width = 412;
        profile.screen.height = 915;
        profile.screen.availWidth = 412;
        profile.screen.availHeight = 915;
        profile.screen.deviceScaleFactor = 2.625;
        return profile;
    }

    static void applyDiversity(SdkProfile profile, long seed) {
        Random random = new Random(seed);
        profile.metadata.seeds.put("diversity", seed);
        profile.fingerprint.noiseSeed = fingerprintNoiseSeed(seed);
        profile.fingerprint.canvas.mode = "noise";
        profile.fingerprint.canvas.noise = 0.001 + random.nextDouble(0.004);
        profile.fingerprint.audio.mode = "noise";
        profile.fingerprint.audio.noise = 0.001 + random.nextDouble(0.004);
        profile.fingerprint.clientRects.mode = "noise";
        profile.fingerprint.clientRects.noise = 0.01 + random.nextDouble(0.04);
        // Diversidade precisa mascarar as strings e individualizar readPixels. Rebaixar para
        // "spoof" manteria todos os perfis da mesma GPU/container com o mesmo hash WebGL.
        profile.fingerprint.webgl.mode = "spoof_noise";
    }

    static void clearDiversity(SdkProfile profile) {
        profile.fingerprint.canvas.mode = "real";
        profile.fingerprint.canvas.noise = 0;
        profile.fingerprint.audio.mode = "real";
        profile.fingerprint.audio.noise = 0;
        profile.fingerprint.clientRects.mode = "real";
        profile.fingerprint.clientRects.noise = 0;
        if ("spoof_noise".equals(profile.fingerprint.webgl.mode)) {
            profile.fingerprint.webgl.mode = "spoof";
        }
    }

    private static String normalizeOs(String os) {
        if ("mac".equals(os)) {
            return "macos";
        }
        if (os.startsWith("win")) {
            return "windows";
        }
        if ("linux-headless".equals(os)) {
            return "linux";
        }
        return os;
    }

    static long stableSeed(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8));
            long seed = 0;
            for (int index = 0; index < Long.BYTES; index++) {
                seed = (seed << Byte.SIZE) | (digest[index] & 0xffL);
            }
            return seed & Long.MAX_VALUE;
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is unavailable", e);
        }
    }

    private static int fingerprintNoiseSeed(long seed) {
        int value = Long.hashCode(seed);
        return value == 0 ? 1 : value;
    }
}
