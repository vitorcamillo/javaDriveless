package io.github.selenium.javaDriverless.support;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import io.github.selenium.javaDriverless.Chrome;
import io.github.selenium.javaDriverless.types.By;
import io.github.selenium.javaDriverless.types.Target;
import io.github.selenium.javaDriverless.types.WebElement;

/** Interrupção não pode ser engolida pelas condições: flag restaurado encerra a espera do WebDriverWait. */
class ExpectedConditionsInterruptTest {

    @Test
    void condicaoBooleanaRetornaFalseEPreservaFlagDeInterrupcao() {
        Thread.currentThread().interrupt();
        try {
            Chrome chrome = mock(Chrome.class);
            when(chrome.getCurrentUrl()).thenReturn(new CompletableFuture<>()); // nunca completa

            Boolean resultado = ExpectedConditions.urlContains("qualquer").apply(chrome);

            assertFalse(resultado);
            assertTrue(Thread.currentThread().isInterrupted(), "flag de interrupção deve permanecer");
        } finally {
            Thread.interrupted(); // limpa o flag da thread de teste
        }
    }

    @Test
    void condicaoComFirstElementRetornaFalsoEPreservaFlagDeInterrupcao() {
        Thread.currentThread().interrupt();
        try {
            Chrome chrome = mock(Chrome.class);
            when(chrome.getCurrentTarget()).thenReturn(new CompletableFuture<>()); // nunca completa

            // firstElement devolve null internamente (get interrompido);
            // a condição pública presenceOfElement observa false
            assertFalse(ExpectedConditions.presenceOfElement(".x").apply(chrome));
            assertTrue(Thread.currentThread().isInterrupted(), "flag de interrupção deve permanecer");
        } finally {
            Thread.interrupted();
        }
    }

    @Test
    void webDriverWaitInterrompidoLancaRuntimeExceptionEmMenosDe5Segundos() {
        Thread.currentThread().interrupt();
        try {
            Chrome chrome = mock(Chrome.class);
            when(chrome.getCurrentUrl()).thenReturn(new CompletableFuture<>()); // nunca completa

            // polling de 10s: sem o flag restaurado, o sleep engoliria a espera e não lançaria
            WebDriverWait espera = new WebDriverWait(chrome, Duration.ofSeconds(6))
                    .pollingEvery(Duration.ofSeconds(10));

            long inicio = System.nanoTime();
            RuntimeException erro = assertThrows(RuntimeException.class,
                    () -> espera.until(ExpectedConditions.urlContains("qualquer")));
            long decorridoMs = (System.nanoTime() - inicio) / 1_000_000;

            assertTrue(erro.getMessage().contains("interrompida"));
            assertTrue(decorridoMs < 5_000, "deve falhar rápido (decorrido=" + decorridoMs + "ms)");
        } finally {
            Thread.interrupted();
        }
    }

    // ---------- Caminhos até cada catch com restaurarInterrupcao (mutantes das linhas 50,
    // 70, 108, 127, 148, 171, 187, 206 e 244) ----------

    /** Mock de Chrome cujo primeiro elemento encontrado tem um future pendente no método dado. */
    private static WebElement primeiroElementoResolvido(Chrome chrome,
            Consumer<WebElement> pendenciaNoElemento) {
        Target alvo = mock(Target.class);
        WebElement elemento = mock(WebElement.class);
        when(chrome.getCurrentTarget()).thenReturn(CompletableFuture.completedFuture(alvo));
        when(alvo.findElements(eq(By.CSS_SELECTOR), eq(".alvo"), eq(0f)))
            .thenReturn(CompletableFuture.completedFuture(List.of(elemento)));
        pendenciaNoElemento.accept(elemento);
        return elemento;
    }

    static Stream<Arguments> condicoesComCatchQueDevemPreservarInterrupcao() {
        return Stream.of(
            Arguments.of("titleContains",
                (Consumer<Chrome>) chrome -> when(chrome.getCurrentTarget())
                    .thenReturn(new CompletableFuture<>()),
                (Function<Chrome, Boolean>) driver ->
                    ExpectedConditions.titleContains("titulo").apply(driver)),
            Arguments.of("titleIs",
                (Consumer<Chrome>) chrome -> when(chrome.getCurrentTarget())
                    .thenReturn(new CompletableFuture<>()),
                (Function<Chrome, Boolean>) driver ->
                    ExpectedConditions.titleIs("titulo").apply(driver)),
            Arguments.of("urlToBe",
                (Consumer<Chrome>) chrome -> when(chrome.getCurrentUrl())
                    .thenReturn(new CompletableFuture<>()),
                (Function<Chrome, Boolean>) driver ->
                    ExpectedConditions.urlToBe("https://x.test/").apply(driver)),
            Arguments.of("urlMatches",
                (Consumer<Chrome>) chrome -> when(chrome.getCurrentUrl())
                    .thenReturn(new CompletableFuture<>()),
                (Function<Chrome, Boolean>) driver ->
                    ExpectedConditions.urlMatches("https://.*").apply(driver)),
            Arguments.of("jsReturnsTrue",
                (Consumer<Chrome>) chrome -> when(chrome.executeScript(eq("return true"),
                    isNull(), eq(false))).thenReturn(new CompletableFuture<>()),
                (Function<Chrome, Boolean>) driver ->
                    ExpectedConditions.jsReturnsTrue("return true").apply(driver)),
            Arguments.of("visibilityOfElement",
                (Consumer<Chrome>) chrome -> primeiroElementoResolvido(chrome,
                    elemento -> when(elemento.isDisplayed()).thenReturn(new CompletableFuture<>())),
                (Function<Chrome, Boolean>) driver ->
                    ExpectedConditions.visibilityOfElement(".alvo").apply(driver)),
            Arguments.of("elementToBeClickable",
                (Consumer<Chrome>) chrome -> primeiroElementoResolvido(chrome, elemento -> {
                    when(elemento.isDisplayed())
                        .thenReturn(CompletableFuture.completedFuture(true));
                    when(elemento.isEnabled()).thenReturn(new CompletableFuture<>());
                }),
                (Function<Chrome, Boolean>) driver ->
                    ExpectedConditions.elementToBeClickable(".alvo").apply(driver)),
            Arguments.of("textToBePresentInElement",
                (Consumer<Chrome>) chrome -> primeiroElementoResolvido(chrome,
                    elemento -> when(elemento.getText()).thenReturn(new CompletableFuture<>())),
                (Function<Chrome, Boolean>) driver ->
                    ExpectedConditions.textToBePresentInElement(".alvo", "texto").apply(driver)),
            Arguments.of("numberOfWindowsToBe",
                (Consumer<Chrome>) chrome -> when(chrome.getTargets())
                    .thenReturn(new CompletableFuture<>()),
                (Function<Chrome, Boolean>) driver ->
                    ExpectedConditions.numberOfWindowsToBe(1).apply(driver)));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("condicoesComCatchQueDevemPreservarInterrupcao")
    void condicaoInterrompidaNoCatchRetornaFalseEPreservaOFlag(String nome,
            Consumer<Chrome> preparacao, Function<Chrome, Boolean> condicao) {
        Thread.currentThread().interrupt();
        try {
            Chrome chrome = mock(Chrome.class);
            preparacao.accept(chrome);

            assertFalse(condicao.apply(chrome), nome + " deve retornar false ao interromper");
            assertTrue(Thread.currentThread().isInterrupted(),
                nome + ": flag de interrupção deve permanecer");
        } finally {
            Thread.interrupted(); // limpa o flag da thread de teste
        }
    }
}
