package io.github.selenium.javaDriverless.scripts;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
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

import java.util.List;
import java.util.concurrent.CompletableFuture;

import com.fasterxml.jackson.databind.node.NullNode;

import org.junit.jupiter.api.Test;

import io.github.selenium.javaDriverless.types.Alert;
import io.github.selenium.javaDriverless.types.Target;
import io.github.selenium.javaDriverless.types.WebElement;

/** Navegação de foco entre alvo/conteúdo — inclusive caminhos de erro de reflexão do contexto. */
class SwitchToTest {

    // Contexto falso expondo a superfície que SwitchTo resolve por reflexão
    static class FakeContext {
        final Target currentTarget;
        final CompletableFuture<Target> currentTargetFuture;

        FakeContext(Target target) {
            this.currentTarget = target;
            this.currentTargetFuture = CompletableFuture.completedFuture(target);
        }

        public Target getCurrentTarget() {
            return currentTarget;
        }

        @SuppressWarnings("unused")
        public CompletableFuture<Target> currentTargetFuture() {
            return currentTargetFuture;
        }

        @SuppressWarnings("unused")
        public CompletableFuture<WebElement> findElement(String by, String value, float timeout) {
            return CompletableFuture.failedFuture(new RuntimeException("não encontrado"));
        }

        @SuppressWarnings("unused")
        public CompletableFuture<List<WebElement>> findElements(String by, String value, float timeout) {
            return CompletableFuture.completedFuture(List.of());
        }
    }

    private static Target targetWithDialogSupport() {
        Target target = mock(Target.class);
        when(target.getSocket()).thenReturn(null);
        when(target.waitForCdp(any(), any()))
            .thenReturn(new CompletableFuture<>());
        when(target.executeCdpCmd(any(), anyMap(), any()))
            .thenReturn(CompletableFuture.completedFuture(NullNode.getInstance()));
        return target;
    }

    @Test
    void getAlertInitializesAlertOnCurrentTarget() {
        Target target = targetWithDialogSupport();
        SwitchTo switchTo = new SwitchTo(new FakeContext(target));

        Alert alert = switchTo.getAlert(0.05f).join();

        assertInstanceOf(Alert.class, alert);
        // timeout esgotado sem diálogo: texto vazio, sem explosão
        assertEquals("", alert.getText().join());
    }

    @Test
    void alertUsesDefaultTimeoutOfFiveSeconds() {
        Target target = targetWithDialogSupport();
        SwitchTo switchTo = new SwitchTo(new FakeContext(target));

        assertInstanceOf(Alert.class, switchTo.alert().join());
    }

    @Test
    void defaultContentAcceptsPlainTarget() {
        // getCurrentTarget aqui devolve Target cru (contrato do Context) -> devolve o target
        Target target = targetWithDialogSupport();
        SwitchTo switchTo = new SwitchTo(new FakeContext(target));

        assertEquals(target, switchTo.defaultContent(false).join());
    }

    @Test
    void defaultContentActivatesTheTargetWhenRequested() {
        Target target = targetWithDialogSupport();
        when(target.getId()).thenReturn("t-1");
        SwitchTo switchTo = new SwitchTo(new FakeContext(target));

        assertEquals(target, switchTo.defaultContent(true).join());

        verify(target).executeCdpCmd(eq("Target.activateTarget"), argThat(args ->
            "t-1".equals(args.get("targetId"))), isNull());
    }

    @Test
    void defaultContentWithoutActivateSendsNoCdpCommands() {
        Target target = targetWithDialogSupport();
        SwitchTo switchTo = new SwitchTo(new FakeContext(target));

        assertEquals(target, switchTo.defaultContent(false).join());

        verify(target, org.mockito.Mockito.never())
            .executeCdpCmd(any(), anyMap(), any());
    }

    @Test
    void defaultContentFailsWhenContextReturnsNullTarget() {
        SwitchTo switchTo = new SwitchTo(new ContextWithNullTarget());

        Exception thrown = assertThrows(Exception.class,
            () -> switchTo.defaultContent(false).join());

        assertInstanceOf(IllegalStateException.class, thrown.getCause());
    }

