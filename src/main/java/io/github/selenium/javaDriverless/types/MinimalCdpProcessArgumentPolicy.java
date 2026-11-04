package io.github.selenium.javaDriverless.types;

import java.util.Arrays;
import java.util.Locale;

/** Rejects Chrome switches that invalidate the minimal-CDP OS/process isolation contract. */
public final class MinimalCdpProcessArgumentPolicy {
    private MinimalCdpProcessArgumentPolicy() {
    }

    public static void requireSafe(Iterable<String> arguments) {
        if (arguments == null) return;
        for (String argument : arguments) {
            String name = switchName(argument);
            if (isDirectBypass(name) || disablesSiteIsolation(name, switchValue(argument))) {
                throw new IllegalArgumentException(
                        "runtimePolicy=minimal-cdp rejeita argumento de seguranca: " + name);
            }
        }
    }

    /** DevTools nao tem autenticacao: so loopback é aceitável ao lançar. Mensagem nunca ecoa o valor. */
    public static void requireLoopbackDebuggingAddress(Iterable<String> arguments) {
        if (arguments == null) return;
        for (String argument : arguments) {
            if (!switchName(argument).equals("--remote-debugging-address")) continue;
            String value = switchValue(argument).trim().toLowerCase(Locale.ROOT);
            boolean loopback = value.equals("127.0.0.1")
                    || value.equals("localhost")
                    || value.equals("::1")
                    || value.equals("[::1]");
            if (!loopback) {
                throw new IllegalArgumentException(
                        "--remote-debugging-address deve ser loopback (127.0.0.1/localhost/::1)");
            }
        }
    }

    private static boolean isDirectBypass(String name) {
        return name.equals("--no-sandbox")
                || name.equals("--disable-sandbox")
                || name.equals("--disable-setuid-sandbox")
                || name.equals("--disable-seccomp-filter-sandbox")
                || name.equals("--disable-seccomp-sandbox")
                || name.equals("--disable-namespace-sandbox")
                || name.equals("--disable-zygote-sandbox")
                || name.equals("--disable-gpu-sandbox")
                || name.equals("--disable-web-security")
                || name.equals("--disable-site-isolation-trials")
                || name.equals("--no-zygote")
                || name.equals("--single-process")
                || name.equals("--in-process-gpu");
    }

    private static boolean disablesSiteIsolation(String name, String value) {
        if (!name.equals("--disable-features")) return false;
        return Arrays.stream(value.toLowerCase(Locale.ROOT).replace('_', '-').split(","))
                .map(String::trim)
                .anyMatch(feature -> feature.equals("isolateorigins")
                        || feature.equals("site-per-process")
                        || feature.equals("siteperprocess"));
    }

    private static String switchName(String argument) {
        if (argument == null) return "";
        String normalized = argument.trim().toLowerCase(Locale.ROOT).replace('_', '-');
        int separator = separatorIndex(normalized);
        String rawName = separator >= 0 ? normalized.substring(0, separator) : normalized;
        if (rawName.startsWith("--")) return rawName;
        if (rawName.startsWith("-") || rawName.startsWith("/")) {
            return "--" + rawName.substring(1);
        }
        return "";
    }

    private static String switchValue(String argument) {
        if (argument == null) return "";
        int separator = separatorIndex(argument);
        return separator >= 0 ? argument.substring(separator + 1).trim() : "";
    }

    private static int separatorIndex(String argument) {
        int equals = argument.indexOf('=');
        int whitespace = -1;
        for (int index = 0; index < argument.length(); index++) {
            if (Character.isWhitespace(argument.charAt(index))) {
                whitespace = index;
                break;
            }
        }
        if (equals < 0) return whitespace;
        if (whitespace < 0) return equals;
        return Math.min(equals, whitespace);
    }
}
