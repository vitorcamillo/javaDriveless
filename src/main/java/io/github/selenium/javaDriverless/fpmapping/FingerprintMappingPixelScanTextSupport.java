package io.github.selenium.javaDriverless.fpmapping;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** Shared text/DOM parsing helpers kept separate from site-specific extraction. */
abstract class FingerprintMappingPixelScanTextSupport {
    static Map<String, String> details(String body, String section, List<String> labels) {
        Map<String, String> values = new LinkedHashMap<>();
        List<String> lines = FingerprintMappingText.nonEmptyLines(body);
        int start = FingerprintMappingText.indexOf(lines, section);
        if (start < 0) {
            return values;
        }
        for (String label : labels) {
            int idx = FingerprintMappingText.indexOf(lines, label, start);
            if (idx >= 0) {
                String value = valueAfterLabel(lines, idx);
                if (!value.isBlank()) {
                    values.put(label, value);
                }
            }
        }
        return values;
    }

    static String pixelScanAcceptLanguage(String body) {
        List<String> lines = FingerprintMappingText.nonEmptyLines(body);
        int start = FingerprintMappingText.indexOf(lines, "Accept-Language header");
        if (start < 0) {
            return "";
        }
        java.util.regex.Pattern tokenPattern = java.util.regex.Pattern.compile(
                "(?i)^(?:\\*|[a-z]{1,8}(?:-[a-z0-9]{1,8})*)"
                        + "(?:;q=(?:0(?:\\.\\d{1,3})?|1(?:\\.0{1,3})?))?$");
        List<String> values = new ArrayList<>();
        for (int index = start + 1; index < lines.size() && values.size() < 6; index++) {
            String line = lines.get(index).trim();
            if (line.equalsIgnoreCase("Hardware") || line.equalsIgnoreCase("WebGL Vendor")) {
                break;
            }
            String[] candidates = line.split("[,\\s]+");
            boolean validLine = candidates.length > 0;
            for (String candidate : candidates) {
                if (candidate.isBlank()) {
                    continue;
                }
                if (!tokenPattern.matcher(candidate).matches()) {
                    validLine = false;
                    break;
                }
            }
            if (!validLine) {
                break;
            }
            for (String candidate : candidates) {
                if (!candidate.isBlank()) {
                    values.add(candidate);
                }
            }
        }
        return String.join(",", values);
    }

    /**
     * BrowserLeaks quebra arrays de Client Hints com {@code <br>}. O innerText vira várias linhas;
     * coletar apenas a primeira fazia brands/fullVersionList parecerem mapeados pela metade.
     */
    static Map<String, String> multiLineSectionDetails(
            String body, String section, String nextSection, List<String> labels) {
        Map<String, String> values = new LinkedHashMap<>();
        List<String> lines = FingerprintMappingText.nonEmptyLines(body);
        int start = sectionHeadingIndex(lines, section, 0);
        if (start < 0) return values;
        int end = sectionHeadingIndex(lines, nextSection, start + 1);
        if (end < 0) end = lines.size();

        for (int i = start + 1; i < end; i++) {
            String line = lines.get(i);
            String label = matchingRowLabel(line, labels);
            if (label == null) continue;
            StringBuilder value = new StringBuilder();
            String inlineValue = valueOnLabelRow(line, label);
            if (!inlineValue.isBlank()) value.append(inlineValue);
            for (int j = i + 1; j < end; j++) {
                String candidate = lines.get(j);
                if (matchingRowLabel(candidate, labels) != null) break;
                // O site insere <br> logo após "}," em arrays JSON; concatenar sem espaço
                // recompõe exatamente o JSON.stringify original para comparação estrita.
                value.append(candidate);
            }
            if (!value.isEmpty()) values.put(label, value.toString());
        }
        return values;
    }

    static String matchingRowLabel(String line, List<String> labels) {
        String candidate = line == null ? "" : line.trim();
        for (String label : labels) {
            if (candidate.equalsIgnoreCase(label)) return label;
            if (candidate.length() > label.length()
                    && candidate.regionMatches(true, 0, label, 0, label.length())) {
                char separator = candidate.charAt(label.length());
                if (separator == '\t' || separator == ':') return label;
            }
        }
        return null;
    }

    static String valueOnLabelRow(String line, String label) {
        if (line == null || line.length() <= label.length()) return "";
        String suffix = line.substring(label.length()).trim();
        if (suffix.startsWith(":")) suffix = suffix.substring(1).trim();
        return suffix;
    }

    static Map<String, String> sectionDetails(String body, String section, String nextSection,
                                                       List<String> labels) {
        Map<String, String> values = new LinkedHashMap<>();
        List<String> lines = FingerprintMappingText.nonEmptyLines(body);
        int start = sectionHeadingIndex(lines, section, 0);
        if (start < 0) return values;
        int end = sectionHeadingIndex(lines, nextSection, start + 1);
        if (end < 0) end = lines.size();

        for (String label : labels) {
            String value = sectionValue(lines, start + 1, end, label, labels);
            if (!value.isBlank()) values.put(label, value);
        }
        return values;
    }

    static int sectionHeadingIndex(List<String> lines, String heading, int start) {
        String expected = normalizeSectionHeading(heading);
        for (int i = Math.max(0, start); i < lines.size(); i++) {
            if (normalizeSectionHeading(lines.get(i)).equalsIgnoreCase(expected)) {
                return i;
            }
        }
        return -1;
    }

