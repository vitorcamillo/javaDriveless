package io.github.selenium.javaDriverless.types;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CompletableFuture;

final class WebElementScriptBridge {
    private WebElementScriptBridge() {
    }

    static CompletableFuture<Object> executeRawScript(WebElement element, String script,
                                                      boolean awaitResult, Object... args) {
        List<Object> fullArgs = new ArrayList<>();
        fullArgs.add(element);
        fullArgs.addAll(Arrays.asList(args));

        return element.getObjId().thenCompose(objId -> {
            if (element.objId == null) element.objId = objId;
            return element.target.executeRawScript(script, awaitResult, null, fullArgs.toArray());
        });
    }

    static CompletableFuture<Object> executeAsyncScript(WebElement element, String script,
                                                        int maxDepth, Object... args) {
        List<Object> fullArgs = new ArrayList<>();
        fullArgs.add(element);
        fullArgs.addAll(Arrays.asList(args));

        return element.getObjId().thenCompose(objId -> {
            if (element.objId == null) element.objId = objId;
            return element.target.executeAsyncScript(script, maxDepth, null, fullArgs.toArray());
        });
    }

    static CompletableFuture<Object> evalAsync(WebElement element, String script, int maxDepth, Object... args) {
        List<Object> fullArgs = new ArrayList<>();
        fullArgs.add(element);
        fullArgs.addAll(Arrays.asList(args));

        // Runtime.evaluate aceita texto JSON, mas não transporta objectId do CDP.
        return element.getObjId().thenCompose(objId -> {
            if (element.objId == null) element.objId = objId;
            return element.target.executeAsyncScript(script, maxDepth, null, fullArgs.toArray());
        });
    }
}
