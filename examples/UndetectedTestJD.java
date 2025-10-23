
package examples;

import io.github.selenium.javaDriverless.JavaDriverless;
import io.github.selenium.javaDriverless.types.ChromeOptions;

import java.io.File;

/**
 * Teste completo de detecção usando JavaDriverless
 * 
 * Sites de teste:
 * - bot.sannysoft.com
 * - arh.antoinevastel.com/bots/areyouheadless
 * - pixelscan.net
 * - browserscan.net
 */
public class UndetectedTestJD {
    
    public static void main(String[] args) {
        System.out.println("╔══════════════════════════════════════════╗");
        System.out.println("║   TESTE DE DETECÇÃO - Java Driverless   ║");
        System.out.println("╚══════════════════════════════════════════╝\n");
        
        // Criar pasta para screenshots
        new File("screenshots").mkdirs();
        
        ChromeOptions options = new ChromeOptions();
        options.addArgument("--start-maximized");
        options.addArgument("--disable-blink-features=AutomationControlled");
        
        try {
            JavaDriverless driver = new JavaDriverless("UndetectedTest", options);
            
            // Teste 1: Bot Sannysoft
            System.out.println("─────────────────────────────────────────");
            System.out.println("Teste 1: bot.sannysoft.com");
            System.out.println("─────────────────────────────────────────");
            testSite(driver, "https://bot.sannysoft.com", "01_sannysoft.png", 5);
            
            // Teste 2: Are You Headless
            System.out.println("\n─────────────────────────────────────────");
            System.out.println("Teste 2: arh.antoinevastel.com");
            System.out.println("─────────────────────────────────────────");
            testSite(driver, "https://arh.antoinevastel.com/bots/areyouheadless", 
                    "02_areyouheadless.png", 5);
            
            // Teste 3: PixelScan
            System.out.println("\n─────────────────────────────────────────");
            System.out.println("Teste 3: pixelscan.net");
            System.out.println("─────────────────────────────────────────");
            testSite(driver, "https://pixelscan.net", "03_pixelscan.png", 10);
            
            // Teste 4: BrowserScan
            System.out.println("\n─────────────────────────────────────────");
            System.out.println("Teste 4: browserscan.net");
            System.out.println("─────────────────────────────────────────");
            testSite(driver, "https://www.browserscan.net", "04_browserscan.png", 8);
            
            // Resumo
            System.out.println("\n╔══════════════════════════════════════════╗");
            System.out.println("║         TESTES FINALIZADOS!              ║");
            System.out.println("╚══════════════════════════════════════════╝");
            System.out.println("\n✓ Screenshots salvos em: ./screenshots/");
            System.out.println("✓ Revise as imagens para ver os resultados!");
            
            // Fechar
            driver.quit();
            
        } catch (Exception e) {
            System.err.println("✗ Erro: " + e.getMessage());
            e.printStackTrace();
        }
    }
    
    private static void testSite(JavaDriverless driver, String url, 
                                 String screenshotName, int waitSeconds) {
        try {
            System.out.println("→ Navegando para " + url);
            driver.get(url);
            
            System.out.println("→ Aguardando " + waitSeconds + " segundos...");
            driver.sleep(waitSeconds);
            
            String title = driver.getTitle();
            System.out.println("✓ Título: " + title);
            
            driver.screenshot("screenshots/" + screenshotName);
            System.out.println("✓ Screenshot: screenshots/" + screenshotName);
            
        } catch (Exception e) {
            System.err.println("✗ Erro ao testar " + url + ": " + e.getMessage());
        }
    }
}

