package io.github.selenium.javaDriverless.profiles;

import io.github.selenium.javaDriverless.types.BrowserFingerprintProfile;
import io.github.selenium.javaDriverless.types.MinimalCdpProcessArgumentPolicy;

import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** Single policy boundary shared by persistence and launch adapters. */
public final class PhantomPassProfilePolicy {
    private PhantomPassProfilePolicy() {
    }

    public static PhantomPassPolicyResult evaluateForPersistence(PhantomPassProfile source) {
        return evaluate(source, null, false);
    }

    public static PhantomPassPolicyResult evaluateForLaunch(
            PhantomPassProfile source,
            PhantomPassRuntime runtime) {
        return evaluate(source, runtime, true);
    }

    private static PhantomPassPolicyResult evaluate(
            PhantomPassProfile source,
            PhantomPassRuntime runtime,
            boolean launch) {
        List<PhantomPassPolicyViolation> violations = new ArrayList<>();
        if (source == null || source.profile == null) {
            violations.add(new PhantomPassPolicyViolation(
                    "profile_required", "profile", "non-null PhantomPass profile"));
            return new PhantomPassPolicyResult(null, violations);
        }
        PhantomPassProfile candidate = source.copy();
        if (candidate.runtimeBase == null || !candidate.runtimeBase.isRuntimeBase()) {
            violations.add(new PhantomPassPolicyViolation(
                    "runtime_base_required", "runtimeBase", "windows|linux|docker"));
        }
        try {
            candidate.profile.applyDefaults();
        } catch (IllegalArgumentException invalid) {
            violations.add(new PhantomPassPolicyViolation(
                    "profile_defaults_invalid", "profile", "valid normalized profile"));
        }
        if (violations.stream().noneMatch(v -> "profile_defaults_invalid".equals(v.code()))) {
            evaluatePersistableContract(candidate, violations);
        }
        if (launch && runtime == null) {
            violations.add(new PhantomPassPolicyViolation(
                    "runtime_required", "runtime", "NATIVE_HOST|VIRTUAL_DISPLAY"));
        } else if (launch) {
            evaluateRuntime(candidate, runtime, violations);
        }
        return new PhantomPassPolicyResult(candidate, violations);
    }

    private static void evaluatePersistableContract(
            PhantomPassProfile candidate,
            List<PhantomPassPolicyViolation> violations) {
        SdkProfile profile = candidate.profile;
        BrowserFingerprintProfile fingerprint = profile.fingerprint;
        if (profile.browser.headless) {
            add(violations, "headless_not_supported", "profile.browser.headless", "Chrome visível");
        }
        if (containsHeadless(profile.browser.extraArgs)) {
            add(violations, "headless_not_supported", "profile.browser.extraArgs", "Chrome visível");
        }
        if (hasText(profile.browser.userAgent)) {
            add(violations, "native_user_agent_required", "profile.browser.userAgent", "native Chrome User-Agent");
        }
        if (!BrowserFingerprintProfile.RUNTIME_POLICY_MINIMAL_CDP.equalsIgnoreCase(
                text(fingerprint.runtimePolicy))) {
            add(violations, "runtime_policy_required", "profile.fingerprint.runtimePolicy", "minimal-cdp");
        }
        evaluateIdentity(candidate.runtimeBase, fingerprint, violations);
        requireNativeSurface(violations, "profile.fingerprint.webgl.mode", fingerprint.webgl.mode);
        requireNativeSurface(violations, "profile.fingerprint.mediaDevices.mode", fingerprint.mediaDevices.mode);
        requireNativeSurface(violations, "profile.fingerprint.audio.mode", fingerprint.audio.mode);
        requireNativeSurface(violations, "profile.fingerprint.canvas.mode", fingerprint.canvas.mode);
        requireNativeSurface(violations, "profile.fingerprint.clientRects.mode", fingerprint.clientRects.mode);
        requireNativeSurface(violations, "profile.fingerprint.storage.mode", fingerprint.storage.mode);
        requireNativeSurface(violations, "profile.fingerprint.fonts.mode", fingerprint.fonts.mode);
        requireNativeSurface(violations, "profile.fingerprint.ports.mode", fingerprint.ports.mode);
        requireNativeSurface(violations, "profile.fingerprint.connection.mode", fingerprint.connection.mode);
        if (!("auto".equalsIgnoreCase(fingerprint.speech.mode)
                || "real".equalsIgnoreCase(fingerprint.speech.mode))) {
            add(violations, "native_surface_required", "profile.fingerprint.speech.mode", "auto|real");
        }
        if (!"altered".equalsIgnoreCase(fingerprint.webRtc.mode)
                || !fingerprint.webRtc.protectLocalIps
                || !fingerprint.webRtc.disableUdp) {
            add(violations, "webrtc_policy_required", "profile.fingerprint.webRtc",
                    "altered + protectLocalIps + disableUdp");
        }
        evaluateProcessArguments(profile.browser.extraArgs, violations);
        requireAbsolutePath(profile.browser.binaryPath, "profile.browser.binaryPath", violations);
        requireAbsolutePath(profile.storage.userDataDir, "profile.storage.userDataDir", violations);
        requireAbsolutePath(profile.storage.downloadsDir, "profile.storage.downloadsDir", violations);
        evaluateScreen(candidate.runtimeBase, profile, violations);
        evaluateNativeLinuxMedia(candidate.runtimeBase, fingerprint, violations);
    }

