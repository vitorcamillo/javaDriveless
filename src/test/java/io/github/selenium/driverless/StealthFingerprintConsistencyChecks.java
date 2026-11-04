package io.github.selenium.driverless;

import org.junit.jupiter.api.*;

import static org.assertj.core.api.Assertions.*;

abstract class StealthFingerprintConsistencyChecks extends StealthFingerprintBrowserSurfaceChecks {
    // 60-69: CONSISTÊNCIA — Cross-check entre APIs
    // ════════════════════════════════════════════════════════════════

    @Test @Order(60)
    @DisplayName("[CONS] UA platform match navigator.platform")
    void uaPlatformMatch() {
        Object r = js("""
            (function(){
              var ua = navigator.userAgent;
              var plat = navigator.platform;
              if (ua.includes('Windows') && plat === 'Win32') return 'MATCH';
              if (ua.includes('Linux') && plat.includes('Linux')) return 'MATCH';
              if (ua.includes('Mac') && plat === 'MacIntel') return 'MATCH';
              return 'UA:'+ua.substring(0,40)+' PLAT:'+plat;
            })()""");
        System.out.println("  UA vs platform = " + r);
        assertThat(r.toString()).isEqualTo("MATCH");
    }

    @Test @Order(61)
    @DisplayName("[CONS] screen.available corresponde ao perfil")
    void screenAvailConsistent() {
        int ah = ((Number) js("screen.availHeight")).intValue();
        int h = ((Number) js("screen.height")).intValue();
        int aw = ((Number) js("screen.availWidth")).intValue();
        System.out.println("  availHeight=" + ah + " height=" + h);
        assertThat(h).isEqualTo(fingerprintProfile.screen.height);
        assertThat(ah).isEqualTo(fingerprintProfile.screen.availHeight);
        assertThat(aw).isEqualTo(fingerprintProfile.screen.availWidth);
    }

    @Test @Order(62)
    @DisplayName("[CONS] Nenhum prototype toString vazado")
    void prototypeToStringClean() {
        Object r = js("""
            (function(){
              var checks = [
                [Navigator.prototype,'hardwareConcurrency'],
                [Navigator.prototype,'deviceMemory'],
                [Navigator.prototype,'vendor'],
                [Screen.prototype,'width'],
                [Screen.prototype,'height']
              ];
              var issues = [];
              for (var c of checks) {
                var d = Object.getOwnPropertyDescriptor(c[0], c[1]);
                if (d && d.get) {
                  var s = d.get.toString();
                  if (s.includes('Proxy') || s.includes('phantompass') || s.includes('selenium')) {
                    issues.push(c[1]+':LEAKED');
                  }
                }
              }
              return issues.length === 0 ? 'CLEAN' : issues.join(',');
            })()""");
        System.out.println("  toString leaks = " + r);
        assertThat(r.toString()).isEqualTo("CLEAN");
    }

    @Test @Order(63)
    @DisplayName("[CONS] navigator.language é prefix do primeiro languages[]")
    void languageConsistency() {
        Object r = js("""
            (function(){
              var lang = navigator.language;
              var langs = navigator.languages;
              if (!langs || langs.length === 0) return 'NO_LANGS';
              return langs[0].startsWith(lang) || lang.startsWith(langs[0]) ? 'MATCH' : 'MISMATCH:'+lang+'!='+langs[0];
            })()""");
        System.out.println("  language consistency = " + r);
        assertThat(r.toString()).isEqualTo("MATCH");
    }

    @Test @Order(64)
    @DisplayName("[CONS] WebRTC respeita a política ICE do perfil")
    void webrtcExists() {
        Object r = driver.executeScript("""
            (async function(){
              if (typeof RTCPeerConnection === 'undefined') return {error:'NO_RTC'};
              try {
                var pc = new RTCPeerConnection({
                  iceServers:[{urls:'stun:stun.l.google.com:19302'}]
                });
                pc.createDataChannel('java-driverless-webrtc-probe');
                const candidates = [];
                pc.onicecandidate = event => {
                  if (event.candidate && event.candidate.candidate) candidates.push(event.candidate.candidate);
                };
                await pc.setLocalDescription(await pc.createOffer());
                await new Promise(resolve => {
                  if (pc.iceGatheringState === 'complete') return resolve();
                  const timeout = setTimeout(resolve, 8000);
                  pc.addEventListener('icegatheringstatechange', () => {
                    if (pc.iceGatheringState === 'complete') { clearTimeout(timeout); resolve(); }
                  });
                });
                pc.close();
                return {candidates};
              } catch(e) { return {error:e.message}; }
            })()""", null, true);
        System.out.println("  RTCPeerConnection = " + r);
        assertThat(r).isInstanceOf(java.util.Map.class);
        @SuppressWarnings("unchecked")
        java.util.Map<String, Object> result = (java.util.Map<String, Object>) r;
        assertThat(result).doesNotContainKey("error");
        if (fingerprintProfile.webRtc.disableUdp || fingerprintProfile.webRtc.protectLocalIps) {
            @SuppressWarnings("unchecked")
            java.util.List<String> candidates = (java.util.List<String>) result.get("candidates");
            assertThat(candidates).allMatch(candidate -> candidate.contains(" typ relay "))
                    .noneMatch(candidate -> candidate.contains(" typ host ")
                            || candidate.contains(" typ srflx ") || candidate.contains(" typ prflx "));
        }
    }

    @Test @Order(65)
    @DisplayName("[CONS] WebRTC RTCPeerConnection.name intacto")
    void webrtcPrototype() {
        Object r = js("""
            (function(){
              if (typeof RTCPeerConnection === 'undefined') return 'NO_RTC';
              return RTCPeerConnection.name === 'RTCPeerConnection' ? 'OK' : 'WRONG:'+RTCPeerConnection.name;
            })()""");
        System.out.println("  RTCPeerConnection.name = " + r);
        assertThat(r.toString()).isEqualTo("OK");
    }

