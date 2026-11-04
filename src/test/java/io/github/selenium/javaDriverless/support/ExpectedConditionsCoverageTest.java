package io.github.selenium.javaDriverless.support;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.concurrent.CompletableFuture;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import io.github.selenium.javaDriverless.Chrome;
import io.github.selenium.javaDriverless.types.Target;
import io.github.selenium.javaDriverless.types.WebElement;

/** Condições de espera: todos os builders contra Chrome/Target mockados, inclusive falhas. */
class ExpectedConditionsCoverageTest {

    private Chrome chrome;
    private Target target;

    @BeforeEach
    void setUp() {
        chrome = mock(Chrome.class);
        target = mock(Target.class);
        when(chrome.getCurrentTarget()).thenReturn(CompletableFuture.completedFuture(target));
    }

    @Test
    void titleConditionsMatchContainsAndExact() {
        when(target.getTitle()).thenReturn(CompletableFuture.completedFuture("Bet365 Apostas"));

        assertTrue(ExpectedConditions.titleContains("Apostas").apply(chrome));
        assertFalse(ExpectedConditions.titleContains("Poker").apply(chrome));
        assertFalse(ExpectedConditions.titleIs("Bet365 Apostas ERRADO").apply(chrome));
        // titleIs exige igualdade exata
        when(target.getTitle()).thenReturn(CompletableFuture.completedFuture("X"));
        assertTrue(ExpectedConditions.titleIs("X").apply(chrome));
    }

    @Test
    void urlConditionsCoverContainsExactAndRegex() {
        when(chrome.getCurrentUrl()).thenReturn(CompletableFuture.completedFuture(
            "https://www.example.test/rota?id=7"));

        assertTrue(ExpectedConditions.urlContains("rota").apply(chrome));
        assertFalse(ExpectedConditions.urlContains("outra").apply(chrome));
        assertFalse(ExpectedConditions.urlToBe("https://outra.test/").apply(chrome));
        assertTrue(ExpectedConditions.urlToBe("https://www.example.test/rota?id=7").apply(chrome));
        assertTrue(ExpectedConditions.urlMatches(".*id=\\d+").apply(chrome));
        assertFalse(ExpectedConditions.urlMatches("^ftp://").apply(chrome));
    }

    @Test
    void jsReturnsTrueOnlyForBooleanTrueResults() {
        when(chrome.executeScript(eq("window.ready"), any(), eq(false)))
            .thenReturn(CompletableFuture.completedFuture(Boolean.TRUE))
            .thenReturn(CompletableFuture.completedFuture(Boolean.FALSE))
            .thenReturn(CompletableFuture.completedFuture("não-bool"));

        assertTrue(ExpectedConditions.jsReturnsTrue("window.ready").apply(chrome));
        assertFalse(ExpectedConditions.jsReturnsTrue("window.ready").apply(chrome));
        assertFalse(ExpectedConditions.jsReturnsTrue("window.ready").apply(chrome));
    }

    @Test
    void presenceVisibilityClickabilityAndTextConditions() {
        WebElement element = mock(WebElement.class);
        when(element.isDisplayed()).thenReturn(CompletableFuture.completedFuture(true));
        when(element.isEnabled()).thenReturn(CompletableFuture.completedFuture(true));
        when(element.getText()).thenReturn(CompletableFuture.completedFuture("Saldo: R$ 10"));
        when(target.findElements(eq("css selector"), eq(".saldo"), eq(0f)))
            .thenReturn(CompletableFuture.completedFuture(List.of(element)));
        when(target.findElements(eq("css selector"), eq(".ausente"), eq(0f)))
            .thenReturn(CompletableFuture.completedFuture(List.of()));

        assertTrue(ExpectedConditions.presenceOfElement(".saldo").apply(chrome));
        assertFalse(ExpectedConditions.presenceOfElement(".ausente").apply(chrome));
        assertTrue(ExpectedConditions.visibilityOfElement(".saldo").apply(chrome));
        assertFalse(ExpectedConditions.visibilityOfElement(".ausente").apply(chrome));
        assertTrue(ExpectedConditions.elementToBeClickable(".saldo").apply(chrome));
        assertFalse(ExpectedConditions.elementToBeClickable(".ausente").apply(chrome));
        assertTrue(ExpectedConditions.textToBePresentInElement(".saldo", "R$ 10").apply(chrome));
        assertFalse(ExpectedConditions.textToBePresentInElement(".saldo", "USD").apply(chrome));

        // elemento invisível/desabilitado reprova as condições compostas
        when(element.isDisplayed()).thenReturn(CompletableFuture.completedFuture(false));
        assertFalse(ExpectedConditions.visibilityOfElement(".saldo").apply(chrome));
        assertFalse(ExpectedConditions.elementToBeClickable(".saldo").apply(chrome));
    }

