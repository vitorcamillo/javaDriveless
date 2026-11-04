// Ported from: https://github.com/kaliiiiiiiiii/Selenium-Driverless
// Original Author: kaliiiiiiiiii | Aurin Aegerter
// Java Port: Vitor Camillo (io.github.vitorcamillo)

package io.github.selenium.javaDriverless;

import com.fasterxml.jackson.databind.JsonNode;

import io.github.selenium.javaDriverless.logging.JavaDriverlessLogger;
import io.github.selenium.javaDriverless.types.ChromeOptions;
import io.github.selenium.javaDriverless.types.Context;
import io.github.selenium.javaDriverless.types.Target;
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
public class JavaDriverless extends JavaDriverlessNavigationOperations implements AutoCloseable {

    private static final Logger logger = JavaDriverlessLogger.getLogger(JavaDriverless.class);
    private static final String DEFAULT_PROFILE_NAME = "default";
    static final String PROFILES_DIR_PROPERTY = "java.driverless.profiles.dir";
    private static final String DEFAULT_PROFILES_DIR = "profiles";
    private static final float STARTUP_TIMEOUT_SECONDS = JavaDriverlessConfiguration.envFloat("JAVA_DRIVERLESS_STARTUP_TIMEOUT_SECONDS", 45.0f);
    private static final boolean STARTUP_DEBUG = JavaDriverlessConfiguration.envBool("JAVA_DRIVERLESS_STARTUP_DEBUG", false);

    private final String nomeJanela;
    private final ProfileManager profileManager;
    private final ChromeOptions options;
    private final boolean useProfileManagement;
    private boolean started = false;

    /**
     * Cria uma nova instância com profile "default" e gerenciamento ativado.
     */
    public JavaDriverless() {
        this(DEFAULT_PROFILE_NAME, null, true);
    }

    /**
     * Cria uma nova instância com opções customizadas e profile "default".
     * Estilo Selenium: new JavaDriverless(options)
     * Opções de perfil estruturado usam o storage gerido pelo catálogo e desativam o gerenciamento legado.
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
        boolean effective = effectiveProfileManagement(useProfileManagement, options);
        if (useProfileManagement && !effective) {
            JavaDriverlessLogger.info(logger,
                    "Perfil estruturado detectado: storage gerido pelo catálogo; gerenciamento de profile legado desativado");
        }
        this.nomeJanela = (nomeJanela != null && !nomeJanela.trim().isEmpty()) ? nomeJanela : DEFAULT_PROFILE_NAME;
        this.useProfileManagement = effective;
        Path profilesRoot = resolveProfilesRoot();
        this.profileManager = effective ? new ProfileManager(profilesRoot.toString()) : null;
        this.options = (options != null) ? options : new ChromeOptions();

        JavaDriverlessLogger.startup(logger, "JavaDriverless iniciando. profile={}, gerenciamento={}",
                this.nomeJanela, effective ? "ativado" : "desativado");

        try {
            if (effective) {
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
                boolean managedStoragePath = this.options.getUserDataDir() == null;
                if (managedStoragePath) {
                    Path profilePath = ProfileKey.resolveUnder(profilesRoot, this.nomeJanela);
                    profilePath = ProfileKey.migrateLegacyDirectory(profilesRoot, this.nomeJanela, profilePath);
                    this.options.setUserDataDir(profilePath.toString());
                    JavaDriverlessLogger.profile(logger, "Profile path configurado: {}", profilePath);
                }
                if (hadOldProcess && managedStoragePath
                        && !ProfileStartupFiles.unlockStaleLocks(
                                Path.of(this.options.getUserDataDir()), () -> {})) {
                    throw new IllegalStateException(
                            "Chrome antigo foi encerrado, mas o lock do profile não ficou comprovadamente stale");
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

    static boolean effectiveProfileManagement(boolean requested, ChromeOptions options) {
        return requested && !(options != null && options.isProfileLaunchArgumentGuardBound());
    }

    /** Fachada sobre um Chrome ja pronto (visao de contexto): nao lanca processo. */
    private JavaDriverless(String nomeJanela, Chrome chromePronto) {
        this.nomeJanela = nomeJanela;
        this.useProfileManagement = false;
        this.profileManager = null;
        this.options = chromePronto.options;
        this.chrome = chromePronto;
        this.started = true;
    }

