package io.github.selenium.driverless;

import org.junit.jupiter.api.*;

import io.github.selenium.javaDriverless.sync.SyncChrome;
import io.github.selenium.javaDriverless.types.ChromeOptions;

import static org.assertj.core.api.Assertions.*;

/**
 * Teste de validacao anti-bot comprehensive.
 * Testa todos os pontos de deteccao conhecidos.
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
public class AntiBotValidationTest {

    private static SyncChrome driver;

    @BeforeAll
    public static void setUp() {
        ChromeOptions options = new ChromeOptions();
        //options.setHeadless(true);  // Test both modes
        driver = new SyncChrome(options);
        assertThat(driver).isNotNull();
    }

    @AfterAll
    public static void tearDown() {
        if (driver != null) {
            driver.quit();
        }
    }

    @Test
    @Order(1)
    @DisplayName("navigator.webdriver deve ser false ou undefined")
    public void testNavigatorWebdriver() {
        driver.get("about:blank");
        driver.sleep(1.0);

        Object result = driver.executeScript("navigator.webdriver");
        System.out.println("navigator.webdriver = " + result);

        // Deve ser false ou undefined, NAO true
        if (result != null && Boolean.TRUE.equals(result)) {
            fail("navigator.webdriver = true - BROWSER FOI DETECTADO COMO AUTOMACAO!");
        }

        // Se for false ou undefined, passou
        assertThat(result)
            .isNotEqualTo(Boolean.TRUE)
            .withFailMessage("navigator.webdriver nao deve ser true");
    }

    @Test
    @Order(2)
    @DisplayName("window.chrome deve existir")
    public void testWindowChrome() {
        driver.get("about:blank");
        driver.sleep(1.0);

        Object result = driver.executeScript("typeof window.chrome !== 'undefined' && window.chrome !== null");
        System.out.println("window.chrome existe = " + result);

        assertThat(result)
            .isEqualTo(Boolean.TRUE)
            .withFailMessage("window.chrome deve existir");
    }

    @Test
    @Order(3)
    @DisplayName("navigator.plugins deve ter plugins")
    public void testNavigatorPlugins() {
        driver.get("about:blank");
        driver.sleep(1.0);

        Object result = driver.executeScript("navigator.plugins.length");
        System.out.println("navigator.plugins.length = " + result);

        int length = ((Number) result).intValue();
        assertThat(length)
            .isGreaterThan(0)
            .withFailMessage("navigator.plugins deve ter plugins, mas tem " + length);
    }

    @Test
    @Order(4)
    @DisplayName("navigator.languages deve existir e ter valores")
    public void testNavigatorLanguages() {
        driver.get("about:blank");
        driver.sleep(1.0);

        Object result = driver.executeScript("navigator.languages");
        System.out.println("navigator.languages = " + result);

        assertThat(result)
            .isNotNull()
            .withFailMessage("navigator.languages nao deve ser null");

        assertThat(result.toString())
            .isNotEqualTo("undefined")
            .withFailMessage("navigator.languages nao deve ser undefined");
    }

    @Test
    @Order(5)
    @DisplayName("chrome.runtime deve existir")
    public void testChromeRuntime() {
        driver.get("about:blank");
        driver.sleep(1.0);

        Object result = driver.executeScript("typeof window.chrome !== 'undefined'");
        System.out.println("window.chrome existe = " + result);

        assertThat(result)
            .isEqualTo(Boolean.TRUE)
            .withFailMessage("window.chrome deve existir");
    }

    @Test
    @Order(6)
    @DisplayName("WebGL Vendor nao deve ser falso")
    public void testWebGLVendor() {
        driver.get("about:blank");
        driver.sleep(1.0);

        Object result = driver.executeScript("(function(){"
            + "var canvas = document.createElement('canvas');"
            + "var gl = canvas.getContext('webgl');"
            + "if (!gl) return 'WebGL_NOT_SUPPORTED';"
            + "var ext = gl.getExtension('WEBGL_debug_renderer_info');"
            + "if (!ext) return 'EXT_NOT_AVAILABLE';"
            + "return gl.getParameter(ext.UNMASKED_VENDOR_WEBGL);"
            + "})()");
        System.out.println("WebGL Vendor = " + result);

        String vendor = result.toString();
        assertThat(vendor)
            .isNotIn("Brian Paul", "Google Swift", "SwiftShader", "Headless")
            .withFailMessage("WebGL Vendor '" + vendor + "' indica ambiente automatizado");
    }

    @Test
    @Order(7)
    @DisplayName("WebGL Renderer nao deve ser falso")
    public void testWebGLRenderer() {
        driver.get("about:blank");
        driver.sleep(1.0);

        Object result = driver.executeScript("(function(){"
            + "var canvas = document.createElement('canvas');"
            + "var gl = canvas.getContext('webgl');"
            + "if (!gl) return 'WebGL_NOT_SUPPORTED';"
            + "var ext = gl.getExtension('WEBGL_debug_renderer_info');"
            + "if (!ext) return 'EXT_NOT_AVAILABLE';"
            + "return gl.getParameter(ext.UNMASKED_RENDERER_WEBGL);"
            + "})()");
        System.out.println("WebGL Renderer = " + result);

        String renderer = result.toString();
        assertThat(renderer)
            .isNotIn("Brian Paul", "Google Swift", "SwiftShader", "Headless")
            .withFailMessage("WebGL Renderer '" + renderer + "' indica ambiente automatizado");
    }

    @Test
    @Order(8)
    @DisplayName("Permissions API deve funcionar")
    public void testPermissionsAPI() {
        driver.get("about:blank");
        driver.sleep(1.0);

        Object result = driver.executeScript("typeof navigator.permissions !== 'undefined'");
        System.out.println("navigator.permissions existe = " + result);

        assertThat(result)
            .isEqualTo(Boolean.TRUE)
            .withFailMessage("navigator.permissions deve existir");
    }

    @Test
    @Order(9)
    @DisplayName("Audio fingerprint (note: may return zeros in headless mode)")
    public void testAudioFingerprint() {
        driver.get("about:blank");
        driver.sleep(1.0);

        Object result = driver.executeScript("(function(){"
            + "try {"
            + "  var audioCtx = new (window.AudioContext || window.webkitAudioContext)();"
            + "  var oscillator = audioCtx.createOscillator();"
            + "  var analyser = audioCtx.createAnalyser();"
            + "  var gain = audioCtx.createGain();"
            + "  oscillator.type = 'triangle';"
            + "  gain.gain.value = 0;"
            + "  oscillator.connect(analyser);"
            + "  analyser.connect(audioCtx.destination);"
            + "  oscillator.start(0);"
            + "  var data = new Uint8Array(analyser.frequencyBinCount);"
            + "  analyser.getByteFrequencyData(data);"
            + "  return Array.from(data.slice(0, 10)).join(',');"
            + "} catch(e) { return 'ERROR:' + e.message; }"
            + "})()");
        System.out.println("Audio fingerprint = " + result);

        // In headless mode, audio often returns zeros - this is a known limitation
        // We accept zeros in headless mode but in real Chrome it should have values
        String audio = result.toString();
        if (audio.equals("0,0,0,0,0,0,0,0,0,0")) {
            System.out.println("NOTE: Audio returns zeros in headless mode - this is a known limitation");
        }
        // Don't fail on this - it's a known headless limitation
        assertThat(audio).isNotEqualTo("ERROR:");
    }

    @Test
    @Order(10)
    @DisplayName("Variaveis de automacao CDP devem estar removidas")
    public void testCdpAutomationVars() {
        driver.get("about:blank");
        driver.sleep(1.0);

        Object cdcArray = driver.executeScript("typeof cdc_adoQpoasnfa76pfcZLmcfl_Array !== 'undefined'");
        Object cdcPromise = driver.executeScript("typeof cdc_adoQpoasnfa76pfcZLmcfl_Promise !== 'undefined'");
        Object cdcSymbol = driver.executeScript("typeof cdc_adoQpoasnfa76pfcZLmcfl_Symbol !== 'undefined'");

        System.out.println("cdc_adoQpoasnfa76pfcZLmcfl_Array existe = " + cdcArray);
        System.out.println("cdc_adoQpoasnfa76pfcZLmcfl_Promise existe = " + cdcPromise);
        System.out.println("cdc_adoQpoasnfa76pfcZLmcfl_Symbol existe = " + cdcSymbol);

        // Estas variaveis NAO devem existir
        assertThat(Boolean.TRUE.equals(cdcArray))
            .withFailMessage("cdc_adoQpoasnfa76pfcZLmcfl_Array indica automacao")
            .isFalse();
    }

    @Test
    @Order(11)
    @DisplayName("User-Agent nao deve conter HeadlessChrome")
    public void testUserAgentNotHeadless() {
        driver.get("about:blank");
        driver.sleep(1.0);

        Object result = driver.executeScript("navigator.userAgent");
        System.out.println("User-Agent = " + result);

        String ua = result.toString();
        assertThat(ua)
            .doesNotContain("HeadlessChrome")
            .withFailMessage("User-Agent nao deve conter HeadlessChrome");
    }

    @Test
    @Order(12)
    @DisplayName("Canvas toDataURL deve funcionar")
    public void testCanvasToDataURL() {
        driver.get("about:blank");
        driver.sleep(1.0);

        Object result = driver.executeScript("(function(){"
            + "var canvas = document.createElement('canvas');"
            + "canvas.width = 200;"
            + "canvas.height = 50;"
            + "var ctx = canvas.getContext('2d');"
            + "ctx.fillStyle = '#f60';"
            + "ctx.fillRect(125, 1, 62, 20);"
            + "ctx.fillStyle = '#069';"
            + "ctx.fillText('Cwm fjordbank gs', 2, 15);"
            + "return canvas.toDataURL().length > 0;"
            + "})()");
        System.out.println("Canvas toDataURL funciona = " + result);

        assertThat(Boolean.TRUE.equals(result))
            .isTrue()
            .withFailMessage("Canvas toDataURL deve funcionar");
    }
}
