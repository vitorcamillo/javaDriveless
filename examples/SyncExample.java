package examples;

import io.github.selenium.driverless.sync.SyncChrome;
import io.github.selenium.driverless.types.By;
import io.github.selenium.driverless.types.ChromeOptions;

/**
 * Exemplo de uso síncrono do Java Driverless.
 * <p>
 * Demonstra automação com API síncrona (bloqueante).
 * </p>
 */
public class SyncExample {
    
    public static void main(String[] args) {
        System.out.println("=== Java Driverless - Exemplo Síncrono ===\n");
        
        ChromeOptions options = new ChromeOptions();
        
        try (SyncChrome driver = new SyncChrome(options)) {
            System.out.println("✓ Chrome iniciado!\n");
            
            // Navegar
            System.out.println("→ Navegando para http://nowsecure.nl...");
            driver.get("http://nowsecure.nl#relax");
            System.out.println("✓ Página carregada!");
            
            driver.sleep(0.5);
            driver.waitForCdp("Page.domContentEventFired", 15.0f);
            
            // Obter informações
            String title = driver.getTitle();
            String url = driver.getCurrentUrl();
            String source = driver.getPageSource();
            
            System.out.println("\n📄 Informações da Página:");
            System.out.println("  Título: " + title);
            System.out.println("  URL: " + url);
            System.out.println("  HTML: " + source.substring(0, Math.min(100, source.length())) + "...");
            
            System.out.println("\n✓ Exemplo síncrono concluído com sucesso!");
            
        } catch (Exception e) {
            System.err.println("✗ Erro: " + e.getMessage());
            e.printStackTrace();
        }
    }
}