    /**
     * Visao de um contexto isolado de um Chrome compartilhado: mesma API do
     * driver, mas presa a {@code pagina} dentro de {@code contexto}.
     * {@link #quit()} descarta so o contexto; {@link #getPid()} devolve -1.
     *
     * @param dono     driver do processo compartilhado
     * @param contexto contexto criado por {@code getChrome().newContext(proxy)}
     * @param pagina   target criado por {@code getChrome().newWindow(contexto, "window")}
     */
    public static JavaDriverless paraContexto(JavaDriverless dono, Context contexto, Target pagina) {
        ChromeContextView view = new ChromeContextView(dono.getChrome(), contexto, pagina);
        return new JavaDriverless(dono.getNomeJanela() + "/" + contexto.getContextId(), view);
    }

    static Path resolveProfilesRoot() {
        return JavaDriverlessConfiguration.resolveProfilesRoot(PROFILES_DIR_PROPERTY, DEFAULT_PROFILES_DIR);
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

    // Keep the public facade methods declared here. Some reflection-based consumers ignore
    // synthetic bridge methods emitted when a public method is inherited through package-private
    // implementation classes.
    @Override public JavaDriverless get(String url) { return super.get(url); }
    @Override public CompletableFuture<Map<String, Object>> get(String url, boolean waitLoad) {
        return super.get(url, waitLoad);
    }
    @Override public String getTitle() { return super.getTitle(); }
    @Override public CompletableFuture<String> getTitleAsync() { return super.getTitleAsync(); }
    @Override public String getCurrentUrl() { return super.getCurrentUrl(); }
    @Override public String getPageSource() { return super.getPageSource(); }
    @Override public CompletableFuture<String> getCurrentUrlAsync() { return super.getCurrentUrlAsync(); }
    @Override public WebElement findElement(io.github.selenium.javaDriverless.types.By by) {
        return super.findElement(by);
    }
    @Override public WebElement findElement(String by, String value) { return super.findElement(by, value); }
    @Override public List<WebElement> findElements(io.github.selenium.javaDriverless.types.By by) {
        return super.findElements(by);
    }
    @Override public List<WebElement> findElements(String by, String value) {
        return super.findElements(by, value);
    }
    @Override public Object executeScript(String script) { return super.executeScript(script); }
    @Override public CompletableFuture<Object> executeScriptAsync(
            String script, Object[] args, boolean awaitPromise) {
        return super.executeScriptAsync(script, args, awaitPromise);
    }
    @Override public JavaDriverless sleep(double seconds) { return super.sleep(seconds); }
    @Override public JavaDriverless back() { return super.back(); }
    @Override public JavaDriverless forward() { return super.forward(); }
    @Override public JavaDriverless refresh() { return super.refresh(); }
    @Override public JavaDriverless screenshot(String filename) { return super.screenshot(filename); }
    @Override public JavaDriverless maximize() { return super.maximize(); }
    @Override public JavaDriverless minimize() { return super.minimize(); }
    @Override public JavaDriverless fullscreen() { return super.fullscreen(); }
    @Override public Map<String, Object> getWindowSize() { return super.getWindowSize(); }
    @Override public JavaDriverless setWindowSize(int width, int height) {
        return super.setWindowSize(width, height);
    }
    @Override public Map<String, Object> getWindowPosition() { return super.getWindowPosition(); }
    @Override public JavaDriverless setWindowPosition(int x, int y) {
        return super.setWindowPosition(x, y);
    }
    @Override public List<Map<String, Object>> getCookies() { return super.getCookies(); }
    @Override public JavaDriverless addCookie(Map<String, Object> cookie) { return super.addCookie(cookie); }
    @Override public JavaDriverless deleteAllCookies() { return super.deleteAllCookies(); }
    @Override public Map<String, Object> getCookie(String name) { return super.getCookie(name); }
    @Override public JavaDriverless deleteCookie(String name) { return super.deleteCookie(name); }
    @Override public JavaDriverless sendKeys(String text) { return super.sendKeys(text); }
    @Override public JavaDriverless implicitlyWait(long millis) { return super.implicitlyWait(millis); }
    @Override public JavaDriverless setScriptTimeout(long millis) { return super.setScriptTimeout(millis); }
    @Override public JavaDriverless setPageLoadTimeout(long millis) { return super.setPageLoadTimeout(millis); }
    @Override public long getImplicitWaitMillis() { return super.getImplicitWaitMillis(); }
    @Override public io.github.selenium.javaDriverless.types.Target newWindow(String type) {
        return super.newWindow(type);
    }
    @Override public List<io.github.selenium.javaDriverless.types.Target> getWindows() {
        return super.getWindows();
    }
    @Override public io.github.selenium.javaDriverless.input.Keyboard getKeyboard() {
        return super.getKeyboard();
    }
    @Override public io.github.selenium.javaDriverless.input.Pointer getPointer() { return super.getPointer(); }

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
}
