package examples;

import io.github.selenium.javaDriverless.Chrome;
import io.github.selenium.javaDriverless.types.Alert;
import io.github.selenium.javaDriverless.types.ChromeOptions;
import io.github.selenium.javaDriverless.types.Target;

import java.util.HashMap;
import java.util.concurrent.CompletableFuture;

/**
 * Teste FINAL de Alerts - método correto
 */
public class TesteAlertsFinal {
    public static void main(String[] args) {
        Chrome chrome = null;
        try {
            System.out.println("=== TESTE ALERTS FINAL ===\n");

            ChromeOptions options = new ChromeOptions();
            chrome = Chrome.create(options).get();
            chrome.startSession().get();
            
            Target target = chrome.getCurrentTarget().get();
            
            // Habilitar Page domain
            target.executeCdpCmd("Page.enable", new HashMap<>(), 5.0f).get();
            
            chrome.get("https://www.example.com", true).get();
            System.out.println("✅ Navegou para example.com\n");
            Thread.sleep(1000);

            int testCount = 0;
            int passCount = 0;

            // ============================================
            // Teste 1: Alert via CDP direto (recomendado)
            // ============================================
            testCount++;
            System.out.println("📝 Teste 1: Alert via CDP direto (RECOMENDADO)");
            
            chrome.executeScript("setTimeout(() => alert('Teste via CDP!'), 500)", null, false);
            Thread.sleep(800);
            
            try {
                target.executeCdpCmd("Page.handleJavaScriptDialog", 
                    java.util.Map.of("accept", true), 5.0f).get();
                System.out.println("✅ PASSOU: Alert aceito via CDP direto");
                passCount++;
            } catch (Exception e) {
                System.out.println("❌ FALHOU: " + e.getMessage());
            }

            Thread.sleep(500);

            // ============================================
            // Teste 2: Confirm via CDP direto
            // ============================================
            testCount++;
            System.out.println("\n📝 Teste 2: Confirm via CDP direto");
            
            chrome.executeScript("setTimeout(() => confirm('Confirma?'), 500)", null, false);
            Thread.sleep(800);
            
            try {
                target.executeCdpCmd("Page.handleJavaScriptDialog", 
                    java.util.Map.of("accept", false), 5.0f).get(); // dismiss
                System.out.println("✅ PASSOU: Confirm cancelado via CDP");
                passCount++;
            } catch (Exception e) {
                System.out.println("❌ FALHOU: " + e.getMessage());
            }

            Thread.sleep(500);

            // ============================================
            // Teste 3: Prompt com texto via CDP direto
            // ============================================
            testCount++;
            System.out.println("\n📝 Teste 3: Prompt com texto via CDP");
            
            chrome.executeScript("setTimeout(() => prompt('Digite:', 'default'), 500)", null, false);
            Thread.sleep(800);
            
            try {
                target.executeCdpCmd("Page.handleJavaScriptDialog", 
                    java.util.Map.of("accept", true, "promptText", "JavaDriverless"), 5.0f).get();
                System.out.println("✅ PASSOU: Prompt respondido via CDP");
                passCount++;
            } catch (Exception e) {
                System.out.println("❌ FALHOU: " + e.getMessage());
            }

            // ============================================
            // RESUMO
            // ============================================
            System.out.println("\n" + "=".repeat(50));
            System.out.println("📊 RESUMO DOS TESTES");
            System.out.println("=".repeat(50));
            System.out.println("Total de testes: " + testCount);
            System.out.println("✅ Passaram: " + passCount);
            System.out.println("❌ Falharam: " + (testCount - passCount));
            System.out.println("Taxa de sucesso: " + (passCount * 100 / testCount) + "%");
            System.out.println("=".repeat(50));

            if (passCount == testCount) {
                System.out.println("\n🎉 TODOS OS TESTES ALERTS PASSARAM!");
                System.out.println("✅ Alerts funcionam via CDP direto!");
            }

            System.out.println("\n💡 RECOMENDAÇÃO:");
            System.out.println("   Use Page.handleJavaScriptDialog via CDP");
            System.out.println("   É mais direto e funciona perfeitamente!");

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

