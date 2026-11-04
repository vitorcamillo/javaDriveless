package io.github.selenium.javaDriverless.sync;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.LinkedBlockingQueue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.NullNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import org.junit.jupiter.api.Test;

import io.github.selenium.javaDriverless.input.Pointer;
import io.github.selenium.javaDriverless.scripts.SwitchTo;
import io.github.selenium.javaDriverless.types.Alert;
import io.github.selenium.javaDriverless.types.BaseTarget;
import io.github.selenium.javaDriverless.types.Context;
import io.github.selenium.javaDriverless.types.Target;
import io.github.selenium.javaDriverless.types.WebElement;

/** Wrappers síncronos (bloqueantes): delegação correta + propagação de erro do join. */
class SyncWrappersTest {

    private static final com.fasterxml.jackson.databind.ObjectMapper MAPPER =
        new com.fasterxml.jackson.databind.ObjectMapper();

    private static ObjectNode json(String json) {
        try {
            return (ObjectNode) MAPPER.readTree(json);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    @Test
    void syncContextDelegatesAndWrapsAsyncResults() {
        Context async = mock(Context.class);
        Target currentTarget = mock(Target.class);
        when(async.getCurrentTarget()).thenReturn(currentTarget);
        when(async.getTitle()).thenReturn(CompletableFuture.completedFuture("T"));
        when(async.getCurrentUrl()).thenReturn(CompletableFuture.completedFuture("https://x.test/"));
        when(async.getPageSource()).thenReturn(CompletableFuture.completedFuture("<html/>"));
        when(async.executeScript(eq("return 1"), any(), eq(false)))
            .thenReturn(CompletableFuture.completedFuture("v"));
        when(async.findElement(eq("id"), eq("q"), eq(5.0f)))
            .thenReturn(CompletableFuture.completedFuture(mock(WebElement.class)));
        when(async.findElements(eq("css selector"), eq("div"), eq(5.0f)))
            .thenReturn(CompletableFuture.completedFuture(
                List.of(mock(WebElement.class), mock(WebElement.class))));

        SyncContext sync = new SyncContext(async);

        assertInstanceOf(SyncTarget.class, sync.getCurrentTarget());
        assertEquals("T", sync.getTitle());
        assertEquals("https://x.test/", sync.getCurrentUrl());
        assertEquals("<html/>", sync.getPageSource());
        assertEquals("v", sync.executeScript("return 1", null, false));
        assertInstanceOf(SyncWebElement.class, sync.findElement("id", "q", 5f));
        assertEquals(2, sync.findElements("css selector", "div", 5f).size());
    }

    @Test
    void syncContextPropagatesAsyncFailureAsRuntimeException() {
        Context async = mock(Context.class);
        when(async.getTitle())
            .thenReturn(CompletableFuture.failedFuture(new IllegalStateException("boom")));

        SyncContext sync = new SyncContext(async);

        RuntimeException thrown = assertThrows(RuntimeException.class, sync::getTitle);
        assertEquals("boom", thrown.getCause().getMessage());
    }

    @Test
    void syncTargetDelegatesNavigationAndPageQueries() {
        Target async = mock(Target.class);
        when(async.get(eq("https://a.test/"), eq(true)))
            .thenReturn(CompletableFuture.completedFuture(java.util.Map.of("ok", true)));
        when(async.getCurrentUrl()).thenReturn(CompletableFuture.completedFuture("u"));
        when(async.getTitle()).thenReturn(CompletableFuture.completedFuture("t"));
        when(async.back()).thenReturn(CompletableFuture.completedFuture(null));
        when(async.forward()).thenReturn(CompletableFuture.completedFuture(null));
        when(async.refresh(eq(true))).thenReturn(CompletableFuture.completedFuture(null));

        SyncTarget sync = new SyncTarget(async);

        assertTrue(sync.get("https://a.test/", true).containsKey("ok"));
        sync.get("https://a.test/");
        assertEquals("u", sync.getCurrentUrl());
        assertEquals("t", sync.getTitle());
        sync.back();
        sync.forward();
        sync.refresh(true);

        verify(async).back();
        verify(async).forward();
        verify(async).refresh(true);
    }

    @Test
    void syncWebElementDelegatesInteractionAndState() {
        WebElement async = mock(WebElement.class);
        when(async.getText()).thenReturn(CompletableFuture.completedFuture("hello"));
        when(async.getTagName()).thenReturn(CompletableFuture.completedFuture("div"));
        when(async.getAttribute(eq("id"))).thenReturn(CompletableFuture.completedFuture("x"));
        when(async.isDisplayed()).thenReturn(CompletableFuture.completedFuture(true));
        when(async.isEnabled()).thenReturn(CompletableFuture.completedFuture(true));
        when(async.isSelected()).thenReturn(CompletableFuture.completedFuture(false));
        when(async.getLocation()).thenReturn(CompletableFuture.completedFuture(new double[]{1, 2}));
        when(async.getSize()).thenReturn(CompletableFuture.completedFuture(new double[]{3, 4}));
        when(async.click(org.mockito.ArgumentMatchers.anyBoolean(), anyDouble(), anyDouble(), anyDouble()))
            .thenReturn(CompletableFuture.completedFuture(null));
        when(async.sendKeys(eq("k"))).thenReturn(CompletableFuture.completedFuture(null));
        when(async.clear()).thenReturn(CompletableFuture.completedFuture(null));

        SyncWebElement sync = new SyncWebElement(async);

        assertEquals("hello", sync.getText());
        assertEquals("div", sync.getTagName());
        assertEquals("x", sync.getAttribute("id"));
        assertEquals(true, sync.isDisplayed());
        assertEquals(true, sync.isEnabled());
        assertEquals(false, sync.isSelected());
        assertEquals(2.0, sync.getLocation()[1], 1e-9);
        assertEquals(4.0, sync.getSize()[1], 1e-9);

        sync.click();
        sync.sendKeys("k");
        sync.clear();
        verify(async).sendKeys("k");
        verify(async).clear();
    }

    @Test
    void syncSwitchToWrapsAsyncSwitchToResults() {
        SwitchTo async = mock(SwitchTo.class);
        Alert asyncAlert = mock(Alert.class);
        when(asyncAlert.getText()).thenReturn(CompletableFuture.completedFuture("msg"));
        when(async.alert()).thenReturn(CompletableFuture.completedFuture(asyncAlert));
        when(async.getAlert(eq(2.0f))).thenReturn(CompletableFuture.completedFuture(asyncAlert));
        when(async.defaultContent(eq(false)))
            .thenReturn(CompletableFuture.completedFuture(mock(Target.class)));

        SyncSwitchTo sync = new SyncSwitchTo(async);

        assertEquals("msg", sync.alert().getText());
        assertEquals("msg", sync.getAlert(2f).getText());
        assertInstanceOf(SyncTarget.class, sync.defaultContent(false));
    }

    @Test
    void syncAlertDelegatesDialogOperations() {
        Alert async = mock(Alert.class);
        when(async.getText()).thenReturn(CompletableFuture.completedFuture("confirm?"));
        when(async.getUrl()).thenReturn(CompletableFuture.completedFuture("https://x.test/"));
        when(async.getType()).thenReturn(CompletableFuture.completedFuture("confirm"));
        when(async.hasBrowserHandler()).thenReturn(CompletableFuture.completedFuture(true));
        when(async.getDefaultPrompt()).thenReturn(CompletableFuture.completedFuture("y"));
        when(async.accept()).thenReturn(CompletableFuture.completedFuture(null));
        when(async.dismiss()).thenReturn(CompletableFuture.completedFuture(null));
        when(async.sendKeys(eq("typed"))).thenReturn(CompletableFuture.completedFuture(null));

        SyncAlert sync = new SyncAlert(async);

        assertEquals("confirm?", sync.getText());
        assertEquals("https://x.test/", sync.getUrl());
        assertEquals("confirm", sync.getType());
        assertEquals(true, sync.hasBrowserHandler());
        assertEquals("y", sync.getDefaultPrompt());
        sync.accept();
        sync.dismiss();
        sync.sendKeys("typed");
        verify(async).accept();
        verify(async).dismiss();
        verify(async).sendKeys("typed");
    }

    @Test
    void syncPointerDelegatesPhysicalInput() {
        Pointer async = mock(Pointer.class);
        when(async.down()).thenReturn(CompletableFuture.completedFuture(null));
        when(async.up()).thenReturn(CompletableFuture.completedFuture(null));
        when(async.click()).thenReturn(CompletableFuture.completedFuture(null));
        when(async.moveTo(anyInt(), anyInt(), anyDouble(), anyInt(), anyInt()))
            .thenReturn(CompletableFuture.completedFuture(null));

        SyncPointer sync = new SyncPointer(async);

        sync.down();
        sync.up();
        sync.click();
        sync.moveTo(1, 2, 1.0, 2, 20);

        verify(async).down();
        verify(async).up();
        verify(async).click();
        verify(async).moveTo(1, 2, 1.0, 2, 20);
    }

    @Test
    void syncBaseTargetDelegatesCdpCommandsAndListeners() {
        BaseTarget async = mock(BaseTarget.class);
        when(async.getType()).thenReturn(CompletableFuture.completedFuture("browser"));
        JsonNode response = json("{\"ok\":true}");
        when(async.executeCdpCmd(eq("Target.getTargets"), any(), any()))
            .thenReturn(CompletableFuture.completedFuture(response));
        when(async.addCdpListener(eq("Page.loadEventFired"), any()))
            .thenReturn(CompletableFuture.completedFuture(null));
        when(async.getCdpEventIter(eq("Page.loadEventFired")))
            .thenReturn(CompletableFuture.completedFuture(new LinkedBlockingQueue<>()));

        SyncBaseTarget sync = new SyncBaseTarget(async);

        assertEquals("browser", sync.getType());
        assertEquals(response, sync.executeCdpCmd("Target.getTargets", null, null));
        sync.addCdpListener("Page.loadEventFired", node -> { });
        assertInstanceOf(LinkedBlockingQueue.class, sync.getCdpEventIter("Page.loadEventFired"));
    }

    // SyncChrome exige Chrome.create real (browser) e é coberto pelas suítes browser/external;
    // aqui ficam os wrappers que podem ser validados de forma determinística.
}
