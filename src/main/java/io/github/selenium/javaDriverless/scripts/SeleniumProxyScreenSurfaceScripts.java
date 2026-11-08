package io.github.selenium.javaDriverless.scripts;

final class SeleniumProxyScreenSurfaceScripts {
    static final String SCRIPT = """
  // ═══ SCREEN OVERRIDES ═══ (only when mode !== 'real')
  try {
    if (screenProfile.mode && screenProfile.mode !== 'real') {
      const nativeScreenWidth = screen.width;
      const nativeScreenHeight = screen.height;
      const nativeAvailWidth = screen.availWidth;
      const nativeAvailHeight = screen.availHeight;
      const targetScreenWidth = intOr(screenProfile.width, nativeScreenWidth);
      const targetScreenHeight = intOr(screenProfile.height, nativeScreenHeight);
      const targetAvailWidth = intOr(screenProfile.availWidth, nativeAvailWidth || nativeScreenWidth);
      const targetAvailHeight = intOr(screenProfile.availHeight, nativeAvailHeight || nativeScreenHeight - 40);
      const targetColorDepth = intOr(screenProfile.colorDepth, screen.colorDepth);
      const targetPixelDepth = intOr(screenProfile.pixelDepth, screen.pixelDepth);
      if (targetScreenWidth !== nativeScreenWidth) {
        define(Screen.prototype, 'width', () => targetScreenWidth);
      }
      if (targetScreenHeight !== nativeScreenHeight) {
        define(Screen.prototype, 'height', () => targetScreenHeight);
      }
      if (targetAvailWidth !== nativeAvailWidth) {
        define(Screen.prototype, 'availWidth', () => targetAvailWidth);
      }
      if (targetAvailHeight !== nativeAvailHeight) {
        define(Screen.prototype, 'availHeight', () => targetAvailHeight);
      }
      if (targetColorDepth !== screen.colorDepth) {
        define(Screen.prototype, 'colorDepth', () => targetColorDepth);
      }
      if (targetPixelDepth !== screen.pixelDepth) {
        define(Screen.prototype, 'pixelDepth', () => targetPixelDepth);
      }
    }
  } catch {}

  // ═══ WINDOW OUTER DIMENSION CONSISTENCY ═══
  // Headless Chromium can expose outerHeight equal to screen.height. Real
  // desktop Chrome normally leaves room for the browser frame/taskbar.
  try {
    const screenHeight = Number.isFinite(Number(screenProfile.height))
      ? Math.trunc(Number(screenProfile.height)) : screen.height;
    const availHeight = Number.isFinite(Number(screenProfile.availHeight))
      ? Math.trunc(Number(screenProfile.availHeight)) : (screen.availHeight || screenHeight - 40);
    const coherentOuterHeight = Math.max(1, availHeight - 8);
    if (screenHeight > 80 && availHeight > 0 && availHeight < screenHeight
        && window.outerHeight !== coherentOuterHeight) {
      define(window, 'outerHeight', () => coherentOuterHeight);
    }
  } catch {}

  // Some headful Linux sessions expose the full screen as available work area.
  // Apply this after prototype-lie detectors have run, but before surfaces read
  // screen dimensions. Also normalize same-origin iframes used for CSS media.
  try {
    if (!screenProfile.mode || screenProfile.mode === 'real') {
     // PC real com taskbar já expõe work-area nativa correta (availHeight < height). Xvfb/headless
     // (availHeight === height) ainda precisa do normalizador. A instalação usa somente lifecycle
     // do documento/MutationObserver e nunca substitui createElement, timers, workers ou register.
     // Opt-out explícito: screen.workAreaPatch=false.
     let _needWorkArea = true;
     try {
       if (screenProfile.workAreaPatch === false) _needWorkArea = false;
       else _needWorkArea = !(screen.height > 80 && screen.availHeight > 0 && screen.availHeight < screen.height);
     } catch (e) {}
     if (_needWorkArea) {
      const patchedScreenProtos = new WeakSet();
      const trackedFrames = new Set();
      const trackFrameTree = (root) => {
        try {
          const frames = [];
          if (root?.tagName && root.tagName.toLowerCase() === 'iframe') frames.push(root);
          if (root?.querySelectorAll) frames.push(...root.querySelectorAll('iframe'));
          for (const frame of frames) {
            if (trackedFrames.has(frame)) continue;
            if (trackedFrames.size >= 64) { for (const old of trackedFrames) { if (!old.isConnected) trackedFrames.delete(old); } }
            trackedFrames.add(frame);
            try {
              if (installedScreenWorkArea) patchScreenScope(frame.contentWindow, false);
            } catch {}
            try { trackFrameTree(frame.contentDocument); } catch {}
          }
        } catch {}
      };
      try {
        new MutationObserver((records) => {
          for (const record of records) {
            for (const node of record.addedNodes || []) {
              trackFrameTree(node);
            }
          }
        }).observe(document.documentElement, { childList: true, subtree: true });
      } catch {}
      const patchScreenScope = (scope, requireVisibleWorkArea) => {
        const scopeScreen = scope?.screen;
        const screenProto = scope?.Screen?.prototype;
        if (!scopeScreen || !screenProto || patchedScreenProtos.has(screenProto)) return;
        const widthGetter = Object.getOwnPropertyDescriptor(screenProto, 'width')?.get;
        const heightGetter = Object.getOwnPropertyDescriptor(screenProto, 'height')?.get;
        const availWidthGetter = Object.getOwnPropertyDescriptor(screenProto, 'availWidth')?.get;
        const availHeightGetter = Object.getOwnPropertyDescriptor(screenProto, 'availHeight')?.get;
        const readNative = (getter, fallback) => {
          try { return getter ? getter.call(scopeScreen) : fallback; } catch { return fallback; }
        };
        const hasVisibleWorkArea = (height) => !requireVisibleWorkArea || (
          window.outerHeight > 0 && window.innerHeight > 0 &&
          window.outerHeight < height && window.innerHeight < height
        );
        const shouldNormalizeWorkArea = () => {
          const width = readNative(widthGetter, scopeScreen.width);
          const height = readNative(heightGetter, scopeScreen.height);
          const availWidth = readNative(availWidthGetter, width);
          const availHeight = readNative(availHeightGetter, height);
          return height > 80 && width === availWidth && height === availHeight && hasVisibleWorkArea(height);
        };
        define(screenProto, 'availWidth', () => {
          const width = readNative(widthGetter, scopeScreen.width);
          const availWidth = readNative(availWidthGetter, width);
          return shouldNormalizeWorkArea() ? intOr(screenProfile.availWidth, availWidth || width) : availWidth;
        });
        define(screenProto, 'availHeight', () => {
          const height = readNative(heightGetter, scopeScreen.height);
          const availHeight = readNative(availHeightGetter, height);
          return shouldNormalizeWorkArea() ? intOr(screenProfile.availHeight, height - 40) : availHeight;
        });
        patchedScreenProtos.add(screenProto);
      };
      let installedScreenWorkArea = false;
      const installScreenWorkAreaNormalization = () => {
        if (installedScreenWorkArea) return;
        installedScreenWorkArea = true;
        patchScreenScope(window, true);
        trackFrameTree(document);
        for (const frame of document.querySelectorAll('iframe')) {
          try { patchScreenScope(frame.contentWindow, false); } catch {}
        }
        for (const frame of trackedFrames) {
          try { trackFrameTree(frame.contentDocument); } catch {}
          try { patchScreenScope(frame.contentWindow, false); } catch {}
        }
      };
      if (document.readyState === 'loading') {
        document.addEventListener('DOMContentLoaded', installScreenWorkAreaNormalization, { once: true });
      } else {
        installScreenWorkAreaNormalization();
      }
     }
    }
  } catch {}

""";

    private SeleniumProxyScreenSurfaceScripts() {}
}
