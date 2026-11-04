package io.github.selenium.javaDriverless.types;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

final class ChromeOptionsLocaleProfiles {
    private ChromeOptionsLocaleProfiles() {
    }

    static void apply(ChromeOptionsFingerprintOptions options, BrowserFingerprintProfile profile) {
        if (profile.locale == null || profile.locale.isBlank()) {
            return;
        }
        List<String> languages = profile.languages == null || profile.languages.isEmpty()
                ? List.of(profile.locale) : profile.languages;
        String posix = profile.locale.replace('-', '_') + ".UTF-8";
        Map<String, String> env = new HashMap<>(options.getEnv());
        env.put("LANG", posix);
        env.put("LC_ALL", posix);
        env.put("LC_MESSAGES", posix);
        env.put("LANGUAGE", String.join(":", languages));
        options.setEnv(env);
        options.addArgument("--lang=" + profile.locale);
        options.addArgument("--accept-lang=" + String.join(",", languages));
    }
}
