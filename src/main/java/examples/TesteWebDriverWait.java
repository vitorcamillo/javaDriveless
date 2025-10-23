package examples;

import io.github.selenium.javaDriverless.Chrome;
import io.github.selenium.javaDriverless.support.ExpectedConditions;
import io.github.selenium.javaDriverless.support.WebDriverWait;
import io.github.selenium.javaDriverless.types.ChromeOptions;

import java.time.Duration;

/**
 * Teste REAL do WebDriverWait e ExpectedConditions
 */
public class TesteWebDriverWait {
    public static void main(String[] args) {
        Chrome chrome = null;
        try {
            System.out.println("=== TESTE WEBDRIVERWAIT ===\n");

            ChromeOptions options = new ChromeOptions();
            chrome = Chrome.create(options).get();
            chrome.startSession().get();
            
            WebDriverWait wait = new WebDriverWait(chrome, Duration.ofSeconds(10));
            int testCount = 0;
            int passCount = 0;

            // ============================================
            // Teste 1: titleContains
            // ============================================
            testCount++;
            System.out.println("\n📝 Teste 1: Aguardar título conter 'Example'");
            chrome.get("https://www.example.com", false).get();
            
            try {
                Boolean result = wait.until(ExpectedConditions.titleContains("Example"));
                if (result) {
                    System.out.println("✅ PASSOU: Título contém 'Example'");
                    passCount++;
                } else {
                    System.out.println("❌ FALHOU: Título não contém 'Example'");
                }
            } catch (Exception e) {
                System.out.println("❌ FALHOU: Timeout - " + e.getMessage());
            }

            // ============================================
            // Teste 2: urlContains
            // ============================================
            testCount++;
            System.out.println("\n📝 Teste 2: Aguardar URL conter 'example.com'");
            
            try {
                Boolean result = wait.until(ExpectedConditions.urlContains("example.com"));
                if (result) {
                    System.out.println("✅ PASSOU: URL contém 'example.com'");
                    passCount++;
                } else {
                    System.out.println("❌ FALHOU: URL não contém 'example.com'");
                }
            } catch (Exception e) {
                System.out.println("❌ FALHOU: Timeout - " + e.getMessage());
            }

            // ============================================
            // Teste 3: presenceOfElement
            // ============================================
            testCount++;
            System.out.println("\n📝 Teste 3: Aguardar elemento <h1> existir");
            
            try {
                Boolean result = wait.until(ExpectedConditions.presenceOfElement("h1"));
                if (result) {
                    System.out.println("✅ PASSOU: Elemento <h1> existe");
                    passCount++;
                } else {
                    System.out.println("❌ FALHOU: Elemento <h1> não existe");
                }
            } catch (Exception e) {
                System.out.println("❌ FALHOU: Timeout - " + e.getMessage());
            }

            // ============================================
            // Teste 4: visibilityOfElement
            // ============================================
            testCount++;
            System.out.println("\n📝 Teste 4: Aguardar elemento <h1> estar visível");
            
            try {
                Boolean result = wait.until(ExpectedConditions.visibilityOfElement("h1"));
                if (result) {
                    System.out.println("✅ PASSOU: Elemento <h1> está visível");
                    passCount++;
                } else {
                    System.out.println("❌ FALHOU: Elemento <h1> não está visível");
                }
            } catch (Exception e) {
                System.out.println("❌ FALHOU: Timeout - " + e.getMessage());
            }

            // ============================================
            // Teste 5: textToBePresentInElement
            // ============================================
            testCount++;
            System.out.println("\n📝 Teste 5: Aguardar texto 'Example Domain' em <h1>");
            
            try {
                Boolean result = wait.until(
                    ExpectedConditions.textToBePresentInElement("h1", "Example Domain")
                );
                if (result) {
                    System.out.println("✅ PASSOU: Texto encontrado em <h1>");
                    passCount++;
                } else {
                    System.out.println("❌ FALHOU: Texto não encontrado");
                }
            } catch (Exception e) {
                System.out.println("❌ FALHOU: Timeout - " + e.getMessage());
            }

            // ============================================
            // Teste 6: jsReturnsTrue (verificar se página carregou)
            // ============================================
            testCount++;
            System.out.println("\n📝 Teste 6: Aguardar document.readyState === 'complete'");
            
            try {
                Boolean result = wait.until(
                    ExpectedConditions.jsReturnsTrue("document.readyState === 'complete'")
                );
                if (result) {
                    System.out.println("✅ PASSOU: Página completamente carregada");
                    passCount++;
                } else {
                    System.out.println("❌ FALHOU: Página não carregou");
                }
            } catch (Exception e) {
                System.out.println("❌ FALHOU: Timeout - " + e.getMessage());
            }

            // ============================================
            // Teste 7: numberOfWindowsToBe
            // ============================================
            testCount++;
            System.out.println("\n📝 Teste 7: Aguardar 1 janela");
            
            try {
                Boolean result = wait.until(ExpectedConditions.numberOfWindowsToBe(1));
                if (result) {
                    System.out.println("✅ PASSOU: Número correto de janelas");
                    passCount++;
                } else {
                    System.out.println("❌ FALHOU: Número incorreto de janelas");
                }
            } catch (Exception e) {
                System.out.println("❌ FALHOU: Timeout - " + e.getMessage());
            }

            // ============================================
            // Teste 8: Polling interval customizado
            // ============================================
            testCount++;
            System.out.println("\n📝 Teste 8: WebDriverWait com polling de 200ms");
            WebDriverWait fastWait = new WebDriverWait(chrome, Duration.ofSeconds(5))
                .pollingEvery(Duration.ofMillis(200));
            
            try {
                Boolean result = fastWait.until(ExpectedConditions.titleContains("Example"));
                if (result) {
                    System.out.println("✅ PASSOU: Polling customizado funciona");
                    passCount++;
                } else {
                    System.out.println("❌ FALHOU");
                }
            } catch (Exception e) {
                System.out.println("❌ FALHOU: Timeout - " + e.getMessage());
            }

            // ============================================
            // RESUMO FINAL
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
                System.out.println("\n🎉 TODOS OS TESTES WEBDRIVERWAIT PASSARAM!");
                System.out.println("✅ WebDriverWait funciona perfeitamente");
                System.out.println("✅ ExpectedConditions funcionam perfeitamente");
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

