import io.github.selenium.javaDriverless.JavaDriverless;

public class TestFingerprint {

    public static void main(String[] args) {
        JavaDriverless driver = new JavaDriverless();
        driver.get("https://www.google.com");
        driver.sleep(2);

        System.out.println("=== FINGERPRINT TESTS ===\n");

        // Canvas Fingerprint
        String canvasScript = "(function() { var canvas = document.createElement('canvas'); canvas.width = 200; canvas.height = 50; var ctx = canvas.getContext('2d'); ctx.textBaseline = 'top'; ctx.font = \"14px 'Arial'\"; ctx.fillStyle = '#f60'; ctx.fillRect(125, 1, 62, 20); ctx.fillStyle = '#069'; ctx.fillText('Cwm fjordbank gs', 2, 15); ctx.fillStyle = 'rgba(102, 224, 170, 0.7)'; ctx.fillText('Cwm fjordbank gs', 4, 17); return canvas.toDataURL(); })()";
        Object canvasResult = driver.executeScript(canvasScript);
        System.out.println("1. CANVAS FINGERPRINT:");
        System.out.println("   " + canvasResult);
        System.out.println();

        // WebGL Renderer
        String webglScript = "(function() { var canvas = document.createElement('canvas'); var gl = canvas.getContext('webgl'); if (!gl) return 'WebGL not supported'; var debugInfo = gl.getExtension('WEBGL_debug_renderer_info'); if (!debugInfo) return 'Debug info not available'; return gl.getParameter(debugInfo.UNMASKED_RENDERER_WEBGL); })()";
        Object webglResult = driver.executeScript(webglScript);
        System.out.println("2. WEBGL RENDERER:");
        System.out.println("   " + webglResult);
        System.out.println();

        // Audio Fingerprint
        String audioScript = "(function() { try { var audioCtx = new (window.AudioContext || window.webkitAudioContext)(); var oscillator = audioCtx.createOscillator(); var analyser = audioCtx.createAnalyser(); var gain = audioCtx.createGain(); var scriptProcessor = audioCtx.createScriptProcessor(4096, 1, 1); oscillator.type = 'triangle'; gain.gain.value = 0; oscillator.connect(analyser); analyser.connect(scriptProcessor); scriptProcessor.connect(gain); gain.connect(audioCtx.destination); oscillator.start(0); var frequencyData = new Uint8Array(analyser.frequencyBinCount); analyser.getByteFrequencyData(frequencyData); return frequencyData.slice(0, 10).join(','); } catch(e) { return 'Audio fingerprint error: ' + e.message; } })()";
        Object audioResult = driver.executeScript(audioScript);
        System.out.println("3. AUDIO FINGERPRINT:");
        System.out.println("   " + audioResult);
        System.out.println();

        // Screen Resolution
        String screenScript = "screen.width + 'x' + screen.height + 'x' + screen.colorDepth";
        Object screenResult = driver.executeScript(screenScript);
        System.out.println("4. SCREEN RESOLUTION:");
        System.out.println("   " + screenResult);
        System.out.println();

        // Timezone
        String tzScript = "Intl.DateTimeFormat().resolvedOptions().timeZone";
        Object tzResult = driver.executeScript(tzScript);
        System.out.println("5. TIMEZONE:");
        System.out.println("   " + tzResult);
        System.out.println();

        // Check for automation indicators
        System.out.println("=== AUTOMATION INDICATORS ===\n");

        // navigator.webdriver
        String webdriverScript = "String(navigator.webdriver)";
        Object webdriver = driver.executeScript(webdriverScript);
        System.out.println("navigator.webdriver: " + webdriver);

        // Chrome runtime
        String runtimeScript = "window.chrome ? window.chrome.runtime : 'not detected'";
        Object runtime = driver.executeScript(runtimeScript);
        System.out.println("window.chrome.runtime: " + runtime);

        // User agent
        String userAgentScript = "navigator.userAgent";
        Object userAgent = driver.executeScript(userAgentScript);
        System.out.println("User Agent: " + userAgent);

        // Chrome flags
        String chromeFlagsScript = "window.chrome ? window.chrome.loadTimes() : 'not detected'";
        Object chromeFlags = driver.executeScript(chromeFlagsScript);
        System.out.println("window.chrome.loadTimes(): " + chromeFlags);

        // WebDriver vendor
        String vendorScript = "navigator.vendor";
        Object vendor = driver.executeScript(vendorScript);
        System.out.println("navigator.vendor: " + vendor);

        driver.quit();
        System.out.println("\n✓ Teste concluído!");
    }
}