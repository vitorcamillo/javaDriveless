package examples;
import io.github.selenium.javaDriverless.JavaDriverless;
import io.github.selenium.javaDriverless.types.ChromeOptions;

/**
 * Exemplo básico usando JavaDriverless (muito mais simples!)
 */
public class BasicExampleJD {
    
    public static void main(String[] args) {
        System.out.println("=== Java Driverless - Exemplo Básico ===\n");
        
        try {
            // Criar driver (profile "default")
            JavaDriverless driver = new JavaDriverless();
            
            // Navegar
            System.out.println("→ Navegando para http://nowsecure.nl#relax");
            driver.get("http://nowsecure.nl#relax");
            
            System.out.println("✓ Página carregada!");
            driver.sleep(2);
            
            // Obter informações
            String title = driver.getTitle();
            String url = driver.getCurrentUrl();
            
            System.out.println("\n📄 Informações:");
            System.out.println("  Título: " + title);
            System.out.println("  URL: " + url);
            
            // Executar JavaScript
            Object userAgent = driver.executeScriptAsync("navigator.userAgent", null, true).join();
            System.out.println("  User-Agent: " + userAgent);
            
            // Screenshot
            driver.screenshot("basic_example.png");
            System.out.println("\n✓ Screenshot: basic_example.png");
            
            // Aguardar
            System.out.println("\n→ Aguardando 3 segundos...");
            driver.sleep(3);
            
            // Fechar
            driver.quit();
            System.out.println("✓ Concluído!");
            
        } catch (Exception e) {
            System.err.println("✗ Erro: " + e.getMessage());
            e.printStackTrace();
        }
    }
}

