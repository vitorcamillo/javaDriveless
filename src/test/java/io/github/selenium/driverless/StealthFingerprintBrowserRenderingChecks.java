package io.github.selenium.driverless;

import io.github.selenium.javaDriverless.sync.SyncChrome;
import io.github.selenium.javaDriverless.types.BrowserFingerprintProfile;
import org.junit.jupiter.api.*;

import static org.assertj.core.api.Assertions.*;

abstract class StealthFingerprintBrowserRenderingChecks extends StealthFingerprintBrowserIdentityChecks {
    // 20-29: SCREEN & VIEWPORT — Dimensões realistas
    // ════════════════════════════════════════════════════════════════

    @Test @Order(20)
    @DisplayName("[SCR] screen.width corresponde ao perfil")
    void screenWidth() {
        int w = ((Number) js("screen.width")).intValue();
        System.out.println("  screen.width = " + w);
        assertThat(w).isEqualTo(fingerprintProfile.screen.width);
    }

    @Test @Order(21)
    @DisplayName("[SCR] screen.height corresponde ao perfil")
    void screenHeight() {
        int h = ((Number) js("screen.height")).intValue();
        System.out.println("  screen.height = " + h);
        assertThat(h).isEqualTo(fingerprintProfile.screen.height);
    }

    @Test @Order(22)
    @DisplayName("[SCR] colorDepth/pixelDepth correspondem ao perfil")
    void screenColorDepth() {
        int cd = ((Number) js("screen.colorDepth")).intValue();
        int pd = ((Number) js("screen.pixelDepth")).intValue();
        System.out.println("  colorDepth = " + cd);
        assertThat(cd).isEqualTo(fingerprintProfile.screen.colorDepth);
        assertThat(pd).isEqualTo(fingerprintProfile.screen.pixelDepth);
    }

    @Test @Order(23)
    @DisplayName("[SCR] innerHeight < screen.height (chrome UI existe)")
    void viewportVsScreen() {
        Object r = js("({ih:window.innerHeight,sh:screen.height,oh:window.outerHeight})");
        System.out.println("  viewport = " + r);
        int ih = ((Number) js("window.innerHeight")).intValue();
        int sh = ((Number) js("screen.height")).intValue();
        // innerHeight DEVE ser menor que screenHeight (browser chrome consome espaço)
        assertThat(ih).isLessThan(sh)
            .withFailMessage("innerHeight(%d) >= screenHeight(%d) = headless flag!", ih, sh);
        if (!"real".equalsIgnoreCase(fingerprintProfile.screen.mode)) {
            assertThat(ih).isEqualTo(Math.max(1, fingerprintProfile.screen.availHeight - 95));
        }
    }

    @Test @Order(24)
    @DisplayName("[SCR] outerWidth/outerHeight coerentes com a tela do perfil")
    void outerDimensions() {
        int ow = ((Number) js("window.outerWidth||0")).intValue();
        int oh = ((Number) js("window.outerHeight||0")).intValue();
        System.out.println("  outer = " + ow + "x" + oh);
        if (!"real".equalsIgnoreCase(fingerprintProfile.screen.mode)) {
            assertThat(ow).isEqualTo(fingerprintProfile.screen.width);
            assertThat(oh).isEqualTo(fingerprintProfile.screen.availHeight - 8);
        } else {
            assertThat(ow).isGreaterThan(0);
            assertThat(oh).isGreaterThan(0);
        }
    }

    @Test @Order(25)
    @DisplayName("[SCR] devicePixelRatio corresponde ao perfil")
    void devicePixelRatio() {
        double dpr = ((Number) js("window.devicePixelRatio||0")).doubleValue();
        System.out.println("  devicePixelRatio = " + dpr);
        assertThat(dpr).isEqualTo(fingerprintProfile.screen.deviceScaleFactor);
    }

    // ════════════════════════════════════════════════════════════════
    // 30-39: WEBGL & CANVAS — Rendering fingerprint
    // ════════════════════════════════════════════════════════════════

    @Test @Order(30)
    @DisplayName("[GL] WebGL vendor corresponde ao perfil ativo")
    void webglVendorReal() {
        Object r = js("""
            (function(){var c=document.createElement('canvas');var gl=c.getContext('webgl');
            if(!gl)return'NO_WEBGL';var e=gl.getExtension('WEBGL_debug_renderer_info');
            if(!e)return'NO_EXT';return gl.getParameter(e.UNMASKED_VENDOR_WEBGL);})()""");
        System.out.println("  WebGL vendor = " + r);
        String expected = fingerprintProfile.webgl.unmaskedVendor == null
                || fingerprintProfile.webgl.unmaskedVendor.isBlank()
                ? fingerprintProfile.webgl.vendor : fingerprintProfile.webgl.unmaskedVendor;
        assertThat(r.toString()).isNotIn("NO_WEBGL", "NO_EXT", "Brian Paul", "Google SwiftShader", "SwiftShader");
        if (!"real".equalsIgnoreCase(fingerprintProfile.webgl.mode)) {
            assertThat(r.toString()).isEqualTo(expected);
        }
    }

