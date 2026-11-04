package io.github.selenium.driverless;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;

import io.github.selenium.javaDriverless.JavaDriverless;
import io.github.selenium.javaDriverless.types.ChromeOptions;

import java.io.*;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Testes completos do JavaDriverless com sistema de profiles.
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@Tag("browser")
public class JavaDriverlessTest {
    private static final String PROFILES_DIR_PROPERTY = "java.driverless.profiles.dir";
    private static String previousProfilesRoot;
    private static HttpServer fixtureServer;
    private static String fixtureOrigin;

    @TempDir
    static Path profilesRoot;

    @BeforeAll
    static void isolateProfiles() throws IOException {
        previousProfilesRoot = System.getProperty(PROFILES_DIR_PROPERTY);
        System.setProperty(PROFILES_DIR_PROPERTY, profilesRoot.toString());
        fixtureServer = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        byte[] body = "<html><head><title>Persistent fixture</title></head><body>fixture</body></html>"
                .getBytes(StandardCharsets.UTF_8);
        fixtureServer.createContext("/", exchange -> {
            exchange.getResponseHeaders().set("Content-Type", "text/html; charset=utf-8");
            exchange.sendResponseHeaders(200, body.length);
            try (var response = exchange.getResponseBody()) {
                response.write(body);
            }
        });
        fixtureServer.start();
        fixtureOrigin = "http://127.0.0.1:" + fixtureServer.getAddress().getPort();
    }

    @AfterAll
    static void restoreProfilesRoot() {
        if (fixtureServer != null) {
            fixtureServer.stop(0);
        }
        if (previousProfilesRoot == null) {
            System.clearProperty(PROFILES_DIR_PROPERTY);
        } else {
            System.setProperty(PROFILES_DIR_PROPERTY, previousProfilesRoot);
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
        options.setStartupUrl("data:text/html,<title>Startup local</title>");
        
        JavaDriverless driver = new JavaDriverless(options);
        
        assertNotNull(driver);
        assertEquals("default", driver.getNomeJanela());
        
        // Aguardar carregar
        driver.sleep(2);
        
        String title = driver.getTitle();
        assertEquals("Startup local", title);
        
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
        Path pidFile = driver.getProfileManager().getPidFilePath(profileName);
        assertTrue(pidFile.toAbsolutePath().normalize().startsWith(profilesRoot.toAbsolutePath().normalize()));
        assertTrue(Files.exists(pidFile));
        
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
        driver.get("data:text/html,<title>Navegacao local</title>");
        driver.sleep(2);
        
        String title = driver.getTitle();
        assertEquals("Navegacao local", title);
        
        String url = driver.getCurrentUrl();
        assertTrue(url.startsWith("data:text/html"));
        
        driver.quit();
        
        System.out.println("✓ Teste 5 concluído!");
    }
    
    @Test
    @Order(6)
    public void testMetodosBasicos() {
        System.out.println("\n=== Teste 6: Métodos básicos ===");
        
        JavaDriverless driver = new JavaDriverless("TestMetodos");
        
        try {
            driver.get("data:text/html,<title>Primeira</title><h1>primeira</h1>");
            driver.sleep(2);
            
            // Navegar para segunda página (para ter histórico)
            driver.get("data:text/html,<title>Segunda</title><h1>segunda</h1>");
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
        driver1.get("data:text/html,<title>Crash fixture</title>");
        
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
        driver.get("data:text/html,<title>Script fixture</title>");
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
        driver.get("data:text/html,<title>Cookie fixture</title>");
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
        driver.get("data:text/html,<title>Window fixture</title>");
        driver.sleep(1);
        
        // Maximizar
        driver.maximize();
        driver.sleep(1);
        Map<String, Object> size = driver.getWindowSize();
        assertTrue(((Number) size.get("width")).intValue() > 0);
        assertTrue(((Number) size.get("height")).intValue() > 0);
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
        driver1.get(fixtureOrigin + "/");
        driver1.sleep(2);
        
        Map<String, Object> persistentCookie = new HashMap<>();
        persistentCookie.put("name", "jdl-persistent-cookie");
        persistentCookie.put("value", "kept-across-restart");
        persistentCookie.put("url", fixtureOrigin + "/");
        persistentCookie.put("expires", System.currentTimeMillis() / 1000.0 + 3600);
        driver1.addCookie(persistentCookie);
        assertNotNull(driver1.getCookie("jdl-persistent-cookie"));
        
        driver1.quit();
        
        // Segunda execução: cookies devem estar lá
        JavaDriverless driver2 = new JavaDriverless(profileName);
        driver2.get(fixtureOrigin + "/");
        driver2.sleep(2);
        
        Map<String, Object> restored = driver2.getCookie("jdl-persistent-cookie");
        assertNotNull(restored, "Cookie precisa sobreviver ao reinício do mesmo profile");
        assertEquals("kept-across-restart", restored.get("value"));
        
        driver2.quit();
        
        System.out.println("✓ Teste 12 concluído!");
    }
    
    @Test
    @Order(13)
    public void testEncadeamentoMetodos() {
        System.out.println("\n=== Teste 13: Encadeamento de métodos ===");
        
        JavaDriverless driver = new JavaDriverless("TestEncadeamento");
        
        // Encadear chamadas
        driver.get("data:text/html,<title>Chain fixture</title>")
              .sleep(1)
              .refresh()
              .sleep(1)
              .maximize();
        
        assertNotNull(driver.getTitle());
        
        driver.quit();
        
        System.out.println("✓ Teste 13 concluído!");
    }
}

