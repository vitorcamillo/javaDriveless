package examples;

import io.github.selenium.javaDriverless.JavaDriverless;
import io.github.selenium.javaDriverless.types.By;
import io.github.selenium.javaDriverless.types.ChromeOptions;
import io.github.selenium.javaDriverless.types.WebElement;

import java.util.concurrent.TimeUnit;

/**
 * Teste de Login no 99Freelas - MODO INTERATIVO
 * Abre Chrome visível para você resolver o CAPTCHA manualmente
 */
public class Teste99FreelasLoginInterativo {

    private static final String EMAIL = System.getenv("FREELAS_EMAIL");
    private static final String SENHA = System.getenv("FREELAS_PASS");

    public static void main(String[] args) {
        JavaDriverless driver = null;
        try {
            System.out.println("════════════════════════════════════════════════════");
            System.out.println("   🔐 LOGIN 99FREELAS - MODO INTERATIVO");
            System.out.println("════════════════════════════════════════════════════");
            System.out.println("Este teste vai:");
            System.out.println("1. Abrir Chrome visível (headful mode)");
            System.out.println("2. Navegar para 99Freelas");
            System.out.println("3. VOCÊ precisa clicar no CAPTCHA do Cloudflare");
            System.out.println("4. O sistema vai preencher e fazer login");
            System.out.println("5. Salvars cookies ao sucesso");
            System.out.println("════════════════════════════════════════════════════\n");

            // Chrome options - modo visível
            ChromeOptions options = new ChromeOptions();
            options.addArgument("--start-maximized");
            options.addArgument("--disable-blink-features=AutomationControlled");
            // Importante: NÃO usar headless para poder ver e clicar

            // Criar driver em modo headful (visível)
            driver = new JavaDriverless("99FreelasInterativo", options, false); // false = NÃO headless

            // Navegar
            System.out.println("📝 Abrindo Chrome e navegando para login...");
            driver.get("https://www.99freelas.com.br/login");

            System.out.println("\n⏱️  AGUARDANDO VOCÊ RESOLVER O CAPTCHA...");
            System.out.println("   - Se aparecer CAPTCHA, clique em 'Sou humano' ou 'Confirmar'");
            System.out.println("   - Aguarde até ver os campos de email/senha preenchidos");
            System.out.println("   - Este script vai aguardar 60 segundos\n");

            // Aguardar 60 segundos para você resolver CAPTCHA
            TimeUnit.SECONDS.sleep(60);

            // Verificar se ainda está na página de login
            String url = driver.getCurrentUrl();
            System.out.println("📝 URL atual: " + url);

            // Preencher login se ainda não estiver logado
            if (url.contains("login")) {
                System.out.println("📝 Preenchendo credenciais...");

                try {
                    WebElement emailField = driver.findElement(By.css("input[type='email'], input[id='email'], input[name='email']"));
                    emailField.sendKeys(EMAIL);
                    System.out.println("   ✅ Email preenchido");
                } catch (Exception e) {
                    System.out.println("   ⚠️ Email: " + e.getMessage());
                }

                try {
                    WebElement senhaField = driver.findElement(By.css("input[type='password'], input[id='password'], input[name='password']"));
                    senhaField.sendKeys(SENHA);
                    System.out.println("   ✅ Senha preenchida");
                } catch (Exception e) {
                    System.out.println("   ⚠️ Senha: " + e.getMessage());
                }

                try {
                    WebElement loginBtn = driver.findElement(By.cssSelector("button[type='submit'], #btnEfetuarLogin"));
                    loginBtn.click();
                    System.out.println("   ✅ Clicou em Entrar");
                } catch (Exception e) {
                    System.out.println("   ⚠️ Botão: " + e.getMessage());
                }

                // Aguardar resultado
                System.out.println("\n⏱️  Aguardando resultado do login (30s)...");
                TimeUnit.SECONDS.sleep(30);
            }

            // Verificar resultado
            url = driver.getCurrentUrl();
            System.out.println("\n📊 RESULTADO:");
            System.out.println("   URL final: " + url);

            if (url.contains("dashboard") || url.contains("projects") || !url.contains("login")) {
                System.out.println("\n✅ LOGIN BEM-SUCEDIDO!");

                // Salvar screenshot
                driver.screenshot("login_sucesso.png");
                System.out.println("   Screenshot salvo: login_sucesso.png");

                // Tentar obter cookies
                try {
                    String cookies = (String) driver.executeScript(
                        "const cookies = document.cookie.split(';').reduce((acc, c) => { " +
                        "  const [k, v] = c.trim().split('='); acc[k] = v; return acc; }, {}); " +
                        "return JSON.stringify(cookies);"
                    );
                    System.out.println("   Cookies: " + cookies);

                    // Salvar cookies em arquivo
                    java.nio.file.Files.writeString(
                        java.nio.file.Paths.get("data/cookies-99freelas.json"),
                        cookies
                    );
                    System.out.println("   Cookies salvos em: data/cookies-99freelas.json");
                } catch (Exception e) {
                    System.out.println("   ⚠️ Não foi possível obter cookies: " + e.getMessage());
                }
            } else {
                System.out.println("\n⚠️ LOGIN NÃO CONCLUÍDO");
                driver.screenshot("login_falhou.png");
            }

            System.out.println("\n⏱️  Deixando aberto por 60 segundos para você verificar...");
            TimeUnit.SECONDS.sleep(60);

        } catch (Exception e) {
            System.err.println("\n❌ ERRO: " + e.getMessage());
            e.printStackTrace();
        } finally {
            if (driver != null) {
                try {
                    driver.quit();
                } catch (Exception e) {}
            }
            System.out.println("\n✅ Teste finalizado");
        }
    }
}
