package io.github.selenium.javaDriverless.types;

import java.util.List;

abstract class BrowserFingerprintProfileBuilder<T extends BrowserFingerprintProfileBuilder<T>> {
    protected final BrowserFingerprintProfile p;

    BrowserFingerprintProfileBuilder(BrowserFingerprintProfile profile) {
        this.p = profile;
    }

    protected abstract T self();

    public T name(String name) { p.name = name; return self(); }
    public T runtimePolicy(String runtimePolicy) { p.runtimePolicy = runtimePolicy; return self(); }
    public T browserVersion(String v) { p.browserVersion = v; return self(); }
    public T locale(String locale) { p.locale = locale; return self(); }
    public T timezone(String tz) { p.timezoneId = tz; return self(); }

    public T platform(String navPlatform, String uaPlatform, String version) {
        p.platform = navPlatform;
        p.uaPlatform = uaPlatform;
        p.platformVersion = version;
        return self();
    }

    public T architecture(String arch, String bitness) {
        p.architecture = arch;
        p.bitness = bitness;
        return self();
    }

    public T mobile(boolean mobile, String model) {
        p.mobile = mobile;
        p.model = model;
        return self();
    }

    public T formFactors(String... values) {
        p.formFactors = values == null ? List.of() : List.of(values);
        return self();
    }

    public T languages(String... langs) { p.languages = List.of(langs); return self(); }

    public T hardware(int cores, int memoryGb, int maxTouch) {
        p.hardwareConcurrency = cores;
        p.deviceMemory = memoryGb;
        p.maxTouchPoints = maxTouch;
        return self();
    }

    public T vendor(String vendor) { p.vendor = vendor; return self(); }
    public T doNotTrack(boolean dnt) { p.doNotTrack = dnt; return self(); }
    public T colorScheme(String colorScheme) { p.colorScheme = colorScheme; return self(); }
    public T mediaPreferences(String reducedMotion, String reducedTransparency,
                              String contrast, String forcedColors) {
        p.prefersReducedMotion = reducedMotion;
        p.prefersReducedTransparency = reducedTransparency;
        p.prefersContrast = contrast;
        p.forcedColors = forcedColors;
        return self();
    }

    public T screen(int width, int height) {
        p.screen.mode = "spoof";
        p.screen.width = width;
        p.screen.height = height;
        p.screen.availWidth = width;
        p.screen.availHeight = height - 40;
        return self();
    }

    public T screen(int width, int height, int colorDepth, double dpr) {
        screen(width, height);
        p.screen.colorDepth = colorDepth;
        p.screen.pixelDepth = colorDepth;
        p.screen.deviceScaleFactor = dpr;
        return self();
    }

    public T screenMode(String mode) { p.screen.mode = mode; return self(); }

    public T webgl(String mode, String vendor, String renderer) {
        p.webgl.mode = mode;
        p.webgl.vendor = vendor;
        p.webgl.renderer = renderer;
        p.webgl.unmaskedVendor = vendor;
        p.webgl.unmaskedRenderer = renderer;
        return self();
    }

    public T webrtc(String mode, boolean protectLocal, String publicIp) {
        p.webRtc.mode = mode;
        p.webRtc.protectLocalIps = protectLocal;
        p.webRtc.publicIp = publicIp;
        return self();
    }

    public T webrtcDisableUdp(boolean disable) { p.webRtc.disableUdp = disable; return self(); }

    public T geolocation(double lat, double lng) {
        p.geolocation.mode = "override";
        p.geolocation.latitude = lat;
        p.geolocation.longitude = lng;
        p.geolocation.accuracy = 100.0;
        return self();
    }

    public T geolocation(double lat, double lng, double accuracy) {
        geolocation(lat, lng);
        p.geolocation.accuracy = accuracy;
        return self();
    }

    public T mediaDevices(int audioIn, int audioOut, int videoIn) {
        p.mediaDevices.mode = "spoof";
        p.mediaDevices.audioInputs = audioIn;
        p.mediaDevices.audioOutputs = audioOut;
        p.mediaDevices.videoInputs = videoIn;
        return self();
    }

    public T audioNoise(double noise) { p.audio.mode = "noise"; p.audio.noise = noise; return self(); }
    public T canvasNoise(double noise) { p.canvas.mode = "noise"; p.canvas.noise = noise; return self(); }
    public T clientRectsNoise(double noise) { p.clientRects.mode = "noise"; p.clientRects.noise = noise; return self(); }
    public T fontsBlock() { p.fonts.mode = "block"; return self(); }

    public T fontsAllow(String... families) {
        p.fonts.mode = "filter";
        p.fonts.families = List.of(families);
        return self();
    }

    public T connection(String effectiveType, double downlink, int rtt) {
        p.connection.mode = "spoof";
        p.connection.effectiveType = effectiveType;
        p.connection.downlink = downlink;
        p.connection.rtt = rtt;
        return self();
    }

    public T portsProtect(Integer... ports) {
        p.ports.mode = "protect";
        p.ports.protectedPorts = List.of(ports);
        return self();
    }

    public BrowserFingerprintProfile build() {
        p.applyDefaults();
        return p;
    }
}
