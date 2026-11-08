package io.github.selenium.javaDriverless.types;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.io.PipedInputStream;
import java.io.PipedOutputStream;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import org.junit.jupiter.api.Test;

import io.github.selenium.javaDriverless.cdp.CDPSocket;
import io.github.selenium.javaDriverless.cdp.pipe.CdpPipeChannel;

/**
 * Regressão de defeitos confirmados em types: alerta capturado via socket,
 * JSEvalException sem NPE em throw de string e atomicidade de TargetConnectionCore.init.
 */
class AuditoriaTypesRegressaoTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static ObjectNode json(String conteudo) {
        try {
            return (ObjectNode) MAPPER.readTree(conteudo);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    // ---------- Alert ----------

    @Test
    void alertaCapturadoPeloSocketEhLidoSemEsperarEvento() {
        Target target = mock(Target.class);
        when(target.getSocket()).thenReturn(mock(CDPSocket.class));
        target.alert = json("{\"message\":\"Do you wish to quit?\"}");

        Alert alert = new Alert(target, 1f);

        assertEquals("Do you wish to quit?", alert.getText().join());
        verify(target, never()).waitForCdp(any(), any());
    }

    @Test
    void alertaComSocketSemDadosCapturadosDevolveTextoVazio() {
        Target target = mock(Target.class);
        when(target.getSocket()).thenReturn(mock(CDPSocket.class));
        target.alert = null;

        Alert alert = new Alert(target, 1f);

        assertEquals("", alert.getText().join());
        verify(target, never()).waitForCdp(any(), any());
    }

    // ---------- TypesExceptions.JSEvalException ----------

    @Test
    void jsEvalExceptionComThrowDeStringNaoLancaNpe() {
        // throw "texto": exception sem description/className/objectId
        TypesExceptions.JSEvalException excecao = new TypesExceptions.JSEvalException(
            json("{\"exceptionId\":1,\"text\":\"Uncaught\","
                + "\"exception\":{\"type\":\"string\",\"value\":\"texto\"}}"));

        assertEquals("texto", excecao.getMessage());
        assertNull(excecao.getObjId());
        assertNull(excecao.getClassName());
        assertEquals("Uncaught", excecao.getText());
        assertEquals("string", excecao.getType());
    }

    @Test
    void jsEvalExceptionComTodosOsCamposMantemValores() {
        TypesExceptions.JSEvalException excecao = new TypesExceptions.JSEvalException(
            json("{\"exceptionId\":7,\"text\":\"Uncaught TypeError\","
                + "\"lineNumber\":12,\"columnNumber\":34,"
                + "\"exception\":{\"type\":\"object\",\"subtype\":\"error\","
                + "\"className\":\"TypeError\",\"description\":\"TypeError: x is not a function\","
                + "\"objectId\":\"1:2\"}}"));

        assertEquals("TypeError: x is not a function", excecao.getMessage());
        assertEquals(7, excecao.getExcId());
        assertEquals("Uncaught TypeError", excecao.getText());
        assertEquals(12, excecao.getLineNumber());
        assertEquals(34, excecao.getColumnNumber());
        assertEquals("object", excecao.getType());
        assertEquals("error", excecao.getSubtype());
        assertEquals("TypeError", excecao.getClassName());
        assertEquals("TypeError: x is not a function", excecao.getDescription());
        assertEquals("1:2", excecao.getObjId());
    }

    @Test
    void jsEvalExceptionSemDescriptionUsaTextComoMensagem() {
        TypesExceptions.JSEvalException excecao = new TypesExceptions.JSEvalException(
            json("{\"exceptionId\":2,\"text\":\"Uncaught\",\"exception\":{\"type\":\"object\"}}"));

        assertEquals("Uncaught", excecao.getMessage());
        assertEquals("object", excecao.getType());
    }

    @Test
    void jsEvalExceptionSemNadaUtilRetornaMensagemPadrao() {
        TypesExceptions.JSEvalException excecao = new TypesExceptions.JSEvalException(
            json("{\"exceptionId\":3,\"exception\":{\"type\":\"object\"}}"));

        assertEquals("Erro de JavaScript", excecao.getMessage());
    }

    @Test
    void jsEvalExceptionComValueNaoNuloIgnoraONuloEUsaText() {
        TypesExceptions.JSEvalException excecao = new TypesExceptions.JSEvalException(
            json("{\"exceptionId\":4,\"text\":\"Uncaught\","
                + "\"exception\":{\"type\":\"string\",\"value\":null}}"));

        assertEquals("Uncaught", excecao.getMessage());
        assertEquals("string", excecao.getType());
    }

    // ---------- TargetConnectionCore.init ----------

    @Test
    void initConcorrenteUsaUmaUnicaTentativaESoPublicaSocketAposConectar() throws Exception {
        // Canal pipe que nunca responde: a tentativa de conexão fica pendente (sem rede real),
        // permitindo observar o estado durante init().
        PipedInputStream respostas = new PipedInputStream();
        PipedOutputStream comandos = new PipedOutputStream(respostas);
        CdpPipeChannel canal = new CdpPipeChannel(respostas, comandos, 5f, 1024 * 1024);
        canal.start();

        Target target = new Target(canal.getHost(), "tid-init", null, null,
            false, 5f, null, false, 10 * 1024 * 1024);
        try {
            CompletableFuture<Target> primeira = target.init();
            CompletableFuture<Target> segunda = target.init();

            // Durante a tentativa (conexão pendente): mesma future, socket ainda não publicado
            assertSame(primeira, segunda);
            assertNull(target.getSocket());

            // Falha a conexão: socket continua nulo e initFuture é liberado
            canal.close();
            assertThrows(Exception.class, () -> primeira.get(5, TimeUnit.SECONDS));
            assertNull(target.getSocket());

            // Novo init() faz outra tentativa (future distinta, que também falha sem canal)
            CompletableFuture<Target> terceira = target.init();
            assertNotSame(primeira, terceira);
            assertThrows(Exception.class, () -> terceira.get(5, TimeUnit.SECONDS));
            assertNull(target.getSocket());
        } finally {
            canal.close();
            comandos.close();
        }
    }
}
