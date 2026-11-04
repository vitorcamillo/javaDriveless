package io.github.selenium.javaDriverless.input;

import com.fasterxml.jackson.databind.JsonNode;
import io.github.selenium.javaDriverless.types.Target;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyFloat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Caracterizacao do contrato CDP do teclado: sequencia keyDown/char/keyUp,
 * normalizacao de aliases de teclas especiais e valores de modificadores.
 */
class KeyboardCdpContractTest {

    @Test
    void pressNormalCharEmitsKeyDownCharAndKeyUpInOrder() {
        RecordingKeyboard keyboard = new RecordingKeyboard();

        keyboard.keyboard.press("a").join();

        assertThat(keyboard.eventTypes()).containsExactly("keyDown", "char", "keyUp");
        Map<String, Object> charEvent = keyboard.events.get(1);
        assertThat(charEvent.get("key")).isEqualTo("a");
        assertThat(charEvent.get("text")).isEqualTo("a");
        assertThat(charEvent.get("unmodifiedText")).isEqualTo("a");
        assertThat(keyboard.events.get(0)).doesNotContainKey("text");
        assertThat(keyboard.events.get(2)).doesNotContainKey("text");
    }

    @Test
    void pressSpecialKeySkipsCharEvent() {
        RecordingKeyboard keyboard = new RecordingKeyboard();

        keyboard.keyboard.press("Enter").join();

        assertThat(keyboard.eventTypes()).containsExactly("keyDown", "keyUp");
        assertThat(keyboard.events).allSatisfy(event -> {
            assertThat(event.get("key")).isEqualTo("Enter");
            assertThat(event).doesNotContainKey("text");
        });
    }

    @Test
    void pressSpaceEmitsCharEventWithTextSpace() {
        // Espaco e caractere imprimivel: sem o evento "char" com text=" ",
        // campos de texto perdiam os espacos digitados via press/type.
        RecordingKeyboard keyboard = new RecordingKeyboard();

        keyboard.keyboard.press(" ").join();

        assertThat(keyboard.eventTypes()).containsExactly("keyDown", "char", "keyUp");
        Map<String, Object> charEvent = keyboard.events.get(1);
        assertThat(charEvent.get("key")).isEqualTo(" ");
        assertThat(charEvent.get("text")).isEqualTo(" ");
        assertThat(charEvent.get("unmodifiedText")).isEqualTo(" ");
        assertThat(keyboard.events.get(0)).doesNotContainKey("text");
        assertThat(keyboard.events.get(2)).doesNotContainKey("text");
    }

    @Test
    void pressSpaceAliasEmitsCharEventWithTextSpace() {
        RecordingKeyboard keyboard = new RecordingKeyboard();

        keyboard.keyboard.press("SPACE").join();

        assertThat(keyboard.eventTypes()).containsExactly("keyDown", "char", "keyUp");
        assertThat(keyboard.events.stream().map(event -> event.get("key")))
                .containsExactly(" ", " ", " ");
        assertThat(keyboard.events.get(1).get("text")).isEqualTo(" ");
    }

    @Test
    void typeEmitsCharEventForSpacesInsideText() {
        RecordingKeyboard keyboard = new RecordingKeyboard();

        keyboard.keyboard.type("a b", 0).join();

        assertThat(keyboard.eventTypes()).containsExactly(
                "keyDown", "char", "keyUp",
                "keyDown", "char", "keyUp",
                "keyDown", "char", "keyUp");
        assertThat(keyboard.events.get(4).get("key")).isEqualTo(" ");
        assertThat(keyboard.events.get(4).get("text")).isEqualTo(" ");
    }

    @Test
    void typeEmitsFullSequenceForEachCharacter() {
        RecordingKeyboard keyboard = new RecordingKeyboard();

        keyboard.keyboard.type("hi", 0).join();

        assertThat(keyboard.eventTypes()).containsExactly(
                "keyDown", "char", "keyUp",
                "keyDown", "char", "keyUp");
        assertThat(keyboard.events.get(1).get("text")).isEqualTo("h");
        assertThat(keyboard.events.get(4).get("text")).isEqualTo("i");
    }

