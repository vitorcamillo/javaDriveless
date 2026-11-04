package io.github.selenium.javaDriverless.types;

import java.util.Map;
import java.util.concurrent.CompletableFuture;

final class WebElementInteractions {
    private WebElementInteractions() {
    }

    static CompletableFuture<Void> click(WebElement element, boolean moveTo, double totalTime,
                                         double accel, double smoothSoft) {
        element.checkStale();

        return element.getMidLocation().thenCompose(coords -> {
            int x = (int) coords[0];
            int y = (int) coords[1];

            var pointer = element.target.getPointer();
            return pointer.click(x, y, moveTo, totalTime, accel, smoothSoft);
        });
    }

    static CompletableFuture<Void> sendKeys(WebElement element, String keys) {
        element.checkStale();

        return element.click(true, 0.3, 2.0, 20.0)
            .thenCompose(v -> {
                CompletableFuture<Void> chain = CompletableFuture.completedFuture(null);

                for (char c : keys.toCharArray()) {
                    chain = chain.thenCompose(ignored -> sendSingleKey(element, c));
                }

                return chain;
            });
    }

    static CompletableFuture<Void> clear(WebElement element) {
        element.checkStale();
        // Nativo por eventos REAIS (zero JS): foca o campo (DOM.focus), seleciona tudo (Ctrl+A) e
        // apaga (Delete) via Input.dispatchKeyEvent — dispara keydown/input/keyup autênticos, melhor
        // que value='' sintético do JS antigo. Não existe CDP 'setValue'.
        return element.getNodeId().thenCompose(nodeId ->
                element.target.executeCdpCmd("DOM.focus", Map.of("nodeId", nodeId), 5f))
            .thenCompose(v -> pressKey(element, "a", 65, 2))        // Ctrl+A (modifiers=2)
            .thenCompose(v -> pressKey(element, "Delete", 46, 0));  // Delete
    }

    static CompletableFuture<Void> moveTo(WebElement element, Float timeout) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                Map<String, Object> location = element.getMidLocation(1.0f, 1.0f, 0.5f, 0.5f, 10.0f).join();
                double x = (Double) location.get("x");
                double y = (Double) location.get("y");

                element.target.getPointer().moveTo((int) x, (int) y).join();
                return null;
            } catch (Exception e) {
                throw new RuntimeException("Erro ao mover mouse: " + e.getMessage(), e);
            }
        });
    }

    static CompletableFuture<Void> write(WebElement element, String text) {
        return element.target.sendKeys(text);
    }

    static CompletableFuture<Void> submit(WebElement element) {
        element.checkStale();
        // Zero JS: foca (DOM.focus) e pressiona Enter (Input.dispatchKeyEvent). Submete forms de input
        // único com botão default, disparando o evento 'submit' REAL. NÃO é HTMLFormElement.submit()
        // (que pula validação e o evento submit) — para submit garantido, clique o botão fisicamente.
        return element.getNodeId().thenCompose(nodeId ->
                element.target.executeCdpCmd("DOM.focus", Map.of("nodeId", nodeId), 5f))
            .thenCompose(v -> pressKey(element, "Enter", 13, 0));
    }

    // Tecla de controle (Ctrl+A, Delete, Enter...) via Input.dispatchKeyEvent nativo — keyDown+keyUp
    // com windowsVirtualKeyCode e modifiers, como Target.sendKeys faz para chars.
    private static CompletableFuture<Void> pressKey(WebElement element, String key, int vk, int modifiers) {
        Map<String, Object> down = Map.of("type", "keyDown", "key", key,
            "windowsVirtualKeyCode", vk, "nativeVirtualKeyCode", vk, "modifiers", modifiers);
        Map<String, Object> up = Map.of("type", "keyUp", "key", key,
            "windowsVirtualKeyCode", vk, "nativeVirtualKeyCode", vk, "modifiers", modifiers);
        return element.target.executeCdpCmd("Input.dispatchKeyEvent", down, null)
            .thenCompose(v -> element.target.executeCdpCmd("Input.dispatchKeyEvent", up, null))
            .thenApply(v -> null);
    }

    private static CompletableFuture<Void> sendSingleKey(WebElement element, char key) {
        String keyStr = String.valueOf(key);
        Map<String, Object> downArgs = CdpKeyboardEventArgs.keyDown(keyStr);
        Map<String, Object> charArgs = CdpKeyboardEventArgs.charEvent(keyStr);
        Map<String, Object> upArgs = CdpKeyboardEventArgs.keyUp(keyStr);

        return element.target.executeCdpCmd("Input.dispatchKeyEvent", downArgs, null)
            .thenCompose(v -> element.target.executeCdpCmd("Input.dispatchKeyEvent", charArgs, null))
            .thenCompose(v -> {
                try { Thread.sleep(30 + (long)(Math.random() * 50)); } catch (InterruptedException ignored) {}
                return element.target.executeCdpCmd("Input.dispatchKeyEvent", upArgs, null);
            })
            .thenApply(v -> null);
    }
}
