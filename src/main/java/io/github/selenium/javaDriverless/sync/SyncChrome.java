package io.github.selenium.javaDriverless.sync;

import com.fasterxml.jackson.databind.JsonNode;

import io.github.selenium.javaDriverless.Chrome;
import io.github.selenium.javaDriverless.types.*;

import java.util.List;
import java.util.Map;

/**
 * Versão síncrona (bloqueante) do Chrome.
 * <p>
 * Esta classe envolve Chrome e bloqueia todas as operações assíncronas
 * chamando .join() nos CompletableFutures.
 * </p>
 * 
 * <h3>Exemplo:</h3>
 * <pre>{@code
 * ChromeOptions options = new ChromeOptions();
 * try (SyncChrome driver = new SyncChrome(options)) {
 *     driver.get("https://example.com");
 *     String title = driver.getTitle();
 *     System.out.println(title);
 * }
 * }</pre>
 */
public class SyncChrome implements AutoCloseable {
    
    private final Chrome asyncChrome;
    
    /**
     * Cria um novo SyncChrome.
     *
     * @param options opções do Chrome
     */
    public SyncChrome(ChromeOptions options) {
        this.asyncChrome = Chrome.create(options).join();
    }
    
    /**
     * Cria um novo SyncChrome com opções padrão.
     */
    public SyncChrome() {
        this(new ChromeOptions());
    }
    
    /**
     * Retorna o target atual.
     *
     * @return target atual
     */
    public SyncTarget getCurrentTarget() {
        Target target = asyncChrome.getCurrentTarget().join();
        return new SyncTarget(target);
    }
    
    /**
     * Navega para uma URL.
     *
     * @param url URL para navegar
     * @param waitLoad se deve aguardar o carregamento
     * @return dados do resultado
     */
    public Map<String, Object> get(String url, boolean waitLoad) {
        return asyncChrome.get(url, waitLoad).join();
    }
    
    /**
     * Navega para uma URL (aguarda carregamento).
     *
     * @param url URL para navegar
     */
    public void get(String url) {
        get(url, true);
    }
    
    /**
     * Retorna o título da página.
     *
     * @return título
     */
    public String getTitle() {
        return asyncChrome.getTitle().join();
    }
    
    /**
     * Retorna a URL atual.
     *
     * @return URL atual
     */
    public String getCurrentUrl() {
        return asyncChrome.getCurrentUrl().join();
    }
    
    /**
     * Retorna o código-fonte da página.
     *
     * @return HTML da página
     */
    public String getPageSource() {
        return asyncChrome.getPageSource().join();
    }
    
    /**
     * Executa JavaScript.
     *
     * @param script código JavaScript
     * @param args argumentos
     * @param awaitPromise se deve aguardar promises
     * @return resultado
     */
    public Object executeScript(String script, Object[] args, boolean awaitPromise) {
        return asyncChrome.executeScript(script, args, awaitPromise).join();
    }
    
    /**
     * Executa JavaScript simples.
     *
     * @param script código JavaScript
     * @return resultado
     */
    public Object executeScript(String script) {
        return executeScript(script, null, false);
    }
    
    /**
     * Aguarda por um evento CDP.
     *
     * @param event nome do evento
     * @param timeout timeout em segundos
     * @return parâmetros do evento
     */
    public JsonNode waitForCdp(String event, Float timeout) {
        return asyncChrome.waitForCdp(event, timeout).join();
    }
    
    /**
     * Busca um elemento.
     *
     * @param by estratégia de busca
     * @param value valor da busca
     * @param timeout timeout em segundos
     * @return elemento encontrado
     */
    public SyncWebElement findElement(String by, String value, float timeout) {
        WebElement elem = asyncChrome.findElement(by, value, timeout).join();
        return new SyncWebElement(elem);
    }
    
    /**
     * Aguarda em segundos.
     *
     * @param seconds segundos para aguardar
     */
    public void sleep(double seconds) {
        asyncChrome.sleep(seconds).join();
    }
    
    /**
     * Volta para a página anterior.
     */
    public void back() {
        asyncChrome.back().join();
    }
    
    /**
     * Avança para a próxima página.
     */
    public void forward() {
        asyncChrome.forward().join();
    }
    
    /**
     * Recarrega a página.
     *
     * @param ignoreCache se deve ignorar o cache
     */
    public void refresh(boolean ignoreCache) {
        asyncChrome.refresh(ignoreCache).join();
    }
    
    /**
     * Recarrega a página.
     */
    public void refresh() {
        refresh(false);
    }
    
    /**
     * Obtém todos os cookies.
     *
     * @return lista de cookies
     */
    public List<Map<String, Object>> getCookies() {
        return asyncChrome.getCookies().join();
    }
    
    /**
     * Obtém um cookie pelo nome.
     *
     * @param name nome do cookie
     * @return cookie ou null
     */
    public Map<String, Object> getCookie(String name) {
        return asyncChrome.getCookie(name).join();
    }
    
    /**
     * Adiciona um cookie.
     *
     * @param cookieDict dicionário do cookie
     */
    public void addCookie(Map<String, Object> cookieDict) {
        asyncChrome.addCookie(cookieDict).join();
    }
    
    /**
     * Deleta um cookie.
     *
     * @param name nome do cookie
     */
    public void deleteCookie(String name) {
        asyncChrome.deleteCookie(name).join();
    }
    
    /**
     * Deleta todos os cookies.
     */
    public void deleteAllCookies() {
        asyncChrome.deleteAllCookies().join();
    }
    
    /**
     * Captura screenshot.
     *
     * @return bytes PNG
     */
    public byte[] getScreenshotAsPng() {
        return asyncChrome.getScreenshotAsPng().join();
    }
    
    /**
     * Salva screenshot em arquivo.
     *
     * @param filename caminho do arquivo
     */
    public void getScreenshotAsFile(String filename) {
        asyncChrome.getScreenshotAsFile(filename).join();
    }
    
    /**
     * Envia teclas.
     *
     * @param text texto a enviar
     */
    public void sendKeys(String text) {
        asyncChrome.sendKeys(text).join();
    }
    
    /**
     * Fecha o Chrome.
     *
     * @param cleanDirs se deve limpar diretórios
     */
    public void quit(boolean cleanDirs) {
        asyncChrome.quit(cleanDirs).join();
    }
    
    /**
     * Fecha o Chrome (limpa diretórios automaticamente).
     */
    public void quit() {
        asyncChrome.quit().join();
    }
    
    @Override
    public void close() {
        quit();
    }
}

