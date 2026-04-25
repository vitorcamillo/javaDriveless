import io.github.selenium.javaDriverless.JavaDriverless;
import io.github.selenium.javaDriverless.types.ChromeOptions;

/**
 * Teste Manual - Chrome fica aberto para você testar o que quiser
 * 
 * Execute: mvn compile exec:java -Dexec.mainClass="TesteManual"
 */
public class TesteManual {
    public static void main(String[] args) {
        JavaDriverless driver = null;
        try {
            System.out.println("════════════════════════════════════════════════════");
            System.out.println("   🧪 TESTE MANUAL - Chrome ficará aberto");
            System.out.println("════════════════════════════════════════════════════\n");

            // Configurar opções
            ChromeOptions options = new ChromeOptions();
            options.addArgument("--start-maximized");
            options.addArgument("--disable-blink-features=AutomationControlled");
            
            // Iniciar JavaDriverless
            driver = new JavaDriverless("TesteManual", options, true);
            
            // Navegar para Google
            System.out.println("📝 Navegando para Google...");
            driver.get("https://www.google.com");
            System.out.println("✅ Google carregado\n");
            
            System.out.println("════════════════════════════════════════════════════");
            System.out.println("   ✅ Chrome está aberto!");
            System.out.println("════════════════════════════════════════════════════");
            System.out.println("");
            System.out.println("💡 AGORA VOCÊ PODE:");
            System.out.println("   - Navegar manualmente no Chrome");
            System.out.println("   - Testar qualquer site");
            System.out.println("   - Verificar se é detectado como bot");
            System.out.println("   - Fazer login em sites");
            System.out.println("   - Testar Cloudflare, Captchas, etc");
            System.out.println("");
            System.out.println("⏱️  Chrome ficará aberto até você pressionar Ctrl+C");
            System.out.println("════════════════════════════════════════════════════\n");
            
            // Aguardar indefinidamente (até Ctrl+C)
            System.out.println("Aguardando... (Ctrl+C para fechar)");
            
            while (true) {
                Thread.sleep(1000);
            }

        } catch (InterruptedException e) {
            System.out.println("\n✅ Encerrando...");
        } catch (Exception e) {
            System.err.println("\n❌ ERRO: " + e.getMessage());
            e.printStackTrace();
        } finally {
            try {
                if (driver != null) {
                    driver.quit();
                }
            } catch (Exception e) {
                // Ignorar
            }
        }
    }
}

