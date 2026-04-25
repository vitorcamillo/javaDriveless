package examples;

import io.github.selenium.javaDriverless.JavaDriverless;
import io.github.selenium.javaDriverless.types.ChromeOptions;

import java.util.concurrent.TimeUnit;

/**
 * Teste de Login no 99Freelas - CLICK AUTOMÁTICO NO CAPTCHA via CDP Pointer.
 *
 * <p>Solução comprovada: localiza o iframe/host do Cloudflare Turnstile via JS,
 * calcula coordenadas absolutas do checkbox e efetua clique real via CDP
 * (Input.dispatchMouseEvent) com movimentação humanizada Bezier.</p>
 */
public class Teste99FreelasCAPTCHA {

    private static final String EMAIL = System.getenv("FREELAS_EMAIL");
    private static final String SENHA = System.getenv("FREELAS_PASS");

    public static void main(String[] args) {
        JavaDriverless driver = null;
        try {
            System.out.println("════════════════════════════════════════════════════");
            System.out.println("   🔐 LOGIN 99FREELAS - CAPTCHA AUTOMÁTICO (CDP)");
            System.out.println("════════════════════════════════════════════════════\n");

            ChromeOptions options = new ChromeOptions();
            options.addArgument("--start-maximized");
            options.addArgument("--disable-blink-features=AutomationControlled");

            driver = new JavaDriverless("99FreelasCAPTCHA", options, false);

            System.out.println("📝 Navegando para login...");
            driver.get("https://www.99freelas.com.br/login");

            System.out.println("⏱️ Aguardando Cloudflare carregar (10s)...");
            TimeUnit.SECONDS.sleep(10);

            // ─── PREENCHER CREDENCIAIS ───
            System.out.println("\n📝 Preenchendo credenciais via JS nativo...");
            driver.executeScript(
                "(() => {" +
                    "function setNative(sel, val) {" +
                    "  var el = document.querySelector(sel);" +
                    "  if(!el) return false;" +
                    "  el.focus();" +
                    "  var setter = Object.getOwnPropertyDescriptor(window.HTMLInputElement.prototype, 'value').set;" +
                    "  setter.call(el, val);" +
                    "  el.dispatchEvent(new Event('input', {bubbles:true}));" +
                    "  el.dispatchEvent(new Event('change', {bubbles:true}));" +
                    "  return true;" +
                    "}" +
                    "setNative('#email, input[name=email], input[type=email]', '" + EMAIL + "');" +
                    "setNative('#senha, input[name=senha], input[type=password]', '" + SENHA + "');" +
                "})()"
            );
            System.out.println("   ✅ Credenciais preenchidas");

            // ─── SCROLL DO TURNSTILE PARA O CENTRO DA VIEWPORT ───
            System.out.println("\n🔒 Resolvendo Cloudflare Turnstile (100% automático)...");
            driver.executeScript(
                "var el = document.querySelector('.cf-turnstile, div[data-sitekey]');" +
                "if(el) el.scrollIntoView({behavior:'smooth', block:'center'});"
            );
            TimeUnit.MILLISECONDS.sleep(1500);

            // ─── LOCALIZAR IFRAME/HOST DO TURNSTILE ───
            String frameInfo = (String) driver.executeScript(
                "(() => {" +
                    "var selectors = [" +
                    "  {sel: '.cf-turnstile', kind: 'host'}," +
                    "  {sel: 'div[data-sitekey]', kind: 'host'}," +
                    "  {sel: 'iframe[src*=\"challenges.cloudflare.com\"]', kind: 'iframe'}," +
                    "  {sel: 'iframe[src*=\"turnstile\"]', kind: 'iframe'}" +
                    "];" +
                    "for(var i = 0; i < selectors.length; i++) {" +
                    "  var el = document.querySelector(selectors[i].sel);" +
                    "  if(el) {" +
                    "    var r = el.getBoundingClientRect();" +
                    "    if(r.width > 20 && r.height > 20) {" +
                    "      return selectors[i].kind + '|' + r.left + '|' + r.top + '|' + r.width + '|' + r.height;" +
                    "    }" +
                    "  }" +
                    "}" +
                    "return 'NOT_FOUND';" +
                "})()"
            );

            if (frameInfo == null || frameInfo.equals("NOT_FOUND")) {
                System.out.println("   ⚠️ Turnstile não encontrado! Tentando submeter mesmo assim...");
            } else {
                String[] parts = frameInfo.split("\\|");
                String kind = parts[0];
                double left = Double.parseDouble(parts[1]);
                double top = Double.parseDouble(parts[2]);
                double width = Double.parseDouble(parts[3]);
                double height = Double.parseDouble(parts[4]);

                System.out.printf("   📐 Turnstile encontrado: kind=%s left=%.0f top=%.0f w=%.0f h=%.0f%n",
                    kind, left, top, width, height);

                // Obter scroll offsets
                int scrollX = toInt(driver.executeScript("return window.scrollX || window.pageXOffset || 0"));
                int scrollY = toInt(driver.executeScript("return window.scrollY || window.pageYOffset || 0"));

                // Checkbox do Turnstile fica ~80px da borda esquerda do container
                int clickX = (int) Math.round(left + scrollX + 80);
                int clickY = (int) Math.round(top + scrollY + (height * 0.5));

                System.out.printf("   🎯 Click CDP em (%d, %d)%n", clickX, clickY);

                // Efeito visual de debug (ponto vermelho)
                try {
                    driver.executeScript(
                        "(() => {" +
                            "const dot = document.createElement('div');" +
                            "dot.style.cssText = 'position:absolute;left:" + clickX + "px;top:" + clickY + "px;" +
                            "width:15px;height:15px;background:rgba(255,0,0,0.8);border:2px solid white;" +
                            "border-radius:50%;z-index:2147483647;pointer-events:none;" +
                            "transform:translate(-50%,-50%);box-shadow:0 0 10px 2px red;';" +
                            "document.body.appendChild(dot);" +
                            "setTimeout(() => dot.remove(), 10000);" +
                        "})()"
                    );
                } catch (Exception ignore) {}

                driver.screenshot("turnstile_pre_click.png");

                // CLIQUE REAL via CDP Pointer com movimentação humanizada Bezier
                driver.getPointer().click(clickX, clickY, true, 0.6, 2.0, 18.0).get();

                System.out.println("   ⏱️ Aguardando validação do Turnstile (5s)...");
                TimeUnit.SECONDS.sleep(5);

                System.out.println("   ✅ Clique efetuado com sucesso!");
            }

            // ─── CLICAR NO BOTÃO ENTRAR ───
            System.out.println("\n🖱️ Clicando no botão Entrar...");
            String btnInfo = (String) driver.executeScript(
                "var btn = document.querySelector('#btnEfetuarLogin, button.btn-green, button[type=submit]');" +
                "if(btn) {" +
                "  var r = btn.getBoundingClientRect();" +
                "  return r.left + '|' + r.top + '|' + r.width + '|' + r.height;" +
                "}" +
                "return 'NOT_FOUND';"
            );

            if (btnInfo != null && !btnInfo.equals("NOT_FOUND")) {
                String[] btnParts = btnInfo.split("\\|");
                double btnLeft = Double.parseDouble(btnParts[0]);
                double btnTop = Double.parseDouble(btnParts[1]);
                double btnW = Double.parseDouble(btnParts[2]);
                double btnH = Double.parseDouble(btnParts[3]);

                int scrollX = toInt(driver.executeScript("return window.scrollX || 0"));
                int scrollY = toInt(driver.executeScript("return window.scrollY || 0"));

                int btnClickX = (int) Math.round(btnLeft + scrollX + (btnW * 0.5));
                int btnClickY = (int) Math.round(btnTop + scrollY + (btnH * 0.5));

                System.out.printf("   ✅ Click no botão Entrar via Pointer CDP em (%d, %d)%n", btnClickX, btnClickY);
                driver.getPointer().click(btnClickX, btnClickY, true, 0.6, 2.0, 18.0).get();
            } else {
                System.out.println("   ⚠️ Botão não encontrado, tentando form submit via JS...");
                driver.executeScript(
                    "var form = document.querySelector('#frmEfetuarLogin, form');" +
                    "if(form) form.submit();"
                );
            }

            // ─── AGUARDAR RESULTADO ───
            System.out.println("\n⏱️ Aguardando resultado do login (8s)...");
            TimeUnit.SECONDS.sleep(8);

            String url = driver.getCurrentUrl();
            System.out.println("\n📊 RESULTADO:");
            System.out.println("   URL: " + url);

            if (url != null && url.contains("/mfa/session/confirm")) {
                System.out.println("\n🔒 Tela de MFA detectada!");
                System.out.println("   ⏳ Aguardando 120s para resolução manual do MFA...");

                // Captura info do MFA
                try {
                    Object mfaInfo = driver.executeScript(
                        "var el = document.querySelector('.mfa-info, .auth-info, main, .content');" +
                        "return el ? el.innerText : 'MFA requisitado';"
                    );
                    System.out.println("   Info: " + mfaInfo);
                } catch (Exception ignore) {}

                driver.screenshot("mfa_aguardando.png");

                // Aguardar resolução manual (sem Telegram neste standalone)
                TimeUnit.SECONDS.sleep(120);

                url = driver.getCurrentUrl();
                System.out.println("   URL pós-MFA: " + url);
            }

            if (url != null && (url.contains("dashboard") || url.contains("projects") || !url.contains("login"))) {
                System.out.println("\n✅ LOGIN BEM-SUCEDIDO!");
                driver.screenshot("login_sucesso.png");
            } else {
                System.out.println("\n⚠️ LOGIN FALHOU");
                driver.screenshot("login_falhou.png");
            }

            System.out.println("\n⏱️ Aguardando 30s para você verificar...");
            TimeUnit.SECONDS.sleep(30);

        } catch (Exception e) {
            System.err.println("\n❌ ERRO: " + e.getMessage());
            e.printStackTrace();
        } finally {
            if (driver != null) {
                driver.quit();
            }
        }
    }

    /**
     * Converte o retorno do executeScript para int de forma segura.
     * O CDP pode retornar Integer, Long, Double ou String dependendo do valor.
     */
    private static int toInt(Object obj) {
        if (obj == null) return 0;
        if (obj instanceof Number) return ((Number) obj).intValue();
        try {
            return (int) Double.parseDouble(String.valueOf(obj));
        } catch (NumberFormatException e) {
            return 0;
        }
    }
}
