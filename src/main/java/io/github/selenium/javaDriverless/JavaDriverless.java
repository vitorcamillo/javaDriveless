// Ported from: https://github.com/kaliiiiiiiiii/Selenium-Driverless
// Original Author: kaliiiiiiiiii | Aurin Aegerter
// Java Port: Vitor Camillo (io.github.vitorcamillo)

package io.github.selenium.javaDriverless;

import com.fasterxml.jackson.databind.JsonNode;

import io.github.selenium.javaDriverless.logging.JavaDriverlessLogger;
import io.github.selenium.javaDriverless.types.ChromeOptions;
import io.github.selenium.javaDriverless.types.WebElement;

import org.slf4j.Logger;

import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
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
public class JavaDriverless implements AutoCloseable {

    private static final Logger logger = JavaDriverlessLogger.getLogger(JavaDriverless.class);
    private static final String DEFAULT_PROFILE_NAME = "default";
    private static final String PROFILES_DIR = "profiles";
    private static final float STARTUP_TIMEOUT_SECONDS = envFloat("JAVA_DRIVERLESS_STARTUP_TIMEOUT_SECONDS", 45.0f);
    private static final boolean STARTUP_DEBUG = envBool("JAVA_DRIVERLESS_STARTUP_DEBUG", false);

    private final String nomeJanela;
    private final ProfileManager profileManager;
    private final Chrome chrome;
    private final ChromeOptions options;
    private final boolean useProfileManagement;
    private boolean started = false;
    private long implicitWaitMillis = 0;
    private long scriptTimeoutMillis = 30000;
    private long pageLoadTimeoutMillis = 300000;

    /**
     * Cria uma nova instância com profile "default" e gerenciamento ativado.
     */
    public JavaDriverless() {
        this(DEFAULT_PROFILE_NAME, null, true);
    }

    /**
     * Cria uma nova instância com opções customizadas e profile "default".
     * Estilo Selenium: new JavaDriverless(options)
     *
     * @param options opções do Chrome
     */
    public JavaDriverless(ChromeOptions options) {
        this(DEFAULT_PROFILE_NAME, options, true);
    }

    /**
     * Cria uma nova instância com profile "default" e controle de gerenciamento.
     *
     * @param useProfileManagement true para usar gerenciamento de profiles, false
     *                             para desabilitar
     */
    public JavaDriverless(boolean useProfileManagement) {
        this(DEFAULT_PROFILE_NAME, null, useProfileManagement);
    }

    /**
     * Cria uma nova instância com nome de janela e gerenciamento ativado.
     *
     * <p>
     * Ao criar, automaticamente:
     * <ul>
     * <li>Verifica e fecha Chrome antigo deste profile (se existir)</li>
     * <li>Cria novo Chrome com profile persistente</li>
     * <li>Salva o PID para gerenciamento futuro</li>
     * </ul>
     * </p>
     *
     * @param nomeJanela nome único para esta janela/profile (use null para
     *                   "default")
     */
    public JavaDriverless(String nomeJanela) {
        this(nomeJanela, null, true);
    }

    /**
     * Cria uma nova instância com nome de janela, opções e gerenciamento ativado.
     *
     * @param nomeJanela nome único para esta janela/profile (use null para
     *                   "default")
     * @param options    opções do Chrome (pode ser null para usar padrões)
     */
    public JavaDriverless(String nomeJanela, ChromeOptions options) {
        this(nomeJanela, options, true);
    }

    /**
     * Cria uma nova instância com opções e controle de gerenciamento.
     *
     * @param options              opções do Chrome
     * @param useProfileManagement true para usar gerenciamento de profiles
     */
    public JavaDriverless(ChromeOptions options, boolean useProfileManagement) {
        this(DEFAULT_PROFILE_NAME, options, useProfileManagement);
    }

