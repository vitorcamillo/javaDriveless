package io.github.selenium.javaDriverless.input;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import io.github.selenium.javaDriverless.scripts.Geometry;

import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.function.Function;

/**
 * Gerenciamento de eventos de ponteiro (mouse) com movimentos humanizados.
 */
public class Pointer {
    
    private static final ObjectMapper objectMapper = new ObjectMapper();
    
    /**
     * Modificadores de teclado.
     */
    public static class Modifiers {
        public static final int NONE = 0;
        public static final int ALT = 1;
        public static final int CTRL = 2;
        public static final int COMMAND = 4;
        public static final int SHIFT = 8;
    }
    
    /**
     * Tipos de ponteiro.
     */
    public static class PointerType {
        public static final String MOUSE = "mouse";
        public static final String PEN = "pen";
    }
    
    /**
     * Botões do mouse.
     */
    public static class MouseButton {
        public static final String NONE = "none";
        public static final String LEFT = "left";
        public static final String MIDDLE = "middle";
        public static final String RIGHT = "right";
        public static final String BACK = "back";
        public static final String FORWARD = "forward";
    }
    
    /**
     * Modificadores de botão do mouse.
     */
    public static class Buttons {
        public static final Integer NONE = 0;
        public static final Integer LEFT = 1;
        public static final Integer RIGHT = 2;
        public static final Integer MIDDLE = 4;
        public static final Integer BACK = 8;
        public static final Integer FORWARD = 16;
        public static final Integer DEFAULT = null;
    }
    
    /**
     * Tipos de evento.
     */
    public static class EventType {
        public static final String PRESS = "mousePressed";
        public static final String RELEASE = "mouseReleased";
        public static final String MOVE = "mouseMoved";
        public static final String WHEEL = "mouseWheel";
    }
    
    /**
     * Evento de ponteiro para CDP.
     */
    public static class PointerEvent {
        private final String command = "Input.dispatchMouseEvent";
        
        public String type;
        public int x;
        public int y;
        public int modifiers = Modifiers.NONE;
        public Double timestamp;
        public String button = MouseButton.LEFT;
        public Integer buttons = Buttons.DEFAULT;
        public int clickCount = 0;
        public double force = 0;
        public double tangentialPressure = 0;
        public double tiltX = 0;
        public double tiltY = 0;
        public double twist = 0;
        public int deltaX = 0;
        public int deltaY = 0;
        public String pointerType = PointerType.MOUSE;
        
        public PointerEvent(String type, int x, int y) {
            this.type = type;
            this.x = x;
            this.y = y;
        }
        
        /**
         * Converte o evento para comando CDP.
         *
         * @return array [comando, parâmetros]
         */
        public Object[] toJson() {
            ObjectNode json = objectMapper.createObjectNode();
            json.put("type", type);
            json.put("x", x);
            json.put("y", y);
            json.put("modifiers", modifiers);
            json.put("button", button);
            json.put("clickCount", clickCount);
            json.put("force", force);
            json.put("tangentialPressure", tangentialPressure);
            json.put("tiltX", tiltX);
            json.put("tiltY", tiltY);
            json.put("twist", twist);
            json.put("deltaX", deltaX);
            json.put("deltaY", deltaY);
            json.put("pointerType", pointerType);
            
            if (timestamp != null) {
                json.put("timestamp", timestamp);
            }
            if (buttons != null) {
                json.put("buttons", buttons);
            }
            
            return new Object[]{command, json};
        }
    }
    
    /**
     * Gera um timeout aleatório para cliques humanizados.
     *
     * @return timeout em segundos (~130ms +/- 50)
     */
    public static double makeRandClickTimeout() {
        return 0.125 + (Geometry.bias0Dot5(0.5, 0.5) - 0.5) / 10;
    }
    
    private final Object target;
    private final String pointerType;
    private int[] location = {100, 0};
    
    /**
     * Cria um novo ponteiro para um target.
     *
     * @param target target do Chrome (deve ter método executeCdpCmd)
     * @param pointerType tipo de ponteiro (padrão: MOUSE)
     */
    public Pointer(Object target, String pointerType) {
        this.target = target;
        this.pointerType = pointerType;
    }
    
    /**
     * Cria um novo ponteiro com tipo padrão (mouse).
     *
     * @param target target do Chrome
     */
    public Pointer(Object target) {
        this(target, PointerType.MOUSE);
    }
    
