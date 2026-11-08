package io.github.selenium.javaDriverless.fpmapping;

import io.github.selenium.javaDriverless.types.BrowserFingerprintProfile;
import io.github.selenium.javaDriverless.types.ChromeOptions;

import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.HashMap;
import java.util.Map;

final class FingerprintMappingLocationProfile {
    private static final Map<String, Geo> COUNTRIES = Map.of(
            "US", new Geo("en-US", "America/New_York", 40.7128, -74.0060, List.of("en-US", "en")),
            "DE", new Geo("de-DE", "Europe/Berlin", 52.5200, 13.4050, List.of("de-DE", "de", "en")),
            "JP", new Geo("ja-JP", "Asia/Tokyo", 35.6762, 139.6503, List.of("ja-JP", "ja", "en")),
            "BR", new Geo("pt-BR", "America/Sao_Paulo", -23.5505, -46.6333, List.of("pt-BR", "pt", "en-US", "en"))
    );

    private FingerprintMappingLocationProfile() {
    }

    static void apply(ChromeOptions options, FingerprintMappingCatalog.Args args) {
        if (!hasLocationInput(args)) {
            return;
        }
        BrowserFingerprintProfile profile = options.getFingerprintProfile();
        Geo geo = country(args.profileCountry());
        if (geo != null) {
            applyGeo(profile, geo);
        }
        if (args.locale() != null) {
            profile.locale = args.locale();
        }
        if (args.timezone() != null) {
            profile.timezoneId = args.timezone();
        }
        if (args.languages() != null) {
            profile.languages = Arrays.stream(args.languages().split(","))
                    .map(String::trim).filter(s -> !s.isEmpty()).toList();
        }
        if (args.geo() != null) {
            String[] parts = args.geo().split(",", 2);
            if (parts.length != 2) {
                throw new IllegalArgumentException("--geo deve ser latitude,longitude: " + args.geo());
            }
            profile.geolocation.mode = "override";
            profile.geolocation.latitude = Double.parseDouble(parts[0]);
            profile.geolocation.longitude = Double.parseDouble(parts[1]);
            profile.geolocation.accuracy = 100.0;
        }
        applyProcessLocale(options, profile.locale, profile.languages);
        options.setFingerprintProfile(profile);
    }

    private static boolean hasLocationInput(FingerprintMappingCatalog.Args args) {
        return args.profileCountry() != null || args.locale() != null || args.timezone() != null
                || args.languages() != null || args.geo() != null;
    }

    private static Geo country(String country) {
        return country == null ? null : COUNTRIES.get(country.trim().toUpperCase(Locale.ROOT));
    }

    private static void applyGeo(BrowserFingerprintProfile profile, Geo geo) {
        profile.locale = geo.locale();
        profile.timezoneId = geo.timezone();
        profile.languages = geo.languages();
        profile.geolocation.mode = "override";
        profile.geolocation.latitude = geo.lat();
        profile.geolocation.longitude = geo.lon();
        profile.geolocation.accuracy = 100.0;
    }

    private static void applyProcessLocale(ChromeOptions options, String locale, List<String> languages) {
        if (locale == null || locale.isBlank()) {
            return;
        }
        String posix = locale.replace('-', '_') + ".UTF-8";
        Map<String, String> env = new HashMap<>(options.getEnv());
        env.put("LANG", posix);
        env.put("LC_ALL", posix);
        env.put("LC_MESSAGES", posix);
        env.put("LANGUAGE", String.join(":", languages));
        options.setEnv(env);
        options.addArgument("--lang=" + locale);
        options.addArgument("--accept-lang=" + String.join(",", languages));
    }

    private record Geo(String locale, String timezone, double lat, double lon, List<String> languages) {
    }
}
