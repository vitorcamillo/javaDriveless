package io.github.selenium.javaDriverless.types;

import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.ThreadLocalRandom;

final class BrowserFingerprintProfiles {
    private BrowserFingerprintProfiles() {}

    static BrowserFingerprintProfile usWindows10() {
        return BrowserFingerprintProfile.builder()
                .name("us-windows-10")
                .locale("en-US")
                .timezone("America/New_York")
                .platform("Win32", "Windows", "10.0.0")
                .architecture("x86", "64")
                .languages("en-US", "en")
                .hardware(8, 8, 0)
                .screen(1920, 1080)
                .webgl("spoof_noise","Google Inc. (NVIDIA)", "ANGLE (NVIDIA, NVIDIA GeForce GTX 1660 SUPER/PCIe/SSE2, OpenGL 4.5.0)")
                .webrtc("altered", true, "")
                .geolocation(40.7128, -74.0060)
                .connection("4g", 10, 50)
                .build();
    }

    static BrowserFingerprintProfile brLinux() {
        return BrowserFingerprintProfile.builder()
                .name("br-linux")
                .locale("pt-BR")
                .timezone("America/Sao_Paulo")
                .platform("Linux x86_64", "Linux", "")
                .architecture("x86", "64")
                .languages("pt-BR", "pt", "en-US", "en")
                .hardware(8, 8, 0)
                .screen(1920, 1080)
                .webgl("spoof_noise","Google Inc. (NVIDIA Corporation)", "ANGLE (NVIDIA Corporation, NVIDIA GeForce GTX 1650/PCIe/SSE2, OpenGL 4.5.0)")
                .webrtc("altered", true, "")
                .geolocation(-23.5505, -46.6333)
                .connection("4g", 10, 50)
                .build();
    }

    static BrowserFingerprintProfile euMacOS() {
        return BrowserFingerprintProfile.builder()
                .name("eu-macos")
                .locale("en-GB")
                .timezone("Europe/London")
                .platform("MacIntel", "macOS", "14.5.0")
                .architecture("arm", "64")
                .languages("en-GB", "en")
                .hardware(10, 16, 0)
                .screen(2560, 1440, 30, 2)
                .webgl("spoof_noise","Google Inc. (Apple)", "ANGLE (Apple, ANGLE Metal Renderer: Apple M2 Pro, Unspecified Version)")
                .webrtc("altered", true, "")
                .geolocation(51.5074, -0.1278)
                .connection("4g", 15, 30)
                .build();
    }

    static BrowserFingerprintProfile usWindows11() {
        return BrowserFingerprintProfile.builder()
                .name("us-windows-11")
                .locale("en-US")
                .timezone("America/Los_Angeles")
                .platform("Win32", "Windows", "15.0.0")
                .architecture("x86", "64")
                .languages("en-US", "en")
                .hardware(12, 16, 0)
                .screen(2560, 1440)
                .webgl("spoof_noise","Google Inc. (NVIDIA)", "ANGLE (NVIDIA, NVIDIA GeForce RTX 4060/PCIe/SSE2, OpenGL 4.5.0)")
                .webrtc("altered", true, "")
                .geolocation(37.7749, -122.4194)
                .connection("4g", 15, 30)
                .build();
    }

    static BrowserFingerprintProfile deMacOS() {
        return BrowserFingerprintProfile.builder()
                .name("de-macos")
                .locale("de-DE")
                .timezone("Europe/Berlin")
                .platform("MacIntel", "macOS", "14.5.0")
                .architecture("arm", "64")
                .languages("de-DE", "de", "en")
                .hardware(8, 16, 0)
                .screen(2560, 1440, 30, 2)
                .webgl("spoof_noise","Google Inc. (Apple)", "ANGLE (Apple, ANGLE Metal Renderer: Apple M3, Unspecified Version)")
                .webrtc("altered", true, "")
                .geolocation(52.5200, 13.4050)
                .connection("4g", 20, 25)
                .build();
    }

