package io.github.selenium.javaDriverless.support;

import io.github.selenium.javaDriverless.Chrome;
import io.github.selenium.javaDriverless.JavaDriverless;
import io.github.selenium.javaDriverless.types.Target;
import org.junit.jupiter.api.Test;

import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ExpectedConditionsJavaDriverlessTest {
    @Test
    void urlConditionsAcceptJavaDriverlessFacade() {
        JavaDriverless driver = mock(JavaDriverless.class);
        Chrome chrome = mock(Chrome.class);
        when(driver.getChrome()).thenReturn(chrome);
        when(chrome.getCurrentUrl()).thenReturn(
            CompletableFuture.completedFuture("https://example.test/dashboard")
        );

        assertTrue(ExpectedConditions.urlContains("dashboard").apply(driver));
    }

    @Test
    void titleConditionsAcceptJavaDriverlessFacade() {
        JavaDriverless driver = mock(JavaDriverless.class);
        Chrome chrome = mock(Chrome.class);
        Target target = mock(Target.class);
        when(driver.getChrome()).thenReturn(chrome);
        when(chrome.getCurrentTarget()).thenReturn(CompletableFuture.completedFuture(target));
        when(target.getTitle()).thenReturn(CompletableFuture.completedFuture("Minha Pagina"));

        assertTrue(ExpectedConditions.titleContains("Pagina").apply(driver));
    }
}
