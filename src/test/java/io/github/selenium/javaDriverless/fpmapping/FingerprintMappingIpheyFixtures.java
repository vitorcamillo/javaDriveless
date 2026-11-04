package io.github.selenium.javaDriverless.fpmapping;

import java.util.LinkedHashMap;
import java.util.Map;

final class FingerprintMappingIpheyFixtures {
    static final String USER_AGENT = "Mozilla/5.0 (X11; Linux x86_64) Chrome/150.0.0.0";
    static final String GPU = "ANGLE (Intel, Mesa Intel UHD Graphics, OpenGL 4.6)";
    static final String PUBLIC_IP = "203.0.113.10";

    private FingerprintMappingIpheyFixtures() {
    }

    static Map<String, String> expected() {
        return Map.ofEntries(
                Map.entry("userAgent", USER_AGENT),
                Map.entry("platform", "Linux x86_64"),
                Map.entry("uaPlatform", "Linux"),
                Map.entry("run.timezone", "America/Sao_Paulo"),
                Map.entry("screen.resolution", "1365x900"),
                Map.entry("webgl.unmaskedRenderer", GPU),
                Map.entry("deviceMemory", "8"),
                Map.entry("hardwareConcurrency", "8"),
                Map.entry("iphey.verdict", "Your Digital Identity Looks Trustworthy"),
                Map.entry("iphey.bot.booleanPattern", "^(?:true|false)$"),
                Map.entry("iphey.bot.hasWebdriver", "false"),
                Map.entry("webRtc.publicIp", PUBLIC_IP),
                Map.entry("geolocation.latitude", "-23.1857"),
                Map.entry("geolocation.longitude", "-46.8978"));
    }

    static Map<String, Object> domEvidence() {
        FingerprintSiteContracts.Site contract = FingerprintSiteContracts.site("iphey");
        Map<String, Object> evidence = new LinkedHashMap<>();
        for (FingerprintSiteContracts.RequiredDom field : contract.requiredDom) {
            evidence.put(field.id, Map.of(
                    "matched", true,
                    "tag", "div",
                    "selector", field.selectors.isEmpty() ? "#fixture" : field.selectors.get(0),
                    "text", field.terms.get(0)));
        }
        return evidence;
    }

    static String detailedBody(String timezone, String gpu, String userAgent, String webRtcIp) {
        return """
                Your Digital Identity Looks Trustworthy
                SIGNALS
                BROWSER
                HARDWARE
                LOCATION
                SOFTWARE
                NETWORK
                BOT
                SIGNALS
                status
                Not detected
                BROWSER
                User Agent
                %s
                Name
                Chrome
                OS
                Linux x86_64
                Languages
                en-US, en
                Language Tag
                en-US
                Timezone
                %s
                Timezone Offset
                GMT-3
                Timezone Text
                Brasilia Standard Time
                HARDWARE
                Audio
                audio-hash
                WebGL
                webgl-hash
                Resolution
                1365x900
                GPU
                %s
                Device Memory
                8
                Hardware Concurrency
                8
                Architecture
                x86
                Media Devices
                audioinput, audiooutput
                Storage
                280
                LOCATION
                IP
                203.0.113.10
                Country Code
                BR
                Country
                Brazil
                State
                Sao Paulo
                City
                Example City
                Timezone
                %s
                Timezone Offset
                GMT-3
                Geolocation
                -23.1857,-46.8978
                Risk Score
                4
                Risk
                low
                ISP Risk
                low
                ISP Risk Score
                4
                Datacenter
                false
                VPN
                false
                Blacklisted External
                false
                Amazon AWS
                false
                Apple iCloud Private Relay
                false
                Google
                false
                SOFTWARE
                Timing Resolution
                0.1, 0.2
                Stack Overflow Counter
                2786
                Plugins
                plugins-hash
                Fonts
                fonts-hash
                Canvas
                canvas-hash
                NETWORK
                User Agent
                %s
                Name
                Chrome
                OS
                Linux
                Architecture
                amd64
                Version
                150.0.0.0
                WebRTC IP
                %s
                Accept Language
                en-US,en;q=0.9
                BOT
                hasCDP
                false
                hasWebdriver
                false
                hasUserAgent
                true
                hasNavigator
                true
                report report report report report report report report report report
                """.formatted(userAgent, timezone, gpu, timezone, userAgent, webRtcIp);
    }
}
