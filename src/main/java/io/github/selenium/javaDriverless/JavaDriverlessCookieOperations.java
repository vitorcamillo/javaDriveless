package io.github.selenium.javaDriverless;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Blocking cookie facade shared by the Selenium-compatible wrapper methods. */
final class JavaDriverlessCookieOperations {
    private JavaDriverlessCookieOperations() {}

    static List<Map<String, Object>> getCookies(Chrome chrome) {
        try {
            return chrome.getCookies().get();
        } catch (Exception e) {
            throw new RuntimeException("Erro ao obter cookies", e);
        }
    }

    static void addCookie(Chrome chrome, Map<String, Object> cookie) {
        try {
            chrome.addCookie(cookie).get();
        } catch (Exception e) {
            throw new RuntimeException("Erro ao adicionar cookie", e);
        }
    }

    static void deleteAllCookies(Chrome chrome) {
        try {
            chrome.deleteAllCookies().get();
        } catch (Exception e) {
            throw new RuntimeException("Erro ao deletar cookies", e);
        }
    }

    static Map<String, Object> getCookie(Chrome chrome, String name) {
        try {
            return chrome.getCookies().get().stream()
                    .filter(cookie -> name.equals(cookie.get("name")))
                    .findFirst()
                    .orElse(null);
        } catch (Exception e) {
            throw new RuntimeException("Erro ao obter cookie: " + name, e);
        }
    }

    static void deleteCookie(Chrome chrome, String name) {
        try {
            Map<String, Object> cookie = getCookie(chrome, name);
            if (cookie == null) return;
            Map<String, Object> args = new HashMap<>();
            args.put("name", name);
            if (cookie.containsKey("domain")) args.put("domain", cookie.get("domain"));
            chrome.getCurrentTarget().get()
                    .executeCdpCmd("Network.deleteCookies", args, 5.0f).get();
        } catch (Exception e) {
            throw new RuntimeException("Erro ao deletar cookie: " + name, e);
        }
    }
}
