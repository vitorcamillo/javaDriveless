package io.github.selenium.javaDriverless.scripts;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Assumptions;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static io.github.selenium.javaDriverless.scripts.FingerprintSurfaceRuntimeSupport.*;

class FingerprintSurfaceRuntimeTest {
    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Test
    void nativeMatchingNavigatorScreenAndStorageSurfacesRemainUntouched() throws Exception {
        String prelude = """
                'use strict';
                globalThis.self = globalThis;
                class Navigator {}
                const nativeLanguage = function language() {
                  if (!(this instanceof Navigator)) throw new TypeError('Illegal invocation');
                  return 'en-US';
                };
                const nativeLanguages = function languages() {
                  if (!(this instanceof Navigator)) throw new TypeError('Illegal invocation');
                  return ['en-US', 'en'];
                };
                Object.defineProperties(Navigator.prototype, {
                  vendor:{get(){return 'Google Inc.'}, configurable:true, enumerable:true},
                  hardwareConcurrency:{get(){return 8}, configurable:true, enumerable:true},
                  deviceMemory:{get(){return 8}, configurable:true, enumerable:true},
                  maxTouchPoints:{get(){return 0}, configurable:true, enumerable:true},
                  webdriver:{get(){return false}, configurable:true, enumerable:true},
                  language:{get:nativeLanguage, configurable:true, enumerable:true},
                  languages:{get:nativeLanguages, configurable:true, enumerable:true}
                });
                const navigator = new Navigator();
                class Screen {}
                const screenValues = {width:1365,height:900,availWidth:1365,availHeight:860,colorDepth:24,pixelDepth:24};
                const nativeScreenGetters = {};
                for (const [name, value] of Object.entries(screenValues)) {
                  const getter = function() {
                    if (!(this instanceof Screen)) throw new TypeError('Illegal invocation');
                    return value;
                  };
                  nativeScreenGetters[name] = getter;
                  Object.defineProperty(Screen.prototype, name, {get:getter, configurable:true, enumerable:true});
                }
                const screen = new Screen();
                class StorageManager { async estimate() { return {quota:123, usage:7}; } }
                const storageManager = new StorageManager();
                Object.defineProperty(Navigator.prototype, 'storage', {
                  get() { return storageManager; }, configurable:true, enumerable:true
                });
                globalThis.Navigator = Navigator;
                Object.defineProperty(globalThis, 'navigator', {
                  value:navigator, configurable:true, writable:true
                });
                globalThis.Screen = Screen;
                globalThis.screen = screen;
                globalThis.StorageManager = StorageManager;
                globalThis.window = globalThis;
                globalThis.devicePixelRatio = 1;
                globalThis.innerWidth = 1365;
                globalThis.innerHeight = 765;
                globalThis.outerWidth = 1365;
                globalThis.outerHeight = 852;
                globalThis.location = {origin:'https://example.test', href:'https://example.test/'};
                globalThis.document = {readyState:'complete', documentElement:{}, querySelectorAll(){return []}};
                const nativeEstimate = StorageManager.prototype.estimate;
                """;
        String profile = """
                {"locale":"en-US","languages":["en-US","en"],"vendor":"Google Inc.",
                 "hardwareConcurrency":8,"deviceMemory":8,"maxTouchPoints":0,
                 "screen":{"mode":"spoof","width":1365,"height":900,"availWidth":1365,
                           "availHeight":860,"colorDepth":24,"pixelDepth":24,"deviceScaleFactor":1},
                 "webgl":{"mode":"real"},"webRtc":{"mode":"real"},
                 "mediaDevices":{"mode":"real"},"speech":{"mode":"real"},
                 "audio":{"mode":"real"},"canvas":{"mode":"real"},
                 "clientRects":{"mode":"real"},"fonts":{"mode":"real"},
                 "ports":{"mode":"real"},"connection":{"mode":"real"},
                 "storage":{"mode":"real"}}
                """;
        String assertions = """
                assertSame(Object.getOwnPropertyDescriptor(Navigator.prototype, 'language').get, nativeLanguage);
                assertSame(Object.getOwnPropertyDescriptor(Navigator.prototype, 'languages').get, nativeLanguages);
                for (const [name, getter] of Object.entries(nativeScreenGetters)) {
                  assertSame(Object.getOwnPropertyDescriptor(Screen.prototype, name).get, getter);
                }
                assertSame(StorageManager.prototype.estimate, nativeEstimate);
                function assertSame(actual, expected) {
                  if (actual !== expected) throw new Error('native surface replaced');
                }
                """;

        assertNodeRuns(prelude + SeleniumProxySurfaceScripts.build(profile) + assertions);
    }

