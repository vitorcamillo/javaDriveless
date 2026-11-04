package io.github.selenium.driverless;

import org.junit.jupiter.api.*;

import static org.assertj.core.api.Assertions.*;

abstract class StealthFingerprintBrowserSurfaceChecks extends StealthFingerprintBrowserRenderingChecks {
    // 40-49: AUDIO & MEDIA
    // ════════════════════════════════════════════════════════════════

    @Test @Order(40)
    @DisplayName("[AUD] AudioBuffer aplica exatamente o modo de áudio do perfil")
    void audioContext() {
        String script = """
                (function(){
                  try {
                    var AudioContextCtor = window.AudioContext || window.webkitAudioContext;
                    if (!AudioContextCtor || typeof AudioBuffer === 'undefined') return {error:'NO_AUDIO'};
                    var context = new AudioContextCtor();
                    var length = 64;
                    var clean = new Float32Array(length);
                    for (var i = 0; i < length; i++) clean[i] = ((i % 17) - 8) / 32;
                    var makeBuffer = function(){
                      var buffer = context.createBuffer(1, length, 44100);
                      buffer.copyToChannel(clean, 0);
                      return buffer;
                    };
                    var firstBuffer = makeBuffer();
                    var secondBuffer = makeBuffer();
                    var first = firstBuffer.getChannelData(0);
                    var again = firstBuffer.getChannelData(0);
                    var second = secondBuffer.getChannelData(0);

                    var expected = clean.slice();
                    var h = ((__SEED__ ^ 0x5A3C9E2B) >>> 0);
                    for (var j = 0; j < expected.length; j++) {
                      h = Math.imul(h ^ ((expected[j] * 1e6) | 0), 0x01000193) >>> 0;
                    }
                    var state = (h ^ expected.length) >>> 0;
                    var random = function(){
                      state = (Math.imul(1664525, state) + 1013904223) >>> 0;
                      return state / 4294967296;
                    };
                    for (var k = 0; k < expected.length; k++) {
                      expected[k] += (random() * 2 - 1) * __NOISE__;
                    }

                    var stable = first === again;
                    var exact = true;
                    var changed = false;
                    for (var n = 0; n < length; n++) {
                      stable = stable && first[n] === again[n] && first[n] === second[n];
                      exact = exact && first[n] === expected[n];
                      changed = changed || first[n] !== clean[n];
                    }
                    var source = Function.prototype.toString.call(AudioBuffer.prototype.getChannelData);
                    context.close();
                    return {
                      contextTag:Object.prototype.toString.call(context),
                      bufferTag:Object.prototype.toString.call(firstBuffer),
                      sampleRate:context.sampleRate,
                      sourceNative:source.includes('[native code]'),
                      stable:stable,
                      exact:exact,
                      changed:changed
                    };
                  } catch (error) { return {error:error.message}; }
                })()
                """
                .replace("__SEED__", Integer.toString(fingerprintProfile.noiseSeed))
                .replace("__NOISE__", Double.toString(fingerprintProfile.audio.noise));
        Object r = js(script);
        System.out.println("  AudioContext = " + r);
        assertThat(r).isInstanceOf(java.util.Map.class);
        @SuppressWarnings("unchecked")
        java.util.Map<String, Object> audio = (java.util.Map<String, Object>) r;
        assertThat(audio).doesNotContainKey("error");
        assertThat(audio.get("contextTag")).isEqualTo("[object AudioContext]");
        assertThat(audio.get("bufferTag")).isEqualTo("[object AudioBuffer]");
        assertThat(((Number) audio.get("sampleRate")).doubleValue()).isPositive();
        assertThat(audio.get("stable")).isEqualTo(true);
        if ("noise".equalsIgnoreCase(fingerprintProfile.audio.mode)
                && fingerprintProfile.audio.noise > 0) {
            assertThat(audio.get("sourceNative")).isEqualTo(false);
            assertThat(audio.get("exact")).isEqualTo(true);
            assertThat(audio.get("changed")).isEqualTo(true);
        } else {
            assertThat(audio.get("sourceNative")).isEqualTo(true);
            assertThat(audio.get("changed")).isEqualTo(false);
        }
    }

    @Test @Order(41)
    @DisplayName("[AUD] navigator.mediaDevices existe")
    void mediaDevices() {
        Object r = js("typeof navigator.mediaDevices !== 'undefined'");
        System.out.println("  mediaDevices = " + r);
        assertThat(r).isEqualTo(true);
    }

    // ════════════════════════════════════════════════════════════════
    // 50-59: WINDOW & DOM — Objetos Chrome, Permissions
    // ════════════════════════════════════════════════════════════════

