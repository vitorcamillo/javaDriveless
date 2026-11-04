package io.github.selenium.javaDriverless;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

import com.fasterxml.jackson.databind.node.NullNode;

import io.github.selenium.javaDriverless.types.Target;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** Fachada bloqueante de cookies/browser/janela: delegação ao Chrome + erros com contexto. */
class JavaDriverlessOperationsTest {

    private Chrome chrome;
    private Target target;

    @BeforeEach
    void setUp() {
        chrome = mock(Chrome.class);
        target = mock(Target.class);
        when(chrome.getCurrentTarget()).thenReturn(CompletableFuture.completedFuture(target));
    }

    // probe concreto para a fachada de janela (abstract, campo protected no mesmo pacote)
    private static final class WindowOpsProbe extends JavaDriverlessWindowOperations {
        void attach(Chrome chrome) {
            this.chrome = chrome;
        }

        Map<String, Object> readWindowSize() {
            return getWindowSize();
        }

        Map<String, Object> readWindowPosition() {
            return getWindowPosition();
        }

        List<Map<String, Object>> readCookies() {
            return getCookies();
        }

        Map<String, Object> readCookie(String name) {
            return getCookie(name);
        }
    }

    @Test
    void windowFacadeReadsDelegateToChromeFutures() {
        WindowOpsProbe probe = new WindowOpsProbe();
        probe.attach(chrome);
        when(chrome.getWindowSize())
            .thenReturn(CompletableFuture.completedFuture(Map.of("width", 640, "height", 480)));
        when(chrome.getWindowPosition())
            .thenReturn(CompletableFuture.completedFuture(Map.of("x", 1, "y", 2)));

        Map<String, Object> size = probe.readWindowSize();
        assertEquals(640, size.get("width"));
        assertEquals(480, size.get("height"));

        Map<String, Object> position = probe.readWindowPosition();
        assertEquals(1, position.get("x"));
        assertEquals(2, position.get("y"));
    }

    @Test
    void windowFacadeReadFailureCarriesContextualMessage() {
        WindowOpsProbe probe = new WindowOpsProbe();
        probe.attach(chrome);
        when(chrome.getWindowSize())
            .thenReturn(CompletableFuture.failedFuture(new IllegalStateException("no window")));

        RuntimeException thrown = assertThrows(RuntimeException.class, probe::readWindowSize);
        assertEquals("Erro ao obter tamanho da janela", thrown.getMessage());
    }

    @Test
    void getCookiesDelegatesToChrome() {
        when(chrome.getCookies())
            .thenReturn(CompletableFuture.completedFuture(List.of(Map.of("name", "a"))));

        assertEquals(1, JavaDriverlessCookieOperations.getCookies(chrome).size());
    }

    @Test
    void getCookieFiltersByNameAndReturnsNullWhenAbsent() {
        when(chrome.getCookies()).thenReturn(CompletableFuture.completedFuture(List.of(
            Map.of("name", "session", "value", "s"),
            Map.of("name", "theme", "value", "dark", "domain", ".x.test"))));

        assertEquals("s", JavaDriverlessCookieOperations.getCookie(chrome, "session").get("value"));
        assertNull(JavaDriverlessCookieOperations.getCookie(chrome, "missing"));
    }

    @Test
    void deleteCookieSkipsCdpWhenCookieDoesNotExist() {
        when(chrome.getCookies()).thenReturn(CompletableFuture.completedFuture(List.of()));

        JavaDriverlessCookieOperations.deleteCookie(chrome, "ghost");

        verify(target, never()).executeCdpCmd(any(), anyMap(), any());
    }

    @Test
    void deleteCookieSendsCdpDeleteWithDomainWhenKnown() {
        when(chrome.getCookies()).thenReturn(CompletableFuture.completedFuture(
            List.of(Map.of("name", "session", "domain", ".x.test"))));
        when(target.executeCdpCmd(eq("Network.deleteCookies"), anyMap(), any()))
            .thenReturn(CompletableFuture.completedFuture(NullNode.getInstance()));

        JavaDriverlessCookieOperations.deleteCookie(chrome, "session");

        verify(target).executeCdpCmd(eq("Network.deleteCookies"), argThat(args ->
            "session".equals(args.get("name"))
                && ".x.test".equals(args.get("domain"))), any());
    }

    @Test
    void cookieFailuresWrapIntoRuntimeExceptionsWithContext() {
        when(chrome.getCookies())
            .thenReturn(CompletableFuture.failedFuture(new IllegalStateException("down")));

        RuntimeException thrown = assertThrows(RuntimeException.class,
            () -> JavaDriverlessCookieOperations.getCookies(chrome));
        assertEquals("Erro ao obter cookies", thrown.getMessage());
    }

    @Test
    void browserOperationsDelegateToChromeFutures() {
        when(chrome.sleep(eq(0.01))).thenReturn(CompletableFuture.completedFuture(null));
        when(chrome.back()).thenReturn(CompletableFuture.completedFuture(null));
        when(chrome.forward()).thenReturn(CompletableFuture.completedFuture(null));
        when(chrome.refresh()).thenReturn(CompletableFuture.completedFuture(null));
        when(chrome.maximizeWindow()).thenReturn(CompletableFuture.completedFuture(null));
        when(chrome.minimizeWindow()).thenReturn(CompletableFuture.completedFuture(null));
        when(chrome.fullscreenWindow()).thenReturn(CompletableFuture.completedFuture(null));
        when(chrome.getScreenshotAsFile(eq("/tmp/shot.png")))
            .thenReturn(CompletableFuture.completedFuture(null));

        JavaDriverlessBrowserOperations.sleep(chrome, 0.01);
        JavaDriverlessBrowserOperations.back(chrome);
        JavaDriverlessBrowserOperations.forward(chrome);
        JavaDriverlessBrowserOperations.refresh(chrome);
        JavaDriverlessBrowserOperations.screenshot(chrome, "/tmp/shot.png");
        JavaDriverlessBrowserOperations.maximize(chrome);
        JavaDriverlessBrowserOperations.minimize(chrome);
        JavaDriverlessBrowserOperations.fullscreen(chrome);

        verify(chrome).back();
        verify(chrome).forward();
        verify(chrome).refresh();
        verify(chrome).getScreenshotAsFile("/tmp/shot.png");
        verify(chrome).maximizeWindow();
        verify(chrome).minimizeWindow();
        verify(chrome).fullscreenWindow();
    }

    @Test
    void browserOperationFailureCarriesContextualMessage() {
        when(chrome.back())
            .thenReturn(CompletableFuture.failedFuture(new IllegalStateException("nav")));

        RuntimeException thrown = assertThrows(RuntimeException.class,
            () -> JavaDriverlessBrowserOperations.back(chrome));
        assertEquals("Erro ao voltar", thrown.getMessage());
    }
}
