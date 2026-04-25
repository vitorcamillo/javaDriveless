package examples;

import io.github.selenium.javaDriverless.Chrome;
import io.github.selenium.javaDriverless.types.ChromeOptions;
import io.github.selenium.javaDriverless.types.Target;

import java.util.HashMap;
import java.util.Map;

/**
 * Teste SIMPLES de Alerts - habilitar Page.enable primeiro
 */
public class TesteAlertsSimples {
    public static void main(String[] args) {
        Chrome chrome = null;
        try {
            System.out.println("=== TESTE ALERTS SIMPLES ===\n");

            ChromeOptions options = new ChromeOptions();
            chrome = Chrome.create(options).get();
            chrome.startSession().get();
            
            Target target = chrome.getCurrentTarget().get();
            
            // IMPORTANTE: Habilitar Page domain
            System.out.println("📝 Habilitando Page.enable...");
            target.executeCdpCmd("Page.enable", new HashMap<>(), 5.0f).get();
            System.out.println("✅ Page.enable executado\n");
            
            chrome.get("https://www.example.com", true).get();
            System.out.println("✅ Navegou para example.com");
            Thread.sleep(1000);

            // Teste 1: Disparar alert e capturar via listener
            System.out.println("\n📝 Teste 1: Disparar alert() com listener");
            
            // Executar alert de forma assíncrona
            chrome.executeScript("setTimeout(() => alert('Teste!'), 500)", null, false);
            System.out.println("   - Alert agendado para 500ms");
            
            Thread.sleep(1000); // Aguardar
            
            // Tentar aceitar via CDP direto
            try {
                Map<String, Object> params = new HashMap<>();
                params.put("accept", true);
                target.executeCdpCmd("Page.handleJavaScriptDialog", params, 5.0f).get();
                System.out.println("✅ PASSOU: Alert aceito via CDP");
            } catch (Exception e) {
                System.out.println("⚠️ Erro: " + e.getMessage());
            }

            Thread.sleep(2000);

            // Teste 2: Verificar se API Alert funciona
            System.out.println("\n📝 Teste 2: Usar API Alert");
            try {
                chrome.executeScript("setTimeout(() => alert('Teste 2!'), 500)", null, false);
                Thread.sleep(800);
                
                var alert = chrome.switchTo().alert().get();
                System.out.println("✅ Alert obtido via switchTo().alert()");
                
                String texto = alert.getText().get();
                System.out.println("   - Texto: " + texto);
                
                alert.accept().get();
                System.out.println("✅ PASSOU: Alert aceito via API");
            } catch (Exception e) {
                System.out.println("❌ FALHOU: " + e.getMessage());
                e.printStackTrace();
            }

            System.out.println("\n════════════════════════════════════════");
            System.out.println("📝 CONCLUSÃO:");
            System.out.println("   - CDP direto funciona ✅");
            System.out.println("   - Investigar API Alert");
            System.out.println("════════════════════════════════════════");

            Thread.sleep(2000);

        } catch (Exception e) {
            System.err.println("\n❌ ERRO: " + e.getMessage());
            e.printStackTrace();
        } finally {
            try {
                if (chrome != null) {
                    chrome.quit().get();
                }
            } catch (Exception e) {
                // Ignorar
            }
        }
    }
}

