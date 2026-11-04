package io.github.selenium.javaDriverless;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
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

import io.github.selenium.javaDriverless.types.By;
import io.github.selenium.javaDriverless.types.Target;
import io.github.selenium.javaDriverless.types.WebElement;

/**
 * Complementa JavaDriverlessNavigationOperationsTest: findElement(s) com By,
 * erros contextuais, timeouts configuráveis e ações encadeadas.
 */
class JavaDriverlessNavigationOperationsCoverageTest {

    private Chrome chrome;
    private Target currentTarget;
    private Probe driver;

    /** probe concreto: o campo protected `chrome` vem da hierarquia real. */
    private static final class Probe extends JavaDriverlessNavigationOperations {
        void attach(Chrome chrome) {
            this.chrome = chrome;
        }

        long currentImplicitWaitMillis() {
            return implicitWaitMillis;
        }

        long currentScriptTimeoutMillis() {
            return scriptTimeoutMillis;
        }

        long currentPageLoadTimeoutMillis() {
            return pageLoadTimeoutMillis;
        }

        void quietly(Runnable op) {
            try {
                op.run();
            } catch (RuntimeException e) {
                // o cast final (JavaDriverless) this falha no probe — a delegação
                // ao Chrome já aconteceu, e é ela que estamos provando.
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
    void findElementAcceptsByLocatorAndDelegatesWithDefaultTimeout() {
        WebElement element = mock(WebElement.class);
        when(chrome.findElement(eq("css selector"), eq("#login"), eq(10.0f)))
            .thenReturn(CompletableFuture.completedFuture(element));

        // o stub casar significa que a chamada veio com strategy/value/timeout padrão
        assertEquals(element, driver.findElement(By.cssSelector("#login")));
    }

    @Test
    void findElementsAcceptsByLocatorAndDelegatesWithDefaultTimeout() {
        when(chrome.findElements(eq("xpath"), eq("//div"), eq(10.0f)))
            .thenReturn(CompletableFuture.completedFuture(
                List.of(mock(WebElement.class), mock(WebElement.class))));

        assertEquals(2, driver.findElements(By.xpath("//div")).size());
    }

    @Test
    void findFailuresCarryLocatorInContextualMessage() {
        when(chrome.findElement(eq("id"), eq("fantasma"), eq(10.0f)))
            .thenReturn(CompletableFuture.failedFuture(new IllegalStateException("sem elemento")));

        RuntimeException thrown = assertThrows(RuntimeException.class,
            () -> driver.findElement("id", "fantasma"));
        assertEquals("Erro ao buscar elemento: id=fantasma", thrown.getMessage());

        when(chrome.findElements(eq("id"), eq("fantasma"), eq(10.0f)))
            .thenReturn(CompletableFuture.failedFuture(new IllegalStateException("sem elementos")));

        RuntimeException thrownList = assertThrows(RuntimeException.class,
            () -> driver.findElements("id", "fantasma"));
        assertEquals("Erro ao buscar elementos: id=fantasma", thrownList.getMessage());
    }

    @Test
    void executeScriptBlocksAndAsyncVariantPassesThrough() {
        when(chrome.executeScript(eq("return 1"), org.mockito.ArgumentMatchers.isNull(), eq(true)))
            .thenReturn(CompletableFuture.completedFuture(1));
        when(chrome.executeScript(eq("return args[0]"),
                org.mockito.AdditionalMatchers.aryEq(new Object[]{5}), eq(false)))
            .thenReturn(CompletableFuture.completedFuture(5));

        assertEquals(1, driver.executeScript("return 1"));
        assertEquals(5, driver.executeScriptAsync("return args[0]", new Object[]{5}, false).join());
    }

    @Test
    void executeScriptFailureCarriesContextualMessage() {
        when(chrome.executeScript(any(), any(), org.mockito.ArgumentMatchers.anyBoolean()))
            .thenReturn(CompletableFuture.failedFuture(new IllegalStateException("js quebrou")));

        RuntimeException thrown = assertThrows(RuntimeException.class,
            () -> driver.executeScript("quebra"));
        assertEquals("Erro ao executar script", thrown.getMessage());
    }

    @Test
    void getBlocksAndWrapsNavigationErrors() {
        when(chrome.get(eq("https://ok.test/"), eq(true)))
            .thenReturn(CompletableFuture.completedFuture(Map.of()));
        // caminho feliz: a delegação acontece antes do cast final (JavaDriverless) this
        driver.quietly(() -> driver.get("https://ok.test/"));
        verify(chrome).get("https://ok.test/", true);

        when(chrome.get(eq("https://falha.test/"), eq(true)))
            .thenReturn(CompletableFuture.failedFuture(new IllegalStateException("timeout")));

        RuntimeException thrown = assertThrows(RuntimeException.class,
            () -> driver.get("https://falha.test/"));
        assertEquals("Erro ao navegar para: https://falha.test/", thrown.getMessage());
    }

    @Test
    void chainedActionsDelegateToChromeBeforeCast() {
        when(chrome.sleep(eq(0.01))).thenReturn(CompletableFuture.completedFuture(null));
        when(chrome.back()).thenReturn(CompletableFuture.completedFuture(null));
        when(chrome.forward()).thenReturn(CompletableFuture.completedFuture(null));
        when(chrome.refresh()).thenReturn(CompletableFuture.completedFuture(null));
        when(chrome.getScreenshotAsFile(eq("/tmp/s.png")))
            .thenReturn(CompletableFuture.completedFuture(null));
        when(chrome.maximizeWindow()).thenReturn(CompletableFuture.completedFuture(null));
        when(chrome.minimizeWindow()).thenReturn(CompletableFuture.completedFuture(null));
        when(chrome.fullscreenWindow()).thenReturn(CompletableFuture.completedFuture(null));
        when(chrome.sendKeys(eq("txt"))).thenReturn(CompletableFuture.completedFuture(null));

        driver.quietly(() -> driver.sleep(0.01));
        driver.quietly(() -> driver.back());
        driver.quietly(() -> driver.forward());
        driver.quietly(() -> driver.refresh());
        driver.quietly(() -> driver.screenshot("/tmp/s.png"));
        driver.quietly(() -> driver.maximize());
        driver.quietly(() -> driver.minimize());
        driver.quietly(() -> driver.fullscreen());
        driver.quietly(() -> driver.sendKeys("txt"));

        verify(chrome).sleep(0.01);
        verify(chrome).back();
        verify(chrome).forward();
        verify(chrome).refresh();
        verify(chrome).getScreenshotAsFile("/tmp/s.png");
        verify(chrome).maximizeWindow();
        verify(chrome).minimizeWindow();
        verify(chrome).fullscreenWindow();
        verify(chrome).sendKeys("txt");
    }

    @Test
    void sendKeysFailureCarriesContextualMessage() {
        when(chrome.sendKeys(eq("falha")))
            .thenReturn(CompletableFuture.failedFuture(new IllegalStateException("sem foco")));

        RuntimeException thrown = assertThrows(RuntimeException.class,
            () -> driver.sendKeys("falha"));
        assertEquals("Erro ao enviar teclas", thrown.getMessage());
    }

    @Test
    void timeoutsAreStoredAndReadable() {
        Probe probe = driver;

        probe.quietly(() -> probe.implicitlyWait(1500));
        probe.quietly(() -> probe.setScriptTimeout(3000));
        probe.quietly(() -> probe.setPageLoadTimeout(60_000));

        assertEquals(1500, probe.currentImplicitWaitMillis());
        assertEquals(3000, probe.currentScriptTimeoutMillis());
        assertEquals(60_000, probe.currentPageLoadTimeoutMillis());
    }

    @Test
    void titleUrlAndPageSourceBlockOnChrome() {
        when(chrome.getTitle()).thenReturn(CompletableFuture.completedFuture("Painel"));
        when(chrome.getCurrentUrl()).thenReturn(CompletableFuture.completedFuture("https://app.test/painel"));
        when(currentTarget.getPageSource()).thenReturn(CompletableFuture.completedFuture("<html/>"));

        assertEquals("Painel", driver.getTitle());
        assertEquals("https://app.test/painel", driver.getCurrentUrl());
        assertEquals("<html/>", driver.getPageSource());
    }

    @Test
    void newWindowAndWindowListingDelegateToChrome() {
        io.github.selenium.javaDriverless.types.Target nova =
            mock(io.github.selenium.javaDriverless.types.Target.class);
        when(chrome.newWindow(eq("tab"))).thenReturn(CompletableFuture.completedFuture(nova));
        when(chrome.getTargets()).thenReturn(CompletableFuture.completedFuture(List.of(currentTarget, nova)));

        assertEquals(nova, driver.newWindow("tab"));
        assertEquals(2, driver.getWindows().size());
    }

    @Test
    void keyboardAndPointerComeFromTheCurrentChromeSession() {
        io.github.selenium.javaDriverless.input.Keyboard keyboard =
            mock(io.github.selenium.javaDriverless.input.Keyboard.class);
        io.github.selenium.javaDriverless.input.Pointer pointer =
            mock(io.github.selenium.javaDriverless.input.Pointer.class);
        when(chrome.getCurrentKeyboard()).thenReturn(keyboard);
        when(chrome.getCurrentPointer()).thenReturn(pointer);

        assertEquals(keyboard, driver.getKeyboard());
        assertEquals(pointer, driver.getPointer());
    }
}