    /**
     * Retorna a localização atual do ponteiro.
     *
     * @return array [x, y]
     */
    public int[] getLocation() {
        return location.clone();
    }
    
    /**
     * Despacha um evento de ponteiro via CDP.
     *
     * @param event evento a despachar
     * @return CompletableFuture que completa quando o evento é despachado
     */
    private CompletableFuture<Void> dispatch(PointerEvent event) {
        Object[] cdpCmd = event.toJson();
        
        try {
            // Usar reflexão para chamar executeCdpCmd no target
            var method = target.getClass().getMethod("executeCdpCmd", String.class, Map.class, Float.class);
            
            @SuppressWarnings("unchecked")
            var paramsNode = (ObjectNode) cdpCmd[1];
            Map<String, Object> params = objectMapper.convertValue(paramsNode, Map.class);
            
            @SuppressWarnings("unchecked")
            CompletableFuture<Object> result = (CompletableFuture<Object>) method.invoke(
                target, 
                cdpCmd[0], 
                params, 
                null
            );
            
            return result.thenApply(r -> null);
            
        } catch (Exception e) {
            return CompletableFuture.failedFuture(e);
        }
    }
    
    /**
     * Pressiona o botão do mouse.
     *
     * @param x coordenada x
     * @param y coordenada y
     * @param button botão a pressionar
     * @param clickCount contagem de cliques
     * @return CompletableFuture que completa quando a ação termina
     */
    public CompletableFuture<Void> down(int x, int y, String button, int clickCount) {
        PointerEvent event = new PointerEvent(EventType.PRESS, x, y);
        event.button = button;
        event.clickCount = clickCount;
        return dispatch(event);
    }
    
    /**
     * Pressiona o botão do mouse na posição atual.
     *
     * @return CompletableFuture que completa quando a ação termina
     */
    public CompletableFuture<Void> down() {
        return down(location[0], location[1], MouseButton.LEFT, 1);
    }
    
    /**
     * Solta o botão do mouse.
     *
     * @param x coordenada x
     * @param y coordenada y
     * @param button botão a soltar
     * @param clickCount contagem de cliques
     * @return CompletableFuture que completa quando a ação termina
     */
    public CompletableFuture<Void> up(int x, int y, String button, int clickCount) {
        PointerEvent event = new PointerEvent(EventType.RELEASE, x, y);
        event.button = button;
        event.clickCount = clickCount;
        return dispatch(event);
    }
    
    /**
     * Solta o botão do mouse na posição atual.
     *
     * @return CompletableFuture que completa quando a ação termina
     */
    public CompletableFuture<Void> up() {
        return up(location[0], location[1], MouseButton.LEFT, 1);
    }
    
