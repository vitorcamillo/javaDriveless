package io.github.selenium.javaDriverless.scripts;

final class AntiDetectionChromeScripts {
    private AntiDetectionChromeScripts() {}

    static String chromeObject() {
        return
        // ============================================================" +
        // 2. CHROME OBJECT - Based on puppeteer-extra-plugin-stealth pattern" +
        // IMPORTANT: Only mock chrome.runtime if it doesn't exist (headful has it)" +
        // ============================================================" +
        "if (!window.chrome) {" +
        "  Object.defineProperty(window, 'chrome', {" +
        "    writable: true," +
        "    enumerable: true," +
        "    configurable: false," +  // note: must be false like in real Chrome
        "    value: {}" +
        "  });" +
        "}" +
        "// Only mock chrome.runtime if it doesn't exist (headful mode already has it)" +
        "if (!('runtime' in window.chrome)) {" +
        "  window.chrome.runtime = {" +
        "    connect: function connect() { return { onMessage: { addListener: function(){} }, onDisconnect: { addListener: function(){} }, postMessage: function(){}, disconnect: function(){} }; }," +
        "    sendMessage: function sendMessage() { return Promise.resolve({}); }," +
        "    getManifest: function getManifest() { return { manifest_version: 3, name: 'Chrome', version: '123.0.0.0' }; }," +
        "    getURL: function getURL(path) { return 'chrome-extension://jbiepmmlhdimnamibkegfhiciejcjolo/' + path; }," +
        "    id: undefined, // Real Chrome returns undefined for id" +
        "    lastError: null," +
        "    onInstalled: { addListener: function(){}, removeListener: function(){}, hasListener: function(){} }," +
        "    onStartup: { addListener: function(){} }," +
        "    onUpdateAvailable: { addListener: function(){} }," +
        "    onUpdateFound: { addListener: function(){} }," +
        "    requestUpdateCheck: function() { return Promise.resolve({ status: 'throttled' }); }," +
        "    reload: function(){}," +
        "    restart: function(){}" +
        "  };" +
        "}" +

        // Add other chrome properties if missing (stealth pattern)" +
        "if (!window.chrome.loadTimes) {" +
        "  window.chrome.loadTimes = function() {" +
        "    var now = Date.now() / 1000;" +
        "    return {" +
        "      commitTime: 0.500," +
        "      connectionInfo: 'h2'," +
        "      finishTime: 0.100," +
        "      firstPaintAfterLoadTime: 0," +
        "      firstPaintTime: 0.800," +
        "      firstMeaningfulPaintTime: 0," +
        "      firstMeaningfulPaintCandidateTime: 0," +
        "      navigationType: 'Other'," +
        "      navigationStart: now - 1," +
        "      loadType: 1," +
        "      referrerPolicy: 'no-referrer-when-downgrade'," +
        "      navigationEntryCommitted: false" +
        "    };" +
        "  };" +
        "}" +
        "if (!window.chrome.csi) {" +
        "  window.chrome.csi = function() {" +
        "    var now = Date.now();" +
        "    return { timestamp: now, startTime: now - 1000 };" +
        "  };" +
        "}" +
        "if (!window.chrome.app) {" +
        "  window.chrome.app = {" +
        "    isInstalled: false," +
        "    InstallState: { DISABLED: 'disabled', INSTALLED: 'installed', NOT_INSTALLED: 'not_installed' }," +
        "    RunningState: { CANNOT_RUN: 'cannot_run', RUNNING: 'running', READY: 'ready' }," +
        "    getDetails: function() { return { id: 'jbiepmmlhdimnamibkegfhiciejcjolo' }; }," +
        "    getIsInstalled: function() { return false; }" +
        "  };" +
        "}" +
        "if (!window.chrome.storage) {" +
        "  window.chrome.storage = {" +
        "    local: { get: function(k, cb) { cb && cb({}); }, set: function(i, cb) { cb && cb(); }, remove: function(k, cb) { cb && cb(); }, clear: function(cb) { cb && cb(); }, getBytesInUse: function(k, cb) { cb && cb(0); } }," +
        "    session: { get: function(k, cb) { cb && cb({}); }, set: function(i, cb) { cb && cb(); }, remove: function(k, cb) { cb && cb(); }, clear: function(cb) { cb && cb(); } }," +
        "    managed: { get: function(k, cb) { cb && cb({}); }, set: function(i, cb) { cb && cb(); } }," +
        "    sync: { get: function(k, cb) { cb && cb({}); }, set: function(i, cb) { cb && cb(); } }" +
        "  };" +
        "}" +
        "if (!window.chrome.tabs) {" +
        "  window.chrome.tabs = { getCurrent: function(cb) { cb && cb(null); }, create: function(){}, get: function(){}, update: function(){}, remove: function(){}, query: function(){}, detectLanguage: function(tabId, cb) { cb && cb('en'); } };" +
        "}" +
        "if (!window.chrome.webstore) {" +
        "  window.chrome.webstore = { onInstallStateChanged: { addListener: function(){}, removeListener: function(){}, hasListener: function(){} }, isInstalled: false, install: function(){}, getIsInstalled: function() { return false; } };" +
        "}" +
        "if (!window.chrome.windows) {" +
        "  window.chrome.windows = { get: function(){}, getCurrent: function(){}, create: function(){}, update: function(){}, remove: function(){}, getAll: function(){}, getFocused: function(){} };" +
        "}" +
        "if (!window.chrome.commands) {" +
        "  window.chrome.commands = { getAll: function() { return Promise.resolve([]); } };" +
        "}" +
        "if (!window.chrome.i18n) {" +
        "  window.chrome.i18n = { getMessage: function(msg, subs) { return msg || ''; }, getUILanguage: function() { return navigator.language || 'en'; }, getAcceptLanguages: function() { return navigator.languages || ['en']; } };" +
        "}" +
        "if (!window.chrome.idle) {" +
        "  window.chrome.idle = { queryState: function() { return Promise.resolve('active'); }, addListener: function(){} };" +
        "}" +
        "if (!window.chrome.notifications) {" +
        "  window.chrome.notifications = { create: function(){}, getAll: function(){}, clear: function(){}, onClicked: { addListener: function(){} } };" +
        "}" +
        "if (!window.chrome.power) {" +
        "  window.chrome.power = { requestKeepAwake: function(){}, releaseKeepAwake: function(){} };" +
        "}" +
        "if (!window.chrome.system) {" +
        "  window.chrome.system = { cpu: { getInfo: function() { return Promise.resolve({}); } }, memory: { getInfo: function() { return Promise.resolve({}); } }, storage: { getInfo: function() { return Promise.resolve({}); } } };" +
        "}" +
        "if (!window.chrome.pointerLock) {" +
        "  window.chrome.pointerLock = { exitPointerLock: function(){}, screen: { width: 1920, height: 1080 } };" +
        "}" +
        "if (!window.chrome.vivaldi) window.chrome.vivaldi = null;" +
        "if (!window.chrome.session) {" +
        "  window.chrome.session = { installPage: function(){}, getPreset: function(){}, get: function(){}, set: function(){}, clear: function(){} };" +
        "}" +
        "if (!window.chrome.event) {" +
        "  window.chrome.event = function() { return { addListener: function(){}, removeListener: function(){}, hasListener: function(){}, getListenerKeys: function() { return []; } }; };" +
        "}";
    }
}
