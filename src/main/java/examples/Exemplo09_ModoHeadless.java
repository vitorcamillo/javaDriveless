package examples;

import io.github.selenium.javaDriverless.JavaDriverless;
import io.github.selenium.javaDriverless.types.ChromeOptions;

/**
 * Exemplo 9: Modo Headless (invisível)
 */
public class Exemplo09_ModoHeadless {
    
    public static void main(String[] args) {
        System.out.println("Exemplo 9: Modo Headless\n");
        
        ChromeOptions options = new ChromeOptions();
        options.addArgument("--headless=new");
        options.addArgument("--window-size=1920,1080");
        
        System.out.println("Abrindo Chrome invisível...");
        JavaDriverless driver = new JavaDriverless("Headless", options);
        
        driver.get("https://www.google.com");
        driver.sleep(2);
        
        System.out.println("✓ Funcionou em headless!");
        System.out.println("  Título: " + driver.getTitle());
        
        driver.screenshot("headless_screenshot.png");
        System.out.println("  Screenshot: headless_screenshot.png");
        
        driver.quit();
        System.out.println("\n✓ Concluído sem abrir janela!");
    }
}

