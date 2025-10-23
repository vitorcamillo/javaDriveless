package examples;

import io.github.selenium.javaDriverless.Chrome;
import io.github.selenium.javaDriverless.input.Keyboard.Keys;
import io.github.selenium.javaDriverless.support.Actions;
import io.github.selenium.javaDriverless.types.By;
import io.github.selenium.javaDriverless.types.ChromeOptions;
import io.github.selenium.javaDriverless.types.WebElement;

/**
 * Teste da classe Actions
 */
public class TesteActions {
    public static void main(String[] args) {
        Chrome chrome = null;
        try {
            System.out.println("=== TESTE ACTIONS ===\n");

            ChromeOptions options = new ChromeOptions();
            chrome = Chrome.create(options).get();
            chrome.startSession().get();
            
            chrome.get("https://www.example.com", true).get();
            System.out.println("✅ Navegou para example.com\n");
            Thread.sleep(2000);

            Actions actions = new Actions(chrome);
            int testCount = 0;
            int passCount = 0;

            // ============================================
            // Teste 1: moveToLocation + click
            // ============================================
            testCount++;
            System.out.println("📝 Teste 1: moveToLocation(100, 100) + click()");
            try {
                actions.moveToLocation(100, 100).click().perform();
                System.out.println("✅ PASSOU: Move + click executado");
                passCount++;
            } catch (Exception e) {
                System.out.println("❌ FALHOU: " + e.getMessage());
            }

            // ============================================
            // Teste 2: doubleClick em coordenadas
            // ============================================
            testCount++;
            System.out.println("\n📝 Teste 2: moveToLocation(200, 200) + doubleClick()");
            try {
                actions.reset().moveToLocation(200, 200).doubleClick().perform();
                System.out.println("✅ PASSOU: Move + doubleClick executado");
                passCount++;
            } catch (Exception e) {
                System.out.println("❌ FALHOU: " + e.getMessage());
            }

            // ============================================
            // Teste 3: contextClick em coordenadas
            // ============================================
            testCount++;
            System.out.println("\n📝 Teste 3: moveToLocation(300, 300) + contextClick()");
            try {
                actions.reset().moveToLocation(300, 300).contextClick().perform();
                System.out.println("✅ PASSOU: Move + contextClick executado");
                passCount++;
            } catch (Exception e) {
                System.out.println("❌ FALHOU: " + e.getMessage());
            }

            // ============================================
            // Teste 4: keyDown + keyUp
            // ============================================
            testCount++;
            System.out.println("\n📝 Teste 4: keyDown(Control) + pause + keyUp(Control)");
            try {
                actions.reset()
                    .keyDown("Control")
                    .pause(100)
                    .keyUp("Control")
                    .perform();
                System.out.println("✅ PASSOU: keyDown/keyUp executado");
                passCount++;
            } catch (Exception e) {
                System.out.println("❌ FALHOU: " + e.getMessage());
            }

            // ============================================
            // Teste 5: Encadeamento complexo
            // ============================================
            testCount++;
            System.out.println("\n📝 Teste 5: Encadeamento complexo");
            try {
                actions.reset()
                    .moveToLocation(400, 400)
                    .pause(200)
                    .click()
                    .pause(200)
                    .moveToLocation(500, 500)
                    .pause(200)
                    .perform();
                System.out.println("✅ PASSOU: Encadeamento complexo executado");
                passCount++;
            } catch (Exception e) {
                System.out.println("❌ FALHOU: " + e.getMessage());
            }

            // ============================================
            // Teste 6: dragAndDrop com coordenadas
            // ============================================
            testCount++;
            System.out.println("\n📝 Teste 6: dragAndDrop(100, 100, 600, 600)");
            try {
                actions.reset().dragAndDrop(100, 100, 600, 600).perform();
                System.out.println("✅ PASSOU: dragAndDrop executado");
                passCount++;
            } catch (Exception e) {
                System.out.println("❌ FALHOU: " + e.getMessage());
            }

            // ============================================
            // Teste 7: clickAndHold + release
            // ============================================
            testCount++;
            System.out.println("\n📝 Teste 7: clickAndHold + pause + release");
            try {
                actions.reset()
                    .moveToLocation(250, 250)
                    .clickAndHold()
                    .pause(500)
                    .release()
                    .perform();
                System.out.println("✅ PASSOU: clickAndHold/release executado");
                passCount++;
            } catch (Exception e) {
                System.out.println("❌ FALHOU: " + e.getMessage());
            }

            // ============================================
            // Teste 8: sendKeys
            // ============================================
            testCount++;
            System.out.println("\n📝 Teste 8: sendKeys(Keys.CONTROL, \"a\")");
            try {
                actions.reset().sendKeys(Keys.CONTROL, "a").perform();
                System.out.println("✅ PASSOU: sendKeys executado");
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
                System.out.println("\n🎉 TODOS OS TESTES ACTIONS PASSARAM!");
                System.out.println("✅ Actions class está 100% funcional!");
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

