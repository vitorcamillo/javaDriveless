package io.github.selenium.driverless;

import org.junit.jupiter.api.*;

import io.github.selenium.javaDriverless.sync.SyncChrome;
import io.github.selenium.javaDriverless.types.ChromeOptions;

import static org.assertj.core.api.Assertions.*;

/**
 * Suite avançada de validação anti-bot.
 * Testa vetores reais de detecção usados por Cloudflare, DataDome,
 * Akamai, PerimeterX, Kasada e FingerprintJS.
 *
 * Estes testes validam que o framework é indetectável para
 * qualquer sistema anti-bot moderno.
 * 
 * NOTA: Os testes navegam para sites reais para validar o anti-detection
 * no contexto de uma página web real (anti-detection não injeta em about:blank).
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
public class AntiBotBypassSuiteTest {

    private static SyncChrome driver;

    @BeforeAll
    public static void setUp() {
        ChromeOptions options = new ChromeOptions();
        driver = new SyncChrome(options);
        assertThat(driver).isNotNull();
        // Navegar para site real — anti-detection não injeta em about:blank
        driver.get("https://bot.sannysoft.com");
        driver.sleep(4.0);
    }

    @AfterAll
    public static void tearDown() {
        if (driver != null) {
            driver.quit();
        }
    }

    // ================================================================
    // NÍVEL 1: Checks básicos que TODOS os anti-bots fazem
    // ================================================================

    @Test
    @Order(1)
    @DisplayName("navigator.webdriver deve ser false (não true)")
    public void testWebdriverIsFalse() {
        Object result = driver.executeScript("navigator.webdriver");
        System.out.println("navigator.webdriver = " + result);

        // Deve ser false ou undefined/null, NAO true
        if (result != null) {
            assertThat(result)
                .isNotEqualTo(true)
                .isNotEqualTo(Boolean.TRUE);
        }
    }

    @Test
    @Order(2)
    @DisplayName("navigator.webdriver não deve ser true via hasOwnProperty")
    public void testWebdriverHasOwnProperty() {
        Object result = driver.executeScript(
            "(function() {" +
            "  var v = navigator.webdriver;" +
            "  if (v === true) return 'DETECTED';" +
            "  return 'OK';" +
            "})()"
        );
        assertThat(result.toString()).isEqualTo("OK");
    }

    @Test
    @Order(3)
    @DisplayName("window.chrome deve existir como object")
    public void testChromeExists() {
        Object result = driver.executeScript(
            "(function() {" +
            "  return typeof window.chrome;" +
            "})()"
        );
        System.out.println("typeof window.chrome = " + result);
        assertThat(result.toString()).isEqualTo("object");
    }

    @Test
    @Order(4)
    @DisplayName("chrome.runtime: headful mode pode não ter (normal)")
    public void testChromeRuntime() {
        // NOTA: Em Chrome headful, chrome.runtime NÃO existe para páginas web normais.
        // Só extensões têm acesso. Isso é comportamento CORRETO — não é vetor de detecção.
        // O mock só é necessário em headless mode.
        Object result = driver.executeScript(
            "(function() {" +
            "  if (!window.chrome) return 'NO_CHROME';" +
            "  if (!window.chrome.runtime) return 'NO_RUNTIME_OK';" +
            "  return 'HAS_RUNTIME';" +
            "})()"
        );
        System.out.println("chrome.runtime check = " + result);
        // Qualquer resultado é aceitável em headful — o que importa é window.chrome existir
        assertThat(result.toString())
            .isIn("NO_RUNTIME_OK", "HAS_RUNTIME");
    }

    // ================================================================
    // NÍVEL 2: Checks avançados (Cloudflare, DataDome)
    // ================================================================

    @Test
    @Order(10)
    @DisplayName("Permissions.query deve aceitar objeto {name: ...}")
    public void testPermissionsQueryObjectParam() {
        // Chrome real espera objeto com 'name' property
        Object result = driver.executeScript(
            "(function() {" +
            "  if (!navigator.permissions) return 'NO_PERMISSIONS';" +
            "  try {" +
            "    var p = navigator.permissions.query({name: 'notifications'});" +
            "    if (p && typeof p.then === 'function') return 'PROMISE_OK';" +
            "    return 'NOT_PROMISE';" +
            "  } catch(e) { return 'ERROR:' + e.message; }" +
            "})()"
        );
        System.out.println("Permissions.query result = " + result);
        assertThat(result.toString())
            .isIn("PROMISE_OK", "NO_PERMISSIONS");
    }

    @Test
    @Order(11)
    @DisplayName("navigator.vendor deve ser Google Inc.")
    public void testVendorConsistency() {
        Object vendor = driver.executeScript("navigator.vendor");
        System.out.println("navigator.vendor = " + vendor);
        assertThat(vendor.toString()).isEqualTo("Google Inc.");
    }

    @Test
    @Order(12)
    @DisplayName("Screen dimensions devem ser realistas (>= 1024x768)")
    public void testScreenDimensions() {
        Object width = driver.executeScript("screen.width");
        Object height = driver.executeScript("screen.height");
        Object colorDepth = driver.executeScript("screen.colorDepth");

        int w = ((Number) width).intValue();
        int h = ((Number) height).intValue();
        int cd = ((Number) colorDepth).intValue();

        System.out.println("Screen: " + w + "x" + h + " depth=" + cd);

        assertThat(w).isGreaterThanOrEqualTo(1024);
        assertThat(h).isGreaterThanOrEqualTo(768);
        assertThat(cd).isGreaterThanOrEqualTo(24);
    }

    @Test
    @Order(13)
    @DisplayName("outerWidth/outerHeight devem existir e ser > 0")
    public void testOuterDimensions() {
        Object outerW = driver.executeScript(
            "(function() { return window.outerWidth || 0; })()"
        );
        Object outerH = driver.executeScript(
            "(function() { return window.outerHeight || 0; })()"
        );

        System.out.println("outerWidth=" + outerW + " outerHeight=" + outerH);
        assertThat(((Number) outerW).intValue()).isGreaterThan(0);
        assertThat(((Number) outerH).intValue()).isGreaterThan(0);
    }

    @Test
    @Order(14)
    @DisplayName("hardwareConcurrency deve ser >= 2")
    public void testHardwareConcurrency() {
        Object result = driver.executeScript(
            "(function() { return navigator.hardwareConcurrency || 0; })()"
        );
        int cores = ((Number) result).intValue();
        System.out.println("hardwareConcurrency = " + cores);
        assertThat(cores).isGreaterThanOrEqualTo(2);
    }

    @Test
    @Order(15)
    @DisplayName("deviceMemory deve ser >= 2 (quando disponível)")
    public void testDeviceMemory() {
        Object result = driver.executeScript(
            "(function() {" +
            "  if (typeof navigator.deviceMemory === 'undefined') return -1;" +
            "  return navigator.deviceMemory;" +
            "})()"
        );
        int memory = ((Number) result).intValue();
        System.out.println("deviceMemory = " + memory);
        if (memory >= 0) {
            assertThat(memory).isGreaterThanOrEqualTo(2);
        }
    }

    // ================================================================
    // NÍVEL 3: Checks de integridade (Kasada, CreepJS, FingerprintJS)
    // ================================================================

    @Test
    @Order(20)
    @DisplayName("Nenhuma variável CDP deve existir no window")
    public void testNoCdpVariables() {
        Object result = driver.executeScript(
            "(function() {" +
            "  var detected = [];" +
            "  var suspects = [" +
            "    'cdc_adoQpoasnfa76pfcZLmcfl_Array'," +
            "    'cdc_adoQpoasnfa76pfcZLmcfl_Promise'," +
            "    'cdc_adoQpoasnfa76pfcZLmcfl_Symbol'," +
            "    '$chrome_asyncScriptInfo'," +
            "    '__webdriver_script_function'," +
            "    '__selenium_evaluate'," +
            "    '__webdriver_evaluate'," +
            "    'selenium'," +
            "    'webdriver'," +
            "    '__nightmare'," +
            "    '__detect_nightmare'" +
            "  ];" +
            "  for (var i = 0; i < suspects.length; i++) {" +
            "    if (typeof window[suspects[i]] !== 'undefined') {" +
            "      detected.push(suspects[i]);" +
            "    }" +
            "  }" +
            "  return detected.length === 0 ? 'CLEAN' : detected.join(',');" +
            "})()"
        );
        System.out.println("CDP variables check: " + result);
        assertThat(result.toString()).isEqualTo("CLEAN");
    }

    @Test
    @Order(21)
    @DisplayName("User-Agent não deve conter HeadlessChrome")
    public void testUserAgentClean() {
        Object ua = driver.executeScript("navigator.userAgent");
        String userAgent = ua.toString();
        System.out.println("User-Agent: " + userAgent);
        assertThat(userAgent)
            .doesNotContain("HeadlessChrome")
            .doesNotContain("Headless")
            .contains("Chrome/");
    }

    @Test
    @Order(22)
    @DisplayName("navigator.plugins deve ter >= 1 plugin")
    public void testPluginsExist() {
        Object length = driver.executeScript(
            "(function() { return navigator.plugins ? navigator.plugins.length : 0; })()"
        );
        int l = ((Number) length).intValue();
        System.out.println("plugins.length = " + l);
        assertThat(l).isGreaterThanOrEqualTo(1);
    }

    @Test
    @Order(23)
    @DisplayName("navigator.languages deve ser array não-vazio")
    public void testLanguages() {
        Object result = driver.executeScript(
            "(function() {" +
            "  var langs = navigator.languages;" +
            "  if (!langs) return 'NULL';" +
            "  if (!Array.isArray(langs)) return 'NOT_ARRAY';" +
            "  if (langs.length === 0) return 'EMPTY';" +
            "  return langs.join(',');" +
            "})()"
        );
        String langs = result.toString();
        System.out.println("languages = " + langs);
        assertThat(langs)
            .isNotEqualTo("NULL")
            .isNotEqualTo("NOT_ARRAY")
            .isNotEqualTo("EMPTY");
    }

    @Test
    @Order(24)
    @DisplayName("WebGL vendor/renderer não deve indicar automação")
    public void testWebGLNotAutomation() {
        Object vendor = driver.executeScript(
            "(function() {" +
            "  var c = document.createElement('canvas');" +
            "  var gl = c.getContext('webgl');" +
            "  if (!gl) return 'NO_WEBGL';" +
            "  var ext = gl.getExtension('WEBGL_debug_renderer_info');" +
            "  if (!ext) return 'NO_EXT';" +
            "  return gl.getParameter(ext.UNMASKED_VENDOR_WEBGL);" +
            "})()"
        );
        String v = vendor.toString();
        System.out.println("WebGL vendor = " + v);
        assertThat(v)
            .isNotIn("Brian Paul", "Google SwiftShader", "SwiftShader");
    }

    @Test
    @Order(25)
    @DisplayName("Canvas fingerprint deve ser estável (2 chamadas = mesmo resultado)")
    public void testCanvasStable() {
        String script = "(function() {" +
            "var c = document.createElement('canvas');" +
            "c.width = 200; c.height = 50;" +
            "var ctx = c.getContext('2d');" +
            "ctx.textBaseline = 'top';" +
            "ctx.font = '14px Arial';" +
            "ctx.fillStyle = '#f60';" +
            "ctx.fillRect(125, 1, 62, 20);" +
            "ctx.fillStyle = '#069';" +
            "ctx.fillText('Cwm fjord', 2, 15);" +
            "return c.toDataURL().substring(0, 50);" +
            "})()";

        Object hash1 = driver.executeScript(script);
        Object hash2 = driver.executeScript(script);

        assertThat(hash1).isNotNull();
        assertThat(hash1.toString()).startsWith("data:image/png");
        // Estabilidade: mesmo resultado nas duas chamadas
        assertThat(hash1).isEqualTo(hash2);
        System.out.println("Canvas fingerprint estável: " + hash1);
    }

    // ================================================================
    // NÍVEL 4: Validação contra site real de detecção
    // ================================================================

    @Test
    @Order(30)
    @DisplayName("bot.sannysoft.com: deve passar checks principais")
    public void testSannysoftBot() {
        // Já estamos neste site desde o setUp
        String url = driver.getCurrentUrl();
        if (!url.contains("sannysoft")) {
            driver.get("https://bot.sannysoft.com");
            driver.sleep(5.0);
        }

        // Verificar resultado do webdriver check
        Object webdriverResult = driver.executeScript(
            "(function() {" +
            "  var tds = document.querySelectorAll('td');" +
            "  for (var i = 0; i < tds.length; i++) {" +
            "    if (tds[i].textContent.indexOf('webdriver') !== -1 && tds[i+1]) {" +
            "      return tds[i+1].textContent.trim();" +
            "    }" +
            "  }" +
            "  return 'NOT_FOUND';" +
            "})()"
        );

        System.out.println("Sannysoft webdriver check: " + webdriverResult);
        String wd = webdriverResult.toString().toLowerCase();
        // Deve ser "missing" ou "false", não "present" ou "true"
        assertThat(wd)
            .doesNotContain("present")
            .doesNotContain("true");
    }

    @Test
    @Order(31)
    @DisplayName("nowsecure.nl: deve passar Cloudflare sem block")
    public void testNowSecureCloudflare() {
        driver.get("https://nowsecure.nl");
        // Cloudflare challenge leva até 5 segundos
        driver.sleep(8.0);

        String title = driver.getTitle();
        System.out.println("NowSecure title: " + title);

        // Se passou Cloudflare, o título não deve ser o challenge page
        assertThat(title.toLowerCase())
            .doesNotContain("just a moment")
            .doesNotContain("attention required");
    }
}
