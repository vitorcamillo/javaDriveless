package examples;

import io.github.selenium.javaDriverless.Chrome;
import io.github.selenium.javaDriverless.types.Alert;
import io.github.selenium.javaDriverless.types.ChromeOptions;

import java.nio.file.Paths;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

/**
 * Teste REAL de Alerts
 */
public class TesteAlertsReal {
    public static void main(String[] args) {
        Chrome chrome = null;
        try {
            System.out.println("=== TESTE ALERTS REAL ===\n");

            ChromeOptions options = new ChromeOptions();
            chrome = Chrome.create(options).get();
            chrome.startSession().get();
            
            // Carregar página de teste
            String htmlPath = Paths.get("test_alerts.html").toAbsolutePath().toUri().toString();
            chrome.get(htmlPath, true).get();
            System.out.println("✅ Carregou página de teste");
            Thread.sleep(2000);

            int testCount = 0;
            int passCount = 0;

            // ============================================
            // Teste 1: Disparar alert via JavaScript
            // ============================================
            testCount++;
            System.out.println("\n📝 Teste 1: Disparar alert() via JavaScript");
            
            // Executar alert em paralelo (não aguardar, senão trava)
            CompletableFuture<Object> alertFuture = chrome.executeScript(
                "setTimeout(() => alert('Teste alert!'), 100)",
                null, false
            );
            
            Thread.sleep(500); // Aguardar alert aparecer
            
            try {
                Alert alert = chrome.switchTo().alert().get(3, TimeUnit.SECONDS);
                System.out.println("✅ PASSOU: Alert detectado");
                passCount++;
                
                // Pegar texto
                String texto = alert.getText().get();
                System.out.println("   - Texto: " + texto);
                
                // Aceitar
                alert.accept().get();
                System.out.println("   - Alert aceito");
                
            } catch (Exception e) {
                System.out.println("❌ FALHOU: " + e.getMessage());
            }
            
            Thread.sleep(1000);

            // ============================================
            // Teste 2: Confirm
            // ============================================
            testCount++;
            System.out.println("\n📝 Teste 2: Disparar confirm()");
            
            CompletableFuture<Object> confirmFuture = chrome.executeScript(
                "setTimeout(() => confirm('Você confirma?'), 100)",
                null, false
            );
            
            Thread.sleep(500);
            
            try {
                Alert confirm = chrome.switchTo().alert().get(3, TimeUnit.SECONDS);
                System.out.println("✅ PASSOU: Confirm detectado");
                passCount++;
                
                // Dismiss (cancelar)
                confirm.dismiss().get();
                System.out.println("   - Confirm cancelado");
                
            } catch (Exception e) {
                System.out.println("❌ FALHOU: " + e.getMessage());
            }

            Thread.sleep(1000);

            // ============================================
            // Teste 3: Prompt
            // ============================================
            testCount++;
            System.out.println("\n📝 Teste 3: Disparar prompt()");
            
            CompletableFuture<Object> promptFuture = chrome.executeScript(
                "setTimeout(() => prompt('Digite algo:', 'default'), 100)",
                null, false
            );
            
            Thread.sleep(500);
            
            try {
                Alert prompt = chrome.switchTo().alert().get(3, TimeUnit.SECONDS);
                System.out.println("✅ PASSOU: Prompt detectado");
                passCount++;
                
                // Enviar texto
                prompt.sendKeys("JavaDriverless").get();
                System.out.println("   - Texto enviado: JavaDriverless");
                
                // Aceitar
                prompt.accept().get();
                System.out.println("   - Prompt aceito");
                
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
            }

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

