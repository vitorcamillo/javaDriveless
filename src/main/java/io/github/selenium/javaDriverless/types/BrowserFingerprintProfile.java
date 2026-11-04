package io.github.selenium.javaDriverless.types;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

public class BrowserFingerprintProfile {
    public static final String RUNTIME_POLICY_LEGACY_SPOOF = "legacy-spoof";
    public static final String RUNTIME_POLICY_MINIMAL_CDP = "minimal-cdp";

    /**
     * Política de execução das superfícies de fingerprint.
     *
     * <p>{@code legacy-spoof} mantém os wrappers JavaScript históricos. {@code minimal-cdp}
     * deixa a identidade observável a cargo dos overrides CDP e das configurações nativas do
     * Chrome, sem instalar wrappers de superfície nos realms da página.</p>
     */
    public String runtimePolicy = RUNTIME_POLICY_LEGACY_SPOOF;
    public String name = "java-driverless-clean";
    public String browserVersion = "";
    public String locale = "en-US";
    public String timezoneId = "";
    public String platform = "Linux x86_64";
    public String uaPlatform = "Linux";
    public String platformVersion = "";
    public String architecture = "x86";
    public String bitness = "64";
    public boolean mobile = false;
    public String model = "";
    public boolean wow64 = false;
    /** Form factors UA-CH explícitos; vazio é normalizado para Desktop/Mobile pelo perfil. */
    public List<String> formFactors = List.of();
    public List<String> languages = List.of("en-US", "en");
    public int hardwareConcurrency = 8;
    public int deviceMemory = 8;
    public int maxTouchPoints = 0;
    // Semente determinística usada somente pelos wrappers sinteticos de legacy-spoof.
    // minimal-cdp preserva canvas/audio/WebGL nativos e, portanto, nao promete diversidade dessas
    // superficies por noiseSeed. Default 1337 mantem compatibilidade com o runtime legado.
    public int noiseSeed = 1337;
    public String vendor = "Google Inc.";
    public boolean doNotTrack = false;
    /** Preferência de cor pertencente ao perfil; nunca herda silenciosamente o tema do host. */
    public String colorScheme = "light";
    public String prefersReducedMotion = "no-preference";
    public String prefersReducedTransparency = "no-preference";
    public String prefersContrast = "no-preference";
    public String forcedColors = "none";
    public Screen screen = new Screen();
    public WebGl webgl = new WebGl();
    public WebRtc webRtc = new WebRtc();
    public Geolocation geolocation = new Geolocation();
    public Permissions permissions = new Permissions();
    public MediaDevices mediaDevices = new MediaDevices();
    public Speech speech = new Speech();
    public Audio audio = new Audio();
    public Canvas canvas = new Canvas();
    public ClientRects clientRects = new ClientRects();
    public Storage storage = new Storage();
    public Fonts fonts = new Fonts();
    public Ports ports = new Ports();
    public Connection connection = new Connection();

    public static BrowserFingerprintProfile fromJson(Path path) throws IOException {
        BrowserFingerprintProfile profile = BrowserFingerprintJsonCodec.read(path);
        profile.applyDefaults();
        return profile;
    }

    public static BrowserFingerprintProfile fromEnvironment() {
        return BrowserFingerprintDefaults.fromEnvironment();
    }

    public static BrowserFingerprintProfile nativeDefaults() {
        return BrowserFingerprintDefaults.nativeDefaults();
    }

    public Map<String, Object> toUserAgentMetadata(String majorVersion) {
        applyDefaults();
        return BrowserFingerprintUserAgentMetadata.from(this, majorVersion);
    }

    public void applyDefaults() {
        BrowserFingerprintProfileDefaults.applyTo(this);
    }

    public void toJson(Path path) throws IOException {
        BrowserFingerprintJsonCodec.write(path, this);
    }

    public String toJsonString() {
        try {
            return BrowserFingerprintJsonCodec.writeString(this);
        } catch (Exception e) {
            throw new RuntimeException("Falha ao serializar BrowserFingerprintProfile", e);
        }
    }

