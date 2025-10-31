package examples;

import io.github.selenium.javaDriverless.JavaDriverless;
import io.github.selenium.javaDriverless.types.ChromeOptions;

import java.io.File;
import java.util.List;
import java.util.Map;

/**
 * Teste dos recursos que estão no README mas não foram testados
 */
public class TestarRecursosFaltantes {
    
    public static void main(String[] args) {
        System.out.println("═══════════════════════════════════════════");
        System.out.println("  TESTANDO RECURSOS FALTANTES DO README");
        System.out.println("═══════════════════════════════════════════\n");
        
        testarScreenshot();
        testarCookies();
        testarMinimize();
        testarHeadless();
        
        System.out.println("\n═══════════════════════════════════════════");
        System.out.println("  ✅ TESTES CONCLUÍDOS!");
        System.out.println("═══════════════════════════════════════════");
    }
    
    /**
     * 1️⃣  SCREENSHOT
     */
    static void testarScreenshot() {
        System.out.println("1️⃣  ═══ SCREENSHOT ═══");
        
        JavaDriverless driver = new JavaDriverless("TesteScreenshot");
        
        try {
            driver.get("https://example.com");
            driver.sleep(2);
            
            // Testar screenshot
            String filename = "test_screenshot.png";
            driver.screenshot(filename);
            
            // Verificar se arquivo foi criado
            File file = new File(filename);
            if (file.exists()) {
                System.out.println("   ✅ Screenshot criado: " + filename);
                System.out.println("   ✅ Tamanho: " + file.length() + " bytes");
                file.delete(); // Limpar
            } else {
                System.out.println("   ❌ Screenshot NÃO foi criado");
            }
            
        } catch (Exception e) {
            System.out.println("   ❌ Erro: " + e.getMessage());
        } finally {
            driver.quit();
        }
        
        System.out.println();
    }
    
    /**
     * 2️⃣  COOKIES
     */
    static void testarCookies() {
        System.out.println("2️⃣  ═══ COOKIES ═══");
        
        JavaDriverless driver = new JavaDriverless("TesteCookies");
        
        try {
            driver.get("https://example.com");
            driver.sleep(2);
            
            // getCookies (já sabemos que funciona)
            List<Map<String, Object>> cookies = driver.getCookies();
            System.out.println("   ✅ getCookies(): " + cookies.size() + " cookies");
            
            // Testar addCookie
            try {
                Map<String, Object> cookie = Map.of(
                    "name", "test_cookie",
                    "value", "test_value",
                    "domain", "example.com"
                );
                driver.addCookie(cookie);
                System.out.println("   ✅ addCookie() funciona");
            } catch (Exception e) {
                System.out.println("   ❌ addCookie() falhou: " + e.getMessage());
            }
            
            // Testar deleteAllCookies
            try {
                driver.deleteAllCookies();
                System.out.println("   ✅ deleteAllCookies() funciona");
            } catch (Exception e) {
                System.out.println("   ❌ deleteAllCookies() falhou: " + e.getMessage());
            }
            
        } catch (Exception e) {
            System.out.println("   ❌ Erro: " + e.getMessage());
        } finally {
            driver.quit();
        }
        
        System.out.println();
    }
    
    /**
     * 3️⃣  MINIMIZE
     */
    static void testarMinimize() {
        System.out.println("3️⃣  ═══ MINIMIZE ═══");
        
        JavaDriverless driver = new JavaDriverless("TesteMinimize");
        
        try {
            driver.get("https://example.com");
            driver.sleep(2);
            
            // Testar minimize
            try {
                driver.minimize();
                System.out.println("   ✅ minimize() funciona");
                driver.sleep(2);
                
                driver.maximize();
                System.out.println("   ✅ maximize() depois de minimize funciona");
            } catch (Exception e) {
                System.out.println("   ❌ minimize() falhou: " + e.getMessage());
            }
            
        } catch (Exception e) {
            System.out.println("   ❌ Erro: " + e.getMessage());
        } finally {
            driver.quit();
        }
        
        System.out.println();
    }
    
    /**
     * 4️⃣  HEADLESS MODE
     */
    static void testarHeadless() {
        System.out.println("4️⃣  ═══ HEADLESS MODE ═══");
        
        try {
            ChromeOptions options = new ChromeOptions();
            options.addArgument("--headless=new");
            options.addArgument("--window-size=1920,1080");
            
            JavaDriverless driver = new JavaDriverless("TesteHeadless", options);
            
            System.out.println("   ✅ Chrome headless iniciado");
            
            driver.get("https://example.com");
            String title = driver.getTitle();
            System.out.println("   ✅ Navegação headless: " + title);
            
            driver.quit();
            System.out.println("   ✅ Headless mode FUNCIONA!");
            
        } catch (Exception e) {
            System.out.println("   ❌ Headless falhou: " + e.getMessage());
        }
        
        System.out.println();
    }
}

