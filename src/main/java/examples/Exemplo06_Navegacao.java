package examples;

import io.github.selenium.javaDriverless.JavaDriverless;

/**
 * Exemplo 6: Navegação (back, forward, refresh)
 */
public class Exemplo06_Navegacao {
    
    public static void main(String[] args) {
        System.out.println("Exemplo 6: Navegação\n");
        
        JavaDriverless driver = new JavaDriverless("Navegacao");
        
        // Navegar para primeira página
        System.out.println("1. Google...");
        driver.get("https://www.google.com");
        driver.sleep(2);
        
        // Navegar para segunda página  
        System.out.println("2. GitHub...");
        driver.get("https://www.github.com");
        driver.sleep(2);
        
        // Voltar
        System.out.println("3. Voltando (back)...");
        driver.back();
        driver.sleep(2);
        System.out.println("   URL: " + driver.getCurrentUrl());
        
        // Avançar
        System.out.println("4. Avançando (forward)...");
        driver.forward();
        driver.sleep(2);
        System.out.println("   URL: " + driver.getCurrentUrl());
        
        // Refresh
        System.out.println("5. Recarregando (refresh)...");
        driver.refresh();
        driver.sleep(2);
        
        driver.quit();
        System.out.println("\n✓ Concluído!");
    }
}

