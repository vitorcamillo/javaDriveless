package io.github.selenium.javaDriverless.types;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.concurrent.CompletableFuture;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/** Ponte de script do elemento: injeta o próprio elemento como primeiro argumento. */
class WebElementScriptBridgeTest {

    private Target target;
    private WebElement element;

    @BeforeEach
    void setUp() {
        target = mock(Target.class);
        element = new WebElement(target, null, null, "obj-1", 42, null, null, false);
    }

    @Test
    void executeRawScriptPrependsElementToArgs() {
        when(target.executeRawScript(eq("obj.click()"), eq(true), isNull(), any(Object[].class)))
            .thenReturn(CompletableFuture.completedFuture("ok"));

        Object out = WebElementScriptBridge.executeRawScript(element, "obj.click()", true, "a", 2)
            .join();

        assertEquals("ok", out);
        ArgumentCaptor<Object[]> argsCaptor = ArgumentCaptor.forClass(Object[].class);
        verify(target).executeRawScript(eq("obj.click()"), eq(true), isNull(), argsCaptor.capture());
        Object[] args = argsCaptor.getValue();
        assertEquals(3, args.length);
        assertEquals(element, args[0]);
        assertEquals("a", args[1]);
        assertEquals(2, args[2]);
    }

    @Test
    void executeAsyncScriptPrependsElementToArgs() {
        when(target.executeAsyncScript(eq("cb => cb(obj.value)"), eq(2), isNull(), any(Object[].class)))
            .thenReturn(CompletableFuture.completedFuture("v"));

        Object out = WebElementScriptBridge.executeAsyncScript(element, "cb => cb(obj.value)", 2)
            .join();

        assertEquals("v", out);
        ArgumentCaptor<Object[]> argsCaptor = ArgumentCaptor.forClass(Object[].class);
        verify(target).executeAsyncScript(eq("cb => cb(obj.value)"), eq(2), isNull(), argsCaptor.capture());
        Object[] args = argsCaptor.getValue();
        assertEquals(1, args.length);
        assertEquals(element, args[0]);
    }

    @Test
    void evalAsyncUsesAsyncScriptWithElementArgument() {
        when(target.executeAsyncScript(eq("obj.focus()"), eq(2), isNull(), any(Object[].class)))
            .thenReturn(CompletableFuture.completedFuture(null));

        WebElementScriptBridge.evalAsync(element, "obj.focus()", 2).join();

        ArgumentCaptor<Object[]> argsCaptor = ArgumentCaptor.forClass(Object[].class);
        verify(target).executeAsyncScript(eq("obj.focus()"), eq(2), isNull(), argsCaptor.capture());
        Object[] args = argsCaptor.getValue();
        assertEquals(1, args.length);
        assertEquals(element, args[0]);
    }
}
