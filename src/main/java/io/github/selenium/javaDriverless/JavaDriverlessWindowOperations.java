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
abstract class JavaDriverlessWindowOperations {
    protected Chrome chrome;
    protected long implicitWaitMillis = 0;
    protected long scriptTimeoutMillis = 30000;
    protected long pageLoadTimeoutMillis = 300000;

    /**
     * Retorna o tamanho da janela.
     *
     * @return Map com "width" e "height"
     */
    public Map<String, Object> getWindowSize() {
        try {
            return chrome.getWindowSize().get();
        } catch (Exception e) {
            throw new RuntimeException("Erro ao obter tamanho da janela", e);
        }
    }

    /**
     * Define o tamanho da janela.
     *
     * @param width  largura em pixels
     * @param height altura em pixels
     * @return this para chamadas encadeadas
     */
    public JavaDriverless setWindowSize(int width, int height) {
        try {
            chrome.setWindowSize(width, height).get();
            return (JavaDriverless) this;
        } catch (Exception e) {
            throw new RuntimeException("Erro ao definir tamanho da janela", e);
        }
    }

    /**
     * Retorna a posição da janela.
     *
     * @return Map com "x" e "y"
     */
    public Map<String, Object> getWindowPosition() {
        try {
            return chrome.getWindowPosition().get();
        } catch (Exception e) {
            throw new RuntimeException("Erro ao obter posição da janela", e);
        }
    }

    /**
     * Define a posição da janela.
     *
     * @param x posição X em pixels
     * @param y posição Y em pixels
     * @return this para chamadas encadeadas
     */
    public JavaDriverless setWindowPosition(int x, int y) {
        try {
            chrome.setWindowPosition(x, y).get();
            return (JavaDriverless) this;
        } catch (Exception e) {
            throw new RuntimeException("Erro ao definir posição da janela", e);
        }
    }

    /**
     * Obtém todos os cookies.
     *
     * @return lista de cookies
     */
    public List<Map<String, Object>> getCookies() {
        return JavaDriverlessCookieOperations.getCookies(chrome);
    }

    /**
     * Adiciona um cookie.
     *
     * @param cookie dados do cookie
     * @return this para chamadas encadeadas
     */
    public JavaDriverless addCookie(Map<String, Object> cookie) {
        JavaDriverlessCookieOperations.addCookie(chrome, cookie);
        return (JavaDriverless) this;
    }

    /**
     * Deleta todos os cookies.
     *
     * @return this para chamadas encadeadas
     */
    public JavaDriverless deleteAllCookies() {
        JavaDriverlessCookieOperations.deleteAllCookies(chrome);
        return (JavaDriverless) this;
    }

    /**
     * Obtém um cookie específico por nome.
     *
     * @param name nome do cookie
     * @return Map com dados do cookie, ou null se não encontrado
     */
    public Map<String, Object> getCookie(String name) {
        return JavaDriverlessCookieOperations.getCookie(chrome, name);
    }

    /**
     * Deleta um cookie específico por nome.
     *
     * @param name nome do cookie a deletar
     * @return this para chamadas encadeadas
     */
    public JavaDriverless deleteCookie(String name) {
        JavaDriverlessCookieOperations.deleteCookie(chrome, name);
        return (JavaDriverless) this;
    }

}