    @Test
    void typeEmptyStringEmitsNothing() {
        RecordingKeyboard keyboard = new RecordingKeyboard();

        keyboard.keyboard.type("", 0).join();

        assertThat(keyboard.events).isEmpty();
    }

    @Test
    void sendKeysReleasesInReverseOrder() {
        RecordingKeyboard keyboard = new RecordingKeyboard();

        keyboard.keyboard.sendKeys("Control", "c").join();

        assertThat(keyboard.eventTypes()).containsExactly(
                "keyDown", "keyDown", "keyUp", "keyUp");
        assertThat(keyboard.events.stream().map(event -> event.get("key")))
                .containsExactly("Control", "c", "c", "Control");
    }

    @Test
    void sendKeysWithoutArgumentsEmitsNothing() {
        RecordingKeyboard keyboard = new RecordingKeyboard();

        keyboard.keyboard.sendKeys().join();

        assertThat(keyboard.events).isEmpty();
    }

    @Test
    void modifierKeysCarryModifierFlag() {
        // Uma instancia por tecla: isola o bit de cada modificador. O CDP
        // define "modifiers" como mascara acumulada — combinacoes em
        // modifierCombinationAccumulatesAndReleases abaixo.
        assertModifierFlag("Control", 2);
        assertModifierFlag("Alt", 1);
        assertModifierFlag("Shift", 8);
        assertModifierFlag("Meta", 4);
    }

    private static void assertModifierFlag(String key, int expectedFlag) {
        RecordingKeyboard keyboard = new RecordingKeyboard();
        keyboard.keyboard.keyDown(key).join();
        assertThat(keyboard.events).hasSize(1);
        assertThat(keyboard.events.get(0).get("key")).isEqualTo(key);
        assertThat(keyboard.events.get(0).get("modifiers")).isEqualTo(expectedFlag);
    }

    @Test
    void aliasCommandMapsToMetaModifierFlag() {
        RecordingKeyboard keyboard = new RecordingKeyboard();
        keyboard.keyboard.keyDown("COMMAND").join();
        assertThat(keyboard.events.get(0).get("key")).isEqualTo("Meta");
        assertThat(keyboard.events.get(0).get("modifiers")).isEqualTo(4);
    }

    @Test
    void sendKeysCarriesAccumulatedModifierMaskThroughNormalKey() {
        // Sem a mascara na tecla normal, o site veria "c" puro em vez de Ctrl+C.
        RecordingKeyboard keyboard = new RecordingKeyboard();

        keyboard.keyboard.sendKeys("Control", "c").join();

        assertThat(keyboard.eventTypes()).containsExactly(
                "keyDown", "keyDown", "keyUp", "keyUp");
        assertThat(keyboard.events.stream().map(event -> event.get("modifiers")))
                .containsExactly(2, 2, 2, null);
        assertThat(keyboard.events.stream().map(event -> event.get("key")))
                .containsExactly("Control", "c", "c", "Control");
    }

    @Test
    void modifierCombinationAccumulatesAndReleases() {
        // Estado por instancia: bits somam enquanto pressionados; o keyUp nao
        // carrega mais o bit do proprio modificador (algoritmo UI Events /
        // Puppeteer removem antes de construir o evento), e teclas normais
        // herdam a mascara vigente. Mascara 0 omite o campo (null).
        RecordingKeyboard keyboard = new RecordingKeyboard();

        keyboard.keyboard.keyDown("Control").join();
        keyboard.keyboard.keyDown("Shift").join();
        keyboard.keyboard.keyUp("Control").join();
        keyboard.keyboard.keyDown("a").join();
        keyboard.keyboard.keyUp("a").join();
        keyboard.keyboard.keyUp("Shift").join();

        assertThat(keyboard.events.stream().map(event -> event.get("modifiers")))
                .containsExactly(2, 10, 8, 8, 8, null);
    }

    @Test
    void releasedModifierClearsStateForSubsequentKeys() {
        RecordingKeyboard keyboard = new RecordingKeyboard();

        keyboard.keyboard.keyDown("Control").join();
        keyboard.keyboard.keyUp("Control").join();
        keyboard.keyboard.keyDown("a").join();

        assertThat(keyboard.events.get(2)).doesNotContainKey("modifiers");
    }

