package io.github.selenium.javaDriverless;

import io.github.selenium.javaDriverless.logging.JavaDriverlessLogger;
import io.github.selenium.javaDriverless.types.Context;
import io.github.selenium.javaDriverless.types.Target;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

/**
 * Visao de UM contexto isolado dentro de um processo Chrome compartilhado.
 *
 * <p>Compartilha host, target base, opcoes e suporte de fingerprint do
 * {@link Chrome} dono do processo, mas o target corrente e a pagina do
 * contexto: executeScript, get, findElement, pointer, screenshot e cookies
 * agem so nela. {@link #quit()} descarta SOMENTE o contexto (nunca o
 * processo) e {@link #getBrowserPid()} devolve -1 para nenhum chamador tratar
 * o processo compartilhado como seu.</p>
 */
final class ChromeContextView extends Chrome {

    private final Chrome dono;
    private final Context contexto;

    ChromeContextView(Chrome dono, Context contexto, Target pagina) {
        super(dono.options, dono.timeout, dono.debug, dono.maxWsSize);
        this.dono = dono;
        this.contexto = contexto;
        this.host = dono.host;
        this.baseTarget = dono.baseTarget;
        this.baseContext = dono.baseContext;
        this.isRemote = dono.isRemote;
        this.sessionUserAgent = dono.sessionUserAgent;
        this.currentContext = contexto;
        this.currentTarget = pagina;
        this.started = true;
    }

    Chrome dono() {
        return dono;
    }

    public Context contexto() {
        return contexto;
    }

    @Override
    public long getBrowserPid() {
        return -1;
    }

    @Override
    public CompletableFuture<List<Map<String, Object>>> getCookies() {
        return contexto.getAllCookies();
    }

    @Override
    public CompletableFuture<Void> addCookie(Map<String, Object> cookieDict) {
        return contexto.addCookie(cookieDict);
    }

    /** Descarta so o contexto: targets, jar de cookies e ponte de proxy. */
    @Override
    public CompletableFuture<Void> quit(boolean cleanDirs) {
        currentTarget = null;
        JavaDriverlessLogger.info(logger, "Descartando contexto isolado {}", contexto.getContextId());
        return dono.removeContext(contexto);
    }

    @Override
    public CompletableFuture<Void> quit() {
        return quit(false);
    }

    @Override
    public String toString() {
        return "<ChromeContextView contexto=" + contexto.getContextId() + " de " + dono + ">";
    }
}