    private static void evaluateIdentity(
            SdkProfilePreset runtimeBase,
            BrowserFingerprintProfile fingerprint,
            List<PhantomPassPolicyViolation> violations) {
        if (runtimeBase == null || !runtimeBase.isRuntimeBase()) return;
        String expectedUa = runtimeBase == SdkProfilePreset.WINDOWS ? "Windows" : "Linux";
        String expectedPlatform = runtimeBase == SdkProfilePreset.WINDOWS ? "Win32" : "Linux x86_64";
        if (!expectedUa.equals(fingerprint.uaPlatform)
                || !expectedPlatform.equals(fingerprint.platform)) {
            String expected = "runtimeBase=" + runtimeBase.id() + ": uaPlatform=" + expectedUa
                    + ", platform=" + expectedPlatform
                    + (runtimeBase == SdkProfilePreset.WINDOWS ? "" : "; Windows is unsupported");
            add(violations, "runtime_identity_mismatch", "profile.fingerprint.platform", expected);
        }
        if (!"x86".equals(fingerprint.architecture)
                || !"64".equals(fingerprint.bitness)
                || fingerprint.mobile
                || hasText(fingerprint.model)) {
            add(violations, "desktop_identity_required", "profile.fingerprint.identity", "desktop x86_64");
        }
    }

    private static void evaluateScreen(
            SdkProfilePreset runtimeBase,
            SdkProfile profile,
            List<PhantomPassPolicyViolation> violations) {
        if (runtimeBase == null || !runtimeBase.isRuntimeBase()) return;
        String mode = text(profile.fingerprint.screen.mode).toLowerCase(Locale.ROOT);
        if (runtimeBase == SdkProfilePreset.WINDOWS && !"real".equals(mode)) {
            add(violations, "screen_mode_required", "profile.fingerprint.screen.mode", "real");
            return;
        }
        if (runtimeBase == SdkProfilePreset.DOCKER && !"spoof".equals(mode)) {
            add(violations, "screen_mode_required", "profile.fingerprint.screen.mode", "spoof");
            return;
        }
        if (!("real".equals(mode) || "spoof".equals(mode))) {
            add(violations, "screen_mode_required", "profile.fingerprint.screen.mode", "real|spoof");
            return;
        }
        if ("spoof".equals(mode)) {
            if (!validGeometry(geometry(profile.fingerprint.screen))) {
                add(violations, "screen_geometry_invalid", "profile.fingerprint.screen", "positive coherent geometry");
            }
            if (!profile.runtimeSurfaces.contains("xvfb-visible")) {
                add(violations, "virtual_display_surface_required", "profile.runtimeSurfaces", "xvfb-visible");
            }
        }
    }

    private static void evaluateNativeLinuxMedia(
            SdkProfilePreset runtimeBase,
            BrowserFingerprintProfile fingerprint,
            List<PhantomPassPolicyViolation> violations) {
        if (runtimeBase != SdkProfilePreset.LINUX && runtimeBase != SdkProfilePreset.DOCKER) return;
        requireExactMedia(violations, "prefersReducedMotion", fingerprint.prefersReducedMotion, "no-preference");
        requireExactMedia(violations, "prefersReducedTransparency", fingerprint.prefersReducedTransparency, "no-preference");
        requireExactMedia(violations, "prefersContrast", fingerprint.prefersContrast, "no-preference");
        requireExactMedia(violations, "forcedColors", fingerprint.forcedColors, "none");
    }

