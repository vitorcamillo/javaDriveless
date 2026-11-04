package io.github.selenium.javaDriverless.scripts;

final class SeleniumProxyRenderingSurfaceScripts {
    static final String SCRIPT = """
  // ═══ DEFERRED WEBGL CAPABILITY NORMALIZATION ═══
  // mode === 'real' (PC com GPU real, pass-through): NAO embrulhar getParameter — em um
  // navegador real getParameter e nativo ([native code]); embrulhar vira tell de automacao.
  // Docker/datacenter usa 'spoof'/'spoof_noise' (esconde llvmpipe) e nunca entra aqui.
  try {
    if (webglProfile.mode !== 'spoof' && webglProfile.mode !== 'spoof_noise' && webglProfile.mode !== 'real') {
      const _patchedWebglNorm = new WeakSet();
      const installWebglCapabilityNormalization = () => {
        const normalizeWebglValue = (value) => value === 4294967295 ? 2147483647 : value;
        const patchWebglProto = (proto) => {
          if (!proto || !proto.getParameter || _patchedWebglNorm.has(proto)) return;
          _patchedWebglNorm.add(proto);
          const nativeGetParameter = proto.getParameter;
          proto.getParameter = function getParameter(pname) {
            return normalizeWebglValue(nativeGetParameter.call(this, pname));
          };
        };
        patchWebglProto(typeof WebGLRenderingContext !== 'undefined' ? WebGLRenderingContext.prototype : null);
        patchWebglProto(typeof WebGL2RenderingContext !== 'undefined' ? WebGL2RenderingContext.prototype : null);
      };
      if (document.readyState === 'loading') {
        document.addEventListener('DOMContentLoaded', installWebglCapabilityNormalization, { once: true });
      } else {
        installWebglCapabilityNormalization();
      }
    }
  } catch {}

  // ═══ WEBGL SPOOFING ═══ (mode === 'spoof' OU 'spoof_noise')
  try {
    if ((webglProfile.mode === 'spoof' || webglProfile.mode === 'spoof_noise') && webglProfile.vendor && webglProfile.renderer) {
      const webglVendor = webglProfile.vendor;
      const webglRenderer = webglProfile.renderer;
      const UNMASKED_VENDOR_WEBGL = 0x9245;
      const UNMASKED_RENDERER_WEBGL = 0x9246;
      const _patchedWebglSpoof = new WeakSet();
      const patchWebglProto = (proto) => {
        if (!proto || !proto.getParameter || _patchedWebglSpoof.has(proto)) return;
        _patchedWebglSpoof.add(proto);
        const getParamOrig = proto.getParameter;
        proto.getParameter = function getParameter(pname) {
          if (pname === UNMASKED_VENDOR_WEBGL) return webglVendor;
          if (pname === UNMASKED_RENDERER_WEBGL) return webglRenderer;
          return getParamOrig.call(this, pname);
        };
      };
      patchWebglProto(typeof WebGLRenderingContext !== 'undefined' ? WebGLRenderingContext.prototype : null);
      patchWebglProto(typeof WebGL2RenderingContext !== 'undefined' ? WebGL2RenderingContext.prototype : null);
      if (typeof OffscreenCanvas !== 'undefined' && OffscreenCanvas.prototype.getContext) {
        const origOffscreenGetContext = OffscreenCanvas.prototype.getContext;
        OffscreenCanvas.prototype.getContext = function getContext() {
          const context = origOffscreenGetContext.apply(this, arguments);
          if (context && context.getParameter) {
            const origGetParameter = context.getParameter.bind(context);
            context.getParameter = function getParameter(pname) {
              if (pname === UNMASKED_VENDOR_WEBGL) return webglVendor;
              if (pname === UNMASKED_RENDERER_WEBGL) return webglRenderer;
              return origGetParameter(pname);
            };
          }
          return context;
        };
      }
    }
  } catch {}

  // ═══ SEEDED LCG — deterministic per-profile noise (never Math.random) ═══
  const _makeLcg = (seed) => {
    let s = seed >>> 0;
    return () => { s = (Math.imul(1664525, s) + 1013904223) >>> 0; return s / 4294967296; };
  };
  const _canvasSeed = (() => {
    if (Number.isFinite(profile.noiseSeed)) return profile.noiseSeed | 0;
    const n = canvasProfile.noise;
    if (n && typeof n === 'object' && Number.isFinite(n.seed)) return n.seed | 0;
    return 1337;
  })();
  const _audioSeed  = _canvasSeed ^ 0x5A3C9E2B;
  const _rectsSeed  = _canvasSeed ^ 0x7F1D4A08;

  // ═══ WEBGL PIXEL NOISE ═══ (only when mode === 'noise') — QUEBRA O CLUSTER.
  // O spoof acima só troca a STRING do renderer; o webglHash (CreepJS/fingerprintjs
  // via gl.readPixels) continua o do renderer REAL = IDENTICO entre contas no mesmo
  // container/GPU. Aqui perturbamos os pixels de readPixels de forma DETERMINISTICA
  // por conteudo + seed do perfil (profile.noiseSeed, por conta): cada conta ganha um
  // webglHash UNICO e ESTAVEL (mesma render -> mesmo ruido -> leituras coerentes),
  // com poucos canais +-1 (imperceptivel). Sem GPU real, e o que diferencia o device.
  try {
    if (webglProfile.mode === 'noise' || webglProfile.mode === 'spoof_noise') {
      const _webglSeed = (_canvasSeed ^ 0x3C6EF35F) >>> 0;
      const _noiseGlPixels = (px) => {
        if (!px || !px.length) return;
        let h = _webglSeed >>> 0;
        const step = Math.max(4, ((px.length / 4096) | 0) * 4);
        for (let i = 0; i < px.length; i += step) h = Math.imul(h ^ px[i], 0x01000193) >>> 0;
        let s = (h ^ px.length) >>> 0;
        const lcg = () => { s = (Math.imul(1664525, s) + 1013904223) >>> 0; return s / 4294967296; };
        const count = Math.max(1, (px.length * 0.004) | 0);
        for (let k = 0; k < count; k++) {
          const idx = (lcg() * px.length) | 0;
          if (idx % 4 === 3) continue;
          const v = px[idx] + (lcg() < 0.5 ? -1 : 1);
          if (v >= 0 && v <= 255) px[idx] = v;
        }
      };
      const _patchReadPixels = (proto) => {
        if (!proto || !proto.readPixels) return;
        const _orig = proto.readPixels;
        proto.readPixels = function(x, y, w, hh, fmt, type, px) {
          const r = _orig.apply(this, arguments);
          try { if (px && (px instanceof Uint8Array || px instanceof Uint8ClampedArray)) _noiseGlPixels(px); } catch (e) {}
          return r;
        };
      };
      _patchReadPixels(typeof WebGLRenderingContext !== 'undefined' ? WebGLRenderingContext.prototype : null);
      _patchReadPixels(typeof WebGL2RenderingContext !== 'undefined' ? WebGL2RenderingContext.prototype : null);
    }
  } catch {}

  // ═══ CANVAS NOISE ═══ (only when mode === 'noise')
  // A serialização/readback precisa continuar nativa. Substituir toDataURL/getImageData, mesmo por
  // Proxy, é observável por Function.prototype.toString e o PixelScan classifica como masking.
  // Para canvases de fingerprint fora do DOM, perturbamos deterministicamente as coordenadas de
  // desenho em uma fração de pixel. O bitmap resultante continua coerente em toDataURL, toBlob e
  // getImageData porque todos leem os mesmos pixels nativos já renderizados.
  try {
    const _cn = canvasProfile.noise;
    const _cnIsObj = _cn && typeof _cn === 'object';
    const _cnActive = canvasProfile.mode === 'noise' && (_cnIsObj ? Number.isFinite(_cn.amplitude) : numberOr(_cn, 0) > 0);
    if (_cnActive) {
      const amplitude = Math.abs(_cnIsObj ? numberOr(_cn.amplitude, 0.01) : numberOr(_cn, 0.01));
      const step = Math.max(1 / 64, Math.min(1 / 32, amplitude * 8));
      const offsetsFor = (name, args) => {
        let hash = (_canvasSeed ^ 0x6D2B79F5) >>> 0;
        const material = name + '|' + Array.from(args, (value, index) => {
          if (value === null) return 'null';
          const type = typeof value;
          return ['string','number','boolean','bigint','undefined'].includes(type)
            ? type + ':' + String(value) : type + '@' + index;
        }).join('|');
        for (let i = 0; i < material.length; i++) {
          hash = Math.imul(hash ^ material.charCodeAt(i), 0x01000193) >>> 0;
        }
        hash ^= hash >>> 16;
        hash = Math.imul(hash, 0x7feb352d) >>> 0;
        hash ^= hash >>> 15;
        hash = Math.imul(hash, 0x846ca68b) >>> 0;
        hash ^= hash >>> 16;
        const maxShift = step * 8;
        const signedOffset = value16 => ((value16 / 0xffff) * 2 - 1) * maxShift;
        return [
          signedOffset(hash & 0xffff),
          signedOffset((hash >>> 16) & 0xffff)
        ];
      };
      const shouldPerturb = context => {
        const canvas = context && context.canvas;
        if (!canvas) return false;
        return canvasProfile.scope !== 'detached'
          || !('isConnected' in canvas)
          || canvas.isConnected === false;
      };
      const clippedContexts = new WeakSet();
      const hasOnlyIntegerRectCoordinates = args => args.length >= 4
        && args.slice(0, 4).every(value => Number.isFinite(Number(value))
          && Number.isInteger(Number(value)));
      const isTransparentShadow = value => {
        if (typeof value !== 'string') return false;
        const compact = value.trim().toLowerCase().replaceAll(' ', '');
        return compact === 'transparent'
          || compact === '#0000'
          || compact === '#00000000'
          || compact === 'rgba(0,0,0,0)'
          || compact === 'rgb(0,0,0,0)';
      };
      const hasPixelAlignedTransform = (context, nativeGetTransform) => {
        if (typeof nativeGetTransform !== 'function') return false;
        try {
          const matrix = Reflect.apply(nativeGetTransform, context, []);
          return matrix
            && Number(matrix.a) === 1 && Number(matrix.b) === 0
            && Number(matrix.c) === 0 && Number(matrix.d) === 1
            && Number.isInteger(Number(matrix.e))
            && Number.isInteger(Number(matrix.f));
        } catch {
          return false;
        }
      };
      const isSafeIntegerFillRect = (context, args, nativeGetTransform) => {
        if (!hasOnlyIntegerRectCoordinates(args) || clippedContexts.has(context)
            || !hasPixelAlignedTransform(context, nativeGetTransform)) return false;
        try {
          const filter = context.filter;
          return typeof context.fillStyle === 'string'
            && Number(context.globalAlpha) === 1
            && context.globalCompositeOperation === 'source-over'
            && Number(context.shadowBlur) === 0
            && Number(context.shadowOffsetX) === 0
            && Number(context.shadowOffsetY) === 0
            && isTransparentShadow(context.shadowColor)
            && (typeof filter === 'undefined' || filter === 'none');
        } catch {
          return false;
        }
      };
      const patchCoordinatePairs = (proto, name, pairs, shouldShift = () => true) => {
        if (!proto || typeof proto[name] !== 'function') return;
        const nativeDraw = proto[name];
        proto[name] = new Proxy(nativeDraw, {
          apply(target, thisArg, args) {
            if (!shouldPerturb(thisArg)) return Reflect.apply(target, thisArg, args);
            const shifted = Array.from(args);
            if (!shouldShift(shifted, thisArg)) return Reflect.apply(target, thisArg, args);
            const offsets = offsetsFor(name, shifted);
            for (const [xIndex, yIndex] of pairs) {
              if (Number.isFinite(Number(shifted[xIndex]))) shifted[xIndex] = Number(shifted[xIndex]) + offsets[0];
              if (Number.isFinite(Number(shifted[yIndex]))) shifted[yIndex] = Number(shifted[yIndex]) + offsets[1];
            }
            return Reflect.apply(target, thisArg, shifted);
          }
        });
      };
      const patchDrawImage = proto => {
        if (!proto || typeof proto.drawImage !== 'function') return;
        const nativeDraw = proto.drawImage;
        proto.drawImage = new Proxy(nativeDraw, {
          apply(target, thisArg, args) {
            if (!shouldPerturb(thisArg)) return Reflect.apply(target, thisArg, args);
            const shifted = Array.from(args);
            const pair = shifted.length >= 9 ? [5, 6] : [1, 2];
            const offsets = offsetsFor('drawImage', shifted);
            if (Number.isFinite(Number(shifted[pair[0]]))) shifted[pair[0]] = Number(shifted[pair[0]]) + offsets[0];
            if (Number.isFinite(Number(shifted[pair[1]]))) shifted[pair[1]] = Number(shifted[pair[1]]) + offsets[1];
            return Reflect.apply(target, thisArg, shifted);
          }
        });
      };
      const patchPathDraw = (proto, name) => {
        if (!proto || typeof proto[name] !== 'function' || typeof Path2D === 'undefined') return;
        const nativeDraw = proto[name];
        const nativeSave = proto.save;
        const nativeRestore = proto.restore;
        const nativeTranslate = proto.translate;
        if (typeof nativeSave !== 'function' || typeof nativeRestore !== 'function'
            || typeof nativeTranslate !== 'function') return;
        proto[name] = new Proxy(nativeDraw, {
          apply(target, thisArg, args) {
            if (!shouldPerturb(thisArg) || !(args[0] instanceof Path2D)) {
              return Reflect.apply(target, thisArg, args);
            }
            const offsets = offsetsFor(name + ':Path2D', args);
            Reflect.apply(nativeSave, thisArg, []);
            try {
              Reflect.apply(nativeTranslate, thisArg, offsets);
              return Reflect.apply(target, thisArg, args);
            } finally {
              Reflect.apply(nativeRestore, thisArg, []);
            }
          }
        });
      };
      const patchClip = proto => {
        if (!proto || typeof proto.clip !== 'function') return;
        const nativeClip = proto.clip;
        const nativeTranslate = proto.translate;
        proto.clip = new Proxy(nativeClip, {
          apply(target, thisArg, args) {
            if (!shouldPerturb(thisArg)) return Reflect.apply(target, thisArg, args);
            clippedContexts.add(thisArg);
            if (typeof Path2D === 'undefined' || !(args[0] instanceof Path2D)
                || typeof nativeTranslate !== 'function') {
              return Reflect.apply(target, thisArg, args);
            }
            const offsets = offsetsFor('clip:Path2D', args);
            Reflect.apply(nativeTranslate, thisArg, offsets);
            try {
              return Reflect.apply(target, thisArg, args);
            } finally {
              Reflect.apply(nativeTranslate, thisArg, [-offsets[0], -offsets[1]]);
            }
          }
        });
      };
      const patch2d = proto => {
        if (!proto) return;
        const nativeGetTransform = proto.getTransform;
        patchCoordinatePairs(proto, 'fillText', [[1, 2]]);
        patchCoordinatePairs(proto, 'strokeText', [[1, 2]]);
        patchCoordinatePairs(proto, 'fillRect', [[0, 1]],
          (args, context) => !isSafeIntegerFillRect(context, args, nativeGetTransform));
        patchCoordinatePairs(proto, 'strokeRect', [[0, 1]]);
        patchCoordinatePairs(proto, 'moveTo', [[0, 1]]);
        patchCoordinatePairs(proto, 'lineTo', [[0, 1]]);
        patchCoordinatePairs(proto, 'bezierCurveTo', [[0, 1], [2, 3], [4, 5]]);
        patchCoordinatePairs(proto, 'quadraticCurveTo', [[0, 1], [2, 3]]);
        patchCoordinatePairs(proto, 'arc', [[0, 1]]);
        patchCoordinatePairs(proto, 'arcTo', [[0, 1], [2, 3]]);
        patchCoordinatePairs(proto, 'ellipse', [[0, 1]]);
        patchCoordinatePairs(proto, 'rect', [[0, 1]]);
        patchCoordinatePairs(proto, 'roundRect', [[0, 1]]);
        patchCoordinatePairs(proto, 'createLinearGradient', [[0, 1], [2, 3]]);
        patchCoordinatePairs(proto, 'createRadialGradient', [[0, 1], [3, 4]]);
        patchCoordinatePairs(proto, 'createConicGradient', [[1, 2]]);
        patchDrawImage(proto);
        patchPathDraw(proto, 'fill');
        patchPathDraw(proto, 'stroke');
        patchClip(proto);
      };
      patch2d(typeof CanvasRenderingContext2D !== 'undefined' ? CanvasRenderingContext2D.prototype : null);
      patch2d(typeof OffscreenCanvasRenderingContext2D !== 'undefined' ? OffscreenCanvasRenderingContext2D.prototype : null);
    }
  } catch {}

  // ═══ AUDIO NOISE ═══ (only when mode === 'noise') — DETERMINÍSTICO por conteúdo
  // A semente do ruído vem do CONTEÚDO do array (não de um LCG global que avançava a cada leitura),
  // então a mesma entrada produz SEMPRE o mesmo ruído: leituras repetidas batem (real Chrome é estável;
  // antes, ler 2x o mesmo fingerprint de áudio dava valores diferentes = sinal de spoof).
  try {
    if (audioProfile.mode === 'noise' && numberOr(audioProfile.noise, 0) > 0) {
      const aNoise = numberOr(audioProfile.noise, 0.002);
      const _seedFloats = (arr) => {
        let h = _audioSeed >>> 0;
        const step = Math.max(1, (arr.length / 1024) | 0);
        for (let i = 0; i < arr.length; i += step) h = Math.imul(h ^ ((arr[i] * 1e6) | 0), 0x01000193) >>> 0;
        return (h ^ arr.length) >>> 0;
      };
      const _noiseFloats = (arr) => {
        let s = _seedFloats(arr);
        const lcg = () => { s = (Math.imul(1664525, s) + 1013904223) >>> 0; return s / 4294967296; };
        for (let i = 0; i < arr.length; i++) arr[i] += (lcg() * 2 - 1) * aNoise;
      };
      // getChannelData devolve a Float32Array VIVA do buffer; ruidar in-place a cada chamada acumularia.
      // WeakSet garante ruidar cada array UMA vez (semente do conteúdo limpo) → leituras estáveis.
      const _noised = new WeakSet();
      const origGetChannelData = AudioBuffer.prototype.getChannelData;
      AudioBuffer.prototype.getChannelData = function(channel) {
        const data = origGetChannelData.call(this, channel);
        if (!_noised.has(data)) { _noiseFloats(data); _noised.add(data); }
        return data;
      };
      // copyFromChannel: rota alternativa do spec; destino é fresco a cada chamada → semente do conteúdo.
      if (AudioBuffer.prototype.copyFromChannel) {
        const origCopyFromChannel = AudioBuffer.prototype.copyFromChannel;
        AudioBuffer.prototype.copyFromChannel = function(destination, channelNumber, bufferOffset) {
          origCopyFromChannel.call(this, destination, channelNumber, bufferOffset);
          _noiseFloats(destination);
        };
      }
      // AnalyserNode: vetor REAL de audio fingerprint. O array é preenchido com os dados reais a cada
      // chamada → semente do conteúdo dá ruído estável e coerente entre leituras.
      if (typeof AnalyserNode !== 'undefined' && AnalyserNode.prototype) {
        const origFFD = AnalyserNode.prototype.getFloatFrequencyData;
        if (origFFD) AnalyserNode.prototype.getFloatFrequencyData = function(array) {
          origFFD.call(this, array); _noiseFloats(array);
        };
        const origBFD = AnalyserNode.prototype.getByteFrequencyData;
        if (origBFD) AnalyserNode.prototype.getByteFrequencyData = function(array) {
          origBFD.call(this, array);
          let h = _audioSeed >>> 0;
          const step = Math.max(1, (array.length / 1024) | 0);
          for (let i = 0; i < array.length; i += step) h = Math.imul(h ^ array[i], 0x01000193) >>> 0;
          let s = (h ^ array.length) >>> 0;
          const lcg = () => { s = (Math.imul(1664525, s) + 1013904223) >>> 0; return s / 4294967296; };
          for (let i = 0; i < array.length; i++) array[i] = Math.max(0, Math.min(255, array[i] + Math.round((lcg() * 2 - 1) * aNoise * 255)));
        };
        const origFTD = AnalyserNode.prototype.getFloatTimeDomainData;
        if (origFTD) AnalyserNode.prototype.getFloatTimeDomainData = function(array) {
          origFTD.call(this, array); _noiseFloats(array);
        };
      }
    }
  } catch {}

  // ═══ CLIENT RECTS NOISE ═══ (only when mode === 'noise') — DETERMINÍSTICO por geometria
  // A semente vem da geometria LIMPA do próprio rect → o mesmo elemento recebe SEMPRE o mesmo ruído
  // (leituras repetidas batem; real Chrome é estável). Antes, um LCG global avançava a cada chamada,
  // então getBoundingClientRect 2x no mesmo elemento dava valores diferentes = sinal de spoof.
  try {
    if (clientRectsProfile.mode === 'noise' && numberOr(clientRectsProfile.noise, 0) > 0) {
      const rNoise = numberOr(clientRectsProfile.noise, 0.05);
      const _noiseRect = (r) => {
        let s = (_rectsSeed
          ^ (Math.round(r.x * 73) | 0)
          ^ (Math.round(r.y * 131) | 0)
          ^ (Math.round(r.width * 197) | 0)
          ^ (Math.round(r.height * 239) | 0)) >>> 0;
        const lcg = () => { s = (Math.imul(1664525, s) + 1013904223) >>> 0; return s / 4294967296; };
        return new DOMRect(
          r.x + (lcg() * 2 - 1) * rNoise,
          r.y + (lcg() * 2 - 1) * rNoise,
          r.width + (lcg() * 2 - 1) * rNoise,
          r.height + (lcg() * 2 - 1) * rNoise
        );
      };
      const origGetClientRects = Element.prototype.getClientRects;
      Element.prototype.getClientRects = function() {
        const rects = origGetClientRects.call(this);
        const noisy = Array.from({ length: rects.length }, (_, i) => _noiseRect(rects[i]));
        // DOMRectList não possui construtor público. Um Array (implementação anterior) vazava
        // imediatamente por instanceof/prototype/toStringTag e não possuía item(). O Proxy mantém
        // o objeto WebIDL nativo como target e substitui apenas os valores observáveis.
        const nativeItem = typeof rects.item === 'function' ? rects.item : null;
        const item = nativeItem ? new Proxy(nativeItem, {
          apply(target, thisArg, args) {
            const index = Number(args[0]);
            return Number.isInteger(index) && index >= 0 && index < noisy.length ? noisy[index] : null;
          }
        }) : null;
        const nativeIterator = rects[Symbol.iterator];
        const iterator = typeof nativeIterator === 'function' ? new Proxy(nativeIterator, {
          apply() { return noisy[Symbol.iterator](); }
        }) : null;
        return new Proxy(rects, {
          get(target, prop) {
            if (prop === 'item' && item) return item;
            if (prop === Symbol.iterator && iterator) return iterator;
            if (typeof prop === 'string' && /^(0|[1-9]\\d*)$/.test(prop)) {
              const index = Number(prop);
              if (index < noisy.length) return noisy[index];
            }
            return Reflect.get(target, prop, target);
          },
          getOwnPropertyDescriptor(target, prop) {
            const descriptor = Reflect.getOwnPropertyDescriptor(target, prop);
            if (descriptor && descriptor.configurable && typeof prop === 'string'
                && /^(0|[1-9]\\d*)$/.test(prop) && Number(prop) < noisy.length) {
              return Object.assign({}, descriptor, { value: noisy[Number(prop)] });
            }
            return descriptor;
          }
        });
      };
      const origGetBCR = Element.prototype.getBoundingClientRect;
      Element.prototype.getBoundingClientRect = function() {
        return _noiseRect(origGetBCR.call(this));
      };
    }
  } catch {}

  // ═══ SYSTEM COLOR NORMALIZATION ═══
  try {
    const nativeGetComputedStyle = window.getComputedStyle;
    let _stylePatched = false;
    if (typeof nativeGetComputedStyle === 'function' && !_stylePatched) {
      _stylePatched = true;
      const patchedGetComputedStyle = new Proxy(nativeGetComputedStyle, {
        apply(target, thisArg, args) {
          const style = Reflect.apply(target, thisArg, args);
          try {
            const element = args && args[0];
            const inlineStyle = element && element.getAttribute && (element.getAttribute('style') || '');
            if (/background-color\\s*:\\s*ActiveText/i.test(inlineStyle) &&
                style && style.backgroundColor === 'rgb(255, 0, 0)') {
              return new Proxy(style, {
                get(styleTarget, prop, receiver) {
                  if (prop === 'backgroundColor') return 'rgb(0, 0, 0)';
                  if (prop === 'getPropertyValue') {
                    return function getPropertyValue(name) {
                      if (String(name).toLowerCase() === 'background-color') return 'rgb(0, 0, 0)';
                      return styleTarget.getPropertyValue.call(styleTarget, name);
                    };
                  }
                  return Reflect.get(styleTarget, prop, receiver);
                }
              });
            }
          } catch {}
          return style;
        }
      });
      Object.defineProperty(window, 'getComputedStyle', {
        value: patchedGetComputedStyle,
        configurable: true,
        writable: true
      });
    }
  } catch {}

""";

    private SeleniumProxyRenderingSurfaceScripts() {}
}
