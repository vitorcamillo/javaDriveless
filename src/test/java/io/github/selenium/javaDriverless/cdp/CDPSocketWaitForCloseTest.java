package io.github.selenium.javaDriverless.cdp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;

import io.github.selenium.javaDriverless.cdp.exceptions.CDPException;

import org.junit.jupiter.api.Test;

/** waitFor órfão: o fechamento da conexão não pode deixar esperas de evento pendentes para sempre. */
class CDPSocketWaitForCloseTest {

    /** Socket sem rede: URI em porta morta, nunca conectado; onClose é chamado direto. */
    private static CDPSocket novoSocket() {
        return new CDPSocket("ws://127.0.0.1:1/devtools/page/teste-waitfor-close", 5f, 1024);
    }

    @Test
    void waitForSemTimeoutFalhaComCdpExceptionQuandoConexaoFecha() throws Exception {
        CDPSocket socket = novoSocket();

        CompletableFuture<JsonNode> esperando = socket.waitFor("X", null);
        assertFalse(esperando.isDone(), "waitFor sem timeout não pode completar sozinho");

        socket.onClose(1000, "", true);

        Exception thrown = assertThrows(Exception.class, () -> esperando.get(5, TimeUnit.SECONDS));
        assertTrue(thrown.getCause() instanceof CDPException);
        assertTrue(thrown.getCause().getMessage().contains("fechada"));
    }

    @Test
    void waitForAposFechamentoFalhaImediatamente() {
        CDPSocket socket = novoSocket();
        socket.onClose(1000, "", true);

        CompletableFuture<JsonNode> esperando = socket.waitFor("X", null);

        assertTrue(esperando.isCompletedExceptionally(), "waitFor pós-fechamento falha de imediato");
        Exception thrown = assertThrows(Exception.class, esperando::join);
        assertTrue(thrown.getCause() instanceof CDPException);
        assertTrue(thrown.getCause().getMessage().contains("fechada"));
    }

    @Test
    void eventoTardioAposFechamentoNaoAlteraOResultadoDoWaitFor() {
        CDPSocket socket = novoSocket();
        CompletableFuture<JsonNode> esperando = socket.waitFor("X", null);
        socket.onClose(1000, "", true);

        // Evento "X" tardio pelo mesmo caminho de entrega do teste vizinho
        // (listenersReceivePushedEvents): exec dispara no servidor, que empurra o evento
        // e chega em dispatchEvent — aqui disparamos dispatchEvent direto, sem servidor.
        JsonNode paramsTardios = JsonNodeFactory.instance.objectNode().put("tardio", true);
        socket.dispatchEvent("X", paramsTardios, "");

        assertTrue(esperando.isCompletedExceptionally());
        Exception thrown = assertThrows(Exception.class, esperando::join);
        assertTrue(thrown.getCause() instanceof CDPException);
        assertTrue(thrown.getCause().getMessage().contains("fechada"));
    }

    @Test
    void eventoRecebidoAntesDoFechamentoCompletaENaoEAlteradoPeloOnClose() throws Exception {
        CDPSocket socket = novoSocket();

        CompletableFuture<JsonNode> esperando = socket.waitFor("X", null);
        JsonNode params = JsonNodeFactory.instance.objectNode().put("seq", 1);
        socket.dispatchEvent("X", params, "");

        JsonNode resultado = esperando.get(5, TimeUnit.SECONDS);
        assertEquals(1, resultado.path("seq").asInt());

        socket.onClose(1000, "", true);

        // o fechamento posterior não altera um resultado já obtido
        assertEquals(1, esperando.join().path("seq").asInt());
    }
}
