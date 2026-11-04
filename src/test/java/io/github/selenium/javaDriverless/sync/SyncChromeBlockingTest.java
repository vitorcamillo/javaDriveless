package io.github.selenium.javaDriverless.sync;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.anyFloat;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import io.github.selenium.javaDriverless.Chrome;
import io.github.selenium.javaDriverless.types.Target;
import io.github.selenium.javaDriverless.types.WebElement;

/**
 * SyncChrome bloqueante sobre um Chrome mockado (via construtor package-private):
 * cada método público deve fazer join do futuro correspondente.
 */
class SyncChromeBlockingTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private Chrome async;
    private Target target;
    private SyncChrome sync;

    @BeforeEach
    void setUp() {
        async = mock(Chrome.class);
        target = mock(Target.class);
        when(async.getCurrentTarget()).thenReturn(CompletableFuture.completedFuture(target));
        sync = new SyncChrome(async);
    }

    private static JsonNode json(String raw) {
        try {
            return MAPPER.readTree(raw);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    @Test
    void constructorRejectsNullChrome() {
        assertThrows(NullPointerException.class, () -> new SyncChrome((Chrome) null));
    }

    @Test
    void getCurrentTargetWrapsIntoSyncTarget() {
        assertInstanceOf(SyncTarget.class, sync.getCurrentTarget());
    }

    @Test
    void navigationJoinsUnderlyingFuture() {
        when(async.get(eq("https://a.test/"), eq(true)))
            .thenReturn(CompletableFuture.completedFuture(Map.of("ok", true)));
        when(async.back()).thenReturn(CompletableFuture.completedFuture(null));
        when(async.forward()).thenReturn(CompletableFuture.completedFuture(null));
        when(async.refresh(eq(true))).thenReturn(CompletableFuture.completedFuture(null));

        assertTrue(sync.get("https://a.test/", true).containsKey("ok"));
        sync.get("https://b.test/");
        sync.back();
        sync.forward();
        sync.refresh(true);
        sync.refresh();

        verify(async).get("https://b.test/", true);
        verify(async).back();
        verify(async).forward();
        verify(async).refresh(true);
        verify(async).refresh(false);
    }

    @Test
    void pageQueriesJoinUnderlyingFutures() {
        when(async.getTitle()).thenReturn(CompletableFuture.completedFuture("titulo"));
        when(async.getCurrentUrl()).thenReturn(CompletableFuture.completedFuture("https://u.test/"));
        when(async.getPageSource()).thenReturn(CompletableFuture.completedFuture("<html/>"));
        when(async.executeScript(eq("return 1"), any(), eq(false)))
            .thenReturn(CompletableFuture.completedFuture(1));
        when(async.executeScript(eq("return 1"), any(), eq(true)))
            .thenReturn(CompletableFuture.completedFuture(10));

        assertEquals("titulo", sync.getTitle());
        assertEquals("https://u.test/", sync.getCurrentUrl());
        assertEquals("<html/>", sync.getPageSource());
        assertEquals(1, sync.executeScript("return 1"));
        assertEquals(10, sync.executeScript("return 1", new Object[]{"x"}, true));
        verify(async).executeScript("return 1", new Object[]{"x"}, true);
    }

    @Test
    void waitForCdpJoinsUnderlyingFuture() {
        JsonNode event = json("{\"type\":\"Page.loadEventFired\"}");
        when(async.waitForCdp(eq("Page.loadEventFired"), eq(3.0f)))
            .thenReturn(CompletableFuture.completedFuture(event));

        assertSame(event, sync.waitForCdp("Page.loadEventFired", 3.0f));
    }

    @Test
    void findElementWrapsIntoSyncWebElement() {
        WebElement element = mock(WebElement.class);
        when(async.findElement(eq("css selector"), eq("#q"), eq(5.0f)))
            .thenReturn(CompletableFuture.completedFuture(element));

        assertInstanceOf(SyncWebElement.class, sync.findElement("css selector", "#q", 5f));
    }

    @Test
    void sleepJoinsUnderlyingFuture() {
        when(async.sleep(anyDouble())).thenReturn(CompletableFuture.completedFuture(null));

        sync.sleep(0.0);
        verify(async).sleep(0.0);
    }

    @Test
    void permissionsAndGeolocationDelegateToChrome() {
        when(async.setPermissions(eq("https://x.test/"), anyList()))
            .thenReturn(CompletableFuture.completedFuture(null));
        when(async.setPermission(eq("https://x.test/"), eq("geolocation"), eq("granted")))
            .thenReturn(CompletableFuture.completedFuture(null));
        when(async.setGeolocationOverride(eq(-23.0), eq(-46.0), eq(null)))
            .thenReturn(CompletableFuture.completedFuture(null));

        sync.setPermissions("https://x.test/", List.of("geolocation"));
        sync.setPermission("https://x.test/", "geolocation", "granted");
        sync.setGeolocationOverride(-23.0, -46.0, null);

        verify(async).setPermissions("https://x.test/", List.of("geolocation"));
        verify(async).setPermission("https://x.test/", "geolocation", "granted");
        verify(async).setGeolocationOverride(-23.0, -46.0, null);
    }

    @Test
    void fingerprintRuntimeFailureSurfacesWithoutBlocking() {
        when(async.hasFingerprintRuntimeFailure()).thenReturn(true);
        when(async.getFingerprintRuntimeFailure())
            .thenReturn(Optional.of("WorkerSpoof falhou em Runtime.evaluate"));

        assertTrue(sync.hasFingerprintRuntimeFailure());
        assertEquals(Optional.of("WorkerSpoof falhou em Runtime.evaluate"),
            sync.getFingerprintRuntimeFailure());
    }

    @Test
    void cookieOperationsDelegateToChrome() {
        Map<String, Object> session = Map.of("name", "session", "value", "s");
        when(async.getCookies()).thenReturn(CompletableFuture.completedFuture(List.of(session)));
        when(async.getCookie(eq("session"))).thenReturn(CompletableFuture.completedFuture(session));
        when(async.getCookie(eq("ghost"))).thenReturn(CompletableFuture.completedFuture(null));
        when(async.addCookie(anyMap())).thenReturn(CompletableFuture.completedFuture(null));
        when(async.deleteCookie(eq("session"))).thenReturn(CompletableFuture.completedFuture(null));
        when(async.deleteAllCookies()).thenReturn(CompletableFuture.completedFuture(null));

        assertEquals(1, sync.getCookies().size());
        assertEquals("s", sync.getCookie("session").get("value"));
        assertNull(sync.getCookie("ghost"));
        sync.addCookie(Map.of("name", "n", "value", "v"));
        sync.deleteCookie("session");
        sync.deleteAllCookies();

        verify(async).addCookie(Map.of("name", "n", "value", "v"));
        verify(async).deleteCookie("session");
        verify(async).deleteAllCookies();
    }

    @Test
    void screenshotsAndTypingDelegateToChrome() {
        byte[] png = new byte[]{(byte) 0x89, 'P', 'N', 'G'};
        when(async.getScreenshotAsPng()).thenReturn(CompletableFuture.completedFuture(png));
        when(async.getScreenshotAsFile(eq("/tmp/shot.png")))
            .thenReturn(CompletableFuture.completedFuture(null));
        when(async.sendKeys(eq("texto"))).thenReturn(CompletableFuture.completedFuture(null));

        assertArrayEquals(png, sync.getScreenshotAsPng());
        sync.getScreenshotAsFile("/tmp/shot.png");
        sync.sendKeys("texto");

        verify(async).getScreenshotAsFile("/tmp/shot.png");
        verify(async).sendKeys("texto");
    }

    @Test
    void quitAndCloseJoinUnderlyingLifecycle() {
        when(async.quit(eq(true))).thenReturn(CompletableFuture.completedFuture(null));
        when(async.quit(eq(false))).thenReturn(CompletableFuture.completedFuture(null));
        when(async.quit()).thenReturn(CompletableFuture.completedFuture(null));

        sync.quit(false);
        sync.quit(true);
        sync.quit();
        sync.close();

        verify(async).quit(false);
        verify(async).quit(true);
        verify(async, org.mockito.Mockito.times(2)).quit();
    }

    @Test
    void asyncFailurePropagatesAsCompletionExceptionWithCause() {
        when(async.getTitle())
            .thenReturn(CompletableFuture.failedFuture(new IllegalStateException("sessão morta")));

        RuntimeException thrown = assertThrows(RuntimeException.class, sync::getTitle);
        assertEquals("sessão morta", thrown.getCause().getMessage());
    }

    @Test
    void optionalFailureStatesAreExposedEmptyWhenHealthy() {
        when(async.hasFingerprintRuntimeFailure()).thenReturn(false);
        when(async.getFingerprintRuntimeFailure()).thenReturn(Optional.empty());

        assertFalse(sync.hasFingerprintRuntimeFailure());
        assertEquals(Optional.empty(), sync.getFingerprintRuntimeFailure());
    }
}
