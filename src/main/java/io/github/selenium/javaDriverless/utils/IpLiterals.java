package io.github.selenium.javaDriverless.utils;

/**
 * Validação sintática de literal IPv6 (RFC 4291), compartilhada pelas validações de
 * CONNECT da ponte autenticada e de host direto do egress.
 *
 * <p>Sintático e fail-closed de propósito: não consulta DNS nem InetAddress (que
 * resolveria hostname e abriria contato com a rede em uma validação de entrada).</p>
 */
public final class IpLiterals {

    private IpLiterals() {
    }

    /**
     * Aceita apenas literal IPv6 completo: grupos hex de 1-4 dígitos, no máximo um
     * {@code ::} de compressão e opcionalmente IPv4 incorporado no final
     * (ex.: {@code ::ffff:192.168.0.1}). Rejeita hostname, {@code :::}, grupos vazios
     * e mais de 8 grupos.
     */
    public static boolean isValidIpv6(String literal) {
        if (literal == null || literal.isEmpty()) {
            return false;
        }
        int compression = literal.indexOf("::");
        if (compression >= 0 && literal.indexOf("::", compression + 1) >= 0) {
            return false;
        }
        String head;
        String tail;
        if (compression >= 0) {
            head = literal.substring(0, compression);
            tail = literal.substring(compression + 2);
        } else {
            head = literal;
            tail = null;
        }
        // IPv4 incorporado só é válido no fim do literal inteiro.
        int headGroups = countGroups(head, tail == null);
        int tailGroups = headGroups < 0 ? -1 : countGroups(tail, tail != null);
        if (headGroups < 0 || tailGroups < 0) {
            return false;
        }
        int groups = headGroups + tailGroups;
        // Com compressão o '::' representa ao menos um grupo zero: nunca 8 explícitos.
        // Sem compressão, IPv6 completo exige exatamente 8 grupos (IPv4 final vale 2).
        return compression < 0 ? groups == 8 : groups < 8;
    }

    /** Aceita apenas IPv4 dotted-quad completo: 4 octetos decimais de 0 a 255. */
    public static boolean isValidIpv4(String literal) {
        return literal != null && isValidIpv4Tail(literal);
    }

    /** Soma dos grupos hex (IPv4 final conta como 2); -1 se algum token é inválido. */
    private static int countGroups(String part, boolean ipv4TailAllowed) {
        if (part == null || part.isEmpty()) {
            return 0;
        }
        String[] tokens = part.split(":", -1);
        int count = 0;
        for (int i = 0; i < tokens.length; i++) {
            String token = tokens[i];
            if (ipv4TailAllowed && i == tokens.length - 1 && token.indexOf('.') >= 0) {
                if (!isValidIpv4Tail(token)) {
                    return -1;
                }
                count += 2;
                continue;
            }
            if (!isValidHexGroup(token)) {
                return -1;
            }
            count++;
        }
        return count;
    }

    private static boolean isValidHexGroup(String token) {
        if (token.isEmpty() || token.length() > 4) {
            return false;
        }
        for (int i = 0; i < token.length(); i++) {
            char c = token.charAt(i);
            if ((c < '0' || c > '9') && (c < 'a' || c > 'f') && (c < 'A' || c > 'F')) {
                return false;
            }
        }
        return true;
    }

    private static boolean isValidIpv4Tail(String token) {
        String[] octets = token.split("\\.", -1);
        if (octets.length != 4) {
            return false;
        }
        for (String octet : octets) {
            if (octet.isEmpty() || octet.length() > 3) {
                return false;
            }
            for (int i = 0; i < octet.length(); i++) {
                char c = octet.charAt(i);
                if (c < '0' || c > '9') {
                    return false;
                }
            }
            if (Integer.parseInt(octet) > 255) {
                return false;
            }
        }
        return true;
    }
}