    @Test @Order(66)
    @DisplayName("[CONS] mediaDevices.enumerateDevices resolve e não vaza hardware")
    void mediaDevicesEnumerate() {
        Object r = driver.executeScript("""
            (async function(){
              if (!navigator.mediaDevices) return {error:'NO_MEDIADEVICES'};
              if (typeof navigator.mediaDevices.enumerateDevices !== 'function') return {error:'NO_ENUMERATE_FN'};
              const snapshot = devices => devices.map(device => ({
                kind:device.kind, deviceId:device.deviceId, groupId:device.groupId, label:device.label,
                nativeTag:Object.prototype.toString.call(device),
                nativePrototype:device instanceof MediaDeviceInfo
              }));
              const first = snapshot(await navigator.mediaDevices.enumerateDevices());
              const second = snapshot(await navigator.mediaDevices.enumerateDevices());
              return {first, stable:JSON.stringify(first) === JSON.stringify(second)};
            })()""", null, true);
        System.out.println("  enumerateDevices = " + r);
        assertThat(r).isInstanceOf(java.util.Map.class);
        @SuppressWarnings("unchecked")
        java.util.Map<String, Object> result = (java.util.Map<String, Object>) r;
        assertThat(result).doesNotContainKey("error");
        assertThat(result.get("stable")).isEqualTo(true);
        @SuppressWarnings("unchecked")
        java.util.List<java.util.Map<String, Object>> devices =
                (java.util.List<java.util.Map<String, Object>>) result.get("first");
        assertThat(devices).allSatisfy(device -> {
            assertThat(device.get("nativeTag")).isIn("[object MediaDeviceInfo]", "[object InputDeviceInfo]");
            assertThat(device.get("nativePrototype")).isEqualTo(true);
        });
        if ("spoof".equalsIgnoreCase(fingerprintProfile.mediaDevices.mode)) {
            assertThat(devices.stream().filter(device -> "audioinput".equals(device.get("kind"))).count())
                    .isLessThanOrEqualTo(fingerprintProfile.mediaDevices.audioInputs);
            assertThat(devices.stream().filter(device -> "audiooutput".equals(device.get("kind"))).count())
                    .isLessThanOrEqualTo(fingerprintProfile.mediaDevices.audioOutputs);
            assertThat(devices.stream().filter(device -> "videoinput".equals(device.get("kind"))).count())
                    .isLessThanOrEqualTo(fingerprintProfile.mediaDevices.videoInputs);
            assertThat(devices).allSatisfy(device -> {
                String id = String.valueOf(device.get("deviceId"));
                String group = String.valueOf(device.get("groupId"));
                String label = String.valueOf(device.get("label"));
                assertThat(id).matches("^$|default|communications|[a-f0-9]{64}");
                assertThat(group).matches("^$|[a-f0-9]{64}");
                assertThat(label).matches("^$|Microphone(?: \\d+)?|Speakers(?: \\d+)?|Camera(?: \\d+)?");
            });
        }
    }

    @Test @Order(67)
    @DisplayName("[CONS] devicePixelRatio repete o perfil")
    void dprValid() {
        double dpr = ((Number) js("window.devicePixelRatio || 0")).doubleValue();
        System.out.println("  devicePixelRatio = " + dpr);
        assertThat(dpr).isEqualTo(fingerprintProfile.screen.deviceScaleFactor);
    }

    @Test @Order(68)
    @DisplayName("[CONS] Port scanning: fetch/XHR não alcançam porta protegida real")
    void portScanConfig() {
        assertThat(fingerprintProfile.ports.mode).isEqualTo("protect");
        assertThat(fingerprintProfile.ports.protectedPorts).contains(protectedPortProbePort);
        driver.get("http://127.0.0.1:" + protectedPortProbePort + "/");
        driver.sleep(0.5);
        protectedPortProbePaths.removeIf(path -> !"/".equals(path));

        Object r = driver.executeScript("""
            (async function(){
              var marker = Symbol.for(['a11y', 'layout', 'ctx'].join('.'));
              if (typeof window[marker] !== 'undefined') return 'DETECTABLE_MARKER';
              if (typeof window[Symbol.for('__jdl_storage__')] !== 'undefined') return 'LEGACY_STORAGE_LEAK';
              let fetchBlocked = false;
              try { await fetch('/fetch-probe'); } catch (error) { fetchBlocked = error instanceof TypeError; }
              var xhr = new XMLHttpRequest();
              const xhrResult = await new Promise(resolve => {
                xhr.onerror = () => resolve('blocked');
                xhr.onload = () => resolve('reached');
                xhr.open('GET', '/xhr-probe');
                xhr.send();
                setTimeout(() => resolve('timeout'), 3000);
              });
              const ownLeak = Object.getOwnPropertyNames(xhr).some(name => /blocked/i.test(name));
              return {fetchBlocked, xhrResult, ownLeak};
            })()""", null, true);
        System.out.println("  Port protection = " + r);
        assertThat(r).isInstanceOf(java.util.Map.class);
        @SuppressWarnings("unchecked")
        java.util.Map<String, Object> result = (java.util.Map<String, Object>) r;
        assertThat(result.get("fetchBlocked")).isEqualTo(true);
        assertThat(result.get("xhrResult")).isEqualTo("blocked");
        assertThat(result.get("ownLeak")).isEqualTo(false);
        driver.sleep(0.3);
        assertThat(protectedPortProbePaths).doesNotContain("/fetch-probe", "/xhr-probe");
    }
}
