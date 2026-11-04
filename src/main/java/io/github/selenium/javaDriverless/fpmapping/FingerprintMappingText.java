package io.github.selenium.javaDriverless.fpmapping;

import java.util.Arrays;
import java.util.List;
import java.util.Locale;

final class FingerprintMappingText {
    private FingerprintMappingText() {
    }

    static boolean contains(String text, String term) {
        return term != null && !term.isBlank() && text.contains(term.toLowerCase(Locale.ROOT));
    }

    static String normalize(String value) {
        return value.toLowerCase(Locale.ROOT).replaceAll("\\s+", " ");
    }

    static List<String> splitCsv(String value) {
        if (value == null || value.isBlank()) {
            return List.of();
        }
        return Arrays.stream(value.split(","))
                .map(String::trim)
                .filter(v -> !v.isBlank())
                .toList();
    }

    static List<String> nonEmptyLines(String body) {
        return Arrays.stream((body == null ? "" : body).split("\\R"))
                .map(String::trim)
                .filter(line -> !line.isBlank())
                .toList();
    }

    static int indexOf(List<String> lines, String label) {
        return indexOf(lines, label, 0);
    }

    static int indexOf(List<String> lines, String label, int start) {
        for (int i = Math.max(0, start); i < lines.size(); i++) {
            if (lines.get(i).equalsIgnoreCase(label)) {
                return i;
            }
        }
        return -1;
    }
}
