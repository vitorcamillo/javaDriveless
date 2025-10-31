package io.github.selenium.javaDriverless.input;

import io.github.selenium.javaDriverless.types.Target;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

/**
 * Classe para controle avançado de teclado
 */
public class Keyboard {
    private final Target target;

    public Keyboard(Target target) {
        this.target = target;
    }

    /**
     * Pressiona uma tecla (key down)
     */
    public CompletableFuture<Void> keyDown(String key) {
        return sendRawKeyEvent("keyDown", key);
    }

    /**
     * Solta uma tecla (key up)
     */
    public CompletableFuture<Void> keyUp(String key) {
        return sendRawKeyEvent("keyUp", key);
    }

    /**
     * Pressiona e solta uma tecla
     */
    public CompletableFuture<Void> press(String key) {
        // Caracteres normais: apenas 1 caractere E não é espaço
        boolean isNormalChar = (key != null && key.length() == 1 && !key.equals(" "));
        
        if (isNormalChar) {
            // Caracteres normais: keyDown + char + keyUp
            return sendRawKeyEvent("keyDown", key)
                .thenCompose(v -> sendKeyEvent("char", key, key))
                .thenCompose(v -> sendRawKeyEvent("keyUp", key));
        } else {
            // Teclas especiais: apenas keyDown + keyUp (sem text)
            return sendRawKeyEvent("keyDown", key)
                .thenCompose(v -> sendRawKeyEvent("keyUp", key));
        }
    }

    /**
     * Digita texto caractere por caractere
     */
    public CompletableFuture<Void> type(String text) {
        return type(text, 50);
    }

    /**
     * Digita texto com delay entre caracteres
     */
    public CompletableFuture<Void> type(String text, int delayMs) {
        CompletableFuture<Void> future = CompletableFuture.completedFuture(null);
        
        for (char c : text.toCharArray()) {
            String key = String.valueOf(c);
            future = future
                .thenCompose(v -> press(key))
                .thenCompose(v -> delay(delayMs));
        }
        
        return future;
    }

    /**
     * Envia combinação de teclas (ex: Ctrl+C)
     */
    public CompletableFuture<Void> sendKeys(String... keys) {
        if (keys.length == 0) {
            return CompletableFuture.completedFuture(null);
        }

        // Pressiona todas as teclas
        CompletableFuture<Void> future = CompletableFuture.completedFuture(null);
        for (String key : keys) {
            future = future.thenCompose(v -> keyDown(key));
        }

        // Pequeno delay
        future = future.thenCompose(v -> delay(50));

        // Solta todas as teclas na ordem inversa
        for (int i = keys.length - 1; i >= 0; i--) {
            String key = keys[i];
            future = future.thenCompose(v -> keyUp(key));
        }

        return future;
    }

    /**
     * Atalhos comuns
     */
    public CompletableFuture<Void> ctrlC() {
        return sendKeys("Control", "c");
    }

    public CompletableFuture<Void> ctrlV() {
        return sendKeys("Control", "v");
    }

    public CompletableFuture<Void> ctrlA() {
        return sendKeys("Control", "a");
    }

    public CompletableFuture<Void> ctrlX() {
        return sendKeys("Control", "x");
    }

    public CompletableFuture<Void> ctrlZ() {
        return sendKeys("Control", "z");
    }

    public CompletableFuture<Void> enter() {
        return press("Enter");
    }

    public CompletableFuture<Void> escape() {
        return press("Escape");
    }

    public CompletableFuture<Void> tab() {
        return press("Tab");
    }

    public CompletableFuture<Void> backspace() {
        return press("Backspace");
    }

    public CompletableFuture<Void> delete() {
        return press("Delete");
    }

    /**
     * Envia evento de teclado RAW (sem text) via CDP
     */
    private CompletableFuture<Void> sendRawKeyEvent(String type, String key) {
        Map<String, Object> params = new HashMap<>();
        params.put("type", type);
        
        // Converter teclas especiais
        String cdpKey = convertKey(key);
        params.put("key", cdpKey);

        // Detectar modificadores
        if (isModifier(cdpKey)) {
            params.put("modifiers", getModifierValue(cdpKey));
        }

        return target.executeCdpCmd("Input.dispatchKeyEvent", params, 5.0f)
            .thenApply(result -> null);
    }