    @Test @Order(31)
    @DisplayName("[GL] WebGL renderer corresponde ao perfil ativo")
    void webglRendererReal() {
        Object r = js("""
            (function(){var c=document.createElement('canvas');var gl=c.getContext('webgl');
            if(!gl)return'NO_WEBGL';var e=gl.getExtension('WEBGL_debug_renderer_info');
            if(!e)return'NO_EXT';return gl.getParameter(e.UNMASKED_RENDERER_WEBGL);})()""");
        System.out.println("  WebGL renderer = " + r);
        String expected = fingerprintProfile.webgl.unmaskedRenderer == null
                || fingerprintProfile.webgl.unmaskedRenderer.isBlank()
                ? fingerprintProfile.webgl.renderer : fingerprintProfile.webgl.unmaskedRenderer;
        assertThat(r.toString()).isNotIn("NO_WEBGL", "NO_EXT").doesNotContain("SwiftShader", "llvmpipe");
        if (!"real".equalsIgnoreCase(fingerprintProfile.webgl.mode)) {
            assertThat(r.toString()).isEqualTo(expected);
        }
    }

    @Test @Order(32)
    @DisplayName("[GL] Worker WebGL renderer corresponde ao perfil ativo")
    void workerWebglRendererReal() {
        driver.get("http://127.0.0.1:" + protectedPortProbePort + "/");
        driver.sleep(0.5);
        Object r = driver.executeScript("""
            new Promise(resolve => {
              const source = `
                const canvas = new OffscreenCanvas(1, 1);
                const gl = canvas.getContext('webgl');
                if (!gl) {
                  postMessage('NO_WEBGL');
                } else {
                  const ext = gl.getExtension('WEBGL_debug_renderer_info');
                  postMessage(ext ? gl.getParameter(ext.UNMASKED_RENDERER_WEBGL) : 'NO_EXT');
                }
              `;
              const worker = new Worker(URL.createObjectURL(new Blob([source], {type:'text/javascript'})));
              worker.onmessage = event => { resolve(event.data); worker.terminate(); };
              worker.onerror = event => resolve('WORKER_ERROR:' + (event.message || 'unknown'));
            })
            """, null, true);
        System.out.println("  Worker WebGL renderer = " + r);
        String expected = fingerprintProfile.webgl.unmaskedRenderer == null
                || fingerprintProfile.webgl.unmaskedRenderer.isBlank()
                ? fingerprintProfile.webgl.renderer : fingerprintProfile.webgl.unmaskedRenderer;
        assertThat(r.toString()).isNotIn("{}", "", "NO_WEBGL", "NO_EXT")
                .doesNotStartWith("WORKER_ERROR:")
                .doesNotContain("SwiftShader", "llvmpipe");
        if (!"real".equalsIgnoreCase(fingerprintProfile.webgl.mode)) {
            assertThat(r.toString()).isEqualTo(expected);
        }
    }

    @Test @Order(33)
    @DisplayName("[GL] Canvas fingerprint é estável (2 chamadas = mesmo hash)")
    void canvasStable() {
        String script = """
            (function(){var c=document.createElement('canvas');c.width=200;c.height=50;
            var x=c.getContext('2d');x.textBaseline='top';x.font='14px Arial';
            x.fillStyle='#f60';x.fillRect(125,1,62,20);x.fillStyle='#069';
            x.fillText('Cwm fjord',2,15);
            var data=c.toDataURL(), hash=2166136261;
            for(var i=0;i<data.length;i++){hash^=data.charCodeAt(i);hash=Math.imul(hash,16777619);}
            return {hash:(hash>>>0).toString(16).padStart(8,'0'),length:data.length};})()""";
        Object h1 = js(script);
        Object h2 = js(script);
        System.out.println("  canvas hash = " + h1);
        assertThat(h1).isInstanceOf(java.util.Map.class);
        @SuppressWarnings("unchecked")
        java.util.Map<String, Object> canvas = (java.util.Map<String, Object>) h1;
        assertThat(canvas.get("hash").toString()).hasSize(8);
        assertThat(((Number) canvas.get("length")).intValue()).isGreaterThan(100);
        assertThat(h1).isEqualTo(h2);
    }

