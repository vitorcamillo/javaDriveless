package io.github.selenium.javaDriverless;

import io.github.selenium.javaDriverless.types.BrowserFingerprintProfile;

import java.util.Locale;
import java.util.Map;

/**
 * Capability contract for fingerprint surfaces in the wrapper-free runtime policy.
 *
 * <p>The native transports are deliberately explicit:</p>
 * <ul>
 *   <li>Chrome process/CDP: native UA/UA-CH, language, locale, timezone, media preferences,
 *       hardware concurrency and geolocation;</li>
 *   <li>Chrome/OS pass-through: rendering, storage, fonts, connection, media devices, speech,
 *       desktop touch points, and either the host screen or an explicitly provisioned Xvfb screen
 *       in headful mode;</li>
 *   <li>Chrome prefs/arguments: relay-only WebRTC for {@code altered + disableUdp};</li>
 *   <li>no native transport: JavaScript port-probe protection.</li>
 * </ul>
 *
 * <p>Any mode outside that matrix must fail before Chrome is spawned instead of being silently
 * ignored when page/worker wrappers are intentionally omitted.</p>
 */
final class MinimalCdpCapabilityContract {
    static final String NATIVE_SCREEN_PROVISIONED_ENV =
            "JAVA_DRIVERLESS_NATIVE_SCREEN_PROVISIONED";
    static final String NATIVE_SCREEN_WIDTH_ENV = "JAVA_DRIVERLESS_NATIVE_SCREEN_WIDTH";
    static final String NATIVE_SCREEN_HEIGHT_ENV = "JAVA_DRIVERLESS_NATIVE_SCREEN_HEIGHT";
    static final String NATIVE_SCREEN_AVAIL_WIDTH_ENV =
            "JAVA_DRIVERLESS_NATIVE_SCREEN_AVAIL_WIDTH";
    static final String NATIVE_SCREEN_AVAIL_HEIGHT_ENV =
            "JAVA_DRIVERLESS_NATIVE_SCREEN_AVAIL_HEIGHT";
    static final String NATIVE_SCREEN_COLOR_DEPTH_ENV =
            "JAVA_DRIVERLESS_NATIVE_SCREEN_COLOR_DEPTH";
    static final String NATIVE_SCREEN_PIXEL_DEPTH_ENV =
            "JAVA_DRIVERLESS_NATIVE_SCREEN_PIXEL_DEPTH";
    static final String NATIVE_SCREEN_DPR_ENV = "JAVA_DRIVERLESS_NATIVE_SCREEN_DPR";
    private MinimalCdpCapabilityContract() {
    }

    static void requireSupported(
            BrowserFingerprintProfile profile,
            boolean headless,
            Map<String, String> environment) {
        if (profile == null) {
            throw new IllegalArgumentException("Fingerprint profile must not be null");
        }
        if (!BrowserFingerprintProfile.RUNTIME_POLICY_MINIMAL_CDP.equalsIgnoreCase(
                profile.runtimePolicy == null ? "" : profile.runtimePolicy.trim())) {
            return;
        }
        if (headless) {
            throw new IllegalStateException(
                    "runtimePolicy=minimal-cdp não suporta Chrome --headless: no Chrome 150, "
                            + "o UA nativo expõe HeadlessChrome e --user-agent/override por worker "
                            + "não preserva UA-CH de alta entropia em todos os realms; execute Chrome "
                            + "visível sob Xvfb com scripts/run-visible-xvfb.sh ou use "
                            + "runtimePolicy=legacy-spoof assumindo o spoof sintético");
        }

        requireMode("webgl.mode", profile.webgl == null ? null : profile.webgl.mode, "real");
        requireMode("canvas.mode", profile.canvas == null ? null : profile.canvas.mode, "real");
        requireMode("audio.mode", profile.audio == null ? null : profile.audio.mode, "real");
        requireMode("clientRects.mode",
                profile.clientRects == null ? null : profile.clientRects.mode, "real");
        requireMode("storage.mode", profile.storage == null ? null : profile.storage.mode, "real");
        requireMode("fonts.mode", profile.fonts == null ? null : profile.fonts.mode, "real");
        requireMode("connection.mode",
                profile.connection == null ? null : profile.connection.mode, "real");
        requireMode("mediaDevices.mode",
                profile.mediaDevices == null ? null : profile.mediaDevices.mode, "real");
        requireMode("speech.mode", profile.speech == null ? null : profile.speech.mode,
                "auto", "real");

        requireNativeIdentity(profile);
        if (profile.maxTouchPoints != 0) {
            unsupported("maxTouchPoints", Integer.toString(profile.maxTouchPoints));
        }

        requireScreen(profile.screen, headless, environment);
        requireWebRtc(profile.webRtc);
        requiresGeolocationOverride(profile.geolocation);
        // The legacy `protect` implementation is a page-realm fetch/XHR wrapper. minimal-cdp does
        // not install it and no equivalent native rule currently covers every relevant realm.
        requireMode("ports.mode", profile.ports == null ? null : profile.ports.mode, "real");
    }

