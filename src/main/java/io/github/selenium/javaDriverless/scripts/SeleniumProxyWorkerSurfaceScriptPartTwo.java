package io.github.selenium.javaDriverless.scripts;

final class SeleniumProxyWorkerSurfaceScriptPartTwo {
    static final String VALUE = """
            || !hasPixelAlignedTransform(context)) return false;
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
      const patchCoordinatePairs = (name, pairs, shouldShift = () => true) => {
        if (typeof proto[name] !== 'function') return;
        const nativeDraw = proto[name];
        proto[name] = new Proxy(nativeDraw, {
          apply(target, thisArg, args) {
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
      const patchDrawImage = () => {
        if (typeof proto.drawImage !== 'function') return;
        const nativeDraw = proto.drawImage;
        proto.drawImage = new Proxy(nativeDraw, {
          apply(target, thisArg, args) {
            const shifted = Array.from(args);
            const pair = shifted.length >= 9 ? [5, 6] : [1, 2];
            const offsets = offsetsFor('drawImage', shifted);
            if (Number.isFinite(Number(shifted[pair[0]]))) shifted[pair[0]] = Number(shifted[pair[0]]) + offsets[0];
            if (Number.isFinite(Number(shifted[pair[1]]))) shifted[pair[1]] = Number(shifted[pair[1]]) + offsets[1];
            return Reflect.apply(target, thisArg, shifted);
          }
        });
      };
      const patchPathDraw = name => {
        if (typeof proto[name] !== 'function' || typeof Path2D === 'undefined') return;
        const nativeDraw = proto[name];
        const nativeSave = proto.save;
        const nativeRestore = proto.restore;
        const nativeTranslate = proto.translate;
        if (typeof nativeSave !== 'function' || typeof nativeRestore !== 'function'
            || typeof nativeTranslate !== 'function') return;
        proto[name] = new Proxy(nativeDraw, {
          apply(target, thisArg, args) {
            if (!(args[0] instanceof Path2D)) return Reflect.apply(target, thisArg, args);
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
      const patchClip = () => {
        if (typeof proto.clip !== 'function') return;
        const nativeClip = proto.clip;
        const nativeTranslate = proto.translate;
        proto.clip = new Proxy(nativeClip, {
          apply(target, thisArg, args) {
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
      patchCoordinatePairs('fillText', [[1, 2]]);
      patchCoordinatePairs('strokeText', [[1, 2]]);
      patchCoordinatePairs('fillRect', [[0, 1]],
        (args, context) => !isSafeIntegerFillRect(context, args));
      patchCoordinatePairs('strokeRect', [[0, 1]]);
      patchCoordinatePairs('moveTo', [[0, 1]]);
      patchCoordinatePairs('lineTo', [[0, 1]]);
      patchCoordinatePairs('bezierCurveTo', [[0, 1], [2, 3], [4, 5]]);
      patchCoordinatePairs('quadraticCurveTo', [[0, 1], [2, 3]]);
      patchCoordinatePairs('arc', [[0, 1]]);
      patchCoordinatePairs('arcTo', [[0, 1], [2, 3]]);
      patchCoordinatePairs('ellipse', [[0, 1]]);
      patchCoordinatePairs('rect', [[0, 1]]);
      patchCoordinatePairs('roundRect', [[0, 1]]);
      patchCoordinatePairs('createLinearGradient', [[0, 1], [2, 3]]);
      patchCoordinatePairs('createRadialGradient', [[0, 1], [3, 4]]);
      patchCoordinatePairs('createConicGradient', [[1, 2]]);
      patchDrawImage();
      patchPathDraw('fill');
      patchPathDraw('stroke');
      patchClip();
    }
  } catch {}

  // ═══ AUDIO NOISE no worker (AudioBuffer via OfflineAudioContext) ═══
  try {
    const audioProfile = profile.audio || {};
    if (audioProfile.mode === 'noise' && numberOr(audioProfile.noise, 0) > 0
        && typeof AudioBuffer !== 'undefined' && AudioBuffer.prototype.getChannelData) {
      const aNoise = numberOr(audioProfile.noise, 0.002);
      const _seedFloats = (arr) => {
        let h = _audioSeed >>> 0;
        const step = Math.max(1, (arr.length / 1024) | 0);
        for (let i = 0; i < arr.length; i += step) {
          h = Math.imul(h ^ ((arr[i] * 1e6) | 0), 0x01000193) >>> 0;
        }
        return (h ^ arr.length) >>> 0;
      };
      const _noiseFloats = (arr) => {
        let s = _seedFloats(arr);
        const lcg = () => { s = (Math.imul(1664525, s) + 1013904223) >>> 0; return s / 4294967296; };
        for (let i = 0; i < arr.length; i++) arr[i] += (lcg() * 2 - 1) * aNoise;
      };
      const _noiseBytes = (array) => {
        let h = _audioSeed >>> 0;
        const step = Math.max(1, (array.length / 1024) | 0);
        for (let i = 0; i < array.length; i += step) h = Math.imul(h ^ array[i], 0x01000193) >>> 0;
        let s = (h ^ array.length) >>> 0;
        const lcg = () => { s = (Math.imul(1664525, s) + 1013904223) >>> 0; return s / 4294967296; };
        for (let i = 0; i < array.length; i++) {
          array[i] = Math.max(0, Math.min(255, array[i] + Math.round((lcg() * 2 - 1) * aNoise * 255)));
        }
      };
      const _noisedChannels = new WeakMap();
      const _channelWasNoised = (buffer, channel) => {
        const channels = _noisedChannels.get(buffer);
        return !!channels && channels.has(channel);
      };
      const _markChannelNoised = (buffer, channel) => {
        let channels = _noisedChannels.get(buffer);
        if (!channels) { channels = new Set(); _noisedChannels.set(buffer, channels); }
        channels.add(channel);
      };
      const origGetChannelData = AudioBuffer.prototype.getChannelData;
      AudioBuffer.prototype.getChannelData = function(channel) {
        const data = origGetChannelData.call(this, channel);
        if (!_channelWasNoised(this, channel)) {
          _noiseFloats(data);
          _markChannelNoised(this, channel);
        }
        return data;
      };
      if (AudioBuffer.prototype.copyFromChannel) {
        const origCopyFromChannel = AudioBuffer.prototype.copyFromChannel;
        AudioBuffer.prototype.copyFromChannel = function(destination, channelNumber, bufferOffset) {
          origCopyFromChannel.call(this, destination, channelNumber, bufferOffset);
          // Se getChannelData já perturbou a Float32Array viva, o copy nativo já trouxe o ruído.
          if (!_channelWasNoised(this, channelNumber)) _noiseFloats(destination);
        };
      }
      if (typeof AnalyserNode !== 'undefined' && AnalyserNode.prototype) {
        const origFFD = AnalyserNode.prototype.getFloatFrequencyData;
        if (origFFD) AnalyserNode.prototype.getFloatFrequencyData = function(array) {
          origFFD.call(this, array); _noiseFloats(array);
        };
        const origBFD = AnalyserNode.prototype.getByteFrequencyData;
        if (origBFD) AnalyserNode.prototype.getByteFrequencyData = function(array) {
          origBFD.call(this, array); _noiseBytes(array);
        };
        const origFTD = AnalyserNode.prototype.getFloatTimeDomainData;
        if (origFTD) AnalyserNode.prototype.getFloatTimeDomainData = function(array) {
          origFTD.call(this, array); _noiseFloats(array);
        };
      }
    }
  } catch {}

  // ═══ WEBRTC no worker (DedicatedWorkerGlobalScope pode expor RTCPeerConnection) ═══
  // Relay-only quando disableUdp (default fail-closed): impede que o worker colete srflx/host via
  // UDP e vaze o IP real fora do alcance do patch de página. Reforça a flag de engine
  // --webrtc-ip-handling-policy (que cobre o processo do worker quando disableUdp=true).
  try {
    const webRtcProfile = profile.webRtc || {};
    if (webRtcProfile.mode && webRtcProfile.mode !== 'real' && webRtcProfile.disableUdp &&
        typeof RTCPeerConnection === 'function') {
      const OrigRTC = RTCPeerConnection;
      const PatchedRTC = new Proxy(OrigRTC, {
        construct(Target, args) {
          const cfg = Object.assign({}, args[0] || {});
          cfg.iceTransportPolicy = 'relay';
          return new Target(cfg, args[1]);
        }
      });
      Object.defineProperty(self, 'RTCPeerConnection', { value: PatchedRTC, configurable: true, writable: true });
    }
  } catch {}

  // ═══ CONNECTION no worker ═══
  try {
    const connectionProfile = profile.connection || {};
    if (connectionProfile.mode === 'spoof' && typeof navigator !== 'undefined'
        && ('connection' in navigator) && navigator.connection) {
      const connectionProto = Object.getPrototypeOf(navigator.connection);
      const downlink = numberOr(connectionProfile.downlink, 10);
      const rtt = intOr(connectionProfile.rtt, 50);
      const effectiveType = (downlink >= 10 && rtt <= 60) ? '4g'
        : (downlink >= 1.5 && rtt <= 280) ? '3g'
          : downlink >= 0.4 ? '2g' : 'slow-2g';
      if ('effectiveType' in navigator.connection) def(connectionProto, 'effectiveType', () => effectiveType);
      if ('downlink' in navigator.connection) def(connectionProto, 'downlink', () => downlink);
      if ('rtt' in navigator.connection) def(connectionProto, 'rtt', () => rtt);
      if ('saveData' in navigator.connection) def(connectionProto, 'saveData', () => !!connectionProfile.saveData);
      if (connectionProfile.type && ('type' in navigator.connection)) def(connectionProto, 'type', () => connectionProfile.type);
    }
  } catch {}

  // ═══ STORAGE QUOTA no worker ═══
  try {
    const storageProfile = profile.storage || {};
    if (storageProfile.mode === 'spoof'
        && typeof navigator !== 'undefined' && ('storage' in navigator) && navigator.storage
        && typeof navigator.storage.estimate === 'function') {
      const storageProto = Object.getPrototypeOf(navigator.storage);
      const nativeEstimate = storageProto.estimate;
      const storageSeed = Number.isFinite(profile.noiseSeed) ? (profile.noiseSeed >>> 0) : 0;
      const quota = storageSeed
        ? ((96 + (storageSeed % 384)) * 1024 * 1024 * 1024) - ((storageSeed % 7919) * 1024 * 1024)
        : 120 * 1024 * 1024 * 1024;
      const usage = storageSeed
        ? Math.floor(quota * (0.003 + ((storageSeed >>> 9) % 110) / 10000))
        : 32 * 1024 * 1024;
      storageProto.estimate = new Proxy(nativeEstimate, {
        apply() { return Promise.resolve({ quota, usage, usageDetails: {} }); }
      });
    }
  } catch {}

  // ═══ TIMEZONE no worker (Intl + Date.getTimezoneOffset) ═══
  // O CDP Emulation.setTimezoneOverride da página NÃO propaga ao worker. Fechamos os 2 sinais
  // primários (Intl.DateTimeFormat().resolvedOptions().timeZone e Date.getTimezoneOffset()) de forma
  // coerente, com fallback nativo em qualquer erro (nunca quebra aritmética de datas do site).
  try {
    const _tz = profile.timezoneId;
    const _workerLocales = Array.isArray(profile.languages) && profile.languages.length
      ? profile.languages.slice() : (profile.locale ? [profile.locale] : undefined);
    if (typeof Intl !== 'undefined') {
      // Locale default de todos os construtores Intl disponíveis. Proxy preserva name/length,
      // prototype e Function#toString do construtor nativo.
      for (const name of ['NumberFormat', 'Collator', 'PluralRules', 'RelativeTimeFormat',
        'ListFormat', 'DisplayNames', 'Segmenter']) {
        const NativeIntl = Intl[name];
        if (typeof NativeIntl !== 'function' || !_workerLocales) continue;
        Intl[name] = new Proxy(NativeIntl, {
          apply(target, thisArg, args) {
            const next = Array.from(args || []);
            if (next[0] == null) next[0] = _workerLocales;
            return Reflect.apply(target, thisArg, next);
          },
          construct(target, args, newTarget) {
            const next = Array.from(args || []);
            if (next[0] == null) next[0] = _workerLocales;
            return Reflect.construct(target, next, newTarget);
          }
        });
      }
    }
    if ((_tz || _workerLocales) && typeof Intl !== 'undefined' && typeof Intl.DateTimeFormat === 'function') {
      const _OrigDTF = Intl.DateTimeFormat;
      const _dtfArgs = args => {
        const next = Array.from(args || []);
        if (next[0] == null && _workerLocales) next[0] = _workerLocales;
        const opts = Object.assign({}, next[1]);
        if (_tz && !opts.timeZone) opts.timeZone = _tz;
        next[1] = opts;
        return next;
      };
      const _PatchedDTF = new Proxy(_OrigDTF, {
        apply(target, thisArg, args) { return Reflect.apply(target, thisArg, _dtfArgs(args)); },
        construct(target, args, newTarget) { return Reflect.construct(target, _dtfArgs(args), newTarget); }
      });
      Intl.DateTimeFormat = _PatchedDTF;
      const _offsetForTz = (date) => {
        const dtf = new _OrigDTF('en-US', { timeZone: _tz, hour12: false,
          year: 'numeric', month: '2-digit', day: '2-digit',
          hour: '2-digit', minute: '2-digit', second: '2-digit' });
        const p = {};
        dtf.formatToParts(date).forEach((x) => { p[x.type] = x.value; });
        const hour = p.hour === '24' ? '00' : p.hour;
        const asUTC = Date.UTC(+p.year, +p.month - 1, +p.day, +hour, +p.minute, +p.second);
        return -Math.round((asUTC - date.getTime()) / 60000);
      };
      if (_tz) {
        const _origGetTZOffset = Date.prototype.getTimezoneOffset;
        Date.prototype.getTimezoneOffset = function() {
          try { return _offsetForTz(this); } catch (e) { return _origGetTZOffset.call(this); }
        };
      }
    }
  } catch {}
})();
""";

    private SeleniumProxyWorkerSurfaceScriptPartTwo() {
    }
}
