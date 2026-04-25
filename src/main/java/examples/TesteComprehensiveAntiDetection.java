package examples;

import io.github.selenium.javaDriverless.sync.SyncChrome;
import io.github.selenium.javaDriverless.types.ChromeOptions;

/**
 * Teste abrangente de anti-detecção em TODOS os sites de检测 conhecidos.
 *
 * Sites testados:
 * 1. bot.sannysoft.com - Principal teste de detecção de bots
 * 2. pixelscan.net - Fingerprint e bot checker
 * 3. browserscan.net - Hardware, browser, bot detection
 * 4. areyouheadless (antoinevastel) - Detecção de headless browser
 * 5. browserleaks.com - Canvas, WebGL, Font fingerprinting
 * 6. iphey.com - IP, fingerprint, bot detection
 * 7. deviceinfo.me - Canvas, WebGL, Audio, WebRTC
 * 8. nowsecure.nl - Site real com Cloudflare
 */
public class TesteComprehensiveAntiDetection {

    public static void main(String[] args) {
        System.out.println("════════════════════════════════════════════════════════════════");
        System.out.println("   🔒 TESTE ABRANGENTE ANTI-DETECÇÃO - JavaDriverless");
        System.out.println("════════════════════════════════════════════════════════════════");
        System.out.println("   Data: 13/04/2026");
        System.out.println("════════════════════════════════════════════════════════════════\n");

        ChromeOptions options = new ChromeOptions();

        try (SyncChrome driver = new SyncChrome(options)) {
            // Verificações internas primeiro
            runInternalValidation(driver);

            System.out.println("\n" + "═".repeat(64));
            System.out.println("   NAVEGANDO PARA SITES DE TESTE");
            System.out.println("═".repeat(64) + "\n");

            // Teste 1: Bot Sannysoft
            testSite(driver, "https://bot.sannysoft.com", "Bot Sannysoft", 10);

            // Teste 2: Are You Headless
            testSite(driver, "https://arh.antoinevastel.com/bots/areyouheadless", "Are You Headless", 8);

            // Teste 3: PixelScan
            testSite(driver, "https://pixelscan.net", "PixelScan", 10);

            // Teste 4: BrowserScan
            testSite(driver, "https://www.browserscan.net", "BrowserScan", 10);

            // Teste 5: BrowserLeaks
            testSite(driver, "https://browserleaks.com", "BrowserLeaks", 8);

            // Teste 6: IPHey
            testSite(driver, "https://iphey.com", "IPHey", 8);

            // Teste 7: DeviceInfo
            testSite(driver, "https://www.deviceinfo.me", "DeviceInfo", 8);

            // Teste 8: NowSecure (Cloudflare real)
            testSite(driver, "https://nowsecure.nl", "NowSecure (Cloudflare)", 12);

            // Teste 9: NoPecha Turnstile
            testSite(driver, "https://nopecha.com/demo/turnstile", "NoPecha Turnstile", 10);

            // Resumo final
            System.out.println("\n" + "═".repeat(64));
            System.out.println("   ✅ TESTES COMPLETOS!");
            System.out.println("═".repeat(64));
            System.out.println("\nVerifique visualmente cada site para confirmar indetectabilidade.");
            System.out.println("Se todos mostrarem verde/success = JavaDriverless é INDETECTÁVEL!");

        } catch (Exception e) {
            System.err.println("❌ ERRO: " + e.getMessage());
            e.printStackTrace();
        }
    }

