package io.github.selenium.javaDriverless;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.anyFloat;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import io.github.selenium.javaDriverless.input.Keyboard;
import io.github.selenium.javaDriverless.input.Pointer;
import io.github.selenium.javaDriverless.types.By;
import io.github.selenium.javaDriverless.types.Context;
import io.github.selenium.javaDriverless.types.Target;
import io.github.selenium.javaDriverless.types.WebElement;

/**
 * Fachada pública do JavaDriverless sobre um Chrome pronto (visão de contexto):
 * os bridges de reflexão delegam 1:1 ao Chrome, quit() fecha o navegador e
 * paraContexto() devolve uma visão presa ao contexto (pid -1).
 */
class JavaDriverlessFacadeTest {

    private Chrome chrome;
    private JavaDriverless driver;

    private JavaDriverless novoDriverComChromePronto(String nome, Chrome chromePronto) throws Exception {
        var construtor = JavaDriverless.class.getDeclaredConstructor(String.class, Chrome.class);
        construtor.setAccessible(true);
        return construtor.newInstance(nome, chromePronto);
    }

    @BeforeEach
    void setUp() throws Exception {
        chrome = mock(Chrome.class);
        Target target = mock(Target.class);
        when(chrome.getCurrentTarget()).thenReturn(CompletableFuture.completedFuture(target));
        when(chrome.quit()).thenReturn(CompletableFuture.completedFuture(null));
        when(chrome.removeContext(any())).thenReturn(CompletableFuture.completedFuture(null));
        when(chrome.getBrowserPid()).thenReturn(4242L);
        driver = novoDriverComChromePronto("bot-fachada", chrome);
    }

    @Test
    void contextViewExposesWindowNameAndPidSentinel() {
        assertEquals("bot-fachada", driver.getNomeJanela());
        assertFalse(driver.isProfileManagementEnabled());
        assertNull(driver.getProfileManager());
        assertSame(chrome, driver.getChrome());
        assertEquals(4242L, driver.getPid());
        assertTrue(driver.toString().contains("bot-fachada"));
        assertTrue(driver.toString().contains("4242"));
    }

    @Test
    void quitClosesTheChromeAndCloseDelegatesToQuit() {
        driver.quit();
        verify(chrome).quit();

        when(chrome.quit()).thenReturn(CompletableFuture.completedFuture(null));
        driver.close();
        verify(chrome, org.mockito.Mockito.times(2)).quit();
    }

    @Test
    void navigationAndScriptFacadeDelegatesToChrome() {
        when(chrome.get(eq("https://app.test/"), eq(true)))
            .thenReturn(CompletableFuture.completedFuture(Map.of()));
        when(chrome.get(eq("https://app.test/"), eq(false)))
            .thenReturn(CompletableFuture.completedFuture(Map.of()));
        when(chrome.getTitle()).thenReturn(CompletableFuture.completedFuture("Título"));
        when(chrome.getCurrentUrl()).thenReturn(CompletableFuture.completedFuture("https://app.test/"));
        when(chrome.executeScript(eq("return 7"), isNull(), eq(true)))
            .thenReturn(CompletableFuture.completedFuture(7));
        when(chrome.executeScript(eq("return 8"), any(), eq(false)))
            .thenReturn(CompletableFuture.completedFuture(8));
        when(chrome.sleep(eq(0.01))).thenReturn(CompletableFuture.completedFuture(null));
        when(chrome.back()).thenReturn(CompletableFuture.completedFuture(null));
        when(chrome.forward()).thenReturn(CompletableFuture.completedFuture(null));
        when(chrome.refresh()).thenReturn(CompletableFuture.completedFuture(null));
        when(chrome.getScreenshotAsFile(eq("/tmp/f.png")))
            .thenReturn(CompletableFuture.completedFuture(null));
        when(chrome.maximizeWindow()).thenReturn(CompletableFuture.completedFuture(null));
        when(chrome.minimizeWindow()).thenReturn(CompletableFuture.completedFuture(null));
        when(chrome.fullscreenWindow()).thenReturn(CompletableFuture.completedFuture(null));
        when(chrome.sendKeys(eq("texto"))).thenReturn(CompletableFuture.completedFuture(null));

        assertSame(driver, driver.get("https://app.test/"));
        driver.get("https://app.test/", false).join();
        assertEquals("Título", driver.getTitle());
        assertEquals("Título", driver.getTitleAsync().join());
        assertEquals("https://app.test/", driver.getCurrentUrl());
        assertEquals("https://app.test/", driver.getCurrentUrlAsync().join());
        assertEquals(7, driver.executeScript("return 7"));
        assertEquals(8, driver.executeScriptAsync("return 8", new Object[0], false).join());
        assertSame(driver, driver.sleep(0.01));
        assertSame(driver, driver.back());
        assertSame(driver, driver.forward());
        assertSame(driver, driver.refresh());
        assertEquals(driver, driver.screenshot("/tmp/f.png"));
        assertSame(driver, driver.maximize());
        assertSame(driver, driver.minimize());
        assertSame(driver, driver.fullscreen());
        assertSame(driver, driver.sendKeys("texto"));
    }