    @Test
    void workerUsesProfileForNativeUaIntlConnectionAndStorageSurfaces() throws Exception {
        String prelude = """
                'use strict';
                globalThis.self = globalThis;
                class NetworkInformation {}
                Object.assign(NetworkInformation.prototype, {
                  effectiveType:'4g', downlink:99, rtt:1, saveData:false, type:'wifi'
                });
                class StorageManager { async estimate() { return { quota: 1, usage: 1 }; } }
                class NavigatorUAData {
                  get brands() { return []; }
                  get mobile() { return false; }
                  get platform() { return 'Linux'; }
                  async getHighEntropyValues() { return {
                    platform:'Linux', architecture:'x86', mobile:false,
                    brands:[{brand:'Native Grease', version:'8'}]
                  }; }
                }
                const uaData = new NavigatorUAData();
                class WorkerNavigator {}
                Object.defineProperties(WorkerNavigator.prototype, {
                  hardwareConcurrency:{get(){return 2}, configurable:true},
                  deviceMemory:{get(){return 2}, configurable:true},
                  platform:{get(){return 'Linux x86_64'}, configurable:true},
                  languages:{get(){return ['en-US']}, configurable:true},
                  language:{get(){return 'en-US'}, configurable:true},
                  userAgent:{get(){return 'native-UA'}, configurable:true},
                  userAgentData:{get(){return uaData}, configurable:true},
                  connection:{get(){return new NetworkInformation()}, configurable:true},
                  storage:{get(){return new StorageManager()}, configurable:true}
                });
                globalThis.WorkerNavigator = WorkerNavigator;
                globalThis.NetworkInformation = NetworkInformation;
                globalThis.StorageManager = StorageManager;
                Object.defineProperty(globalThis, 'navigator', {
                  value: new WorkerNavigator(), configurable: true, writable: true
                });
                """;
        String assertions = """
                (async () => {
                  if (!navigator.userAgent.includes('Chrome/147.0.0.0')) throw new Error('UA mismatch');
                  if (navigator.userAgentData.platform !== 'Windows') throw new Error('UA-CH platform mismatch');
                  const high = await navigator.userAgentData.getHighEntropyValues(
                    ['architecture','formFactors','fullVersionList']);
                  if (high.architecture !== 'x86' || !high.fullVersionList[1].version.startsWith('147.')) {
                    throw new Error('UA-CH high entropy mismatch');
                  }
                  if (JSON.stringify(high.formFactors) !== JSON.stringify(['Desktop'])) {
                    throw new Error('UA-CH formFactors mismatch');
                  }
                  if (high.platform !== 'Windows' || high.mobile !== false ||
                      high.brands[0].brand !== 'Not(A:Brand') {
                    throw new Error('UA-CH native high entropy leak');
                  }
                  if (new Intl.DateTimeFormat().resolvedOptions().timeZone !== 'America/Sao_Paulo') {
                    throw new Error('timezone mismatch');
                  }
                  if (!new Intl.NumberFormat().resolvedOptions().locale.toLowerCase().startsWith('pt-br')) {
                    throw new Error('Intl locale mismatch');
                  }
                  if (navigator.connection.downlink !== 5 || navigator.connection.rtt !== 75 ||
                      navigator.connection.effectiveType !== '3g') throw new Error('connection mismatch');
                  const estimateA = await navigator.storage.estimate();
                  const estimateB = await navigator.storage.estimate();
                  if (estimateA.quota === 1 || JSON.stringify(estimateA) !== JSON.stringify(estimateB)) {
                    throw new Error('storage mismatch');
                  }
                })().catch(error => { console.error(error); process.exitCode = 1; });
                """;

        assertNodeRuns(prelude + SeleniumProxyWorkerSurfaceScripts.build("""
                {"browserVersion":"147.0.7727.137","platform":"Win32","uaPlatform":"Windows",
                "platformVersion":"15.0.0","architecture":"x86","bitness":"64","mobile":false,
                "model":"","wow64":false,"formFactors":["Desktop"],
                "locale":"pt-BR","languages":["pt-BR","pt"],
                "timezoneId":"America/Sao_Paulo","noiseSeed":987654,
                "storage":{"mode":"spoof"},
                "connection":{"mode":"spoof","effectiveType":"4g","downlink":5,"rtt":75,
                "saveData":false,"type":"wifi"}}
                """) + assertions);
    }

