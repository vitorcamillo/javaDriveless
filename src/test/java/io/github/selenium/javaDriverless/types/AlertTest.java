package io.github.selenium.javaDriverless.types;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.concurrent.CompletableFuture;

import com.fasterxml.jackson.databind.node.NullNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** Diálogos JavaScript (alert/confirm/prompt) via Page.javascriptDialogOpening/handle. */
class AlertTest {

    private static final com.fasterxml.jackson.databind.ObjectMapper MAPPER =
        new com.fasterxml.jackson.databind.ObjectMapper();

    private Target target;

    @BeforeEach
    void setUp() {
        target = mock(Target.class);
        when(target.getSocket()).thenReturn(null);
    }

    private static ObjectNode json(String json) {
        try {
            return (ObjectNode) MAPPER.readTree(json);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private void stubDialog(ObjectNode dialogData) {
        when(target.waitForCdp(eq("Page.javascriptDialogOpening"), any()))
            .thenReturn(CompletableFuture.completedFuture(dialogData));
    }

    @Test
    void initReturnsTheSameAlertInstance() {
        stubDialog(json("{}"));
        Alert alert = new Alert(target, 1f);

        assertEquals(alert, alert.init().join());
    }

    @Test
    void dialogArrivingWithinTheTimeoutIsRead() {
        // diálogo chega em ~60ms com orçamento de 500ms: deve ser lido,
        // não tratado como ausente (mata mutação de unidade do timeout)
        when(target.waitForCdp(eq("Page.javascriptDialogOpening"), any()))
            .thenReturn(CompletableFuture.supplyAsync(() -> {
                try {
                    Thread.sleep(60);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                return json("{\"message\":\"Chegou tarde\"}");
            }));
        Alert alert = new Alert(target, 0.5f);

        assertEquals("Chegou tarde", alert.getText().join());
    }

    @Test
    void initIsIdempotentOnceStarted() {
        stubDialog(json("{}"));
        Alert alert = new Alert(target, 1f);

        alert.init().join();
        alert.init().join();

        verify(target, org.mockito.Mockito.times(1))
            .waitForCdp(eq("Page.javascriptDialogOpening"), any());
    }

    @Test
    void alertDetectedViaSocketSkipsEventWait() {
        when(target.getSocket()).thenReturn(mock(io.github.selenium.javaDriverless.cdp.CDPSocket.class));
        Alert alert = new Alert(target, 1f);

        alert.init().join();

        verify(target, org.mockito.Mockito.never()).waitForCdp(any(), any());
    }

    @Test
    void dialogFieldsAreExposedAsTextUrlTypeAndPrompt() {
        stubDialog(json("{\"message\":\"Do you wish to quit?\",\"url\":\"https://example.test/quit\","
            + "\"type\":\"confirm\",\"hasBrowserHandler\":true,\"defaultPrompt\":\"yes\"}"));
        Alert alert = new Alert(target, 1f);

        assertEquals("Do you wish to quit?", alert.getText().join());
        assertEquals("https://example.test/quit", alert.getUrl().join());
        assertEquals("confirm", alert.getType().join());
        assertEquals(Boolean.TRUE, alert.hasBrowserHandler().join());
        assertEquals("yes", alert.getDefaultPrompt().join());
    }

    @Test
    void undetectedDialogYieldsDefaults() {
        // future que nunca completa: o timeout vem do get(ms) e vira warn + estado iniciado
        when(target.waitForCdp(eq("Page.javascriptDialogOpening"), any()))
            .thenReturn(new CompletableFuture<>());
        Alert alert = new Alert(target, 0.05f);

        assertEquals("", alert.getText().join());
        assertEquals("", alert.getUrl().join());
        assertEquals("", alert.getType().join());
        assertFalse(alert.hasBrowserHandler().join());
        assertEquals("", alert.getDefaultPrompt().join());
    }

    @Test
    void acceptAndDismissSendHandleJavaScriptDialogWithAcceptFlag() {
        stubDialog(json("{}"));
        when(target.executeCdpCmd(eq("Page.handleJavaScriptDialog"), anyMap(), isNull()))
            .thenReturn(CompletableFuture.completedFuture(NullNode.getInstance()));
        Alert alert = new Alert(target, 1f);

        alert.accept().join();
        verify(target).executeCdpCmd(eq("Page.handleJavaScriptDialog"),
            argThat(args -> Boolean.TRUE.equals(args.get("accept"))), isNull());

        alert.dismiss().join();
        verify(target).executeCdpCmd(eq("Page.handleJavaScriptDialog"),
            argThat(args -> Boolean.FALSE.equals(args.get("accept"))), isNull());
    }

    @Test
    void sendKeysHandlesDialogWithPromptTextAndAccept() {
        stubDialog(json("{\"type\":\"prompt\"}"));
        when(target.executeCdpCmd(eq("Page.handleJavaScriptDialog"), anyMap(), isNull()))
            .thenReturn(CompletableFuture.completedFuture(NullNode.getInstance()));
        Alert alert = new Alert(target, 1f);

        alert.sendKeys("William Shakespeare").join();

        verify(target).executeCdpCmd(eq("Page.handleJavaScriptDialog"),
            argThat(args -> Boolean.TRUE.equals(args.get("accept"))
                && "William Shakespeare".equals(args.get("promptText"))), isNull());
    }

    @Test
    void initWrapsUnexpectedWaitErrors() {
        when(target.waitForCdp(eq("Page.javascriptDialogOpening"), any()))
            .thenReturn(CompletableFuture.failedFuture(new IllegalStateException("socket closed")));
        Alert alert = new Alert(target, 1f);

        CompletableFuture<Alert> future = alert.init();
        Exception thrown = assertThrows(Exception.class, () -> future.join());
        assertTrue(thrown.getCause().getMessage().contains("Erro ao aguardar alerta"));
    }
}
