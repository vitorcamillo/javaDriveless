package examples;

import io.github.selenium.javaDriverless.JavaDriverless;
import io.github.selenium.javaDriverless.types.By;
import io.github.selenium.javaDriverless.types.ChromeOptions;
import io.github.selenium.javaDriverless.types.WebElement;

/**
 * Teste COMPLETO de anti-detecção - todas propriedades
 */
public class TesteAntiDeteccaoCompleto {
    public static void main(String[] args) {
        JavaDriverless driver = null;
        try {
            System.out.println("════════════════════════════════════════════════════");
            System.out.println("   🔒 TESTE ANTI-DETECÇÃO COMPLETO");
            System.out.println("════════════════════════════════════════════════════\n");

            ChromeOptions options = new ChromeOptions();
            options.addArgument("--start-maximized");
            options.addArgument("--disable-blink-features=AutomationControlled");

            driver = new JavaDriverless("AntiDetecaoTest", options, true);

            // Site com Cloudflare ATIVO - httpbin.org
            String url = "https://www.nowsecure.nl";
            System.out.println("📝 Navegando para: " + url);
            driver.get(url);

            System.out.println("\n⏱️  Aguardando 8 segundos para carregar...");
            Thread.sleep(8000);

            String title = driver.getTitle();
            String currentUrl = driver.getCurrentUrl();

            System.out.println("\n📊 RESULTADO:");
            System.out.println("   - Título: " + title);
            System.out.println("   - URL: " + currentUrl);

            String script = """
                (function() {
                  var results = [];
                  results.push({name: 'navigator.webdriver', value: String(navigator.webdriver)});
                  results.push({name: 'navigator.plugins.length', value: String(navigator.plugins.length)});
                  results.push({name: 'navigator.languages', value: String(navigator.languages)});
                  results.push({name: 'window.chrome', value: String(window.chrome)});
                  results.push({name: 'chrome.runtime', value: String(chrome.runtime)});
                  results.push({name: 'Permissions', value: String(navigator.permissions)});
                  var canvas = document.createElement('canvas');
                  var gl = canvas.getContext('webgl') || canvas.getContext('experimental-webgl');
                  if (gl) {
                    var ext = gl.getExtension('WEBGL');
                    if (ext) {
                      results.push({name: 'WebGLVendor', value: String(gl.getParameter(ext.UNMASKED_VENDOR_WEBGL))});
                      results.push({name: 'WebGLRenderer', value: String(gl.getParameter(ext.UNMASKED_RENDERER_WEBGL))});
                    } else {
                      results.push({name: 'WebGLVendor', value: 'ext_null'});
                      results.push({name: 'WebGLRenderer', value: 'ext_null'});
                    }
                  } else {
                    results.push({name: 'WebGLVendor', value: 'gl_null'});
                    results.push({name: 'WebGLRenderer', value: 'gl_null'});
                  }
                  results.push({name: 'userAgent', value: String(navigator.userAgent)});
                  results.push({name: 'platform', value: String(navigator.platform)});
                  return results;
                })()
                """;

            System.out.println("\n📝 Executando verificação de anti-detecção...\n");

            Object result = driver.executeScript(script);

            System.out.println("════════════════════════════════════════════════════");
            System.out.println("   RESULTADOS ANTI-DETECÇÃO:");
            System.out.println("════════════════════════════════════════════════════\n");

            System.out.println(result.toString());

            System.out.println("\n════════════════════════════════════════════════════");
            System.out.println("   🎯 CONCLUSÃO:");
            System.out.println("════════════════════════════════════════════════════");

            System.out.println("\n⏱️  Deixando aberto por 15 segundos para você verificar...");
            Thread.sleep(15000);

        } catch (Exception e) {
            System.err.println("\n❌ ERRO: " + e.getMessage());
            e.printStackTrace();
        } finally {
            try {
                if (driver != null) {
                    driver.quit();
                }
            } catch (Exception e) {
                // Ignorar
            }
        }
    }
}