    /**
     * Envia evento de teclado via CDP (com text para char)
     */
    private CompletableFuture<Void> sendKeyEvent(String type, String key, String text) {
        Map<String, Object> params = new HashMap<>();
        params.put("type", type);
        
        // Converter teclas especiais
        String cdpKey = convertKey(key);
        params.put("key", cdpKey);
        
        // Para eventos "char", SEMPRE enviar o texto
        if ("char".equals(type)) {
            params.put("text", text != null ? text : key);
            params.put("unmodifiedText", text != null ? text : key);
        }

        // Detectar modificadores
        if (isModifier(cdpKey)) {
            params.put("modifiers", getModifierValue(cdpKey));
        }

        return target.executeCdpCmd("Input.dispatchKeyEvent", params, 5.0f)
            .thenApply(result -> null);
    }

    /**
     * Converte nome da tecla para formato CDP
     */
    private String convertKey(String key) {
        if (key == null || key.isEmpty()) {
            return key;
        }
        
        if (key.length() == 1) {
            return key;
        }

        // Mapeamento de teclas especiais (chave já normalizada)
        Map<String, String> keyMap = new HashMap<>();
        keyMap.put("ENTER", "Enter");
        keyMap.put("TAB", "Tab");
        keyMap.put("ESCAPE", "Escape");
        keyMap.put("ESC", "Escape");
        keyMap.put("BACKSPACE", "Backspace");
        keyMap.put("DELETE", "Delete");
        keyMap.put("SPACE", " ");
        keyMap.put("CONTROL", "Control");
        keyMap.put("CTRL", "Control");
        keyMap.put("ALT", "Alt");
        keyMap.put("SHIFT", "Shift");
        keyMap.put("META", "Meta");
        keyMap.put("COMMAND", "Meta");
        keyMap.put("ARROW_UP", "ArrowUp");
        keyMap.put("ARROW_DOWN", "ArrowDown");
        keyMap.put("ARROW_LEFT", "ArrowLeft");
        keyMap.put("ARROW_RIGHT", "ArrowRight");
        keyMap.put("HOME", "Home");
        keyMap.put("END", "End");
        keyMap.put("PAGE_UP", "PageUp");
        keyMap.put("PAGE_DOWN", "PageDown");
        keyMap.put("F1", "F1");
        keyMap.put("F2", "F2");
        keyMap.put("F3", "F3");
        keyMap.put("F4", "F4");
        keyMap.put("F5", "F5");
        keyMap.put("F6", "F6");
        keyMap.put("F7", "F7");
        keyMap.put("F8", "F8");
        keyMap.put("F9", "F9");
        keyMap.put("F10", "F10");
        keyMap.put("F11", "F11");
        keyMap.put("F12", "F12");

        // Retornar mapeado ou original (já pode estar capitalizado)
        String mappedKey = keyMap.get(key.toUpperCase());
        return mappedKey != null ? mappedKey : key;
    }

    /**
     * Verifica se é tecla modificadora
     */
    private boolean isModifier(String key) {
        return key.equals("Control") || key.equals("Alt") || 
               key.equals("Shift") || key.equals("Meta");
    }

    /**
     * Retorna valor numérico do modificador
     */
    private int getModifierValue(String key) {
        switch (key) {
            case "Alt": return 1;
            case "Control": return 2;
            case "Meta": return 4;
            case "Shift": return 8;
            default: return 0;
        }
    }

    /**
     * Delay helper
     */
    private CompletableFuture<Void> delay(int milliseconds) {
        return CompletableFuture.runAsync(() -> {
            try {
                Thread.sleep(milliseconds);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });
    }

    /**
     * Classe com constantes de teclas para facilitar uso
     */
    public static class Keys {
        public static final String ENTER = "Enter";
        public static final String TAB = "Tab";
        public static final String ESCAPE = "Escape";
        public static final String BACKSPACE = "Backspace";
        public static final String DELETE = "Delete";
        public static final String SPACE = " ";
        public static final String CONTROL = "Control";
        public static final String CTRL = "Control";
        public static final String ALT = "Alt";
        public static final String SHIFT = "Shift";
        public static final String META = "Meta";
        public static final String COMMAND = "Meta";
        public static final String ARROW_UP = "ArrowUp";
        public static final String ARROW_DOWN = "ArrowDown";
        public static final String ARROW_LEFT = "ArrowLeft";
        public static final String ARROW_RIGHT = "ArrowRight";
        public static final String HOME = "Home";
        public static final String END = "End";
        public static final String PAGE_UP = "PageUp";
        public static final String PAGE_DOWN = "PageDown";
        public static final String F1 = "F1";
        public static final String F2 = "F2";
        public static final String F3 = "F3";
        public static final String F4 = "F4";
        public static final String F5 = "F5";
        public static final String F6 = "F6";
        public static final String F7 = "F7";
        public static final String F8 = "F8";
        public static final String F9 = "F9";
        public static final String F10 = "F10";
        public static final String F11 = "F11";
        public static final String F12 = "F12";
    }
}