    @Test
    void blockedPortStateDoesNotCreateAnXhrOwnProperty() throws Exception {
        String prelude = """
                'use strict';
                (() => {
                const profile = { mediaDevices: { mode: 'real' }, ports: { mode: 'protect', protectedPorts: [3001] }, fonts: {} };
                const screenProfile = { mode: 'real' };
                const intOr = (v, f) => Number.isFinite(Number(v)) ? Math.trunc(Number(v)) : f;
                const navigator = {};
                const location = { origin: 'https://site.example', href: 'https://site.example/' };
                const window = { devicePixelRatio: 1, fetch: () => Promise.resolve() };
                class XMLHttpRequest {
                  open() {}
                  send() { this.nativeSendCalled = true; }
                  dispatchEvent(event) { this.lastEvent = event.type; }
                }
                globalThis.XMLHttpRequest = XMLHttpRequest;
                """;
        String assertions = """
                const xhr = new XMLHttpRequest();
                xhr.open('GET', 'http://127.0.0.1:3001/probe');
                if (Object.getOwnPropertyNames(xhr).some(name => /blocked/i.test(name))) {
                  throw new Error('XHR leaked blocking marker');
                }
                xhr.send();
                if (xhr.nativeSendCalled) throw new Error('blocked request reached native send');
                """;

        assertNodeRuns(mediaRuntime(prelude, assertions));
    }

    @Test
    void mediaDeviceIdsAreOriginScopedWithoutChangingNativeShape() throws Exception {
        String sameOriginA = mediaDeviceProbe("https://one.example");
        String sameOriginB = mediaDeviceProbe("https://one.example");
        String otherOrigin = mediaDeviceProbe("https://two.example");

        assertEquals(sameOriginA, sameOriginB, "device IDs must be stable in the same origin");
        assertNotEquals(sameOriginA, otherOrigin, "device IDs must not link different origins");
    }