    static BrowserFingerprintProfile jpWindows() {
        return BrowserFingerprintProfile.builder()
                .name("jp-windows")
                .locale("ja-JP")
                .timezone("Asia/Tokyo")
                .platform("Win32", "Windows", "10.0.0")
                .architecture("x86", "64")
                .languages("ja-JP", "ja", "en")
                .hardware(8, 16, 0)
                .screen(1920, 1080)
                .webgl("spoof_noise","Google Inc. (NVIDIA)", "ANGLE (NVIDIA, NVIDIA GeForce RTX 3070/PCIe/SSE2, OpenGL 4.5.0)")
                .webrtc("altered", true, "")
                .geolocation(35.6762, 139.6503)
                .connection("4g", 20, 20)
                .build();
    }

    static BrowserFingerprintProfile brWindows() {
        return BrowserFingerprintProfile.builder()
                .name("br-windows")
                .locale("pt-BR")
                .timezone("America/Sao_Paulo")
                .platform("Win32", "Windows", "10.0.0")
                .architecture("x86", "64")
                .languages("pt-BR", "pt", "en-US", "en")
                .hardware(4, 8, 0)
                .screen(1366, 768)
                .webgl("spoof_noise","Google Inc. (Intel)", "ANGLE (Intel, Intel(R) UHD Graphics 620 Direct3D11 vs_5_0 ps_5_0, D3D11)")
                .webrtc("altered", true, "")
                .geolocation(-23.5505, -46.6333)
                .connection("4g", 5, 80)
                .build();
    }

    private static final String[][] WEBGL_WINDOWS = {
            {"Google Inc. (NVIDIA)", "ANGLE (NVIDIA, NVIDIA GeForce GTX 1660 SUPER/PCIe/SSE2, OpenGL 4.5.0)"},
            {"Google Inc. (NVIDIA)", "ANGLE (NVIDIA, NVIDIA GeForce RTX 3060/PCIe/SSE2, OpenGL 4.5.0)"},
            {"Google Inc. (NVIDIA)", "ANGLE (NVIDIA, NVIDIA GeForce RTX 4060/PCIe/SSE2, OpenGL 4.5.0)"},
            {"Google Inc. (NVIDIA)", "ANGLE (NVIDIA, NVIDIA GeForce RTX 3070/PCIe/SSE2, OpenGL 4.5.0)"},
            {"Google Inc. (NVIDIA)", "ANGLE (NVIDIA, NVIDIA GeForce GTX 1050 Ti/PCIe/SSE2, OpenGL 4.5.0)"},
            {"Google Inc. (AMD)", "ANGLE (AMD, AMD Radeon RX 580 Direct3D11 vs_5_0 ps_5_0, D3D11)"},
            {"Google Inc. (AMD)", "ANGLE (AMD, AMD Radeon RX 6700 XT Direct3D11 vs_5_0 ps_5_0, D3D11)"},
            {"Google Inc. (Intel)", "ANGLE (Intel, Intel(R) UHD Graphics 630 Direct3D11 vs_5_0 ps_5_0, D3D11)"},
            {"Google Inc. (Intel)", "ANGLE (Intel, Intel(R) UHD Graphics 620 Direct3D11 vs_5_0 ps_5_0, D3D11)"},
            {"Google Inc. (Intel)", "ANGLE (Intel, Intel(R) Iris(R) Xe Graphics Direct3D11 vs_5_0 ps_5_0, D3D11)"}
    };

