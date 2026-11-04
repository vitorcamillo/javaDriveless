package io.github.selenium.javaDriverless.types;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

import com.fasterxml.jackson.databind.node.NullNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import io.github.selenium.javaDriverless.Chrome;
import io.github.selenium.javaDriverless.input.Pointer;

/** Fachada de contexto (guia/incognito): delegação ao target corrente + limites de navegação. */
class ContextTest {

    private static final com.fasterxml.jackson.databind.ObjectMapper MAPPER =
        new com.fasterxml.jackson.databind.ObjectMapper();

    private Target target;
    private BaseTarget baseTarget;
    private Chrome driver;
    private Context context;

    @BeforeEach
    void setUp() {
        target = mock(Target.class);
        baseTarget = mock(BaseTarget.class);
        driver = mock(Chrome.class);
        when(driver.getHost()).thenReturn("127.0.0.1");
        when(driver.getBaseTarget()).thenReturn(CompletableFuture.completedFuture(baseTarget));

        context = new Context(target, driver, "ctx-1", false, 10);
    }

    private static ObjectNode json(String json) {
        try {
            return (ObjectNode) MAPPER.readTree(json);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    @Test
    void startSessionIsIdempotentAndEnablesSwitchTo() {
        Context first = context.startSession().join();
        Context second = context.startSession().join();

        assertEquals(context, first);
        assertEquals(context, second);
        assertInstanceOf(io.github.selenium.javaDriverless.scripts.SwitchTo.class, context.switchTo());
    }

    @Test
    void getCurrentTargetAndPointerDelegate() {
        Pointer pointer = mock(Pointer.class);
        when(target.getPointer()).thenReturn(pointer);

        assertEquals(target, context.getCurrentTarget());
        assertEquals(pointer, context.getCurrentPointer());
        assertEquals(baseTarget, context.getBaseTarget());
    }

    @Test
    void incognitoContextRejectsChromeExtensionsUrl() {
        Context incognito = new Context(target, driver, "ctx-2", true, 10);

        CompletableFuture<Map<String, Object>> future =
            incognito.get("chrome://extensions", null, true, 1f);

        Exception thrown = assertThrows(Exception.class, () -> future.join());
        assertInstanceOf(IllegalArgumentException.class, thrown.getCause());
        assertTrue(thrown.getCause().getMessage().contains("chrome://extensions"));
    }

    @Test
    void regularContextNavigatesThroughTarget() {
        Map<String, Object> nav = Map.of("url", "https://example.test/");
        when(target.get(eq("https://example.test/"), isNull(), eq(true), eq(30.0f)))
            .thenReturn(CompletableFuture.completedFuture(nav));

        assertEquals(nav, context.get("https://example.test/", true).join());
    }

    @Test
    void pageAccessorsDelegateToCurrentTarget() {
        when(target.getTitle()).thenReturn(CompletableFuture.completedFuture("Title"));
        when(target.getCurrentUrl()).thenReturn(CompletableFuture.completedFuture("https://x.test/"));
        when(target.getPageSource()).thenReturn(CompletableFuture.completedFuture("<html/>"));

        assertEquals("Title", context.getTitle().join());
        assertEquals("https://x.test/", context.getCurrentUrl().join());
        assertEquals("<html/>", context.getPageSource().join());
    }

    @Test
    void scriptAndSearchMethodsDelegateToCurrentTarget() {
        when(target.executeScript(eq("return 1"), any(), eq(false)))
            .thenReturn(CompletableFuture.completedFuture("v"));
        when(target.findElement(eq("id"), eq("x"), eq(5.0f)))
            .thenReturn(CompletableFuture.completedFuture(mock(WebElement.class)));
        when(target.findElements(eq("css selector"), eq("div"), eq(5.0f)))
            .thenReturn(CompletableFuture.completedFuture(List.of()));
        when(target.searchElements(eq("//a")))
            .thenReturn(CompletableFuture.completedFuture(List.of()));

        assertEquals("v", context.executeScript("return 1", null, false).join());
        assertInstanceOf(WebElement.class, context.findElement("id", "x", 5f).join());
        assertEquals(List.of(), context.findElements("css selector", "div", 5f).join());
        assertEquals(List.of(), context.searchElements("//a").join());
    }

    @Test
    void cookieOperationsGoThroughCdpCommands() {
        when(target.executeCdpCmd(eq("Network.getCookies"), isNull(), isNull()))
            .thenReturn(CompletableFuture.completedFuture(
                json("{\"cookies\":[{\"name\":\"k\",\"value\":\"v\"}]}")));
        // Storage.setCookies e comando de browser: sai pelo baseTarget do processo
        when(baseTarget.executeCdpCmd(eq("Storage.setCookies"), anyMap(), isNull()))
            .thenReturn(CompletableFuture.completedFuture(NullNode.getInstance()));
        when(target.executeCdpCmd(eq("Network.deleteCookies"), anyMap(), isNull()))
            .thenReturn(CompletableFuture.completedFuture(NullNode.getInstance()));
        when(target.executeCdpCmd(eq("Network.clearBrowserCookies"), isNull(), isNull()))
            .thenReturn(CompletableFuture.completedFuture(NullNode.getInstance()));

        List<Map<String, Object>> cookies = context.getCookies().join();
        assertEquals(1, cookies.size());
        assertEquals("v", context.getCookie("k").join().get("value"));
        assertNull(context.getCookie("zz").join());

        context.addCookie(Map.of("name", "k2", "value", "v2")).join();
        verify(baseTarget).executeCdpCmd(eq("Storage.setCookies"), argThat(args -> {
            Object cookiesArg = args.get("cookies");
            return cookiesArg instanceof List && ((List<?>) cookiesArg).size() == 1;
        }), isNull());

        context.deleteCookie("k");
        context.deleteAllCookies();
        verify(target).executeCdpCmd(eq("Network.deleteCookies"), argThat(args ->
            "k".equals(args.get("name"))), isNull());
        verify(target).executeCdpCmd(eq("Network.clearBrowserCookies"), isNull(), isNull());
    }

    @Test
    void navigationHistoryDelegatesToCurrentTarget() {
        when(target.back()).thenReturn(CompletableFuture.completedFuture(null));
        when(target.forward()).thenReturn(CompletableFuture.completedFuture(null));
        when(target.refresh(eq(true))).thenReturn(CompletableFuture.completedFuture(null));

        context.back().join();
        context.forward().join();
        context.refresh(true).join();

        verify(target).back();
        verify(target).forward();
        verify(target).refresh(true);
    }

    @Test
    void windowBoundsUseBaseTargetWithCurrentWindowId() {
        when(target.getWindowId()).thenReturn(CompletableFuture.completedFuture(7));
        when(baseTarget.executeCdpCmd(eq("Browser.setWindowBounds"), anyMap(), isNull()))
            .thenReturn(CompletableFuture.completedFuture(NullNode.getInstance()));
        when(baseTarget.executeCdpCmd(eq("Browser.getWindowBounds"), anyMap(), isNull()))
            .thenReturn(CompletableFuture.completedFuture(
                json("{\"bounds\":{\"left\":10,\"top\":20,\"width\":800,\"height\":600}}")));

        context.maximizeWindow().join();
        verify(baseTarget).executeCdpCmd(eq("Browser.setWindowBounds"), argThat(args ->
            Integer.valueOf(7).equals(args.get("windowId"))
                && "maximized".equals(((Map<?, ?>) args.get("bounds")).get("windowState"))), isNull());

        context.minimizeWindow().join();
        context.fullscreenWindow().join();
        context.normalizeWindow().join();
        context.setWindowState("normal").join();
        verify(baseTarget, times(5)).executeCdpCmd(eq("Browser.setWindowBounds"), anyMap(), isNull());

        Map<String, Object> rect = context.getWindowRect().join();
        assertEquals(10, rect.get("x"));
        assertEquals(20, rect.get("y"));
        assertEquals(800, rect.get("width"));
        assertEquals(600, rect.get("height"));

        Map<String, Object> position = context.getWindowPosition().join();
        assertEquals(10, position.get("x"));
        assertEquals(20, position.get("y"));
    }

    @Test
    void windowSizeCombinesPositionAndRect() {
        when(target.getWindowId()).thenReturn(CompletableFuture.completedFuture(7));
        when(baseTarget.executeCdpCmd(eq("Browser.getWindowBounds"), anyMap(), isNull()))
            .thenReturn(CompletableFuture.completedFuture(
                json("{\"bounds\":{\"left\":5,\"top\":6,\"width\":640,\"height\":480}}")));
        when(baseTarget.executeCdpCmd(eq("Browser.setWindowBounds"), anyMap(), isNull()))
            .thenReturn(CompletableFuture.completedFuture(NullNode.getInstance()));

        Map<String, Object> size = context.getWindowSize().join();
        assertEquals(640, size.get("width"));
        assertEquals(480, size.get("height"));

        context.setWindowSize(1024, 768).join();
        verify(baseTarget).executeCdpCmd(eq("Browser.setWindowBounds"), argThat(args -> {
            Map<?, ?> bounds = (Map<?, ?>) args.get("bounds");
            return Integer.valueOf(1024).equals(bounds.get("width"))
                && Integer.valueOf(768).equals(bounds.get("height"))
                && Integer.valueOf(5).equals(bounds.get("left"));
        }), isNull());

        context.setWindowPosition(1, 2).join();
        verify(baseTarget).executeCdpCmd(eq("Browser.setWindowBounds"), argThat(args -> {
            Map<?, ?> bounds = (Map<?, ?>) args.get("bounds");
            return Integer.valueOf(1).equals(bounds.get("left"))
                && Integer.valueOf(2).equals(bounds.get("top"));
        }), isNull());
    }

    @Test
    void setWindowRectSendsFullBounds() {
        when(target.getWindowId()).thenReturn(CompletableFuture.completedFuture(9));
        when(baseTarget.executeCdpCmd(eq("Browser.setWindowBounds"), anyMap(), isNull()))
            .thenReturn(CompletableFuture.completedFuture(NullNode.getInstance()));

        context.setWindowRect(1, 2, 300, 200).join();

        verify(baseTarget).executeCdpCmd(eq("Browser.setWindowBounds"), argThat(args -> {
            Map<?, ?> bounds = (Map<?, ?>) args.get("bounds");
            return Integer.valueOf(1).equals(bounds.get("left"))
                && Integer.valueOf(2).equals(bounds.get("top"))
                && Integer.valueOf(300).equals(bounds.get("width"))
                && Integer.valueOf(200).equals(bounds.get("height"));
        }), isNull());
    }

    @Test
    void focusDelegatesAndWindowHandlesComeFromContextTargets() {
        when(target.focus()).thenReturn(CompletableFuture.completedFuture(null));
        when(target.getId()).thenReturn("cur-target");

        context.focus().join();
        assertEquals(List.of(), context.getWindowHandles().join());
        assertEquals("cur-target", context.getCurrentWindowHandle());
        verify(target).focus();
    }

    @Test
    void networkAndMediaDelegatesPassThrough() {
        Map<String, Object> conditions = Map.of("offline", false);
        when(target.getNetworkConditions())
            .thenReturn(CompletableFuture.completedFuture(conditions));
        when(target.deleteNetworkConditions()).thenReturn(CompletableFuture.completedFuture(null));
        when(target.waitDownload(eq(5.0f)))
            .thenReturn(CompletableFuture.completedFuture(conditions));
        when(target.getSinks()).thenReturn(CompletableFuture.completedFuture(List.of()));
        when(target.getIssueMessage())
            .thenReturn(CompletableFuture.completedFuture("issue"));
        when(target.printPage()).thenReturn(CompletableFuture.completedFuture("pdf-b64"));

        assertEquals(conditions, context.getNetworkConditions().join());
        context.deleteNetworkConditions().join();
        assertEquals(conditions, context.waitDownload(5f).join());
        assertEquals(List.of(), context.getSinks().join());
        assertEquals("issue", context.getIssueMessage().join());
        assertEquals("pdf-b64", context.printPage().join());
    }

    @Test
    void fetchAndXhrDelegateWithArguments() {
        Map<String, Object> response = new HashMap<>();
        response.put("status", 200);
        when(target.fetch(eq("https://api.test/"), eq("GET"), isNull(), isNull(), eq(10.0f)))
            .thenReturn(CompletableFuture.completedFuture(response));
        when(target.xhr(eq("https://api.test/"), eq("POST"), any(), eq("body"), eq(5.0f)))
            .thenReturn(CompletableFuture.completedFuture(response));

        assertEquals(response, context.fetch("https://api.test/", "GET", null, null, 10f).join());
        assertEquals(response,
            context.xhr("https://api.test/", "POST", Map.of("h", "v"), "body", 5f).join());
    }

    @Test
    void rawAndAsyncScriptDelegate() {
        when(target.executeRawScript(eq("s"), eq(true), isNull(), any(Object[].class)))
            .thenReturn(CompletableFuture.completedFuture("raw"));
        when(target.executeAsyncScript(eq("s"), eq(2), isNull(), any(Object[].class)))
            .thenReturn(CompletableFuture.completedFuture("async"));
        when(target.evalAsync(eq("s"), eq(3.0f), eq(false), any(Object[].class)))
            .thenReturn(CompletableFuture.completedFuture("eval"));

        assertEquals("raw", context.executeRawScript("s", true, (String) null).join());
        assertEquals("async", context.executeAsyncScript("s", 2, (String) null).join());
        assertEquals("eval", context.evalAsync("s", 3f, false).join());
    }

    @Test
    void sleepCompletesAfterRequestedDelay() {
        long start = System.nanoTime();
        context.sleep(0.05).join();
        long elapsedMs = (System.nanoTime() - start) / 1_000_000;
        assertTrue(elapsedMs >= 40, "elapsed=" + elapsedMs);
    }

    @Test
    void cdpListenerRegistrationDelegates() {
        java.util.function.Consumer<com.fasterxml.jackson.databind.JsonNode> cb = node -> { };
        when(target.addCdpListener(eq("Page.loadEventFired"), any()))
            .thenReturn(CompletableFuture.completedFuture(null));
        when(target.removeCdpListener(eq("Page.loadEventFired"), any()))
            .thenReturn(CompletableFuture.completedFuture(null));
        when(target.getCdpEventIter(eq("Page.loadEventFired")))
            .thenReturn(CompletableFuture.completedFuture(List.of()));

        context.addCdpListener("Page.loadEventFired", cb).join();
        context.removeCdpListener("Page.loadEventFired", cb).join();
        assertEquals(List.of(), context.getCdpEventIter("Page.loadEventFired").join());
    }

    @Test
    void targetManagementUsesContextStateAndDelegatesFrameQueries() {
        when(target.getFrameTree()).thenReturn(CompletableFuture.completedFuture(Map.of()));
        when(target.getTargetForIframe(any()))
            .thenReturn(CompletableFuture.completedFuture(target));

        assertEquals(List.of(), context.getTargets().join());

        Exception missing = assertThrows(Exception.class, () -> context.getTarget("nope").join());
        assertTrue(missing.getCause().getMessage().contains("não encontrado"));

        assertEquals(Map.of(), context.getFrameTree().join());
        assertEquals(target, context.getTargetForIframe(mock(WebElement.class)).join());
    }

    @Test
    void removeTargetClosesTargetEvenWhenNotRegistered() {
        Target extra = mock(Target.class);
        when(extra.getId()).thenReturn("extra");
        when(extra.close()).thenReturn(CompletableFuture.completedFuture(null));

        context.removeTarget(extra).join();

        verify(extra).close();
    }

    @Test
    void closeCompletesQuietlyWithNoExtraTargetsOrCallbacks() {
        context.startSession().join();

        // sem targets extras nem callbacks registrados, close() apenas completa
        context.close();

        assertEquals("ctx-1", context.getContextId());
        assertFalse(context.isIncognito());
    }
}
