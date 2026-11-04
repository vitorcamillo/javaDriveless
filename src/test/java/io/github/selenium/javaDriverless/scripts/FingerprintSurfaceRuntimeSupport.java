package io.github.selenium.javaDriverless.scripts;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Assumptions;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class FingerprintSurfaceRuntimeSupport {
    private static final ObjectMapper MAPPER = new ObjectMapper();
    static String workerWebglPixels(String profileJson) throws Exception {
        String prelude = """
                'use strict';
                globalThis.self = globalThis;
                class WebGLRenderingContext {
                  getParameter() { return 'native'; }
                  readPixels(x, y, width, height, format, type, pixels) {
                    for (let i = 0; i < pixels.length; i++) pixels[i] = (i * 17 + 31) & 255;
                  }
                }
                globalThis.WebGLRenderingContext = WebGLRenderingContext;
                """;
        String assertions = """
                const pixels = new Uint8Array(16384);
                new WebGLRenderingContext().readPixels(0, 0, 64, 64, 0, 0, pixels);
                process.stdout.write(Buffer.from(pixels).toString('hex'));
                """;
        return nodeOutput(prelude + SeleniumProxyWorkerSurfaceScripts.build(profileJson) + assertions);
    }

    static String pageCanvasDrawSignature(int seed, boolean connected) throws Exception {
        return pageCanvasDrawSignature(seed, connected, "all");
    }

    static String pageCanvasDrawSignature(int seed, boolean connected, String scope) throws Exception {
        String profileJson = "{\"noiseSeed\":" + seed
                + ",\"canvas\":{\"mode\":\"noise\",\"noise\":0.01,\"scope\":\""
                + scope + "\"}}";
        String prelude = """
                'use strict';
                const profile = %s;
                const webglProfile = { mode: 'real' };
                const canvasProfile = profile.canvas;
                const audioProfile = { mode: 'real', noise: 0 };
                const clientRectsProfile = { mode: 'real', noise: 0 };
                const numberOr = (v, f) => Number.isFinite(Number(v)) ? Number(v) : f;
                const document = { readyState: 'complete' };
                const window = { getComputedStyle() { return {}; } };
                class CanvasRenderingContext2D {
                  constructor(canvas) { this.canvas = canvas; this.calls = []; }
                  fillRect(...args) { this.calls.push(args.slice(0, 4)); }
                  strokeRect(...args) { this.calls.push(args.slice(0, 4)); }
                  fillText(...args) { this.calls.push(args.slice(0, 3)); }
                  strokeText(...args) { this.calls.push(args.slice(0, 3)); }
                }
                class HTMLCanvasElement {
                  constructor() { this.isConnected = %s; this.context = new CanvasRenderingContext2D(this); }
                  getContext() { return this.context; }
                }
                globalThis.CanvasRenderingContext2D = CanvasRenderingContext2D;
                globalThis.HTMLCanvasElement = HTMLCanvasElement;
                """.formatted(profileJson, connected);
        String assertions = """
                const canvas = new HTMLCanvasElement();
                const context = canvas.getContext('2d');
                context.fillRect(0.25, 0.5, 16, 16);
                context.fillRect(2.25, 3.5, 11, 9);
                process.stdout.write(JSON.stringify(context.calls));
                """;
        return nodeOutput(prelude + SeleniumProxyRenderingSurfaceScripts.SCRIPT + assertions);
    }

    static String workerCanvasDrawSignature(int seed) throws Exception {
        String profileJson = "{\"noiseSeed\":" + seed
                + ",\"canvas\":{\"mode\":\"noise\",\"noise\":0.01}}";
        String prelude = """
                'use strict';
                globalThis.self = globalThis;
                class OffscreenCanvasRenderingContext2D {
                  constructor() { this.calls = []; }
                  fillRect(...args) { this.calls.push(args.slice(0, 4)); }
                  strokeRect(...args) { this.calls.push(args.slice(0, 4)); }
                  fillText(...args) { this.calls.push(args.slice(0, 3)); }
                  strokeText(...args) { this.calls.push(args.slice(0, 3)); }
                }
                globalThis.OffscreenCanvasRenderingContext2D = OffscreenCanvasRenderingContext2D;
                """;
        String assertions = """
                const context = new OffscreenCanvasRenderingContext2D();
                context.fillRect(0.25, 0.5, 16, 16);
                context.fillRect(2.25, 3.5, 11, 9);
                process.stdout.write(JSON.stringify(context.calls));
                """;
        return nodeOutput(prelude + SeleniumProxyWorkerSurfaceScripts.build(profileJson) + assertions);
    }

    static String pageCanvasGeometrySignature(int seed) throws Exception {
        String profileJson = "{\"noiseSeed\":" + seed
                + ",\"canvas\":{\"mode\":\"noise\",\"noise\":0.01,\"scope\":\"all\"}}";
        String prelude = canvasGeometryPrelude(false, profileJson);
        return nodeOutput(prelude + SeleniumProxyRenderingSurfaceScripts.SCRIPT
                + canvasGeometryAssertions());
    }

    static String workerCanvasGeometrySignature(int seed) throws Exception {
        String profileJson = "{\"noiseSeed\":" + seed
                + ",\"canvas\":{\"mode\":\"noise\",\"noise\":0.01}}";
        String prelude = canvasGeometryPrelude(true, profileJson);
        return nodeOutput(prelude + SeleniumProxyWorkerSurfaceScripts.build(profileJson)
                + canvasGeometryAssertions());
    }

    static java.util.Map<String, Object> canvasHardeningSignature(boolean worker, int seed)
            throws Exception {
        String profileJson = "{\"noiseSeed\":" + seed
                + ",\"canvas\":{\"mode\":\"noise\",\"noise\":0.01,\"scope\":\"all\"}}";
        String script = canvasGeometryPrelude(worker, profileJson)
                + (worker ? SeleniumProxyWorkerSurfaceScripts.build(profileJson)
                : SeleniumProxyRenderingSurfaceScripts.SCRIPT)
                + """
                const Context = globalThis.CanvasRenderingContext2D
                  || globalThis.OffscreenCanvasRenderingContext2D;
                const makeContext = () => new Context({ isConnected: true });
                const callsFor = action => {
                  const context = makeContext();
                  action(context);
                  return context.calls;
                };
                const safeFill = callsFor(context => context.fillRect(1, 2, 3, 4));
                const transformedFill = callsFor(context => {
                  context._matrix.e = 0.5;
                  context._matrix.f = 0.25;
                  context.fillRect(1, 2, 3, 4);
                });
                const styledFill = callsFor(context => {
                  context.fillStyle = { kind: 'gradient' };
                  context.fillRect(1, 2, 3, 4);
                });
                const integerStroke = callsFor(context => context.strokeRect(2, 3, 4, 5));
                const clippedContext = makeContext();
                clippedContext.clip(new Path2D());
                const pathClip = clippedContext.calls.slice();
                clippedContext.calls.length = 0;
                clippedContext.fillRect(1, 2, 3, 4);
                const clippedFill = clippedContext.calls.slice();
                process.stdout.write(JSON.stringify({
                  safeFill, transformedFill, styledFill, integerStroke, pathClip, clippedFill
                }));
                """;
        return MAPPER.readValue(nodeOutput(script), new TypeReference<>() {});
    }

    static String canvasGeometryPrelude(boolean worker, String profileJson) {
        String className = worker ? "OffscreenCanvasRenderingContext2D" : "CanvasRenderingContext2D";
        return """
                'use strict';
                const profile = %s;
                const webglProfile = { mode: 'real' };
                const canvasProfile = profile.canvas;
                const audioProfile = { mode: 'real', noise: 0 };
                const clientRectsProfile = { mode: 'real', noise: 0 };
                const numberOr = (v, f) => Number.isFinite(Number(v)) ? Number(v) : f;
                const document = { readyState: 'complete' };
                const window = { getComputedStyle() { return {}; } };
                globalThis.self = globalThis;
                class Path2D {}
                class %s {
                  constructor(canvas) {
                    this.canvas = canvas;
                    this.calls = [];
                    this.fillStyle = '#000000';
                    this.globalAlpha = 1;
                    this.globalCompositeOperation = 'source-over';
                    this.shadowBlur = 0;
                    this.shadowOffsetX = 0;
                    this.shadowOffsetY = 0;
                    this.shadowColor = 'rgba(0, 0, 0, 0)';
                    this.filter = 'none';
                    this._matrix = { a: 1, b: 0, c: 0, d: 1, e: 0, f: 0 };
                    this._matrixStack = [];
                  }
                  record(name, args) { this.calls.push([name, ...Array.from(args)]); }
                  getTransform() { return { ...this._matrix }; }
                  fillRect(...args) { this.record('fillRect', args); }
                  strokeRect(...args) { this.record('strokeRect', args); }
                  moveTo(...args) { this.record('moveTo', args); }
                  lineTo(...args) { this.record('lineTo', args); }
                  bezierCurveTo(...args) { this.record('bezierCurveTo', args); }
                  quadraticCurveTo(...args) { this.record('quadraticCurveTo', args); }
                  arc(...args) { this.record('arc', args); }
                  arcTo(...args) { this.record('arcTo', args); }
                  ellipse(...args) { this.record('ellipse', args); }
                  rect(...args) { this.record('rect', args); }
                  roundRect(...args) { this.record('roundRect', args); }
                  createLinearGradient(...args) { this.record('createLinearGradient', args); return {}; }
                  createRadialGradient(...args) { this.record('createRadialGradient', args); return {}; }
                  createConicGradient(...args) { this.record('createConicGradient', args); return {}; }
                  drawImage(...args) { this.record('drawImage', args); }
                  save(...args) {
                    this._matrixStack.push({ ...this._matrix });
                    this.record('save', args);
                  }
                  translate(x, y) {
                    this._matrix.e += this._matrix.a * x + this._matrix.c * y;
                    this._matrix.f += this._matrix.b * x + this._matrix.d * y;
                    this.record('translate', arguments);
                  }
                  restore(...args) {
                    if (this._matrixStack.length) this._matrix = this._matrixStack.pop();
                    this.record('restore', args);
                  }
                  fill(...args) { this.record('fill', args); }
                  stroke(...args) { this.record('stroke', args); }
                  clip(...args) { this.record('clip', args); }
                }
                globalThis.Path2D = Path2D;
                globalThis.%s = %s;
                """.formatted(profileJson, className, className, className);
    }

    static String canvasGeometryAssertions() {
        return """
                const canvas = { isConnected: true };
                const context = new (globalThis.CanvasRenderingContext2D
                  || globalThis.OffscreenCanvasRenderingContext2D)(canvas);
                context.moveTo(1, 2);
                context.lineTo(3, 4);
                context.bezierCurveTo(1, 2, 3, 4, 5, 6);
                context.quadraticCurveTo(1, 2, 3, 4);
                context.arc(5, 6, 7, 0, Math.PI);
                context.arcTo(1, 2, 3, 4, 5);
                context.ellipse(5, 6, 7, 8, 0, 0, Math.PI);
                context.rect(1, 2, 30, 40);
                context.roundRect(2, 3, 20, 30, 4);
                context.createLinearGradient(0, 1, 2, 3);
                context.createRadialGradient(0, 1, 2, 3, 4, 5);
                context.createConicGradient(0, 1, 2);
                const image = { toString() { throw new Error('drawImage source was stringified'); } };
                context.drawImage(image, 4, 5);
                context.drawImage(image, 1, 2, 3, 4, 5, 6, 7, 8);
                const path = new Path2D();
                context.fill(path);
                context.stroke(path);
                context.clip(path);
                process.stdout.write(JSON.stringify(context.calls));
                """;
    }

    static String pageWebglPixels(String profileJson) throws Exception {
        String prelude = """
                'use strict';
                const profile = %s;
                const webglProfile = profile.webgl || {};
                const canvasProfile = profile.canvas || {};
                const audioProfile = profile.audio || {};
                const clientRectsProfile = profile.clientRects || {};
                const numberOr = (v, f) => Number.isFinite(Number(v)) ? Number(v) : f;
                const document = { readyState: 'complete' };
                const window = { getComputedStyle() { return {}; } };
                class WebGLRenderingContext {
                  getParameter() { return 'native'; }
                  readPixels(x, y, width, height, format, type, pixels) {
                    for (let i = 0; i < pixels.length; i++) pixels[i] = (i * 17 + 31) & 255;
                  }
                }
                globalThis.WebGLRenderingContext = WebGLRenderingContext;
                """.formatted(profileJson);
        String assertions = """
                const pixels = new Uint8Array(16384);
                new WebGLRenderingContext().readPixels(0, 0, 64, 64, 0, 0, pixels);
                process.stdout.write(Buffer.from(pixels).toString('hex'));
                """;
        return nodeOutput(prelude + SeleniumProxyRenderingSurfaceScripts.SCRIPT + assertions);
    }

    static String mediaDeviceProbe(String origin) throws Exception {
        String prelude = """
                'use strict';
                (() => {
                const profile = { noiseSeed: 424242, mediaDevices: { mode: 'spoof', audioInputs: 1,
                  audioOutputs: 0, videoInputs: 0 }, ports: {}, fonts: {} };
                const _canvasSeed = profile.noiseSeed;
                const intOr = (v, f) => Number.isFinite(Number(v)) ? Math.trunc(Number(v)) : f;
                const screenProfile = { mode: 'real' };
                const window = { devicePixelRatio: 1 };
                const location = { origin: '%s', href: '%s/' };
                class MediaDeviceInfo {
                  constructor(kind, id, group, label) {
                    this._kind=kind; this._id=id; this._group=group; this._label=label;
                  }
                  get kind() { return this._kind; }
                  get deviceId() { return this._id; }
                  get groupId() { return this._group; }
                  get label() { return this._label; }
                  toJSON() { return { kind:this.kind, deviceId:this.deviceId, groupId:this.groupId, label:this.label }; }
                }
                const nativeDevice = new MediaDeviceInfo('audioinput', 'native-id', 'native-group', 'Host microphone');
                const nativeOwnNames = Object.getOwnPropertyNames(nativeDevice).sort().join(',');
                class MediaDevices { async enumerateDevices() { return [nativeDevice]; } }
                const navigator = { mediaDevices: new MediaDevices() };
                """.formatted(origin, origin);
        String assertions = """
                (async () => {
                  const device = (await navigator.mediaDevices.enumerateDevices())[0];
                  if (Object.getPrototypeOf(device) !== MediaDeviceInfo.prototype) throw new Error('prototype changed');
                  if (Object.getOwnPropertyNames(device).sort().join(',') !== nativeOwnNames) throw new Error('own shape changed');
                  if (device.toJSON().deviceId !== device.deviceId) throw new Error('toJSON mismatch');
                  process.stdout.write(device.deviceId);
                })().catch(error => { console.error(error); process.exitCode = 1; });
                """;
        return nodeOutput(mediaRuntime(prelude, assertions));
    }

    static String mediaRuntime(String prelude, String assertions) {
        String component = SeleniumProxyMediaSurfaceScripts.SCRIPT;
        int closureEnd = component.lastIndexOf("})();");
        assertTrue(closureEnd >= 0, "media component must close the surface IIFE");
        return prelude + component.substring(0, closureEnd) + assertions + "\n})();\n";
    }

    static void assertNodeRuns(String script) throws Exception {
        NodeResult result = runNode(script);
        assertEquals(0, result.exitCode(), result.output());
    }

    static String nodeOutput(String script) throws Exception {
        NodeResult result = runNode(script);
        assertEquals(0, result.exitCode(), result.output());
        return result.output();
    }

    static NodeResult runNode(String script) throws Exception {
        Assumptions.assumeTrue(nodeAvailable(), "Node.js is optional; runtime JS contract tests were skipped");
        Path source = Files.createTempFile("jdl-fingerprint-surface-", ".js");
        Process process = null;
        try {
            Files.writeString(source, script, StandardCharsets.UTF_8);
            process = new ProcessBuilder("node", source.toString())
                    .redirectErrorStream(true)
                    .start();
            Process runningProcess = process;
            var outputFuture = java.util.concurrent.CompletableFuture.supplyAsync(() -> {
                try {
                    return runningProcess.getInputStream().readAllBytes();
                } catch (IOException e) {
                    throw new java.util.concurrent.CompletionException(e);
                }
            });
            boolean finished = process.waitFor(20, TimeUnit.SECONDS);
            if (!finished) {
                process.destroyForcibly();
                process.waitFor(5, TimeUnit.SECONDS);
            }
            String output = new String(outputFuture.get(5, TimeUnit.SECONDS), StandardCharsets.UTF_8);
            assertTrue(finished, "node runtime test timed out; output=" + output);
            return new NodeResult(process.exitValue(), output);
        } finally {
            if (process != null && process.isAlive()) {
                process.destroyForcibly();
            }
            Files.deleteIfExists(source);
        }
    }

    static boolean nodeAvailable() {
        try {
            Process process = new ProcessBuilder("node", "--version")
                    .redirectErrorStream(true)
                    .start();
            return process.waitFor(5, TimeUnit.SECONDS) && process.exitValue() == 0;
        } catch (Exception ignored) {
            return false;
        }
    }

    record NodeResult(int exitCode, String output) {}

}
