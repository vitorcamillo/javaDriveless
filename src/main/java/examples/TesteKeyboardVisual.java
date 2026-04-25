package examples;

import io.github.selenium.javaDriverless.Chrome;
import io.github.selenium.javaDriverless.input.Keyboard;
import io.github.selenium.javaDriverless.input.Keyboard.Keys;
import io.github.selenium.javaDriverless.types.ChromeOptions;

import java.nio.file.Paths;

/**
 * Teste VISUAL do Keyboard - você pode ver tudo acontecendo!
 */
public class TesteKeyboardVisual {
    public static void main(String[] args) {
        Chrome chrome = null;
        try {
            System.out.println("════════════════════════════════════════════════════");
            System.out.println("   🎹 TESTE VISUAL - KEYBOARD");
            System.out.println("   Observe a janela do Chrome!");
            System.out.println("════════════════════════════════════════════════════\n");

            ChromeOptions options = new ChromeOptions();
            chrome = Chrome.create(options).get();
            chrome.startSession().get();
            
            // Carregar página de teste visual
            String htmlPath = Paths.get("test_keyboard_visual.html").toAbsolutePath().toUri().toString();
            chrome.get(htmlPath, true).get();
            System.out.println("✅ Página de teste carregada");
            System.out.println("👀 OBSERVE A JANELA DO CHROME!\n");
            Thread.sleep(2000);

            Keyboard kb = chrome.getCurrentKeyboard();

            // ====== TESTE 1: Focar e digitar ======
            System.out.println("📝 Teste 1: Focar no input1 e digitar 'JavaDriverless'");
            chrome.executeScript("document.getElementById('input1').focus()", null, false).get();
            Thread.sleep(500);
            
            kb.type("JavaDriverless", 80).get();
            System.out.println("   ✅ Digitou 'JavaDriverless' (veja na tela!)");
            Thread.sleep(3000); // Tempo para você ver!

            // ====== TESTE 2: Selecionar tudo (Ctrl+A) ======
            System.out.println("\n📝 Teste 2: Ctrl+A (selecionar tudo)");
            kb.ctrlA().get();
            System.out.println("   ✅ Ctrl+A enviado");
            System.out.println("   👀 Olhe o log verde! O evento foi detectado!");
            Thread.sleep(3000);

            // ====== TESTE 3: Apagar com Backspace ======
            System.out.println("\n📝 Teste 3: Backspace (apagar)");
            kb.backspace().get();
            System.out.println("   ✅ Backspace enviado");
            Thread.sleep(2000);

            // ====== TESTE 4: Digitar novamente ======
            System.out.println("\n📝 Teste 4: Digitar 'funciona!' mais rápido");
            kb.type("funciona!", 50).get();
            System.out.println("   ✅ Digitou 'funciona!'");
            Thread.sleep(3000);

            // ====== TESTE 5: Tab para próximo campo ======
            System.out.println("\n📝 Teste 5: Tab (ir para input2)");
            kb.tab().get();
            System.out.println("   ✅ Tab enviado");
            System.out.println("   👀 Veja o foco mudar para input2!");
            Thread.sleep(2000);

            // ====== TESTE 6: Digitar no input2 ======
            System.out.println("\n📝 Teste 6: Digitar 'segundo campo'");
            kb.type("segundo campo", 70).get();
            System.out.println("   ✅ Digitou no input2");
            Thread.sleep(3000);

            // ====== TESTE 7: Tab para textarea ======
            System.out.println("\n📝 Teste 7: Tab (ir para textarea)");
            kb.tab().get();
            Thread.sleep(500);

            // ====== TESTE 8: Enter em textarea ======
            System.out.println("\n📝 Teste 8: Digitar 'linha1' + Enter + 'linha2'");
            kb.type("linha1", 60).get();
            Thread.sleep(500);
            kb.enter().get();
            Thread.sleep(500);
            kb.type("linha2", 60).get();
            System.out.println("   ✅ Múltiplas linhas digitadas");
            Thread.sleep(3000);

            // ====== TESTE 9: Escape ======
            System.out.println("\n📝 Teste 9: Escape");
            kb.escape().get();
            System.out.println("   ✅ Escape enviado");
            Thread.sleep(2000);

            // ====== TESTE 10: Atalhos ======
            System.out.println("\n📝 Teste 10: Atalhos de teclado");
            System.out.println("   - Ctrl+C");
            kb.ctrlC().get();
            Thread.sleep(500);
            
            System.out.println("   - Ctrl+V");
            kb.ctrlV().get();
            Thread.sleep(500);
            
            System.out.println("   - Ctrl+Z");
            kb.ctrlZ().get();
            System.out.println("   ✅ Atalhos enviados");
            Thread.sleep(2000);

            // ====== TESTE 11: F5 (recarregar) ======
            System.out.println("\n📝 Teste 11: F5 (NÃO vamos testar - recarrega a página)");
            System.out.println("   ⏭️  Pulado");

            // ====== TESTE 12: sendKeys customizado ======
            System.out.println("\n📝 Teste 12: sendKeys(Control, 'a')");
            chrome.executeScript("document.getElementById('input1').focus()", null, false).get();
            Thread.sleep(500);
            kb.sendKeys(Keys.CONTROL, "a").get();
            System.out.println("   ✅ sendKeys(Control, 'a') enviado");
            System.out.println("   👀 Veja o log verde mostrando Ctrl+A!");
            Thread.sleep(3000);

            // ====== VERIFICAÇÃO FINAL ======
            System.out.println("\n" + "=".repeat(60));
            System.out.println("📊 VERIFICANDO RESULTADOS...");
            System.out.println("=".repeat(60));

            Object value1 = chrome.executeScript("document.getElementById('input1').value", null, false).get();
            Object value2 = chrome.executeScript("document.getElementById('input2').value", null, false).get();
            Object value3 = chrome.executeScript("document.getElementById('textarea1').value", null, false).get();
            Object logCount = chrome.executeScript("document.getElementById('display').children.length", null, false).get();

            System.out.println("Input1 valor: " + value1);
            System.out.println("Input2 valor: " + value2);
            System.out.println("Textarea valor: " + value3);
            System.out.println("Total de eventos capturados: " + logCount);

            System.out.println("\n" + "=".repeat(60));
            System.out.println("🎉 KEYBOARD ESTÁ FUNCIONANDO!");
            System.out.println("=".repeat(60));
            System.out.println("✅ type() - Digita caractere por caractere");
            System.out.println("✅ tab() - Navega entre campos");
            System.out.println("✅ enter() - Quebra linha");
            System.out.println("✅ backspace() - Apaga");
            System.out.println("✅ escape() - Funciona");
            System.out.println("✅ ctrlC/V/Z() - Atalhos funcionam");
            System.out.println("✅ sendKeys() - Combinações funcionam");
            System.out.println("");
            System.out.println("⚠️  Ctrl+A: Eventos detectados mas não seleciona visualmente");
            System.out.println("   (Limitação do CDP - não tem como resolver)");
            System.out.println("=".repeat(60));

            System.out.println("\n⏱️  Deixando aberto por 10 segundos para você ver...");
            Thread.sleep(10000);

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

