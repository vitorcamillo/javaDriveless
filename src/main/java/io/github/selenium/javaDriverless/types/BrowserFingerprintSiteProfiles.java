package io.github.selenium.javaDriverless.types;

import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;

/**
 * Catálogo de perfis de fingerprint por TIPO DE SITE.
 * <p>
 * Cada tipo de site parte do perfil base HARDENED ({@link BrowserFingerprintProfiles#randomForCountry}
 * via seed estável por conta) — que já ativa WebGL spoof, ruído determinístico de canvas/audio/
 * clientRects, mediaDevices relabel e WebRTC altered — e aplica ajustes específicos do site através
 * de um {@link Consumer} que pode LIGAR/DESLIGAR qualquer superfície (ex.: pôr {@code p.webRtc.mode =
 * "real"} para um site que quebra com a proteção, trocar o set de mediaDevices, forçar uma GPU
 * específica, etc.). Para adicionar/ajustar um site, basta uma entrada no mapa {@code CATALOG} — é
 * o ponto único de "ativar e desativar o que precisar" por tipo de site.
 * </p>
 */
final class BrowserFingerprintSiteProfiles {
    private BrowserFingerprintSiteProfiles() {}

    /**
     * Ajustes por tipo de site sobre o perfil base hardened. Consumer vazio = usa o base como está.
     * O Consumer recebe o perfil já hardened e pode alterar qualquer superfície/modo.
     */
    private static final Map<String, Consumer<BrowserFingerprintProfile>> CATALOG = Map.ofEntries(
            // Base hardened — cobre a maioria das casas/engines.
            Map.entry("default", p -> { }),
            // Sportsbooks Kambi (ex.: bet365, kto): coerência GPU/canvas; base hardened serve.
            Map.entry("kambi", p -> { }),
            // Altenar (widget SB2 / biahosted): base hardened.
            Map.entry("altenar", p -> { }),
            // OBG / Betsson (sportingbet, betsson): base hardened.
            Map.entry("obg", p -> { }),
            // OpenEngage (4play, ginga, vivasorte): base hardened.
            Map.entry("openengage", p -> { }),
            // SoftSwiss (betesporte): base hardened.
            Map.entry("softswiss", p -> { }),
            // Betano (engine próprio): base hardened.
            Map.entry("betano", p -> { })
    );

    static BrowserFingerprintProfile forSite(String siteType, String countryCode, String os, String seedKey) {
        BrowserFingerprintProfile profile = BrowserFingerprintProfiles.randomForCountry(countryCode, os, seedKey);
        String key = siteType == null ? "default" : siteType.toLowerCase();
        Consumer<BrowserFingerprintProfile> tweak = CATALOG.getOrDefault(key, CATALOG.get("default"));
        if (tweak != null) {
            tweak.accept(profile);
        }
        profile.name = "site-" + key + "-" + (os == null ? "" : os.toLowerCase());
        return profile;
    }

    static Set<String> supportedSites() {
        return CATALOG.keySet();
    }
}
