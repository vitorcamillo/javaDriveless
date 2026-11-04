package io.github.selenium.javaDriverless.types;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.Map;
import java.util.concurrent.CompletableFuture;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.NullNode;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class WebElementSendKeysTest {
    @Test
    @SuppressWarnings("unchecked")
    void sendKeysDoesNotPutTextOnKeyDownEvent() {
        Target target = mock(Target.class);
        when(target.executeCdpCmd(eq("Input.dispatchKeyEvent"), any(), isNull()))
            .thenReturn(CompletableFuture.completedFuture(NullNode.getInstance()));

        WebElement element = new NoClickWebElement(target);

        element.sendKeys("a").join();

        ArgumentCaptor<Map<String, Object>> argsCaptor = ArgumentCaptor.forClass(Map.class);
        verify(target, times(3)).executeCdpCmd(
            eq("Input.dispatchKeyEvent"),
            argsCaptor.capture(),
            isNull()
        );

        Map<String, Object> keyDown = argsCaptor.getAllValues().get(0);
        Map<String, Object> charEvent = argsCaptor.getAllValues().get(1);
        Map<String, Object> keyUp = argsCaptor.getAllValues().get(2);

        assertEquals("keyDown", keyDown.get("type"));
        assertEquals("a", keyDown.get("key"));
        assertFalse(keyDown.containsKey("text"));

        assertEquals("char", charEvent.get("type"));
        assertEquals("a", charEvent.get("text"));
        assertNull(charEvent.get("key"));

        assertEquals("keyUp", keyUp.get("type"));
        assertEquals("a", keyUp.get("key"));
        assertFalse(keyUp.containsKey("text"));
    }

    private static class NoClickWebElement extends WebElement {
        NoClickWebElement(Target target) {
            super(target, null, null, "object-id", 1, 1, 1, false);
        }

        @Override
        public CompletableFuture<Void> click(boolean moveTo, double totalTime, double accel, double smoothSoft) {
            return CompletableFuture.completedFuture(null);
        }
    }
}