    @Test @Order(34)
    @DisplayName("[GL] WebGL2 é nativo e repete a identidade WebGL do perfil")
    void webgl2Context() {
        Object r = js("""
                (function(){
                  var gl = document.createElement('canvas').getContext('webgl2');
                  if (!gl) return {error:'NO_WEBGL2'};
                  var ext = gl.getExtension('WEBGL_debug_renderer_info');
                  if (!ext) return {error:'NO_EXT'};
                  return {
                    tag:Object.prototype.toString.call(gl),
                    instance:typeof WebGL2RenderingContext !== 'undefined' && gl instanceof WebGL2RenderingContext,
                    vendor:gl.getParameter(ext.UNMASKED_VENDOR_WEBGL),
                    renderer:gl.getParameter(ext.UNMASKED_RENDERER_WEBGL)
                  };
                })()
                """);
        System.out.println("  WebGL2 = " + r);
        assertThat(r).isInstanceOf(java.util.Map.class);
        @SuppressWarnings("unchecked")
        java.util.Map<String, Object> webgl2 = (java.util.Map<String, Object>) r;
        assertThat(webgl2).doesNotContainKey("error");
        assertThat(webgl2.get("tag")).isEqualTo("[object WebGL2RenderingContext]");
        assertThat(webgl2.get("instance")).isEqualTo(true);
        if (!"real".equalsIgnoreCase(fingerprintProfile.webgl.mode)) {
            String expectedVendor = fingerprintProfile.webgl.unmaskedVendor == null
                    || fingerprintProfile.webgl.unmaskedVendor.isBlank()
                    ? fingerprintProfile.webgl.vendor : fingerprintProfile.webgl.unmaskedVendor;
            String expectedRenderer = fingerprintProfile.webgl.unmaskedRenderer == null
                    || fingerprintProfile.webgl.unmaskedRenderer.isBlank()
                    ? fingerprintProfile.webgl.renderer : fingerprintProfile.webgl.unmaskedRenderer;
            assertThat(webgl2.get("vendor")).isEqualTo(expectedVendor);
            assertThat(webgl2.get("renderer")).isEqualTo(expectedRenderer);
        }
    }

    @Test @Order(35)
    @DisplayName("[GL] Canvas permanece coerente em iframe javascript:")
    void canvasMatchesJavascriptIframeRealm() {
        driver.get("http://127.0.0.1:" + protectedPortProbePort + "/");
        Object result = driver.executeScript("""
                new Promise(resolve => {
                  const fingerprint = realm => {
                    const canvas = realm.document.createElement('canvas');
                    canvas.width = 300;
                    canvas.height = 150;
                    const context = canvas.getContext('2d');
                    context.textBaseline = 'alphabetic';
                    context.fillStyle = '#f60';
                    context.fillRect(125, 1, 62, 20);
                    context.fillStyle = '#069';
                    context.font = '11pt no-real-font-590';
                    context.fillText('Cwm fjordbank glyphs vext quiz', 2, 15);
                    context.fillStyle = 'rgba(102, 204, 0, 0.2)';
                    context.font = '14pt Arial';
                    context.fillText('Cwm fjordbank glyphs vext quiz', 4, 45);
                    const data = canvas.toDataURL();
                    let hash = 2166136261;
                    for (let i = 0; i < data.length; i++) {
                      hash ^= data.charCodeAt(i);
                      hash = Math.imul(hash, 16777619);
                    }
                    return {
                      hash: (hash >>> 0).toString(16).padStart(8, '0'),
                      length: data.length,
                      nativeShape: /\\[native code\\]/.test(canvas.toDataURL.toString())
                    };
                  };
                  const topResult = fingerprint(window);
                  const iframe = document.createElement('iframe');
                  const frameProbe = function() {
                    const canvas = document.createElement('canvas');
                    canvas.width = 300;
                    canvas.height = 150;
                    const context = canvas.getContext('2d');
                    context.textBaseline = 'alphabetic';
                    context.fillStyle = '#f60';
                    context.fillRect(125, 1, 62, 20);
                    context.fillStyle = '#069';
                    context.font = '11pt no-real-font-590';
                    context.fillText('Cwm fjordbank glyphs vext quiz', 2, 15);
                    context.fillStyle = 'rgba(102, 204, 0, 0.2)';
                    context.font = '14pt Arial';
                    context.fillText('Cwm fjordbank glyphs vext quiz', 4, 45);
                    const data = canvas.toDataURL();
                    let hash = 2166136261;
                    for (let i = 0; i < data.length; i++) {
                      hash ^= data.charCodeAt(i);
                      hash = Math.imul(hash, 16777619);
                    }
                    parent.postMessage({
                      messageSource: 'jdl-canvas-first-statement',
                      value: {
                        hash: (hash >>> 0).toString(16).padStart(8, '0'),
                        length: data.length,
                        nativeShape: /\\[native code\\]/.test(canvas.toDataURL.toString())
                      }
                    }, '*');
                  };
                  const finish = value => {
                    try { iframe.remove(); } catch (_) {}
                    resolve(value);
                  };
                  const timeout = setTimeout(() => finish({ error: 'iframe timeout' }), 5000);
                  const onMessage = event => {
                    if (!event.data || event.data.messageSource !== 'jdl-canvas-first-statement') return;
                    window.removeEventListener('message', onMessage);
                    clearTimeout(timeout);
                    finish({ top: topResult, frame: event.data.value });
                  };
                  window.addEventListener('message', onMessage);
                  const source = '(' + frameProbe.toString() + ')()';
                  iframe.src = 'javascript:eval(atob("' + btoa(source) + '"))';
                  document.body.appendChild(iframe);
                })
                """, null, true);

        assertThat(result).isInstanceOf(java.util.Map.class);
        @SuppressWarnings("unchecked")
        java.util.Map<String, Object> realms = (java.util.Map<String, Object>) result;
        assertThat(realms).doesNotContainKey("error");
        assertThat(realms.get("top")).isEqualTo(realms.get("frame"));
    }

