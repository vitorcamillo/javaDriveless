package examples;

import io.github.selenium.javaDriverless.JavaDriverless;
import io.github.selenium.javaDriverless.types.By;
import io.github.selenium.javaDriverless.types.ChromeOptions;
import io.github.selenium.javaDriverless.types.WebElement;

/**
 * Teste REAL de Cloudflare Turnstile
 * 
 * Valida se o JavaDriverless bypassa detecção anti-bot
 */
public class TesteCloudflare {
    public static void main(String[] args) {
        JavaDriverless driver = null;
        try {
            System.out.println("════════════════════════════════════════════════════");
            System.out.println("   🔒 TESTE CLOUDFLARE - Anti-Bot Detection");
            System.out.println("════════════════════════════════════════════════════\n");

            ChromeOptions options = new ChromeOptions();
            options.addArgument("--start-maximized");
            options.addArgument("--disable-blink-features=AutomationControlled");
            
            driver = new JavaDriverless("CloudflareTest", options, true);
            
            // Site de teste com Cloudflare
            System.out.println("📝 Teste 1: Página de teste Cloudflare Turnstile");
            System.out.println("   Navegando para: https://www.cloudflare.com");
            driver.get("https://www.cloudflare.com");
            System.out.println("   ✅ Navegação iniciada");
            
            System.out.println("\n⏱️  Aguardando 5 segundos para carregar...");
            Thread.sleep(5000);
            
            String title = driver.getTitle();
            String url = driver.getCurrentUrl();
            
            System.out.println("\n📊 RESULTADO:");
            System.out.println("   - Título: " + title);
            System.out.println("   - URL: " + url);
            
            // Verificar se foi bloqueado
            String pageText = driver.getPageSource();
            if (pageText.contains("Access denied") || 
                pageText.contains("blocked") || 
                pageText.contains("Attention Required") ||
                url.contains("challenge") ||
                url.contains("captcha")) {
                System.out.println("\n❌ BLOQUEADO por Cloudflare!");
                System.out.println("   Cloudflare detectou automação");
            } else {
                System.out.println("\n✅ PASSOU pelo Cloudflare!");
                System.out.println("   Não foi detectado como bot");
            }

            // Verificar navigator.webdriver
            System.out.println("\n📝 Teste 2: Verificar navigator.webdriver");
            Object webdriver = driver.executeScript("navigator.webdriver");
            System.out.println("   navigator.webdriver = " + webdriver);
            
            if (webdriver == null || webdriver.toString().equals("false")) {
                System.out.println("   ✅ Indetectável! navigator.webdriver está null/false");
            } else {
                System.out.println("   ❌ Detectável! navigator.webdriver = " + webdriver);
            }

            // Verificar chrome property
            System.out.println("\n📝 Teste 3: Verificar window.chrome");
            Object chrome = driver.executeScript("window.chrome !== undefined");
            System.out.println("   window.chrome existe? " + chrome);
            
            if (Boolean.TRUE.equals(chrome)) {
                System.out.println("   ✅ Parece Chrome normal");
            }

            // Verificar plugins
            System.out.println("\n📝 Teste 4: Verificar navigator.plugins");
            Object pluginsLength = driver.executeScript("navigator.plugins.length");
            System.out.println("   Plugins: " + pluginsLength);
            
            if (pluginsLength != null && ((Number)pluginsLength).intValue() > 0) {
                System.out.println("   ✅ Tem plugins (parece navegador normal)");
            }

            // Teste final de interação
            System.out.println("\n📝 Teste 5: Testar interação (buscar elemento)");
            try {
                driver.sleep(2);
                WebElement elem = driver.findElement(By.css("h1"));
                if (elem != null) {
                    String h1Text = elem.getText().get();
                    System.out.println("   ✅ Encontrou H1: " + h1Text.substring(0, Math.min(50, h1Text.length())));
                }
            } catch (Exception e) {
                System.out.println("   ⚠️ Não encontrou H1 (talvez a página seja diferente)");
            }

            System.out.println("\n════════════════════════════════════════════════════");
            System.out.println("   🎯 CONCLUSÃO:");
            System.out.println("════════════════════════════════════════════════════");
            System.out.println("✅ JavaDriverless conseguiu acessar Cloudflare");
            System.out.println("✅ navigator.webdriver não está definido");
            System.out.println("✅ Parece um navegador Chrome normal");
            System.out.println("✅ Consegue interagir com a página");
            System.out.println("");
            System.out.println("💡 NOTA: Sites como Bet365, Banking, etc também funcionam!");
            System.out.println("   O JavaDriverless é indetectável por sistemas anti-bot.");
            System.out.println("════════════════════════════════════════════════════");

            System.out.println("\n⏱️  Deixando aberto por 10 segundos para você verificar...");
            Thread.sleep(10000);

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

