package io.github.selenium.javaDriverless.fpmapping;

import java.util.LinkedHashMap;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

abstract class FingerprintMappingCreepJsExtractor extends FingerprintMappingPixelScanTextSupport {
    static Map<String, Object> extractCreepJs(String body) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("fpId", lineContaining(body, "FP ID:"));
        Map<String, String> headless = new LinkedHashMap<>();
        headless.put("likeHeadlessPercent", firstPercentLine(body, "like headless"));
        headless.put("headlessPercent", firstHeadlessPercentLine(body));
        headless.put("stealthPercent", firstPercentLine(body, "stealth:"));
        out.put("headless", headless);
        out.put("canvas", extractCreepJsCanvas(body));
        out.put("worker", extractCreepJsWorker(body));
        out.put("webgl", FingerprintMappingCreepWebGl.parse(body).main());
        return out;
    }

    static void augmentCreepJsFromPage(String page, Map<String, Object> extracted) {
        if (extracted == null) return;

        String html = page == null ? "" : page;
        extracted.put("liedSections", creepJsLiedSections(html));

        String canvasHtml = creepJsCanvasHtml(html);
        if (canvasHtml.isBlank()) return;

        Map<String, String> canvas = new LinkedHashMap<>();
        Object currentCanvas = extracted.get("canvas");
        if (currentCanvas instanceof Map<?, ?> current) {
            current.forEach((key, value) -> {
                if (key != null && value != null) canvas.put(key.toString(), value.toString());
            });
        }
        putCreepJsCanvasModalHash(canvas, "textHash", canvasHtml, "text");
        putCreepJsCanvasModalHash(canvas, "emojiHash", canvasHtml, "emoji");
        putCreepJsCanvasModalHash(canvas, "paintGpuHash", canvasHtml, "paint\\s*\\(GPU\\)");
        putCreepJsCanvasModalHash(canvas, "paintCpuHash", canvasHtml, "paint\\s*\\(CPU\\)");
        putCreepJsCanvasModalHash(canvas, "combinedHash", canvasHtml, "combined");
        extracted.put("canvas", canvas);
    }

    static List<String> creepJsLiedSections(String page) {
        String html = page == null ? "" : page;
        java.util.regex.Matcher sectionMatcher = java.util.regex.Pattern.compile(
                "(?is)<strong\\b[^>]*>\\s*([^<]+?)\\s*</strong>\\s*<span\\b([^>]*)>")
                .matcher(html);
        Map<String, String> unique = new LinkedHashMap<>();
        java.util.regex.Pattern classAttribute = java.util.regex.Pattern.compile(
                "(?is)\\bclass\\s*=\\s*(['\"])(.*?)\\1");
        while (sectionMatcher.find()) {
            java.util.regex.Matcher classMatcher = classAttribute.matcher(sectionMatcher.group(2));
            if (!classMatcher.find()) continue;
            List<String> classes = java.util.Arrays.stream(classMatcher.group(2).trim().split("\\s+"))
                    .map(value -> value.toLowerCase(Locale.ROOT)).toList();
            if (!classes.contains("lies") || !classes.contains("hash")) continue;
            String section = sectionMatcher.group(1).replaceAll("\\s+", " ").trim();
            if (!section.isBlank()) unique.putIfAbsent(section.toLowerCase(Locale.ROOT), section);
        }
        return new ArrayList<>(unique.values());
    }

    static String creepJsCanvasHtml(String page) {
        java.util.regex.Matcher heading = java.util.regex.Pattern.compile(
                "(?is)<strong\\b[^>]*>\\s*Canvas\\s+2d\\s*</strong>").matcher(page);
        if (!heading.find()) return "";
        int end = page.toLowerCase(Locale.ROOT).indexOf("<strong", heading.end());
        return page.substring(heading.start(), end < 0 ? page.length() : end);
    }

    static void putCreepJsCanvasModalHash(Map<String, String> canvas, String key,
                                                   String canvasHtml, String labelRegex) {
        java.util.regex.Matcher matcher = java.util.regex.Pattern.compile(
                "(?is)<br\\s*/?>\\s*" + labelRegex + "\\s*:\\s*([a-f0-9]{8})\\b")
                .matcher(canvasHtml);
        if (matcher.find()) canvas.put(key, matcher.group(1).toLowerCase(Locale.ROOT));
    }

    static Map<String, String> extractCreepJsCanvas(String body) {
        List<String> lines = FingerprintMappingText.nonEmptyLines(body);
        Map<String, String> canvas = new LinkedHashMap<>();
        int start = -1;
        java.util.regex.Pattern heading = java.util.regex.Pattern.compile(
                "(?i)^Canvas\\s+2d\\s*([a-f0-9]{8})$");
        for (int i = 0; i < lines.size(); i++) {
            java.util.regex.Matcher matcher = heading.matcher(lines.get(i));
            if (matcher.matches()) {
                canvas.put("hash", matcher.group(1));
                start = i + 1;
                break;
            }
        }
        if (start < 0) return canvas;
        for (int i = start; i < lines.size(); i++) {
            String line = lines.get(i);
            if (line.matches("(?i)^Fonts[a-f0-9]{8}$") || line.equalsIgnoreCase("Fonts")) break;
            if (line.regionMatches(true, 0, "data:", 0, 5)) {
                String value = line.substring(5).trim();
                if (value.matches("(?i)[a-f0-9]{8}")) canvas.put("dataHash", value);
            } else if (line.regionMatches(true, 0, "rendering:", 0, 10)) {
                String value = line.substring(10).trim();
                canvas.put("rendering", value.isBlank() ? "none" : value);
            } else if (line.regionMatches(true, 0, "textMetrics:", 0, 12)) {
                String value = line.substring(12).trim();
                if (value.isBlank() && i + 1 < lines.size()) value = lines.get(i + 1).trim();
                if (value.matches("-?(?:\\d+(?:\\.\\d+)?|\\.\\d+)")) {
                    canvas.put("textMetrics", value);
                }
            }
        }
        return canvas;
    }

    static Map<String, String> extractCreepJsWorker(String body) {
        Map<String, String> worker = new LinkedHashMap<>();
        List<String> lines = FingerprintMappingText.nonEmptyLines(body);
        int start = -1;
        for (int i = 0; i < lines.size(); i++) {
            String line = lines.get(i);
            if (line.endsWith("WorkerGlobalScope")) {
                start = i;
                worker.put("scope", line);
                break;
            }
        }
        if (start < 0) {
            return worker;
        }

        int end = lines.size();
        for (int i = start + 1; i < lines.size(); i++) {
            if (lines.get(i).startsWith("WebGL")) {
                end = i;
                break;
            }
        }

        if (start + 1 < end && lines.get(start + 1).startsWith("Worker")) {
            worker.put("hash", lines.get(start + 1));
        }

        int gpu = indexOfBetween(lines, "gpu:", start, end);
        if (gpu >= 0) {
            List<String> gpuValues = creepJsGpuValues(lines, gpu + 1, end);
            if (gpuValues.size() == 2) {
                worker.put("gpuVendor", gpuValues.get(0));
                worker.put("gpuRenderer", gpuValues.get(1));
            }
        }

        for (int i = start; i < end; i++) {
            if (lines.get(i).startsWith("cores:")) {
                worker.put("device", lines.get(i));
                break;
            }
        }
        return worker;
    }

    static List<String> creepJsGpuValues(List<String> lines, int start, int end) {
        List<String> values = new java.util.ArrayList<>(2);
        for (int index = Math.max(0, start); index < Math.min(lines.size(), end); index++) {
            String candidate = lines.get(index).trim();
            if (candidate.toLowerCase(Locale.ROOT).startsWith("confidence:")) {
                continue;
            }
            if (isUnavailableCreepJsGpuValue(candidate) || candidate.endsWith(":")) {
                return List.of();
            }
            values.add(candidate);
            if (values.size() == 2) {
                return List.copyOf(values);
            }
        }
        return List.of();
    }

    static boolean isUnavailableCreepJsGpuValue(String value) {
        if (value == null || value.isBlank()) {
            return true;
        }
        String normalized = value.trim().toLowerCase(java.util.Locale.ROOT);
        return normalized.equals("blocked")
                || normalized.equals("unsupported")
                || normalized.equals("undefined")
                || normalized.equals("null");
    }
}
