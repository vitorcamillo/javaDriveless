package examples;

import io.github.selenium.javaDriverless.JavaDriverless;
import io.github.selenium.javaDriverless.types.By;
import io.github.selenium.javaDriverless.types.WebElement;
import java.util.List;

/**
 * Exemplo 4: Buscar elementos com By (igual Selenium Python)
 * 
 * Demonstra o uso do localizador By, inspirado no Selenium-Driverless Python:
 * https://github.com/kaliiiiiiiiii/Selenium-Driverless
 */
public class Exemplo04_BuscarElementos {
    
    public static void main(String[] args) {
        System.out.println("═══════════════════════════════════════════");
        System.out.println("  Exemplo 4: Buscar Elementos com By");
        System.out.println("═══════════════════════════════════════════\n");
        
        JavaDriverless driver = new JavaDriverless("BuscarElementos");
        
        try {
            // Navegar para uma página simples
            System.out.println("▶️  Navegando para example.com...");
            driver.get("https://example.com");
            System.out.println("✓ Página carregada");
            
            driver.sleep(2);
            
            // 1. Buscar por Tag Name (mais simples)
            System.out.println("\n1️⃣  By.tagName(\"h1\") - Buscar título");
            WebElement h1 = driver.findElement(By.tagName("h1"));
            System.out.println("   ✓ Encontrou H1: " + h1.getText().join());
            
            // 2. Buscar múltiplos elementos
            System.out.println("\n2️⃣  By.tagName(\"div\") - Buscar múltiplos");
            List<WebElement> divs = driver.findElements(By.tagName("div"));
            System.out.println("   ✓ Encontrou " + divs.size() + " divs");
            
            // 3. Buscar por CSS
            System.out.println("\n3️⃣  By.css(\"p\") - Buscar por CSS");
            WebElement p = driver.findElement(By.css("p"));
            System.out.println("   ✓ Encontrou parágrafo");
            
            // 4. Buscar por XPath
            System.out.println("\n4️⃣  By.xpath(\"//a\") - Buscar por XPath");
            WebElement link = driver.findElement(By.xpath("//a"));
            System.out.println("   ✓ Encontrou link: " + link.getText().join());
            
            // 5. Demonstrar outros métodos By
            System.out.println("\n5️⃣  Outros métodos disponíveis:");
            System.out.println("   • By.id(\"myId\")");
            System.out.println("   • By.name(\"myName\")");
            System.out.println("   • By.className(\"myClass\")");
            System.out.println("   • By.cssSelector(\"div > p\")");
            System.out.println("   • By.linkText(\"Click here\")");
            System.out.println("   • By.partialLinkText(\"Click\")");
            
            // 6. Compatibilidade com sintaxe antiga
            System.out.println("\n6️⃣  Sintaxe antiga (ainda funciona):");
            WebElement body = driver.findElement("css", "body");
            System.out.println("   ✓ driver.findElement(\"css\", \"body\")");
            
            System.out.println("\n═══════════════════════════════════════════");
            System.out.println("  ✅ Todos os métodos By funcionaram!");
            System.out.println("  📚 Use By.xxx() para código mais limpo");
            System.out.println("═══════════════════════════════════════════\n");
            
        } catch (Exception e) {
            System.err.println("\n❌ Erro: " + e.getMessage());
            e.printStackTrace();
        } finally {
            driver.quit();
        }
    }
}
