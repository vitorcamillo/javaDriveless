package io.github.selenium.javaDriverless.scripts;

public final class AntiDetectionScripts {
    private AntiDetectionScripts() {}

    public static String comprehensive() {
        return
        "(function() {" +
        "'use strict';" +

        // ============================================================" +
        // 1. NAVIGATOR.WEBDRIVER - Fix using prototype approach (stealth pattern)" +
        // ============================================================" +
        "try { document.documentElement.removeAttribute('webdriver'); } catch(e) {}" +
        "try { Object.defineProperty(Navigator.prototype, 'webdriver', { get: function() { return undefined; }, configurable: true, enumerable: true }); } catch(e) {}" +

        AntiDetectionChromeScripts.chromeObject() +

        // ============================================================" +
        // 3. NAVIGATOR.PLUGINS - Mimic headful Chrome plugins array" +
        // Based on puppeteer-extra-plugin-stealth navigator.plugins" +
        // ============================================================" +
        "(function() {" +
        "  // Only mock if plugins array is empty (headless)" +
        "  if (navigator.plugins && navigator.plugins.length > 0) return;" +
        "  var plugins = [" +
        "    { name: 'Chrome PDF Plugin', filename: 'internal-pdf-viewer', description: 'Portable Document Format viewer', length: 0 }," +
        "    { name: 'Chrome PDF Viewer', filename: 'mhjfbmdgcfgjoadjgmfgcdmkdgbpian', description: 'Chrome PDF Viewer', length: 0 }," +
        "    { name: 'Native Client', filename: 'internal-nacl-plugin', description: 'Native Client', length: 0 }" +
        "  ];" +
        "  plugins.length = 3;" +
        "  plugins.item = function(i) { return this[i] || null; };" +
        "  plugins.namedItem = function(name) {" +
        "    for (var i = 0; i < this.length; i++) {" +
        "      if (this[i].name === name) return this[i];" +
        "    }" +
        "    return null;" +
        "  };" +
        "  plugins.refresh = function(){};" +
        "  Object.defineProperty(navigator, 'plugins', {" +
        "    get: function() { return plugins; }," +
        "    configurable: true," +
        "    enumerable: true" +
        "  });" +
        "  // Also define mimeTypes" +
        "  var mimeTypes = [];" +
        "  mimeTypes.length = 0;" +
        "  mimeTypes.item = function(i) { return this[i] || null; };" +
        "  mimeTypes.namedItem = function(name) {" +
        "    for (var i = 0; i < this.length; i++) {" +
        "      if (this[i].type === name) return this[i]; } return null;" +
        "  };" +
        "  Object.defineProperty(navigator, 'mimeTypes', {" +
        "    get: function() { return mimeTypes; }," +
        "    configurable: true," +
        "    enumerable: true" +
        "  });" +
        "})();" +

        // ============================================================" +
        // 4. NAVIGATOR.LANGUAGES - Match with Chrome UA HTTP Accept-Language" +
        // ============================================================" +
        "Object.defineProperty(navigator, 'languages', {" +
        "  get: function() { return ['en-US', 'en']; }," +
        "  configurable: true," +
        "  enumerable: true" +
        "});" +


        // ============================================================" +
        // 5. NAVIGATOR.MEDIADEVICES - Based on stealth pattern" +
        // ============================================================" +
        "(function() {" +
        "  // Only mock if mediaDevices doesn't exist or is empty" +
        "  if (navigator.mediaDevices && navigator.mediaDevices.enumerateDevices) return;" +
        "  var devices = [" +
        "    { kind: 'audioinput', deviceId: 'default', groupId: 'group_0', label: 'Default Audio Input', toJSON: function() { return Object.assign({}, this); } }," +
        "    { kind: 'audiooutput', deviceId: 'default', groupId: 'group_0', label: 'Default Audio Output', toJSON: function() { return Object.assign({}, this); } }," +
        "    { kind: 'videoinput', deviceId: 'default', groupId: 'group_0', label: 'FaceTime HD Camera', toJSON: function() { return Object.assign({}, this); } }" +
        "  ];" +
        "  var mockMediaDevices = {" +
        "    getUserMedia: function(constraints) {" +
        "      return Promise.resolve({ getTracks: function() { return []; }, getAudioTracks: function() { return []; }, getVideoTracks: function() { return []; }, getTrackById: function(id) { return null; }, addTrack: function(){}, removeTrack: function(){}, addEventListener: function(){}, removeEventListener: function(){}, dispatchEvent: function() { return true; } });" +
        "    }," +
        "    enumerateDevices: function() {" +
        "      return Promise.resolve(devices.map(function(d) { return Object.assign({}, d); }));" +
        "    }," +
        "    getSupportedConstraints: function() {" +
        "      return { width: true, height: true, aspectRatio: true, frameRate: true, facingMode: true, resizeMode: true, volume: true, sampleRate: true, sampleSize: true, echoCancellation: true, latency: true, channelCount: true, deviceId: true, groupId: true, displaySurface: true, logicalSurface: true, cursor: true };" +
        "    }," +
        "    addEventListener: function(){}," +
        "    removeEventListener: function(){}," +
        "    dispatchEvent: function() { return true; }," +
        "    ondevicechange: null" +
        "  };" +
        "  Object.defineProperty(navigator, 'mediaDevices', {" +
        "    get: function() { return mockMediaDevices; }," +
        "    configurable: true," +
        "    enumerable: true" +
        "  });" +
        "})();" +

        // ============================================================" +
        // 6. CRITICAL: USER-AGENT AND PLATFORM - PRESERVE REAL VALUES" +
        // In headful mode, the real UA and platform are already correct." +
        // Only override if we detect HeadlessChrome (headless mode)." +
        // Overriding a real Linux UA with Win32 is a DETECTION VECTOR!" +
        // ============================================================" +
        "(function() {" +
        "  var currentUA = navigator.userAgent || '';" +
        "  // Only override if headless is detected in UA" +
        "  if (currentUA.indexOf('HeadlessChrome') !== -1) {" +
        "    var fixedUA = currentUA.replace('HeadlessChrome', 'Chrome');" +
        "    try { delete navigator.userAgent; } catch(e) {}" +
        "    Object.defineProperty(navigator, 'userAgent', { get: function() { return fixedUA; }, configurable: true, enumerable: true });" +
        "  }" +
        "  // Platform: only override if it's empty or indicates headless" +
        "  var plat = navigator.platform;" +
        "  if (!plat || plat === '') {" +
        "    var guessedPlatform = 'Win32';" +
        "    if (currentUA.indexOf('Linux') !== -1) guessedPlatform = 'Linux x86_64';" +
        "    else if (currentUA.indexOf('Mac') !== -1) guessedPlatform = 'MacIntel';" +
        "    try { delete navigator.platform; } catch(e) {}" +
        "    Object.defineProperty(navigator, 'platform', { get: function() { return guessedPlatform; }, configurable: true, enumerable: true });" +
        "  }" +
        "})();" +

        // ============================================================" +
        // 7. NAVIGATOR PROPERTIES - PRESERVE REAL, ONLY MOCK IF MISSING" +
        // In headful mode, real values (e.g. 16 cores, 32GB RAM) are" +
        // MORE convincing than fake ones (8/8). Only override if the" +
        // values are missing or zero (headless mode)." +
        // ============================================================" +
        "(function() {" +
        "  // Only override hardwareConcurrency if it's 0 or missing" +
        "  // Dolphin sets this natively via cpu.mode='manual'" +
        "  if (!navigator.hardwareConcurrency || navigator.hardwareConcurrency < 2) {" +
        "    Object.defineProperty(navigator, 'hardwareConcurrency', { get: function() { return 8; }, configurable: true, enumerable: true });" +
        "  }" +
        "  // Only override deviceMemory if it's missing" +
        "  if (typeof navigator.deviceMemory === 'undefined' || navigator.deviceMemory < 2) {" +
        "    Object.defineProperty(navigator, 'deviceMemory', { get: function() { return 8; }, configurable: true, enumerable: true });" +
        "  }" +
        "})();" +
        // vendor: keep — guard would be better but OK for now
        "Object.defineProperty(navigator, 'vendor', { get: function() { return 'Google Inc.'; }, configurable: true, enumerable: true });" +
        // maxTouchPoints: 0 is correct for desktop
        "Object.defineProperty(navigator, 'maxTouchPoints', { get: function() { return 0; }, configurable: true, enumerable: true });" +
        // doNotTrack: null is Chrome default (user hasn't set preference)
        "Object.defineProperty(navigator, 'doNotTrack', { get: function() { return null; }, configurable: true, enumerable: true });" +
        "Object.defineProperty(navigator, 'cookieEnabled', { get: function() { return true; }, configurable: true, enumerable: true });" +
        "Object.defineProperty(navigator, 'onLine', { get: function() { return true; }, configurable: true, enumerable: true });" +
        "Object.defineProperty(navigator, 'pdfViewerEnabled', { get: function() { return true; }, configurable: true, enumerable: true });" +


        // ============================================================" +
        // 8. PERMISSIONS API - Based on stealth pattern" +
        // ============================================================" +
        "(function() {" +
        "  if (navigator.permissions) return; // Already exists in headful" +
        "  var permissions = {" +
        "    query: function query(permDescriptor) {" +
        "      var name = (typeof permDescriptor === 'string') ? permDescriptor : (permDescriptor && permDescriptor.name) || 'default';" +
        "      var result = { state: 'prompt', onchange: null };" +
        "      if (name === 'geolocation') result.state = 'prompt';" +
        "      else if (name === 'notifications') result.state = 'denied';" +
        "      else if (name === 'persistent-storage') result.state = 'granted';" +
        "      else if (name === 'storage-access') result.state = 'prompt';" +
        "      else if (name === 'camera') result.state = 'prompt';" +
        "      else if (name === 'microphone') result.state = 'prompt';" +
        "      else result.state = 'granted';" +
        "      return Promise.resolve(result);" +
        "    }," +
        "    request: function(permission) { return this.query(permission); }," +
        "    revoke: function(permission) { return this.query(permission); }" +
        "  };" +
        "  Object.defineProperty(navigator, 'permissions', {" +
        "    get: function() { return permissions; }," +
        "    configurable: true," +
        "    enumerable: true" +
        "  });" +
        "})();" +

        // ============================================================" +
        // 9. SCREEN PROPERTIES — DISABLED" +
        // Dolphin profile uses screen.mode='real' (native resolution)" +
        // Hardcoding 1920x1080 here conflicts with CSS media queries" +
        // which report actual resolution → CreepJS detects inconsistency" +
        // ============================================================" +

        // ============================================================" +
        // 10. OUTER DIMENSIONS - Fix for headless missing outerWidth/outerHeight" +
        // Based on puppeteer-extra-plugin-stealth window.outerdimensions" +
        // ============================================================" +
        "(function() {" +
        "  try {" +
        "    if (window.outerWidth !== undefined && window.outerHeight !== undefined) return;" +
        "    var windowFrame = 85; // OS and WM dependent" +
        "    window.outerWidth = window.innerWidth;" +
        "    window.outerHeight = window.innerHeight + windowFrame;" +
        "  } catch(err) {}" +
        "})();" +

        // ============================================================" +
        // 11. WEBGL SPOOFING — DISABLED" +
        // Dolphin profile handles this natively via webglInfo.mode='manual'" +
        // The Proxy wrapper was detected as a 'Lie' by CreepJS because" +
        // it contradicts the browser's native WebGL strings." +
        // ============================================================" +

        // ============================================================" +
        // 12. CANVAS FINGERPRINT - STABLE (no modification)" +
        // KEY: consistency > modification (pixelscan principle)" +
        // ============================================================" +
        // "(function() {" +
        // "  // PASS-THROUGH: Let canvas return natural values" +
        // "  // The key insight is that INCONSISTENCY causes detection, not the values themselves" +
        // "  // We don't add any noise to ensure same values on every call" +
        // "  var origToDataURL = HTMLCanvasElement.prototype.toDataURL;" +
        // "  HTMLCanvasElement.prototype.toDataURL = function(type) {" +
        // "    return origToDataURL.apply(this, arguments);" +
        // "  };" +
        // "  var origGetImageData = CanvasRenderingContext2D.prototype.getImageData;" +
        // "  CanvasRenderingContext2D.prototype.getImageData = function(sx, sy, sw, sh) {" +
        // "    return origGetImageData.call(this, sx, sy, sw, sh);" +
        // "  };" +
        // "  var origToBlob = HTMLCanvasElement.prototype.toBlob;" +
        // "  HTMLCanvasElement.prototype.toBlob = function(cb, type, quality) {" +
        // "    return origToBlob.call(this, cb, type, quality);" +
        // "  };" +
        // "})();" +

        // ============================================================" +
        // 13. AUDIO FINGERPRINT - STABLE (pass-through)" +
        // ============================================================" +
        // "(function() {" +
        // "  if (!window.AudioContext && !window.webkitAudioContext) return;" +
        // "  var AudioCtx = window.AudioContext || window.webkitAudioContext;" +
        // "  // Pass-through for consistency" +
        // "  var origCreateAnalyser = AudioCtx.prototype.createAnalyser;" +
        // "  AudioCtx.prototype.createAnalyser = function() { return origCreateAnalyser.call(this); };" +
        // "  var origCreateOscillator = AudioCtx.prototype.createOscillator;" +
        // "  AudioCtx.prototype.createOscillator = function() {" +
        // "    var osc = origCreateOscillator.call(this);" +
        // "    var origConnect = osc.connect;" +
        // "    osc.connect = function(dest) { return origConnect.call(this, dest); };" +
        // "    return osc;" +
        // "  };" +
        // "})();" +

        // ============================================================" +
        // 14. WEBRTC SPOOFING" +
        // ============================================================" +
        // "(function() {" +
        // "  if (!window.RTCPeerConnection && !window.webkitRTCPeerConnection) return;" +
        // "  var RTCPeerConnection = window.RTCPeerConnection || window.webkitRTCPeerConnection;" +
        // "  var OrigRTC = RTCPeerConnection;" +
        // "  window.RTCPeerConnection = function(config) {" +
        // "    var pc = new OrigRTC(config);" +
        // "    pc.getConfiguration = function() { return config || { iceServers: [] }; };" +
        // "    return pc;" +
        // "  };" +
        // "  window.RTCPeerConnection.prototype = OrigRTC.prototype;" +
        // "  window.RTCPeerConnection.prototype.constructor = window.RTCPeerConnection;" +
        // "})();" +

        // ============================================================" +
        // 15. CONNECTION INFO SPOOFING" +
        // ============================================================" +
        // "(function() {" +
        // "  if (navigator.connection || navigator.mozConnection || navigator.webkitConnection) return;" +
        // "  Object.defineProperty(navigator, 'connection', {" +
        // "    get: function() {" +
        // "      return {" +
        // "        downlink: 10," +
        // "        effectiveType: '4g'," +
        // "        rtt: 50," +
        // "        saveData: false," +
        // "        onchange: null," +
        // "        addEventListener: function(){}," +
        // "        removeEventListener: function(){}," +
        // "        dispatchEvent: function() { return true; }" +
        // "      };" +
        // "    }," +
        // "    configurable: true," +
        // "    enumerable: true" +
        // "  });" +
        // "})();" +

        // ============================================================" +
        // 16. REMOVE CDP AUTOMATION VARIABLES - Comprehensive cleanup" +
        // Based on stealth pattern - delete via Object.defineProperty" +
        // ============================================================" +
        "(function() {" +
        "  var cleanup = function(obj, prop) {" +
        "    try {" +
        "      var val = obj[prop];" +
        "      if (val !== undefined && val !== null) {" +
        "        delete obj[prop];" +
        "      }" +
        "    } catch(e) {" +
        "      try { Object.defineProperty(obj, prop, { get: function() { return undefined; }, configurable: true }); } catch(e2) {}" +
        "    }" +
        "  };" +
        "  // CDP properties to remove" +
        "  var cdcProps = [" +
        "    'cdc_adoQpoasnfa76pfcZLmcfl_Array'," +
        "    'cdc_adoQpoasnfa76pfcZLmcfl_Promise'," +
        "    'cdc_adoQpoasnfa76pfcZLmcfl_Symbol'," +
        "    '$chrome_asyncScriptInfo'," +
        "    '__webdriver_script_function'," +
        "    '__selenium_evaluate'," +
        "    '__webdriver_evaluate'," +
        "    '__selenium_scriptFunction'," +
        "    '__webdriver_script_function'," +
        "    '__webdriver_script_func'," +
        "    '__selenium_script_func'," +
        "    '__fxdriver_alert'," +
        "    '__fxdriver_exception'," +
        "    '__fxdriver_is_stale'," +
        "    '__selenium_ide_click'," +
        "    '__lastWatirKey'," +
        "    '__webnode_'," +
        "    '__webnode_id'," +
        "    '__native_error'," +
        "    '__nightwatch'," +
        "    '__webdriver_'," +
        "    '__selenium__'," +
        "    '__driver_'," +
        "    '__fxdad_'," +
        "    '__dom_automation_'," +
        "    '__chromepopup'," +
        "    '__detect_nightmare'," +
        "    '__nightmare'," +
        "    '_WEBDRIVER_ELEM_CACHE'," +
        "    'selenium'," +
        "    'webdriver'," +
        "    '__webgl2debug'," +
        "    '__driver_undefined'" +
        "  ];" +
        "  cdcProps.forEach(function(p) { cleanup(window, p); });" +
        // "  // Also clean up prototype properties" +
        // "  try { delete Object.getPrototypeOf(navigator).webdriver; } catch(e) {}" +
        // "  try { delete Object.getPrototypeOf(navigator).plugins; } catch(e) {}" +
        // "  try { delete Object.getPrototypeOf(navigator).mimeTypes; } catch(e) {}" +
        "})();" +

        // ============================================================" +
        // 17. PERFORMANCE TIMING - STABLE (no modification)" +
        // ============================================================" +
        "(function() {" +
        "  // Don't modify Performance.now() - natural consistent values are better" +
        "  // The native implementation is already consistent" +
        "})();" +

        // ============================================================" +
        // 18. FONTS FINGERPRINT - Proper implementation" +
        // ============================================================" +
        // "(function() {" +
        // "  // Use prototype-based approach like stealth" +
        // "  if (navigator.fonts && navigator.fonts.query) return; // Already exists" +
        // "  Object.defineProperty(navigator, 'fonts', {" +
        // "    get: function() {" +
        // "      return {" +
        // "        get query() { return 'always'; }," +
        // "        get size() { return 0; }," +
        // "        size: 0," +
        // "        status: 'complete'," +
        // "        ready: Promise.resolve()" +
        // "      };" +
        // "    }," +
        // "    configurable: true," +
        // "    enumerable: true" +
        // "  });" +
        // "})();" +

        // ============================================================" +
        // 19. VENDOR/CHROME SPECIFIC" +
        // ============================================================" +
        // "try { delete navigator.vendor; } catch(e) {}" +
        // FIX GAP-10: Consistência — usar 'Google Inc.' em todos os lugares" +
        "Object.defineProperty(navigator, 'vendor', { get: function() { return 'Google Inc.'; }, configurable: true, enumerable: true });" +
        "if (!window.webkitResolveLocalFileSystemURL) {" +
        "  window.webkitResolveLocalFileSystemURL = function(url, success, failure) { if (failure) failure(null); };" +
        "}" +

        // ============================================================" +
        // 20. SECURE CONTEXT CHECK BYPASS" +
        // ============================================================" +
        // "if (typeof window.isSecureContext !== 'undefined') {" +
        // "  Object.defineProperty(window, 'isSecureContext', { get: function() { return true; }, configurable: true });" +
        // "}" +

        // ============================================================" +
        // 21. WEBDRIVER FLAG BYPASS (Chrome 89+)" +
        // ============================================================" +
        // "try { delete navigator.webdriver; } catch(e) {}" +
        // "Object.defineProperty(navigator, 'webdriver', { get: function() { return false; }, configurable: true, enumerable: true });" +

        // ============================================================" +
        // 22. NATIVE TOSTRING WRAPPER - Make mock functions look native" +
        // ============================================================" +
        // "(function() {" +
        // "  var nativeToString = Function.prototype.toString;" +
        // "  var fakeToString = function toString() {" +
        // "    if (this.__nativeName) return 'function ' + this.__nativeName + '() { [native code] }';" +
        // "    return nativeToString.call(this);" +
        // "  };" +
        // "  fakeToString.__nativeName = 'toString';" +
        // "  Function.prototype.toString = fakeToString;" +
        // "})();" +

        // FIX GAP-06: Removido console.log que expunha anti-detection" +
        "})();";
    }
}
