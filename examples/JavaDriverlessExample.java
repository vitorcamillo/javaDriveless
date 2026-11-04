import io.github.selenium.javaDriverless.JavaDriverless;
import io.github.selenium.javaDriverless.types.ChromeOptions;

/**
 * Exemplos de uso do JavaDriverless com sistema de profiles integrado.
 */
public class JavaDriverlessExample {
    
    public static void main(String[] args) {
        System.out.println("╔════════════════════════════════════════════════════════════╗");
        System.out.println("║      JavaDriverless - Exemplos de Uso com Profiles        ║");
        System.out.println("╚════════════════════════════════════════════════════════════╝\n");
        
        // Descomentar o exemplo que deseja testar:
        
        exemplo1_ProfilePadrao();
        // exemplo2_ProfileComNome();
        // exemplo3_SemGerenciamento();
        // exemplo4_MultiplosBots();
        // exemplo5_ComOpcoes();
    }
    
    /**
     * EXEMPLO 1: Usar profile padrão "default"
     * 
     * - Cria pasta profiles/ na raiz do projeto
     * - Usa nome "default" para o profile
     * - Gerenciamento automático ativado
     */
    public static void exemplo1_ProfilePadrao() {
        System.out.println("\n═══ EXEMPLO 1: Profile Padrão ═══\n");
        
        try {
            // Simplesmente criar - usa profile "default"
            JavaDriverless driver = new JavaDriverless();
            
            // Usar normalmente
            driver.get("https://www.google.com");
            System.out.println("Título: " + driver.getTitle());
            
            // Aguardar para visualizar
            driver.sleep(3);
            
            // Fechar
            driver.quit();
            
            System.out.println("✅ Exemplo 1 concluído!");
            
        } catch (Exception e) {
            e.printStackTrace();
        }
    }
    
    /**
     * EXEMPLO 2: Usar profile com nome específico
     * 
     * - Cria pasta profiles/MeuBot/ na raiz do projeto
     * - Mantém login, cookies, histórico separados
     * - Se crashear e reiniciar, fecha apenas este Chrome
     */
    public static void exemplo2_ProfileComNome() {
        System.out.println("\n═══ EXEMPLO 2: Profile com Nome ═══\n");
        
        try {
            // Criar com nome específico
            JavaDriverless driver = new JavaDriverless("MeuBot");
            
            // Navegar
            driver.get("https://example.com");
            System.out.println("Site: " + driver.getTitle());
            
            // Trabalhar...
            driver.sleep(5);
            
            // Fechar
            driver.quit();
            
            System.out.println("✅ Exemplo 2 concluído!");
            System.out.println("📂 Profile salvo em: ./profiles/MeuBot/");
            
        } catch (Exception e) {
            e.printStackTrace();
        }
    }
    
    /**
     * EXEMPLO 3: Desabilitar gerenciamento (como era antes)
     * 
     * - NÃO cria pasta profiles/
     * - NÃO salva PIDs
     * - Profile temporário (deletado ao fechar)
     * - Útil para testes rápidos
     */
    public static void exemplo3_SemGerenciamento() {
        System.out.println("\n═══ EXEMPLO 3: Sem Gerenciamento ═══\n");
        
        try {
            // Desabilitar gerenciamento
            JavaDriverless driver = new JavaDriverless(false);
            
            // Usar normalmente
            driver.get("https://www.github.com");
            System.out.println("Site: " + driver.getTitle());
            
            // Aguardar
            driver.sleep(3);
            
            // Fechar (profile temporário será deletado)
            driver.quit();
            
            System.out.println("✅ Exemplo 3 concluído!");
            System.out.println("ℹ️  Nenhum profile foi salvo (modo temporário)");
            
        } catch (Exception e) {
            e.printStackTrace();
        }
    }
    
    /**
     * EXEMPLO 4: Múltiplos bots rodando simultaneamente
     * 
     * - Cada bot tem seu próprio Chrome
     * - Cada bot tem seu próprio profile em profiles/Bot_X/
     * - Se um crashear, só afeta aquele bot específico
     * - Ao reiniciar, cada bot fecha apenas seu Chrome antigo
     */
    public static void exemplo4_MultiplosBots() {
        System.out.println("\n═══ EXEMPLO 4: Múltiplos Bots ═══\n");
        
        String[] nomesBots = {"Bot_1", "Bot_2", "Bot_3"};
        
        for (String nome : nomesBots) {
            // Cada bot roda em thread separada
            new Thread(() -> {
                try {
                    JavaDriverless driver = new JavaDriverless(nome);
                    
                    driver.get("https://www.google.com");
                    System.out.println(nome + " iniciado: " + driver.getTitle());
                    
                    // Trabalhar por 10 segundos
                    driver.sleep(10);
                    
                    driver.quit();
                    System.out.println(nome + " encerrado!");
                    
                } catch (Exception e) {
                    e.printStackTrace();
                }
            }).start();
        }
        
        System.out.println("\n✅ 3 bots iniciados em paralelo!");
        System.out.println("📂 Profiles salvos em: ./profiles/Bot_1/, ./profiles/Bot_2/, ./profiles/Bot_3/");
        
        try {
            Thread.sleep(15000); // Aguardar bots finalizarem
        } catch (InterruptedException e) {
            e.printStackTrace();
        }
    }
    
    /**
     * EXEMPLO 5: Com opções customizadas
     * 
     * - Define opções específicas do Chrome
     * - Ainda usa gerenciamento de profiles
     */
    public static void exemplo5_ComOpcoes() {
        System.out.println("\n═══ EXEMPLO 5: Com Opções ═══\n");
        
        try {
            // Criar opções customizadas
            ChromeOptions options = new ChromeOptions();
            options.addArgument("--start-maximized");
            options.setStartupUrl("https://www.google.com");
            
            // Criar com opções
            JavaDriverless driver = new JavaDriverless("Bot_Customizado", options);
            
            System.out.println("Chrome aberto maximizado!");
            System.out.println("Título: " + driver.getTitle());
            
            driver.sleep(5);
            driver.quit();
            
            System.out.println("✅ Exemplo 5 concluído!");
            
        } catch (Exception e) {
            e.printStackTrace();
        }
    }
    
    /**
     * EXEMPLO EXTRA: Simular crash e reinício
     */
    public static void exemplo6_SimularCrash() {
        System.out.println("\n═══ EXEMPLO 6: Simular Crash e Reinício ═══\n");
        
        String profileName = "BotTeste_Crash";
        
        try {
            System.out.println("=== PRIMEIRA EXECUÇÃO ===");
            JavaDriverless driver1 = new JavaDriverless(profileName);
            driver1.get("https://www.google.com");
            System.out.println("Bot rodando com PID: " + driver1.getPid());
            
            // Simular crash (NÃO chamar quit())
            System.out.println("\n*** SIMULANDO CRASH (Chrome continua aberto) ***");
            System.out.println("Aguarde 5 segundos...\n");
            Thread.sleep(5000);
            
            // "Reiniciar aplicação"
            System.out.println("=== REINICIANDO APLICAÇÃO ===");
            JavaDriverless driver2 = new JavaDriverless(profileName);
            // Automaticamente detecta e fecha o Chrome antigo!
            
            driver2.get("https://www.github.com");
            System.out.println("Novo bot rodando com PID: " + driver2.getPid());
            
            driver2.sleep(3);
            driver2.quit();
            
            System.out.println("✅ Exemplo 6 concluído!");
            System.out.println("✓ Chrome antigo foi detectado e fechado automaticamente!");
            
        } catch (Exception e) {
            e.printStackTrace();
        }
    }
}

