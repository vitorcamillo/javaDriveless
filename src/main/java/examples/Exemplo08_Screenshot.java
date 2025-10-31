package examples;

import io.github.selenium.javaDriverless.JavaDriverless;
import java.io.File;

/**
 * Exemplo 8: Screenshots
 */
public class Exemplo08_Screenshot {
    
    public static void main(String[] args) {
        System.out.println("Exemplo 8: Screenshot\n");
        
        JavaDriverless driver = new JavaDriverless("Screenshot");
        
        driver.get("https://www.google.com");
        driver.sleep(2);
        
        // Tirar screenshot
        String filename = "exemplo_screenshot.png";
        System.out.println("Salvando screenshot...");
        driver.screenshot(filename);
        
        // Verificar
        File file = new File(filename);
        if (file.exists()) {
            System.out.println("✓ Screenshot salvo: " + filename);
            System.out.println("  Tamanho: " + file.length() + " bytes");
        }
        
        driver.quit();
        System.out.println("\n✓ Concluído!");
    }
}

