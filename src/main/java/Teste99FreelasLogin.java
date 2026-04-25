package examples;

import io.github.selenium.javaDriverless.JavaDriverless;
import io.github.selenium.javaDriverless.types.By;
import io.github.selenium.javaDriverless.types.ChromeOptions;
import io.github.selenium.javaDriverless.types.WebElement;

import java.util.concurrent.TimeUnit;

/**
 * Teste de Login no 99Freelas usando JavaDriverless
 * Bypass Cloudflare Turnstile
 */
public class Teste99FreelasLogin {

    // Credenciais
    private static final String EMAIL = System.getenv("FREELAS_EMAIL");
    private static final String SENHA = System.getenv("FREELAS_PASS");

    public static void main(String[] args) {
        JavaDriverless driver = null;
        try {
            System.out.println("════════════════════════════════════════════════════");
            System.out.println("   🔐 TESTE LOGIN 99FREELAS");
            System.out.println("════════════════════════════════════════════════════\n");

            // Configurar Chrome options para evitar detecção
            ChromeOptions options = new ChromeOptions();
            options.addArgument("--start-maximized");
            options.addArgument("--disable-blink-features=AutomationControlled");
            options.addArgument("--user-agent=Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/147.0.0.0 Safari/537.36");

            // Criar driver
            driver = new JavaDriverless("99FreelasLogin", options, true);

            // Navegar para login
            System.out.println("📝 Navegando para 99Freelas login...");
            driver.get("https://www.99freelas.com.br/login");
            System.out.println("   ✅ Página carregada");

            // Aguardar Cloudflare resolver
            System.out.println("\n⏱️  Aguardando Cloudflare resolver (10s)...");
            TimeUnit.SECONDS.sleep(10);

            // Verificar se ainda está na página de login
            String url = driver.getCurrentUrl();
            System.out.println("   URL atual: " + url);

            // Tentar encontrar campos de login
            System.out.println("\n📝 Procurando campos de login...");

            try {
                // Procurar campo de email
                WebElement emailField = driver.findElement(By.css("input[type='email'], input[id='email'], input[name='email']"));
                if (emailField != null) {
                    System.out.println("   ✅ Campo email encontrado");
                    emailField.sendKeys(EMAIL);
                }
            } catch (Exception e) {
                System.out.println("   ⚠️ Campo email não encontrado: " + e.getMessage());
            }

            try {
                // Procurar campo de senha
                WebElement senhaField = driver.findElement(By.css("input[type='password'], input[id='password'], input[name='password']"));
                if (senhaField != null) {
                    System.out.println("   ✅ Campo senha encontrado");
                    senhaField.sendKeys(SENHA);
                }
            } catch (Exception e) {
                System.out.println("   ⚠️ Campo senha não encontrado: " + e.getMessage());
            }

            // Clicar no botão de login
            try {
                WebElement loginBtn = driver.findElement(By.cssSelector("button[type='submit'], #btnEfetuarLogin, button.btn-green"));
                if (loginBtn != null) {
                    System.out.println("   ✅ Botão de login encontrado");
                    loginBtn.click();
                    System.out.println("   ✅ Cliquei no botão");
                }
            } catch (Exception e) {
                System.out.println("   ⚠️ Botão não encontrado: " + e.getMessage());
            }

            // Aguardar resposta
            System.out.println("\n⏱️  Aguardando resposta do login (10s)...");
            TimeUnit.SECONDS.sleep(10);

            // Verificar resultado
            url = driver.getCurrentUrl();
            System.out.println("\n📊 RESULTADO:");
            System.out.println("   URL final: " + url);

            if (url.contains("dashboard") || url.contains("projects")) {
                System.out.println("\n✅ LOGIN BEM-SUCEDIDO!");
                System.out.println("   Redirecionado para: " + url);

                // Salvar cookies
                try {
                    Object cookies = driver.executeScript("document.cookie");
                    System.out.println("   Cookies: " + cookies);
                } catch (Exception e) {
                    System.out.println("   (Não foi possível acessar cookies)");
                }
            } else if (url.contains("login")) {
                System.out.println("\n⚠️  LOGIN PENDENTE - Verificar CAPTCHA");

                // Tirar screenshot
                driver.screenshot("login_99freelas.png");
                System.out.println("   Screenshot salvo: login_99freelas.png");
            } else {
                System.out.println("\n❓ RESULTADO INESPERADO");
            }

            System.out.println("\n⏱️  Deixando aberto por 30 segundos para verificação...");
            TimeUnit.SECONDS.sleep(30);

        } catch (Exception e) {
            System.err.println("\n❌ ERRO: " + e.getMessage());
            e.printStackTrace();
        } finally {
            if (driver != null) {
                try {
                    driver.quit();
                } catch (Exception e) {
                    // Ignorar
                }
            }
        }
    }
}
