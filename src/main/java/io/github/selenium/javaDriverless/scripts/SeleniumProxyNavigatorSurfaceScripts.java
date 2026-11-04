package io.github.selenium.javaDriverless.scripts;

final class SeleniumProxyNavigatorSurfaceScripts {
    static final String SCRIPT = """
  // ═══ NAVIGATOR OVERRIDES ═══
  // CDP handles: userAgent, platform, acceptLanguage via Network.setUserAgentOverride
  // CDP handles: locale via Emulation.setLocaleOverride
  // JS needed for: vendor, hardwareConcurrency, deviceMemory, maxTouchPoints, doNotTrack, languages
  try {
    const _nativeVendor = navigator.vendor;
    const _nativeCores = navigator.hardwareConcurrency;
    const _nativeMemory = navigator.deviceMemory;
    const _nativeTouch = navigator.maxTouchPoints;
    if (profile.vendor && profile.vendor !== _nativeVendor) {
      define(Navigator.prototype, 'vendor', () => profile.vendor);
    }
    if (profile.hardwareConcurrency && intOr(profile.hardwareConcurrency, 0) !== _nativeCores) {
      define(Navigator.prototype, 'hardwareConcurrency', () => intOr(profile.hardwareConcurrency, _nativeCores));
    }
    if (profile.deviceMemory && intOr(profile.deviceMemory, 0) !== _nativeMemory) {
      define(Navigator.prototype, 'deviceMemory', () => intOr(profile.deviceMemory, _nativeMemory));
    }
    if (profile.maxTouchPoints != null && intOr(profile.maxTouchPoints, 0) !== _nativeTouch) {
      define(Navigator.prototype, 'maxTouchPoints', () => intOr(profile.maxTouchPoints, 0));
    }
    if (profile.doNotTrack) {
      define(Navigator.prototype, 'doNotTrack', () => doNotTrack);
    }
    // Mantém main e worker coerentes com a lista limpa do perfil. Network.setUserAgentOverride
    // também recebe tags limpas; o próprio Chromium adiciona q-values somente ao header HTTP.
    const _nativeLanguages = Array.from(navigator.languages || []);
    const _languagesMatch = languages.length === _nativeLanguages.length
      && languages.every((value, index) => value === _nativeLanguages[index]);
    if (languages.length && !_languagesMatch) {
      define(Navigator.prototype, 'languages', () => languages.slice());
    }
    if (firstLanguage && firstLanguage !== navigator.language) {
      define(Navigator.prototype, 'language', () => firstLanguage);
    }
  } catch {}

  // ═══ WEBDRIVER ═══
  // Defense-in-depth (a flag --disable-blink-features=AutomationControlled pode falhar em
  // versões futuras). Chrome real expõe navigator.webdriver === false; automação deixa true.
  // Só redefine se a flag não tiver zerado — assim o caminho limpo mantém o getter nativo.
  try {
    if (navigator.webdriver !== false) {
      define(Navigator.prototype, 'webdriver', () => false);
    }
  } catch {}

  // ═══ NOTIFICATION/PERMISSIONS ═══
  // Estado de permissão é funcional, persistente e por origem. JS não consegue conceder/revogar a
  // decisão real com segurança; falsificar somente getters faria Notification, Permissions.query e
  // o ato de criar uma notificação divergirem. Portanto essas APIs permanecem integralmente nativas.

  // ═══ SPEECH VOICES CONSISTENCY ═══
  // Native Linux/espeak often exposes a default voice in an unrelated locale.
  // Keep this profile-driven so PhantomPass can provide country-specific voices.
  try {
    if (window.speechSynthesis && speechProfile.mode !== 'real') {
      const installSpeechVoicesConsistency = () => {
        const buildVoice = (voice, index) => {
          const lang = voice.lang || firstLanguage;
          const name = voice.name || `${lang} Google voice`;
          const uri = voice.voiceURI || name;
          return Object.freeze({
            voiceURI: uri,
            name,
            lang,
            localService: voice.localService !== false,
            default: !!voice.isDefault || index === 0
          });
        };
        // Vozes POR CONTA (seed do perfil): uma lista fixa de 2 vozes e identica em toda
        // instancia (template). Aqui sempre as 2 da lingua do perfil + um subconjunto seedado
        // do pool (count e selecao variam por conta), mantendo coerencia com o idioma/OS.
        const _vSeed = (Number.isFinite(profile.noiseSeed) ? (profile.noiseSeed >>> 0) : 1337);
        const _voicePool = [
          { name: `${firstLanguage} Google`, lang: firstLanguage, isDefault: true },
          { name: `${firstLanguage.split('-')[0]} local`, lang: firstLanguage },
          { name: 'Google US English', lang: 'en-US' },
          { name: 'Google UK English Female', lang: 'en-GB' },
          { name: 'Google español', lang: 'es-ES' },
          { name: 'Google français', lang: 'fr-FR' },
          { name: 'Google Deutsch', lang: 'de-DE' },
          { name: 'Google italiano', lang: 'it-IT' }
        ];
        const configuredVoices = Array.isArray(speechProfile.voices) && speechProfile.voices.length
          ? speechProfile.voices.map(buildVoice)
          : (() => {
              // LCG sobre o seed inteiro (nao bits crus consecutivos, que colidem entre
              // seeds proximos): cada decisao usa entropia fresca dos 32 bits.
              let _vr = (_vSeed ^ 0x9E3779B9) >>> 0;
              const _vrand = () => { _vr = (Math.imul(1664525, _vr) + 1013904223) >>> 0; return _vr; };
              const picked = [_voicePool[0], _voicePool[1]];
              for (let i = 2; i < _voicePool.length; i++) {
                if ((_vrand() & 1) === 1) picked.push(_voicePool[i]);
              }
              return picked.map(buildVoice);
            })();
        const getVoices = function getVoices() {
          return configuredVoices.slice();
        };
        try {
          Object.defineProperty(window.speechSynthesis, 'getVoices', {
            value: getVoices,
            configurable: true,
            writable: true
          });
        } catch {
          window.speechSynthesis.getVoices = getVoices;
        }
        setTimeout(() => {
          try { window.speechSynthesis.dispatchEvent(new Event('voiceschanged')); } catch {}
        }, 0);
      };
      if (document.readyState === 'loading') {
        document.addEventListener('DOMContentLoaded', installSpeechVoicesConsistency, { once: true });
      } else {
        installSpeechVoicesConsistency();
      }
    }
  } catch {}

  // ═══ CONNECTION OVERRIDE ═══ (only when mode === 'spoof')
  try {
    if (connectionProfile.mode === 'spoof' && window.NetworkInformation && navigator.connection) {
      const connProto = window.NetworkInformation.prototype;
      // effectiveType DERIVADO do downlink/rtt (que ja variam por conta) -> por conta E
      // coerente, em vez de cravar "4g" igual em toda instancia (e incoerente com rtt alto).
      {
        const _dl = numberOr(connectionProfile.downlink, 10), _rt = intOr(connectionProfile.rtt, 50);
        const _eff = (_dl >= 10 && _rt <= 60) ? '4g'
                   : (_dl >= 1.5 && _rt <= 280) ? '3g'
                   : (_dl >= 0.4) ? '2g' : 'slow-2g';
        define(connProto, 'effectiveType', () => _eff);
      }
      if (connectionProfile.rtt) define(connProto, 'rtt', () => intOr(connectionProfile.rtt, 100));
      if (connectionProfile.downlink) define(connProto, 'downlink', () => numberOr(connectionProfile.downlink, 10));
      if (connectionProfile.saveData != null) define(connProto, 'saveData', () => !!connectionProfile.saveData);
      if (connectionProfile.type) define(connProto, 'type', () => connectionProfile.type);
    }
  } catch {}

  // ═══ WEBGPU ═══ — quando o WebGL é spoofado, navigator.gpu exporia a GPU REAL via
  // requestAdapter().requestAdapterInfo() (vendor/architecture/device), contradizendo o renderer
  // spoofado. requestAdapter()->null é exatamente o estado de um backend de software/datacenter
  // (SwiftShader/headless): WebGPU presente, sem adapter de hardware. Coerente e sem vazar a GPU real.
  try {
    if ((webglProfile.mode === 'spoof' || webglProfile.mode === 'spoof_noise') && navigator.gpu && typeof navigator.gpu.requestAdapter === 'function') {
      const gpuProto = Object.getPrototypeOf(navigator.gpu);
      Object.defineProperty(gpuProto, 'requestAdapter', {
        value: async function requestAdapter() { return null; },
        configurable: true, writable: true
      });
    }
  } catch {}

  // ═══ STORAGE QUOTA ═══ — navigator.storage.estimate().quota deriva do espaço em disco REAL do
  // host (identificador estável). Fixar um valor plausível e estável evita vazar o tamanho do disco.
  try {
    const storageProfile = profile.storage || {};
    if (storageProfile.mode === 'spoof'
        && navigator.storage && typeof navigator.storage.estimate === 'function') {
      const storageProto = Object.getPrototypeOf(navigator.storage);
      // Quota/usage POR CONTA (seed do perfil): Chrome real deriva do disco; valor cravado
      // igual em toda instancia e correlacionavel. Aqui sai plausivel, nao-redondo e estavel
      // por conta. Sem seed, mantem o default antigo (~120GB) p/ compat.
      const _sSeed = (Number.isFinite(profile.noiseSeed) ? (profile.noiseSeed >>> 0) : 0);
      const _quota = _sSeed
        ? ((96 + (_sSeed % 384)) * 1024 * 1024 * 1024) - ((_sSeed % 7919) * 1024 * 1024)
        : 120 * 1024 * 1024 * 1024;
      const _usage = _sSeed
        ? Math.floor(_quota * (0.003 + ((_sSeed >>> 9) % 110) / 10000))
        : 32 * 1024 * 1024;
      Object.defineProperty(storageProto, 'estimate', {
        value: async function estimate() {
          return { quota: _quota, usage: _usage, usageDetails: {} };
        },
        configurable: true, writable: true
      });
    }
  } catch {}

""";

    private SeleniumProxyNavigatorSurfaceScripts() {}
}
