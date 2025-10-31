package io.github.selenium.driverless;

import org.junit.jupiter.api.*;

import io.github.selenium.javaDriverless.Chrome;
import io.github.selenium.javaDriverless.ProfileManager;
import io.github.selenium.javaDriverless.types.ChromeOptions;

import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Teste do sistema de gerenciamento de profiles com PIDs.
 * 
 * Este teste valida que conseguimos:
 * 1. Criar arquivos de PID únicos por nome de janela
 * 2. Detectar processos Chrome em execução
 * 3. Fechar apenas o processo específico de um profile
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
public class ProfileManagerTest {
    
    private static final String PROFILES_DIR = "test_profiles";
    private static ProfileManager profileManager;
    
    @BeforeAll
    public static void setup() {
        profileManager = new ProfileManager(PROFILES_DIR);
    }
    
    @AfterAll
    public static void cleanup() {
        // Limpar diretório de testes
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
    public void testCreateProfilePidFile() throws Exception {
        System.out.println("=== Teste 1: Criar arquivo de PID para profile ===");
        
        String profileName = "janela_teste_1";
        long fakePid = 12345;
        
        // Salvar PID
        profileManager.savePid(profileName, fakePid);
        
        // Verificar se arquivo foi criado
        Path pidFile = profileManager.getPidFilePath(profileName);
        assertTrue(Files.exists(pidFile), "Arquivo de PID deve existir");
        
        // Ler PID do arquivo
        Optional<Long> savedPid = profileManager.loadPid(profileName);
        assertTrue(savedPid.isPresent(), "PID deve estar presente no arquivo");
        assertEquals(fakePid, savedPid.get(), "PID salvo deve ser igual ao PID lido");
        
        System.out.println("✓ Arquivo de PID criado com sucesso: " + pidFile);
    }
    
    @Test
    @Order(2)
    public void testMultipleProfiles() throws Exception {
        System.out.println("\n=== Teste 2: Múltiplos profiles com PIDs diferentes ===");
        
        String profile1 = "janela_bot_1";
        String profile2 = "janela_bot_2";
        String profile3 = "janela_bot_3";
        
        long pid1 = 10001;
        long pid2 = 10002;
        long pid3 = 10003;
        
        profileManager.savePid(profile1, pid1);
        profileManager.savePid(profile2, pid2);
        profileManager.savePid(profile3, pid3);
        
        // Verificar que cada um tem seu próprio arquivo
        assertEquals(pid1, profileManager.loadPid(profile1).get());
        assertEquals(pid2, profileManager.loadPid(profile2).get());
        assertEquals(pid3, profileManager.loadPid(profile3).get());
        
        System.out.println("✓ Múltiplos profiles gerenciados independentemente");
    }
    
    @Test
    @Order(3)
    public void testProcessCheck() throws Exception {
        System.out.println("\n=== Teste 3: Verificar se processo está rodando ===");
        
        // Usar o PID do processo Java atual (sabemos que está rodando)
        long currentPid = ProcessHandle.current().pid();
        
        boolean isRunning = profileManager.isProcessRunning(currentPid);
        assertTrue(isRunning, "Processo atual deve estar rodando");
        
        // Testar com PID que provavelmente não existe
        boolean notRunning = profileManager.isProcessRunning(999999);
        assertFalse(notRunning, "Processo fictício não deve estar rodando");
        
        System.out.println("✓ Verificação de processos funcionando corretamente");
    }
    
    @Test
    @Order(4)
    public void testCleanupOldProcess() throws Exception {
        System.out.println("\n=== Teste 4: Limpeza de processo antigo ===");
        
        String profileName = "janela_teste_cleanup";
        
        // Salvar um PID que não existe
        profileManager.savePid(profileName, 999998);
        
        // Tentar limpar processo antigo
        boolean cleaned = profileManager.cleanupOldProcess(profileName);
        
        System.out.println("✓ Cleanup de processo antigo: " + (cleaned ? "processo removido" : "sem processo para remover"));
    }
    
    @Test
    @Order(5)
    public void testRealChromeProcess() throws Exception {
        System.out.println("\n=== Teste 5: Teste com Chrome real ===");
        
        String profileName = "janela_chrome_real";
        
        // Criar instância do Chrome (SEM HEADLESS - você verá a janela!)
        ChromeOptions options = new ChromeOptions();
        // options.addArgument("--headless=new"); // COMENTADO para você ver!
        
        System.out.println("Abrindo Chrome... você deve ver a janela aparecer!");
        Chrome chrome = Chrome.create(options).get(30, TimeUnit.SECONDS);
        
        // Obter PID real do Chrome
        long chromePid = chrome.getBrowserPid();
        System.out.println("Chrome iniciado com PID: " + chromePid);
        
        // Salvar PID
        profileManager.savePid(profileName, chromePid);
        
        // Verificar que processo está rodando
        assertTrue(profileManager.isProcessRunning(chromePid), "Chrome deve estar rodando");
        
        // Verificar que pode detectar o profile em uso
        Optional<Long> savedPid = profileManager.loadPid(profileName);
        assertTrue(savedPid.isPresent(), "PID deve estar salvo");
        assertEquals(chromePid, savedPid.get(), "PID salvo deve ser igual ao PID do Chrome");
        
        System.out.println("Aguardando 3 segundos para você ver o Chrome aberto...");
        Thread.sleep(3000);
        
        // Fechar Chrome
        System.out.println("Fechando Chrome...");
        chrome.quit().get(10, TimeUnit.SECONDS);
        
        // Aguardar um pouco para garantir que processo foi encerrado
        Thread.sleep(1000);
        
        // Verificar que processo não está mais rodando
        assertFalse(profileManager.isProcessRunning(chromePid), "Chrome não deve estar mais rodando");
        
        System.out.println("✓ Teste com Chrome real concluído");
    }
    
    @Test
    @Order(6)
    public void testKillSpecificChrome() throws Exception {
        System.out.println("\n=== Teste 6: Matar Chrome específico por profile ===");
        
        String profile1 = "chrome_kill_test_1";
        String profile2 = "chrome_kill_test_2";
        
        // Criar dois Chromes (SEM HEADLESS - você verá 2 janelas!)
        ChromeOptions options1 = new ChromeOptions();
        // options1.addArgument("--headless=new"); // COMENTADO
        
        ChromeOptions options2 = new ChromeOptions();
        // options2.addArgument("--headless=new"); // COMENTADO
        
        System.out.println("Abrindo PRIMEIRO Chrome... você verá a janela 1!");
        Chrome chrome1 = Chrome.create(options1).get(30, TimeUnit.SECONDS);
        
        System.out.println("Abrindo SEGUNDO Chrome... você verá a janela 2!");
        Chrome chrome2 = Chrome.create(options2).get(30, TimeUnit.SECONDS);
        
        long pid1 = chrome1.getBrowserPid();
        long pid2 = chrome2.getBrowserPid();
        
        System.out.println("Chrome 1 (profile: " + profile1 + ") PID: " + pid1);
        System.out.println("Chrome 2 (profile: " + profile2 + ") PID: " + pid2);
        
        // Salvar PIDs
        profileManager.savePid(profile1, pid1);
        profileManager.savePid(profile2, pid2);
        
        // Verificar que ambos estão rodando
        assertTrue(profileManager.isProcessRunning(pid1), "Chrome 1 deve estar rodando");
        assertTrue(profileManager.isProcessRunning(pid2), "Chrome 2 deve estar rodando");
        
        System.out.println("\nAgora você deve ver 2 janelas Chrome abertas!");
        System.out.println("Aguardando 5 segundos para você visualizar...");
        Thread.sleep(5000);
        
        // Matar apenas o Chrome 1
        System.out.println("\nMatando apenas o PRIMEIRO Chrome (profile: " + profile1 + ")...");
        System.out.println("A janela 1 deve fechar, mas a janela 2 deve continuar aberta!");
        boolean killed = profileManager.killProcessByProfile(profile1);
        assertTrue(killed, "Deve conseguir matar o processo do profile 1");
        
        // Aguardar um pouco
        System.out.println("Aguardando 5 segundos para você ver que apenas 1 janela fechou...");
        Thread.sleep(5000);
        
        // Verificar que apenas Chrome 1 foi morto
        assertFalse(profileManager.isProcessRunning(pid1), "Chrome 1 não deve estar mais rodando");
        assertTrue(profileManager.isProcessRunning(pid2), "Chrome 2 ainda deve estar rodando");
        
        System.out.println("✓ Sucesso! Apenas o Chrome 1 foi fechado!");
        System.out.println("Agora fechando o Chrome 2...");
        
        // Fechar Chrome 2 normalmente
        chrome2.quit().get(10, TimeUnit.SECONDS);
        
        System.out.println("✓ Conseguiu matar apenas o Chrome específico do profile");
    }
    
    @Test
    @Order(7)
    public void testProfileRestart() throws Exception {
        System.out.println("\n=== Teste 7: Simular reinício de aplicação ===");
        System.out.println("Este é o teste MAIS IMPORTANTE - simula quando seu programa crasheia!");
        
        String profileName = "profile_restart_test";
        
        // Primeira execução: criar Chrome e salvar PID
        System.out.println("\nPrimeira execução: iniciando Chrome...");
        ChromeOptions options = new ChromeOptions();
        // options.addArgument("--headless=new"); // COMENTADO
        
        // Configurar para abrir direto no Google (sem about:blank)
        options.setStartupUrl("https://www.google.com");
        
        Chrome chrome1 = Chrome.create(options).get(30, TimeUnit.SECONDS);
        long pid1 = chrome1.getBrowserPid();
        profileManager.savePid(profileName, pid1);
        
        // Aguardar carregar
        System.out.println("Aguardando Google carregar...");
        Thread.sleep(3000);
        String titulo1 = chrome1.getTitle().get();
        
        System.out.println("Chrome rodando com PID: " + pid1);
        System.out.println("Título da página: " + titulo1);
        System.out.println("Você deve ver 1 janela Chrome aberta com GOOGLE.COM!");
        System.out.println("Aguardando 5 segundos...");
        Thread.sleep(5000);
        
        // Simular "crash" da aplicação (não fechar o Chrome)
        System.out.println("\n*** SIMULANDO CRASH DA APLICAÇÃO ***");
        System.out.println("Normalmente o Chrome fecharia, mas como crasheou, ele continua aberto!");
        System.out.println("A janela Chrome deve continuar aberta...");
        Thread.sleep(3000);
        
        // Segunda execução: detectar Chrome antigo e fechar
        System.out.println("\n*** REINICIANDO APLICAÇÃO ***");
        System.out.println("Detectando se já existe Chrome rodando para este profile...");
        boolean hadOldProcess = profileManager.cleanupOldProcess(profileName);
        assertTrue(hadOldProcess, "Deveria detectar processo antigo");
        
        System.out.println("PROCESSO ANTIGO DETECTADO! Fechando a janela antiga...");
        System.out.println("A janela Chrome deve fechar agora!");
        
        // Aguardar processo ser encerrado
        Thread.sleep(3000);
        
        // Verificar que o processo foi morto
        assertFalse(profileManager.isProcessRunning(pid1), "Chrome antigo deve ter sido encerrado");
        System.out.println("✓ Chrome antigo fechado com sucesso!");
        
        // Agora pode criar novo Chrome com mesmo profile
        System.out.println("\nAgora criando NOVO Chrome com o mesmo profile...");
        System.out.println("Uma NOVA janela deve abrir!");
        
        // Configurar para abrir direto no GitHub (sem about:blank)
        ChromeOptions options2 = new ChromeOptions();
        options2.setStartupUrl("https://www.github.com");
        
        Chrome chrome2 = Chrome.create(options2).get(30, TimeUnit.SECONDS);
        long pid2 = chrome2.getBrowserPid();
        profileManager.savePid(profileName, pid2);
        
        // Aguardar carregar
        System.out.println("Aguardando GitHub carregar...");
        Thread.sleep(3000);
        String titulo2 = chrome2.getTitle().get();
        
        System.out.println("Novo Chrome rodando com PID: " + pid2);
        System.out.println("Título da página: " + titulo2);
        System.out.println("PID antigo era: " + pid1 + ", novo PID: " + pid2);
        assertNotEquals(pid1, pid2, "Novo Chrome deve ter PID diferente");
        
        System.out.println("\n⚠️  VERIFICAR: Você deve ver APENAS 1 ABA com GITHUB.COM!");
        System.out.println("⚠️  Se você ver GOOGLE.COM ou mais de 1 aba, algo está errado!");
        System.out.println("Aguardando 5 segundos para você visualizar...");
        Thread.sleep(5000);
        
        // Fechar Chrome normalmente
        System.out.println("Fechando novo Chrome...");
        chrome2.quit().get(10, TimeUnit.SECONDS);
        
        System.out.println("✓ Reinício de profile funcionando corretamente");
    }
    
    @Test
    @Order(8)
    public void testProfileNameSanitization() throws Exception {
        System.out.println("\n=== Teste 8: Sanitização de nomes de profile ===");
        
        // Testar com nomes que podem causar problemas no sistema de arquivos
        String[] problematicNames = {
            "janela:com:dois pontos",
            "janela/com/barras",
            "janela\\com\\barras invertidas",
            "janela com espaços",
            "janela_normal"
        };
        
        for (String name : problematicNames) {
            try {
                profileManager.savePid(name, 12345);
                Optional<Long> loaded = profileManager.loadPid(name);
                assertTrue(loaded.isPresent(), "Deve conseguir salvar/carregar profile: " + name);
                System.out.println("✓ Profile aceito: " + name);
            } catch (Exception e) {
                System.out.println("✗ Profile problemático: " + name + " - " + e.getMessage());
            }
        }
    }
}

