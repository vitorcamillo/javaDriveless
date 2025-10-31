package examples;

import io.github.selenium.javaDriverless.JavaDriverless;
import java.util.*;

/**
 * Exemplo 7: Gerenciamento de cookies
 */
public class Exemplo07_Cookies {
    
    public static void main(String[] args) {
        System.out.println("Exemplo 7: Cookies\n");
        
        JavaDriverless driver = new JavaDriverless("Cookies");
        
        driver.get("https://www.google.com");
        driver.sleep(2);
        
        // Ver cookies
        System.out.println("1. Cookies existentes:");
        List<Map<String, Object>> cookies = driver.getCookies();
        System.out.println("   Total: " + cookies.size());
        
        // Adicionar cookie
        System.out.println("\n2. Adicionando cookie...");
        Map<String, Object> cookie = new HashMap<>();
        cookie.put("name", "meu_cookie");
        cookie.put("value", "teste123");
        cookie.put("domain", ".google.com");
        driver.addCookie(cookie);
        
        // Verificar
        System.out.println("3. Cookies após adicionar: " + driver.getCookies().size());
        
        // Deletar todos
        System.out.println("\n4. Deletando todos os cookies...");
        driver.deleteAllCookies();
        System.out.println("   Cookies restantes: " + driver.getCookies().size());
        
        driver.quit();
        System.out.println("\n✓ Concluído!");
    }
}