    private static void evaluateRuntime(
            PhantomPassProfile candidate,
            PhantomPassRuntime runtime,
            List<PhantomPassPolicyViolation> violations) {
        if (runtime.kind() == PhantomPassRuntime.Kind.NATIVE_HOST) {
            if (candidate.runtimeBase == SdkProfilePreset.DOCKER) {
                add(violations, "runtime_kind_mismatch", "runtime.kind", "VIRTUAL_DISPLAY");
            }
            if (!"real".equalsIgnoreCase(candidate.profile.fingerprint.screen.mode)) {
                add(violations, "runtime_screen_mode_mismatch", "profile.fingerprint.screen.mode", "real");
            }
            return;
        }
        if (candidate.runtimeBase == SdkProfilePreset.WINDOWS) {
            add(violations, "runtime_kind_mismatch", "runtime.kind", "NATIVE_HOST");
        }
        if (!"spoof".equalsIgnoreCase(candidate.profile.fingerprint.screen.mode)) {
            add(violations, "runtime_screen_mode_mismatch", "profile.fingerprint.screen.mode", "spoof");
        }
        if (runtime.geometry() == null) {
            add(violations, "runtime_geometry_required", "runtime.geometry", "exact virtual-display geometry");
            return;
        }
        if (!validGeometry(runtime.geometry())) {
            add(violations, "runtime_geometry_invalid", "runtime.geometry", "positive coherent geometry");
        } else if (!sameGeometry(geometry(candidate.profile.fingerprint.screen), runtime.geometry())) {
            add(violations, "runtime_geometry_mismatch", "fingerprint.screen", "exact virtual-display geometry");
        }
    }

    private static void evaluateProcessArguments(
            List<String> arguments,
            List<PhantomPassPolicyViolation> violations) {
        try {
            MinimalCdpProcessArgumentPolicy.requireSafe(arguments);
        } catch (IllegalArgumentException unsafe) {
            add(violations, "unsafe_process_argument", "profile.browser.extraArgs", "minimal-cdp safe arguments");
        }
        if (arguments != null && arguments.stream()
                .filter(java.util.Objects::nonNull)
                .map(value -> value.trim().toLowerCase(Locale.ROOT))
                .anyMatch(value -> value.contains("swiftshader")
                        || value.equals("--disable-gpu") || value.startsWith("--disable-gpu="))) {
            add(violations, "synthetic_graphics_rejected", "profile.browser.extraArgs", "minimal-cdp native GPU");
        }
    }

    private static void requireNativeSurface(
            List<PhantomPassPolicyViolation> violations,
            String field,
            String value) {
        if (!"real".equalsIgnoreCase(text(value))) {
            add(violations, "native_surface_required", field, "real");
        }
    }

    private static void requireExactMedia(
            List<PhantomPassPolicyViolation> violations,
            String name,
            String value,
            String expected) {
        if (!expected.equalsIgnoreCase(text(value))) {
            add(violations, "native_media_required", "profile.fingerprint." + name, expected);
        }
    }

    private static void requireAbsolutePath(
            String value,
            String field,
            List<PhantomPassPolicyViolation> violations) {
        if (!hasText(value)) return;
        try {
            if (!Path.of(value).isAbsolute()) {
                add(violations, "absolute_path_required", field, "absolute path");
            }
        } catch (InvalidPathException invalid) {
            add(violations, "absolute_path_required", field, "absolute path");
        }
    }

    private static PhantomPassRuntime.Geometry geometry(BrowserFingerprintProfile.Screen screen) {
        return new PhantomPassRuntime.Geometry(
                screen.width, screen.height, screen.availWidth, screen.availHeight,
                screen.colorDepth, screen.pixelDepth, screen.deviceScaleFactor);
    }

    private static boolean validGeometry(PhantomPassRuntime.Geometry geometry) {
        return geometry != null
                && geometry.width() > 0 && geometry.height() > 0
                && geometry.availWidth() > 0 && geometry.availHeight() > 0
                && geometry.availWidth() <= geometry.width()
                && geometry.availHeight() <= geometry.height()
                && geometry.colorDepth() > 0 && geometry.pixelDepth() > 0
                && Double.isFinite(geometry.deviceScaleFactor())
                && geometry.deviceScaleFactor() > 0;
    }

    private static boolean sameGeometry(
            PhantomPassRuntime.Geometry left,
            PhantomPassRuntime.Geometry right) {
        return left.width() == right.width()
                && left.height() == right.height()
                && left.availWidth() == right.availWidth()
                && left.availHeight() == right.availHeight()
                && left.colorDepth() == right.colorDepth()
                && left.pixelDepth() == right.pixelDepth()
                && Double.compare(left.deviceScaleFactor(), right.deviceScaleFactor()) == 0;
    }

    private static boolean containsHeadless(List<String> arguments) {
        return arguments != null && arguments.stream()
                .filter(java.util.Objects::nonNull)
                .map(value -> value.trim().toLowerCase(Locale.ROOT))
                .anyMatch(value -> value.equals("--headless") || value.startsWith("--headless="));
    }

    private static void add(
            List<PhantomPassPolicyViolation> violations,
            String code,
            String field,
            String expected) {
        PhantomPassPolicyViolation violation = new PhantomPassPolicyViolation(code, field, expected);
        if (!violations.contains(violation)) violations.add(violation);
    }

    private static boolean hasText(String value) {
        return value != null && !value.isBlank();
    }

    private static String text(String value) {
        return value == null ? "" : value.trim();
    }
}