    @Test
    void frameByWebElementFailsWithExplicitUnsupportedMessage() {
        Target target = targetWithDialogSupport();
        SwitchTo switchTo = new SwitchTo(new FakeContext(target));
        WebElement iframe = mock(WebElement.class);

        CompletableFuture<Target> future = switchTo.frame(iframe, false);

        Exception thrown = assertThrows(Exception.class, () -> future.join());
        assertInstanceOf(UnsupportedOperationException.class, thrown.getCause());
    }

    @Test
    void frameByInvalidReferenceFailsImmediately() {
        SwitchTo switchTo = new SwitchTo(new FakeContext(targetWithDialogSupport()));

        CompletableFuture<Target> future = switchTo.frame(3.14, false);

        Exception thrown = assertThrows(Exception.class, () -> future.join());
        assertInstanceOf(IllegalArgumentException.class, thrown.getCause());
    }

    @Test
    void frameByInvalidIndexReportsOutOfRange() {
        class ContextWithFrames {
            @SuppressWarnings("unused")
            public CompletableFuture<List<WebElement>> findElements(String by, String value, float timeout) {
                return CompletableFuture.completedFuture(List.of(mock(WebElement.class)));
            }
        }

        SwitchTo switchTo = new SwitchTo(new ContextWithFrames());

        CompletableFuture<Target> future = switchTo.frame(5, false);

        Exception thrown = assertThrows(Exception.class, () -> future.join());
        // índice inválido -> RuntimeException do thenApply encapsulada
        assertInstanceOf(RuntimeException.class,
            thrown.getCause() != null ? thrown.getCause() : thrown);
    }

    @Test
    void targetWithTargetInstanceActivatesAndFocusesWhenRequested() {
        Target target = targetWithDialogSupport();
        when(target.getId()).thenReturn("t-1");
        SwitchTo switchTo = new SwitchTo(new FakeContext(target));

        Target result = switchTo.target(target, true, true).join();

        assertEquals(target, result);
        verify(target).executeCdpCmd(eq("Target.activateTarget"), argThat(args ->
            "t-1".equals(args.get("targetId"))), isNull());
        verify(target).executeCdpCmd(eq("Emulation.setFocusEmulationEnabled"),
            argThat(args -> Boolean.TRUE.equals(args.get("enabled"))), isNull());
    }

    @Test
    void targetWithoutActivateOrFocusSendsNoCdpCommands() {
        Target target = targetWithDialogSupport();
        SwitchTo switchTo = new SwitchTo(new FakeContext(target));

        Target result = switchTo.target(target, false, false).join();

        assertEquals(target, result);
        verify(target, org.mockito.Mockito.never())
            .executeCdpCmd(eq("Target.activateTarget"), anyMap(), any());
        verify(target, org.mockito.Mockito.never())
            .executeCdpCmd(eq("Emulation.setFocusEmulationEnabled"), anyMap(), any());
    }

    @Test
    void targetByIdStringIsNotYetSupported() {
        SwitchTo switchTo = new SwitchTo(new FakeContext(targetWithDialogSupport()));

        CompletableFuture<Target> future = switchTo.target("some-id", false, false);

        Exception thrown = assertThrows(Exception.class, () -> future.join());
        assertInstanceOf(UnsupportedOperationException.class, thrown.getCause());
    }

    @Test
    void windowByStringIdIsNotYetSupported() {
        SwitchTo switchTo = new SwitchTo(new FakeContext(targetWithDialogSupport()));

        CompletableFuture<Target> future = switchTo.window("w", false, false);

        Exception thrown = assertThrows(Exception.class, () -> future.join());
        assertInstanceOf(UnsupportedOperationException.class, thrown.getCause());
    }

