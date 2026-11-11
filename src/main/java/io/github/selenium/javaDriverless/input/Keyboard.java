package io.github.selenium.javaDriverless.input;

import io.github.selenium.javaDriverless.types.Target;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Classe para controle avançado de teclado
 */
public class Keyboard {
    private final Target target;

    /**
     * Mascara acumulada de modificadores pressionados nesta instancia
     * (Alt=1, Ctrl=2, Meta=4, Shift=8), como o CDP espera em "modifiers".
     * Estado por instancia: encadeios como {@code sendKeys("Control", "c")}
     * compartilham o mesmo Keyboard; instancias distintas para o mesmo target
     * nao compartilham modificadores (limitacao conhecida — reutilize a
     * instancia para combinacoes). AtomicInteger: escritas concorrentes nao
     * corrompem o estado; a ordem de eventos de chamadas sobrepostas continua
     * nao deterministica, como ja era antes do estado existir.
     */
    private final AtomicInteger modifierMask = new AtomicInteger();

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
        // Caracteres de 1 caractere (incluindo espaco) precisam do evento
        // "char" com text para inserir texto; teclas especiais apenas keyDown/keyUp.
        String cdpKey = convertKey(key);
        boolean isPrintableChar = cdpKey != null && cdpKey.length() == 1;

        if (isPrintableChar) {
            // Caracteres normais: keyDown + char + keyUp
            return sendRawKeyEvent("keyDown", key)
                .thenCompose(v -> sendKeyEvent("char", cdpKey, cdpKey))
                .thenCompose(v -> sendRawKeyEvent("keyUp", key));
        } else {
            // Teclas especiais: apenas keyDown + keyUp (sem text)
            return sendRawKeyEvent("keyDown", key)
                .thenCompose(v -> sendRawKeyEvent("keyUp", key));
        }
    }

    /**
     * Digita texto caractere por caractere com ritmo humano (default).
     * Cada tecla sai via CDP ({@code Input.dispatchKeyEvent}), portanto com
     * {@code isTrusted=true}, e o intervalo entre teclas varia (~60-150ms) com
     * micro-pausas ocasionais de "pensamento" — em vez de um delay fixo, que é
     * cadência de bot. Para delay fixo/determinístico use {@link #type(String, int)}.
     */
    public CompletableFuture<Void> type(String text) {
        return typeHumanized(text);
    }

    /**
     * Digita texto com delay FIXO entre caracteres (determinístico).
     * Útil para testes ou quando se quer cadência constante. Para comportamento
     * humano (default), use {@link #type(String)} / {@link #typeHumanized(String)}.
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
     * Digita texto com ritmo humano: intervalo variável por caractere (~60-150ms)
     * e micro-pausas ocasionais. É o comportamento padrão de {@link #type(String)}.
     */
    public CompletableFuture<Void> typeHumanized(String text) {
        CompletableFuture<Void> future = CompletableFuture.completedFuture(null);

        for (char c : text.toCharArray()) {
            String key = String.valueOf(c);
            int d = humanKeyDelayMs();
            future = future
                .thenCompose(v -> press(key))
                .thenCompose(v -> delay(d));
        }

        return future;
    }

    /**
     * Intervalo humanizado (ms) entre teclas: base 60-149ms com ~8% de chance de
     * uma micro-pausa adicional (150-369ms), simulando hesitação humana.
     */
    private static int humanKeyDelayMs() {
        java.util.concurrent.ThreadLocalRandom r = java.util.concurrent.ThreadLocalRandom.current();
        int d = 60 + r.nextInt(90);
        if (r.nextInt(100) < 8) {
            d += 150 + r.nextInt(220);
        }
        return d;
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

        int mask = previewMask(type, cdpKey);
        if (mask != 0) {
            params.put("modifiers", mask);
        }

        return target.executeCdpCmd("Input.dispatchKeyEvent", params, 5.0f)
            .thenApply(result -> {
                commitMask(type, cdpKey);
                return null;
            });
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

        int mask = previewMask(type, cdpKey);
        if (mask != 0) {
            params.put("modifiers", mask);
        }

        return target.executeCdpCmd("Input.dispatchKeyEvent", params, 5.0f)
            .thenApply(result -> {
                commitMask(type, cdpKey);
                return null;
            });
    }

    /**
     * Compoem a mascara que o evento carrega: o keyDown do proprio modificador
     * ja conta, o keyUp nao carrega mais o bit (algoritmo UI Events e Puppeteer
     * removem o modificador antes de construir o keyup). Teclas normais com
     * modificadores ativos recebem a mascara — sem isso, atalhos como Ctrl+C
     * chegariam ao site como "c" puro.
     */
    private int previewMask(String type, String cdpKey) {
        int current = modifierMask.get();
        int bit = isModifier(cdpKey) ? getModifierValue(cdpKey) : 0;
        if (bit == 0) {
            return current;
        }
        if ("keyDown".equals(type)) {
            return current | bit;
        }
        if ("keyUp".equals(type)) {
            return current & ~bit;
        }
        return current;
    }

    /**
     * Persiste o estado so quando o CDP aceitou o evento: dispatch rejeitado
     * nao deixa modificador preso na instancia. Nuance: em timeout o comando
     * pode ter sido processado mesmo assim — sem resposta do CDP nao ha
     * verdade, e o estado conservador e manter o bit anterior.
     */
    private void commitMask(String type, String cdpKey) {
        if (!isModifier(cdpKey)) {
            return;
        }
        int bit = getModifierValue(cdpKey);
        if ("keyDown".equals(type)) {
            modifierMask.accumulateAndGet(bit, (mask, b) -> mask | b);
        } else if ("keyUp".equals(type)) {
            modifierMask.accumulateAndGet(bit, (mask, b) -> mask & ~b);
        }
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
        // delayedExecutor: a espera não ocupa thread do ForkJoinPool comum.
        return CompletableFuture.runAsync(() -> { }, CompletableFuture.delayedExecutor(
                milliseconds, java.util.concurrent.TimeUnit.MILLISECONDS));
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

