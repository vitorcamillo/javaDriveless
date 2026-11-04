package io.github.selenium.javaDriverless.fpmapping;

import java.util.LinkedHashMap;
import java.util.Map;

final class FingerprintMappingPixelscanFixtures {
    private FingerprintMappingPixelscanFixtures() {
    }

    static String pixelscanRead() {
        return String.join(",",
                "pixelscan-main-heading",
                "pixelscan-verdict",
                "pixelscan-browser-card",
                "pixelscan-location-card",
                "pixelscan-proxy-card",
                "pixelscan-fingerprint-card",
                "pixelscan-bot-card",
                "pixelscan-ip-address",
                "pixelscan-webrtc-ip-address",
                "pixelscan-country",
                "pixelscan-city",
                "pixelscan-isp",
                "pixelscan-latitude",
                "pixelscan-longitude",
                "pixelscan-user-type-by-ip",
                "pixelscan-timezone-js",
                "pixelscan-time-ip",
                "pixelscan-screen-resolution",
                "pixelscan-font-hash",
                "pixelscan-font-list",
                "pixelscan-user-agent-http",
                "pixelscan-user-agent-js",
                "pixelscan-platform",
                "pixelscan-do-not-track",
                "pixelscan-hardware-concurrency",
                "pixelscan-language-js",
                "pixelscan-intl-api",
                "pixelscan-accept-language",
                "pixelscan-webgl-vendor",
                "pixelscan-webgl-version",
                "pixelscan-webgl-renderer",
                "pixelscan-webgl-hash",
                "pixelscan-canvas-hash",
                "pixelscan-audiocontext-hash"
        );
    }

    static Map<String, String> expectedProfile() {
        Map<String, String> expected = new LinkedHashMap<>();
        expected.put("pixelscan.verdict", "Your Browser Fingerprint is consistent");
        expected.put("pixelscan.browserCard", "Chrome 147.0.0.0 on Linux");
        expected.put("pixelscan.locationCard", "Brazil / Jundiaí");
        expected.put("pixelscan.proxyCard", "No proxy detected");
        expected.put("pixelscan.fingerprintCard", "No masking detected");
        expected.put("pixelscan.botCard", "No automated behavior detected");
        expected.put("webRtc.allowedIps",
                "198.51.100.20,2001:db8:0:0:0:0:0:1");
        expected.put("pixelscan.location.IP Address", "2001:db8:0:0:0:0:0:1");
        expected.put("pixelscan.location.WebRTC IP Address", "198.51.100.20");
        expected.put("pixelscan.location.Country", "Brazil");
        expected.put("pixelscan.location.City", "Jundiaí");
        expected.put("pixelscan.location.ISP", "TELEF*NICA BRASIL S.A");
        expected.put("pixelscan.location.Latitude", "-23.1798");
        expected.put("pixelscan.location.Longitude", "-46.8816");
        expected.put("pixelscan.location.User Type by IP", "isp");
        expected.put("pixelscan.dateTime.Timezone from JS", "America/Sao_Paulo");
        expected.put("pixelscan.dateTime.Daylight Savings Time", "No");
        expected.put("pixelscan.dateTime.Time from JS", "*");
        expected.put("pixelscan.dateTime.Time from IP", "*");
        expected.put("pixelscan.screen.Resolution", "1920x1080");
        expected.put("pixelscan.screen.Available screen size", "1920x1040");
        expected.put("pixelscan.fonts.Font hash", "*");
        expected.put("pixelscan.userAgent.HTTP", "Chrome/147.0.0.0");
        expected.put("pixelscan.userAgent.JavaScript", "Chrome/147.0.0.0");
        expected.put("pixelscan.userAgent.Platform", "Linux x86_64");
        expected.put("pixelscan.userAgent.DoNotTrack", "null");
        expected.put("pixelscan.userAgent.HardwareConcurency", "16");
        expected.put("pixelscan.language.Languages from Javascript", "pt-BR");
        expected.put("pixelscan.language.Internationalization API", "pt-BR");
        expected.put("pixelscan.language.Accept-Language header", "pt-BR");
        expected.put("pixelscan.hardware.WebGL Vendor", "Google Inc. (NVIDIA Corporation)");
        expected.put("pixelscan.hardware.WebGL Version", "WebGL 1.0");
        expected.put("pixelscan.hardware.WebGL Renderer", "NVIDIA GeForce RTX 3060");
        expected.put("pixelscan.hardware.WebGL Hash", "*");
        expected.put("pixelscan.hardware.Canvas Hash", "*");
        expected.put("pixelscan.hardware.AudioContext Hash", "*");
        return expected;
    }
    static String pixelscanBody() {
        return """
                Fingerprint Scan
                Your Browser Fingerprint is consistent
                Chrome 147.0.0.0 on Linux
                Browser
                Brazil / Jundiaí
                Location
                No proxy detected
                Proxy
                No masking detected
                Fingerprint
                No automated behavior detected
                Bot check
                What Websites See About You
                Location
                IP Address
                2001:db8:0:0:0:0:0:1
                WebRTC IP Address
                198.51.100.20, 2001:db8:0:0:0:0:0:1
                Country
                Brazil
                City
                Jundiaí
                ISP
                TELEF*NICA BRASIL S.A
                Latitude
                -23.1798
                Longitude
                -46.8816
                User Type by IP
                isp
                Date & Time
                Timezone from JS
                America/Sao_Paulo
                Daylight Savings Time
                No
                Time from JS
                Mon May 18 2026 08:16:50 GMT-0300
                Time from IP
                Mon May 18 2026 08:17:07 GMT-0300
                Screen
                Resolution
                1920x1080
                Available screen size
                1920x1040
                Fonts
                Font hash
                e5c5200c31cfeff31537a62552e5b18a
                User-Agent
                HTTP
                Mozilla/5.0 Chrome/147.0.0.0 Safari/537.36
                JavaScript
                Mozilla/5.0 Chrome/147.0.0.0 Safari/537.36
                Platform
                Linux x86_64
                DoNotTrack
                null
                HardwareConcurency
                16
                Language
                Languages from Javascript
                pt-BR
                Internationalization API
                pt-BR
                Accept-Language header
                pt-BR
                Hardware
                WebGL Vendor
                Google Inc. (NVIDIA Corporation)
                WebGL Version
                WebGL 1.0 (OpenGL ES 2.0 Chromium)
                WebGL Renderer
                ANGLE (NVIDIA Corporation, NVIDIA GeForce RTX 3060/PCIe/SSE2, OpenGL 4.5.0)
                WebGL Hash
                a456bf40dcd7c61cff770ba942109b39
                Canvas Hash
                7c61aebb7bb5d191d3947492e1fb7435
                AudioContext Hash
                ba6689f9a1550fb5eef25d1fc682c8c1
                """;
    }
}