    private static void requireScreen(
            BrowserFingerprintProfile.Screen screen,
            boolean headless,
            Map<String, String> environment) {
        String mode = normalize(screen == null ? null : screen.mode);
        if ("real".equals(mode)) {
            return;
        }
        if ("spoof".equals(mode)
                && environment != null
                && "1".equals(environment.get(NATIVE_SCREEN_PROVISIONED_ENV))) {
            requireNativeScreenHandshake(screen, environment);
            return;
        }
        if ("spoof".equals(mode)) {
            throw new IllegalStateException(
                    "runtimePolicy=minimal-cdp exige " + NATIVE_SCREEN_PROVISIONED_ENV
                            + "=1 para screen.mode=spoof em headful; use o launcher Xvfb "
                            + "provisionado ou runtimePolicy=legacy-spoof");
        }
        unsupported("screen.mode", mode);
    }

    private static void requireNativeIdentity(BrowserFingerprintProfile profile) {
        BrowserFingerprintProfile nativeBaseline = BrowserFingerprintProfile.nativeDefaults();
        int nativeHardwareConcurrency = nativeBaseline.hardwareConcurrency;
        if (profile.hardwareConcurrency != nativeHardwareConcurrency) {
            throw new IllegalStateException(
                    "runtimePolicy=minimal-cdp não possui transporte global para hardwareConcurrency="
                            + profile.hardwareConcurrency + "; baseline nativo="
                            + nativeHardwareConcurrency
                            + "; o override CDP é target-local e não cobre todos os workers; "
                            + "use o baseline nativo do host ou runtimePolicy=legacy-spoof");
        }
        int nativeDeviceMemory = nativeBaseline.deviceMemory;
        if (profile.deviceMemory != nativeDeviceMemory) {
            throw new IllegalStateException(
                    "runtimePolicy=minimal-cdp não possui transporte nativo para deviceMemory="
                            + profile.deviceMemory + "; baseline nativo=" + nativeDeviceMemory
                            + "; use o baseline nativo do host ou runtimePolicy=legacy-spoof");
        }
        if (!"Google Inc.".equals(profile.vendor)) {
            String observedVendor = profile.vendor == null || profile.vendor.isBlank()
                    ? "<vazio>" : profile.vendor;
            throw new IllegalStateException(
                    "runtimePolicy=minimal-cdp não possui transporte nativo para vendor="
                            + observedVendor + "; use vendor=Google Inc. ou "
                            + "runtimePolicy=legacy-spoof");
        }
        if (profile.doNotTrack) {
            unsupported("doNotTrack", "true");
        }
    }

