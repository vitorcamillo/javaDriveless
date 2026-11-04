package io.github.selenium.javaDriverless.fpmapping;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

final class FingerprintMappingTerms {
    private FingerprintMappingTerms() {
    }

    static List<String> requiredFields(String readCsv, FingerprintSiteContracts.Site contract) {
        if (contract != null && contract.requiredDom != null && !contract.requiredDom.isEmpty()) {
            return contract.requiredDom.stream()
                    .map(entry -> entry.id)
                    .filter(value -> value != null && !value.isBlank())
                    .toList();
        }
        return FingerprintMappingText.splitCsv(readCsv);
    }

    static List<String> termsFor(String id, String field, FingerprintSiteContracts.Site contract) {
        if (contract != null && contract.requiredDom != null) {
            for (FingerprintSiteContracts.RequiredDom entry : contract.requiredDom) {
                if (field.equals(entry.id) && entry.terms != null && !entry.terms.isEmpty()) {
                    return entry.terms;
                }
            }
        }
        Map<String, List<String>> site = siteTerms(id);
        if (site.containsKey(field)) {
            return site.get(field);
        }
        return genericTerms(field);
    }

    private static Map<String, List<String>> siteTerms(String id) {
        Map<String, List<String>> map = new LinkedHashMap<>();
        if ("pixelscan".equals(id)) {
            map.put("pixelscan-main-heading", List.of("fingerprint scan"));
            map.put("pixelscan-verdict", List.of("your browser fingerprint is consistent", "your browser fingerprint is inconsistent"));
            map.put("pixelscan-browser-card", List.of("browser"));
            map.put("pixelscan-location-card", List.of("check geo api", "location"));
            map.put("pixelscan-proxy-card", List.of("no proxy detected proxy", "proxy detected proxy"));
            map.put("pixelscan-fingerprint-card", List.of("no masking detected fingerprint", "masking detected fingerprint"));
            map.put("pixelscan-bot-card", List.of("no automated behavior detected bot check", "automated behavior detected bot check"));
            map.put("pixelscan-ip-address", List.of("ip address"));
            map.put("pixelscan-webrtc-ip-address", List.of("webrtc ip address"));
            map.put("pixelscan-country", List.of("country"));
            map.put("pixelscan-city", List.of("city"));
            map.put("pixelscan-isp", List.of("isp"));
            map.put("pixelscan-latitude", List.of("latitude"));
            map.put("pixelscan-longitude", List.of("longitude"));
            map.put("pixelscan-user-type-by-ip", List.of("user type by ip"));
            map.put("pixelscan-timezone-js", List.of("timezone from js"));
            map.put("pixelscan-time-ip", List.of("time from ip"));
            map.put("pixelscan-screen-resolution", List.of("screen resolution", "resolution"));
            map.put("pixelscan-font-hash", List.of("font hash"));
            map.put("pixelscan-font-list", List.of("fonts"));
            map.put("pixelscan-user-agent-http", List.of("user-agent http"));
            map.put("pixelscan-user-agent-js", List.of("javascript mozilla/5.0"));
            map.put("pixelscan-platform", List.of("platform"));
            map.put("pixelscan-do-not-track", List.of("donottrack"));
            map.put("pixelscan-hardware-concurrency", List.of("hardwareconcurency", "hardwareconcurrency"));
            map.put("pixelscan-language-js", List.of("languages from javascript"));
            map.put("pixelscan-intl-api", List.of("internationalization api"));
            map.put("pixelscan-accept-language", List.of("accept-language header"));
            map.put("pixelscan-webgl-vendor", List.of("webgl vendor"));
            map.put("pixelscan-webgl-version", List.of("webgl version"));
            map.put("pixelscan-webgl-renderer", List.of("webgl renderer"));
            map.put("pixelscan-webgl-hash", List.of("webgl hash"));
            map.put("pixelscan-canvas-hash", List.of("canvas hash"));
            map.put("pixelscan-audiocontext-hash", List.of("audiocontext hash"));
            map.put("bot/headless/masking", List.of("browser fingerprint is", "consistent", "bot", "masking", "headless"));
        } else if ("browserleaks-webrtc".equals(id)) {
            map.put("mdns", List.of("mdns", "local ip address", "your webrtc ip"));
            map.put("stun", List.of("stun", "webrtc leak test", "rtcpeerconnection"));
            map.put("ice-candidates", List.of("ice", "candidate", "session description", "sdp log"));
            map.put("local-ip-candidates", List.of("local ip address", "your webrtc ip"));
            map.put("public-ip", List.of("public ip address", "your remote ip"));
        } else if ("browserleaks-canvas".equals(id)) {
            map.put("canvas-hash", List.of("canvas fingerprint", "signature", "fingerprint"));
            map.put("png-data-url", List.of("png headers", "png image data", "file size"));
            map.put("text-metrics", List.of("text api for canvas", "text metrics", "signature stats"));
        } else if ("browserleaks-fonts".equals(id)) {
            map.put("font-list", List.of("font metrics", "fonts", "unicode glyphs"));
            map.put("font-count", List.of("fonts and", "unique metrics found", "report"));
            map.put("font-access", List.of("font access", "font metrics", "unicode glyphs"));
        } else if ("sannysoft".equals(id)) {
            map.put("hairline", List.of("broken image dimensions", "hairline", "transparent_pixel"));
            map.put("chrome-object", List.of("chrome", "headchr_chrome_obj"));
            map.put("fingerprint-scanner", List.of("fingerprint scanner tests"));
        } else if ("coveryourtracks".equals(id)) {
            map.put("tracking-protection", List.of("tracking protection", "protecting against tracking", "how well is your browser protected"));
            map.put("fingerprint-uniqueness", List.of("fingerprint is", "bits of identifying information", "unique"));
            map.put("attribute-table", List.of("characteristic", "bits", "one in"));
        } else if ("checkup-000r".equals(id)) {
            map.put("browser-revealed-data", List.of("check what information your browser reveals", "privacy summary", "score"));
        } else if ("antidetect-timezone".equals(id)) {
            map.put("timezone-match", List.of("perfect match", "match", "no red flags", "timezone consistency"));
            map.put("ip-timezone", List.of("ip timezone", "ip location", "ip geolocation timezone"));
            map.put("browser-timezone", List.of("browser timezone"));
        }
        return map;
    }

