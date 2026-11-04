package io.github.selenium.javaDriverless.scripts;

/**
 * Spoof adaptado ao escopo de Worker / ServiceWorker / SharedWorker.
 * <p>
 * O script de superfície principal ({@link SeleniumProxySurfaceScripts}) é injetado via
 * {@code Page.addScriptToEvaluateOnNewDocument} + {@code Runtime.evaluate}, que só alcançam
 * o realm da página e iframes. Os Workers têm um global próprio ({@code WorkerGlobalScope})
 * com sua própria {@code WorkerNavigator}, {@code WebGL2RenderingContext} e {@code OffscreenCanvas},
 * que ficavam sem spoof — vazando o renderer real (ex.: Mesa llvmpipe num container sem GPU) e o
 * {@code hardwareConcurrency}/{@code deviceMemory} reais, criando um mismatch main-vs-worker.
 * </p>
 * <p>
 * Este script é injetado em cada sessão de worker (obtida via {@code Target.setAutoAttach} +
 * {@code Target.attachedToTarget}, com o worker pausado por {@code waitForDebuggerOnStart})
 * ANTES do código do worker rodar. Cobre apenas as superfícies que existem no worker; tudo é
 * protegido por {@code typeof} + {@code try/catch} para nunca lançar (um erro no worker seria,
 * ele próprio, um sinal de automação).
 * </p>
 */
public final class SeleniumProxyWorkerSurfaceScripts {
    private SeleniumProxyWorkerSurfaceScripts() {}

    private static final String SCRIPT = SeleniumProxyWorkerSurfaceScriptPartOne.VALUE
            + SeleniumProxyWorkerSurfaceScriptPartTwo.VALUE;


    /**
     * Constrói o script de worker com o perfil serializado embutido.
     *
     * @param profileJson JSON do {@code BrowserFingerprintProfile} ativo
     * @return código JS pronto para {@code Runtime.evaluate} na sessão do worker
     */
    public static String build(String profileJson) {
        if (!SeleniumProxySurfaceScripts.javascriptSurfaceWrappersEnabled(profileJson)) {
            return "";
        }
        return SCRIPT.replace("__PROFILE_JSON__", profileJson);
    }
}