    static boolean requiresGeolocationOverride(
            BrowserFingerprintProfile.Geolocation geolocation) {
        String mode = normalize(geolocation == null ? null : geolocation.mode);
        Double latitude = geolocation == null ? null : geolocation.latitude;
        Double longitude = geolocation == null ? null : geolocation.longitude;
        if ("override".equals(mode)) {
            if (latitude == null || longitude == null) {
                throw new IllegalStateException(
                        "runtimePolicy=minimal-cdp exige latitude e longitude para "
                                + "geolocation.mode=override");
            }
            return true;
        }
        if ("real".equals(mode) || "prompt".equals(mode)) {
            if (latitude != null || longitude != null) {
                throw new IllegalStateException(
                        "runtimePolicy=minimal-cdp exige coordenadas ausentes para "
                                + "geolocation.mode=" + mode);
            }
            return false;
        }
        unsupported("geolocation.mode", mode);
        return false;
    }

    private static void requireNativeScreenHandshake(
            BrowserFingerprintProfile.Screen screen, Map<String, String> environment) {
        requireExactInteger(environment, NATIVE_SCREEN_WIDTH_ENV, screen.width);
        requireExactInteger(environment, NATIVE_SCREEN_HEIGHT_ENV, screen.height);
        requireExactInteger(environment, NATIVE_SCREEN_AVAIL_WIDTH_ENV, screen.availWidth);
        requireExactInteger(environment, NATIVE_SCREEN_AVAIL_HEIGHT_ENV, screen.availHeight);
        requireExactInteger(environment, NATIVE_SCREEN_COLOR_DEPTH_ENV, screen.colorDepth);
        requireExactInteger(environment, NATIVE_SCREEN_PIXEL_DEPTH_ENV, screen.pixelDepth);
        requireExactDouble(environment, NATIVE_SCREEN_DPR_ENV, screen.deviceScaleFactor);
    }

    private static void requireExactInteger(
            Map<String, String> environment, String field, int expected) {
        String raw = environment.get(field);
        int observed;
        try {
            observed = Integer.parseInt(raw == null ? "" : raw.trim());
        } catch (NumberFormatException failure) {
            throw nativeScreenMismatch(field, raw, Integer.toString(expected));
        }
        if (observed != expected) {
            throw nativeScreenMismatch(field, raw, Integer.toString(expected));
        }
    }

    private static void requireExactDouble(
            Map<String, String> environment, String field, double expected) {
        String raw = environment.get(field);
        double observed;
        try {
            observed = Double.parseDouble(raw == null ? "" : raw.trim());
        } catch (NumberFormatException failure) {
            throw nativeScreenMismatch(field, raw, Double.toString(expected));
        }
        if (!Double.isFinite(observed) || Double.compare(observed, expected) != 0) {
            throw nativeScreenMismatch(field, raw, Double.toString(expected));
        }
    }

    private static IllegalStateException nativeScreenMismatch(
            String field, String observed, String expected) {
        String display = observed == null || observed.isBlank() ? "<ausente>" : observed;
        return new IllegalStateException(
                "runtimePolicy=minimal-cdp: handshake Xvfb " + field + "=" + display
                        + " não corresponde ao perfil (esperado=" + expected + ")");
    }

    private static void requireWebRtc(BrowserFingerprintProfile.WebRtc webRtc) {
        String mode = normalize(webRtc == null ? null : webRtc.mode);
        boolean disableUdp = webRtc != null && webRtc.disableUdp;
        if (("real".equals(mode) && !disableUdp)
                || ("altered".equals(mode) && disableUdp)) {
            return;
        }
        unsupported("webRtc.mode", mode + ", webRtc.disableUdp=" + disableUdp);
    }

    private static void requireMode(String field, String observedMode, String... supportedModes) {
        String normalized = normalize(observedMode);
        for (String supportedMode : supportedModes) {
            if (supportedMode.equals(normalized)) {
                return;
            }
        }
        unsupported(field, normalized);
    }

    private static String normalize(String value) {
        return value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
    }

    private static void unsupported(String field, String observedValue) {
        throw new IllegalStateException(
                "runtimePolicy=minimal-cdp não possui transporte nativo para "
                        + field + "=" + (observedValue.isEmpty() ? "<vazio>" : observedValue)
                        + "; use um modo nativo suportado ou runtimePolicy=legacy-spoof");
    }
}
