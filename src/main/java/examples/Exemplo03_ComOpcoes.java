package examples;

import io.github.selenium.javaDriverless.JavaDriverless;
import io.github.selenium.javaDriverless.types.ChromeOptions;

/**
 * Exemplo 3: Com ChromeOptions customizadas
 */
public class Exemplo03_ComOpcoes {
    
    public static void main(String[] args) {
        System.out.println("Exemplo 3: Com opções\n");
        
        ChromeOptions options = new ChromeOptions();
        options.addArgument("--start-maximized");
        options.setStartupUrl("https://www.google.com");
        
        JavaDriverless driver = new JavaDriverless("BotComOpcoes", options);
        
        driver.sleep(2);
        System.out.println("Título: " + driver.getTitle());
        
        driver.quit();
        System.out.println("\n✓ Concluído!");
    }
}

