package examples;

import io.github.selenium.javaDriverless.JavaDriverless;
import io.github.selenium.javaDriverless.types.ChromeOptions;

/**
 * Exemplo Bet365 com JavaDriverless e gerenciamento de profiles
 */
public class Bet365ExampleJD {
    
    public static void main(String[] args) {
        System.out.println("=== Bet365 com Gerenciamento de Profiles ===\n");
        
        ChromeOptions options = new ChromeOptions();
        options.addArgument("--start-maximized");
        options.addArgument("--disable-blink-features=AutomationControlled");
        options.setStartupUrl("https://bet365.bet.br");
        
        try {
            // Profile "Bet365" - mantém login entre execuções
            // Se crashear, ao rodar de novo fecha só este Chrome
            JavaDriverless driver = new JavaDriverless("Bet365", options);
            
            System.out.println("→ Aguardando Bet365 carregar...");
            driver.sleep(3);
            
            String title = driver.getTitle();
            String url = driver.getCurrentUrl();
            
            System.out.println("\n✓ Site carregado:");
            System.out.println("  Título: " + title);
            System.out.println("  URL: " + url);
            
            // Screenshot
            driver.screenshot("bet365_jd.png");
            System.out.println("\n✓ Screenshot: bet365_jd.png");
            
            // Aguardar
            System.out.println("\n→ Mantendo aberto por 10 segundos...");
            System.out.println("  (você pode fazer login, o profile vai salvar!)");
            driver.sleep(10);
            
            // Fechar
            driver.quit();
            System.out.println("✓ Concluído! Profile salvo em ./profiles/Bet365/");
            
        } catch (Exception e) {
            System.err.println("✗ Erro: " + e.getMessage());
            e.printStackTrace();
        }
    }
}

