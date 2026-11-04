package io.github.selenium.driverless;

import static io.github.selenium.driverless.MultiProfileStorageIsolationScripts.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import io.github.selenium.javaDriverless.JavaDriverless;
import io.github.selenium.javaDriverless.types.BrowserFingerprintProfile;
import io.github.selenium.javaDriverless.types.ChromeOptions;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Prova live opt-in de isolamento de storage e fingerprint entre dois processos Chrome reais.
 *
 * <p>A fixture fica restrita a uma origem HTTP de loopback. A campanha só roda quando
 * {@code JAVA_DRIVERLESS_LIVE_ISOLATION_REQUIRED=1}; o valor padrão da origem é
 * uma porta dinâmica em {@code 127.0.0.1} e pode ser alterado por
 * {@code JAVA_DRIVERLESS_LIVE_ORIGIN}.</p>
 */
@Tag("external")
final class MultiProfileStorageIsolationScripts {
    static final String REQUIRED_ENV = "JAVA_DRIVERLESS_LIVE_ISOLATION_REQUIRED";
    static final String ORIGIN_ENV = "JAVA_DRIVERLESS_LIVE_ORIGIN";
    static final String DEFAULT_ORIGIN = "http://127.0.0.1:0";
    static final String SERVICE_WORKER_PATH = "/jdl-live-isolation-sw.js";
    static final ObjectMapper MAPPER = new ObjectMapper();