    // Pool amplo de GPUs reais de Linux (NVIDIA driver proprietario + AMD/Intel Mesa). Quanto maior
    // o pool, menor a colisao de renderer entre contas (campo grosso que sai no fingerprint). Strings
    // em formatos reais por vendor/driver.
    private static final String[][] WEBGL_LINUX = {
            {"Google Inc. (NVIDIA Corporation)", "ANGLE (NVIDIA Corporation, NVIDIA GeForce RTX 3060/PCIe/SSE2, OpenGL 4.5.0)"},
            {"Google Inc. (NVIDIA Corporation)", "ANGLE (NVIDIA Corporation, NVIDIA GeForce RTX 3060 Ti/PCIe/SSE2, OpenGL 4.5.0)"},
            {"Google Inc. (NVIDIA Corporation)", "ANGLE (NVIDIA Corporation, NVIDIA GeForce RTX 3070/PCIe/SSE2, OpenGL 4.5.0)"},
            {"Google Inc. (NVIDIA Corporation)", "ANGLE (NVIDIA Corporation, NVIDIA GeForce RTX 3080/PCIe/SSE2, OpenGL 4.5.0)"},
            {"Google Inc. (NVIDIA Corporation)", "ANGLE (NVIDIA Corporation, NVIDIA GeForce RTX 4060/PCIe/SSE2, OpenGL 4.5.0)"},
            {"Google Inc. (NVIDIA Corporation)", "ANGLE (NVIDIA Corporation, NVIDIA GeForce RTX 4070/PCIe/SSE2, OpenGL 4.5.0)"},
            {"Google Inc. (NVIDIA Corporation)", "ANGLE (NVIDIA Corporation, NVIDIA GeForce GTX 1660 SUPER/PCIe/SSE2, OpenGL 4.5.0)"},
            {"Google Inc. (NVIDIA Corporation)", "ANGLE (NVIDIA Corporation, NVIDIA GeForce GTX 1650/PCIe/SSE2, OpenGL 4.5.0)"},
            {"Google Inc. (NVIDIA Corporation)", "ANGLE (NVIDIA Corporation, NVIDIA GeForce GTX 1060/PCIe/SSE2, OpenGL 4.5.0)"},
            {"Google Inc. (NVIDIA Corporation)", "ANGLE (NVIDIA Corporation, NVIDIA GeForce GTX 1050 Ti/PCIe/SSE2, OpenGL 4.5.0)"},
            {"Google Inc. (AMD)", "ANGLE (AMD, AMD Radeon RX 580 (polaris10, LLVM 17.0.6, DRM 3.57, 6.8.0), OpenGL 4.6)"},
            {"Google Inc. (AMD)", "ANGLE (AMD, AMD Radeon RX 6600 (navi23, LLVM 17.0.6, DRM 3.57, 6.8.0), OpenGL 4.6)"},
            {"Google Inc. (AMD)", "ANGLE (AMD, AMD Radeon RX 6700 XT (navi22, LLVM 17.0.6, DRM 3.57, 6.8.0), OpenGL 4.6)"},
            {"Google Inc. (AMD)", "ANGLE (AMD, AMD Radeon RX 7600 (navi33, LLVM 17.0.6, DRM 3.57, 6.8.0), OpenGL 4.6)"},
            {"Google Inc. (AMD)", "ANGLE (AMD, AMD Radeon Graphics (renoir, LLVM 17.0.6, DRM 3.57, 6.8.0), OpenGL 4.6)"},
            {"Google Inc. (Intel)", "ANGLE (Intel, Mesa Intel(R) UHD Graphics 630 (CFL GT2), OpenGL 4.6)"},
            {"Google Inc. (Intel)", "ANGLE (Intel, Mesa Intel(R) UHD Graphics 620 (KBL GT2), OpenGL 4.6)"},
            {"Google Inc. (Intel)", "ANGLE (Intel, Mesa Intel(R) Iris(R) Xe Graphics (TGL GT2), OpenGL 4.6)"}
    };

