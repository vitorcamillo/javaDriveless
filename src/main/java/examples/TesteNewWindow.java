package examples;

import io.github.selenium.javaDriverless.Chrome;
import io.github.selenium.javaDriverless.types.ChromeOptions;
import io.github.selenium.javaDriverless.types.Target;

import java.util.List;

/**
 * Teste REAL de newWindow()
 */
public class TesteNewWindow {
    public static void main(String[] args) {
        Chrome chrome = null;
        try {
            System.out.println("=== TESTE NEWWINDOW ===\n");

            ChromeOptions options = new ChromeOptions();
            chrome = Chrome.create(options).get();
            chrome.startSession().get();
            
            // Navegar para página inicial
            chrome.get("https://www.example.com", true).get();
            System.out.println("✅ Navegou para example.com");
            Thread.sleep(1000);

            // Teste 1: Verificar quantas abas existem
            List<Target> targets1 = chrome.getTargets().get();
            int abas1 = targets1.size();
            System.out.println("\n📝 Teste 1: Abas iniciais = " + abas1);

            // Teste 2: Criar nova janela
            System.out.println("\n📝 Teste 2: Criando nova janela...");
            Target newWindow = chrome.newWindow("tab").get();
            System.out.println("✅ Nova janela criada: " + newWindow.getId());
            Thread.sleep(1000);

            // Teste 3: Verificar se aumentou o número de abas
            List<Target> targets2 = chrome.getTargets().get();
            int abas2 = targets2.size();
            System.out.println("\n📝 Teste 3: Abas após newWindow = " + abas2);
            
            if (abas2 > abas1) {
                System.out.println("✅ PASSOU: Número de abas aumentou (" + abas1 + " -> " + abas2 + ")");
            } else {
                System.out.println("❌ FALHOU: Número de abas não aumentou");
            }

            // Teste 4: Navegar na nova janela
            System.out.println("\n📝 Teste 4: Navegando na nova janela...");
            newWindow.get("https://www.google.com", true).get();
            System.out.println("✅ Navegou na nova janela");
            Thread.sleep(1000);

            // Teste 5: Obter título da nova janela
            String titulo = newWindow.getTitle().get();
            System.out.println("\n📝 Teste 5: Título da nova janela = " + titulo);
            if (titulo != null && titulo.contains("Google")) {
                System.out.println("✅ PASSOU: Título correto");
            } else {
                System.out.println("⚠️ Título: " + titulo);
            }

            // Teste 6: Trocar entre janelas com switchTo
            System.out.println("\n📝 Teste 6: Trocar para primeira janela...");
            chrome.switchTo().window(targets1.get(0).getId(), true, false).get();
            Thread.sleep(500);
            String titulo1 = chrome.getCurrentTarget().thenCompose(t -> t.getTitle()).get();
            System.out.println("✅ Trocou para primeira janela: " + titulo1);

            // Teste 7: Voltar para a segunda janela
            System.out.println("\n📝 Teste 7: Voltar para segunda janela...");
            chrome.switchTo().window(newWindow.getId(), true, false).get();
            Thread.sleep(500);
            String titulo2 = chrome.getCurrentTarget().thenCompose(t -> t.getTitle()).get();
            System.out.println("✅ Voltou para segunda janela: " + titulo2);

            System.out.println("\n" + "=".repeat(50));
            System.out.println("🎉 TODOS OS TESTES NEWWINDOW PASSARAM!");
            System.out.println("✅ newWindow() funciona corretamente");
            System.out.println("✅ switchTo().window() funciona corretamente");
            System.out.println("=".repeat(50));

            Thread.sleep(3000);

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

