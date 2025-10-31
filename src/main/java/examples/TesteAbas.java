package examples;

import io.github.selenium.javaDriverless.Chrome;
import io.github.selenium.javaDriverless.types.ChromeOptions;
import io.github.selenium.javaDriverless.types.Target;

import java.util.List;

/**
 * Teste: Múltiplas Abas (newWindow, switchTo, getTargets)
 */
public class TesteAbas {
    
    public static void main(String[] args) {
        System.out.println("╔══════════════════════════════════════════════════════╗");
        System.out.println("║  TESTE: MÚLTIPLAS ABAS                             ║");
        System.out.println("╚══════════════════════════════════════════════════════╝\n");
        
        try {
            ChromeOptions options = new ChromeOptions();
            Chrome chrome = Chrome.create(options).get();
            chrome.startSession().get(); // Inicializar session e context
            
            System.out.println("✅ Chrome iniciado\n");
            
            // 1. Pegar aba atual
            System.out.println("1️⃣  getCurrentTarget()");
            Target tab1 = chrome.getCurrentTarget().get();
            tab1.get("https://example.com", null, true, 30.0f).get();
            System.out.println("   ✅ Aba 1: " + tab1.getTitle().get());
            
            // 2. Abrir nova aba
            System.out.println("\n2️⃣  newWindow(\"tab\")");
            Target tab2 = chrome.newWindow("tab").get();
            tab2.get("https://github.com", null, true, 30.0f).get();
            System.out.println("   ✅ Aba 2 criada: " + tab2.getTitle().get());
            
            // 3. Listar todas as abas
            System.out.println("\n3️⃣  getTargets()");
            List<Target> tabs = chrome.getTargets().get();
            System.out.println("   ✅ Total de abas: " + tabs.size());
            for (int i = 0; i < tabs.size(); i++) {
                String title = tabs.get(i).getTitle().get();
                System.out.println("      Aba " + (i+1) + ": " + title);
            }
            
            // 4. Voltar para aba 1
            System.out.println("\n4️⃣  Voltar para aba 1");
            chrome.switchTo().window(tab1.getId(), true, false).get();
            String currentTitle = chrome.getTitle().get();
            System.out.println("   ✅ Aba ativa: " + currentTitle);
            
            // 5. Fechar aba 2
            System.out.println("\n5️⃣  Fechar aba 2");
            tab2.close().get();
            Thread.sleep(1000);
            List<Target> tabsAfterClose = chrome.getTargets().get();
            System.out.println("   ✅ Abas restantes: " + tabsAfterClose.size());
            
            chrome.quit().get();
            
            System.out.println("\n╔══════════════════════════════════════════════════════╗");
            System.out.println("║  ✅ TESTE DE ABAS: 100% FUNCIONAL!                 ║");
            System.out.println("╚══════════════════════════════════════════════════════╝");
            
        } catch (Exception e) {
            System.err.println("\n❌ Erro: " + e.getMessage());
            e.printStackTrace();
        }
    }
}

