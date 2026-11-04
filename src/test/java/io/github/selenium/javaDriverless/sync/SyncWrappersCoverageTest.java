package io.github.selenium.javaDriverless.sync;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyFloat;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.junit.jupiter.api.Test;

import io.github.selenium.javaDriverless.input.Pointer;
import io.github.selenium.javaDriverless.scripts.SwitchTo;
import io.github.selenium.javaDriverless.types.Alert;
import io.github.selenium.javaDriverless.types.BaseTarget;
import io.github.selenium.javaDriverless.types.Context;
import io.github.selenium.javaDriverless.types.Target;
import io.github.selenium.javaDriverless.types.WebElement;

/**
 * Complementa SyncWrappersTest cobrindo os métodos restantes dos wrappers
 * síncronos: CDP direto, cookies, screenshots, snapshots e input físico.
 */
class SyncWrappersCoverageTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static JsonNode json(String raw) {
        try {
            return MAPPER.readTree(raw);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    @Test
    void syncTargetCdpAndSessionOperationsJoinUnderlyingFutures() {
        Target async = mock(Target.class);
        JsonNode cdpResult = json("{\"id\":1}");
        when(async.getUrlViaCDP()).thenReturn(CompletableFuture.completedFuture("https://cdp.test/"));
        when(async.getTitleViaCDP()).thenReturn(CompletableFuture.completedFuture("via cdp"));
        when(async.getPageSource()).thenReturn(CompletableFuture.completedFuture("<html/>"));
        when(async.executeScript(eq("return 2"), any(), eq(true)))
            .thenReturn(CompletableFuture.completedFuture(2));
        when(async.executeScript(eq("return 2"), any(), eq(false)))
            .thenReturn(CompletableFuture.completedFuture(20));
        when(async.waitForCdp(eq("Page.frameStartedLoading"), eq(7.0f)))
            .thenReturn(CompletableFuture.completedFuture(json("{}")));
        when(async.executeCdpCmd(eq("Page.enable"), any(), eq(null)))
            .thenReturn(CompletableFuture.completedFuture(cdpResult));
        when(async.sleep(eq(0.0))).thenReturn(CompletableFuture.completedFuture(null));
        when(async.getPointer()).thenReturn(mock(Pointer.class));
        when(async.getCookies()).thenReturn(CompletableFuture.completedFuture(List.of()));
        when(async.getCookie(eq("c"))).thenReturn(CompletableFuture.completedFuture(null));
        when(async.addCookie(any())).thenReturn(CompletableFuture.completedFuture(null));
        when(async.deleteCookie(eq("c"))).thenReturn(CompletableFuture.completedFuture(null));
        when(async.deleteAllCookies()).thenReturn(CompletableFuture.completedFuture(null));
        when(async.getScreenshotAsPng()).thenReturn(CompletableFuture.completedFuture(new byte[]{1}));
        when(async.getScreenshotAsFile(eq("/tmp/a.png"))).thenReturn(CompletableFuture.completedFuture(null));
        when(async.saveScreenshot(eq("/tmp/b.png"))).thenReturn(CompletableFuture.completedFuture(null));
        when(async.snapshot()).thenReturn(CompletableFuture.completedFuture("<html/>"));
        when(async.saveSnapshot(eq("/tmp/snap.html"))).thenReturn(CompletableFuture.completedFuture(null));
        when(async.sendKeys(eq("txt"))).thenReturn(CompletableFuture.completedFuture(null));
        when(async.focus()).thenReturn(CompletableFuture.completedFuture(null));
        when(async.activate()).thenReturn(CompletableFuture.completedFuture(null));
        when(async.setNetworkConditions(eq(true), eq(10), eq(20), eq(30), eq("offline")))
            .thenReturn(CompletableFuture.completedFuture(null));
        when(async.close()).thenReturn(CompletableFuture.completedFuture(null));

        SyncTarget sync = new SyncTarget(async);

        assertEquals("https://cdp.test/", sync.getUrlViaCdp());
        assertEquals("via cdp", sync.getTitleViaCdp());
        assertEquals("<html/>", sync.getPageSource());
        assertEquals(2, sync.executeScript("return 2", new Object[]{}, true));
        assertEquals(20, sync.executeScript("return 2"));
        sync.waitForCdp("Page.frameStartedLoading", 7f);
        assertEquals(1, sync.executeCdpCmd("Page.enable", Map.of(), null).get("id").asInt());
        sync.sleep(0.0);
        assertInstanceOf(Pointer.class, sync.getPointer());
        sync.getCookies();
        sync.getCookie("c");
        sync.addCookie(Map.of("name", "c"));
        sync.deleteCookie("c");
        sync.deleteAllCookies();
        sync.getScreenshotAsPng();
        sync.getScreenshotAsFile("/tmp/a.png");
        sync.saveScreenshot("/tmp/b.png");
        assertEquals("<html/>", sync.snapshot());
        sync.saveSnapshot("/tmp/snap.html");
        sync.sendKeys("txt");
        sync.focus();
        sync.activate();
        sync.setNetworkConditions(true, 10, 20, 30, "offline");
        sync.close();

        verify(async).executeScript("return 2", null, false);
        verify(async).setNetworkConditions(true, 10, 20, 30, "offline");
        verify(async).close();
    }

    @Test
    void syncTargetFindElementsMapsEveryAsyncElement() {
        Target async = mock(Target.class);
        WebElement a = mock(WebElement.class);
        WebElement b = mock(WebElement.class);
        when(async.findElements(eq("xpath"), eq("//div"), eq(2.0f)))
            .thenReturn(CompletableFuture.completedFuture(List.of(a, b)));
        when(async.findElement(eq("xpath"), eq("//p"), eq(2.0f)))
            .thenReturn(CompletableFuture.completedFuture(a));

        SyncTarget sync = new SyncTarget(async);

        assertEquals(2, sync.findElements("xpath", "//div", 2f).size());
        assertInstanceOf(SyncWebElement.class, sync.findElement("xpath", "//p", 2f));
    }

    @Test
    void syncContextCookieAndBrowserOperationsJoinUnderlyingFutures() {
        Context async = mock(Context.class);
        JsonNode cdpResult = json("{\"ok\":true}");
        when(async.get(eq("https://c.test/"), eq(false)))
            .thenReturn(CompletableFuture.completedFuture(Map.of("ok", true)));
        when(async.executeScript(eq("1+1"), any(), eq(false)))
            .thenReturn(CompletableFuture.completedFuture(2));
        when(async.findElement(eq("css selector"), eq("#x"), eq(1.0f)))
            .thenReturn(CompletableFuture.completedFuture(mock(WebElement.class)));
        when(async.findElements(eq("css selector"), eq(".y"), eq(1.0f)))
            .thenReturn(CompletableFuture.completedFuture(List.of(mock(WebElement.class))));
        when(async.getCookies()).thenReturn(CompletableFuture.completedFuture(List.of(Map.of("name", "k"))));
        when(async.getCookie(eq("k"))).thenReturn(CompletableFuture.completedFuture(Map.of("name", "k")));
        when(async.addCookie(any())).thenReturn(CompletableFuture.completedFuture(null));
        when(async.deleteCookie(eq("k"))).thenReturn(CompletableFuture.completedFuture(null));
        when(async.deleteAllCookies()).thenReturn(CompletableFuture.completedFuture(null));
        when(async.sleep(eq(0.0))).thenReturn(CompletableFuture.completedFuture(null));
        when(async.back()).thenReturn(CompletableFuture.completedFuture(null));
        when(async.forward()).thenReturn(CompletableFuture.completedFuture(null));
        when(async.refresh(eq(false))).thenReturn(CompletableFuture.completedFuture(null));
        when(async.refresh(eq(true))).thenReturn(CompletableFuture.completedFuture(null));
        when(async.quit()).thenReturn(CompletableFuture.completedFuture(null));
        when(async.getScreenshotAsPng()).thenReturn(CompletableFuture.completedFuture(new byte[]{9}));
        when(async.getScreenshotAsFile(eq("/tmp/c.png"))).thenReturn(CompletableFuture.completedFuture(null));
        when(async.saveScreenshot(eq("/tmp/d.png"))).thenReturn(CompletableFuture.completedFuture(null));
        when(async.snapshot()).thenReturn(CompletableFuture.completedFuture("<html/>"));
        when(async.saveSnapshot(eq("/tmp/c.html"))).thenReturn(CompletableFuture.completedFuture(null));
        when(async.setNetworkConditions(eq(false), eq(0), eq(-1), eq(-1), eq("wifi")))
            .thenReturn(CompletableFuture.completedFuture(null));
        when(async.sendKeys(eq("hi"))).thenReturn(CompletableFuture.completedFuture(null));

        SyncContext sync = new SyncContext(async);

        sync.get("https://c.test/");
        assertEquals(2, sync.executeScript("1+1", null, false));
        assertInstanceOf(SyncWebElement.class, sync.findElement("css selector", "#x", 1f));
        assertEquals(1, sync.findElements("css selector", ".y", 1f).size());
        assertEquals(1, sync.getCookies().size());
        assertEquals("k", sync.getCookie("k").get("name"));
        sync.addCookie(Map.of("name", "k"));
        sync.deleteCookie("k");
        sync.deleteAllCookies();
        sync.sleep(0.0);
        sync.back();
        sync.forward();
        sync.refresh(false);
        sync.refresh(true);
        sync.getScreenshotAsPng();
        sync.getScreenshotAsFile("/tmp/c.png");
        sync.saveScreenshot("/tmp/d.png");
        assertEquals("<html/>", sync.snapshot());
        sync.saveSnapshot("/tmp/c.html");
        sync.setNetworkConditions(false, 0, -1, -1, "wifi");
        sync.sendKeys("hi");
        sync.quit();
        sync.close();

        verify(async).get("https://c.test/", true);
        verify(async, org.mockito.Mockito.times(2)).quit();
    }

    @Test
    void syncWebElementChildQueriesAndDomProbesJoinUnderlyingFutures() {
        WebElement async = mock(WebElement.class);
        WebElement child = mock(WebElement.class);
        when(async.click(eq(false), eq(0.25), eq(1.5), eq(5.0)))
            .thenReturn(CompletableFuture.completedFuture(null));
        when(async.getMidLocation()).thenReturn(CompletableFuture.completedFuture(new double[]{5, 5}));
        when(async.findElement(eq("css selector"), eq("span"), eq(1.0f)))
            .thenReturn(CompletableFuture.completedFuture(child));
        when(async.findElements(eq("css selector"), eq("em")))
            .thenReturn(CompletableFuture.completedFuture(List.of(child)));
        when(async.getSource()).thenReturn(CompletableFuture.completedFuture("<span/>"));
        when(async.setSource(eq("<b/>"))).thenReturn(CompletableFuture.completedFuture(null));
        when(async.getListeners(eq(0))).thenReturn(CompletableFuture.completedFuture(List.of()));
        when(async.getObjId()).thenReturn(CompletableFuture.completedFuture("obj-1"));
        when(async.getNodeId()).thenReturn(CompletableFuture.completedFuture(42));

        SyncWebElement sync = new SyncWebElement(async);

        sync.click(false, 0.25, 1.5, 5.0);
        assertArrayEquals(new double[]{5, 5}, sync.getMidLocation());
        assertInstanceOf(SyncWebElement.class, sync.findElement("css selector", "span", 1f));
        assertEquals(1, sync.findElements("css selector", "em").size());
        assertEquals("<span/>", sync.getSource());
        sync.setSource("<b/>");
        sync.getListeners(0);
        assertEquals("obj-1", sync.getObjId());
        assertEquals(42, sync.getNodeId());

        verify(async).setSource("<b/>");
        verify(async).getListeners(0);
    }

    @Test
    void syncSwitchToFrameTargetAndWindowWrapIntoSyncTarget() {
        SwitchTo async = mock(SwitchTo.class);
        Target frameTarget = mock(Target.class);
        when(async.frame(eq(0), eq(true))).thenReturn(CompletableFuture.completedFuture(frameTarget));
        when(async.frame(eq("frameName"), eq(true))).thenReturn(CompletableFuture.completedFuture(frameTarget));
        when(async.target(eq("tid"), eq(false), eq(true)))
            .thenReturn(CompletableFuture.completedFuture(frameTarget));
        when(async.window(eq("wid"), eq(true), eq(false)))
            .thenReturn(CompletableFuture.completedFuture(frameTarget));

        SyncSwitchTo sync = new SyncSwitchTo(async);

        assertInstanceOf(SyncTarget.class, sync.frame(0, true));
        assertInstanceOf(SyncTarget.class, sync.frame("frameName", true));
        assertInstanceOf(SyncTarget.class, sync.target("tid", false, true));
        assertInstanceOf(SyncTarget.class, sync.window("wid", true, false));
    }

    @Test
    void syncPointerPositionedClickDoubleClickAndScrollJoinUnderlyingFutures() {
        Pointer async = mock(Pointer.class);
        when(async.click(eq(10), eq(20), eq(true), eq(0.5), eq(2.0), eq(20.0)))
            .thenReturn(CompletableFuture.completedFuture(null));
        when(async.doubleClick(eq(10), eq(20), eq(5.0)))
            .thenReturn(CompletableFuture.completedFuture(null));
        when(async.moveTo(eq(1), eq(2), eq(0.5), eq(2.0), eq(20.0)))
            .thenReturn(CompletableFuture.completedFuture(null));
        when(async.scroll(eq(0), eq(-120))).thenReturn(CompletableFuture.completedFuture(null));
        when(async.getLocation()).thenReturn(new int[]{10, 20});

        SyncPointer sync = new SyncPointer(async);

        sync.click(10, 20, true, 0.5, 2.0, 20.0);
        sync.doubleClick(10, 20, 5.0);
        sync.moveTo(1, 2);
        sync.scroll(0, -120);
        assertArrayEquals(new int[]{10, 20}, sync.getLocation());

        verify(async).click(10, 20, true, 0.5, 2.0, 20.0);
        verify(async).doubleClick(10, 20, 5.0);
        verify(async).moveTo(1, 2, 0.5, 2.0, 20.0);
        verify(async).scroll(0, -120);
    }

    @Test
    void syncBaseTargetIdentityAndListenerRemovalJoinUnderlyingFutures() {
        BaseTarget async = mock(BaseTarget.class);
        JsonNode cdpResult = json("{}");
        when(async.getId()).thenReturn("tid-1");
        when(async.removeCdpListener(eq("Page.loadEventFired"), any()))
            .thenReturn(CompletableFuture.completedFuture(null));
        when(async.downloadsDirForContext(eq("ctx-1"))).thenReturn("/tmp/dl");
        when(async.executeCdpCmd(eq("Browser.getVersion"), any(), eq(5.0f)))
            .thenReturn(CompletableFuture.completedFuture(cdpResult));
        when(async.close()).thenReturn(CompletableFuture.completedFuture(null));

        SyncBaseTarget sync = new SyncBaseTarget(async);

        assertEquals("tid-1", sync.getId());
        sync.removeCdpListener("Page.loadEventFired", node -> { });
        assertEquals("/tmp/dl", sync.downloadsDirForContext("ctx-1"));
        sync.executeCdpCmd("Browser.getVersion", Map.of(), 5f);
        sync.close();

        verify(async).downloadsDirForContext("ctx-1");
        verify(async).close();
    }

    @Test
    void syncTargetRefreshWithoutArgsKeepsCache() {
        Target async = mock(Target.class);
        when(async.refresh(eq(false))).thenReturn(CompletableFuture.completedFuture(null));

        new SyncTarget(async).refresh();

        verify(async).refresh(false);
    }

    @Test
    void syncBaseTargetJoinsWaitForCdpWithBoxedTimeout() {
        BaseTarget async = mock(BaseTarget.class);
        JsonNode event = json("{\"method\":\"Page.loadEventFired\"}");
        when(async.waitForCdp(eq("Page.loadEventFired"), eq(3.0f)))
            .thenReturn(CompletableFuture.completedFuture(event));

        assertEquals(event, new SyncBaseTarget(async).waitForCdp("Page.loadEventFired", 3f));
    }
}