    @Test
    void numberOfWindowsCountsTargets() {
        when(chrome.getTargets())
            .thenReturn(CompletableFuture.completedFuture(List.of(mock(Target.class),
                mock(Target.class))));

        assertTrue(ExpectedConditions.numberOfWindowsToBe(2).apply(chrome));
        assertFalse(ExpectedConditions.numberOfWindowsToBe(3).apply(chrome));
    }

    @Test
    void unknownDriverYieldsFalseWithoutThrowing() {
        assertFalse(ExpectedConditions.titleContains("x").apply("not-a-driver"));
        assertFalse(ExpectedConditions.urlContains("x").apply(null));
        assertFalse(ExpectedConditions.presenceOfElement(".x").apply(42));
    }

    @Test
    void failingChromeIsSwallowedAsFalse() {
        when(target.getTitle())
            .thenReturn(CompletableFuture.failedFuture(new IllegalStateException("down")));
        when(chrome.getCurrentUrl())
            .thenReturn(CompletableFuture.failedFuture(new IllegalStateException("down")));

        assertFalse(ExpectedConditions.titleContains("x").apply(chrome));
        assertFalse(ExpectedConditions.urlContains("x").apply(chrome));
    }

    @Test
    void customConditionPassesThroughTheDelegate() {
        assertTrue(ExpectedConditions.customCondition(d -> true).apply(chrome));
        assertFalse(ExpectedConditions.customCondition(d -> false).apply(chrome));
    }

    @Test
    void failingTargetsAreSwallowedAsFalseByEveryCondition() {
        RuntimeException down = new IllegalStateException("down");
        when(target.getTitle()).thenReturn(CompletableFuture.failedFuture(down));
        when(chrome.getCurrentUrl()).thenReturn(CompletableFuture.failedFuture(down));
        when(chrome.executeScript(any(), any(), eq(false)))
            .thenReturn(CompletableFuture.failedFuture(down));
        when(chrome.getTargets()).thenReturn(CompletableFuture.failedFuture(down));
        when(target.findElements(any(), any(), org.mockito.ArgumentMatchers.anyFloat()))
            .thenReturn(CompletableFuture.failedFuture(down));

        assertFalse(ExpectedConditions.titleIs("qualquer").apply(chrome));
        assertFalse(ExpectedConditions.urlToBe("https://x.test/").apply(chrome));
        assertFalse(ExpectedConditions.urlMatches(".*").apply(chrome));
        assertFalse(ExpectedConditions.jsReturnsTrue("return 1").apply(chrome));
        assertFalse(ExpectedConditions.visibilityOfElement(".x").apply(chrome));
        assertFalse(ExpectedConditions.elementToBeClickable(".x").apply(chrome));
        assertFalse(ExpectedConditions.textToBePresentInElement(".x", "texto").apply(chrome));
        assertFalse(ExpectedConditions.numberOfWindowsToBe(1).apply(chrome));
    }

    @Test
    void elementFailuresAreSwallowedAsFalseByCompositeConditions() {
        WebElement element = mock(WebElement.class);
        when(element.isDisplayed())
            .thenReturn(CompletableFuture.failedFuture(new IllegalStateException("css caiu")));
        when(element.getText())
            .thenReturn(CompletableFuture.failedFuture(new IllegalStateException("dom caiu")));
        when(target.findElements(eq("css selector"), eq(".quebra"), eq(0f)))
            .thenReturn(CompletableFuture.completedFuture(List.of(element)));

        assertFalse(ExpectedConditions.visibilityOfElement(".quebra").apply(chrome));
        assertFalse(ExpectedConditions.elementToBeClickable(".quebra").apply(chrome));
        assertFalse(ExpectedConditions.textToBePresentInElement(".quebra", "texto").apply(chrome));
    }

    @Test
    void nullTitleAndUrlNeverMatchAnyCondition() {
        when(target.getTitle()).thenReturn(CompletableFuture.completedFuture(null));
        when(chrome.getCurrentUrl()).thenReturn(CompletableFuture.completedFuture(null));

        assertFalse(ExpectedConditions.titleContains("x").apply(chrome));
        assertFalse(ExpectedConditions.titleIs("x").apply(chrome));
        assertFalse(ExpectedConditions.urlContains("x").apply(chrome));
        assertFalse(ExpectedConditions.urlToBe("x").apply(chrome));
        assertFalse(ExpectedConditions.urlMatches(".*").apply(chrome));
    }

    @Test
    void textConditionIsFalseForAbsentElementWithoutReadingIt() {
        when(target.findElements(eq("css selector"), eq(".ausente"), eq(0f)))
            .thenReturn(CompletableFuture.completedFuture(List.of()));

        assertFalse(ExpectedConditions.textToBePresentInElement(".ausente", "qualquer").apply(chrome));
    }
}
