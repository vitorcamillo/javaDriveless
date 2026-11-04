package io.github.selenium.javaDriverless.input;

import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Caracterizacao do payload CDP gerado para eventos de ponteiro.
 */
class PointerEventDataContractTest {

    @Test
    void defaultPayloadTargetsDispatchMouseEventWithCompleteFields() {
        PointerEventData event = new PointerEventData("mousePressed", 130, 40);

        Object[] command = event.toJson();

        assertThat(command).hasSize(2);
        assertThat(command[0]).isEqualTo("Input.dispatchMouseEvent");
        ObjectNode params = (ObjectNode) command[1];
        assertThat(params.get("type").asText()).isEqualTo("mousePressed");
        assertThat(params.get("x").asInt()).isEqualTo(130);
        assertThat(params.get("y").asInt()).isEqualTo(40);
        assertThat(params.get("modifiers").asInt()).isZero();
        assertThat(params.get("button").asText()).isEqualTo("left");
        assertThat(params.get("clickCount").asInt()).isZero();
        assertThat(params.get("pointerType").asText()).isEqualTo("mouse");
        assertThat(params.get("force").asDouble()).isZero();
        assertThat(params.get("deltaX").asInt()).isZero();
        assertThat(params.get("deltaY").asInt()).isZero();
    }

    @Test
    void optionalTimestampAndButtonsAreOmittedWhenUnset() {
        PointerEventData event = new PointerEventData("mouseMoved", 10, 20);

        ObjectNode params = (ObjectNode) event.toJson()[1];

        assertThat(params.has("timestamp")).isFalse();
        assertThat(params.has("buttons")).isFalse();
    }

    @Test
    void optionalTimestampAndButtonsAreIncludedWhenSet() {
        PointerEventData event = new PointerEventData("mouseMoved", 10, 20);
        event.timestamp = 12.5;
        event.buttons = PointerButtonMasks.RIGHT;

        ObjectNode params = (ObjectNode) event.toJson()[1];

        assertThat(params.get("timestamp").asDouble()).isEqualTo(12.5);
        assertThat(params.get("buttons").asInt()).isEqualTo(2);
    }

    @Test
    void buttonMasksFollowCdpBitOrder() {
        assertThat(PointerButtonMasks.forButton("left")).isEqualTo(1);
        assertThat(PointerButtonMasks.forButton("right")).isEqualTo(2);
        assertThat(PointerButtonMasks.forButton("middle")).isEqualTo(4);
        assertThat(PointerButtonMasks.forButton("back")).isEqualTo(8);
        assertThat(PointerButtonMasks.forButton("forward")).isEqualTo(16);
        assertThat(PointerButtonMasks.forButton("none")).isZero();
        assertThat(PointerButtonMasks.forButton("unknown-button")).isZero();
    }

    @Test
    void toMapConvertsObjectNodeParams() {
        PointerEventData event = new PointerEventData("mouseWheel", 5, 6);

        Map<String, Object> params = PointerEventData.toMap(event.toJson()[1]);

        assertThat(params.get("type")).isEqualTo("mouseWheel");
        assertThat(params.get("x")).isEqualTo(5);
        assertThat(params.get("y")).isEqualTo(6);
    }
}
