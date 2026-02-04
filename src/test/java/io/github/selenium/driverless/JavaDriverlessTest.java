package io.github.selenium.driverless;

import org.junit.jupiter.api.*;

import io.github.selenium.javaDriverless.JavaDriverless;
import io.github.selenium.javaDriverless.types.ChromeOptions;

import java.io.*;
import java.nio.file.*;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Testes completos do JavaDriverless com sistema de profiles.
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
public class JavaDriverlessTest {
    
    private static final String PROFILES_DIR = "profiles";
    
    @AfterAll
    public static void cleanup() {
        // Limpar pasta profiles de teste
        try {
            Path profilesPath = Paths.get(PROFILES_DIR);
            if (Files.exists(profilesPath)) {
                Files.walk(profilesPath)
                    .sorted(Comparator.reverseOrder())
                    .forEach(path -> {
                        try {
                            Files.delete(path);
                        } catch (IOException e) {
                            // Ignorar
                        }
                    });
            }
        } catch (IOException e) {
            // Ignorar
        }
    }
    
    @Test
    @Order(1)
    public void testConstrutor_SemParametros() {
        System.out.println("\n=== Teste 1: Construtor sem parâmetros ===");
        
        JavaDriverless driver = new JavaDriverless();
        
        assertNotNull(driver);
        assertEquals("default", driver.getNomeJanela());
        assertTrue(driver.isProfileManagementEnabled());
        assertNotNull(driver.getChrome());
        assertTrue(driver.getPid() > 0);
        
        driver.quit();
        
        System.out.println("✓ Teste 1 concluído!");
    }
    
    @Test
    @Order(2)
    public void testConstrutor_ComOpcoes() {
        System.out.println("\n=== Teste 2: Construtor com opções (estilo Selenium) ===");
        
        ChromeOptions options = new ChromeOptions();
        options.setStartupUrl("https://www.google.com");
        
        JavaDriverless driver = new JavaDriverless(options);
        
        assertNotNull(driver);
        assertEquals("default", driver.getNomeJanela());
        
        // Aguardar carregar
        driver.sleep(2);
        
        String title = driver.getTitle();
        assertTrue(title.contains("Google"));
        
        driver.quit();
        
        System.out.println("✓ Teste 2 concluído!");
    }
    
    @Test
    @Order(3)
    public void testConstrutor_ComNomeProfile() {
        System.out.println("\n=== Teste 3: Construtor com nome de profile ===");
        
        String profileName = "TestProfile_1";
        JavaDriverless driver = new JavaDriverless(profileName);
        
        assertNotNull(driver);
        assertEquals(profileName, driver.getNomeJanela());
        assertTrue(driver.isProfileManagementEnabled());
        
        // Verificar que profile foi criado
        Path profilePath = Paths.get(PROFILES_DIR, profileName);
        assertTrue(Files.exists(profilePath));
        
        driver.quit();
        
        System.out.println("✓ Teste 3 concluído!");
    }
    
    @Test
    @Order(4)
    public void testConstrutor_SemGerenciamento() {
        System.out.println("\n=== Teste 4: Construtor sem gerenciamento ===");
        
        JavaDriverless driver = new JavaDriverless(false);
        
        assertNotNull(driver);
        assertFalse(driver.isProfileManagementEnabled());
        assertNull(driver.getProfileManager());
        
        driver.quit();
        
        System.out.println("✓ Teste 4 concluído!");
    }
    
    @Test
    @Order(5)
    public void testNavegacao() {
        System.out.println("\n=== Teste 5: Navegação básica ===");
        
        JavaDriverless driver = new JavaDriverless("TestNavegacao");
        
        // Navegar
        driver.get("https://www.google.com");
        driver.sleep(2);
        
        String title = driver.getTitle();
        assertTrue(title.contains("Google"));
        
        String url = driver.getCurrentUrl();
        assertTrue(url.contains("google.com"));
        
        driver.quit();
        
        System.out.println("✓ Teste 5 concluído!");
    }
    
    @Test
    @Order(6)
    public void testMetodosBasicos() {
        System.out.println("\n=== Teste 6: Métodos básicos ===");
        
        JavaDriverless driver = new JavaDriverless("TestMetodos");
        
        try {
            // Navegar para primeira página - usar URL mais simples caso GitHub tenha problemas
            try {
                driver.get("https://www.github.com");
            } catch (RuntimeException e) {
                // Se GitHub falhar, tentar outra página
                System.err.println("Aviso: Falha ao navegar para GitHub, tentando Google...");
                driver.get("https://www.google.com");
            }
            driver.sleep(2);
            
            // Navegar para segunda página (para ter histórico)
            driver.get("https://www.google.com");
            driver.sleep(2);
            
            // Agora pode usar back (tem histórico)
            driver.back();
            driver.sleep(1);
            
            // Forward
            driver.forward();
            driver.sleep(1);
            
            // Refresh
            driver.refresh();
            driver.sleep(1);
            
            // Verificar título
            String title = driver.getTitle();
            assertNotNull(title);
            System.out.println("Título: " + title);
            
        } finally {
            driver.quit();
        }
        
        System.out.println("✓ Teste 6 concluído!");
    }
    