    private static final String[][] WEBGL_MACOS = {
            {"Google Inc. (Apple)", "ANGLE (Apple, ANGLE Metal Renderer: Apple M1, Unspecified Version)"},
            {"Google Inc. (Apple)", "ANGLE (Apple, ANGLE Metal Renderer: Apple M1 Pro, Unspecified Version)"},
            {"Google Inc. (Apple)", "ANGLE (Apple, ANGLE Metal Renderer: Apple M2, Unspecified Version)"},
            {"Google Inc. (Apple)", "ANGLE (Apple, ANGLE Metal Renderer: Apple M2 Pro, Unspecified Version)"},
            {"Google Inc. (Apple)", "ANGLE (Apple, ANGLE Metal Renderer: Apple M3, Unspecified Version)"},
            {"Google Inc. (Apple)", "ANGLE (Apple, ANGLE Metal Renderer: Apple M3 Pro, Unspecified Version)"}
    };

    /**
     * GPU (vendor+renderer) coerente com a plataforma para default seguro de WebGL spoof.
     * Determinístico (primeira entrada do pool). Evita vazar o renderer real do host
     * (SwiftShader/llvmpipe em datacenter = assinatura de bot).
     */
    public static String[] defaultWebgl(String uaPlatform) {
        String p = uaPlatform == null ? "" : uaPlatform.toLowerCase(java.util.Locale.ROOT);
        String[][] pool = p.contains("mac") ? WEBGL_MACOS : (p.contains("win") ? WEBGL_WINDOWS : WEBGL_LINUX);
        return new String[]{pool[0][0], pool[0][1]};
    }

    private static final int[][] SCREENS_DESKTOP = {
            {1366, 768}, {1920, 1080}, {2560, 1440}, {1536, 864},
            {1440, 900}, {1680, 1050}, {1600, 900}, {2560, 1080},
            {3840, 2160}, {1280, 720}, {1280, 1024}, {1360, 768},
            {1600, 1200}, {1920, 1200}, {2048, 1152}, {2560, 1600},
            {1920, 1280}, {3440, 1440}, {1280, 800}, {1024, 768}
    };

    private static final int[][] SCREENS_MACOS = {
            {1440, 900}, {1680, 1050}, {2560, 1440}, {2560, 1600},
            {3024, 1964}, {3456, 2234}
    };

    private static final int[][] HARDWARE_COMBOS = {
            {4, 4}, {4, 8}, {6, 8}, {6, 16}, {8, 8}, {8, 16},
            {8, 32}, {10, 16}, {12, 16}, {12, 32}, {16, 16}, {16, 32}
    };

    static BrowserFingerprintProfile randomProfile(String os) {
        return randomProfile(os, ThreadLocalRandom.current());
    }

    /** Variante determinística: o mesmo seed sempre produz o mesmo device. */
    static BrowserFingerprintProfile randomProfile(String os, long seed) {
        return randomProfile(os, new Random(seed));
    }

