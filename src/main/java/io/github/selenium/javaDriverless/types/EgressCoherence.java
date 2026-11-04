package io.github.selenium.javaDriverless.types;

import java.time.ZoneId;
import java.util.List;
import java.util.Locale;

/**
 * Torna um {@link BrowserFingerprintProfile} coerente com o IP de egress.
 *
 * <p>Tudo que um site anti-fraude cruza com o GeoIP — timezone, idioma, geo,
 * WebRTC e tipo de conexão — passa a refletir o IP do proxy, em vez de valores
 * cravados no perfil. É o ponto único de coerência: consumidores (phantomPass,
 * bots, etc.) só preenchem um {@link EgressInfo} e chamam {@link #apply}.
 *
 * <p>Princípio anti-vazamento: sem dados do IP, NÃO inventa. Quando não há
 * latitude/longitude, o override de geolocalização é limpo (evita que uma
 * coordenada de "país-default" do perfil vaze divergindo do IP).
 */
public final class EgressCoherence {

    private EgressCoherence() {
    }

    /**
     * Resolve o {@link EgressInfo} efetivo da sessão: o {@code explicit} (setado via
     * {@code ChromeOptions.setEgressInfo}) tem precedência; na ausência dele, usa o
     * {@code resolver} (se houver). Retorna {@code null} quando nenhuma fonte conhece o egress —
     * o caller mantém os defaults coerentes por locale.
     */
    public static EgressInfo resolveEgress(EgressInfo explicit, EgressResolver resolver, ProxyConfig proxy)
            throws Exception {
        if (explicit != null) {
            return explicit;
        }
        return resolver != null ? resolver.resolve(proxy) : null;
    }

    /** Sem proxy o IP de saída é o do host: fuso e idioma seguem o host. Não mexe em WebRTC/geo/conexão. */
    public static void applyHostLocale(BrowserFingerprintProfile fp, ZoneId zone, Locale locale) {
        if (fp == null) {
            return;
        }
        if (zone != null) {
            fp.timezoneId = zone.getId();
        }
        if (locale != null && !locale.getLanguage().isEmpty()) { // Locale.ROOT daria "und"
            List<String> langs = java.util.stream.Stream.concat(
                    java.util.stream.Stream.of(locale.toLanguageTag()),
                    languagesForCountry(locale.getCountry()).stream())
                    .distinct()
                    .toList();
            fp.languages = langs;
            fp.locale = langs.get(0);
        }
    }

    /** Restaura exatamente os campos que {@link #apply} pode alterar entre tentativas. */
    public static void restore(BrowserFingerprintProfile target, BrowserFingerprintProfile baseline) {
        if (target == null || baseline == null) return;
        BrowserFingerprintProfile restored = baseline.copy();
        target.timezoneId = restored.timezoneId;
        target.locale = restored.locale;
        target.languages = restored.languages;
        target.webRtc = restored.webRtc;
        target.geolocation = restored.geolocation;
        target.connection = restored.connection;
    }

    /**
     * Aplica a coerência do egress ao perfil. No-op se {@code fp} ou
     * {@code egress} forem nulos.
     */
    public static void apply(BrowserFingerprintProfile fp, EgressInfo egress) {
        if (fp == null || egress == null) {
            return;
        }

        // Timezone segue o IP (ex.: America/Manaus se a saída é em Manaus).
        if (notBlank(egress.timezone)) {
            fp.timezoneId = egress.timezone;
        }

        // Idioma coerente com o país do IP.
        List<String> langs = languagesForCountry(egress.countryCode);
        if (!langs.isEmpty()) {
            fp.languages = langs;
            fp.locale = langs.get(0);
        }

        // WebRTC: o wrapper legado pode expor somente o IP de egress conhecido. minimal-cdp usa
        // relay-only sem wrapper; nesse modo declarar publicIp seria apenas metadado não entregue.
        boolean minimalCdp = BrowserFingerprintProfile.RUNTIME_POLICY_MINIMAL_CDP.equalsIgnoreCase(
                fp.runtimePolicy == null ? "" : fp.runtimePolicy.trim());
        if (!minimalCdp && notBlank(egress.publicIp)) {
            fp.webRtc.publicIp = egress.publicIp;
        } else if (minimalCdp) {
            fp.webRtc.publicIp = "";
            fp.webRtc.allowedIps = List.of();
        }
        fp.webRtc.mode = "altered";
        fp.webRtc.protectLocalIps = true;
        fp.webRtc.disableUdp = true;

        // Geo segue o IP; sem coordenadas → limpa (não cravar país-default).
        if (egress.latitude != null && egress.longitude != null) {
            fp.geolocation.mode = "override";
            fp.geolocation.latitude = egress.latitude;
            fp.geolocation.longitude = egress.longitude;
            fp.geolocation.accuracy = egress.accuracy != null ? egress.accuracy : 50.0;
        } else {
            fp.geolocation.mode = "prompt";
            fp.geolocation.latitude = null;
            fp.geolocation.longitude = null;
            fp.geolocation.accuracy = null;
        }

        // Conexão: o runtime legado pode declarar tipo móvel quando o IP é de operadora
        // móvel. minimal-cdp preserva a API nativa porque não instala esse wrapper e rejeitaria
        // connection.mode=spoof antes do launch. Para egress fixo, não força nada.
        if (egress.mobile && !minimalCdp) {
            fp.connection.mode = "spoof";
            fp.connection.effectiveType = "4g";
            fp.connection.type = "cellular";
        }
    }