    /**
     * Clica em uma posição.
     *
     * @param x coordenada x
     * @param y coordenada y
     * @param timeout tempo entre down e up (segundos)
     * @param button botão a clicar
     * @return CompletableFuture que completa quando a ação termina
     */
    public CompletableFuture<Void> click(int x, int y, Double timeout, String button) {
        double effectiveTimeout = (timeout != null) ? timeout : makeRandClickTimeout();
        
        return down(x, y, button, 1)
            .thenCompose(v -> CompletableFuture.runAsync(() -> {
                try {
                    Thread.sleep((long) (effectiveTimeout * 1000));
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }))
            .thenCompose(v -> up(x, y, button, 1));
    }
    
    /**
     * Clica na posição atual.
     *
     * @return CompletableFuture que completa quando a ação termina
     */
    public CompletableFuture<Void> click() {
        return click(location[0], location[1], null, MouseButton.LEFT);
    }
    
    /**
     * Clica em uma posição com movimento opcional.
     *
     * @param x coordenada x (null para usar posição atual)
     * @param y coordenada y
     * @param moveTo se deve mover o ponteiro antes de clicar
     * @param totalTime tempo total de movimento (segundos)
     * @param accel fator de aceleração
     * @param smoothSoft suavidade da curva
     * @return CompletableFuture que completa quando a ação termina
     */
    public CompletableFuture<Void> click(Integer x, Integer y, boolean moveTo,
                                        double totalTime, double accel, double smoothSoft) {
        int targetX = (x != null) ? x : location[0];
        int targetY = (y != null) ? y : location[1];
        
        if (moveTo) {
            return moveTo(targetX, targetY, totalTime, accel, smoothSoft)
                .thenCompose(v -> click(targetX, targetY, null, MouseButton.LEFT));
        } else {
            return click(targetX, targetY, null, MouseButton.LEFT);
        }
    }
    
    /**
     * Clica duas vezes.
     *
     * @param x coordenada x
     * @param y coordenada y
     * @param timeout tempo entre cliques (segundos)
     * @return CompletableFuture que completa quando a ação termina
     */
    public CompletableFuture<Void> doubleClick(int x, int y, Double timeout) {
        double effectiveTimeout = (timeout != null) ? timeout : makeRandClickTimeout();
        
        return click(x, y, effectiveTimeout, MouseButton.LEFT)
            .thenCompose(v -> CompletableFuture.runAsync(() -> {
                try {
                    Thread.sleep((long) (effectiveTimeout * 1000));
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }))
            .thenCompose(v -> down(x, y, MouseButton.LEFT, 2))
            .thenCompose(v -> CompletableFuture.runAsync(() -> {
                try {
                    Thread.sleep((long) (effectiveTimeout * 1000));
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }))
            .thenCompose(v -> up(x, y, MouseButton.LEFT, 2));
    }
    
    /**
     * Move o ponteiro para uma posição específica.
     *
     * @param x coordenada x
     * @param y coordenada y
     * @return CompletableFuture que completa quando a ação termina
     */
    private CompletableFuture<Void> moveToRaw(int x, int y) {
        PointerEvent event = new PointerEvent(EventType.MOVE, x, y);
        return dispatch(event);
    }
    
    /**
     * Move o ponteiro ao longo de um caminho com aceleração.
     *
     * @param totalTime tempo total do movimento (segundos)
     * @param posFromTimeCallback função que retorna [x,y] para um dado tempo
     * @param freqAssumption frequência assumida de eventos (Hz)
     * @return CompletableFuture que completa quando o movimento termina
     */
    private CompletableFuture<int[]> movePath(double totalTime, 
                                             Function<Double, int[]> posFromTimeCallback,
                                             double freqAssumption) {
        return CompletableFuture.supplyAsync(() -> {
            long startNanos = System.nanoTime();
            int[] lastPos = null;
            
            while (true) {
                double elapsed = (System.nanoTime() - startNanos) / 1_000_000_000.0;
                
                if (elapsed > totalTime) {
                    return lastPos;
                }
                
                int[] pos = posFromTimeCallback.apply(elapsed);
                moveToRaw(pos[0], pos[1]).join();
                lastPos = pos;
                
                // Aguardar próximo frame
                try {
                    long sleepMs = (long) ((1.0 / freqAssumption) * 1000);
                    Thread.sleep(sleepMs);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return lastPos;
                }
            }
        });
    }
    
    /**
     * Move o ponteiro para uma coordenada com movimento humanizado.
     *
     * @param x coordenada x de destino
     * @param y coordenada y de destino
     * @param totalTime tempo total do movimento (segundos, padrão 0.5)
     * @param accel fator de aceleração (padrão 2)
     * @param smoothSoft suavidade da curva (padrão 20)
     * @return CompletableFuture que completa quando o movimento termina
     */
    public CompletableFuture<Void> moveTo(int x, int y, double totalTime, double accel, double smoothSoft) {
        if (location[0] == x && location[1] == y) {
            return CompletableFuture.completedFuture(null);
        }
        
        double midTime = Geometry.bias0Dot5(0.5, 0.3);
        
        // Gerar caminho humanizado
        List<double[]> points = new ArrayList<>();
        points.add(new double[]{location[0], location[1]});
        points.add(new double[]{x, y});
        
        List<int[]> path = Geometry.genCombinedPath(
            points, 
            5,           // n_points_soft
            smoothSoft,  // smooth_soft
            100,         // n_points_distort
            0.4          // smooth_distort
        );
        
        Function<Double, int[]> posCallback = time -> 
            Geometry.posAtTime(path, totalTime, time, accel, midTime);
        
        return movePath(totalTime, posCallback, 60.0)
            .thenAccept(finalPos -> {
                this.location = new int[]{x, y};
            });
    }
    
    /**
     * Move o ponteiro para uma coordenada com parâmetros padrão.
     *
     * @param x coordenada x de destino
     * @param y coordenada y de destino
     * @return CompletableFuture que completa quando o movimento termina
     */
    public CompletableFuture<Void> moveTo(int x, int y) {
        return moveTo(x, y, 0.5, 2.0, 20.0);
    }
    
    /**
     * Rola a página.
     *
     * @param deltaX delta horizontal
     * @param deltaY delta vertical
     * @return CompletableFuture que completa quando a ação termina
     */
    public CompletableFuture<Void> scroll(int deltaX, int deltaY) {
        PointerEvent event = new PointerEvent(EventType.WHEEL, location[0], location[1]);
        event.deltaX = deltaX;
        event.deltaY = deltaY;
        return dispatch(event);
    }
    
    /**
     * Realiza um duplo clique na posição atual ou em coordenadas específicas.
     *
     * @param x coordenada X (ou -1 para usar posição atual)
     * @param y coordenada Y (ou -1 para usar posição atual)
     * @return CompletableFuture que completa quando a ação termina
     */
    public CompletableFuture<Void> doubleClick(int x, int y) {
        int targetX = x >= 0 ? x : location[0];
        int targetY = y >= 0 ? y : location[1];
        
        return down(targetX, targetY, MouseButton.LEFT, 1)
            .thenCompose(v -> up(targetX, targetY, MouseButton.LEFT, 1))
            .thenCompose(v -> {
                try {
                    Thread.sleep(50); // Pequeno delay entre clicks
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                return down(targetX, targetY, MouseButton.LEFT, 2);
            })
            .thenCompose(v -> up(targetX, targetY, MouseButton.LEFT, 2));
    }
    
    /**
     * Duplo clique na posição atual.
     *
     * @return CompletableFuture que completa quando a ação termina
     */
    public CompletableFuture<Void> doubleClick() {
        return doubleClick(-1, -1);
    }
    
    /**
     * Clique com botão direito (context menu).
     *
     * @param x coordenada X (ou -1 para usar posição atual)
     * @param y coordenada Y (ou -1 para usar posição atual)
     * @return CompletableFuture que completa quando a ação termina
     */
    public CompletableFuture<Void> contextClick(int x, int y) {
        int targetX = x >= 0 ? x : location[0];
        int targetY = y >= 0 ? y : location[1];
        
        return down(targetX, targetY, MouseButton.RIGHT, 1)
            .thenCompose(v -> up(targetX, targetY, MouseButton.RIGHT, 1));
    }
    
    /**
     * Clique com botão direito na posição atual.
     *
     * @return CompletableFuture que completa quando a ação termina
     */
    public CompletableFuture<Void> contextClick() {
        return contextClick(-1, -1);
    }
    
    /**
     * Arrasta e solta de uma posição para outra.
     *
     * @param fromX coordenada X inicial
     * @param fromY coordenada Y inicial
     * @param toX coordenada X final
     * @param toY coordenada Y final
     * @param totalTime tempo total do movimento em segundos
     * @return CompletableFuture que completa quando a ação termina
     */
    public CompletableFuture<Void> dragAndDrop(int fromX, int fromY, int toX, int toY, double totalTime) {
        return moveTo(fromX, fromY, totalTime / 2, 2.0, 20.0)
            .thenCompose(v -> down(fromX, fromY, MouseButton.LEFT, 1))
            .thenCompose(v -> {
                try {
                    Thread.sleep(100); // Pequeno delay após pressionar
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                return moveTo(toX, toY, totalTime / 2, 2.0, 20.0);
            })
            .thenCompose(v -> {
                try {
                    Thread.sleep(100); // Pequeno delay antes de soltar
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                return up(toX, toY, MouseButton.LEFT, 1);
            });
    }
    
    /**
     * Arrasta e solta (versão simplificada com tempo padrão).
     *
     * @param fromX coordenada X inicial
     * @param fromY coordenada Y inicial
     * @param toX coordenada X final
     * @param toY coordenada Y final
     * @return CompletableFuture que completa quando a ação termina
     */
    public CompletableFuture<Void> dragAndDrop(int fromX, int fromY, int toX, int toY) {
        return dragAndDrop(fromX, fromY, toX, toY, 1.0);
    }
}