    private static void runInternalValidation(SyncChrome driver) {
        System.out.println("─────────────────────────────────────────────────────────────");
        System.out.println("   VERIFICAÇÕES INTERNAS DE ANTI-DETECÇÃO");
        System.out.println("─────────────────────────────────────────────────────────────\n");

        driver.get("about:blank");
        driver.sleep(2.0);

        // Test all detection points
        String[] checks = {
            "navigator.webdriver === false || navigator.webdriver === undefined",
            "typeof window.chrome !== 'undefined'",
            "navigator.plugins.length > 0",
            "navigator.languages && navigator.languages.length > 0",
            "typeof navigator.permissions !== 'undefined'",
            "typeof window.chrome.runtime !== 'undefined'",
            "navigator.hardwareConcurrency > 0",
            "navigator.deviceMemory > 0",
            "screen.width > 0 && screen.height > 0",
            "!window.cdc_adoQpoasnfa76pfcZLmcfl_Array",
            "!window.cdc_adoQpoasnfa76pfcZLmcfl_Promise"
        };

        String[] names = {
            "navigator.webdriver = false",
            "window.chrome existe",
            "navigator.plugins.length > 0",
            "navigator.languages existe",
            "navigator.permissions existe",
            "chrome.runtime existe",
            "hardwareConcurrency > 0",
            "deviceMemory > 0",
            "screen.width/height válido",
            "cdc_Array removida",
            "cdc_Promise removida"
        };

        int passed = 0;
        for (int i = 0; i < checks.length; i++) {
            try {
                Object result = driver.executeScript(checks[i]);
                boolean success = Boolean.TRUE.equals(result) || "true".equals(String.valueOf(result));
                String status = success ? "✅" : "❌";
                System.out.printf("   %s %s%n", status, names[i]);
                if (success) passed++;
            } catch (Exception e) {
                System.out.printf("   ❌ %s (erro: %s)%n", names[i], e.getMessage());
            }
        }

        // WebGL check
        try {
            Object webgl = driver.executeScript(
                "(function(){ " +
                "  var c = document.createElement('canvas'); " +
                "  var g = c.getContext('webgl'); " +
                "  if (!g) return 'no_webgl'; " +
                "  var ext = g.getExtension('WEBGL_debug_renderer_info'); " +
                "  if (!ext) return 'no_ext'; " +
                "  var vendor = g.getParameter(ext.UNMASKED_VENDOR_WEBGL); " +
                "  var renderer = g.getParameter(ext.UNMASKED_RENDERER_WEBGL); " +
                "  return vendor + '|' + renderer; " +
                "})()"
            );
            String webglStr = String.valueOf(webgl);
            if (webglStr.contains("Brian Paul") || webglStr.contains("SwiftShader") || webglStr.contains("Headless")) {
                System.out.println("   ❌ WebGL vendor/renderer detectável: " + webglStr);
            } else {
                System.out.println("   ✅ WebGL spoofed: " + webglStr);
                passed++;
            }
        } catch (Exception e) {
            System.out.println("   ⚠️  WebGL não disponível (headless limitation)");
        }

        // User-Agent check
        try {
            Object ua = driver.executeScript("navigator.userAgent");
            String uaStr = String.valueOf(ua);
            if (uaStr.contains("HeadlessChrome")) {
                System.out.println("   ❌ User-Agent contém HeadlessChrome");
            } else {
                System.out.println("   ✅ User-Agent válido: " + uaStr.substring(0, Math.min(60, uaStr.length())) + "...");
                passed++;
            }
        } catch (Exception e) {
            System.out.println("   ❌ User-Agent check falhou");
        }

        System.out.printf("\n   RESULTADO INTERNO: %d/%d verificações passaram%n", passed, checks.length + 3);
    }

    private static void testSite(SyncChrome driver, String url, String name, int waitSeconds) {
        System.out.println("─────────────────────────────────────────────────────────────");
        System.out.printf("   📝 %s%n", name);
        System.out.printf("   URL: %s%n", url);
        System.out.println("─────────────────────────────────────────────────────────────\n");

        try {
            driver.get(url);
            System.out.println("   ✅ Página carregou");

            driver.sleep(waitSeconds);

            String title = driver.getCurrentUrl();
            System.out.println("   ✅ Aguardou " + waitSeconds + "s");
            System.out.println("   ℹ️  Título/URL: " + title.substring(0, Math.min(50, title.length())));

            System.out.println("\n   👀 VERIFIQUE VISUALMENTE:");
            System.out.println("   - Se indicador estiver VERDE = indetectável");
            System.out.println("   - Se indicador estiver VERMELHO = detectável");

        } catch (Exception e) {
            System.out.println("   ❌ ERRO: " + e.getMessage());
        }

        System.out.println();
    }
}