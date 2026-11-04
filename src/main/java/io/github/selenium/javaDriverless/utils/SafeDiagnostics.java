package io.github.selenium.javaDriverless.utils;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * Utilitários mínimos para que logs e mensagens de exceção não carreguem dados da página,
 * credenciais ou caminhos locais. Redação é heurística: texto sem palavra-chave sensível passa.
 */
public final class SafeDiagnostics {

    private static final List<String> SAFE_IDENTIFIERS = List.of(
            "querySelector", "querySelectorAll", "getCookies", "getAllCookies",
            "setCookie", "setCookies", "deleteCookies", "clearBrowserCookies", "cookieEnabled");
    private static final Pattern SAFE_IDENTIFIER_PATTERN = Pattern.compile(
            "\\b(?:" + SAFE_IDENTIFIERS.stream().map(Pattern::quote)
                    .reduce((left, right) -> left + "|" + right).orElse("") + ")\\b");

    private SafeDiagnostics() {
    }

    /**
     * Redige URLs, Bearer, JWT, chaves com prefixo, pares chave=valor sensíveis, palavras com
     * token/secret/password/cookie e caminhos. Nunca retorna null; entrada vazia vira {@code <redacted>}.
     */
    public static String sanitizeMessage(String message) {
        if (message == null || message.isBlank()) return "<redacted>";
        String sanitized = message
                .replaceAll("(?i)(?:https?|wss?|file)://\\S+", "<url>")
                .replaceAll("(?i)bearer\\s+[^\\s|,;]+", "Bearer <redacted>")
                .replaceAll("(?i)(authorization|proxy-authorization|cookie|set-cookie|x-api-key|"
                                + "api[_-]?key|password|passwd|secret|token|postdata)"
                                + "\\s*[:=]\\s*[^\\s|,;]+",
                        "$1=<redacted>")
                .replaceAll("(?i)\\b(auth|pwd|pass|passphrase|session|session[_-]?id|sid)\\s*[:=]\\s*[^\\s|,;]+", "$1=<redacted>")
                .replaceAll("\\beyJ[A-Za-z0-9_-]+\\.[A-Za-z0-9_-]+\\.[A-Za-z0-9_-]*", "<jwt>")
                .replaceAll("\\b(?:sk|rk|pk)_(?:live|test)_[A-Za-z0-9]{8,}", "<redacted>")
                .replaceAll("\\bgh[pousr]_[A-Za-z0-9]{20,}", "<redacted>");
        // Só depois da regra chave=valor: "setCookie=valor" ainda precisa ter o valor redigido.
        sanitized = protectSafeIdentifiers(sanitized)
                .replaceAll("(?i)\\b[A-Za-z0-9_-]*(?:sentinel|secret|password|token|cookie|"
                                + "post_data|query)[A-Za-z0-9_-]*\\b",
                        "<redacted>")
                .replaceAll("(?<![:\\w])/(?:[^\\s|]+)", "<path>")
                .replaceAll("(?i)\\b[A-Z]:\\\\[^\\s|]+", "<path>");
        sanitized = restoreSafeIdentifiers(sanitized);
        return sanitized.isBlank() ? "<redacted>" : sanitized;
    }

    private static String protectSafeIdentifiers(String message) {
        var matcher = SAFE_IDENTIFIER_PATTERN.matcher(message);
        StringBuffer protectedMessage = new StringBuffer();
        while (matcher.find()) {
            int index = SAFE_IDENTIFIERS.indexOf(matcher.group());
            matcher.appendReplacement(protectedMessage, "\u0000SAFE" + index + "\u0000");
        }
        matcher.appendTail(protectedMessage);
        return protectedMessage.toString();
    }

    private static String restoreSafeIdentifiers(String message) {
        String restored = message;
        for (int i = 0; i < SAFE_IDENTIFIERS.size(); i++) {
            restored = restored.replace("\u0000SAFE" + i + "\u0000", SAFE_IDENTIFIERS.get(i));
        }
        return restored;
    }

    private static final Pattern FLAG_NAME = Pattern.compile("^--[A-Za-z0-9][A-Za-z0-9-]*$");

    /**
     * Mantém somente nomes de flags (corta no primeiro '=' ou espaço em branco); nome fora do
     * padrão vira {@code <invalid-flag>} e argumentos posicionais só são contados.
     */
    public static List<String> safeChromeArgumentFlags(List<String> arguments) {
        List<String> flags = new ArrayList<>();
        int positionalCount = 0;
        for (String argument : arguments) {
            if (argument != null && argument.startsWith("--")) {
                int end = argument.length();
                for (int i = 0; i < argument.length(); i++) {
                    char c = argument.charAt(i);
                    if (c == '=' || Character.isWhitespace(c)) {
                        end = i;
                        break;
                    }
                }
                String name = argument.substring(0, end);
                flags.add(FLAG_NAME.matcher(name).matches() ? name : "<invalid-flag>");
            } else positionalCount++;
        }
        if (positionalCount > 0) flags.add("<positional:" + positionalCount + ">");
        return List.copyOf(flags);
    }
}
