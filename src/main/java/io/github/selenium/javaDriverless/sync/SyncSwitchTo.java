package io.github.selenium.javaDriverless.sync;

import io.github.selenium.javaDriverless.scripts.SwitchTo;
import io.github.selenium.javaDriverless.types.Target;

/**
 * Versão síncrona (bloqueante) do SwitchTo.
 */
public class SyncSwitchTo {
    
    private final SwitchTo asyncSwitchTo;
    
    public SyncSwitchTo(SwitchTo asyncSwitchTo) {
        this.asyncSwitchTo = asyncSwitchTo;
    }
    
    public SyncAlert alert() {
        return new SyncAlert(asyncSwitchTo.alert().join());
    }
    
    public SyncAlert getAlert(float timeout) {
        return new SyncAlert(asyncSwitchTo.getAlert(timeout).join());
    }
    
    public SyncTarget defaultContent(boolean activate) {
        Target target = asyncSwitchTo.defaultContent(activate).join();
        return new SyncTarget(target);
    }
    
    public SyncTarget frame(Object frameReference, boolean focus) {
        Target target = asyncSwitchTo.frame(frameReference, focus).join();
        return new SyncTarget(target);
    }
    
    public SyncTarget target(Object targetId, boolean activate, boolean focus) {
        Target target = asyncSwitchTo.target(targetId, activate, focus).join();
        return new SyncTarget(target);
    }
    
    public SyncTarget window(String windowId, boolean activate, boolean focus) {
        Target target = asyncSwitchTo.window(windowId, activate, focus).join();
        return new SyncTarget(target);
    }
}

