package io.github.selenium.javaDriverless.sync;

import io.github.selenium.javaDriverless.input.Pointer;

/**
 * Versão síncrona (bloqueante) do Pointer.
 */
public class SyncPointer {
    
    private final Pointer asyncPointer;
    
    public SyncPointer(Pointer asyncPointer) {
        this.asyncPointer = asyncPointer;
    }
    
    public void down() {
        asyncPointer.down().join();
    }
    
    public void up() {
        asyncPointer.up().join();
    }
    
    public void click() {
        asyncPointer.click().join();
    }
    
    public void click(Integer x, Integer y, boolean moveTo, double totalTime, 
                     double accel, double smoothSoft) {
        asyncPointer.click(x, y, moveTo, totalTime, accel, smoothSoft).join();
    }
    
    public void doubleClick(int x, int y, Double timeout) {
        asyncPointer.doubleClick(x, y, timeout).join();
    }
    
    public void moveTo(int x, int y, double totalTime, double accel, double smoothSoft) {
        asyncPointer.moveTo(x, y, totalTime, accel, smoothSoft).join();
    }
    
    public void moveTo(int x, int y) {
        moveTo(x, y, 0.5, 2.0, 20.0);
    }
    
    public void scroll(int deltaX, int deltaY) {
        asyncPointer.scroll(deltaX, deltaY).join();
    }
    
    public int[] getLocation() {
        return asyncPointer.getLocation();
    }
}

