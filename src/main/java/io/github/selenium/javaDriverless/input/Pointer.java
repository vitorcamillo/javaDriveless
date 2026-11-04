package io.github.selenium.javaDriverless.input;

import io.github.selenium.javaDriverless.cdp.CDPCommandExecutor;
import io.github.selenium.javaDriverless.scripts.Geometry;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.function.Function;

/**
 * Gerenciamento de eventos de ponteiro (mouse) com movimentos humanizados.
 */
public class Pointer {

    /**
     * Modificadores de teclado.
     */
    public static class Modifiers extends PointerModifiers {}

    /**
     * Tipos de ponteiro.
     */
    public static class PointerType extends PointerTypes {}

    /**
     * Botões do mouse.
     */
    public static class MouseButton extends PointerMouseButtons {}

    /**
     * Modificadores de botão do mouse.
     */
    public static class Buttons extends PointerButtonMasks {}

    /**
     * Tipos de evento.
     */
    public static class EventType extends PointerEventTypes {}

    /**
     * Evento de ponteiro para CDP.
     */
    public static class PointerEvent extends PointerEventData {
        public PointerEvent(String type, int x, int y) {
            super(type, x, y);
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

    /**
     * Raio padrão (px) do jitter aplicado à coordenada de pouso do clique.
     * Humanos não acertam o pixel central exato; ligado por padrão. 0 desativa.
     */
    public static final int DEFAULT_CLICK_JITTER = 3;

    private final CDPCommandExecutor target;
    private final String pointerType;
    private int[] location = {100, 0};
    private int pressedButtons = Buttons.NONE;
    /** Teto do raio de jitter: evita overflow em {@code 2 * r + 1}. */
    static final int MAX_CLICK_JITTER = 10_000;
    private int clickJitterRadius = DEFAULT_CLICK_JITTER;

    /**
     * Cria um novo ponteiro para um target.
     *
     * @param target executor de comandos CDP (Target, BaseTarget, etc.)
     * @param pointerType tipo de ponteiro (padrão: MOUSE)
     */
    public Pointer(CDPCommandExecutor target, String pointerType) {
        this.target = target;
        this.pointerType = pointerType;
    }

    /**
     * Cria um novo ponteiro com tipo padrão (mouse).
     *
     * @param target executor de comandos CDP
     */
    public Pointer(CDPCommandExecutor target) {
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
     * Ajusta o raio (px) do jitter aplicado à coordenada de pouso dos cliques.
     * Por padrão é {@value #DEFAULT_CLICK_JITTER}px (humanos não clicam no pixel
     * central exato). Passe 0 para clicar na coordenada exata (ex.: testes).
     *
     * @param radiusPx raio em pixels (negativos são tratados como 0)
     */
    public void setClickJitter(int radiusPx) {
        this.clickJitterRadius = Math.max(0, Math.min(radiusPx, MAX_CLICK_JITTER));
    }

    /**
     * @return raio atual (px) do jitter de pouso do clique
     */
    public int getClickJitter() {
        return clickJitterRadius;
    }

    /**
     * Aplica um deslocamento aleatório de até {@code clickJitterRadius} px em cada
     * eixo. Retorna a coordenada original quando o jitter está desativado.
     */
    private int[] jitterPoint(int x, int y) {
        int r = clickJitterRadius;
        if (r <= 0) {
            return new int[]{x, y};
        }
        java.util.concurrent.ThreadLocalRandom rnd = java.util.concurrent.ThreadLocalRandom.current();
        return new int[]{x + rnd.nextInt(2 * r + 1) - r, y + rnd.nextInt(2 * r + 1) - r};
    }

    /**
     * Despacha um evento de ponteiro via CDP.
     *
     * @param event evento a despachar
     * @return CompletableFuture que completa quando o evento é despachado
     */
    private CompletableFuture<Void> dispatch(PointerEvent event) {
        Object[] cdpCmd = event.toJson();
        String command = (String) cdpCmd[0];
        Map<String, Object> params = PointerEventData.toMap(cdpCmd[1]);

        return target.executeCdpCmd(command, params, null)
            .thenApply(r -> null);
    }

    private PointerEvent newEvent(String type, int x, int y) {
        PointerEvent event = new PointerEvent(type, x, y);
        event.pointerType = pointerType;
        return event;
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
        PointerEvent event = newEvent(EventType.PRESS, x, y);
        int nextButtons = pressedButtons | PointerButtonMasks.forButton(button);
        event.button = button;
        event.buttons = nextButtons;
        event.clickCount = clickCount;
        return dispatch(event).thenRun(() -> pressedButtons = nextButtons);
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
        PointerEvent event = newEvent(EventType.RELEASE, x, y);
        int nextButtons = pressedButtons & ~PointerButtonMasks.forButton(button);
        event.button = button;
        event.buttons = nextButtons;
        event.clickCount = clickCount;
        return dispatch(event).thenRun(() -> pressedButtons = nextButtons);
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

        // Pouso humano: desloca a coordenada em ±clickJitterRadius px (default ligado). Humanos
        // não acertam o pixel central exato repetidamente — cliques sempre no mesmo pixel são
        // assinatura de bot. Calculado UMA vez para que move/down/up caiam no mesmo ponto.
        int[] p = jitterPoint(x, y);
        int jx = p[0], jy = p[1];

        // Movimento humanizado (Bézier) até o alvo ANTES do down/up. Sem isto o clique "teleporta":
        // mousedown/mouseup materializam numa coordenada sem nenhum mousemove prévio — assinatura
        // clássica de bot que antifraudes (ex.: bet365 Cashmere) detectam. moveTo é no-op se o
        // ponteiro já estiver no ponto, então cliques repetidos no mesmo lugar não pagam o custo.
        return moveTo(jx, jy)
            .thenCompose(v -> down(jx, jy, button, 1))
            .thenCompose(v -> PointerTiming.sleepSeconds(effectiveTimeout))
            .thenCompose(v -> up(jx, jy, button, 1));
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

        // O 1º clique já aplicou jitter e atualizou location; o 2º clique reusa esse mesmo
        // ponto de pouso (location) para não teleportar nem cair num pixel diferente.
        return click(x, y, effectiveTimeout, MouseButton.LEFT)
            .thenCompose(v -> PointerTiming.sleepSeconds(effectiveTimeout))
            .thenCompose(v -> down(location[0], location[1], MouseButton.LEFT, 2))
            .thenCompose(v -> PointerTiming.sleepSeconds(effectiveTimeout))
            .thenCompose(v -> up(location[0], location[1], MouseButton.LEFT, 2));
    }

    /**
     * Move o ponteiro para uma posição específica.
     *
     * @param x coordenada x
     * @param y coordenada y
     * @return CompletableFuture que completa quando a ação termina
     */
    private CompletableFuture<Void> moveToRaw(int x, int y) {
        PointerEvent event = newEvent(EventType.MOVE, x, y);
        event.button = MouseButton.NONE;
        event.buttons = pressedButtons;
        return dispatch(event);
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
        List<int[]> path = PointerMotion.humanizedPath(location, x, y, smoothSoft);

        Function<Double, int[]> posCallback = time ->
            Geometry.posAtTime(path, totalTime, time, accel, midTime);

        return PointerMotion.movePath(totalTime, posCallback, 60.0, this::moveToRaw)
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
        PointerEvent event = newEvent(EventType.WHEEL, location[0], location[1]);
        event.button = MouseButton.NONE;
        event.buttons = pressedButtons;
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
        int[] p = jitterPoint(targetX, targetY);
        int jx = p[0], jy = p[1];

        // moveTo humanizado antes do down/up (sem isto o duplo clique teleporta — tell de bot).
        // FIX BUG-13: Usar delayedExecutor em vez de Thread.sleep para não bloquear ForkJoinPool
        return moveTo(jx, jy)
            .thenCompose(v -> down(jx, jy, MouseButton.LEFT, 1))
            .thenCompose(v -> up(jx, jy, MouseButton.LEFT, 1))
            .thenCompose(v -> CompletableFuture.runAsync(() -> {},
                CompletableFuture.delayedExecutor(50, java.util.concurrent.TimeUnit.MILLISECONDS)))
            .thenCompose(v -> down(jx, jy, MouseButton.LEFT, 2))
            .thenCompose(v -> up(jx, jy, MouseButton.LEFT, 2));
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
        int[] p = jitterPoint(targetX, targetY);
        int jx = p[0], jy = p[1];

        // moveTo humanizado antes do down/up (sem isto o context click teleporta — tell de bot).
        return moveTo(jx, jy)
            .thenCompose(v -> down(jx, jy, MouseButton.RIGHT, 1))
            .thenCompose(v -> up(jx, jy, MouseButton.RIGHT, 1));
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
                PointerTiming.sleepMillis(100); // Pequeno delay após pressionar
                return moveTo(toX, toY, totalTime / 2, 2.0, 20.0);
            })
            .thenCompose(v -> {
                PointerTiming.sleepMillis(100); // Pequeno delay antes de soltar
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