    static BrowserFingerprintProfile randomProfile(String os, Random rng) {
        // os nulo cai no default (Windows): callers como SiteProfiles.forSite aceitam os=null
        // e o switch abaixo usaria os.toLowerCase() sem guarda (NPE).
        String osKey = os == null ? "windows" : os.toLowerCase();
        BrowserFingerprintProfile.Builder builder = BrowserFingerprintProfile.builder()
                .name("synthetic-" + osKey + "-"
                        + Integer.toHexString(rng.nextInt() & 0x7fffffff));

        int[] hardware = HARDWARE_COMBOS[rng.nextInt(HARDWARE_COMBOS.length)];
        builder.hardware(hardware[0], hardware[1], 0);

        String[][] gpuPool;
        int[][] screenPool;
        switch (osKey) {
            case "macos" -> {
                builder.platform("MacIntel", "macOS", "14." + rng.nextInt(0, 6) + ".0");
                builder.architecture("arm", "64");
                gpuPool = WEBGL_MACOS;
                screenPool = SCREENS_MACOS;
            }
            case "linux" -> {
                builder.platform("Linux x86_64", "Linux", "6." + rng.nextInt(5, 12) + ".0");
                builder.architecture("x86", "64");
                gpuPool = WEBGL_LINUX;
                screenPool = SCREENS_DESKTOP;
            }
            default -> {
                String winVersion = rng.nextBoolean() ? "10.0.0" : "15.0.0";
                builder.platform("Win32", "Windows", winVersion);
                builder.architecture("x86", "64");
                gpuPool = WEBGL_WINDOWS;
                screenPool = SCREENS_DESKTOP;
            }
        }

        int[] screen = screenPool[rng.nextInt(screenPool.length)];
        if ("macos".equalsIgnoreCase(osKey)) {
            builder.screen(screen[0], screen[1], 30, 2);
        } else {
            builder.screen(screen[0], screen[1]);
        }

        String[] gpu = gpuPool[rng.nextInt(gpuPool.length)];
        // "spoof_noise" (não "real"): mascara o renderer real e individualiza readPixels. Em ambiente
        // headless/datacenter (Xvfb sem GPU) o renderer real é SwiftShader/llvmpipe —
        // assinatura inequívoca de bot. Com "real" esse valor vazaria.
        builder.webgl("spoof_noise", gpu[0], gpu[1]);
        builder.webrtc("altered", true, "");

        // Ruído determinístico (mesmos ranges de SdkProfileGenerator.applyDiversity):
        // individualiza canvas/audio/clientRects para que perfis distintos não colidam
        // nesses hashes. Semeado pelo rng → estável quando o perfil é determinístico.
        builder.canvasNoise(0.001 + rng.nextDouble(0.004));
        builder.audioNoise(0.001 + rng.nextDouble(0.004));
        builder.clientRectsNoise(0.01 + rng.nextDouble(0.04));

        // mediaDevices: trunca para um conjunto de desktop típico (1 mic, 1 alto-falante, 0 câmera)
        // E relabela deviceId/groupId/label de forma determinística — sem isto os deviceId/label
        // REAIS do host vazariam após permissão de mídia. Ativado por padrão no perfil sintético.
        builder.mediaDevices(1, 1, 0);

        double[] downlinks = {1.4, 2.5, 5.0, 10.0, 15.0, 20.0};
        int[] rtts = {20, 30, 50, 75, 100, 150};
        builder.connection("4g", downlinks[rng.nextInt(downlinks.length)], rtts[rng.nextInt(rtts.length)]);

        BrowserFingerprintProfile profile = builder.build();
        int generatedNoiseSeed = rng.nextInt();
        profile.noiseSeed = generatedNoiseSeed == 1337 ? generatedNoiseSeed ^ 0x5A3C9E2B : generatedNoiseSeed;
        // O tema também é uma superfície de fingerprint (CSS + Client Hint). Derivá-lo da mesma
        // identidade estável evita herdar o tema do desktop compartilhado sem consumir mais RNG.
        profile.colorScheme = (profile.noiseSeed & 1) == 0 ? "light" : "dark";
        return profile;
    }