    /**
     * Lista de {@code navigator.languages} plausível para o país (primária local
     * do país + fallbacks). Vazia para país desconhecido (caller mantém o perfil).
     */
    public static List<String> languagesForCountry(String countryCode) {
        String cc = (countryCode == null ? "" : countryCode).toUpperCase();
        return switch (cc) {
            case "BR" -> List.of("pt-BR", "pt", "en-US", "en");
            case "PT" -> List.of("pt-PT", "pt", "en-US", "en");
            case "DE" -> List.of("de-DE", "de", "en-US", "en");
            case "FR" -> List.of("fr-FR", "fr", "en-US", "en");
            case "GB" -> List.of("en-GB", "en-US", "en");
            case "ES" -> List.of("es-ES", "es", "en-US", "en");
            case "MX", "AR", "CL", "CO" -> List.of("es-" + cc, "es", "en-US", "en");
            case "JP" -> List.of("ja-JP", "ja", "en-US", "en");
            case "CA" -> List.of("en-CA", "en-US", "fr-CA", "fr");
            case "AU" -> List.of("en-AU", "en-US", "en");
            case "SG" -> List.of("en-SG", "en-US", "zh-SG", "zh");
            case "IN" -> List.of("en-IN", "en-US", "hi-IN", "hi");
            case "US" -> List.of("en-US", "en");
            case "IT" -> List.of("it-IT", "it", "en-US", "en");
            case "NL" -> List.of("nl-NL", "nl", "en-US", "en");
            case "SE" -> List.of("sv-SE", "sv", "en-US", "en");
            case "PL" -> List.of("pl-PL", "pl", "en-US", "en");
            case "TR" -> List.of("tr-TR", "tr", "en-US", "en");
            case "RU" -> List.of("ru-RU", "ru", "en-US", "en");
            case "KR" -> List.of("ko-KR", "ko", "en-US", "en");
            default -> List.of();
        };
    }

    /**
     * Timezone IANA representativo do país (capital/maior cidade). Usado como default
     * fail-closed: melhor um fuso coerente com o locale do que vazar o fuso real do host
     * (datacenter em UTC num IP residencial é assinatura clássica de bot). Em produção, o
     * {@link #apply} sobrescreve com o timezone real do IP de egress. Retorna {@code null}
     * para país desconhecido (caller mantém o que tiver).
     */
    public static String timezoneForCountry(String countryCode) {
        String cc = (countryCode == null ? "" : countryCode).toUpperCase();
        return switch (cc) {
            case "BR" -> "America/Sao_Paulo";
            case "PT" -> "Europe/Lisbon";
            case "DE" -> "Europe/Berlin";
            case "FR" -> "Europe/Paris";
            case "GB" -> "Europe/London";
            case "ES" -> "Europe/Madrid";
            case "MX" -> "America/Mexico_City";
            case "AR" -> "America/Argentina/Buenos_Aires";
            case "CL" -> "America/Santiago";
            case "CO" -> "America/Bogota";
            case "JP" -> "Asia/Tokyo";
            case "CA" -> "America/Toronto";
            case "AU" -> "Australia/Sydney";
            case "SG" -> "Asia/Singapore";
            case "IN" -> "Asia/Kolkata";
            case "US" -> "America/New_York";
            case "IT" -> "Europe/Rome";
            case "NL" -> "Europe/Amsterdam";
            case "SE" -> "Europe/Stockholm";
            case "PL" -> "Europe/Warsaw";
            case "TR" -> "Europe/Istanbul";
            case "RU" -> "Europe/Moscow";
            case "KR" -> "Asia/Seoul";
            case "CN" -> "Asia/Shanghai";
            default -> null;
        };
    }

    private static boolean notBlank(String s) {
        return s != null && !s.isBlank();
    }
}