    @Test
    void navigatorDoesNotInventApisAndKeepsNotificationPermissionCoherent() throws Exception {
        String prelude = """
                'use strict';
                const profile = { permissions: { notifications: 'default', geolocation: 'prompt' } };
                const permissionProfile = profile.permissions;
                const languages = ['pt-BR', 'pt'];
                const firstLanguage = languages[0];
                const doNotTrack = '1';
                const speechProfile = { mode: 'real' };
                const connectionProfile = { mode: 'real' };
                const webglProfile = { mode: 'real' };
                const intOr = (v, f) => Number.isFinite(Number(v)) ? Math.trunc(Number(v)) : f;
                const numberOr = (v, f) => Number.isFinite(Number(v)) ? Number(v) : f;
                const define = (obj, prop, getter) => Object.defineProperty(obj, prop, {
                  get: getter, configurable: true, enumerable: true
                });
                class Navigator {}
                class Permissions {
                  async query() { return { state: 'prompt' }; }
                }
                const navigator = new Navigator();
                Object.assign(navigator, {
                  vendor: 'Google Inc.', hardwareConcurrency: 8, deviceMemory: 8,
                  maxTouchPoints: 0, webdriver: false,
                  permissions: new Permissions()
                });
                class NativeNotification {
                  static get permission() { return 'default'; }
                  static requestPermission(callback) {
                    const result = Promise.resolve('default');
                    if (callback) result.then(callback);
                    return result;
                  }
                }
                const window = { Notification: NativeNotification, speechSynthesis: null };
                globalThis.Notification = NativeNotification;
                """;
        String assertions = """
                (async () => {
                  if ('share' in navigator || 'canShare' in navigator) throw new Error('invented Web Share API');
                  if ('ContentIndex' in window || 'ContactsManager' in window) throw new Error('invented window API');
                  if (window.Notification !== NativeNotification) throw new Error('replaced native constructor');
                  if (Notification.permission !== 'default') throw new Error('notification property mismatch');
                  if (await Notification.requestPermission() !== 'default') throw new Error('request mismatch');
                  if ((await navigator.permissions.query({ name: 'notifications' })).state !== 'prompt') {
                    throw new Error('permissions.query notification mismatch');
                  }
                  if ((await navigator.permissions.query({ name: 'geolocation' })).state !== 'prompt') {
                    throw new Error('permissions.query geolocation mismatch');
                  }
                })().catch(error => { console.error(error); process.exitCode = 1; });
                """;

        assertNodeRuns(prelude + SeleniumProxyNavigatorSurfaceScripts.SCRIPT + assertions);

        String grantedPrelude = prelude
                .replace("notifications: 'default'", "notifications: 'denied'")
                .replace("async query() { return { state: 'prompt' }; }",
                        "async query() { return { state: 'granted' }; }")
                .replace("static get permission() { return 'default'; }",
                        "static get permission() { return 'granted'; }")
                .replace("Promise.resolve('default')", "Promise.resolve('granted')");
        String grantedAssertions = """
                (async () => {
                  if (window.Notification !== NativeNotification) throw new Error('replaced native constructor');
                  if (Notification.permission !== 'granted') throw new Error('falsified granted permission');
                  if (await Notification.requestPermission() !== 'granted') throw new Error('request mismatch');
                  if ((await navigator.permissions.query({ name: 'notifications' })).state !== 'granted') {
                    throw new Error('permissions.query was falsified');
                  }
                })().catch(error => { console.error(error); process.exitCode = 1; });
                """;
        assertNodeRuns(grantedPrelude + SeleniumProxyNavigatorSurfaceScripts.SCRIPT + grantedAssertions);
    }

