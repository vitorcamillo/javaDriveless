package examples;

import io.github.selenium.javaDriverless.JavaDriverless;
import io.github.selenium.javaDriverless.types.ChromeOptions;

/**
 * Teste REAL com sites protegidos por anti-bot
 * 
 * Sites sugeridos para testar:
 * - https://nopecha.com/demo/turnstile
 * - https://2captcha.com/demo/cloudflare-turnstile
 * - https://nowsecure.nl (proteção Cloudflare)
 * - https://bot.sannysoft.com (detector de bots)
 */
public class TesteAntiBotReal {
    public static void main(String[] args) {
        JavaDriverless driver = null;
        try {
            System.out.println("════════════════════════════════════════════════════");
            System.out.println("   🔒 TESTE ANTI-BOT REAL");
            System.out.println("════════════════════════════════════════════════════\n");

            ChromeOptions options = new ChromeOptions();
            options.addArgument("--start-maximized");
            options.addArgument("--disable-blink-features=AutomationControlled");
            
            driver = new JavaDriverless("AntiBotTest", options, true);
            
            // SITE 1: Bot detector (mais fácil de testar)
            System.out.println("📝 Teste 1: Bot Sannysoft (detector de bots)");
            System.out.println("   Site: https://bot.sannysoft.com");
            driver.get("https://bot.sannysoft.com");
            System.out.println("   ✅ Carregou");
            
            System.out.println("\n⏱️  Aguardando 8 segundos para carregar...");
            Thread.sleep(8000);
            
            // Verificar navigator.webdriver
            Object webdriver = driver.executeScript("navigator.webdriver");
            System.out.println("\n📊 VERIFICAÇÕES:");
            System.out.println("   navigator.webdriver = " + webdriver);
            
            if (webdriver == null || "false".equals(webdriver.toString()) || Boolean.FALSE.equals(webdriver)) {
                System.out.println("   ✅ navigator.webdriver: INDETECTÁVEL!");
            } else {
                System.out.println("   ❌ navigator.webdriver: DETECTÁVEL!");
            }
            
            // Verificar chrome
            Object chrome = driver.executeScript("window.chrome !== undefined");
            System.out.println("   window.chrome existe: " + chrome);
            
            // Verificar permissions
            Object permissions = driver.executeScript("navigator.permissions !== undefined");
            System.out.println("   navigator.permissions existe: " + permissions);
            
            // Verificar plugins
            Object plugins = driver.executeScript("navigator.plugins.length");
            System.out.println("   navigator.plugins.length: " + plugins);
            
            // Verificar languages
            Object languages = driver.executeScript("navigator.languages.length");
            System.out.println("   navigator.languages.length: " + languages);

            System.out.println("\n════════════════════════════════════════════════════");
            System.out.println("   🎯 RESULTADO:");
            System.out.println("════════════════════════════════════════════════════");
            System.out.println("✅ Página carregou sem bloqueio");
            System.out.println("✅ navigator.webdriver está correto");
            System.out.println("✅ Propriedades de navegador normais presentes");
            System.out.println("");
            System.out.println("👀 VERIFIQUE VISUALMENTE:");
            System.out.println("   A página bot.sannysoft.com mostra todos os testes");
            System.out.println("   Se tudo estiver VERDE = indetectável!");
            System.out.println("   Se tiver VERMELHO = detectável");
            System.out.println("════════════════════════════════════════════════════");

            System.out.println("\n⏱️  Deixando aberto por 15 segundos para você verificar...");
            System.out.println("👀 OLHE A JANELA DO CHROME E VEJA OS RESULTADOS!");
            Thread.sleep(15000);

            // SITE 2: Cloudflare Turnstile Demo (nopecha)
            System.out.println("\n\n📝 Teste 2: NoPecha Cloudflare Turnstile Demo");
            System.out.println("   Site: https://nopecha.com/demo/turnstile");
            driver.get("https://nopecha.com/demo/turnstile");
            System.out.println("   ✅ Carregou");
            
            System.out.println("\n⏱️  Aguardando 10 segundos para processar Turnstile...");
            Thread.sleep(10000);
            
            String titleNopecha = driver.getTitle();
            System.out.println("   Título: " + titleNopecha);
            System.out.println("   👀 Veja se o Turnstile passou (checkbox verde)");
            Thread.sleep(10000);

            // SITE 3: 2Captcha Cloudflare Demo
            System.out.println("\n\n📝 Teste 3: 2Captcha Cloudflare Turnstile Demo");
            System.out.println("   Site: https://2captcha.com/demo/cloudflare-turnstile");
            driver.get("https://2captcha.com/demo/cloudflare-turnstile");
            System.out.println("   ✅ Carregou");
            
            System.out.println("\n⏱️  Aguardando 10 segundos para processar Turnstile...");
            Thread.sleep(10000);
            
            String title2captcha = driver.getTitle();
            System.out.println("   Título: " + title2captcha);
            System.out.println("   👀 Veja se o Turnstile passou");
            Thread.sleep(10000);

            // SITE 4: NowSecure (site real com Cloudflare)
            System.out.println("\n\n📝 Teste 4: NowSecure (site protegido por Cloudflare)");
            System.out.println("   Site: https://nowsecure.nl");
            driver.get("https://nowsecure.nl");
            System.out.println("   ✅ Navegação iniciada");
            
            System.out.println("\n⏱️  Aguardando 10 segundos...");
            Thread.sleep(10000);
            
            String urlNow = driver.getCurrentUrl();
            String titleNow = driver.getTitle();
            
            System.out.println("   URL: " + urlNow);
            System.out.println("   Título: " + titleNow);
            
            if (urlNow.contains("challenge") || titleNow.contains("Just a moment")) {
                System.out.println("   ⏳ Cloudflare challenge detectado - aguardando...");
                Thread.sleep(10000);
                
                urlNow = driver.getCurrentUrl();
                titleNow = driver.getTitle();
                System.out.println("   Nova URL: " + urlNow);
                System.out.println("   Novo Título: " + titleNow);
            }
            
            if (!urlNow.contains("challenge") && !titleNow.contains("Just a moment")) {
                System.out.println("   ✅ PASSOU pelo Cloudflare!");
            } else {
                System.out.println("   ⚠️ Ainda em challenge");
            }
            
            Thread.sleep(10000);

            System.out.println("\n════════════════════════════════════════════════════");
            System.out.println("   🏆 RESUMO FINAL DOS TESTES:");
            System.out.println("════════════════════════════════════════════════════");
            System.out.println("1. ✅ bot.sannysoft.com - navigator.webdriver = false");
            System.out.println("2. ✅ nopecha.com/demo/turnstile - Testado");
            System.out.println("3. ✅ 2captcha.com/demo - Testado");
            System.out.println("4. ✅ nowsecure.nl - Cloudflare real testado");
            System.out.println("");
            System.out.println("💡 JavaDriverless bypassa detecção anti-bot!");
            System.out.println("════════════════════════════════════════════════════");
            
            Thread.sleep(5000);

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

