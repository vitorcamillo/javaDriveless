package examples;

import io.github.selenium.javaDriverless.JavaDriverless;
import io.github.selenium.javaDriverless.types.By;

/**
 * Exemplo demonstrando o novo sistema By
 * (Inspirado no Selenium-Driverless Python)
 * 
 * ✅ O sistema By está implementado e pronto!
 * ⚠️  findElement/findElements ainda em desenvolvimento
 * 
 * O que FUNCIONA hoje:
 * - ✅ Navegação (get, back, forward, refresh)
 * - ✅ executeScript
 * - ✅ getTitle, getCurrentUrl, getPageSource
 * - ✅ Gerenciamento de Profiles
 * - ✅ Cookies
 * - ✅ Window control (maximize, etc)
 */
public class ExemploByFuncional {
    
    public static void main(String[] args) {
        System.out.println("═══════════════════════════════════════════");
        System.out.println("  Exemplo: Sistema By (Python-like)");
        System.out.println("═══════════════════════════════════════════\n");
        
        JavaDriverless driver = new JavaDriverless("ExemploBy");
        
        try {
            // 1. Navegação básica
            System.out.println("1️⃣  Navegação");
            driver.get("https://example.com");
            System.out.println("   ✓ URL: " + driver.getCurrentUrl());
            System.out.println("   ✓ Título: " + driver.getTitle());
            
            // 2. Execute Script (FUNCIONA!)
            System.out.println("\n2️⃣  ExecuteScript");
            Object h1Text = driver.executeScript("document.getElementsByTagName('h1')[0].textContent");
            System.out.println("   ✓ H1 via script: " + h1Text);
            
            // 3. Sistema By está implementado!
            System.out.println("\n3️⃣  Sistema By (IMPLEMENTADO!)");
            System.out.println("   Métodos disponíveis:");
            System.out.println("   • By.xpath(\"//h1\")");
            System.out.println("   • By.css(\"h1\")");
            System.out.println("   • By.id(\"myId\")");
            System.out.println("   • By.name(\"myName\")");
            System.out.println("   • By.tagName(\"h1\")");
            System.out.println("   • By.className(\"myClass\")");
            System.out.println("   • By.linkText(\"Click here\")");
            System.out.println("   • By.partialLinkText(\"Click\")");
            
            // 4. Exemplos de como usar quando findElement funcionar
            System.out.println("\n4️⃣  Exemplo de uso futuro:");
            System.out.println("   driver.findElement(By.xpath(\"//h1\"));");
            System.out.println("   driver.findElement(By.css(\"textarea[name='q']\"));");
            System.out.println("   driver.findElements(By.tagName(\"a\"));");
            
            // 5. Workaround atual: usar executeScript
            System.out.println("\n5️⃣  Workaround atual (executeScript):");
            Object links = driver.executeScript("document.querySelectorAll('a').length");
            System.out.println("   ✓ Links na página: " + links);
            
            System.out.println("\n═══════════════════════════════════════════");
            System.out.println("  ✅ Sistema By pronto!");
            System.out.println("  📚 Referência: https://github.com/kaliiiiiiiiii/Selenium-Driverless");
            System.out.println("═══════════════════════════════════════════\n");
            
        } catch (Exception e) {
            System.err.println("\n❌ Erro: " + e.getMessage());
            e.printStackTrace();
        } finally {
            driver.quit();
        }
    }
}