    @Test
    void canvasNoiseKeepsNativeReadbackAndStableDrawOutput() throws Exception {
        String prelude = """
                'use strict';
                const profile = { noiseSeed: 918273 };
                const webglProfile = { mode: 'real' };
                const canvasProfile = { mode: 'noise', noise: 0.01 };
                const audioProfile = { mode: 'real', noise: 0 };
                const clientRectsProfile = { mode: 'real', noise: 0 };
                const numberOr = (v, f) => Number.isFinite(Number(v)) ? Number(v) : f;
                const window = { getComputedStyle() { return {}; } };
                class CanvasRenderingContext2D {
                  constructor(canvas) {
                    this.canvas = canvas;
                    this.pixels = new Uint8ClampedArray([
                      100,101,102,255, 110,111,112,255,
                      120,121,122,255, 130,131,132,255
                    ]);
                  }
                  getImageData() { return { data: new Uint8ClampedArray(this.pixels) }; }
                  putImageData(image) { this.pixels = new Uint8ClampedArray(image.data); }
                  fillRect(x, y) {
                    this.pixels[0] = Math.round((x + 16) * 64) & 255;
                    this.pixels[1] = Math.round((y + 16) * 64) & 255;
                  }
                  strokeRect(x, y) {
                    this.pixels[2] = Math.round((x + 16) * 64) & 255;
                    this.pixels[4] = Math.round((y + 16) * 64) & 255;
                  }
                  fillText(text, x, y) {
                    this.pixels[5] = Math.round((x + 16) * 64) & 255;
                    this.pixels[6] = Math.round((y + 16) * 64) & 255;
                  }
                  strokeText(text, x, y) {
                    this.pixels[8] = Math.round((x + 16) * 64) & 255;
                    this.pixels[9] = Math.round((y + 16) * 64) & 255;
                  }
                }
                class HTMLCanvasElement {
                  constructor() {
                    this.width = 2;
                    this.height = 2;
                    this.isConnected = false;
                    this.context = new CanvasRenderingContext2D(this);
                  }
                  getContext(kind) { return kind === '2d' ? this.context : null; }
                  toDataURL() { return 'data:' + Array.from(this.context.pixels).join(','); }
                  toBlob(callback) { callback({ bytes: Array.from(this.context.pixels) }); }
                }
                const document = {
                  readyState: 'complete',
                  createElement(kind) { return kind === 'canvas' ? new HTMLCanvasElement() : {}; }
                };
                globalThis.CanvasRenderingContext2D = CanvasRenderingContext2D;
                globalThis.HTMLCanvasElement = HTMLCanvasElement;
                const draw = canvas => {
                  const context = canvas.getContext('2d');
                  context.fillRect(1, 2, 3, 4);
                  context.strokeRect(2, 3, 4, 5);
                  context.fillText('fingerprint', 3, 4);
                  context.strokeText('fingerprint', 4, 5);
                };
                const cleanCanvas = new HTMLCanvasElement();
                draw(cleanCanvas);
                const clean = cleanCanvas.toDataURL();
                const nativeGetImageData = CanvasRenderingContext2D.prototype.getImageData;
                const nativeToDataURL = HTMLCanvasElement.prototype.toDataURL;
                const nativeToBlob = HTMLCanvasElement.prototype.toBlob;
                """;
        String assertions = """
                const firstCanvas = new HTMLCanvasElement();
                const secondCanvas = new HTMLCanvasElement();
                const connectedCanvas = new HTMLCanvasElement();
                connectedCanvas.isConnected = true;
                draw(firstCanvas);
                draw(secondCanvas);
                draw(connectedCanvas);
                const first = firstCanvas.toDataURL();
                const second = secondCanvas.toDataURL();
                const connected = connectedCanvas.toDataURL();
                if (first === clean) throw new Error('canvas noise was not applied');
                if (first !== second) throw new Error('canvas serialization noise drifted');
                if (first !== connected) throw new Error('all-scope connected canvas diverged');
                if (HTMLCanvasElement.prototype.toDataURL !== nativeToDataURL) {
                  throw new Error('toDataURL was wrapped');
                }
                if (CanvasRenderingContext2D.prototype.getImageData !== nativeGetImageData) {
                  throw new Error('getImageData was wrapped');
                }
                if (HTMLCanvasElement.prototype.toBlob !== nativeToBlob) {
                  throw new Error('toBlob was wrapped');
                }
                """;

        assertNodeRuns(prelude + SeleniumProxyRenderingSurfaceScripts.SCRIPT + assertions);
    }

    @Test
    void canvasNoiseSeparatesPreviouslyCollidingSeedsAcrossPageAndWorker() throws Exception {
        String pageSeed7 = pageCanvasDrawSignature(7, true);
        String workerSeed7 = workerCanvasDrawSignature(7);
        String pageSeed222 = pageCanvasDrawSignature(222, true);
        String workerSeed222 = workerCanvasDrawSignature(222);

        assertEquals(pageSeed7, workerSeed7, "page and worker must agree for seed 7");
        assertEquals(pageSeed222, workerSeed222, "page and worker must agree for seed 222");
        assertNotEquals(pageSeed7, pageSeed222,
                "the previously colliding profile seeds must not share draw coordinates");
    }

    @Test
    void canvasGeometryDrawImageAndPath2dAreIsolatedAndRealmCoherent() throws Exception {
        String pageSeed7 = pageCanvasGeometrySignature(7);
        String workerSeed7 = workerCanvasGeometrySignature(7);
        String pageSeed222 = pageCanvasGeometrySignature(222);
        String workerSeed222 = workerCanvasGeometrySignature(222);

        assertEquals(pageSeed7, workerSeed7,
                "page and worker must shift every geometric route identically for seed 7");
        assertEquals(pageSeed222, workerSeed222,
                "page and worker must shift every geometric route identically for seed 222");
        assertNotEquals(pageSeed7, pageSeed222,
                "geometric-only canvas fingerprints must not collide across profile seeds");
    }