    static String normalizeSectionHeading(String value) {
        return (value == null ? "" : value.trim()).replaceFirst("[?:]+$", "").trim();
    }

    static String sectionValue(List<String> lines, int start, int end, String label,
                                       List<String> labels) {
        java.util.regex.Pattern inline = java.util.regex.Pattern.compile(
                "(?i)^" + java.util.regex.Pattern.quote(label) + "\\s*(?::|\\t)\\s*(.+)$");
        for (int i = start; i < end; i++) {
            java.util.regex.Matcher matcher = inline.matcher(lines.get(i));
            if (matcher.matches()) return matcher.group(1).trim();
            if (lines.get(i).equalsIgnoreCase(label) && i + 1 < end) {
                String candidate = lines.get(i + 1).trim();
                boolean anotherLabel = labels.stream().anyMatch(candidate::equalsIgnoreCase);
                if (!anotherLabel) return candidate;
            }
        }
        return "";
    }

    static String lineContaining(String body, String needle) {
        return FingerprintMappingText.nonEmptyLines(body).stream()
                .filter(line -> line.toLowerCase(Locale.ROOT).contains(needle.toLowerCase(Locale.ROOT)))
                .findFirst()
                .orElse("");
    }

    static String previousNonEmptyLine(String body, String label) {
        List<String> lines = FingerprintMappingText.nonEmptyLines(body);
        int idx = FingerprintMappingText.indexOf(lines, label);
        if (idx > 0) {
            return lines.get(idx - 1);
        }
        return "";
    }

    static String nextNonEmptyLine(String body, String label) {
        List<String> lines = FingerprintMappingText.nonEmptyLines(body);
        int idx = FingerprintMappingText.indexOf(lines, label);
        if (idx >= 0) {
            return valueAfterLabel(lines, idx);
        }
        return "";
    }

    static String nextNonMarkerLine(String body, String label) {
        List<String> lines = FingerprintMappingText.nonEmptyLines(body);
        int idx = FingerprintMappingText.indexOf(lines, label);
        for (int i = idx + 1; idx >= 0 && i < lines.size(); i++) {
            String value = lines.get(i).trim();
            if (!value.equals("!") && !value.equals("?")
                    && !value.toLowerCase(Locale.ROOT).startsWith("click to ")) {
                return value;
            }
        }
        return "";
    }

    static String inlineValue(String body, String label) {
        for (String line : FingerprintMappingText.nonEmptyLines(body)) {
            if (line.regionMatches(true, 0, label, 0, label.length())) {
                return line.substring(label.length()).trim();
            }
        }
        return "";
    }

    static String lineAfter(String body, String label, int offset) {
        List<String> lines = FingerprintMappingText.nonEmptyLines(body);
        int idx = FingerprintMappingText.indexOf(lines, label);
        int target = idx + offset;
        if (idx >= 0 && target >= 0 && target < lines.size()) {
            return lines.get(target);
        }
        return "";
    }

    static String firstPercentLine(String body, String needle) {
        return FingerprintMappingText.nonEmptyLines(body).stream()
                .filter(line -> line.contains("%") && line.toLowerCase(Locale.ROOT).contains(needle.trim().toLowerCase(Locale.ROOT)))
                .findFirst()
                .orElse("");
    }

    static String firstHeadlessPercentLine(String body) {
        return FingerprintMappingText.nonEmptyLines(body).stream()
                .filter(line -> line.contains("%")
                        && line.toLowerCase(Locale.ROOT).contains("headless:")
                        && !line.toLowerCase(Locale.ROOT).contains("like headless"))
                .findFirst()
                .orElse("");
    }

    static String valueAfterLabel(List<String> lines, int idx) {
        for (int i = idx + 1; i < lines.size(); i++) {
            String candidate = lines.get(i);
            if (!candidate.toLowerCase(Locale.ROOT).startsWith("click to ")) {
                return candidate;
            }
        }
        return "";
    }

    static int indexOfBetween(List<String> lines, String label, int start, int end) {
        for (int i = Math.max(0, start); i < Math.min(lines.size(), end); i++) {
            if (lines.get(i).equalsIgnoreCase(label)) {
                return i;
            }
        }
        return -1;
    }

    static String nextMeaningfulLine(List<String> lines, int start, int end) {
        for (int i = Math.max(0, start); i < Math.min(lines.size(), end); i++) {
            String candidate = lines.get(i);
            if (!candidate.toLowerCase(Locale.ROOT).startsWith("confidence:")) {
                return candidate;
            }
        }
        return "";
    }

    static String firstLineContainingAny(String body, String... needles) {
        for (String line : FingerprintMappingText.nonEmptyLines(body)) {
            for (String needle : needles) {
                if (line.toLowerCase(Locale.ROOT).contains(needle.toLowerCase(Locale.ROOT))) return line;
            }
        }
        return "";
    }

    static String browserLeaksCanvasHash(String body) {
        java.util.regex.Matcher matcher = java.util.regex.Pattern.compile(
                        "(?im)^\\s*Signature(?:[\\t :]+|\\R\\s*)([a-f0-9]{32})\\b")
                .matcher(body == null ? "" : body);
        return matcher.find() ? matcher.group(1) : "";
    }

    static String trustMyIpCanvasHash(String body) {
        java.util.regex.Matcher matcher = java.util.regex.Pattern.compile(
                        "(?i)\\b(CVS-[0-9A-F]{8}-[0-9A-F]{1,4})\\b")
                .matcher(body == null ? "" : body);
        return matcher.find() ? matcher.group(1) : "";
    }
}
