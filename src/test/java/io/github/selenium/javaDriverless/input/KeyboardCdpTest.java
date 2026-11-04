package io.github.selenium.javaDriverless.input;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.TimeUnit;
import java.util.function.BiConsumer;

import com.fasterxml.jackson.databind.JsonNode;

import org.java_websocket.WebSocket;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import io.github.selenium.javaDriverless.cdp.FakeCdpServer;
import io.github.selenium.javaDriverless.types.Target;

/**
 * Keyboard REAL contra servidor CDP falso: conversão de teclas, eventos char
 * para caracteres imprimíveis e máscara de modificadores de atalhos (Ctrl+C).
 */
class KeyboardCdpTest {

    private FakeCdpServer server;
    private Target target;
    private Keyboard keyboard;

    /** params de cada Input.dispatchKeyEvent recebido, em ordem. */
    private final ConcurrentLinkedQueue<Map<String, Object>> eventos =
        new ConcurrentLinkedQueue<>();

    private final BiConsumer<WebSocket, JsonNode> handler = (conn, req) -> {
        int id = req.get("id").asInt();
        String method = req.path("method").asText("");
        if ("Input.dispatchKeyEvent".equals(method)) {
            eventos.add((Map<String, Object>) (Map<?, ?>) com.fasterxml.jackson.databind.json.JsonMapper
                .builder().build().convertValue(req.path("params"), Map.class));
            conn.send("{\"id\":" + id + ",\"result\":{}}");
            return;
        }
        conn.send("{\"id\":" + id + ",\"result\":{}}");
    };

    @BeforeEach
    void setUp() throws InterruptedException {
        server = new FakeCdpServer();
        server.startAndAwaitReady();
        server.setCustomHandler(handler);

        target = new Target("127.0.0.1:" + server.port(), "tid-key", null, null,
            false, 5f, null, false, 10 * 1024 * 1024);
        target.init().join();
        keyboard = new Keyboard(target);
    }

    @AfterEach
    void tearDown() {
        try {
            if (target.getSocket() != null) {
                target.getSocket().close();
            }
        } catch (Exception ignored) {
        }
        try {
            server.stop(0);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> evento(int indice) {
        return (Map<String, Object>) eventos.toArray()[indice];
    }

    @Test
    void printableCharacterPressEmitsDownCharAndUp() throws Exception {
        keyboard.press("a").get(5, TimeUnit.SECONDS);

        assertEquals(3, eventos.size());
        assertEquals("keyDown", evento(0).get("type"));
        assertEquals("a", evento(0).get("key"));

        assertEquals("char", evento(1).get("type"));
        assertEquals("a", evento(1).get("text"));
        assertEquals("a", evento(1).get("unmodifiedText"));

        assertEquals("keyUp", evento(2).get("type"));
    }

    @Test
    void specialKeyPressEmitsOnlyDownAndUpWithoutText() throws Exception {
        keyboard.press("Enter").get(5, TimeUnit.SECONDS);

        assertEquals(2, eventos.size());
        assertEquals("Enter", evento(0).get("key"));
        assertEquals("Enter", evento(1).get("key"));
        assertTrue(!evento(0).containsKey("text"));
        assertTrue(!evento(1).containsKey("text"));
    }

    @Test
    void keyNamesAreNormalizedToCdpFormat() throws Exception {
        keyboard.press("ENTER").get(5, TimeUnit.SECONDS);
        assertEquals("Enter", evento(0).get("key"));

        keyboard.press("ESC").get(5, TimeUnit.SECONDS);
        assertEquals("Escape", evento(2).get("key"));

        keyboard.press("PAGE_DOWN").get(5, TimeUnit.SECONDS);
        assertEquals("PageDown", evento(4).get("key"));
    }

    @Test
    void typeWithFixedDelayEmitsThreeEventsPerCharacter() throws Exception {
        keyboard.type("ab", 0).get(5, TimeUnit.SECONDS);

        assertEquals(6, eventos.size());
        assertEquals("a", evento(1).get("text"));
        assertEquals("b", evento(4).get("text"));
    }

    @Test
    void shortcutCarriesModifierMaskOnInnerKeysButNotOnModifierKeyUp() throws Exception {
        keyboard.sendKeys("Control", "c").get(5, TimeUnit.SECONDS);

        assertEquals(4, eventos.size());
        // keyDown do Control já carrega o próprio bit
        assertEquals(2, evento(0).get("modifiers"));
        // tecla normal com modificador ativo recebe a máscara — sem isso o site
        // veria "c" puro
        assertEquals(2, evento(1).get("modifiers"));
        // keyUp da tecla normal ainda tem a máscara; keyUp do Control já não tem
        assertEquals(2, evento(2).get("modifiers"));
        assertEquals(0, evento(3).getOrDefault("modifiers", 0));
    }

    @Test
    void shortcutHelpersDispatchControlPlusKey() throws Exception {
        keyboard.ctrlV().get(5, TimeUnit.SECONDS);

        assertEquals("Control", evento(0).get("key"));
        assertEquals("v", evento(1).get("key"));
        assertEquals(2, evento(1).get("modifiers"));
    }

    @Test
    void modifierStaysStuckUntilKeyUpArrives() throws Exception {
        keyboard.keyDown("Shift").get(5, TimeUnit.SECONDS);
        keyboard.press("x").get(5, TimeUnit.SECONDS);
        keyboard.keyUp("Shift").get(5, TimeUnit.SECONDS);

        // Shift keyDown carrega bit 8; x (down/char/up) herda 8; keyUp do Shift vai a 0
        assertEquals(8, evento(0).get("modifiers"));
        assertEquals(8, evento(1).get("modifiers"));
        assertEquals(8, evento(2).get("modifiers"));
        assertEquals(8, evento(3).get("modifiers"));
        assertEquals(0, evento(4).getOrDefault("modifiers", 0));
    }

    @Test
    void enterEscapeTabAndEditingHelpersUseSpecialKeyPath() throws Exception {
        keyboard.enter().get(5, TimeUnit.SECONDS);
        keyboard.escape().get(5, TimeUnit.SECONDS);
        keyboard.tab().get(5, TimeUnit.SECONDS);
        keyboard.backspace().get(5, TimeUnit.SECONDS);
        keyboard.delete().get(5, TimeUnit.SECONDS);

        assertEquals("Enter", evento(0).get("key"));
        assertEquals("Escape", evento(2).get("key"));
        assertEquals("Tab", evento(4).get("key"));
        assertEquals("Backspace", evento(6).get("key"));
        assertEquals("Delete", evento(8).get("key"));
    }

    @Test
    void sendKeysReleasesKeysInReverseOrder() throws Exception {
        keyboard.sendKeys("Alt", "Shift", "k").get(5, TimeUnit.SECONDS);

        List<Map<String, Object>> todos = (List) (List<?>) eventos.stream().toList();
        // pressiona Alt, Shift, k — solta k, Shift, Alt
        assertEquals("Alt", todos.get(0).get("key"));
        assertEquals("Shift", todos.get(1).get("key"));
        assertEquals("k", todos.get(2).get("key"));
        assertEquals("k", todos.get(3).get("key"));
        assertEquals("Shift", todos.get(4).get("key"));
        assertEquals("Alt", todos.get(5).get("key"));
    }

    @Test
    void emptySendKeysCompletesWithoutCdpTraffic() throws Exception {
        keyboard.sendKeys().get(5, TimeUnit.SECONDS);

        assertTrue(eventos.isEmpty());
    }
}