    /**
     * Creates an independent in-memory copy, including every nested fingerprint surface.
     *
     * @return a deep copy that can be safely assigned to another browser session
     */
    public BrowserFingerprintProfile copy() {
        return BrowserFingerprintJsonCodec.copy(this);
    }

    public static Builder builder() {
        return new Builder();
    }

    public static class Screen extends BrowserFingerprintScreen {}
    public static class WebGl extends BrowserFingerprintWebGl {}
    public static class WebRtc extends BrowserFingerprintWebRtc {}
    public static class Geolocation extends BrowserFingerprintGeolocation {}
    public static class Permissions extends BrowserFingerprintPermissions {}
    public static class MediaDevices extends BrowserFingerprintMediaDevices {}
    public static class Speech extends BrowserFingerprintSpeech {}
    public static class Voice extends BrowserFingerprintVoice {}
    public static class Audio extends BrowserFingerprintAudio {}
    public static class Canvas extends BrowserFingerprintCanvas {}
    public static class ClientRects extends BrowserFingerprintClientRects {}
    public static class Storage extends BrowserFingerprintStorage {}
    public static class Fonts extends BrowserFingerprintFonts {}
    public static class Ports extends BrowserFingerprintPorts {}
    public static class Connection extends BrowserFingerprintConnection {}

    public static class Builder extends BrowserFingerprintProfileBuilder<Builder> {
        public Builder() {
            super(new BrowserFingerprintProfile());
        }

        @Override
        protected Builder self() {
            return this;
        }

        public Builder name(String name) { return super.name(name); }
        public Builder runtimePolicy(String runtimePolicy) { return super.runtimePolicy(runtimePolicy); }
        public Builder browserVersion(String v) { return super.browserVersion(v); }
        public Builder locale(String locale) { return super.locale(locale); }
        public Builder timezone(String tz) { return super.timezone(tz); }
        public Builder platform(String navPlatform, String uaPlatform, String version) {
            return super.platform(navPlatform, uaPlatform, version);
        }
        public Builder architecture(String arch, String bitness) { return super.architecture(arch, bitness); }
        public Builder mobile(boolean mobile, String model) { return super.mobile(mobile, model); }
        public Builder formFactors(String... values) { return super.formFactors(values); }
        public Builder languages(String... langs) { return super.languages(langs); }
        public Builder hardware(int cores, int memoryGb, int maxTouch) { return super.hardware(cores, memoryGb, maxTouch); }
        public Builder vendor(String vendor) { return super.vendor(vendor); }
        public Builder doNotTrack(boolean dnt) { return super.doNotTrack(dnt); }
        public Builder colorScheme(String colorScheme) { return super.colorScheme(colorScheme); }
        public Builder mediaPreferences(String reducedMotion, String reducedTransparency,
                                        String contrast, String forcedColors) {
            return super.mediaPreferences(reducedMotion, reducedTransparency, contrast, forcedColors);
        }
        public Builder screen(int width, int height) { return super.screen(width, height); }
        public Builder screen(int width, int height, int colorDepth, double dpr) {
            return super.screen(width, height, colorDepth, dpr);
        }
        public Builder screenMode(String mode) { return super.screenMode(mode); }
        public Builder webgl(String mode, String vendor, String renderer) { return super.webgl(mode, vendor, renderer); }
        public Builder webrtc(String mode, boolean protectLocal, String publicIp) {
            return super.webrtc(mode, protectLocal, publicIp);
        }
        public Builder webrtcDisableUdp(boolean disable) { return super.webrtcDisableUdp(disable); }
        public Builder geolocation(double lat, double lng) { return super.geolocation(lat, lng); }
        public Builder geolocation(double lat, double lng, double accuracy) { return super.geolocation(lat, lng, accuracy); }
        public Builder mediaDevices(int audioIn, int audioOut, int videoIn) {
            return super.mediaDevices(audioIn, audioOut, videoIn);
        }
        public Builder audioNoise(double noise) { return super.audioNoise(noise); }
        public Builder canvasNoise(double noise) { return super.canvasNoise(noise); }
        public Builder clientRectsNoise(double noise) { return super.clientRectsNoise(noise); }
        public Builder fontsBlock() { return super.fontsBlock(); }
        public Builder fontsAllow(String... families) { return super.fontsAllow(families); }
        public Builder connection(String effectiveType, double downlink, int rtt) {
            return super.connection(effectiveType, downlink, rtt);
        }
        public Builder portsProtect(Integer... ports) { return super.portsProtect(ports); }
    }