    @Test @Order(36)
    @DisplayName("[GL] PixelScan vê o mesmo fingerprint no topo e no iframe")
    void pixelScanFingerprintMatchesItsIframe() {
        BrowserFingerprintProfile pixelscanProfile = externalProfileForSite("pixelscan");
        SyncChrome pixelscanDriver = createExternalDriver(pixelscanProfile);
        try {
            // Este teste mede apenas paridade entre realms. O veredito do detector e comparado
            // contra Chrome cru, no mesmo transporte, por pixelscanLoad().
            StealthFingerprintExternalSiteSupport.captureContract(
                    pixelscanDriver, pixelscanProfile, "pixelscan",
                    "https://pixelscan.net/fingerprint-check", 12.0, false);
            Object result = pixelscanDriver.executeScript("""
                (async () => {
                  const iframe = document.querySelector('pxlscn-fingerprint-masking iframe');
                  if (typeof Fptc !== 'function') return { error: 'Fptc unavailable' };
                  if (!iframe || !iframe.contentWindow || !iframe.contentWindow.fp) {
                    return { error: 'PixelScan fingerprint iframe unavailable' };
                  }
                  const topFingerprint = await new Fptc().getFingerprints();
                  const frameFingerprint = await iframe.contentWindow.fp.getFingerprints();
                  const keys = Array.from(new Set([
                    ...Object.keys(topFingerprint), ...Object.keys(frameFingerprint)
                  ])).sort();
                  const differences = {};
                  for (const key of keys) {
                    if (topFingerprint[key] !== frameFingerprint[key]) {
                      differences[key] = {
                        top: topFingerprint[key] || null,
                        frame: frameFingerprint[key] || null
                      };
                    }
                  }
                  const hiddenCanvas = Array.from(document.querySelectorAll('#red-solid-box canvas')).map(canvas => {
                    const context = canvas.getContext('2d');
                    const colors = new Set();
                    let hash = 2166136261;
                    const serialized = canvas.toDataURL();
                    for (let i = 0; i < serialized.length; i++) {
                      hash ^= serialized.charCodeAt(i);
                      hash = Math.imul(hash, 16777619);
                    }
                    if (context) {
                      const pixels = context.getImageData(0, 0, canvas.width, canvas.height).data;
                      for (let i = 0; i < pixels.length; i += 4) {
                        colors.add(`${pixels[i]},${pixels[i + 1]},${pixels[i + 2]},${pixels[i + 3]}`);
                      }
                    }
                    return {
                      width: canvas.width,
                      height: canvas.height,
                      context2d: !!context,
                      colors: context ? colors.size : null,
                      hash: (hash >>> 0).toString(16).padStart(8, '0')
                    };
                  });
                  return { differences, hiddenCanvas };
                })()
                """, null, true);

            System.out.println("  PixelScan realm comparison = " + result);
            assertThat(result).isInstanceOf(java.util.Map.class);
            @SuppressWarnings("unchecked")
            java.util.Map<String, Object> comparison = (java.util.Map<String, Object>) result;
            assertThat(comparison).doesNotContainKey("error");
            assertThat(comparison.get("differences")).isEqualTo(java.util.Map.of());
        } finally {
            pixelscanDriver.quit();
        }
    }

}