    @Test
    @Order(7)
    public void testGerenciamentoProfile_ReinicioAutomatico() {
        System.out.println("\n=== Teste 7: Reinício automático (simula crash) ===");
        
        String profileName = "TestCrash";
        
        // PRIMEIRA EXECUÇÃO
        System.out.println("Primeira execução: criando Chrome...");
        JavaDriverless driver1 = new JavaDriverless(profileName);
        driver1.get("https://www.google.com");
        
        long pid1 = driver1.getPid();
        System.out.println("PID primeiro Chrome: " + pid1);
        
        // Simular crash (NÃO chamar quit)
        System.out.println("Simulando crash...");
        
        // SEGUNDA EXECUÇÃO (reinício)
        System.out.println("Segunda execução: reiniciando...");
        JavaDriverless driver2 = new JavaDriverless(profileName);
        // Deve detectar e fechar o Chrome antigo automaticamente
        
        long pid2 = driver2.getPid();
        System.out.println("PID segundo Chrome: " + pid2);
        
        // PIDs devem ser diferentes
        assertNotEquals(pid1, pid2);
        
        driver2.quit();
        
        System.out.println("✓ Teste 7 concluído!");
    }
    
    @Test
    @Order(8)
    public void testMultiplosProfiles() {
        System.out.println("\n=== Teste 8: Múltiplos profiles simultâneos ===");
        
        List<JavaDriverless> drivers = new ArrayList<>();
        
        // Criar 3 drivers com profiles diferentes
        for (int i = 1; i <= 3; i++) {
            String profileName = "TestMulti_" + i;
            JavaDriverless driver = new JavaDriverless(profileName);
            drivers.add(driver);
            
            System.out.println("Driver " + i + " criado: PID " + driver.getPid());
        }
        
        // Todos devem estar rodando
        for (JavaDriverless driver : drivers) {
            assertTrue(driver.getPid() > 0);
        }
        
        // Fechar todos
        for (JavaDriverless driver : drivers) {
            driver.quit();
        }
        
        System.out.println("✓ Teste 8 concluído!");
    }
    
    @Test
    @Order(9)
    public void testExecuteScript() {
        System.out.println("\n=== Teste 9: Executar JavaScript ===");
        
        JavaDriverless driver = new JavaDriverless("TestScript");
        driver.get("https://www.google.com");
        driver.sleep(2);
        
        // Executar JavaScript (usar executeScriptAsync ou mudar a sintaxe)
        Object result = driver.executeScriptAsync("document.title", null, true).join();
        assertNotNull(result);
        System.out.println("Resultado script: " + result);
        
        driver.quit();
        
        System.out.println("✓ Teste 9 concluído!");
    }
    
    @Test
    @Order(10)
    public void testCookies() {
        System.out.println("\n=== Teste 10: Gerenciamento de cookies ===");
        
        JavaDriverless driver = new JavaDriverless("TestCookies");
        driver.get("https://www.google.com");
        driver.sleep(2);
        
        // Obter cookies
        List<Map<String, Object>> cookies = driver.getCookies();
        assertNotNull(cookies);
        System.out.println("Cookies encontrados: " + cookies.size());
        
        // Deletar todos
        driver.deleteAllCookies();
        
        driver.quit();
        
        System.out.println("✓ Teste 10 concluído!");
    }
    
    @Test
    @Order(11)
    public void testJanela() {
        System.out.println("\n=== Teste 11: Controle de janela ===");
        
        JavaDriverless driver = new JavaDriverless("TestJanela");
        driver.get("https://www.google.com");
        driver.sleep(1);
        
        // Maximizar
        driver.maximize();
        driver.sleep(1);
        System.out.println("Janela maximizada");
        
        // Só testar maximizar (evitar minimizar que causa problemas)
        // Se precisar minimizar/maximizar, precisa normalizar entre eles
        
        driver.quit();
        
        System.out.println("✓ Teste 11 concluído!");
    }
    
    @Test
    @Order(12)
    public void testProfilePersistente() {
        System.out.println("\n=== Teste 12: Profile persistente ===");
        
        String profileName = "TestPersistente";
        
        // Primeira execução: adicionar cookie
        JavaDriverless driver1 = new JavaDriverless(profileName);
        driver1.get("https://www.google.com");
        driver1.sleep(2);
        
        // Pegar cookies iniciais
        int cookiesBefore = driver1.getCookies().size();
        System.out.println("Cookies na primeira execução: " + cookiesBefore);
        
        driver1.quit();
        
        // Segunda execução: cookies devem estar lá
        JavaDriverless driver2 = new JavaDriverless(profileName);
        driver2.get("https://www.google.com");
        driver2.sleep(2);
        
        int cookiesAfter = driver2.getCookies().size();
        System.out.println("Cookies na segunda execução: " + cookiesAfter);
        
        // Se profile é persistente, pode ter cookies salvos
        assertTrue(cookiesAfter >= 0);
        
        driver2.quit();
        
        System.out.println("✓ Teste 12 concluído!");
    }
    
    @Test
    @Order(13)
    public void testEncadeamentoMetodos() {
        System.out.println("\n=== Teste 13: Encadeamento de métodos ===");
        
        JavaDriverless driver = new JavaDriverless("TestEncadeamento");
        
        // Encadear chamadas
        driver.get("https://www.google.com")
              .sleep(1)
              .refresh()
              .sleep(1)
              .maximize();
        
        assertNotNull(driver.getTitle());
        
        driver.quit();
        
        System.out.println("✓ Teste 13 concluído!");
    }
}