    public static BrowserFingerprintProfile usWindows10() { return BrowserFingerprintProfiles.usWindows10(); }
    public static BrowserFingerprintProfile brLinux() { return BrowserFingerprintProfiles.brLinux(); }
    public static BrowserFingerprintProfile euMacOS() { return BrowserFingerprintProfiles.euMacOS(); }
    public static BrowserFingerprintProfile usWindows11() { return BrowserFingerprintProfiles.usWindows11(); }
    public static BrowserFingerprintProfile deMacOS() { return BrowserFingerprintProfiles.deMacOS(); }
    public static BrowserFingerprintProfile jpWindows() { return BrowserFingerprintProfiles.jpWindows(); }
    public static BrowserFingerprintProfile brWindows() { return BrowserFingerprintProfiles.brWindows(); }
    public static BrowserFingerprintProfile randomProfile(String os) { return BrowserFingerprintProfiles.randomProfile(os); }
    /** Perfil sintético determinístico: o mesmo seed reproduz o mesmo device. */
    public static BrowserFingerprintProfile randomProfile(String os, long seed) {
        return BrowserFingerprintProfiles.randomProfile(os, seed);
    }
    public static Builder forCountry(String countryCode) { return BrowserFingerprintProfiles.forCountry(countryCode); }
    public static BrowserFingerprintProfile randomForCountry(String countryCode, String os) {
        return BrowserFingerprintProfiles.randomForCountry(countryCode, os);
    }
    /** Determinístico por seed numérico (mesmo seed → mesmo device + geo do país). */
    public static BrowserFingerprintProfile randomForCountry(String countryCode, String os, long seed) {
        return BrowserFingerprintProfiles.randomForCountry(countryCode, os, seed);
    }
    /** Determinístico por chave estável (ex.: id da conta) → device fixo por conta. */
    public static BrowserFingerprintProfile randomForCountry(String countryCode, String os, String seedKey) {
        return BrowserFingerprintProfiles.randomForCountry(countryCode, os, seedKey);
    }
    public static List<String> supportedCountries() { return BrowserFingerprintProfiles.supportedCountries(); }
    /** Fontes opcionais escondidas para um noiseSeed (modo "subset"). O worker sobrescreve o
     *  noiseSeed por conta e re-deriva daqui para manter fonts coerente com canvas/webgl. */
    public static List<String> hiddenFontsFor(int noiseSeed) { return BrowserFingerprintProfiles.hiddenFontsFor(noiseSeed); }

    /**
     * Perfil hardened por TIPO DE SITE + país/OS, determinístico por conta (seedKey).
     * Parte do perfil base hardened ({@link #randomForCountry}) e aplica ajustes específicos do
     * site (ligar/desligar superfícies) conforme o catálogo. Ver {@link BrowserFingerprintSiteProfiles}.
     *
     * @param siteType  tipo de site (ex.: "default", "kambi", "altenar", "obg", "betano")
     * @param countryCode país (ex.: "BR")
     * @param os        sistema ("linux", "windows", "macos")
     * @param seedKey   chave estável por conta (ex.: id/email) → device fixo por conta
     */
    public static BrowserFingerprintProfile forSite(String siteType, String countryCode, String os, String seedKey) {
        return BrowserFingerprintSiteProfiles.forSite(siteType, countryCode, os, seedKey);
    }

    /** Tipos de site suportados pelo catálogo {@link #forSite}. */
    public static java.util.Set<String> supportedSites() {
        return BrowserFingerprintSiteProfiles.supportedSites();
    }
}