    /** Contexto cujo getCurrentTarget devolve o CompletableFuture (superfície de ChromeTargetActions). */
    static class FakeContextWithFutureTarget {
        @SuppressWarnings("unused")
        public CompletableFuture<Target> getCurrentTarget() {
            return CompletableFuture.completedFuture(targetWithDialogSupport());
        }
    }

    /** Contexto cujo getCurrentTarget devolve null: defaultContent tem que falhar fechado. */
    static class ContextWithNullTarget {
        @SuppressWarnings("unused")
        public Target getCurrentTarget() {
            return null;
        }
    }

    /** Contexto sem nenhum método getCurrentTarget: reflexão tem que falhar fechado. */
    static class ContextWithoutTargetApi {
        @SuppressWarnings("unused")
        public String metodoAlheio() {
            return "nada a ver";
        }
    }

    /** findElement: por ID devolve falha como future; por NAME lança na chamada. */
    static class ContextIdFutureNameThrow {
        @SuppressWarnings("unused")
        public CompletableFuture<WebElement> findElement(String by, String value, float timeout) {
            if (io.github.selenium.javaDriverless.types.By.ID.equals(by)) {
                return CompletableFuture.failedFuture(new RuntimeException("sem id"));
            }
            throw new IllegalStateException("lookup quebrou");
        }
    }

    /** findElement resolve por NAME depois de falhar por ID. */
    static class ContextNameFallback {
        @SuppressWarnings("unused")
        public CompletableFuture<WebElement> findElement(String by, String value, float timeout) {
            if (io.github.selenium.javaDriverless.types.By.ID.equals(by)) {
                return CompletableFuture.failedFuture(new RuntimeException("sem id"));
            }
            return CompletableFuture.completedFuture(mock(WebElement.class));
        }
    }

    @Test
    void defaultContentResolvesTheCurrentTargetFuture() {
        SwitchTo switchTo = new SwitchTo(new FakeContextWithFutureTarget());

        Target target = switchTo.defaultContent(true).join();

        assertNotNull(target);
    }

    @Test
    void getAlertFailsClosedWhenContextHasNoTargetApi() {
        SwitchTo switchTo = new SwitchTo(new ContextWithoutTargetApi());

        Exception thrown = assertThrows(Exception.class,
            () -> switchTo.getAlert(0.05f).join());

        assertEquals("Erro ao obter alerta", thrown.getCause().getMessage());
    }

    @Test
    void frameByNameFallsBackAfterIdMissesAndFailsAtThePendingIframeGate() {
        SwitchTo switchTo = new SwitchTo(new ContextNameFallback());

        Exception thrown = assertThrows(Exception.class,
            () -> switchTo.frame("login", false).join());

        // elemento encontrado -> o gate de iframes CORS ainda não implementado dispara
        assertInstanceOf(UnsupportedOperationException.class, thrown.getCause());
    }

    @Test
    void frameFailsClosedWhenTheNameLookupItselfBlowsUp() {
        SwitchTo switchTo = new SwitchTo(new ContextIdFutureNameThrow());

        Exception thrown = assertThrows(Exception.class,
            () -> switchTo.frame("login", false).join());

        assertTrue(thrown.getCause().getMessage().contains("Frame não encontrado: login"));
    }

    @Test
    void frameByValidIndexFindsTheElementAndHitsThePendingIframeGate() {
        class ContextWithFrames {
            @SuppressWarnings("unused")
            public CompletableFuture<WebElement> findElement(String by, String value, float timeout) {
                return CompletableFuture.failedFuture(new RuntimeException("índice não busca unitário"));
            }

            @SuppressWarnings("unused")
            public CompletableFuture<List<WebElement>> findElements(String by, String value, float timeout) {
                return CompletableFuture.completedFuture(List.of(mock(WebElement.class)));
            }
        }

        SwitchTo switchTo = new SwitchTo(new ContextWithFrames());

        Exception thrown = assertThrows(Exception.class,
            () -> switchTo.frame(0, false).join());

        assertInstanceOf(UnsupportedOperationException.class, thrown.getCause());
    }
}
