package examples;

import io.github.selenium.javaDriverless.JavaDriverless;
import io.github.selenium.javaDriverless.types.By;
import io.github.selenium.javaDriverless.types.ChromeOptions;
import io.github.selenium.javaDriverless.types.WebElement;

import java.util.concurrent.TimeUnit;

/**
 * Teste completo de funcionalidade do JavaDriverless.
 * Valida: findElement, getLocation, getSize, click, sendKeys, getPointer
 */
public class TesteElementosFuncional {

    public static void main(String[] args) {
        JavaDriverless driver = null;
        int passed = 0;
        int failed = 0;

        try {
            System.out.println("════════════════════════════════════════════════════");
            System.out.println("   🧪 TESTE FUNCIONAL - JavaDriverless WebElement");
            System.out.println("════════════════════════════════════════════════════\n");

            ChromeOptions options = new ChromeOptions();
            options.addArgument("--start-maximized");

            driver = new JavaDriverless("TesteFuncional", options, false);

            // Usar uma página de teste simples
            System.out.println("📝 Navegando para página de teste...");
            driver.get("https://www.99freelas.com.br/login");
            TimeUnit.SECONDS.sleep(8);

            String url = driver.getCurrentUrl();
            System.out.println("   URL: " + url);

            // TESTE 1: findElement por CSS
            System.out.println("\n═══ TESTE 1: findElement(By.css('#email')) ═══");
            try {
                WebElement emailField = driver.findElement(By.css("#email"));
                System.out.println("   ✅ PASS - Elemento encontrado: " + emailField);
                passed++;
            } catch (Exception e) {
                System.out.println("   ❌ FAIL - " + e.getMessage());
                failed++;
            }

            // TESTE 2: getLocation
            System.out.println("\n═══ TESTE 2: getLocation() ═══");
            try {
                WebElement emailField = driver.findElement(By.css("#email"));
                double[] loc = emailField.getLocation().get();
                System.out.println("   Location: x=" + loc[0] + ", y=" + loc[1]);
                if (loc[0] > 0 || loc[1] > 0) {
                    System.out.println("   ✅ PASS - Coordenadas válidas");
                    passed++;
                } else {
                    System.out.println("   ❌ FAIL - Coordenadas são (0,0)!");
                    failed++;
                }
            } catch (Exception e) {
                System.out.println("   ❌ FAIL - " + e.getMessage());
                failed++;
            }

            // TESTE 3: getSize
            System.out.println("\n═══ TESTE 3: getSize() ═══");
            try {
                WebElement emailField = driver.findElement(By.css("#email"));
                double[] size = emailField.getSize().get();
                System.out.println("   Size: w=" + size[0] + ", h=" + size[1]);
                if (size[0] > 0 && size[1] > 0) {
                    System.out.println("   ✅ PASS - Dimensões válidas");
                    passed++;
                } else {
                    System.out.println("   ❌ FAIL - Dimensões são zero!");
                    failed++;
                }
            } catch (Exception e) {
                System.out.println("   ❌ FAIL - " + e.getMessage());
                failed++;
            }

            // TESTE 4: click + sendKeys no campo email
            System.out.println("\n═══ TESTE 4: sendKeys() no #email ═══");
            try {
                WebElement emailField = driver.findElement(By.css("#email"));
                emailField.sendKeys("teste@teste.com").get();
                TimeUnit.MILLISECONDS.sleep(500);

                // Verificar se o valor foi preenchido
                Object value = driver.executeScript("document.querySelector('#email').value");
                System.out.println("   Valor no campo: " + value);
                if (value != null && value.toString().contains("teste")) {
                    System.out.println("   ✅ PASS - sendKeys funcionou!");
                    passed++;
                } else {
                    System.out.println("   ❌ FAIL - sendKeys não preencheu o campo");
                    failed++;
                }
            } catch (Exception e) {
                System.out.println("   ❌ FAIL - " + e.getMessage());
                failed++;
            }

            // TESTE 5: sendKeys no campo senha
            System.out.println("\n═══ TESTE 5: sendKeys() no #senha ═══");
            try {
                WebElement senhaField = driver.findElement(By.css("#senha"));
                senhaField.sendKeys("minhasenha123").get();
                TimeUnit.MILLISECONDS.sleep(500);

                Object value = driver.executeScript("document.querySelector('#senha').value");
                System.out.println("   Valor no campo: " + (value != null ? "[" + value.toString().length() + " chars]" : "null"));
                if (value != null && value.toString().length() > 0) {
                    System.out.println("   ✅ PASS - sendKeys funcionou no campo senha!");
                    passed++;
                } else {
                    System.out.println("   ❌ FAIL - sendKeys não preencheu o campo senha");
                    failed++;
                }
            } catch (Exception e) {
                System.out.println("   ❌ FAIL - " + e.getMessage());
                failed++;
            }

            // TESTE 6: Encontrar Turnstile e obter coordenadas
            System.out.println("\n═══ TESTE 6: Turnstile getLocation() ═══");
            try {
                WebElement turnstile = driver.findElement(By.css(".cf-turnstile, div[data-sitekey]"));
                double[] loc = turnstile.getLocation().get();
                double[] size = turnstile.getSize().get();
                System.out.println("   Turnstile: x=" + loc[0] + ", y=" + loc[1] + ", w=" + size[0] + ", h=" + size[1]);

                // Calcular posição do checkbox
                int checkboxX = (int)(loc[0] + 28);
                int checkboxY = (int)(loc[1] + size[1] / 2);
                System.out.println("   Checkbox estimado: (" + checkboxX + ", " + checkboxY + ")");

                if (loc[0] > 0 && loc[1] > 0 && size[0] > 0 && size[1] > 0) {
                    System.out.println("   ✅ PASS - Coordenadas do Turnstile válidas");
                    passed++;
                } else {
                    System.out.println("   ❌ FAIL - Coordenadas inválidas");
                    failed++;
                }
            } catch (Exception e) {
                System.out.println("   ❌ FAIL - " + e.getMessage());
                failed++;
            }

            // TESTE 7: Pointer.click() nas coordenadas do Turnstile
            System.out.println("\n═══ TESTE 7: Pointer.click() no Turnstile ═══");
            try {
                WebElement turnstile = driver.findElement(By.css(".cf-turnstile, div[data-sitekey]"));
                double[] loc = turnstile.getLocation().get();
                double[] size = turnstile.getSize().get();

                int checkboxX = (int)(loc[0] + 28);
                int checkboxY = (int)(loc[1] + size[1] / 2);

                var pointer = driver.getPointer();
                pointer.click(checkboxX, checkboxY, true, 0.8, 2.0, 20.0).get();
                System.out.println("   ✅ PASS - Click CDP enviado em (" + checkboxX + ", " + checkboxY + ")");
                passed++;

                // Aguardar resolução
                TimeUnit.SECONDS.sleep(8);
            } catch (Exception e) {
                System.out.println("   ❌ FAIL - " + e.getMessage());
                failed++;
            }

            // TESTE 8: click no botão de login
            System.out.println("\n═══ TESTE 8: click() no botão #btnEfetuarLogin ═══");
            try {
                WebElement btn = driver.findElement(By.css("#btnEfetuarLogin"));
                double[] loc = btn.getLocation().get();
                System.out.println("   Botão location: x=" + loc[0] + ", y=" + loc[1]);
                btn.click().get();
                System.out.println("   ✅ PASS - Click no botão enviado");
                passed++;
            } catch (Exception e) {
                System.out.println("   ❌ FAIL - " + e.getMessage());
                failed++;
            }

            // Resultado
            System.out.println("\n════════════════════════════════════════════════════");
            System.out.printf("   📊 RESULTADO: %d PASS / %d FAIL (total: %d)%n", passed, failed, passed + failed);
            System.out.println("════════════════════════════════════════════════════");

            System.out.println("\n⏱️ Aguardando 15s para verificação visual...");
            TimeUnit.SECONDS.sleep(15);

        } catch (Exception e) {
            System.err.println("\n❌ ERRO FATAL: " + e.getMessage());
            e.printStackTrace();
        } finally {
            if (driver != null) {
                driver.quit();
            }
        }
    }
}
