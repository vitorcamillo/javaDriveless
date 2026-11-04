package io.github.selenium.javaDriverless.types;

import java.util.HashMap;
import java.util.Map;

final class CdpKeyboardEventArgs {
    private CdpKeyboardEventArgs() {
    }

    static Map<String, Object> keyDown(String key) {
        Map<String, Object> args = new HashMap<>();
        args.put("type", "keyDown");
        args.put("key", key);
        return args;
    }

    static Map<String, Object> charEvent(String text) {
        Map<String, Object> args = new HashMap<>();
        args.put("type", "char");
        args.put("text", text);
        return args;
    }

    static Map<String, Object> keyUp(String key) {
        Map<String, Object> args = new HashMap<>();
        args.put("type", "keyUp");
        args.put("key", key);
        return args;
    }
}