    @Test
    void elementFacadeDelegatesToChrome() {
        WebElement elemento = mock(WebElement.class);
        when(chrome.findElement(eq("css selector"), eq("#a"), anyFloat()))
            .thenReturn(CompletableFuture.completedFuture(elemento));
        when(chrome.findElements(eq("css selector"), eq(".b"), anyFloat()))
            .thenReturn(CompletableFuture.completedFuture(List.of(elemento)));

        assertEquals(elemento, driver.findElement(By.cssSelector("#a")));
        assertEquals(elemento, driver.findElement("css selector", "#a"));
        assertEquals(1, driver.findElements(By.cssSelector(".b")).size());
        assertEquals(1, driver.findElements("css selector", ".b").size());
    }

    @Test
    void windowAndTimeoutFacadeDelegatesToChrome() {
        when(chrome.getWindowSize()).thenReturn(CompletableFuture.completedFuture(Map.of("w", 800)));
        when(chrome.setWindowSize(anyInt(), anyInt())).thenReturn(CompletableFuture.completedFuture(null));
        when(chrome.getWindowPosition()).thenReturn(CompletableFuture.completedFuture(Map.of("x", 10)));
        when(chrome.setWindowPosition(anyInt(), anyInt())).thenReturn(CompletableFuture.completedFuture(null));
        when(chrome.sleep(anyDouble())).thenReturn(CompletableFuture.completedFuture(null));

        assertEquals(Map.of("w", 800), driver.getWindowSize());
        assertSame(driver, driver.setWindowSize(800, 600));
        assertEquals(Map.of("x", 10), driver.getWindowPosition());
        assertSame(driver, driver.setWindowPosition(10, 20));
        assertSame(driver, driver.implicitlyWait(1000));
        assertSame(driver, driver.setScriptTimeout(2000));
        assertSame(driver, driver.setPageLoadTimeout(3000));
        assertEquals(1000, driver.getImplicitWaitMillis());
    }

    @Test
    void cookieFacadeDelegatesToChrome() {
        Map<String, Object> cookie = Map.of("name", "sessao", "value", "abc");
        when(chrome.getCookies()).thenReturn(CompletableFuture.completedFuture(List.of(cookie)));
        when(chrome.addCookie(anyMap())).thenReturn(CompletableFuture.completedFuture(null));
        when(chrome.deleteAllCookies()).thenReturn(CompletableFuture.completedFuture(null));
        when(chrome.deleteCookie(eq("sessao"))).thenReturn(CompletableFuture.completedFuture(null));

        assertEquals(List.of(cookie), driver.getCookies());
        assertSame(driver, driver.addCookie(cookie));
        assertSame(driver, driver.deleteAllCookies());
        assertEquals(cookie, driver.getCookie("sessao"));
        assertSame(driver, driver.deleteCookie("sessao"));
    }

    @Test
    void windowAndInputHandleFacadeDelegatesToChrome() {
        Target janela = mock(Target.class);
        Keyboard teclado = mock(Keyboard.class);
        Pointer ponteiro = mock(Pointer.class);
        when(chrome.newWindow(eq("tab"))).thenReturn(CompletableFuture.completedFuture(janela));
        when(chrome.getTargets()).thenReturn(CompletableFuture.completedFuture(List.of(janela)));
        when(chrome.getCurrentKeyboard()).thenReturn(teclado);
        when(chrome.getCurrentPointer()).thenReturn(ponteiro);

        assertEquals(janela, driver.newWindow("tab"));
        assertEquals(List.of(janela), driver.getWindows());
        assertSame(teclado, driver.getKeyboard());
        assertSame(ponteiro, driver.getPointer());
    }

    @Test
    void paraContextoBuildsAnIsolatedViewThatOnlyDiscardsItsOwnContext() {
        Context contexto = mock(Context.class);
        Target pagina = mock(Target.class);
        when(contexto.getContextId()).thenReturn("ctx-9");

        JavaDriverless visao = JavaDriverless.paraContexto(driver, contexto, pagina);

        assertEquals("bot-fachada/ctx-9", visao.getNomeJanela());
        assertEquals(-1L, visao.getPid(), "visão de contexto nunca expõe o pid do processo dono");
        // a fachada do contexto é uma ChromeContextView presa ao chrome do dono
        ChromeContextView visaoDoContexto = assertInstanceOf(ChromeContextView.class, visao.getChrome());
        assertSame(chrome, visaoDoContexto.dono());

        visao.quit();
        verify(chrome).removeContext(contexto);
        verify(chrome, org.mockito.Mockito.never()).quit();
    }
}
