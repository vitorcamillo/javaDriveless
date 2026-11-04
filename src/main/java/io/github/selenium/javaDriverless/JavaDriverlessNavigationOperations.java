// Ported from: https://github.com/kaliiiiiiiiii/Selenium-Driverless
// Original Author: kaliiiiiiiiii | Aurin Aegerter
// Java Port: Vitor Camillo (io.github.vitorcamillo)

package io.github.selenium.javaDriverless;

import com.fasterxml.jackson.databind.JsonNode;

import io.github.selenium.javaDriverless.logging.JavaDriverlessLogger;
import io.github.selenium.javaDriverless.types.ChromeOptions;
import io.github.selenium.javaDriverless.types.WebElement;

import org.slf4j.Logger;

import java.util.List;
import java.util.Map;
import java.nio.file.Path;
import java.util.concurrent.CompletableFuture;

/**
 * Wrapper simplificado do Chrome com gerenciamento automático de profiles.
 *
 * <p>
 * Esta classe gerencia automaticamente:
 * <ul>
 * <li>Limpeza de processos Chrome antigos (de execuções anteriores que
 * crashearam)</li>
 * <li>Salvamento do PID do processo</li>
 * <li>Profile persistente com nome único</li>
 * <li>Cleanup ao encerrar</li>
 * </ul>
 * </p>
 *
 * <h3>Exemplo de uso simples:</h3>
 *
 * <pre>{@code
 * // Usar profile padrão
 * JavaDriverless driver = new JavaDriverless();
 *
 * // Usar profile com nome específico
 * JavaDriverless driver = new JavaDriverless("MeuBot_Bet365");
 *
 * // Desabilitar gerenciamento de profiles (como era antes)
 * JavaDriverless driver = new JavaDriverless(false);
 *
 * // Usar normalmente
 * driver.get("https://www.bet365.com");
 * System.out.println(driver.getTitle());
 *
 * // Fechar (limpa tudo automaticamente)
 * driver.quit();
 * }</pre>
 *
 * <h3>Exemplo com múltiplos bots:</h3>
 *
 * <pre>{@code
 * JavaDriverless bot1 = new JavaDriverless("Bot_1");
 * JavaDriverless bot2 = new JavaDriverless("Bot_2");
 * JavaDriverless bot3 = new JavaDriverless("Bot_3");
 *
 * // Cada um tem seu próprio Chrome gerenciado independentemente!
 * // Se crashear e reiniciar, cada um fecha apenas seu Chrome antigo
 * }</pre>
 */
abstract class JavaDriverlessNavigationOperations extends JavaDriverlessWindowOperations {
    // ========================================================================
    // MÉTODOS DELEGADOS AO CHROME (para facilitar o uso)
    // ========================================================================

    /**
     * Navega para uma URL.
     *
     * @param url URL para navegar
     * @return this para chamadas encadeadas
     */
    public JavaDriverless get(String url) {
        try {
            chrome.get(url, true).get();
            return (JavaDriverless) this;
        } catch (Exception e) {
            throw new RuntimeException("Erro ao navegar para: " + url, e);
        }
    }

    /**
     * Navega para uma URL.
     *
     * @param url      URL para navegar
     * @param waitLoad se deve aguardar o carregamento
     * @return CompletableFuture
     */
    public CompletableFuture<Map<String, Object>> get(String url, boolean waitLoad) {
        return chrome.get(url, waitLoad);
    }

    /**
     * Obtém o título da página.
     *
     * @return título da página
     */
    public String getTitle() {
        try {
            return chrome.getTitle().get();
        } catch (Exception e) {
            throw new RuntimeException("Erro ao obter título", e);
        }
    }

    /**
     * Obtém o título da página (async).
     *
     * @return CompletableFuture com o título
     */
    public CompletableFuture<String> getTitleAsync() {
        return chrome.getTitle();
    }

    /**
     * Obtém a URL atual.
     *
     * @return URL atual
     */
    public String getCurrentUrl() {
        try {
            return chrome.getCurrentUrl().get();
        } catch (Exception e) {
            throw new RuntimeException("Erro ao obter URL", e);
        }
    }

    /**
     * Obtém o código HTML da página.
     *
     * @return HTML da página
     */
    public String getPageSource() {
        try {
            return chrome.getCurrentTarget().get().getPageSource().get();
        } catch (Exception e) {
            throw new RuntimeException("Erro ao obter código fonte", e);
        }
    }

    /**
     * Obtém a URL atual (async).
     *
     * @return CompletableFuture com a URL
     */
    public CompletableFuture<String> getCurrentUrlAsync() {
        return chrome.getCurrentUrl();
    }

    /**
     * Busca um elemento usando o localizador By (recomendado).
     *
     * @param by o localizador By
     * @return elemento encontrado
     */
    public WebElement findElement(io.github.selenium.javaDriverless.types.By by) {
        return findElement(by.getStrategy(), by.getValue());
    }

    /**
     * Busca um elemento na página.
     *
     * @param by    estratégia de busca (css, xpath, etc)
     * @param value valor da busca
     * @return elemento encontrado
     */
    public WebElement findElement(String by, String value) {
        try {
            return chrome.findElement(by, value, 10.0f).get();
        } catch (Exception e) {
            throw new RuntimeException("Erro ao buscar elemento: " + by + "=" + value, e);
        }
    }

    /**
     * Busca múltiplos elementos usando o localizador By (recomendado).
     *
     * @param by o localizador By
     * @return lista de elementos
     */
    public List<WebElement> findElements(io.github.selenium.javaDriverless.types.By by) {
        return findElements(by.getStrategy(), by.getValue());
    }