    @Test @Order(50)
    @DisplayName("[DOM] window.chrome preserva exatamente a superfície nativa de página")
    void chromeObject() {
        Object r = js("""
                (function(){
                  var descriptor = Object.getOwnPropertyDescriptor(window, 'chrome');
                  var keys = window.chrome ? Object.keys(window.chrome).sort() : [];
                  return {
                    tag:Object.prototype.toString.call(window.chrome),
                    keys:keys.join('|'),
                    writable:descriptor && descriptor.writable,
                    enumerable:descriptor && descriptor.enumerable,
                    configurable:descriptor && descriptor.configurable,
                    loadTimesNative:!!window.chrome && typeof window.chrome.loadTimes === 'function'
                      && Function.prototype.toString.call(window.chrome.loadTimes).includes('[native code]'),
                    csiNative:!!window.chrome && typeof window.chrome.csi === 'function'
                      && Function.prototype.toString.call(window.chrome.csi).includes('[native code]')
                  };
                })()
                """);
        System.out.println("  chrome = " + r);
        assertThat(r).isInstanceOf(java.util.Map.class);
        @SuppressWarnings("unchecked")
        java.util.Map<String, Object> chrome = (java.util.Map<String, Object>) r;
        assertThat(chrome.get("tag")).isEqualTo("[object Object]");
        assertThat(chrome.get("keys")).isEqualTo("app|csi|loadTimes");
        assertThat(chrome.get("writable")).isEqualTo(true);
        assertThat(chrome.get("enumerable")).isEqualTo(true);
        assertThat(chrome.get("configurable")).isEqualTo(false);
        assertThat(chrome.get("loadTimesNative")).isEqualTo(true);
        assertThat(chrome.get("csiNative")).isEqualTo(true);
    }

    @Test @Order(51)
    @DisplayName("[DOM] Permissions.query é nativa e coerente com Notification")
    void permissionsQuery() {
        Object r = driver.executeScript("""
            (async function(){
              if (!navigator.permissions) return {error:'NO_PERM'};
              try {
                const permission = await navigator.permissions.query({name:'notifications'});
                return {
                  state: permission.state,
                  notification: Notification.permission,
                  nativeQuery: Function.prototype.toString.call(
                    Object.getPrototypeOf(navigator.permissions).query
                  ).includes('[native code]')
                };
              } catch (error) { return {error:error.message}; }
            })()""", null, true);
        System.out.println("  Permissions.query = " + r);
        assertThat(r).isInstanceOf(java.util.Map.class);
        @SuppressWarnings("unchecked")
        java.util.Map<String, Object> permission = (java.util.Map<String, Object>) r;
        assertThat(permission).doesNotContainKey("error");
        assertThat(permission.get("nativeQuery")).isEqualTo(true);
        String notification = fingerprintProfile.permissions.notifications;
        String expectedState = "default".equals(notification) ? "prompt" : notification;
        assertThat(permission.get("notification")).isEqualTo(notification);
        assertThat(permission.get("state")).isEqualTo(expectedState);
    }

    @Test @Order(52)
    @DisplayName("[DOM] Notification API existe")
    void notificationApi() {
        Object r = js("typeof Notification !== 'undefined'");
        System.out.println("  Notification = " + r);
        assertThat(r).isEqualTo(true);
    }

    @Test @Order(53)
    @DisplayName("[DOM] Performance.now permanece nativo e monotônico")
    void performanceNow() {
        Object r = js("""
                (function(){
                  var values = [];
                  for (var i = 0; i < 256; i++) values.push(performance.now());
                  var monotonic = values.every(function(value, index){
                    return index === 0 || value >= values[index - 1];
                  });
                  return {
                    tag:Object.prototype.toString.call(performance),
                    source:Function.prototype.toString.call(Performance.prototype.now),
                    own:Object.prototype.hasOwnProperty.call(performance, 'now'),
                    timeOriginFinite:Number.isFinite(performance.timeOrigin) && performance.timeOrigin > 0,
                    monotonic:monotonic
                  };
                })()
                """);
        System.out.println("  Performance = " + r);
        assertThat(r).isInstanceOf(java.util.Map.class);
        @SuppressWarnings("unchecked")
        java.util.Map<String, Object> performance = (java.util.Map<String, Object>) r;
        assertThat(performance.get("tag")).isEqualTo("[object Performance]");
        assertThat(performance.get("source")).isEqualTo("function now() { [native code] }");
        assertThat(performance.get("own")).isEqualTo(false);
        assertThat(performance.get("timeOriginFinite")).isEqualTo(true);
        assertThat(performance.get("monotonic")).isEqualTo(true);
    }

    // ════════════════════════════════════════════════════════════════
}
