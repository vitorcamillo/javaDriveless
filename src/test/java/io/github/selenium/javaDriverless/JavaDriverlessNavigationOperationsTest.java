package io.github.selenium.javaDriverless;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import io.github.selenium.javaDriverless.types.Target;
import io.github.selenium.javaDriverless.types.WebElement;

/** Fachada de navegação do JavaDriverless: bloqueante sobre o Chrome assíncrono. */
class JavaDriverlessNavigationOperationsTest {

    private Chrome chrome;
    private Target currentTarget;
    private Probe driver;

    /** probe concreto: o campo protected `chrome` vem da hierarquia real. */
    private static final class Probe extends JavaDriverlessNavigationOperations {
        void attach(Chrome chrome) {
            this.chrome = chrome;
        }

        void quietly(Runnable op) {
            try {
                op.run();
            } catch (RuntimeException e) {
                // métodos chainable retornam (JavaDriverless) this — o probe não é JavaDriverless;
                // a delegação ao Chrome já aconteceu antes do cast, que é o que estamos testando.
                if (!(e.getCause() instanceof ClassCastException
                        || e instanceof ClassCastException)) {
                    throw e;
                }
            }
        }
    }

    @BeforeEach
    void setUp() {
        chrome = mock(Chrome.class);
        currentTarget = mock(Target.class);
        when(chrome.getCurrentTarget()).thenReturn(CompletableFuture.completedFuture(currentTarget));
        driver = new Probe();
        driver.attach(chrome);
    }

    @Test
    void pageQueriesBlockOnChromeFutures() {
        when(chrome.getTitle()).thenReturn(CompletableFuture.completedFuture("Título"));
        when(chrome.getCurrentUrl()).thenReturn(CompletableFuture.completedFuture("https://x.test/"));
        // a fachada lê o page source do target corrente
        when(currentTarget.getPageSource()).thenReturn(CompletableFuture.completedFuture("<html/>"));

        assertEquals("Título", driver.getTitle());
        assertEquals("https://x.test/", driver.getCurrentUrl());
        assertEquals("<html/>", driver.getPageSource());
        assertEquals("Título", driver.getTitleAsync().join());
        assertEquals("https://x.test/", driver.getCurrentUrlAsync().join());
    }

    @Test
    void navigationAndActionsDelegateToChrome() {
        when(chrome.get(eq("https://x.test/"), eq(true)))
            .thenReturn(CompletableFuture.completedFuture(Map.of("url", "https://x.test/")));
        when(chrome.sleep(eq(0.01))).thenReturn(CompletableFuture.completedFuture(null));
        when(chrome.back()).thenReturn(CompletableFuture.completedFuture(null));
        when(chrome.forward()).thenReturn(CompletableFuture.completedFuture(null));
        when(chrome.refresh()).thenReturn(CompletableFuture.completedFuture(null));
        when(chrome.getScreenshotAsFile(eq("/tmp/x.png")))
            .thenReturn(CompletableFuture.completedFuture(null));
        when(chrome.maximizeWindow()).thenReturn(CompletableFuture.completedFuture(null));
        when(chrome.minimizeWindow()).thenReturn(CompletableFuture.completedFuture(null));
        when(chrome.fullscreenWindow()).thenReturn(CompletableFuture.completedFuture(null));

        assertEquals("https://x.test/", driver.get("https://x.test/", true).join().get("url"));
        driver.quietly(() -> driver.get("https://x.test/"));
        driver.quietly(() -> driver.sleep(0.01));
        driver.quietly(driver::back);
        driver.quietly(driver::forward);
        driver.quietly(driver::refresh);
        driver.quietly(() -> driver.screenshot("/tmp/x.png"));
        driver.quietly(driver::maximize);
        driver.quietly(driver::minimize);
        driver.quietly(driver::fullscreen);

        verify(chrome, org.mockito.Mockito.times(2)).get("https://x.test/", true);
        verify(chrome).sleep(0.01);
        verify(chrome).back();
        verify(chrome).forward();
        verify(chrome).refresh();
        verify(chrome).getScreenshotAsFile("/tmp/x.png");
        verify(chrome).maximizeWindow();
        verify(chrome).minimizeWindow();
        verify(chrome).fullscreenWindow();
    }

    @Test
    void elementSearchAndScriptDelegateToChrome() {
        WebElement element = mock(WebElement.class);
        when(chrome.findElement(eq("id"), eq("q"), eq(10.0f)))
            .thenReturn(CompletableFuture.completedFuture(element));
        when(chrome.findElements(eq("css selector"), eq("div"), eq(10.0f)))
            .thenReturn(CompletableFuture.completedFuture(List.of(element)));
        when(chrome.executeScript(eq("return 1"), any(), eq(true)))
            .thenReturn(CompletableFuture.completedFuture("v"));

        assertInstanceOf(WebElement.class, driver.findElement("id", "q"));
        assertEquals(1, driver.findElements("css selector", "div").size());
        assertEquals("v", driver.executeScript("return 1"));
        assertEquals("v", driver.executeScriptAsync("return 1", null, true).join());

        verify(chrome).findElement("id", "q", 10.0f);
        verify(chrome).findElements("css selector", "div", 10.0f);
        verify(chrome, org.mockito.Mockito.times(2)).executeScript(eq("return 1"), any(), eq(true));
    }

    @Test
    void pageSourceReadsFromCurrentTargetWhenChromeHasNoFacade() {
        when(currentTarget.getPageSource()).thenReturn(CompletableFuture.completedFuture("<html/>"));

        assertEquals("<html/>", driver.getPageSource());
        verify(currentTarget).getPageSource();
    }
}