    static final String FINGERPRINT_SNAPSHOT_FUNCTION = """
            const jdlFingerprintSnapshot = async () => {
              const canvas = typeof document !== 'undefined'
                ? document.createElement('canvas')
                : new OffscreenCanvas(16, 16);
              canvas.width = 16;
              canvas.height = 16;
              if (typeof document !== 'undefined') document.body.appendChild(canvas);
              const context = canvas.getContext('2d');
              context.fillStyle = '#13579b';
              context.fillRect(0, 0, 16, 16);
              context.fillStyle = '#eca864';
              context.fillRect(2, 3, 11, 9);
              const pixels = context.getImageData(0, 0, 16, 16).data;
              let canvasHash = 0x811c9dc5;
              for (const value of pixels) {
                canvasHash ^= value;
                canvasHash = Math.imul(canvasHash, 0x01000193);
              }
              if (typeof document !== 'undefined') canvas.remove();
              const hash2d = target => {
                const data = target.getContext('2d')
                  .getImageData(0, 0, target.width, target.height).data;
                let hash = 0x811c9dc5;
                for (const value of data) {
                  hash ^= value;
                  hash = Math.imul(hash, 0x01000193);
                }
                return (hash >>> 0).toString(16).padStart(8, '0');
              };
              const geometryCanvas = typeof document !== 'undefined'
                ? document.createElement('canvas') : new OffscreenCanvas(48, 48);
              geometryCanvas.width = 48;
              geometryCanvas.height = 48;
              if (typeof document !== 'undefined') document.body.appendChild(geometryCanvas);
              const geometry = geometryCanvas.getContext('2d');
              const gradient = geometry.createLinearGradient(2, 3, 43, 41);
              gradient.addColorStop(0, '#13579b');
              gradient.addColorStop(1, '#eca864');
              geometry.fillStyle = gradient;
              geometry.strokeStyle = '#2468ac';
              geometry.lineWidth = 1.25;
              geometry.beginPath();
              geometry.moveTo(3, 5);
              geometry.bezierCurveTo(7, 1, 29, 45, 44, 9);
              geometry.quadraticCurveTo(31, 37, 9, 42);
              geometry.lineTo(4, 18);
              geometry.arc(18, 19, 7, 0.15, 2.75);
              geometry.ellipse(31, 27, 9, 5, 0.35, 0, Math.PI * 1.8);
              geometry.closePath();
              geometry.fill();
              geometry.stroke();
              const geometryCanvasHash = hash2d(geometryCanvas);
              if (typeof document !== 'undefined') geometryCanvas.remove();

              const rectCanvas = typeof document !== 'undefined'
                ? document.createElement('canvas') : new OffscreenCanvas(32, 32);
              rectCanvas.width = 32;
              rectCanvas.height = 32;
              if (typeof document !== 'undefined') document.body.appendChild(rectCanvas);
              const rectContext = rectCanvas.getContext('2d');
              rectContext.fillStyle = '#13579b';
              rectContext.translate(0.5, 0.25);
              rectContext.fillRect(2, 3, 21, 19);
              rectContext.setTransform(1, 0, 0, 1, 0, 0);
              rectContext.strokeStyle = '#eca864';
              rectContext.lineWidth = 1;
              rectContext.strokeRect(1, 2, 28, 27);
              const rectCanvasHash = hash2d(rectCanvas);
              if (typeof document !== 'undefined') rectCanvas.remove();

              const sourceCanvas = typeof document !== 'undefined'
                ? document.createElement('canvas') : new OffscreenCanvas(8, 8);
              sourceCanvas.width = 8;
              sourceCanvas.height = 8;
              const sourceContext = sourceCanvas.getContext('2d');
              const sourceImage = sourceContext.createImageData(8, 8);
              for (let i = 0; i < sourceImage.data.length; i += 4) {
                sourceImage.data[i] = (i * 13) & 255;
                sourceImage.data[i + 1] = (i * 7 + 31) & 255;
                sourceImage.data[i + 2] = (i * 3 + 97) & 255;
                sourceImage.data[i + 3] = 255;
              }
              sourceContext.putImageData(sourceImage, 0, 0);
              const imageCanvas = typeof document !== 'undefined'
                ? document.createElement('canvas') : new OffscreenCanvas(32, 32);
              imageCanvas.width = 32;
              imageCanvas.height = 32;
              if (typeof document !== 'undefined') document.body.appendChild(imageCanvas);
              imageCanvas.getContext('2d').drawImage(sourceCanvas, 5, 6, 21, 19);
              const drawImageCanvasHash = hash2d(imageCanvas);
              if (typeof document !== 'undefined') imageCanvas.remove();
              const uaData = navigator.userAgentData ? {
                brands: Array.from(navigator.userAgentData.brands || []),
                mobile: navigator.userAgentData.mobile,
                platform: navigator.userAgentData.platform,
                highEntropy: typeof navigator.userAgentData.getHighEntropyValues === 'function'
                  ? await navigator.userAgentData.getHighEntropyValues([
                      'architecture', 'bitness', 'formFactors', 'fullVersionList', 'model',
                      'platformVersion', 'uaFullVersion', 'wow64'
                    ])
                  : null
              } : null;
              const connection = navigator.connection ? {
                effectiveType: navigator.connection.effectiveType,
                downlink: navigator.connection.downlink,
                rtt: navigator.connection.rtt,
                saveData: navigator.connection.saveData,
                type: 'type' in navigator.connection ? navigator.connection.type : null
              } : null;
              const estimate = navigator.storage && typeof navigator.storage.estimate === 'function'
                ? await navigator.storage.estimate()
                : null;
              const glCanvas = typeof document !== 'undefined'
                ? document.createElement('canvas')
                : new OffscreenCanvas(24, 24);
              glCanvas.width = 24;
              glCanvas.height = 24;
              const gl = glCanvas.getContext('webgl');
              let webgl = null;
              if (gl) {
                gl.clearColor(0.13, 0.37, 0.61, 1);
                gl.clear(gl.COLOR_BUFFER_BIT);
                const glPixels = new Uint8Array(24 * 24 * 4);
                gl.readPixels(0, 0, 24, 24, gl.RGBA, gl.UNSIGNED_BYTE, glPixels);
                let glHash = 0x811c9dc5;
                for (const value of glPixels) {
                  glHash ^= value;
                  glHash = Math.imul(glHash, 0x01000193);
                }
                const ext = gl.getExtension('WEBGL_debug_renderer_info');
                webgl = {
                  vendor: ext ? gl.getParameter(ext.UNMASKED_VENDOR_WEBGL) : null,
                  renderer: ext ? gl.getParameter(ext.UNMASKED_RENDERER_WEBGL) : null,
                  hash: (glHash >>> 0).toString(16).padStart(8, '0')
                };
              }
              let audioHash = null;
              if (typeof OfflineAudioContext === 'function') {
                const audioContext = new OfflineAudioContext(1, 2048, 44100);
                const oscillator = audioContext.createOscillator();
                oscillator.type = 'triangle';
                oscillator.frequency.value = 1000;
                oscillator.connect(audioContext.destination);
                oscillator.start(0);
                const rendered = await audioContext.startRendering();
                const samples = rendered.getChannelData(0);
                let hash = 0x811c9dc5;
                for (let i = 0; i < samples.length; i += 8) {
                  const quantized = Math.round(samples[i] * 1000000);
                  hash ^= quantized;
                  hash = Math.imul(hash, 0x01000193);
                }
                audioHash = (hash >>> 0).toString(16).padStart(8, '0');
              }
              let clientRects = null;
              let screenSurface = null;
              let notification = null;
              let runtimeShape = null;
              if (typeof document !== 'undefined') {
                const probe = document.createElement('div');
                probe.textContent = 'rect-probe';
                document.body.appendChild(probe);
                const rects = probe.getClientRects();
                clientRects = {
                  tag: Object.prototype.toString.call(rects),
                  isArray: Array.isArray(rects),
                  hasItem: typeof rects.item === 'function',
                  prototype: Object.getPrototypeOf(rects)?.constructor?.name || '',
                  stable: JSON.stringify(Array.from(rects)) === JSON.stringify(Array.from(probe.getClientRects()))
                };
                probe.remove();
                screenSurface = {
                  width: screen.width,
                  height: screen.height,
                  availWidth: screen.availWidth,
                  availHeight: screen.availHeight,
                  colorDepth: screen.colorDepth,
                  pixelDepth: screen.pixelDepth,
                  devicePixelRatio: window.devicePixelRatio
                };
                if ('Notification' in window) {
                  const permissionState = navigator.permissions && navigator.permissions.query
                    ? await navigator.permissions.query({name: 'notifications'}).then(value => value.state).catch(() => null)
                    : null;
                  notification = {permission: Notification.permission, permissionState};
                }
                const xhr = new XMLHttpRequest();
                xhr.open('GET', 'http://127.0.0.1:3001/jdl-port-probe');
                runtimeShape = {
                  share: 'share' in navigator,
                  canShare: 'canShare' in navigator,
                  contentIndex: 'ContentIndex' in window,
                  contactsManager: 'ContactsManager' in window,
                  downlinkMax: !!(window.NetworkInformation
                    && 'downlinkMax' in window.NetworkInformation.prototype),
                  xhrBlockedMarker: Object.prototype.hasOwnProperty.call(xhr, '_blocked'),
                  createElementNative: /\\[native code\\]/.test(Function.prototype.toString.call(document.createElement)),
                  setTimeoutNative: /\\[native code\\]/.test(Function.prototype.toString.call(window.setTimeout)),
                  workerNative: typeof Worker !== 'function'
                    || /\\[native code\\]/.test(Function.prototype.toString.call(Worker))
                };
              }
              return {
                userAgent: navigator.userAgent,
                webdriver: typeof document !== 'undefined' ? navigator.webdriver : null,
                userAgentData: uaData,
                hardwareConcurrency: navigator.hardwareConcurrency,
                deviceMemory: navigator.deviceMemory,
                platform: navigator.platform,
                language: navigator.language,
                languages: Array.from(navigator.languages || []),
                intlLocale: Intl.DateTimeFormat().resolvedOptions().locale,
                timezone: Intl.DateTimeFormat().resolvedOptions().timeZone,
                timezoneOffset: new Date('2026-01-15T12:00:00Z').getTimezoneOffset(),
                canvasHash: (canvasHash >>> 0).toString(16).padStart(8, '0'),
                geometryCanvasHash,
                rectCanvasHash,
                drawImageCanvasHash,
                audioHash,
                webgl,
                connection,
                storageEstimate: estimate ? {quota: estimate.quota, usage: estimate.usage} : null,
                clientRects,
                screen: screenSurface,
                notification,
                runtimeShape
              };
            };
            """;