    /**
     * Construtor completo com todas as opções.
     *
     * @param nomeJanela           nome único para esta janela/profile (use null
     *                             para "default")
     * @param options              opções do Chrome (pode ser null para usar
     *                             padrões)
     * @param useProfileManagement true para usar gerenciamento de profiles, false
     *                             para desabilitar
     */
    public JavaDriverless(String nomeJanela, ChromeOptions options, boolean useProfileManagement) {
        this.nomeJanela = (nomeJanela != null && !nomeJanela.trim().isEmpty()) ? nomeJanela : DEFAULT_PROFILE_NAME;
        this.useProfileManagement = useProfileManagement;
        this.profileManager = useProfileManagement ? new ProfileManager(PROFILES_DIR) : null;
        this.options = (options != null) ? options : new ChromeOptions();

        JavaDriverlessLogger.startup(logger, "JavaDriverless iniciando. profile={}, gerenciamento={}",
                this.nomeJanela, useProfileManagement ? "ativado" : "desativado");

        try {
            if (useProfileManagement) {
                // MODO COM GERENCIAMENTO DE PROFILES

                // PASSO 1: Limpar processo antigo (se existir)
                JavaDriverlessLogger.profile(logger, "Verificando processo antigo do profile {}", this.nomeJanela);
                boolean hadOldProcess = this.profileManager.cleanupOldProcess(this.nomeJanela);

                if (hadOldProcess) {
                    JavaDriverlessLogger.warn(logger, "Chrome antigo detectado e fechado para profile {}", this.nomeJanela);
                } else {
                    JavaDriverlessLogger.profile(logger, "Nenhum processo antigo encontrado para profile {}", this.nomeJanela);
                }

                // PASSO 2: Configurar profile persistente
                JavaDriverlessLogger.profile(logger, "Configurando profile persistente {}", this.nomeJanela);

                // Se não foi especificado userDataDir, usar pasta profiles/ na raiz do projeto
                if (this.options.getUserDataDir() == null) {
                    String projectRoot = System.getProperty("user.dir");
                    String profilePath = projectRoot + "/" + PROFILES_DIR + "/" + this.nomeJanela;
                    this.options.setUserDataDir(profilePath);
                    JavaDriverlessLogger.profile(logger, "Profile path configurado: {}", profilePath);
                }

                // PASSO 3: Criar Chrome
                JavaDriverlessLogger.info(logger,
                        "Iniciando Chrome. binary={}, timeout={}s, startupDebug={}",
                        this.options.getBinaryLocation(), STARTUP_TIMEOUT_SECONDS, STARTUP_DEBUG);
                this.chrome = Chrome.create(this.options, STARTUP_TIMEOUT_SECONDS, STARTUP_DEBUG, 1 << 27).get();

                long pid = this.chrome.getBrowserPid();
                JavaDriverlessLogger.info(logger, "Chrome iniciado. profile={}, pid={}", this.nomeJanela, pid);

                // PASSO 4: Salvar PID
                this.profileManager.savePid(this.nomeJanela, pid);
                JavaDriverlessLogger.profile(logger, "PID salvo para profile {}", this.nomeJanela);

            } else {
                // MODO SEM GERENCIAMENTO (como era antes)

                JavaDriverlessLogger.info(logger,
                        "Iniciando Chrome sem gerenciamento. binary={}, timeout={}s, startupDebug={}",
                        this.options.getBinaryLocation(), STARTUP_TIMEOUT_SECONDS, STARTUP_DEBUG);
                this.chrome = Chrome.create(this.options, STARTUP_TIMEOUT_SECONDS, STARTUP_DEBUG, 1 << 27).get();

                long pid = this.chrome.getBrowserPid();
                JavaDriverlessLogger.info(logger, "Chrome iniciado sem gerenciamento. pid={}", pid);
                JavaDriverlessLogger.profile(logger, "Profile temporário será deletado ao fechar");
            }

            this.started = true;

            JavaDriverlessLogger.startup(logger, "JavaDriverless pronto para uso. profile={}", this.nomeJanela);

        } catch (Exception e) {
            JavaDriverlessLogger.error(logger, "Erro ao iniciar JavaDriverless: {}", e.getMessage());
            throw new RuntimeException("Erro ao iniciar JavaDriverless", e);
        }
    }

    /**
     * Obtém o nome da janela/profile.
     *
     * @return nome da janela
     */
    public String getNomeJanela() {
        return nomeJanela;
    }

