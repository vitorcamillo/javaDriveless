package io.github.selenium.javaDriverless.fpmapping;

final class FingerprintMappingScripts {
    private FingerprintMappingScripts() {
    }

    static final String NAVIGATOR_SCRIPT = """
            (async function(){
              const webgl = (() => {
                try {
                  const canvas = document.createElement('canvas');
                  const gl = canvas.getContext('webgl') || canvas.getContext('experimental-webgl');
                  const ext = gl && gl.getExtension('WEBGL_debug_renderer_info');
                  return {
                    vendor: gl && ext ? gl.getParameter(ext.UNMASKED_VENDOR_WEBGL) : null,
                    renderer: gl && ext ? gl.getParameter(ext.UNMASKED_RENDERER_WEBGL) : null
                  };
                } catch (e) { return { error: String(e) }; }
              })();
              const userAgentData = (() => {
                try {
                  if (!navigator.userAgentData) return null;
                  const out = {
                    brands: navigator.userAgentData.brands || null,
                    mobile: navigator.userAgentData.mobile,
                    platform: navigator.userAgentData.platform || null
                  };
                  if (typeof navigator.userAgentData.getHighEntropyValues === 'function') {
                    return navigator.userAgentData.getHighEntropyValues([
                      'architecture',
                      'bitness',
                      'fullVersionList',
                      'model',
                      'platformVersion',
                      'uaFullVersion',
                      'wow64'
                    ]).then(values => Object.assign(out, values)).catch(() => out);
                  }
                  return out;
                } catch (e) { return { error: String(e) }; }
              })();
              const uaData = await Promise.resolve(userAgentData);
              return JSON.stringify({
                userAgent: navigator.userAgent,
                userAgentData: uaData,
                webdriver: navigator.webdriver,
                language: navigator.language,
                languages: navigator.languages,
                platform: navigator.platform,
                vendor: navigator.vendor,
                hardwareConcurrency: navigator.hardwareConcurrency,
                deviceMemory: navigator.deviceMemory,
                maxTouchPoints: navigator.maxTouchPoints,
                plugins: navigator.plugins ? navigator.plugins.length : null,
                mimeTypes: navigator.mimeTypes ? navigator.mimeTypes.length : null,
                timezone: Intl.DateTimeFormat().resolvedOptions().timeZone,
                locale: Intl.DateTimeFormat().resolvedOptions().locale,
                screen: {
                  width: screen.width,
                  height: screen.height,
                  availWidth: screen.availWidth,
                  availHeight: screen.availHeight,
                  colorDepth: screen.colorDepth,
                  pixelDepth: screen.pixelDepth,
                  dpr: devicePixelRatio,
                  innerWidth: innerWidth,
                  innerHeight: innerHeight,
                  outerWidth: outerWidth,
                  outerHeight: outerHeight
                },
                webgl
              });
            })()
            """;

    static final String SIGNAL_SCRIPT = """
            JSON.stringify((function(){
              const text = document.body ? document.body.innerText : '';
              const red = [];
              const yellow = [];
              for (const el of Array.from(document.querySelectorAll('*')).slice(0, 5000)) {
                const style = getComputedStyle(el);
                const value = (el.innerText || '').trim().replace(/\\s+/g, ' ');
                if (!value || value.length > 260) continue;
                const m = style.color.match(/rgba?\\((\\d+),\\s*(\\d+),\\s*(\\d+)/);
                if (!m) continue;
                const r = Number(m[1]), g = Number(m[2]), b = Number(m[3]);
                if (r > 180 && g < 110 && b < 110) red.push(value);
                if (r > 180 && g > 100 && g < 230 && b < 120) yellow.push(value);
              }
              return {
                bodyLength: text.length,
                hasWebdriverText: /webdriver/i.test(text),
                hasHeadlessText: /headless/i.test(text),
                hasLieText: /\\blie|lies\\b/i.test(text),
                hasInconsistentText: /inconsistent/i.test(text),
                red: Array.from(new Set(red)).slice(0, 80),
                yellow: Array.from(new Set(yellow)).slice(0, 80)
              };
            })())
            """;

    static final String DETECTION_HINT_SCRIPT = """
            JSON.stringify((function(){
              const text = document.body ? document.body.innerText : '';
              const hasPositive = phrase => {
                const escaped = phrase.replace(/[.*+?^${}()|[\\]\\\\]/g, '\\\\$&');
                const negative = new RegExp('(?:no|not|without)\\\\s+' + escaped, 'i');
                return new RegExp(escaped, 'i').test(text) && !negative.test(text);
              };
              const ua = navigator.userAgent || '';
              const strong = [];
              const weak = [];
              if (navigator.webdriver === true) strong.push('navigator.webdriver=true');
              if (/HeadlessChrome/i.test(ua)) strong.push('HeadlessChrome userAgent');
              if (/webdriver\\\\s*(true|detected|present)/i.test(text)) strong.push('webdriver text');
              if (hasPositive('browser fingerprint is inconsistent')) strong.push('PixelScan inconsistent');
              if (hasPositive('masking detected')) strong.push('PixelScan masking detected');
              if (hasPositive('automated behavior detected')) strong.push('PixelScan bot detected');
              if (/headless/i.test(text)
                  && !/0%\\s+(?:like\\s+)?headless/i.test(text)
                  && !/Headless browsers and virtual machines produce distinctive/i.test(text)) {
                weak.push('page mentions headless');
              }
              if (/\\\\blies?\\\\b/i.test(text)) weak.push('page mentions lies');
              if (/inconsistent/i.test(text) && !/browser fingerprint is consistent/i.test(text)) {
                weak.push('page mentions inconsistent');
              }
              return {
                webdriver: navigator.webdriver === true,
                userAgent: ua,
                title: document.title || '',
                strong,
                weak
              };
            })())
            """;
}