    static final String PAGE_FINGERPRINT_SCRIPT = """
            (() => {
            """ + FINGERPRINT_SNAPSHOT_FUNCTION + """
              return jdlFingerprintSnapshot();
            })()
            """;

    static final String STORAGE_SNAPSHOT_SCRIPT = """
            (async () => {
              const cookieKey = 'jdl_live_isolation';
              const cookiePair = document.cookie.split('; ').find(row => row.startsWith(cookieKey + '='));
              const cookieValue = cookiePair ? decodeURIComponent(cookiePair.slice(cookieKey.length + 1)) : null;
              const sessionCookieKey = 'jdl_live_isolation_session';
              const sessionCookiePair = document.cookie.split('; ')
                .find(row => row.startsWith(sessionCookieKey + '='));
              const sessionCookieValue = sessionCookiePair
                ? decodeURIComponent(sessionCookiePair.slice(sessionCookieKey.length + 1)) : null;

              const indexedDbValue = await new Promise((resolve, reject) => {
                const open = indexedDB.open('jdl-live-isolation', 1);
                open.onupgradeneeded = () => {
                  const db = open.result;
                  if (!db.objectStoreNames.contains('markers')) db.createObjectStore('markers');
                };
                open.onerror = () => reject(open.error || new Error('indexedDB.open failed'));
                open.onsuccess = () => {
                  const db = open.result;
                  const request = db.transaction('markers', 'readonly').objectStore('markers').get('profile-token');
                  request.onerror = () => { db.close(); reject(request.error || new Error('indexedDB.get failed')); };
                  request.onsuccess = () => { const value = request.result ?? null; db.close(); resolve(value); };
                };
              });

              const cache = await caches.open('jdl-live-isolation');
              const cachedResponse = await cache.match('/jdl-live-isolation-cache-entry');
              const cacheValue = cachedResponse ? await cachedResponse.text() : null;

              const registration = await navigator.serviceWorker.getRegistration('/');
              let serviceWorkerValue = null;
              if (registration) {
                const worker = registration.active || registration.waiting || registration.installing;
                if (worker) {
                  serviceWorkerValue = await new Promise((resolve, reject) => {
                    const channel = new MessageChannel();
                    const timer = setTimeout(() => reject(new Error('service worker probe timeout')), 10000);
                    channel.port1.onmessage = event => { clearTimeout(timer); resolve(event.data ?? null); };
                    worker.postMessage({type: 'read-isolation-token'}, [channel.port2]);
                  });
                }
              }

              return {
                cookie: cookieValue,
                sessionCookie: sessionCookieValue,
                localStorage: localStorage.getItem('jdl.live.isolation'),
                sessionStorage: sessionStorage.getItem('jdl.live.isolation'),
                indexedDb: indexedDbValue,
                cacheStorage: cacheValue,
                serviceWorkerRegistered: registration !== undefined,
                serviceWorker: serviceWorkerValue
              };
            })()
            """;

