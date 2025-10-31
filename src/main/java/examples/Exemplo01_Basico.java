package examples;

import io.github.selenium.javaDriverless.JavaDriverless;

/**
 * Exemplo 1: Uso mais básico possível
 */
public class Exemplo01_Basico {
    
    public static void main(String[] args) {
        System.out.println("Exemplo 1: Básico\n");
        
        // Criar driver (profile "default")
        JavaDriverless driver = new JavaDriverless();
        
        // Navegar
        driver.get("https://www.google.com");
        driver.sleep(2);
        
        // Obter informações
        System.out.println("Título: " + driver.getTitle());
        System.out.println("URL: " + driver.getCurrentUrl());
        
        // Fechar
        driver.quit();
        System.out.println("\n✓ Concluído!");
    }
}

