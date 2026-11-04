package io.github.selenium.javaDriverless.scripts;

final class SeleniumProxyWebRtcSurfaceScripts {
    static final String SCRIPT = """
  // ═══ WEBRTC PROTECTION ═══ (CRITICAL for proxy — prevents real IP leak)
  try {
    const webRtcProfile = profile.webRtc || {};
    // Quando disableUdp=true, o processo Chrome ja recebe a politica nativa
    // disable_non_proxied_udp. Nao substituir o construtor JS: alem de redundante, o Proxy do
    // construtor e uma assinatura detectavel por verificadores como o Iphey.
    if (webRtcProfile.mode && webRtcProfile.mode !== 'real' && !webRtcProfile.disableUdp) {
      const _origRTC = window.RTCPeerConnection;
      const _origRTCWebkit = window.webkitRTCPeerConnection;
      const patchRTC = (OrigRTC) => {
        if (!OrigRTC) return null;
        const replacementIp = webRtcProfile.publicIp || '';
        const localIp = (ip) => {
          const parts = String(ip || '').split('.').map(Number);
          if (parts.length !== 4 || parts.some(n => !Number.isInteger(n) || n < 0 || n > 255)) return false;
          return parts[0] === 0 || parts[0] === 10 || parts[0] === 127 ||
            (parts[0] === 100 && parts[1] >= 64 && parts[1] <= 127) ||
            (parts[0] === 169 && parts[1] === 254) ||
            (parts[0] === 172 && parts[1] >= 16 && parts[1] <= 31) ||
            (parts[0] === 192 && parts[1] === 168);
        };
        // IPv4 dotted-quad OU literal IPv6. Aplicado SÓ em linhas de SDP que carregam IP real do
        // host (c=, o=, a=candidate host/srflx) — NUNCA em a=fingerprint/ice-ufrag/ice-pwd/etc, cujos
        // bytes hex separados por ':' pareceriam IPv6 e seriam corrompidos (quebraria o handshake DTLS).
        const IP_RE = /\\b(?:\\d{1,3}\\.){3}\\d{1,3}\\b|\\b(?:[0-9a-fA-F]{0,4}:){2,7}[0-9a-fA-F]{0,4}\\b/g;
        const ipsIn = (text) => String(text || '').match(IP_RE) || [];
        const candidateType = (cand) => {
          const m = /\\btyp\\s+(\\w+)/.exec(cand || '');
          return m ? m[1] : '';
        };
        // Substitui o IP real pelo IP do proxy (publicIp) ou por um valor neutro quando não há
        // substituto coerente. Mascara host/srflx tanto IPv4 quanto IPv6 (não só faixas privadas).
        const ipReplacer = (ip) => ip.indexOf(':') >= 0 ? (replacementIp || '::') : (replacementIp || '0.0.0.0');
        // Sanitiza o SDP LINHA A LINHA. Toca apenas c=/o= e candidatos host/srflx/prflx; candidatos
        // 'relay' carregam o IP do servidor TURN (seguro) e NÃO são tocados — sanitizá-los quebraria
        // o WebRTC legítimo. Todas as outras linhas (a=fingerprint, a=ice-*, etc.) ficam intactas.
        const sanitizeText = (text) => String(text || '').replace(/[^\\r\\n]+/g, (line) => {
          if (/^a=candidate/i.test(line)) {
            return candidateType(line) === 'relay' ? line : line.replace(IP_RE, ipReplacer);
          }
          if (/^(c=|o=)/i.test(line)) {
            return line.replace(IP_RE, ipReplacer);
          }
          return line;
        });
        const sanitizeDesc = (desc) => {
          if (desc && desc.sdp) {
            return { type: desc.type, sdp: sanitizeText(desc.sdp) };
          }
          return desc;
        };
        // Suprime mDNS (.local) e qualquer candidato host/srflx/prflx cujo IP (v4 ou v6) não seja o
        // publicIp esperado. Candidatos relay (IP do TURN) são seguros de expor.
        const suppressCandidate = (event) => {
          const candidate = event && event.candidate && event.candidate.candidate;
          if (!candidate) return false;
          if (/\\.local\\b/i.test(candidate)) return true;
          if (candidateType(candidate) === 'relay') return false;
          const ips = ipsIn(candidate);
          if (!ips.length) return false;
          if (replacementIp) return !ips.every((ip) => ip === replacementIp);
          return true;
        };
        const listenerMap = new WeakMap();
        const wrapListener = (listener) => {
          if (typeof listener !== 'function') return listener;
          if (listenerMap.has(listener)) return listenerMap.get(listener);
          const wrapped = function(event) {
            if (!suppressCandidate(event)) listener.call(this, event);
          };
          listenerMap.set(listener, wrapped);
          return wrapped;
        };
        // Proxy sobre o construtor NATIVO: herda name/length/toString nativos automaticamente —
        // RTCPeerConnection.toString() devolve "function RTCPeerConnection() { [native code] }" e
        // .length === 0 (sem o tell de uma função JS distinta). O construct trap clona o config (não
        // muta o objeto do caller) e força relay-only quando disableUdp.
        const handler = {
          construct(Target, args) {
            let config = args[0];
            const constraints = args[1];
            if (webRtcProfile.disableUdp) {
              config = Object.assign({}, config || {});
              config.iceTransportPolicy = 'relay';
            }
            const pc = new Target(config, constraints);
            if (!webRtcProfile.protectLocalIps) return pc;
            // PROXY SOBRE A INSTANCIA (nao redefinir own-property nos metodos): Chrome nativo
            // expoe setLocalDescription/createOffer/onicecandidate no PROTOTYPE, NUNCA como
            // own-prop da instancia. Redefini-los na instancia (codigo antigo) criava a anomalia
            // detectavel `pc.hasOwnProperty('setLocalDescription')===true`. Aqui o pc real fica
            // intacto e o Proxy intercepta so o IP-mask. getOwnPropertyNames(proxy) reflete o pc
            // nativo (sem own-prop). Metodos nao-mascarados saem bound ao pc (toString nativo +
            // identidade estavel via cache).
            const _oicMap = new WeakMap();
            const _boundCache = new Map();
            const _bound = (prop) => {
              if (!_boundCache.has(prop)) {
                const v = pc[prop];
                _boundCache.set(prop, typeof v === 'function' ? v.bind(pc) : v);
              }
              return _boundCache.get(prop);
            };
            const _maskCache = new Map();
            const _mask = (prop, fn) => {
              if (!_maskCache.has(prop)) _maskCache.set(prop, fn);
              return _maskCache.get(prop);
            };
            return new Proxy(pc, {
              get(t, prop) {
                switch (prop) {
                  case 'setLocalDescription':
                    return _mask(prop, function setLocalDescription(desc) { return t.setLocalDescription(sanitizeDesc(desc)); });
                  case 'createOffer':
                    return _mask(prop, function createOffer(...a) { return t.createOffer(...a).then(sanitizeDesc); });
                  case 'createAnswer':
                    return _mask(prop, function createAnswer(...a) { return t.createAnswer(...a).then(sanitizeDesc); });
                  case 'addEventListener':
                    return _mask(prop, function addEventListener(type, listener, options) {
                      return t.addEventListener(type, type === 'icecandidate' ? wrapListener(listener) : listener, options);
                    });
                  case 'removeEventListener':
                    return _mask(prop, function removeEventListener(type, listener, options) {
                      return t.removeEventListener(type, listenerMap.get(listener) || listener, options);
                    });
                  case 'onicecandidate':
                    return _oicMap.get(t) || null;
                  default: {
                    const v = t[prop];
                    return typeof v === 'function' ? _bound(prop) : v;
                  }
                }
              },
              set(t, prop, value) {
                if (prop === 'onicecandidate') {
                  const prev = _oicMap.get(t);
                  if (prev) { try { t.removeEventListener('icecandidate', listenerMap.get(prev) || prev); } catch {} }
                  _oicMap.set(t, value);
                  if (typeof value === 'function') t.addEventListener('icecandidate', wrapListener(value));
                  return true;
                }
                t[prop] = value;
                return true;
              }
            });
          }
        };
        return new Proxy(OrigRTC, handler);
      };
      const patched = patchRTC(_origRTC);
      if (patched) {
        Object.defineProperty(window, 'RTCPeerConnection', { value: patched, configurable: true, writable: true });
      }
      const patchedWebkit = patchRTC(_origRTCWebkit);
      if (patchedWebkit) {
        Object.defineProperty(window, 'webkitRTCPeerConnection', { value: patchedWebkit, configurable: true, writable: true });
      }
    }
  } catch {}

""";

    private SeleniumProxyWebRtcSurfaceScripts() {}
}
