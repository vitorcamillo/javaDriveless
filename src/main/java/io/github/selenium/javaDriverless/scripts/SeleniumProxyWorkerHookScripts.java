package io.github.selenium.javaDriverless.scripts;

/**
 * Compatibilidade para o antigo hook de {@code Worker}/{@code SharedWorker} no realm da página.
 *
 * <p>A injeção do spoof pertence exclusivamente ao auto-attach CDP pausado, deduplicado por
 * {@code targetId}. Prefixar o mesmo código no construtor e também executar
 * {@code Runtime.evaluate} aplicava os patches duas vezes no worker e exigia um marcador global
 * detectável para evitar a duplicação.</p>
 */
public final class SeleniumProxyWorkerHookScripts {
    private SeleniumProxyWorkerHookScripts() {}

    /**
     * Mantém a assinatura usada pelo compositor, sem produzir uma segunda rota de injeção.
     *
     * @param workerSpoofSrcLiteral ignorado; o auto-attach CDP instala o script diretamente
     * @return string vazia para não alterar o realm da página nem duplicar o spoof do worker
     */
    public static String build(String workerSpoofSrcLiteral) {
        return "";
    }
}
