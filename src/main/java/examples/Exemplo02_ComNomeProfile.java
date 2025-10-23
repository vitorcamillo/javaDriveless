package examples;

import io.github.selenium.javaDriverless.JavaDriverless;

/**
 * Exemplo 2: Usando profile com nome específico
 */
public class Exemplo02_ComNomeProfile {
    
    public static void main(String[] args) {
        System.out.println("Exemplo 2: Profile com nome\n");
        
        // Profile específico "MeuBot"
        JavaDriverless driver = new JavaDriverless("MeuBot");
        
        driver.get("https://www.github.com");
        driver.sleep(2);
        
        System.out.println("Título: " + driver.getTitle());
        System.out.println("Profile salvo em: ./profiles/MeuBot/");
        
        driver.quit();
        System.out.println("\n✓ Concluído!");
    }
}