    /**
     * Obtém o ProfileManager.
     *
     * @return profile manager (pode ser null se gerenciamento estiver desabilitado)
     */
    public ProfileManager getProfileManager() {
        return profileManager;
    }

    /**
     * Verifica se o gerenciamento de profiles está ativado.
     *
     * @return true se o gerenciamento está ativado
     */
    public boolean isProfileManagementEnabled() {
        return useProfileManagement;
    }

    /**
     * Obtém a instância do Chrome.
     *
     * @return instância Chrome
     */
    public Chrome getChrome() {
        return chrome;
    }

    /**
     * Obtém o PID do processo Chrome.
     *
     * @return PID do navegador
     */
    public long getPid() {
        return chrome.getBrowserPid();
    }

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
            return this;
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
        try {
            chrome.sleep(seconds).get();
            return this;
        } catch (Exception e) {
            throw new RuntimeException("Erro ao aguardar", e);
        }
    }

    /**
     * Volta para a página anterior.
     *
     * @return this para chamadas encadeadas
     */
    public JavaDriverless back() {
        try {
            chrome.back().get();
            return this;
        } catch (Exception e) {
            throw new RuntimeException("Erro ao voltar", e);
        }
    }

    /**
     * Avança para a próxima página.
     *
     * @return this para chamadas encadeadas
     */
    public JavaDriverless forward() {
        try {
            chrome.forward().get();
            return this;
        } catch (Exception e) {
            throw new RuntimeException("Erro ao avançar", e);
        }
    }

    /**
     * Recarrega a página.
     *
     * @return this para chamadas encadeadas
     */
    public JavaDriverless refresh() {
        try {
            chrome.refresh().get();
            return this;
        } catch (Exception e) {
            throw new RuntimeException("Erro ao recarregar", e);
        }
    }

    /**
     * Tira screenshot e salva em arquivo.
     *
     * @param filename caminho do arquivo
     * @return this para chamadas encadeadas
     */
    public JavaDriverless screenshot(String filename) {
        try {
            chrome.getScreenshotAsFile(filename).get();
            return this;
        } catch (Exception e) {
            throw new RuntimeException("Erro ao tirar screenshot", e);
        }
    }

    /**
     * Maximiza a janela.
     *
     * @return this para chamadas encadeadas
     */
    public JavaDriverless maximize() {
        try {
            chrome.maximizeWindow().get();
            return this;
        } catch (Exception e) {
            throw new RuntimeException("Erro ao maximizar", e);
        }
    }

    /**
     * Minimiza a janela.
     *
     * @return this para chamadas encadeadas
     */
    public JavaDriverless minimize() {
        try {
            chrome.minimizeWindow().get();
            return this;
        } catch (Exception e) {
            throw new RuntimeException("Erro ao minimizar", e);
        }
    }

    /**
     * Coloca a janela em modo fullscreen.
     *
     * @return this para chamadas encadeadas
     */
    public JavaDriverless fullscreen() {
        try {
            chrome.fullscreenWindow().get();
            return this;
        } catch (Exception e) {
            throw new RuntimeException("Erro ao fullscreen", e);
        }
    }

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
            return this;
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
            return this;
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
        try {
            return chrome.getCookies().get();
        } catch (Exception e) {
            throw new RuntimeException("Erro ao obter cookies", e);
        }
    }

    /**
     * Adiciona um cookie.
     *
     * @param cookie dados do cookie
     * @return this para chamadas encadeadas
     */
    public JavaDriverless addCookie(Map<String, Object> cookie) {
        try {
            chrome.addCookie(cookie).get();
            return this;
        } catch (Exception e) {
            throw new RuntimeException("Erro ao adicionar cookie", e);
        }
    }

    /**
     * Deleta todos os cookies.
     *
     * @return this para chamadas encadeadas
     */
    public JavaDriverless deleteAllCookies() {
        try {
            chrome.deleteAllCookies().get();
            return this;
        } catch (Exception e) {
            throw new RuntimeException("Erro ao deletar cookies", e);
        }
    }

    /**
     * Obtém um cookie específico por nome.
     *
     * @param name nome do cookie
     * @return Map com dados do cookie, ou null se não encontrado
     */
    public Map<String, Object> getCookie(String name) {
        try {
            List<Map<String, Object>> cookies = chrome.getCookies().get();
            return cookies.stream()
                    .filter(cookie -> name.equals(cookie.get("name")))
                    .findFirst()
                    .orElse(null);
        } catch (Exception e) {
            throw new RuntimeException("Erro ao obter cookie: " + name, e);
        }
    }

    /**
     * Deleta um cookie específico por nome.
     *
     * @param name nome do cookie a deletar
     * @return this para chamadas encadeadas
     */
    public JavaDriverless deleteCookie(String name) {
        try {
            Map<String, Object> cookie = getCookie(name);
            if (cookie != null) {
                // Usar CDP para deletar cookie específico
                Map<String, Object> args = new HashMap<>();
                args.put("name", name);
                if (cookie.containsKey("domain")) {
                    args.put("domain", cookie.get("domain"));
                }
                chrome.getCurrentTarget().get()
                        .executeCdpCmd("Network.deleteCookies", args, 5.0f).get();
            }
            return this;
        } catch (Exception e) {
            throw new RuntimeException("Erro ao deletar cookie: " + name, e);
        }
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
            return this;
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
        return this;
    }

    /**
     * Define o timeout para execução de scripts.
     *
     * @param millis timeout em milissegundos
     * @return esta instância para method chaining
     */
    public JavaDriverless setScriptTimeout(long millis) {
        this.scriptTimeoutMillis = millis;
        return this;
    }

    /**
     * Define o timeout para carregamento de páginas.
     *
     * @param millis timeout em milissegundos
     * @return esta instância para method chaining
     */
    public JavaDriverless setPageLoadTimeout(long millis) {
        this.pageLoadTimeoutMillis = millis;
        return this;
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

    /**
     * Fecha o Chrome e limpa recursos.
     *
     * <p>
     * Automaticamente:
     * <ul>
     * <li>Fecha o Chrome</li>
     * <li>Deleta o arquivo de PID (se gerenciamento estiver ativado)</li>
     * <li>Limpa recursos</li>
     * </ul>
     * </p>
     */
    public void quit() {
        try {
            JavaDriverlessLogger.startup(logger, "Encerrando JavaDriverless. profile={}", nomeJanela);

            if (chrome != null) {
                JavaDriverlessLogger.profile(logger, "Fechando Chrome do profile {}", nomeJanela);
                chrome.quit().get();
                JavaDriverlessLogger.info(logger, "Chrome fechado. profile={}", nomeJanela);
            }

            if (useProfileManagement && profileManager != null) {
                profileManager.deletePidFile(nomeJanela);
                JavaDriverlessLogger.profile(logger, "PID deletado para profile {}", nomeJanela);
            } else {
                JavaDriverlessLogger.profile(logger, "Sem limpeza de PID para profile {}", nomeJanela);
            }

            JavaDriverlessLogger.startup(logger, "JavaDriverless encerrado com sucesso. profile={}", nomeJanela);

        } catch (Exception e) {
            JavaDriverlessLogger.error(logger, "Erro ao encerrar JavaDriverless: {}", e.getMessage());
            throw new RuntimeException("Erro ao encerrar JavaDriverless", e);
        }
    }

    /**
     * Implementação de AutoCloseable para uso com try-with-resources.
     */
    @Override
    public void close() {
        quit();
    }

    @Override
    public String toString() {
        return String.format("JavaDriverless(nome='%s', pid=%d, started=%b)",
                nomeJanela, getPid(), started);
    }

    private static boolean envBool(String key, boolean defaultValue) {
        String raw = System.getenv(key);
        if (raw == null || raw.isBlank()) return defaultValue;
        String v = raw.trim().toLowerCase(Locale.ROOT);
        return v.equals("1") || v.equals("true") || v.equals("yes") || v.equals("on");
    }

    private static float envFloat(String key, float defaultValue) {
        String raw = System.getenv(key);
        if (raw == null || raw.isBlank()) return defaultValue;
        try {
            return Float.parseFloat(raw.trim());
        } catch (Exception ignored) {
            return defaultValue;
        }
    }
}
