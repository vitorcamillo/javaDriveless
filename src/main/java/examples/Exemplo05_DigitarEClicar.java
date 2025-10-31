package examples;

import io.github.selenium.javaDriverless.JavaDriverless;
import io.github.selenium.javaDriverless.types.WebElement;

/**
 * Exemplo 5: Digitar e clicar em elementos
 */
public class Exemplo05_DigitarEClicar {
    
    public static void main(String[] args) {
        System.out.println("Exemplo 5: Digitar e clicar\n");
        
        JavaDriverless driver = new JavaDriverless("DigitarClicar");
        
        driver.get("https://www.google.com");
        driver.sleep(2);
        
        // Buscar caixa de texto
        System.out.println("Buscando caixa de pesquisa...");
        WebElement searchBox = driver.findElement("css", "textarea[name='q']");
        
        // Digitar
        System.out.println("Digitando...");
        searchBox.sendKeys("Java Driverless").join();
        driver.sleep(1);
        
        // Buscar e clicar no botão
        System.out.println("Clicando no botão...");
        WebElement searchButton = driver.findElement("css", "input[value='Pesquisa Google']");
        searchButton.click().join();
        driver.sleep(3);
        
        // Verificar resultado
        String url = driver.getCurrentUrl();
        System.out.println("\n✓ Pesquisa realizada!");
        System.out.println("  URL: " + url);
        
        driver.quit();
        System.out.println("\n✓ Concluído!");
    }
}

