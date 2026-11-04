package io.github.selenium.javaDriverless.types;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

final class BrowserFingerprintUserAgentMetadata {
    private BrowserFingerprintUserAgentMetadata() {}

    static Map<String, Object> from(BrowserFingerprintProfile profile, String fallbackMajorVersion) {
        String majorVersion = profile.browserVersion != null && !profile.browserVersion.isBlank()
                ? profile.browserVersion.split("\\.")[0]
                : fallbackMajorVersion;
        String fullVersion = fullVersion(profile.browserVersion, majorVersion);

        List<Map<String, Object>> brands = List.of(
                Map.of("brand", "Not(A:Brand", "version", "99"),
                Map.of("brand", "Google Chrome", "version", majorVersion),
                Map.of("brand", "Chromium", "version", majorVersion));
        List<Map<String, Object>> fullVersionList = List.of(
                Map.of("brand", "Not(A:Brand", "version", "99.0.0.0"),
                Map.of("brand", "Google Chrome", "version", fullVersion),
                Map.of("brand", "Chromium", "version", fullVersion));

        // Android real reporta Sec-CH-UA-Arch e Sec-CH-UA-Bitness vazios. Forçar "" para perfil
        // mobile/Android evita o conflito de reportar arch="arm"/bitness="64" num device móvel.
        boolean android = profile.mobile
                || (profile.uaPlatform != null && "android".equalsIgnoreCase(profile.uaPlatform.trim()));
        String architecture = android ? "" : profile.architecture;
        String bitness = android ? "" : profile.bitness;

        Map<String, Object> metadata = new HashMap<>();
        metadata.put("brands", brands);
        metadata.put("fullVersionList", fullVersionList);
        metadata.put("fullVersion", fullVersion);
        metadata.put("platform", profile.uaPlatform);
        metadata.put("platformVersion", profile.platformVersion);
        metadata.put("architecture", architecture);
        metadata.put("model", profile.model == null ? "" : profile.model);
        metadata.put("mobile", profile.mobile);
        metadata.put("bitness", bitness);
        metadata.put("wow64", profile.wow64);
        metadata.put("formFactors", profile.formFactors);
        return metadata;
    }

    private static String fullVersion(String browserVersion, String majorVersion) {
        if (browserVersion == null || browserVersion.isBlank()) {
            return majorVersion + "." + buildPatchForMajor(majorVersion);
        }
        return browserVersion.contains(".") ? browserVersion : browserVersion + "." + buildPatchForMajor(majorVersion);
    }

    /**
     * build.patch plausível (não-zero) por major recente. Chrome real nunca tem
     * "<major>.0.0.0" no fullVersionList — emitir isso é assinatura de UA forjado.
     * Para majors fora do mapa, usar um fallback genérico não-zero ("0.6900.100").
     */
    private static String buildPatchForMajor(String majorVersion) {
        switch (majorVersion == null ? "" : majorVersion.trim()) {
            case "131": return "0.6778.86";
            case "132": return "0.6834.83";
            case "133": return "0.6943.142";
            case "134": return "0.6998.36";
            case "135": return "0.7049.96";
            default: return "0.6900.100";
        }
    }
}