    @Test
    void canvasIntegerRectsRespectOnlyTheSafeFastPathAndPath2dClipIsRealmCoherent() throws Exception {
        var pageSeed7 = canvasHardeningSignature(false, 7);
        var workerSeed7 = canvasHardeningSignature(true, 7);
        var pageSeed222 = canvasHardeningSignature(false, 222);
        var workerSeed222 = canvasHardeningSignature(true, 222);

        assertEquals(pageSeed7, workerSeed7, "page and worker must agree for seed 7");
        assertEquals(pageSeed222, workerSeed222, "page and worker must agree for seed 222");
        assertEquals(pageSeed7.get("safeFill"), pageSeed222.get("safeFill"),
                "a plain pixel-aligned solid integer fill must remain native");
        assertEquals(java.util.List.of(java.util.List.of("fillRect", 1, 2, 3, 4)),
                pageSeed7.get("safeFill"));
        assertNotEquals(pageSeed7.get("transformedFill"), pageSeed222.get("transformedFill"),
                "a fractional CTM must make an integer fill seed-sensitive");
        assertNotEquals(pageSeed7.get("styledFill"), pageSeed222.get("styledFill"),
                "a non-solid fillStyle must make an integer fill seed-sensitive");
        assertNotEquals(pageSeed7.get("integerStroke"), pageSeed222.get("integerStroke"),
                "integer strokeRect must always be seed-sensitive");
        assertNotEquals(pageSeed7.get("clippedFill"), pageSeed222.get("clippedFill"),
                "a known clip must disable the native integer-fill fast path");
        assertNotEquals(pageSeed7.get("pathClip"), pageSeed222.get("pathClip"),
                "Path2D clip geometry must be seed-sensitive");

        @SuppressWarnings("unchecked")
        var clipCalls = (java.util.List<java.util.List<Object>>) pageSeed7.get("pathClip");
        assertEquals(java.util.List.of("translate", "clip", "translate"),
                clipCalls.stream().map(call -> String.valueOf(call.get(0))).toList(),
                "Path2D clip must use a temporary translation and inverse without save/restore");
        assertEquals(-((Number) clipCalls.get(0).get(1)).doubleValue(),
                ((Number) clipCalls.get(2).get(1)).doubleValue(), 0.0);
        assertEquals(-((Number) clipCalls.get(0).get(2)).doubleValue(),
                ((Number) clipCalls.get(2).get(2)).doubleValue(), 0.0);
    }

    @Test
    void detachedCanvasScopeLeavesConnectedRenderingNativeByExplicitChoice() throws Exception {
        String allScope = pageCanvasDrawSignature(7, true, "all");
        String detachedScope = pageCanvasDrawSignature(7, true, "detached");
        String nativeSignature = "[[0.25,0.5,16,16],[2.25,3.5,11,9]]";

        assertNotEquals(nativeSignature, allScope);
        assertEquals(nativeSignature, detachedScope);
    }

    @Test
    void workerWebglPixelsAreStablePerProfileAndDifferentAcrossProfiles() throws Exception {
        String profileA = """
                {"noiseSeed":111,"webgl":{"mode":"spoof_noise","vendor":"Vendor","renderer":"Renderer"}}
                """;
        String profileB = """
                {"noiseSeed":222,"webgl":{"mode":"spoof_noise","vendor":"Vendor","renderer":"Renderer"}}
                """;

        String firstA = workerWebglPixels(profileA);
        String secondA = workerWebglPixels(profileA);
        String firstB = workerWebglPixels(profileB);
        String pageA = pageWebglPixels(profileA);

        assertEquals(firstA, secondA, "same profile must keep a stable WebGL hash input");
        assertEquals(pageA, firstA, "page and worker must derive the same WebGL hash input");
        assertNotEquals(firstA, firstB, "different profiles must not share the same WebGL hash input");
    }

    @Test
    void workerAudioNoiseIsStableAcrossRepeatedReads() throws Exception {
        String prelude = """
                'use strict';
                globalThis.self = globalThis;
                class AudioBuffer {
                  constructor() { this.data = new Float32Array([0.1, -0.2, 0.3, -0.4]); }
                  getChannelData() { return this.data; }
                  copyFromChannel(destination) { destination.set(this.data.subarray(0, destination.length)); }
                }
                globalThis.AudioBuffer = AudioBuffer;
                """;
        String assertions = """
                const buffer = new AudioBuffer();
                const first = Array.from(buffer.getChannelData(0));
                const second = Array.from(buffer.getChannelData(0));
                if (JSON.stringify(first) !== JSON.stringify(second)) throw new Error('live buffer noise drifted');
                if (first[0] === new Float32Array([0.1])[0]) throw new Error('noise was not applied');
                const copyA = new Float32Array(4);
                const copyB = new Float32Array(4);
                buffer.copyFromChannel(copyA, 0, 0);
                buffer.copyFromChannel(copyB, 0, 0);
                if (JSON.stringify(Array.from(copyA)) !== JSON.stringify(Array.from(copyB))) {
                  throw new Error('copyFromChannel noise drifted');
                }
                """;

        assertNodeRuns(prelude + SeleniumProxyWorkerSurfaceScripts.build("""
                {"noiseSeed":918273,"audio":{"mode":"noise","noise":0.002}}
                """) + assertions);
    }

