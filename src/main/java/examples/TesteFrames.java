package examples;

import io.github.selenium.javaDriverless.Chrome;
import io.github.selenium.javaDriverless.types.ChromeOptions;

import java.nio.file.Paths;

/**
 * Teste REAL de Frames
 */
public class TesteFrames {
    public static void main(String[] args) {
        Chrome chrome = null;
        try {
            System.out.println("=== TESTE FRAMES ===\n");

            ChromeOptions options = new ChromeOptions();
            chrome = Chrome.create(options).get();
            chrome.startSession().get();
            
            // Carregar página de teste com frames
            String htmlPath = Paths.get("test_frames.html").toAbsolutePath().toUri().toString();
            chrome.get(htmlPath, true).get();
            System.out.println("✅ Carregou página de teste: " + htmlPath);
            Thread.sleep(2000);

            int testCount = 0;
            int passCount = 0;

            // ============================================
            // Teste 1: Verificar que estamos na página principal
            // ============================================
            testCount++;
            System.out.println("\n📝 Teste 1: Verificar contexto inicial (página principal)");
            Object mainPageId = chrome.executeScript("document.getElementById('pageId').textContent", null, false).get();
            if ("main-page".equals(mainPageId)) {
                System.out.println("✅ PASSOU: Estamos na página principal");
                passCount++;
            } else {
                System.out.println("❌ FALHOU: Não estamos na página principal: " + mainPageId);
            }

            // ============================================
            // Teste 2: Contar iframes
            // ============================================
            testCount++;
            System.out.println("\n📝 Teste 2: Contar iframes na página");
            Object iframeCount = chrome.executeScript("document.querySelectorAll('iframe').length", null, false).get();
            if (iframeCount instanceof Number && ((Number)iframeCount).intValue() == 2) {
                System.out.println("✅ PASSOU: Encontrou 2 iframes");
                passCount++;
            } else {
                System.out.println("❌ FALHOU: Número incorreto de iframes: " + iframeCount);
            }

            // ============================================
            // Teste 3: Acessar conteúdo do Frame 1 via executeScript
            // ============================================
            testCount++;
            System.out.println("\n📝 Teste 3: Acessar Frame 1 via executeScript (contentDocument)");
            String script = 
                "(function() {" +
                "  const iframe = document.getElementById('frame1');" +
                "  const doc = iframe.contentDocument || iframe.contentWindow.document;" +
                "  const elem = doc.getElementById('frame1-text');" +
                "  return elem ? elem.textContent : null;" +
                "})()";
            
            Object frame1Text = chrome.executeScript(script, null, false).get();
            if (frame1Text != null && frame1Text.toString().contains("Frame 1")) {
                System.out.println("✅ PASSOU: Acessou conteúdo do Frame 1: " + frame1Text);
                passCount++;
            } else {
                System.out.println("❌ FALHOU: Não conseguiu acessar Frame 1: " + frame1Text);
            }

            // ============================================
            // Teste 4: Digitar em input dentro do Frame 1
            // ============================================
            testCount++;
            System.out.println("\n📝 Teste 4: Digitar em input dentro do Frame 1");
            String scriptFocus = 
                "(function() {" +
                "  const iframe = document.getElementById('frame1');" +
                "  const doc = iframe.contentDocument || iframe.contentWindow.document;" +
                "  const input = doc.getElementById('frame1-input');" +
                "  input.value = 'Teste no frame 1';" +
                "  return input.value;" +
                "})()";
            
            Object inputValue = chrome.executeScript(scriptFocus, null, false).get();
            if ("Teste no frame 1".equals(inputValue)) {
                System.out.println("✅ PASSOU: Digitou no input do Frame 1");
                passCount++;
            } else {
                System.out.println("❌ FALHOU: Não conseguiu digitar no Frame 1: " + inputValue);
            }

            // ============================================
            // Teste 5: Acessar Frame 2 via executeScript
            // ============================================
            testCount++;
            System.out.println("\n📝 Teste 5: Acessar Frame 2 via executeScript");
            String script2 = 
                "(function() {" +
                "  const iframe = document.getElementById('frame2');" +
                "  const doc = iframe.contentDocument || iframe.contentWindow.document;" +
                "  const elem = doc.getElementById('frame2-text');" +
                "  return elem ? elem.textContent : null;" +
                "})()";
            
            Object frame2Text = chrome.executeScript(script2, null, false).get();
            if (frame2Text != null && frame2Text.toString().contains("Frame 2")) {
                System.out.println("✅ PASSOU: Acessou conteúdo do Frame 2: " + frame2Text);
                passCount++;
            } else {
                System.out.println("❌ FALHOU: Não conseguiu acessar Frame 2: " + frame2Text);
            }

            // ============================================
            // Teste 6: Digitar no campo da página principal
            // ============================================
            testCount++;
            System.out.println("\n📝 Teste 6: Digitar no campo da página principal");
            chrome.executeScript(
                "document.getElementById('main-input').value = 'Teste na página principal'",
                null, false
            ).get();
            Thread.sleep(300);
            
            Object mainValue = chrome.executeScript("document.getElementById('main-input').value", null, false).get();
            if ("Teste na página principal".equals(mainValue)) {
                System.out.println("✅ PASSOU: Digitou no campo principal");
                passCount++;
            } else {
                System.out.println("❌ FALHOU: Não digitou no campo principal");
            }

            // ============================================
            // Teste 7: Verificar isolamento entre frames
            // ============================================
            testCount++;
            System.out.println("\n📝 Teste 7: Verificar que frames são isolados");
            
            // Tentar acessar elemento do Frame 1 diretamente (deve falhar)
            Object directAccess = chrome.executeScript(
                "document.getElementById('frame1-text') ? 'ENCONTROU' : 'NAO_ENCONTROU'",
                null, false
            ).get();
            
            if ("NAO_ENCONTROU".equals(directAccess)) {
                System.out.println("✅ PASSOU: Frames estão isolados (não acessou frame1-text diretamente)");
                passCount++;
            } else {
                System.out.println("❌ FALHOU: Frames não estão isolados");
            }

            // ============================================
            // NOTA SOBRE switchTo().frame()
            // ============================================
            System.out.println("\n📝 NOTA: switchTo().frame()");
            System.out.println("⚠️ A API switchTo().frame() existe mas está marcada como deprecated");
            System.out.println("⚠️ Razão: Requer implementação completa de iframes CORS");
            System.out.println("✅ SOLUÇÃO: Use executeScript com contentDocument/contentWindow");
            System.out.println("   Exemplo: iframe.contentDocument.getElementById('elemento')");

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
                System.out.println("\n🎉 TODOS OS TESTES PASSARAM!");
                System.out.println("✅ Frames funcionam via executeScript + contentDocument");
            } else {
                System.out.println("\n⚠️ Alguns testes falharam.");
            }

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

