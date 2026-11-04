package io.github.selenium.driverless;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import io.github.selenium.javaDriverless.cdp.CDPCommandExecutor;
import io.github.selenium.javaDriverless.input.Pointer;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class PointerEventPayloadTest {

    @Test
    void moveEventsUseNoButtonWhenNotDragging() {
        RecordingExecutor executor = new RecordingExecutor();
        Pointer pointer = new Pointer(executor);

        pointer.moveTo(130, 40, 0.03, 2.0, 20.0).join();

        List<Map<String, Object>> moves = executor.eventsByType("mouseMoved");
        assertThat(moves).isNotEmpty();
        assertThat(moves).allSatisfy(event -> {
            assertThat(event.get("button")).isEqualTo("none");
            assertThat(event.get("buttons")).isEqualTo(0);
        });
    }

    @Test
    void dragMoveKeepsPressedButtonMaskUntilRelease() {
        RecordingExecutor executor = new RecordingExecutor();
        Pointer pointer = new Pointer(executor);

        pointer.down(100, 20, Pointer.MouseButton.LEFT, 1).join();
        pointer.moveTo(140, 50, 0.03, 2.0, 20.0).join();
        pointer.up(140, 50, Pointer.MouseButton.LEFT, 1).join();

        Map<String, Object> press = executor.firstEventByType("mousePressed");
        assertThat(press.get("button")).isEqualTo("left");
        assertThat(press.get("buttons")).isEqualTo(1);

        List<Map<String, Object>> moves = executor.eventsByType("mouseMoved");
        assertThat(moves).isNotEmpty();
        assertThat(moves).allSatisfy(event -> {
            assertThat(event.get("button")).isEqualTo("none");
            assertThat(event.get("buttons")).isEqualTo(1);
        });

        Map<String, Object> release = executor.firstEventByType("mouseReleased");
        assertThat(release.get("button")).isEqualTo("left");
        assertThat(release.get("buttons")).isEqualTo(0);
    }

    private static final class RecordingExecutor implements CDPCommandExecutor {
        private final List<Map<String, Object>> events = new ArrayList<>();

        @Override
        public CompletableFuture<JsonNode> executeCdpCmd(
                String cmd,
                Map<String, Object> cmdArgs,
                Float timeout) {
            if ("Input.dispatchMouseEvent".equals(cmd)) {
                events.add(Map.copyOf(cmdArgs));
            }
            return CompletableFuture.completedFuture(JsonNodeFactory.instance.objectNode());
        }

        List<Map<String, Object>> eventsByType(String type) {
            return events.stream()
                    .filter(event -> type.equals(event.get("type")))
                    .toList();
        }

        Map<String, Object> firstEventByType(String type) {
            return eventsByType(type).get(0);
        }
    }
}
