package examples;

import io.github.selenium.driverless.sync.SyncChrome;
import io.github.selenium.driverless.types.ChromeOptions;

/**
 * Exemplo SIMPLES para abrir Bet365
 */
public class RunBet365 {
    
    public static void main(String[] args) {
        System.out.println("=== Abrindo Bet365 ===\n");
        
        ChromeOptions options = new ChromeOptions();
        options.addArgument("--start-maximized");
        options.addArgument("--disable-blink-features=AutomationControlled");
        
        try {
            System.out.println("✓ Iniciando Chrome...");
            SyncChrome driver = new SyncChrome(options);
            
            System.out.println("✓ Navegando para bet365...");
            driver.get("https://bet365.bet.br", true);
            
            System.out.println("✓ Aguardando carregar...");
            Thread.sleep(3000);
            
            String titulo = driver.getTitle();
            System.out.println("✓ Título: " + titulo);
            
            String url = driver.getCurrentUrl();
            System.out.println("✓ URL: " + url);
            
            System.out.println("\n✓ Tirando screenshot...");
            driver.getScreenshotAsFile("bet365.png");
            System.out.println("✓ Screenshot salvo em bet365.png");
            
            System.out.println("\n→ Mantendo navegador aberto por 20 segundos...");
            System.out.println("  (você pode interagir com a página)");
            Thread.sleep(20000);
            
            System.out.println("\n→ Fechando Chrome...");
            driver.quit();
            
            System.out.println("✓ Concluído!");
            
        } catch (Exception e) {
            System.err.println("✗ Erro: " + e.getMessage());
            e.printStackTrace();
        }
    }
}

