package io.github.selenium.javaDriverless.support;

import io.github.selenium.javaDriverless.Chrome;
import io.github.selenium.javaDriverless.input.Keyboard;
import io.github.selenium.javaDriverless.input.Keyboard.Keys;
import io.github.selenium.javaDriverless.input.Pointer;
import io.github.selenium.javaDriverless.types.WebElement;

import java.util.concurrent.CompletableFuture;

/**
 * Classe Actions para encadear ações de mouse e teclado.
 * Similar à classe Actions do Selenium.
 */
public class Actions {
    private final Chrome driver;
    private final Pointer pointer;
    private final Keyboard keyboard;
    private CompletableFuture<Void> chain = CompletableFuture.completedFuture(null);

    public Actions(Chrome driver) {
        this.driver = driver;
        this.pointer = driver.getCurrentPointer();
        this.keyboard = driver.getCurrentKeyboard();
    }

    /**
     * Move o mouse para as coordenadas especificadas.
     */
    public Actions moveToLocation(int x, int y) {
        chain = chain.thenCompose(v -> pointer.moveTo(x, y, 1.0, 2, 20));
        return this;
    }

    /**
     * Move o mouse para um elemento (centro).
     */
    public Actions moveToElement(WebElement element) {
        chain = chain.thenCompose(v -> {
            try {
                return element.getMidLocation().thenCompose(mid -> {
                    double[] coords = (double[]) mid;
                    return pointer.moveTo((int)coords[0], (int)coords[1], 1.0, 2, 20);
                });
            } catch (Exception e) {
                return CompletableFuture.failedFuture(e);
            }
        });
        return this;
    }

    /**
     * Move o mouse para um elemento com offset.
     */
    public Actions moveToElement(WebElement element, int xOffset, int yOffset) {
        chain = chain.thenCompose(v -> {
            try {
                return element.getMidLocation().thenCompose(mid -> {
                    double[] coords = (double[]) mid;
                    return pointer.moveTo((int)coords[0] + xOffset, (int)coords[1] + yOffset, 1.0, 2, 20);
                });
            } catch (Exception e) {
                return CompletableFuture.failedFuture(e);
            }
        });
        return this;
    }

    /**
     * Clica no elemento.
     */
    public Actions click() {
        chain = chain.thenCompose(v -> pointer.click());
        return this;
    }

    /**
     * Clica em um elemento específico.
     */
    public Actions click(WebElement element) {
        return moveToElement(element).click();
    }

    /**
     * Duplo clique.
     */
    public Actions doubleClick() {
        chain = chain.thenCompose(v -> pointer.doubleClick());
        return this;
    }

    /**
     * Duplo clique em um elemento.
     */
    public Actions doubleClick(WebElement element) {
        return moveToElement(element).doubleClick();
    }

    /**
     * Clique com botão direito.
     */
    public Actions contextClick() {
        chain = chain.thenCompose(v -> pointer.contextClick());
        return this;
    }

    /**
     * Clique com botão direito em um elemento.
     */
    public Actions contextClick(WebElement element) {
        return moveToElement(element).contextClick();
    }

    /**
     * Pressiona uma tecla.
     */
    public Actions keyDown(String key) {
        chain = chain.thenCompose(v -> keyboard.keyDown(key));
        return this;
    }

    /**
     * Solta uma tecla.
     */
    public Actions keyUp(String key) {
        chain = chain.thenCompose(v -> keyboard.keyUp(key));
        return this;
    }

    /**
     * Envia teclas.
     */
    public Actions sendKeys(String... keys) {
        chain = chain.thenCompose(v -> keyboard.sendKeys(keys));
        return this;
    }

    /**
     * Envia teclas para um elemento.
     */
    public Actions sendKeys(WebElement element, String text) {
        return click(element).thenCompose(v -> keyboard.type(text));
    }

    /**
     * Digita texto.
     */
    public Actions type(String text) {
        chain = chain.thenCompose(v -> keyboard.type(text));
        return this;
    }

    /**
     * Arrasta e solta de um elemento para outro.
     */
    public Actions dragAndDrop(WebElement source, WebElement target) {
        chain = chain.thenCompose(v -> {
            try {
                return source.getMidLocation().thenCompose(srcMid -> {
                    return target.getMidLocation().thenCompose(tgtMid -> {
                        double[] srcCoords = (double[]) srcMid;
                        double[] tgtCoords = (double[]) tgtMid;
                        return pointer.dragAndDrop(
                            (int)srcCoords[0], (int)srcCoords[1],
                            (int)tgtCoords[0], (int)tgtCoords[1],
                            2.0
                        );
                    });
                });
            } catch (Exception e) {
                return CompletableFuture.failedFuture(e);
            }
        });
        return this;
    }

    /**
     * Arrasta e solta com coordenadas.
     */
    public Actions dragAndDrop(int fromX, int fromY, int toX, int toY) {
        chain = chain.thenCompose(v -> pointer.dragAndDrop(fromX, fromY, toX, toY, 2.0));
        return this;
    }

    /**
     * Pressiona e segura o botão do mouse.
     */
    public Actions clickAndHold() {
        chain = chain.thenCompose(v -> pointer.down());
        return this;
    }

    /**
     * Solta o botão do mouse.
     */
    public Actions release() {
        chain = chain.thenCompose(v -> pointer.up());
        return this;
    }

    /**
     * Adiciona uma pausa.
     */
    public Actions pause(long millis) {
        chain = chain.thenCompose(v -> CompletableFuture.runAsync(() -> {
            try {
                Thread.sleep(millis);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }));
        return this;
    }

    /**
     * Executa todas as ações encadeadas.
     */
    public void perform() {
        try {
            chain.get();
        } catch (Exception e) {
            throw new RuntimeException("Erro ao executar Actions", e);
        }
    }

    /**
     * Executa e retorna o CompletableFuture.
     */
    public CompletableFuture<Void> performAsync() {
        return chain;
    }

    /**
     * Reseta a cadeia de ações.
     */
    public Actions reset() {
        chain = CompletableFuture.completedFuture(null);
        return this;
    }

    // Métodos helper internos
    private Actions thenCompose(java.util.function.Function<Void, CompletableFuture<Void>> action) {
        chain = chain.thenCompose(action);
        return this;
    }
}