    private static List<String> genericTerms(String field) {
        Map<String, List<String>> map = new LinkedHashMap<>();
        map.put("browser", List.of("browser", "chrome", "chromium", "user agent", "user-agent"));
        map.put("os", List.of("operating system", "platform", "linux", "windows", "mac"));
        map.put("screen", List.of("screen", "resolution", "viewport", "available size"));
        map.put("timezone", List.of("timezone", "time zone", "america/sao_paulo", "system timezone"));
        map.put("language", List.of("language", "languages", "accept-language"));
        map.put("webgl", List.of("webgl", "gpu", "renderer", "vendor"));
        map.put("canvas", List.of("canvas", "canvas hash", "canvas fingerprint", "signature"));
        map.put("webrtc", List.of("webrtc", "rtcpeerconnection", "local ip", "public ip"));
        map.put("dns", List.of("dns", "resolver", "dns leak"));
        map.put("ip", List.of("ip address", "your ip", "public ip", "remote ip", "ipv4", "ipv6"));
        map.put("geo", List.of("geo", "location", "latitude", "longitude", "country", "city"));
        map.put("tls", List.of("tls", "ja3", "ja4", "cipher", "http/2"));
        map.put("fonts", List.of("fonts", "font metrics", "font fingerprint"));
        map.put("hardware", List.of("hardware", "cpu", "cores", "memory", "ram", "gpu"));
        map.put("software", List.of("software", "browser", "plugins", "cookies"));
        map.put("plugins", List.of("plugins", "plugin"));
        map.put("permissions", List.of("permission", "permissions"));
        map.put("storage", List.of("storage", "localstorage", "indexeddb", "quota"));
        map.put("navigator", List.of("navigator", "user agent", "platform", "webdriver"));
        map.put("mimeTypes", List.of("mime types", "mimetypes"));
        map.put("languages", List.of("languages", "language"));
        map.put("platform", List.of("platform"));
        map.put("version", List.of("version"));
        map.put("renderer", List.of("renderer"));
        map.put("vendor", List.of("vendor"));
        map.put("extensions", List.of("extensions"));
        map.put("parameters", List.of("parameters", "max texture", "viewport", "precision"));
        map.put("permission-state", List.of("permissions api", "origin permissions", "granted", "prompt", "denied"));
        map.put("latitude", List.of("latitude"));
        map.put("longitude", List.of("longitude"));
        map.put("accuracy", List.of("accuracy"));
        map.put("tls-fingerprint", List.of("tls fingerprint", "ja3", "ja4"));
        map.put("http-protocol", List.of("http protocol", "h2", "http/1.1", "application_layer_protocol_negotiation"));
        map.put("cipher-suites", List.of("cipher suite", "cipher suites"));
        map.put("webdriver", List.of("webdriver"));
        map.put("headless", List.of("headless"));
        map.put("lies", List.of("lies", "lie"));
        map.put("status", List.of("status", "passed", "ok"));
        map.put("trust-score", List.of("trust score"));
        map.put("risk-assessment", List.of("risk assessment complete", "risk board"));
        map.put("risk-level", List.of("moderate risk", "low risk", "high risk", "critical risk"));
        map.put("trust-status", List.of("trust", "status"));
        map.put("vpn-proxy", List.of("vpn", "proxy"));
        map.put("audio-context", List.of("audio context", "audio hash", "web audio"));
        map.put("audio", List.of("audio", "audio hash", "web audio"));
        map.put("speech", List.of("speech", "speechsynthesis", "voices"));
        map.put("worker", List.of("worker"));
        map.put("intl", List.of("intl", "internationalization"));
        map.put("css-media", List.of("css media", "media queries"));
        map.put("sec-ch-ua", List.of("sec-ch-ua", "client hints", "brands"));
        map.put("full-version", List.of("full version", "uaFullVersion", "fullVersionList"));
        map.put("architecture", List.of("architecture", "x86"));
        map.put("bitness", List.of("bitness", "64"));
        map.put("mobile", List.of("mobile"));
        map.put("model", List.of("model"));
        map.put("connection-type", List.of("connection type"));
        map.put("proxy-headers", List.of("proxy headers", "x-forwarded-for", "via"));
        map.put("forwarded-ips", List.of("forwarded ip"));
        map.put("protocol", List.of("protocol", "https"));
        map.put("ip-match", List.of("ip match"));
        map.put("ip-timezone", List.of("ip timezone", "ip location time", "current timezone"));
        map.put("browser-timezone", List.of("browser timezone", "system timezone", "current timezone"));
        map.put("timezone-mismatch", List.of("mismatch", "matched", "synced"));
        map.put("ip-location", List.of("ip location", "location"));
        map.put("uniqueness", List.of("unique", "uniqueness", "similarity ratio"));
        map.put("attribute-table", List.of("attribute", "value", "category"));
        map.put("chrome-object", List.of("chrome"));
        map.put("bold-fail", List.of("failed", "fail"));
        map.put("behavior", List.of("behavior"));
        map.put("location", List.of("location", "latitude", "longitude", "city", "country"));
        List<String> configured = map.get(field);
        if (configured != null) {
            return configured;
        }
        Set<String> fallback = new LinkedHashSet<>();
        fallback.add(field);
        fallback.add(field.replace('-', ' '));
        fallback.add(field.replace('/', ' '));
        return new ArrayList<>(fallback);
    }
}
