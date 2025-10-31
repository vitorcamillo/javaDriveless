import io.github.selenium.javaDriverless.Chrome;
import io.github.selenium.javaDriverless.ProfileManager;
import io.github.selenium.javaDriverless.types.ChromeOptions;

/**
 * Exemplo de uso do ProfileManager para gerenciar múltiplas automações.
 * 
 * Este exemplo mostra como:
 * 1. Criar múltiplas instâncias Chrome com profiles diferentes
 * 2. Salvar os PIDs para cada profile
 * 3. Ao reiniciar, detectar e fechar apenas o Chrome do profile específico
 */
public class ProfileManagementExample {
    
    public static void main(String[] args) {
        // Criar o gerenciador de profiles
        ProfileManager profileManager = new ProfileManager();
        
        // Nome do profile (escolha um nome único para cada automação)
        String profileName = "minha_automacao_bet365";
        
        try {
            // PASSO 1: Ao iniciar, verificar se já existe Chrome rodando deste profile
            System.out.println("Verificando se já existe Chrome rodando para: " + profileName);
            
            boolean hadOldProcess = profileManager.cleanupOldProcess(profileName);
            if (hadOldProcess) {
                System.out.println("✓ Chrome antigo foi detectado e fechado!");
            } else {
                System.out.println("✓ Nenhum Chrome antigo encontrado");
            }
            
            // PASSO 2: Criar novo Chrome
            System.out.println("\nCriando novo Chrome...");
            ChromeOptions options = new ChromeOptions();
            // Adicione suas opções aqui
            // options.setUserDataDir("C:/profiles/" + profileName);
            
            Chrome chrome = Chrome.create(options).get();
            
            // PASSO 3: Salvar o PID deste Chrome
            long pid = chrome.getBrowserPid();
            profileManager.savePid(profileName, pid);
            System.out.println("✓ Chrome criado com PID: " + pid);
            System.out.println("✓ PID salvo para profile: " + profileName);
            
            // PASSO 4: Fazer sua automação normalmente
            System.out.println("\nIniciando automação...");
            chrome.get("https://www.google.com", true).get();
            System.out.println("Título: " + chrome.getTitle().get());
            
            // Simular trabalho...
            System.out.println("Trabalhando...");
            Thread.sleep(5000);
            
            // PASSO 5: Ao encerrar normalmente, fechar Chrome
            System.out.println("\nEncerrando...");
            chrome.quit().get();
            
            // PASSO 6: Deletar arquivo de PID (opcional - o cleanup já faz isso)
            profileManager.deletePidFile(profileName);
            System.out.println("✓ Encerrado com sucesso!");
            
        } catch (Exception e) {
            e.printStackTrace();
        }
    }
    
    /**
     * Exemplo com múltiplas automações rodando simultaneamente
     */
    public static void exemploMultiplasAutomacoes() {
        ProfileManager profileManager = new ProfileManager();
        
        String[] profiles = {
            "bot_1_betano",
            "bot_2_bet365", 
            "bot_3_pixbet"
        };
        
        try {
            for (String profile : profiles) {
                // Limpar processo antigo se existir
                profileManager.cleanupOldProcess(profile);
                
                // Criar Chrome
                ChromeOptions options = new ChromeOptions();
                Chrome chrome = Chrome.create(options).get();
                
                // Salvar PID
                profileManager.savePid(profile, chrome.getBrowserPid());
                
                System.out.println("Bot '" + profile + "' iniciado!");
                
                // Cada bot faz sua automação...
                // chrome.get("https://...", true).get();
            }
            
            System.out.println("Todos os bots estão rodando!");
            System.out.println("Se crashar, ao reiniciar cada bot fechará apenas seu Chrome!");
            
        } catch (Exception e) {
            e.printStackTrace();
        }
    }
    
    /**
     * Exemplo de como listar e gerenciar profiles ativos
     */
    public static void exemploGerenciarProfiles() {
        ProfileManager profileManager = new ProfileManager();
        
        // Listar todos os profiles ativos
        System.out.println("Profiles ativos:");
        for (String profile : profileManager.listActiveProfiles()) {
            System.out.println("  - " + profile);
            
            // Obter informações do profile
            var info = profileManager.getProfileInfo(profile);
            System.out.println("    PID: " + info.get("pid"));
            System.out.println("    Rodando: " + info.get("isRunning"));
        }
        
        // Limpar profiles órfãos (processos que não estão mais rodando)
        profileManager.cleanupOrphanedPidFiles();
        System.out.println("Limpeza de arquivos órfãos concluída!");
    }
}

