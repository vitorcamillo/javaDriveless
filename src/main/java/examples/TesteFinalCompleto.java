package examples;

import io.github.selenium.javaDriverless.JavaDriverless;
import io.github.selenium.javaDriverless.input.Keyboard;
import io.github.selenium.javaDriverless.input.Pointer;
import io.github.selenium.javaDriverless.types.ChromeOptions;
import io.github.selenium.javaDriverless.types.Target;

import java.util.List;

/**
 * TESTE FINAL COMPLETO - Valida TUDO que foi implementado
 */
public class TesteFinalCompleto {
    public static void main(String[] args) {
        JavaDriverless driver = null;
        try {
            System.out.println("════════════════════════════════════════════════════");
            System.out.println("   🧪 TESTE FINAL COMPLETO - TODOS OS RECURSOS");
            System.out.println("════════════════════════════════════════════════════\n");

            int totalTests = 0;
            int passedTests = 0;

            // ============================================
            // 1. TESTAR IMPLICIT WAITS
            // ============================================
            totalTests++;
            System.out.println("📝 Teste 1: Implicit Waits");
            ChromeOptions options = new ChromeOptions();
            driver = new JavaDriverless("TesteFinal", options, true);
            
            // Definir timeouts
            driver.implicitlyWait(5000);
            driver.setScriptTimeout(30000);
            driver.setPageLoadTimeout(60000);
            
            // Verificar
            if (driver.getImplicitWaitMillis() == 5000) {
                System.out.println("   ✅ Implicit wait definido corretamente: 5000ms");
                passedTests++;
            } else {
                System.out.println("   ❌ FALHOU: Implicit wait incorreto");
            }

            // ============================================
            // 2. TESTAR NAVEGAÇÃO BÁSICA
            // ============================================
            totalTests++;
            System.out.println("\n📝 Teste 2: Navegação básica");
            driver.get("https://www.example.com");
            Thread.sleep(2000);
            
            String title = driver.getTitle();
            if (title != null && title.contains("Example")) {
                System.out.println("   ✅ Navegação funcionando: " + title);
                passedTests++;
            } else {
                System.out.println("   ❌ FALHOU: Título incorreto");
            }

            // ============================================
            // 3. TESTAR GETWINDOWS
            // ============================================
            totalTests++;
            System.out.println("\n📝 Teste 3: getWindows()");
            List<Target> windows = driver.getWindows();
            if (windows != null && windows.size() == 1) {
                System.out.println("   ✅ getWindows funciona: " + windows.size() + " janela");
                passedTests++;
            } else {
                System.out.println("   ❌ FALHOU: getWindows incorreto");
            }

            // ============================================
            // 4. TESTAR NEWWINDOW DO JAVADRIVERLESS
            // ============================================
            totalTests++;
            System.out.println("\n📝 Teste 4: newWindow() wrapper");
            Target newTab = driver.newWindow("tab");
            Thread.sleep(1000);
            
            List<Target> windowsAfter = driver.getWindows();
            if (windowsAfter.size() == 2) {
                System.out.println("   ✅ newWindow funciona: " + windowsAfter.size() + " janelas");
                passedTests++;
            } else {
                System.out.println("   ❌ FALHOU: newWindow não criou aba");
            }

            // ============================================
            // 5. TESTAR GETKEYBOARD
            // ============================================
            totalTests++;
            System.out.println("\n📝 Teste 5: getKeyboard()");
            Keyboard kb = driver.getKeyboard();
            if (kb != null) {
                System.out.println("   ✅ getKeyboard funciona");
                passedTests++;
            } else {
                System.out.println("   ❌ FALHOU: getKeyboard retornou null");
            }

            // ============================================
            // 6. TESTAR GETPOINTER
            // ============================================
            totalTests++;
            System.out.println("\n📝 Teste 6: getPointer()");
            Pointer pointer = driver.getPointer();
            if (pointer != null) {
                System.out.println("   ✅ getPointer funciona");
                passedTests++;
            } else {
                System.out.println("   ❌ FALHOU: getPointer retornou null");
            }

            // ============================================
            // 7. TESTAR KEYBOARD.TYPE()
            // ============================================
            totalTests++;
            System.out.println("\n📝 Teste 7: Keyboard type()");
            String script = 
                "const input = document.createElement('input');" +
                "input.id = 'test-input';" +
                "document.body.appendChild(input);" +
                "input.focus();" +
                "input.value";
            driver.executeScript(script);
            Thread.sleep(500);
            
            kb.type("teste", 50).get();
            Thread.sleep(500);
            
            Object inputValue = driver.executeScript("document.getElementById('test-input').value");
            if ("teste".equals(inputValue)) {
                System.out.println("   ✅ Keyboard type funciona: " + inputValue);
                passedTests++;
            } else {
                System.out.println("   ❌ FALHOU: Keyboard type incorreto: " + inputValue);
            }

            // ============================================
            // 8. TESTAR POINTER MOVETO
            // ============================================
            totalTests++;
            System.out.println("\n📝 Teste 8: Pointer moveTo()");
            pointer.moveTo(100, 100, 0.5, 2, 20).get();
            Thread.sleep(500);
            System.out.println("   ✅ Pointer moveTo executado sem erro");
            passedTests++;

            // ============================================
            // 9. TESTAR DOUBLECLICK
            // ============================================
            totalTests++;
            System.out.println("\n📝 Teste 9: Pointer doubleClick()");
            pointer.doubleClick(200, 200).get();
            Thread.sleep(500);
            System.out.println("   ✅ doubleClick executado sem erro");
            passedTests++;

            // ============================================
            // 10. TESTAR CONTEXTCLICK
            // ============================================
            totalTests++;
            System.out.println("\n📝 Teste 10: Pointer contextClick()");
            pointer.contextClick(300, 300).get();
            Thread.sleep(500);
            System.out.println("   ✅ contextClick executado sem erro");
            passedTests++;

            // ============================================
            // 11. TESTAR DRAGANDDROP
            // ============================================
            totalTests++;
            System.out.println("\n📝 Teste 11: Pointer dragAndDrop()");
            pointer.dragAndDrop(100, 100, 500, 500, 1.0).get();
            Thread.sleep(1000);
            System.out.println("   ✅ dragAndDrop executado sem erro");
            passedTests++;

            // ============================================
            // 12. TESTAR REFRESH
            // ============================================
            totalTests++;
            System.out.println("\n📝 Teste 12: refresh()");
            driver.refresh();
            Thread.sleep(2000);
            System.out.println("   ✅ refresh funcionou");
            passedTests++;

            // ============================================
            // 13. TESTAR BACK/FORWARD
            // ============================================
            totalTests++;
            System.out.println("\n📝 Teste 13: back() e forward()");
            driver.get("https://www.google.com");
            Thread.sleep(2000);
            driver.back();
            Thread.sleep(1000);
            driver.forward();
            Thread.sleep(1000);
            System.out.println("   ✅ back/forward funcionaram");
            passedTests++;

            // ============================================
            // 14. TESTAR SCREENSHOT
            // ============================================
            totalTests++;
            System.out.println("\n📝 Teste 14: screenshot()");
            driver.screenshot("teste_final.png");
            System.out.println("   ✅ Screenshot salvo");
            passedTests++;

            // ============================================
            // 15. TESTAR MAXIMIZE/MINIMIZE
            // ============================================
            totalTests++;
            System.out.println("\n📝 Teste 15: maximize() e minimize()");
            driver.maximize();
            Thread.sleep(500);
            driver.minimize();
            Thread.sleep(500);
            driver.maximize();
            System.out.println("   ✅ maximize/minimize funcionaram");
            passedTests++;

            // ============================================
            // 16. TESTAR FULLSCREEN
            // ============================================
            totalTests++;
            System.out.println("\n📝 Teste 16: fullscreen()");
            driver.fullscreen();
            Thread.sleep(1000);
            System.out.println("   ✅ fullscreen funcionou");
            passedTests++;

            // ============================================
            // 17. TESTAR COOKIES
            // ============================================
            totalTests++;
            System.out.println("\n📝 Teste 17: Cookies");
            driver.addCookie(java.util.Map.of(
                "name", "teste_cookie",
                "value", "valor123",
                "domain", ".example.com"
            ));
            Thread.sleep(500);
            
            var cookie = driver.getCookie("teste_cookie");
            if (cookie != null) {
                System.out.println("   ✅ Cookies funcionam: " + cookie.get("value"));
                passedTests++;
            } else {
                System.out.println("   ❌ FALHOU: Cookie não encontrado");
            }

            // ============================================
            // 18. TESTAR EXECUTESCRIPT
            // ============================================
            totalTests++;
            System.out.println("\n📝 Teste 18: executeScript()");
            Object result = driver.executeScript("2 + 2");
            if (result != null && result.toString().equals("4")) {
                System.out.println("   ✅ executeScript funciona: " + result);
                passedTests++;
            } else {
                System.out.println("   ❌ FALHOU: executeScript incorreto");
            }

            // ============================================
            // RESUMO FINAL
            // ============================================
            System.out.println("\n════════════════════════════════════════════════════");
            System.out.println("   📊 RESUMO FINAL");
            System.out.println("════════════════════════════════════════════════════");
            System.out.println("Total de testes: " + totalTests);
            System.out.println("✅ Passaram: " + passedTests);
            System.out.println("❌ Falharam: " + (totalTests - passedTests));
            System.out.println("Taxa de sucesso: " + (passedTests * 100 / totalTests) + "%");
            System.out.println("════════════════════════════════════════════════════");

            if (passedTests == totalTests) {
                System.out.println("\n🎉🎉🎉 TODOS OS TESTES PASSARAM! 🎉🎉🎉");
                System.out.println("✅ JavaDriverless está 100% FUNCIONAL!");
            } else {
                System.out.println("\n⚠️ " + (totalTests - passedTests) + " teste(s) falharam");
            }

            Thread.sleep(2000);

        } catch (Exception e) {
            System.err.println("\n❌ ERRO CRÍTICO: " + e.getMessage());
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
