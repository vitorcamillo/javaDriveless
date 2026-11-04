package io.github.selenium.javaDriverless.scripts;

final class SeleniumProxyMediaSurfaceScripts {
    static final String SCRIPT = """
  // ═══ MEDIA DEVICES OVERRIDE ═══ (only when mode === 'spoof')
  // Trunca por contagem E RELABELA deviceId/groupId/label de forma DETERMINÍSTICA por conta (hash do
  // seed do perfil). Sem isto, o spoof anterior só truncava e os deviceId/label REAIS do host vazavam
  // após permissão de mídia (ex.: "Starship/Matisse HD Audio Controller" AMD, "HDA NVidia ... HDMI").
  try {
    const mediaProfile = profile.mediaDevices || {};
    if (mediaProfile.mode === 'spoof' && navigator.mediaDevices && navigator.mediaDevices.enumerateDevices) {
      const _mdSeed = ((typeof _canvasSeed !== 'undefined' ? _canvasSeed : 1337) ^ 0x4D454449) >>> 0;
      const _mdOrigin = (() => {
        try {
          if (location.origin && location.origin !== 'null') return location.origin;
          return new URL('.', location.href).href;
        } catch { return 'opaque-origin'; }
      })();
      // deviceId no formato do Chrome: 64 chars hex minúsculos, determinístico por
      // (perfil, origem, kind, índice). Chrome particiona IDs por origem; omitir a origem permitia
      // correlacionar a mesma conta entre sites distintos.
      const _mdHash = (s) => {
        let out = ''; let x = (_mdSeed ^ 0x9E3779B9) >>> 0; const str = String(s);
        for (let k = 0; k < 8; k++) {
          let v = x;
          for (let i = 0; i < str.length; i++) v = Math.imul(v ^ str.charCodeAt(i), 0x01000193) >>> 0;
          v = Math.imul(v ^ k, 0x85EBCA6B) >>> 0;
          out += ('00000000' + (v >>> 0).toString(16)).slice(-8);
          x = v;
        }
        return out;
      };
      const _mdLabel = (kind, idx) => {
        const base = { audioinput: 'Microphone', audiooutput: 'Speakers', videoinput: 'Camera' }[kind] || 'Device';
        return idx > 0 ? base + ' ' + (idx + 1) : base;
      };
      const _relabel = (d, idx) => {
        // Pré-permissão o Chrome já entrega deviceId/label vazios (exceto audiooutput 'default'/'communications').
        const keepId = d.deviceId === 'default' || d.deviceId === 'communications';
        const deviceId = d.deviceId === '' ? '' : (keepId ? d.deviceId : _mdHash(_mdOrigin + '|id|' + d.kind + '|' + idx));
        const groupId = d.groupId === '' ? '' : _mdHash(_mdOrigin + '|grp|' + d.kind + '|' + idx);
        const label = d.label === '' ? '' : _mdLabel(d.kind, idx);
        const nativeToJson = typeof d.toJSON === 'function' ? d.toJSON : null;
        const toJson = nativeToJson ? new Proxy(nativeToJson, {
          apply() { return { kind: d.kind, deviceId, groupId, label }; }
        }) : function toJSON() { return { kind: d.kind, deviceId, groupId, label }; };
        // O target continua sendo o MediaDeviceInfo nativo: prototype, instanceof, own-property
        // descriptors e @@toStringTag permanecem iguais. Só os getters observáveis são projetados.
        return new Proxy(d, {
          get(target, prop) {
            if (prop === 'kind') return d.kind;
            if (prop === 'deviceId') return deviceId;
            if (prop === 'groupId') return groupId;
            if (prop === 'label') return label;
            if (prop === 'toJSON') return toJson;
            const value = Reflect.get(target, prop, target);
            return typeof value === 'function' ? value.bind(target) : value;
          }
        });
      };
      const mediaDevicesProto = Object.getPrototypeOf(navigator.mediaDevices);
      const origEnumerate = mediaDevicesProto && mediaDevicesProto.enumerateDevices;
      if (typeof origEnumerate === 'function') {
        mediaDevicesProto.enumerateDevices = new Proxy(origEnumerate, {
          async apply(target, thisArg, args) {
            const devices = await Reflect.apply(target, thisArg, args);
            const result = [];
            let ai = 0, ao = 0, vi = 0;
            const maxAI = intOr(mediaProfile.audioInputs, 1);
            const maxAO = intOr(mediaProfile.audioOutputs, 1);
            const maxVI = intOr(mediaProfile.videoInputs, 0);
            for (const d of devices) {
              if (d.kind === 'audioinput' && ai < maxAI) { result.push(_relabel(d, ai)); ai++; }
              else if (d.kind === 'audiooutput' && ao < maxAO) { result.push(_relabel(d, ao)); ao++; }
              else if (d.kind === 'videoinput' && vi < maxVI) { result.push(_relabel(d, vi)); vi++; }
            }
            return result;
          }
        });
      }
    }
  } catch {}

  // ═══ DEVICE PIXEL RATIO ═══ (only when screen mode !== 'real' and DPR differs)
  try {
    if (screenProfile.mode && screenProfile.mode !== 'real' && screenProfile.deviceScaleFactor) {
      const targetDPR = numberOr(screenProfile.deviceScaleFactor, window.devicePixelRatio);
      if (targetDPR !== window.devicePixelRatio) {
        Object.defineProperty(window, 'devicePixelRatio', {
          get: () => targetDPR,
          configurable: true, enumerable: true
        });
      }
    }
  } catch {}

  // ═══ PORT SCANNING PROTECTION ═══ (blocks port probing via fetch/XHR)
  try {
    const portsProfile = profile.ports || {};
    if (portsProfile.mode === 'protect' && Array.isArray(portsProfile.protectedPorts) && portsProfile.protectedPorts.length > 0) {
      const blockedPorts = new Set(portsProfile.protectedPorts);
      const extractPort = (url) => {
        try {
          const u = new URL(url, location.href);
          if (u.hostname === 'localhost' || u.hostname === '127.0.0.1' || u.hostname === '0.0.0.0') {
            return parseInt(u.port) || (u.protocol === 'https:' ? 443 : 80);
          }
        } catch {}
        return 0;
      };
      const blockedXhrs = new WeakSet();
      const origFetch = window.fetch;
      window.fetch = new Proxy(origFetch, { apply(target, thisArg, args) {
        const input = args[0];
        const url = typeof input === 'string' ? input : (input && input.url ? input.url : '');
        const port = extractPort(url);
        if (port && blockedPorts.has(port)) {
          return Promise.reject(new TypeError('Failed to fetch'));
        }
        return Reflect.apply(target, thisArg, args);
      }});
      const origXhrOpen = XMLHttpRequest.prototype.open;
      XMLHttpRequest.prototype.open = new Proxy(origXhrOpen, { apply(target, thisArg, args) {
        const url = args[1];
        const port = extractPort(url);
        if (port && blockedPorts.has(port)) {
          blockedXhrs.add(thisArg);
        }
        return Reflect.apply(target, thisArg, args);
      }});
      const origXhrSend = XMLHttpRequest.prototype.send;
      XMLHttpRequest.prototype.send = new Proxy(origXhrSend, { apply(target, thisArg, args) {
        if (blockedXhrs.has(thisArg)) {
          blockedXhrs.delete(thisArg);
          setTimeout(() => {
            thisArg.dispatchEvent(new Event('error'));
          }, 1);
          return;
        }
        return Reflect.apply(target, thisArg, args);
      }});
    }
  } catch {}

  // ═══ FONT ENUMERATION PROTECTION ═══ (queryLocalFonts)
  try {
    const fontsProfile = profile.fonts || {};
    if (fontsProfile.mode && fontsProfile.mode !== 'real' && 'queryLocalFonts' in window) {
      const origQueryFonts = window.queryLocalFonts;
      window.queryLocalFonts = async function() {
        if (fontsProfile.mode === 'block') return [];
        let fonts = await origQueryFonts.call(window);
        if (Array.isArray(fontsProfile.families) && fontsProfile.families.length > 0) {
          const allowed = new Set(fontsProfile.families.map(f => f.toLowerCase()));
          fonts = fonts.filter(f => allowed.has((f.family || '').toLowerCase()));
        }
        if (Array.isArray(fontsProfile.hidden) && fontsProfile.hidden.length > 0) {
          const blocked = new Set(fontsProfile.hidden.map(f => f.toLowerCase()));
          fonts = fonts.filter(f => !blocked.has((f.family || '').toLowerCase()));
        }
        return fonts;
      };
    }
  } catch {}

  // ═══ FONT SUBSET PER ACCOUNT ═══ (mode === 'subset') — esconde fatia seedada das OPCIONAIS.
  // Coerência entre os 3 oráculos (DOM offsetWidth / canvas measureText / document.fonts.check):
  // a família escondida é removida ANTES da medição → cai em fallback real, sem geometria forjada.
  // Tudo via Proxy sobre função nativa → measureText.toString() segue "[native code]" (sem tell).
  try {
    const _fp = profile.fonts || {};
    if (_fp.mode === 'subset' && Array.isArray(_fp.hidden) && _fp.hidden.length) {
      const _CORE = new Set(['dejavu sans','dejavu serif','dejavu sans mono',
        'liberation sans','liberation serif','liberation mono','noto color emoji',
        'sans-serif','serif','monospace','cursive','fantasy','system-ui']);
      const H = new Set(_fp.hidden.map(f => String(f).toLowerCase()).filter(f => !_CORE.has(f)));
      if (H.size) {
        const _norm = t => String(t).trim().replace(/^['"]|['"]$/g, '').toLowerCase();
        const _stripFam = v => {
          const parts = String(v).split(',').map(x => x.trim()).filter(x => !H.has(_norm(x)));
          return parts.length ? parts.join(', ') : 'sans-serif';
        };
        const _famOf = font => { const m = String(font).match(/^.*?(?:px|pt|em|rem|%|ex|ch|vh|vw)\\s+(.*)$/i); return m ? m[1] : ''; };
        const _hasHidden = font => _famOf(font).split(',').some(t => H.has(_norm(t)));
        const _stripFont = font => {
          const m = String(font).match(/^(.*?(?:px|pt|em|rem|%|ex|ch|vh|vw)\\s+)(.*)$/i);
          if (!m) return String(font);
          const fams = m[2].split(',').map(x => x.trim()).filter(x => !H.has(_norm(x)));
          return m[1] + (fams.length ? fams.join(', ') : 'sans-serif');
        };
        // (1) DOM: style.fontFamily com família escondida → fallback real → offsetWidth/rect/getComputedStyle concordam.
        try {
          const SP = (typeof CSS2Properties !== 'undefined' ? CSS2Properties : CSSStyleDeclaration).prototype;
          const dFF = Object.getOwnPropertyDescriptor(SP, 'fontFamily');
          if (dFF && dFF.set) {
            const _pset = new Proxy(dFF.set, { apply(t, th, a) { try { a[0] = _stripFam(a[0]); } catch (e) {} return Reflect.apply(t, th, a); } });
            Object.defineProperty(SP, 'fontFamily', { get: dFF.get, set: _pset, configurable: true, enumerable: true });
          }
        } catch (e) {}
        // (2) Canvas measureText: mede com a família escondida removida e restaura ctx.font.
        const _wrapMT = P => {
          if (!P || !P.measureText) return;
          P.measureText = new Proxy(P.measureText, { apply(t, th, a) {
            try { if (_hasHidden(th.font)) { const prev = th.font; th.font = _stripFont(prev); const r = Reflect.apply(t, th, a); th.font = prev; return r; } } catch (e) {}
            return Reflect.apply(t, th, a);
          }});
        };
        _wrapMT(typeof CanvasRenderingContext2D !== 'undefined' ? CanvasRenderingContext2D.prototype : null);
        _wrapMT(typeof OffscreenCanvasRenderingContext2D !== 'undefined' ? OffscreenCanvasRenderingContext2D.prototype : null);
        // (3) document.fonts.check → false p/ escondida (coerente com (1) e (2)).
        try {
          if (typeof FontFaceSet !== 'undefined' && FontFaceSet.prototype.check) {
            FontFaceSet.prototype.check = new Proxy(FontFaceSet.prototype.check, { apply(t, th, a) {
              try { if (_hasHidden(a[0])) return false; } catch (e) {} return Reflect.apply(t, th, a);
            }});
          }
        } catch (e) {}
      }
    }
  } catch {}

  // Estado interno fica em CLOSURE (const _storage, no bootstrap) e nenhum guard ou marcador é
  // publicado em window/globalThis. Nada adicional aparece nas APIs de enumeração do realm.
})();
""";

    private SeleniumProxyMediaSurfaceScripts() {}
}