    /**
     * Busca múltiplos elementos na página.
     *
     * @param by    estratégia de busca
     * @param value valor da busca
     * @return lista de elementos
     */
    public List<WebElement> findElements(String by, String value) {
        try {
            return chrome.findElements(by, value, 10.0f).get();
        } catch (Exception e) {
            throw new RuntimeException("Erro ao buscar elementos: " + by + "=" + value, e);
        }
    }

    /**
     * Executa JavaScript na página.
     *
     * @param script código JavaScript
     * @return resultado da execução
     */
    public Object executeScript(String script) {
        try {
            return chrome.executeScript(script, null, true).get();
        } catch (Exception e) {
            throw new RuntimeException("Erro ao executar script", e);
        }
    }

    /**
     * Executa JavaScript na página (async).
     *
     * @param script       código JavaScript
     * @param args         argumentos
     * @param awaitPromise se deve aguardar promises
     * @return CompletableFuture com o resultado
     */
    public CompletableFuture<Object> executeScriptAsync(String script, Object[] args, boolean awaitPromise) {
        return chrome.executeScript(script, args, awaitPromise);
    }

    /**
     * Aguarda em segundos.
     *
     * @param seconds segundos para aguardar
     * @return this para chamadas encadeadas
     */
    public JavaDriverless sleep(double seconds) {
        JavaDriverlessBrowserOperations.sleep(chrome, seconds);
        return (JavaDriverless) this;
    }

    /**
     * Volta para a página anterior.
     *
     * @return this para chamadas encadeadas
     */
    public JavaDriverless back() {
        JavaDriverlessBrowserOperations.back(chrome);
        return (JavaDriverless) this;
    }

    /**
     * Avança para a próxima página.
     *
     * @return this para chamadas encadeadas
     */
    public JavaDriverless forward() {
        JavaDriverlessBrowserOperations.forward(chrome);
        return (JavaDriverless) this;
    }

    /**
     * Recarrega a página.
     *
     * @return this para chamadas encadeadas
     */
    public JavaDriverless refresh() {
        JavaDriverlessBrowserOperations.refresh(chrome);
        return (JavaDriverless) this;
    }

    /**
     * Tira screenshot e salva em arquivo.
     *
     * @param filename caminho do arquivo
     * @return this para chamadas encadeadas
     */
    public JavaDriverless screenshot(String filename) {
        JavaDriverlessBrowserOperations.screenshot(chrome, filename);
        return (JavaDriverless) this;
    }

    /**
     * Maximiza a janela.
     *
     * @return this para chamadas encadeadas
     */
    public JavaDriverless maximize() {
        JavaDriverlessBrowserOperations.maximize(chrome);
        return (JavaDriverless) this;
    }

    /**
     * Minimiza a janela.
     *
     * @return this para chamadas encadeadas
     */
    public JavaDriverless minimize() {
        JavaDriverlessBrowserOperations.minimize(chrome);
        return (JavaDriverless) this;
    }

    /**
     * Coloca a janela em modo fullscreen.
     *
     * @return this para chamadas encadeadas
     */
    public JavaDriverless fullscreen() {
        JavaDriverlessBrowserOperations.fullscreen(chrome);
        return (JavaDriverless) this;
    }

    /**
     * Envia teclas.
     *
     * @param text texto a enviar
     * @return this para chamadas encadeadas
     */
    public JavaDriverless sendKeys(String text) {
        try {
            chrome.sendKeys(text).get();
            return (JavaDriverless) this;
        } catch (Exception e) {
            throw new RuntimeException("Erro ao enviar teclas", e);
        }
    }

    /**
     * Define o timeout de implicit wait (tempo de espera implícito para encontrar
     * elementos).
     *
     * @param millis timeout em milissegundos
     * @return esta instância para method chaining
     */
    public JavaDriverless implicitlyWait(long millis) {
        this.implicitWaitMillis = millis;
        return (JavaDriverless) this;
    }

    /**
     * Define o timeout para execução de scripts.
     *
     * @param millis timeout em milissegundos
     * @return esta instância para method chaining
     */
    public JavaDriverless setScriptTimeout(long millis) {
        this.scriptTimeoutMillis = millis;
        return (JavaDriverless) this;
    }

    /**
     * Define o timeout para carregamento de páginas.
     *
     * @param millis timeout em milissegundos
     * @return esta instância para method chaining
     */
    public JavaDriverless setPageLoadTimeout(long millis) {
        this.pageLoadTimeoutMillis = millis;
        return (JavaDriverless) this;
    }

    /**
     * Obtém o timeout de implicit wait atual.
     *
     * @return timeout em milissegundos
     */
    public long getImplicitWaitMillis() {
        return implicitWaitMillis;
    }

    /**
     * Cria uma nova janela/aba.
     *
     * @param type "tab" para aba, "window" para janela
     * @return Target da nova janela
     */
    public io.github.selenium.javaDriverless.types.Target newWindow(String type) {
        try {
            return chrome.newWindow(type).get();
        } catch (Exception e) {
            throw new RuntimeException("Erro ao criar nova janela", e);
        }
    }

    /**
     * Obtém todas as janelas abertas.
     *
     * @return lista de targets
     */
    public List<io.github.selenium.javaDriverless.types.Target> getWindows() {
        try {
            return chrome.getTargets().get();
        } catch (Exception e) {
            throw new RuntimeException("Erro ao obter janelas", e);
        }
    }

    /**
     * Obtém o Keyboard para interações avançadas de teclado.
     *
     * @return instância do Keyboard
     */
    public io.github.selenium.javaDriverless.input.Keyboard getKeyboard() {
        return chrome.getCurrentKeyboard();
    }

    /**
     * Obtém o Pointer para interações avançadas de mouse.
     *
     * @return instância do Pointer
     */
    public io.github.selenium.javaDriverless.input.Pointer getPointer() {
        return chrome.getCurrentPointer();
    }

}