    @Test
    void workerWebRtcForcesRelayWhenUdpIsDisabled() throws Exception {
        String prelude = """
                'use strict';
                globalThis.self = globalThis;
                class NativeRTCPeerConnection {
                  constructor(config) { this.config = config || {}; }
                }
                globalThis.RTCPeerConnection = NativeRTCPeerConnection;
                """;
        String assertions = """
                const originalConfig = { iceServers: [] };
                const connection = new RTCPeerConnection(originalConfig);
                if (connection.config.iceTransportPolicy !== 'relay') throw new Error('worker did not force relay');
                if ('iceTransportPolicy' in originalConfig) throw new Error('caller config was mutated');
                """;

        assertNodeRuns(prelude + SeleniumProxyWorkerSurfaceScripts.build("""
                {"webRtc":{"mode":"altered","disableUdp":true}}
                """) + assertions);
    }

    @Test
    void clientRectsPreserveNativeListContractAndStableNoisyValues() throws Exception {
        String prelude = """
                'use strict';
                const profile = { noiseSeed: 918273 };
                const webglProfile = { mode: 'real' };
                const canvasProfile = { mode: 'real', noise: 0 };
                const audioProfile = { mode: 'real', noise: 0 };
                const clientRectsProfile = { mode: 'noise', noise: 0.05 };
                const numberOr = (v, f) => Number.isFinite(Number(v)) ? Number(v) : f;
                const document = { readyState: 'complete' };
                const window = { getComputedStyle() { return {}; } };
                class DOMRect {
                  constructor(x, y, width, height) {
                    this.x=x; this.y=y; this.width=width; this.height=height;
                    this.left=x; this.top=y; this.right=x+width; this.bottom=y+height;
                  }
                }
                class DOMRectList {
                  constructor(rects) {
                    this._rects = rects;
                    rects.forEach((rect, index) => Object.defineProperty(this, String(index), {
                      value: rect, enumerable: true, configurable: true
                    }));
                  }
                  get length() { return this._rects.length; }
                  item(index) { return this._rects[index] || null; }
                  [Symbol.iterator]() { return this._rects[Symbol.iterator](); }
                  get [Symbol.toStringTag]() { return 'DOMRectList'; }
                }
                class Element {
                  getClientRects() {
                    return new DOMRectList([new DOMRect(10, 20, 30, 40), new DOMRect(1, 2, 3, 4)]);
                  }
                  getBoundingClientRect() { return new DOMRect(10, 20, 30, 40); }
                }
                """;
        String assertions = """
                const element = new Element();
                const first = element.getClientRects();
                const second = element.getClientRects();
                if (!(first instanceof DOMRectList)) throw new Error('not DOMRectList');
                if (Object.getPrototypeOf(first) !== DOMRectList.prototype) throw new Error('wrong prototype');
                if (Object.prototype.toString.call(first) !== '[object DOMRectList]') throw new Error('wrong tag');
                if (first.item(0) !== first[0] || first.item(999) !== null) throw new Error('item contract');
                if (Object.getOwnPropertyDescriptor(first, '0').value !== first[0]) throw new Error('descriptor mismatch');
                if ([...first].length !== first.length) throw new Error('iterator contract');
                if (first[0].x === 10) throw new Error('noise was not applied');
                if (JSON.stringify([...first]) !== JSON.stringify([...second])) throw new Error('noise drifted');
                """;

        assertNodeRuns(prelude + SeleniumProxyRenderingSurfaceScripts.SCRIPT + assertions);
    }

}
