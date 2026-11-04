package io.github.selenium.javaDriverless.scripts;

final class SeleniumProxyWorkerSurfaceScriptPartOne {
    static final String VALUE = """
(() => {
  // O auto-attach deduplica por targetId; não expor guard em self/globalThis do worker.
  const profile = __PROFILE_JSON__;
  const numberOr = (v, f) => Number.isFinite(Number(v)) ? Number(v) : f;
  const intOr = (v, f) => Math.trunc(numberOr(v, f));
  // NOTA: NÃO sobrescrever Function.prototype.toString aqui (medido: CreepJS detecta e penaliza).
  const def = (obj, prop, getter) => {
    try { Object.defineProperty(obj, prop, { get: getter, configurable: true, enumerable: true }); } catch {}
  };

  // ═══ WORKER NAVIGATOR (hardwareConcurrency / deviceMemory) ═══
  // Só essas duas existem nativamente em WorkerNavigator. NÃO adicionar 'vendor'
  // (não existe em WorkerNavigator — criá-lo seria, por si só, uma mentira).
  try {
    if (typeof WorkerNavigator !== 'undefined' && typeof navigator !== 'undefined') {
      // Capturar os valores nativos ANTES de redefinir — senão o getter referenciaria a própria
      // propriedade redefinida e entraria em recursão infinita (Maximum call stack size exceeded).
      const _nativeCores = navigator.hardwareConcurrency;
      const _nativeMem = navigator.deviceMemory;
      if (profile.hardwareConcurrency && intOr(profile.hardwareConcurrency, 0) !== _nativeCores) {
        def(WorkerNavigator.prototype, 'hardwareConcurrency', () => intOr(profile.hardwareConcurrency, _nativeCores));
      }
      if (profile.deviceMemory && intOr(profile.deviceMemory, 0) !== _nativeMem) {
        def(WorkerNavigator.prototype, 'deviceMemory', () => intOr(profile.deviceMemory, _nativeMem));
      }
      // Identidade no worker, alinhada à main thread. Só sobrescreve props que JÁ existem em
      // WorkerNavigator (não criar prop inexistente = mentira). vendor/platform/languages/language.
      if (profile.vendor && ('vendor' in navigator) && navigator.vendor !== profile.vendor) {
        def(WorkerNavigator.prototype, 'vendor', () => profile.vendor);
      }
      if (profile.platform && ('platform' in navigator) && navigator.platform !== profile.platform) {
        def(WorkerNavigator.prototype, 'platform', () => profile.platform);
      }
      if (Array.isArray(profile.languages) && profile.languages.length) {
        const _langs = profile.languages.slice();
        const _nativeLangs = Array.from(navigator.languages || []);
        const _langsMatch = _langs.length === _nativeLangs.length
          && _langs.every((value, index) => value === _nativeLangs[index]);
        if ('languages' in navigator && !_langsMatch) {
          def(WorkerNavigator.prototype, 'languages', () => _langs.slice());
        }
        if ('language' in navigator && navigator.language !== _langs[0]) {
          def(WorkerNavigator.prototype, 'language', () => _langs[0]);
        }
      }
    }
  } catch {}

  // ═══ WORKER USER-AGENT + CLIENT HINTS ═══
  // O CDP nem sempre propaga Network.setUserAgentOverride ao WorkerNavigator. Só normaliza APIs
  // que já existem no realm; nunca cria userAgentData em versões/contexts que não a expõem.
  try {
    if (typeof WorkerNavigator !== 'undefined' && typeof navigator !== 'undefined'
        && profile.browserVersion && ('userAgent' in navigator)) {
      const major = String(profile.browserVersion).split('.')[0] || '133';
      const reducedVersion = major + '.0.0.0';
      const uaPlatform = String(profile.uaPlatform || '').toLowerCase();
      const android = !!profile.mobile || uaPlatform.includes('android');
      const userAgent = android
        ? `Mozilla/5.0 (Linux; Android ${String(profile.platformVersion || '13').split('.')[0]}; ${profile.model || 'SM-G991B'}) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/${reducedVersion} Mobile Safari/537.36`
        : uaPlatform.includes('windows')
          ? `Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/${reducedVersion} Safari/537.36`
          : uaPlatform.includes('mac')
            ? `Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/${reducedVersion} Safari/537.36`
            : `Mozilla/5.0 (X11; ${profile.bitness === '64' ? 'Linux x86_64' : 'Linux i686'}) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/${reducedVersion} Safari/537.36`;
      if (navigator.userAgent !== userAgent) {
        def(WorkerNavigator.prototype, 'userAgent', () => userAgent);
      }

      if (('userAgentData' in navigator) && navigator.userAgentData) {
        const uaDataProto = Object.getPrototypeOf(navigator.userAgentData);
        const brands = [
          { brand: 'Not(A:Brand', version: '99' },
          { brand: 'Google Chrome', version: major },
          { brand: 'Chromium', version: major }
        ];
        const fullVersion = String(profile.browserVersion).includes('.')
          ? String(profile.browserVersion) : String(profile.browserVersion) + '.0.6900.100';
        const fullVersionList = [
          { brand: 'Not(A:Brand', version: '99.0.0.0' },
          { brand: 'Google Chrome', version: fullVersion },
          { brand: 'Chromium', version: fullVersion }
        ];
        if ('brands' in navigator.userAgentData) def(uaDataProto, 'brands', () => brands.map(x => ({ ...x })));
        if ('mobile' in navigator.userAgentData) def(uaDataProto, 'mobile', () => !!profile.mobile);
        if ('platform' in navigator.userAgentData) def(uaDataProto, 'platform', () => profile.uaPlatform || '');
        if (typeof navigator.userAgentData.getHighEntropyValues === 'function') {
          const nativeHighEntropy = uaDataProto.getHighEntropyValues;
          if (typeof nativeHighEntropy === 'function') {
            uaDataProto.getHighEntropyValues = new Proxy(nativeHighEntropy, {
              apply(target, thisArg, args) {
                const hints = Array.isArray(args[0]) ? args[0] : [];
                return Promise.resolve(Reflect.apply(target, thisArg, args)).then(nativeValues => {
                  const values = Object.assign({}, nativeValues || {});
                  // Chromium pode devolver brands/mobile/platform mesmo quando não foram pedidos.
                  // Sobrescrever só `hints` deixava a marca GREASE nativa vazar no worker enquanto
                  // a página já expunha a lista do profile.
                  values.brands = brands.map(x => ({ ...x }));
                  values.mobile = !!profile.mobile;
                  values.platform = profile.uaPlatform || '';
                  const available = {
                    architecture: android ? '' : (profile.architecture || ''),
                    bitness: android ? '' : (profile.bitness || ''),
                    model: profile.model || '',
                    platform: profile.uaPlatform || '',
                    platformVersion: profile.platformVersion || '',
                    uaFullVersion: fullVersion,
                    fullVersionList,
                    formFactors: Array.isArray(profile.formFactors) && profile.formFactors.length
                      ? profile.formFactors.map(value => String(value))
                      : [profile.mobile ? 'Mobile' : 'Desktop'],
                    wow64: !!profile.wow64
                  };
                  for (const hint of hints) if (Object.prototype.hasOwnProperty.call(available, hint)) values[hint] = available[hint];
                  return values;
                });
              }
            });
          }
        }
      }
    }
  } catch {}

  // ═══ WEBGL SPOOFING ═══ (espelha o realm de página; quando mode === 'spoof' ou 'spoof_noise')
  // BUG corrigido: o worker usa 'spoof_noise' e este realm só tratava 'spoof' → em OffscreenCanvas/
  // Worker a GPU NÃO era spoofada (vazava llvmpipe do datacenter, IDÊNTICO entre drivers).
  try {
    const webglProfile = profile.webgl || {};
    if ((webglProfile.mode === 'spoof' || webglProfile.mode === 'spoof_noise') && webglProfile.vendor && webglProfile.renderer) {
      const webglVendor = webglProfile.vendor;
      const webglRenderer = webglProfile.renderer;
      const UNMASKED_VENDOR_WEBGL = 0x9245;
      const UNMASKED_RENDERER_WEBGL = 0x9246;
      const patchWebglProto = (proto) => {
        if (!proto || !proto.getParameter) return;
        const getParamOrig = proto.getParameter;
        proto.getParameter = function getParameter(pname) {
          if (pname === UNMASKED_VENDOR_WEBGL) return webglVendor;
          if (pname === UNMASKED_RENDERER_WEBGL) return webglRenderer;
          return getParamOrig.call(this, pname);
        };
      };
      if (typeof WebGLRenderingContext !== 'undefined') patchWebglProto(WebGLRenderingContext.prototype);
      if (typeof WebGL2RenderingContext !== 'undefined') patchWebglProto(WebGL2RenderingContext.prototype);
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

  // ═══ WEBGL PIXEL NOISE ═══
  // Mesma derivação da página: seed do perfil + hash dos pixels limpos. Assim readPixels é estável
  // no mesmo perfil, diferente entre contas e coerente entre page/worker/OffscreenCanvas.
  try {
    const webglProfile = profile.webgl || {};
    if (webglProfile.mode === 'noise' || webglProfile.mode === 'spoof_noise') {
      const _baseSeed = (() => {
        if (Number.isFinite(profile.noiseSeed)) return profile.noiseSeed | 0;
        const n = (profile.canvas || {}).noise;
        if (n && typeof n === 'object' && Number.isFinite(n.seed)) return n.seed | 0;
        return 1337;
      })();
      const _webglSeed = (_baseSeed ^ 0x3C6EF35F) >>> 0;
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
        const nativeReadPixels = proto.readPixels;
        proto.readPixels = function(x, y, width, height, format, type, pixels) {
          const result = nativeReadPixels.apply(this, arguments);
          try {
            if (pixels && (pixels instanceof Uint8Array || pixels instanceof Uint8ClampedArray)) {
              _noiseGlPixels(pixels);
            }
          } catch {}
          return result;
        };
      };
      _patchReadPixels(typeof WebGLRenderingContext !== 'undefined' ? WebGLRenderingContext.prototype : null);
      _patchReadPixels(typeof WebGL2RenderingContext !== 'undefined' ? WebGL2RenderingContext.prototype : null);
    }
  } catch {}

  // ═══ SEEDED LCG — mesma derivação de seed do realm de página (consistência) ═══
  const _canvasSeed = (() => {
    if (Number.isFinite(profile.noiseSeed)) return profile.noiseSeed | 0;
    const n = (profile.canvas || {}).noise;
    if (n && typeof n === 'object' && Number.isFinite(n.seed)) return n.seed | 0;
    return 1337;
  })();
  const _audioSeed = _canvasSeed ^ 0x5A3C9E2B;

  // ═══ CANVAS NOISE no worker — mesma perturbação de desenho da página. Readback e
  // convertToBlob permanecem nativos para não expor wrappers de serialização. ═══
  try {
    const canvasProfile = profile.canvas || {};
    const _cn = canvasProfile.noise;
    const _cnIsObj = _cn && typeof _cn === 'object';
    const _cnActive = canvasProfile.mode === 'noise'
      && (_cnIsObj ? Number.isFinite(_cn.amplitude) : numberOr(_cn, 0) > 0);
    if (_cnActive && typeof OffscreenCanvasRenderingContext2D !== 'undefined') {
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
      const proto = OffscreenCanvasRenderingContext2D.prototype;
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
      const nativeGetTransform = proto.getTransform;
      const hasPixelAlignedTransform = context => {
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
      const isSafeIntegerFillRect = (context, args) => {
        if (!hasOnlyIntegerRectCoordinates(args) || clippedContexts.has(context)
""";

    private SeleniumProxyWorkerSurfaceScriptPartOne() {
    }
}
