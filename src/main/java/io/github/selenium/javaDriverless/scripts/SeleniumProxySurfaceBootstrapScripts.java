package io.github.selenium.javaDriverless.scripts;

final class SeleniumProxySurfaceBootstrapScripts {
    static final String SCRIPT = """
(() => {
  // A instalação é deduplicada no lado Java por targetId. Não registrar estado no realm da página:
  // símbolos registrados também podem ser enumerados e convertidos de volta para sua chave global.
  const profile = __PROFILE_JSON__;
  const _storage = { profile: profile, workerLogs: [], webaudioLogs: [], localFontsLogs: [] };
  const safe = (fn, fallback = null) => { try { return fn(); } catch { return fallback; } };
  // NOTA: NÃO sobrescrever Function.prototype.toString para "mascarar" os wrappers. Medido
  // empiricamente: o CreepJS detecta o override de Function.prototype.toString e SOBE o stealth de
  // 0% para 20% — ou seja, mascarar o toString PIORA a deteccao. As checagens ingenuas de
  // fn.toString() ficam expostas, mas isso e menos detectavel que o proprio mascaramento.
  const define = (obj, prop, getter, configurable = true) => {
    try { Object.defineProperty(obj, prop, { get: getter, configurable, enumerable: true }); } catch {}
  };
  const languages = Array.isArray(profile.languages) && profile.languages.length
    ? profile.languages.slice()
    : [profile.locale || 'en-US'];
  const screenProfile = profile.screen || {};
  const connectionProfile = profile.connection || {};
  const webglProfile = profile.webgl || {};
  const speechProfile = profile.speech || {};
  const audioProfile = profile.audio || {};
  const canvasProfile = profile.canvas || {};
  const clientRectsProfile = profile.clientRects || {};
  const numberOr = (value, fallback) => Number.isFinite(Number(value)) ? Number(value) : fallback;
  const intOr = (value, fallback) => Math.trunc(numberOr(value, fallback));
  const firstLanguage = languages[0] || profile.locale || 'en-US';
  const doNotTrack = profile.doNotTrack ? '1' : null;

""";

    private SeleniumProxySurfaceBootstrapScripts() {}
}
