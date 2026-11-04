package io.github.selenium.javaDriverless.cdp.exceptions;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/**
 * Construtores e classificação segura do CDPException: código/mensagem canônica
 * sem reter o texto bruto do protocolo.
 */
class CDPExceptionConstructorsTest {

    @Test
    void codeAndMessageConstructorIsUnclassified() {
        CDPException e = new CDPException(-32601, "método não encontrado");

        assertEquals(-32601, e.getCode());
        assertEquals("método não encontrado", e.getCdpMessage());
        assertEquals(CDPException.Kind.UNCLASSIFIED, e.getKind());
        assertTrue(e.getMessage().contains("-32601"));
        assertTrue(e.hasKind(CDPException.Kind.UNCLASSIFIED));
        assertFalse(e.hasKind(CDPException.Kind.COMMAND_REJECTED));
    }

    @Test
    void codeMessageAndCauseConstructorKeepsTheCause() {
        IllegalStateException causa = new IllegalStateException("socket morto");
        CDPException e = new CDPException(-32000, "boom", causa);

        assertEquals(-32000, e.getCode());
        assertEquals(causa, e.getCause());
        assertEquals(CDPException.Kind.UNCLASSIFIED, e.getKind());
    }

    @Test
    void messageOnlyConstructorHasNoCode() {
        CDPException e = new CDPException("falha local");

        assertEquals(-1, e.getCode());
        assertEquals("falha local", e.getCdpMessage());
        assertNull(e.getCause());
    }

    @Test
    void messageAndCauseConstructorHasNoCode() {
        RuntimeException causa = new RuntimeException("causa");
        CDPException e = new CDPException("falha local", causa);

        assertEquals(-1, e.getCode());
        assertEquals(causa, e.getCause());
        assertEquals("falha local", e.getCdpMessage());
    }

    @Test
    void unknownSessionCodeClassifiesAsAttachedSessionNotFound() {
        CDPException e = CDPException.fromProtocolError(-32001, "qualquer texto bruto");

        assertEquals(-32001, e.getCode());
        assertTrue(e.hasKind(CDPException.Kind.ATTACHED_SESSION_NOT_FOUND));
        // mensagem canônica substitui o texto do protocolo
        assertEquals("Sessão CDP anexada não encontrada", e.getCdpMessage());
    }

    @Test
    void topLevelTargetRequiredIsClassifiedByCanonicalMessage() {
        CDPException e = CDPException.fromProtocolError(-32000,
            "  Command can only be executed on top-level targets  ");

        assertTrue(e.hasKind(CDPException.Kind.TOP_LEVEL_TARGET_REQUIRED));
        assertEquals("Command can only be executed on top-level targets", e.getCdpMessage());
    }

    @Test
    void notAttachedToActivePageIsClassifiedByCanonicalMessage() {
        CDPException e = CDPException.fromProtocolError(-32000, "Not attached to an active page");

        assertTrue(e.hasKind(CDPException.Kind.NOT_ATTACHED_TO_ACTIVE_PAGE));
    }

    @Test
    void anythingElseIsARejectedCommandWithGenericMessage() {
        CDPException semMensagem = CDPException.fromProtocolError(-32000, null);
        CDPException desconhecida = CDPException.fromProtocolError(-99999, "texto sensível do host");

        assertTrue(semMensagem.hasKind(CDPException.Kind.COMMAND_REJECTED));
        assertTrue(desconhecida.hasKind(CDPException.Kind.COMMAND_REJECTED));
        // mensagem bruta nunca atravessa: vira a genérica segura
        assertEquals("Comando CDP rejeitado", desconhecida.getCdpMessage());
    }
}
