package io.github.selenium.javaDriverless.types.deserialize;

import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/** Exceção de referência JS remota obsoleta: objeto de origem preservado e mensagem default. */
class StaleJSRemoteObjExceptionTest {

    @Test
    void defaultMessageDescribesReloadOrDeletion() {
        JSRemoteObj remoteObj = new JSRemoteObj("inj.7", null, null, null);

        StaleJSRemoteObjReferenceException ex =
            new StaleJSRemoteObjReferenceException(remoteObj, null);

        assertSame(remoteObj, ex.getRemoteObj());
        assertTrue(ex.getMessage().contains("recarregado"), ex.getMessage());
    }

    @Test
    void customMessageWinsOverDefault() {
        JSRemoteObj remoteObj = new JSRemoteObj("inj.9", null, null, null);

        StaleJSRemoteObjReferenceException ex =
            new StaleJSRemoteObjReferenceException(remoteObj, "contexto destruído");

        assertSame(remoteObj, ex.getRemoteObj());
        assertTrue(ex.getMessage().contains("contexto destruído"));
    }
}
