package io.github.selenium.javaDriverless.input;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Map;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.junit.jupiter.api.Test;


/**
 * Constantes de input (modificadores, botões, tipos) e o payload CDP do
 * PointerEventData; exceções tipadas de JS e iframe.
 */
class InputConstantsAndPayloadTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Test
    void pointerConstantsExposeCdpVocabulary() {
        assertEquals(0, Pointer.Modifiers.NONE);
        assertEquals(1, Pointer.Modifiers.ALT);
        assertEquals(2, Pointer.Modifiers.CTRL);
        assertEquals(4, Pointer.Modifiers.COMMAND);
        assertEquals(8, Pointer.Modifiers.SHIFT);

        assertEquals("mouse", Pointer.PointerType.MOUSE);
        assertEquals("pen", Pointer.PointerType.PEN);

        assertEquals("none", Pointer.MouseButton.NONE);
        assertEquals("left", Pointer.MouseButton.LEFT);
        assertEquals("middle", Pointer.MouseButton.MIDDLE);
        assertEquals("right", Pointer.MouseButton.RIGHT);
        assertEquals("back", Pointer.MouseButton.BACK);
        assertEquals("forward", Pointer.MouseButton.FORWARD);

        assertEquals(0, Pointer.Buttons.NONE);
        assertEquals(1, Pointer.Buttons.LEFT);
        assertEquals(2, Pointer.Buttons.RIGHT);
        assertEquals(4, Pointer.Buttons.MIDDLE);
        assertEquals(8, Pointer.Buttons.BACK);
        assertEquals(16, Pointer.Buttons.FORWARD);
        assertNull(Pointer.Buttons.DEFAULT);

        assertEquals("mousePressed", Pointer.EventType.PRESS);
        assertEquals("mouseReleased", Pointer.EventType.RELEASE);
        assertEquals("mouseMoved", Pointer.EventType.MOVE);
        assertEquals("mouseWheel", Pointer.EventType.WHEEL);
    }

    @Test
    void buttonMaskResolutionCoversEveryButtonAndUnknown() {
        assertEquals(Pointer.Buttons.LEFT, PointerButtonMasks.forButton(Pointer.MouseButton.LEFT));
        assertEquals(Pointer.Buttons.RIGHT, PointerButtonMasks.forButton(Pointer.MouseButton.RIGHT));
        assertEquals(Pointer.Buttons.MIDDLE, PointerButtonMasks.forButton(Pointer.MouseButton.MIDDLE));
        assertEquals(Pointer.Buttons.BACK, PointerButtonMasks.forButton(Pointer.MouseButton.BACK));
        assertEquals(Pointer.Buttons.FORWARD, PointerButtonMasks.forButton(Pointer.MouseButton.FORWARD));
        assertEquals(Pointer.Buttons.NONE, PointerButtonMasks.forButton(Pointer.MouseButton.NONE));
        assertEquals(Pointer.Buttons.NONE, PointerButtonMasks.forButton("qualquer"));
    }

    @Test
    void pointerEventDataSerializesCoreCdpFields() throws Exception {
        PointerEventData event = new PointerEventData(PointerEventTypes.PRESS, 10, 20);
        event.clickCount = 1;
        event.timestamp = 123.5;
        event.buttons = Pointer.Buttons.LEFT;

        Object[] command = event.toJson();
        assertEquals("Input.dispatchMouseEvent", command[0]);

        JsonNode params = MAPPER.readTree(command[1].toString());
        assertEquals("mousePressed", params.path("type").asText());
        assertEquals(10, params.path("x").asInt());
        assertEquals(20, params.path("y").asInt());
        assertEquals(0, params.path("modifiers").asInt());
        assertEquals(1, params.path("clickCount").asInt());
        assertEquals("left", params.path("button").asText());
        assertEquals(123.5, params.path("timestamp").asDouble(), 1e-9);
        assertEquals(1, params.path("buttons").asInt());
        assertEquals("mouse", params.path("pointerType").asText());
    }

    @Test
    void pointerEventDataOmitsOptionalFieldsWhenUnset() throws Exception {
        PointerEventData event = new PointerEventData(PointerEventTypes.MOVE, 0, 0);

        JsonNode params = MAPPER.readTree(event.toJson()[1].toString());

        assertFalse(params.has("timestamp"));
        assertFalse(params.has("buttons"));
        assertEquals(0, params.path("deltaX").asInt());
    }

    @Test
    void pointerEventDataToMapRoundTripsParameters() {
        PointerEventData event = new PointerEventData(PointerEventTypes.WHEEL, 5, 6);
        event.deltaX = 1;
        event.deltaY = -2;

        Map<String, Object> map = PointerEventData.toMap(event.toJson()[1]);

        assertEquals("mouseWheel", map.get("type"));
        assertEquals(1, map.get("deltaX"));
        assertEquals(-2, map.get("deltaY"));
    }

    @Test
    void keyboardKeysExposeExpectedKeyNames() {
        assertEquals("Enter", Keyboard.Keys.ENTER);
        assertEquals("Tab", Keyboard.Keys.TAB);
        assertEquals("Escape", Keyboard.Keys.ESCAPE);
        assertEquals("Backspace", Keyboard.Keys.BACKSPACE);
        assertEquals("Delete", Keyboard.Keys.DELETE);
        assertEquals(" ", Keyboard.Keys.SPACE);
        assertEquals(Keyboard.Keys.CONTROL, Keyboard.Keys.CTRL);
        assertEquals(Keyboard.Keys.META, Keyboard.Keys.COMMAND);
        assertEquals("ArrowUp", Keyboard.Keys.ARROW_UP);
        assertEquals("PageDown", Keyboard.Keys.PAGE_DOWN);
        assertEquals("F12", Keyboard.Keys.F12);
    }

    @Test
    void pointerNestedVocabularyClassesAreInstantiableNamespaceMarkers() {
        // classes de vocabulário do Pointer existem só para agrupar constantes:
        // o contrato é serem instanciáveis (sem estado) para consumers que as tipam
        assertNotNull(new Pointer.Modifiers());
        assertNotNull(new Pointer.PointerType());
        assertNotNull(new Pointer.MouseButton());
        assertNotNull(new Pointer.Buttons());
        assertNotNull(new Pointer.EventType());
    }
}