    private static final Map<String, String[]> GEO_PROFILES = Map.ofEntries(
            Map.entry("US", new String[]{"en-US", "America/New_York", "40.7128", "-74.0060", "en-US", "en"}),
            Map.entry("BR", new String[]{"pt-BR", "America/Sao_Paulo", "-23.5505", "-46.6333", "pt-BR", "pt", "en-US", "en"}),
            Map.entry("GB", new String[]{"en-GB", "Europe/London", "51.5074", "-0.1278", "en-GB", "en"}),
            Map.entry("DE", new String[]{"de-DE", "Europe/Berlin", "52.5200", "13.4050", "de-DE", "de", "en"}),
            Map.entry("FR", new String[]{"fr-FR", "Europe/Paris", "48.8566", "2.3522", "fr-FR", "fr", "en"}),
            Map.entry("JP", new String[]{"ja-JP", "Asia/Tokyo", "35.6762", "139.6503", "ja-JP", "ja", "en"}),
            Map.entry("KR", new String[]{"ko-KR", "Asia/Seoul", "37.5665", "126.9780", "ko-KR", "ko", "en"}),
            Map.entry("CA", new String[]{"en-CA", "America/Toronto", "43.6532", "-79.3832", "en-CA", "en", "fr-CA"}),
            Map.entry("AU", new String[]{"en-AU", "Australia/Sydney", "-33.8688", "151.2093", "en-AU", "en"}),
            Map.entry("IT", new String[]{"it-IT", "Europe/Rome", "41.9028", "12.4964", "it-IT", "it", "en"}),
            Map.entry("ES", new String[]{"es-ES", "Europe/Madrid", "40.4168", "-3.7038", "es-ES", "es", "en"}),
            Map.entry("PT", new String[]{"pt-PT", "Europe/Lisbon", "38.7223", "-9.1393", "pt-PT", "pt", "en"}),
            Map.entry("MX", new String[]{"es-MX", "America/Mexico_City", "19.4326", "-99.1332", "es-MX", "es", "en"}),
            Map.entry("AR", new String[]{"es-AR", "America/Argentina/Buenos_Aires", "-34.6037", "-58.3816", "es-AR", "es", "en"}),
            Map.entry("IN", new String[]{"hi-IN", "Asia/Kolkata", "28.6139", "77.2090", "hi-IN", "en-IN", "en"}),
            Map.entry("RU", new String[]{"ru-RU", "Europe/Moscow", "55.7558", "37.6173", "ru-RU", "ru", "en"}),
            Map.entry("NL", new String[]{"nl-NL", "Europe/Amsterdam", "52.3676", "4.9041", "nl-NL", "nl", "en"}),
            Map.entry("SE", new String[]{"sv-SE", "Europe/Stockholm", "59.3293", "18.0686", "sv-SE", "sv", "en"}),
            Map.entry("PL", new String[]{"pl-PL", "Europe/Warsaw", "52.2297", "21.0122", "pl-PL", "pl", "en"}),
            Map.entry("TR", new String[]{"tr-TR", "Europe/Istanbul", "41.0082", "28.9784", "tr-TR", "tr", "en"})
    );

    static BrowserFingerprintProfile.Builder forCountry(String countryCode) {
        String[] geo = geoProfile(countryCode, true);
        String[] languages = new String[geo.length - 4];
        System.arraycopy(geo, 4, languages, 0, languages.length);
        return BrowserFingerprintProfile.builder()
                .locale(geo[0])
                .timezone(geo[1])
                .geolocation(Double.parseDouble(geo[2]), Double.parseDouble(geo[3]))
                .languages(languages);
    }

    static BrowserFingerprintProfile randomForCountry(String countryCode, String os) {
        BrowserFingerprintProfile p = applyCountry(randomProfile(os), countryCode);
        p.noiseSeed = ThreadLocalRandom.current().nextInt();
        p.fonts.mode = "subset";
        p.fonts.hidden = hiddenFontsFor(p.noiseSeed);
        return p;
    }

    /** Determinístico por seed numérico. */
    static BrowserFingerprintProfile randomForCountry(String countryCode, String os, long seed) {
        BrowserFingerprintProfile p = applyCountry(randomProfile(os, seed), countryCode);
        p.noiseSeed = mixToNoiseSeed(seed);
        p.fonts.mode = "subset";
        p.fonts.hidden = hiddenFontsFor(p.noiseSeed);
        return p;
    }

    /** Determinístico por chave estável (ex.: id da conta). */
    static BrowserFingerprintProfile randomForCountry(String countryCode, String os, String seedKey) {
        return randomForCountry(countryCode, os, stableSeed(seedKey));
    }