    static final String WRITE_STORAGE_SCRIPT = """
            (async () => {
              const token = __JDL_TOKEN_JSON__;
              document.cookie = 'jdl_live_isolation=' + encodeURIComponent(token)
                + '; Path=/; SameSite=Lax; Max-Age=86400';
              document.cookie = 'jdl_live_isolation_session=' + encodeURIComponent(token)
                + '; Path=/; SameSite=Lax';
              localStorage.setItem('jdl.live.isolation', token);
              sessionStorage.setItem('jdl.live.isolation', token);

              await new Promise((resolve, reject) => {
                const open = indexedDB.open('jdl-live-isolation', 1);
                open.onupgradeneeded = () => {
                  const db = open.result;
                  if (!db.objectStoreNames.contains('markers')) db.createObjectStore('markers');
                };
                open.onerror = () => reject(open.error || new Error('indexedDB.open failed'));
                open.onsuccess = () => {
                  const db = open.result;
                  const tx = db.transaction('markers', 'readwrite');
                  tx.objectStore('markers').put(token, 'profile-token');
                  tx.oncomplete = () => { db.close(); resolve(); };
                  tx.onerror = () => { db.close(); reject(tx.error || new Error('indexedDB.put failed')); };
                  tx.onabort = () => { db.close(); reject(tx.error || new Error('indexedDB transaction aborted')); };
                };
              });

              const cache = await caches.open('jdl-live-isolation');
              await cache.put('/jdl-live-isolation-cache-entry', new Response(token, {
                headers: {'Content-Type': 'text/plain; charset=utf-8'}
              }));

              if (!('serviceWorker' in navigator)) throw new Error('Service Worker API unavailable');
              const registration = await navigator.serviceWorker.register('/jdl-live-isolation-sw.js', {scope: '/'});
              let worker = registration.active || registration.waiting || registration.installing;
              if (!worker) throw new Error('Service Worker registration has no worker');
              if (worker.state !== 'activated') {
                await new Promise((resolve, reject) => {
                  const timer = setTimeout(() => reject(new Error('service worker activation timeout')), 15000);
                  const stateChanged = () => {
                    if (worker.state === 'activated') {
                      clearTimeout(timer);
                      worker.removeEventListener('statechange', stateChanged);
                      resolve();
                    }
                  };
                  worker.addEventListener('statechange', stateChanged);
                  stateChanged();
                });
              }
              await Promise.race([
                navigator.serviceWorker.ready,
                new Promise((_, reject) => setTimeout(() => reject(new Error('service worker ready timeout')), 15000))
              ]);
              return true;
            })()
            """;

    static final String SERVICE_WORKER_SCRIPT = FINGERPRINT_SNAPSHOT_FUNCTION + """
            const jdlFirstStatementFingerprint = jdlFingerprintSnapshot();
            self.addEventListener('install', event => event.waitUntil(self.skipWaiting()));
            self.addEventListener('activate', event => event.waitUntil(self.clients.claim()));
            self.addEventListener('message', event => {
              if (!event.data || !event.ports[0]) return;
              if (event.data.type === 'read-fingerprint') {
                event.waitUntil(jdlFingerprintSnapshot().then(snapshot => event.ports[0].postMessage(snapshot)));
                return;
              }
              if (event.data.type === 'read-first-statement-fingerprint') {
                event.waitUntil(jdlFirstStatementFingerprint.then(snapshot => event.ports[0].postMessage(snapshot)));
                return;
              }
              if (event.data.type !== 'read-isolation-token') return;
              event.waitUntil((async () => {
                const cache = await caches.open('jdl-live-isolation');
                const response = await cache.match('/jdl-live-isolation-cache-entry');
                event.ports[0].postMessage(response ? await response.text() : null);
              })());
            });
            """;


    private MultiProfileStorageIsolationScripts() {
    }
}
