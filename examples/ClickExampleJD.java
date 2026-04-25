package examples;

import io.github.selenium.javaDriverless.JavaDriverless;
import io.github.selenium.javaDriverless.types.WebElement;

/**
 * Exemplo de clicks e interação com elementos usando JavaDriverless
 */
public class ClickExampleJD {
    
    public static void main(String[] args) {
        System.out.println("=== Teste de Click com JavaDriverless ===\n");
        
        try {
            JavaDriverless driver = new JavaDriverless("ClickTest");
            
            // Navegar para Google
            System.out.println("→ Navegando para Google...");
            driver.get("https://www.google.com");
            driver.sleep(2);
            
            // Buscar caixa de texto
            System.out.println("→ Buscando caixa de pesquisa...");
            WebElement searchBox = driver.findElement("css", "textarea[name='q']");
            System.out.println("✓ Elemento encontrado!");
            
            // Digitar
            System.out.println("→ Digitando 'Java Driverless'...");
            searchBox.sendKeys("Java Driverless").join();
            driver.sleep(1);
            System.out.println("✓ Texto digitado!");
            
            // Buscar botão de pesquisa
            System.out.println("→ Buscando botão de pesquisa...");
            WebElement searchButton = driver.findElement("css", "input[value='Pesquisa Google']");
            
            // Clicar
            System.out.println("→ Clicando no botão...");
            searchButton.click().join();
            driver.sleep(3);
            
            // Verificar resultado
            String url = driver.getCurrentUrl();
            System.out.println("\n✓ Busca realizada!");
            System.out.println("  URL: " + url);
            
            if (url.contains("search") || url.contains("q=")) {
                System.out.println("  ✓ Pesquisa funcionou corretamente!");
            }
            
            // Screenshot
            driver.screenshot("click_result.png");
            System.out.println("\n✓ Screenshot: click_result.png");
            
            // Aguardar
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