    /**
     * Seed estável e independente de plataforma a partir de uma chave textual. Usa SHA-256 (NÃO
     * String.hashCode, que correlaciona chaves vizinhas — "contaA"/"contaB" diferem por 1 e gerariam
     * perfis quase idênticos). Garante que dois drivers NUNCA saiam parecidos por nomes próximos.
     */
    static long stableSeed(String value) {
        if (value == null) {
            return 0L;
        }
        try {
            byte[] h = java.security.MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            long s = 0L;
            for (int i = 0; i < 8; i++) {
                s = (s << 8) | (h[i] & 0xFFL);
            }
            return s;
        } catch (Exception e) {
            return Integer.toUnsignedLong(value.hashCode());
        }
    }

    /**
     * Deriva o noiseSeed (alimenta canvas/audio/clientRects/readPixels) de um seed, com mistura forte
     * (splitmix64) para que seeds próximos produzam noiseSeeds totalmente distintos.
     */
    static int mixToNoiseSeed(long seed) {
        long z = seed + 0x9E3779B97F4A7C15L;
        z = (z ^ (z >>> 30)) * 0xBF58476D1CE4E5B9L;
        z = (z ^ (z >>> 27)) * 0x94D049BB133111EBL;
        z = z ^ (z >>> 31);
        return (int) z;
    }

    /**
     * Fontes OPCIONAIS escondidas por conta (modo "subset"). Cada driver esconde uma fatia
     * determinística das fontes não-core → a enumeração (measureText/offsetWidth/document.fonts)
     * sai ÚNICA por driver, sem nunca tocar nas core (quebraria o render). LCG independente do
     * canvas/webgl a partir do noiseSeed; nunca devolve lista vazia nem todas.
     */
    private static final String[] OPTIONAL_FONTS = {
        "Carlito", "Caladea", "Noto Sans", "Noto Serif", "FreeSans", "FreeSerif",
        "FreeMono", "Ubuntu", "Ubuntu Mono", "Ubuntu Condensed"
    };

    static List<String> hiddenFontsFor(int noiseSeed) {
        long s = (Integer.toUnsignedLong(noiseSeed) ^ 0x464F4E54L) & 0xFFFFFFFFL; // 'FONT'
        java.util.ArrayList<String> h = new java.util.ArrayList<>();
        for (String f : OPTIONAL_FONTS) {
            s = (1664525L * s + 1013904223L) & 0xFFFFFFFFL;
            if ((s >>> 8) % 100 < 45) h.add(f);
        }
        if (h.isEmpty()) h.add(OPTIONAL_FONTS[(int) ((s >>> 4) % OPTIONAL_FONTS.length)]); // nunca vazio
        if (h.size() == OPTIONAL_FONTS.length) h.remove(0);                                // nunca todas
        return List.copyOf(h);
    }

    private static BrowserFingerprintProfile applyCountry(BrowserFingerprintProfile random, String countryCode) {
        String[] geo = geoProfile(countryCode, false);
        random.locale = geo[0];
        random.timezoneId = geo[1];
        random.geolocation.mode = "override";
        random.geolocation.latitude = Double.parseDouble(geo[2]);
        random.geolocation.longitude = Double.parseDouble(geo[3]);
        random.geolocation.accuracy = 100.0;
        String[] languages = new String[geo.length - 4];
        System.arraycopy(geo, 4, languages, 0, languages.length);
        random.languages = List.of(languages);
        random.applyDefaults();
        return random;
    }

    static List<String> supportedCountries() {
        return List.copyOf(GEO_PROFILES.keySet());
    }

    private static String[] geoProfile(String countryCode, boolean includeAvailable) {
        String[] geo = GEO_PROFILES.get(countryCode.toUpperCase());
        if (geo == null) {
            String message = "País não suportado: " + countryCode;
            if (includeAvailable) {
                message += ". Disponíveis: " + String.join(", ", GEO_PROFILES.keySet());
            }
            throw new IllegalArgumentException(message);
        }
        return geo;
    }
}
