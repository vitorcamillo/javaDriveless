package io.github.selenium.javaDriverless.fpmapping;

final class FingerprintMappingSiteBodies {
    private FingerprintMappingSiteBodies() {
    }

    static String browserscanBody() {
        return """
                Browser Fingerprint & Trust Score Analyzer
                Risk assessment complete
                Scan complete
                82
                B
                STATUS
                Moderate Risk
                Powered by BrowserScan Trust Algorithm
                IDENTITY
                Digital fingerprint snapshot
                IP ADDRESS
                203.0.113.210
                ASN
                AS40065 Sharktech
                LOCATION
                Los Angeles, US
                BROWSER
                Chrome 142
                OS
                Mac OS X 10.15
                DEVICE
                Desktop
                RISK BOARD
                WebRTC Leak
                Status:
                LEAK
                Proxy Detection
                Proxy signature detected
                Timezone Consistency
                FAIL
                PROTOCOL FINGERPRINTS
                TLS JA3
                e7d705a3286e19ea42f55823113e2755
                TLS VERSION
                TLS 1.3
                HTTP VERSION
                h3
                TCP OS GUESS
                Linux 5.x
                WEBRTC
                Status
                LEAK
                IP
                203.0.113.57
                Region
                CN
                DNS
                Status
                SAFE
                Servers
                1.1.1.1
                CONSISTENCY MATRIX
                Timezone vs IP
                Language vs Locale
                HARDWARE SURFACE
                CANVAS HASH
                a1b2c3d4
                WEBGL RENDERER
                ANGLE
                SOFTWARE SURFACE
                FONTS HASH
                f9e8d7c6
                TIMEZONE
                Asia/Shanghai
                LANGUAGES
                en-US, zh-CN
                filler filler filler filler filler filler filler filler filler filler filler filler
                """;
    }
    static String ipheyBody() {
        return """
                Your Digital Identity Looks Unreliable
                See what your browser reveals about you. Run a real-time browser fingerprinting test to detect tracking risks and hidden leaks.
                BROWSER
                Click here to see
                LOCATION
                Brazil
                IP ADDRESS
                2001:db8:0:0:0:0:0:1
                HARDWARE
                Everything is fine
                SOFTWARE
                Everything is fine
                Detected the following processes on your device: Dolphin Anty
                Fingerprint check
                IP Check
                VPN Checker
                Bot Check
                DNS Leak Test
                VPN is working as expected. IP address leak test.
                filler filler filler filler filler filler filler filler filler filler filler filler
                """;
    }
    static String printleaksBody() {
        return """
                PrintLeaks
                Browser Fingerprint Analysis Tool
                Collect Fingerprints
                Collection complete
                55
                TOTAL COLLECTORS
                54
                SUCCEEDED
                1
                ERRORS
                System
                Font
                Colors
                CanvasText
                audio audioinput audiooutput
                Canvas
                WebGL
                WebRTC
                Navigator
                Permissions
                Plugins
                Storage
                GPU
                hardware concurrency
                device memory
                filler filler filler filler filler filler filler filler filler filler filler filler
                """;
    }
    static String creepjsBody(String likeHeadless, String headless) {
        return """
                FP ID: abc
                Fuzzy: def
                WebRTCabc
                candidate:1 1 udp 1677734911 203.0.113.20 50000 typ relay
                Timezone123
                Intl123
                Headless123
                __LIKE_HEADLESS__
                __HEADLESS__
                0% stealth: clean
                ServiceWorkerGlobalScope
                Worker
                gpu:
                Google Inc. (Mesa)
                ANGLE (Mesa, llvmpipe (LLVM 20.1.2 256 bits), OpenGL 4.5)
                cores: 16, ram: 32
                WebGLecf168f0
                images:469dac5a
                pixels:a8ee71dc
                params (78): e6cd09ba
                exts (67): 096fe525
                gpu:
                confidence: high
                Google Inc. (Mesa)
                ANGLE (Mesa, llvmpipe (LLVM 20.1.2 256 bits), OpenGL 4.5)
                Screen
                Canvas 2d0123abcd
                data: 89abcdef
                rendering:
                textMetrics:
                0.014635898437500001
                <strong>Canvas 2d</strong><span class="hash">0123abcd</span>
                <br>text: 32f2431d
                <br>emoji: 343e70fc
                <br>paint (GPU): 285e3cf1
                <br>paint (CPU): 285e3cf1
                <br>combined: 178c2768
                <strong>Fonts</strong><span class="hash">6a39161f</span>
                Fonts123
                Audio123
                Speech123
                system styles
                css media
                Navigator123
                permissions (5)
                storage: 10GB
                filler filler filler filler filler filler filler filler filler filler filler filler
                """.replace("__LIKE_HEADLESS__", likeHeadless).replace("__HEADLESS__", headless);
    }
    static String checkup000rBody() {
        return """
                Fingerprint Checkup
                Check what information your browser reveals
                Your IP Address
                Location: Campinas, Sao Paulo, BR
                ISP: AS26599
                JavaScript
                WebRTC
                Canvas
                WebGL
                Fonts
                SSL/TLS
                DNS
                Browser Information
                User Agent: Mozilla/5.0
                Browser: Chrome
                Version: 147.0.0.0
                Language: pt-BR
                Languages: pt-BR, pt, en-US, en
                System Information
                Platform: Linux x86_64
                CPU Cores: 16
                Memory: 32 GB
                Timezone: America/Sao_Paulo
                UTC Offset: -3 hours
                Screen & Display
                Resolution: 1920x1080
                Viewport: 1342x750
                Privacy Summary
                Safe: 1
                Warnings: 3
                Threats: 0
                Score: Medium
                """;
    }
    static String browsercheckBody(String realness, String riskSummary, String botSection) {
        return """
                Browser Check
                Browser Fingerprint Check
                Trust Score
                Browser Fingerprint Realness
                __REALNESS__
                __RISK_SUMMARY__
                Public IP Address
                203.0.113.10
                Proxy Detected
                No
                SYSTEM LOG
                [NETWORK]
                [WEBGL]
                [CANVAS]
                [FONTS]
                Risk Analysis Report
                IP Blacklist
                Bot Detection
                Proxy Check
                WebRTC Leak
                BROWSER & SYSTEM
                Browser
                User Agent
                Language
                HARDWARE
                Screen Resolution
                Device Memory
                CPU Cores
                GPU Vendor
                GPU Renderer
                FINGERPRINTS
                Visitor ID
                Canvas Hash
                WebGL Hash
                Fonts Hash
                NETWORK ANALYSIS
                WebRTC Public IP
                DNS Leak
                TIME & LOCATION
                IP Timezone
                America/Sao_Paulo
                System Timezone
                America/Sao_Paulo
                SOFTWARE
                Cookies
                Incognito
                __BOT_SECTION__
                """.replace("__REALNESS__", realness)
                .replace("__RISK_SUMMARY__", riskSummary)
                .replace("__BOT_SECTION__", botSection);
    }
    static String antidetectTimezoneBody() {
        return """
                Timezone Consistency Mismatch & Check IP
                Browser Timezone
                America/Sao_Paulo
                UTC -3
                IP Location
                Unable to detect
                Unable to fetch IP location data.
                What This Tool Tests
                Browser timezone
                System time offset
                IP geolocation timezone
                UTC offset consistency
                Why Timezone Consistency Matters
                Timezone mismatch is suspicious.
                Proxy/VPN masking errors
                How It Works
                checks your public IP timezone
                """;
    }
    static String trustmyipTimezoneUnresolvedBody() {
        return """
                TrustMyIP.com
                TEMPORAL FORENSIC SCANNER ACTIVE
                Timezone Dissonance Test
                Free IP vs Browser Timezone Check
                IP vs Browser Timezone Check
                RUN TIMEZONE DISSONANCE TEST
                2001:db8:0:0:0:0:0:1
                IP TIMEZONE
                Not Available
                BROWSER TIMEZONE
                America/Sao_Paulo (UTC-3:00)
                UTC OFFSET DIFF
                3 hours
                DISSONANCE RESULT
                UNABLE TO VERIFY
                TEMPORAL FORENSIC AUDIT LOG
                [GEOIP] IP Timezone -> Not Available
                [QUERY] Intl.DateTimeFormat().resolvedOptions().timeZone -> America/Sao_Paulo
                [QUERY] new Date().getTimezoneOffset() -> 180 min (UTC-3:00)
                [QUERY] Date().toString() -> Mon May 18 2026 13:42:20 GMT-0300
                [CALC] UTC Offset Difference -> 180 minutes (3 hours)
                [DST] Browser observes DST -> No
                [INFO] IP timezone could not be determined. GeoIP lookup failed.
                SUMMARY
                Your IP address is 2001:db8:0:0:0:0:0:1.
                Your browser timezone is America/Sao_Paulo (UTC-3:00).
                """;
    }
    static String trustmyipTimezoneMatchBody() {
        return """
                TrustMyIP.com
                TEMPORAL FORENSIC SCANNER ACTIVE
                Timezone Dissonance Test
                Free IP vs Browser Timezone Check
                IP vs Browser Timezone Check
                RUN TIMEZONE DISSONANCE TEST
                IP TIMEZONE
                Europe/Berlin (UTC+2:00)
                BROWSER TIMEZONE
                Europe/Berlin (UTC+2:00)
                UTC OFFSET DIFF
                0 hours (Match)
                DISSONANCE RESULT
                MATCH - No Dissonance
                TEMPORAL FORENSIC AUDIT LOG
                [IP] Your IP Address -> 203.0.113.245 | Frankfurt am Main, Hesse, Germany
                [GEOIP] IP Timezone -> Europe/Berlin (UTC+2:00)
                [QUERY] Intl.DateTimeFormat().resolvedOptions().timeZone -> Europe/Berlin
                [QUERY] new Date().getTimezoneOffset() -> -120 min (UTC+2:00)
                [QUERY] Date().toString() -> Mon May 18 2026 19:43:17 GMT+0200
                [CALC] UTC Offset Difference -> 0 minutes (0 hours)
                [DST] Browser observes DST -> Yes
                [PASS] All temporal markers are synchronized. No timezone dissonance detected.
                SUMMARY
                Your IP address is 203.0.113.245 located in Frankfurt am Main, Hesse, Germany.
                Your IP geolocation timezone is Europe/Berlin (UTC+2:00).
                Your browser reports Europe/Berlin (UTC+2:00).
                These timezones match perfectly - no timezone dissonance detected.
                """;
    }
    static String coveryourtracksStuckBody() {
        return """
                Cover Your Tracks
                See how trackers view your browser
                TESTING YOUR BROWSER
                This project allows you to understand how easy it is to identify and track your browser.
                filler filler filler filler filler filler filler filler filler filler filler filler
                filler filler filler filler filler filler filler filler filler filler filler filler
                """;
    }
    static String amiuniqueBody() {
        return """
                MY BROWSER FINGERPRINT
                SEE YOUR BROWSER FINGERPRINT PROPERTIES
                ARE YOU UNIQUE ?
                Yes! You are unique among fingerprints in our entire dataset.
                Operating system
                Web browser
                Language
                Timezone
                HTTP HEADERS ATTRIBUTES
                Attribute
                Similarity ratio
                Value
                JAVASCRIPT ATTRIBUTES
                User agent
                Platform
                Cookies enabled
                Canvas
                List of fonts
                fonts
                Screen width
                Screen height
                Screen depth
                Permissions
                geolocation
                notifications
                WebGL Vendor
                WebGL Renderer
                WebGL Parameters
                local storage
                session storage
                IndexedDB
                Audio formats
                Audio context
                """;
    }
}