    @Test
    void rejectedDispatchDoesNotLeaveModifierStuck() {
        // Estado so persiste quando o CDP aceita o evento: keyDown rejeitado
        // nao pode vazar modifiers=2 para os eventos seguintes.
        RecordingKeyboard keyboard = new RecordingKeyboard();
        keyboard.failNextDispatch();

        keyboard.keyboard.keyDown("Control").handle((ignored, failure) -> null).join();
        keyboard.keyboard.keyDown("a").join();

        assertThat(keyboard.events).hasSize(1);
        assertThat(keyboard.events.get(0)).doesNotContainKey("modifiers");
    }

    @Test
    void modifierStateDoesNotLeakAcrossInstances() {
        RecordingKeyboard first = new RecordingKeyboard();
        first.keyboard.keyDown("Control").join();

        RecordingKeyboard second = new RecordingKeyboard();
        second.keyboard.keyDown("a").join();

        assertThat(second.events.get(0)).doesNotContainKey("modifiers");
    }

    @Test
    void controlCharacterKeepsCharEventWithText() {
        // Caracterizacao: \t (ISO control) emite keyDown/char/keyUp com
        // text="\t", igual a qualquer caractere de 1 caractere.
        RecordingKeyboard keyboard = new RecordingKeyboard();

        keyboard.keyboard.press("\t").join();

        assertThat(keyboard.eventTypes()).containsExactly("keyDown", "char", "keyUp");
        assertThat(keyboard.events.get(1).get("text")).isEqualTo("\t");
    }

    @Test
    void specialKeyAliasesAreNormalizedToCdpNames() {
        RecordingKeyboard keyboard = new RecordingKeyboard();

        keyboard.keyboard.enter().join();
        keyboard.keyboard.escape().join();
        keyboard.keyboard.tab().join();
        keyboard.keyboard.backspace().join();
        keyboard.keyboard.delete().join();

        assertThat(keyboard.events.stream().map(event -> event.get("key")))
                .containsExactly("Enter", "Enter", "Escape", "Escape",
                        "Tab", "Tab", "Backspace", "Backspace",
                        "Delete", "Delete");
    }

    @Test
    void nonModifierKeysOmitModifierFlag() {
        // Comportamento atual pinado: o campo "modifiers" só é enviado para
        // teclas modificadoras; o CDP aplica o default 0 quando ausente.
        RecordingKeyboard keyboard = new RecordingKeyboard();

        keyboard.keyboard.keyDown("a").join();

        assertThat(keyboard.events.get(0).get("key")).isEqualTo("a");
        assertThat(keyboard.events.get(0)).doesNotContainKey("modifiers");
    }

    @Test
    void everyEventTargetsDispatchKeyEventCommand() {
        RecordingKeyboard keyboard = new RecordingKeyboard();

        keyboard.keyboard.type("ab", 0).join();

        verify(keyboard.target, times(6)).executeCdpCmd(
                eq("Input.dispatchKeyEvent"), any(), anyFloat());
    }

    private static final class RecordingKeyboard {
        final Target target = mock(Target.class);
        final Keyboard keyboard = new Keyboard(target);
        final List<Map<String, Object>> events = new ArrayList<>();
        final java.util.concurrent.atomic.AtomicInteger rejections = new java.util.concurrent.atomic.AtomicInteger();

        @SuppressWarnings("unchecked")
        RecordingKeyboard() {
            when(target.executeCdpCmd(
                    eq("Input.dispatchKeyEvent"), any(Map.class), anyFloat()))
                    .thenAnswer(invocation -> {
                        if (rejections.getAndDecrement() > 0) {
                            return CompletableFuture.failedFuture(
                                    new IllegalStateException("cdp rejected"));
                        }
                        events.add(Map.copyOf((Map<String, Object>) invocation.getArgument(1)));
                        return CompletableFuture.completedFuture(
                                (JsonNode) com.fasterxml.jackson.databind.node.JsonNodeFactory
                                        .instance.objectNode());
                    });
        }

        void failNextDispatch() {
            rejections.set(1);
        }

        List<String> eventTypes() {
            return events.stream().map(event -> (String) event.get("type")).toList();
        }
    }
}
