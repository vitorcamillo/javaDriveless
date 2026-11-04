package io.github.selenium.javaDriverless.fpmapping;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Parser estrutural da seção WebGL do CreepJS, compartilhado pelo extractor e pelo gate. */
final class FingerprintMappingCreepWebGl {
    private static final Pattern WEBGL_HEADING = Pattern.compile("(?i)^WebGL([a-f0-9]{8})$");
    private static final Pattern SCREEN_HEADING = Pattern.compile("(?i)^Screen(?:[a-f0-9]{8})?$");
    private static final Pattern HASH_FIELD = Pattern.compile(
            "(?i)^(images|pixels):\\s*([a-f0-9]{8})$");
    private static final Pattern COUNTED_HASH_FIELD = Pattern.compile(
            "(?i)^(params|exts)\\s*\\(([1-9][0-9]*)\\):\\s*([a-f0-9]{8})$");
    private static final Pattern ELAPSED_TIME = Pattern.compile(
            "(?i)^-?\\d+(?:\\.\\d+)?ms$");

    private FingerprintMappingCreepWebGl() {
    }

    record Snapshot(boolean complete, boolean realmCoherent, Map<String, String> main) {
        Snapshot {
            main = Collections.unmodifiableMap(new LinkedHashMap<>(main));
        }
    }

    static Snapshot parse(String body) {
        List<String> lines = FingerprintMappingText.nonEmptyLines(body);
        int workerStart = indexMatching(lines, Pattern.compile(".*WorkerGlobalScope$"), 0);
        if (workerStart < 0) return incomplete();

        int screenStart = indexMatching(lines, SCREEN_HEADING, workerStart + 1);
        int webGlStart = indexMatching(lines, WEBGL_HEADING, workerStart + 1);
        if (screenStart < 0 || webGlStart < 0 || webGlStart >= screenStart) {
            return incomplete();
        }

        int workerGpu = indexOfExact(lines, "gpu:", workerStart + 1, webGlStart);
        List<String> workerGpuValues = workerGpu < 0
                ? List.of() : gpuValues(lines, workerGpu + 1, webGlStart);
        if (workerGpuValues.size() != 2) {
            return incomplete();
        }

        Matcher heading = WEBGL_HEADING.matcher(lines.get(webGlStart));
        if (!heading.matches()) return incomplete();
        Map<String, String> main = new LinkedHashMap<>();
        main.put("hash", heading.group(1));
        boolean invalid = false;
        int mainGpu = -1;

        for (int index = webGlStart + 1; index < screenStart; index++) {
            String line = lines.get(index).trim();
            String lower = line.toLowerCase(java.util.Locale.ROOT);
            if (lower.startsWith("images:") || lower.startsWith("pixels:")) {
                Matcher matcher = HASH_FIELD.matcher(line);
                if (!matcher.matches()) {
                    invalid = true;
                    continue;
                }
                String key = matcher.group(1).equalsIgnoreCase("images")
                        ? "imagesHash" : "pixelsHash";
                invalid |= main.putIfAbsent(key, matcher.group(2)) != null;
                continue;
            }
            if (lower.startsWith("params") || lower.startsWith("exts")) {
                Matcher matcher = COUNTED_HASH_FIELD.matcher(line);
                if (!matcher.matches()) {
                    invalid = true;
                    continue;
                }
                boolean params = matcher.group(1).equalsIgnoreCase("params");
                String countKey = params ? "paramsCount" : "extensionsCount";
                String hashKey = params ? "paramsHash" : "extensionsHash";
                invalid |= main.putIfAbsent(countKey, matcher.group(2)) != null;
                invalid |= main.putIfAbsent(hashKey, matcher.group(3)) != null;
                continue;
            }
            if (lower.startsWith("gpu:")) {
                if (!lower.equals("gpu:") || mainGpu >= 0) {
                    invalid = true;
                } else {
                    mainGpu = index;
                }
            }
        }

        List<String> gpu = mainGpu < 0
                ? List.of() : gpuValues(lines, mainGpu + 1, screenStart);
        if (gpu.size() == 2) {
            main.put("gpuVendor", gpu.get(0));
            main.put("gpuRenderer", gpu.get(1));
        }
        boolean complete = !invalid && main.keySet().containsAll(List.of(
                "hash", "imagesHash", "pixelsHash", "paramsCount", "paramsHash",
                "extensionsCount", "extensionsHash", "gpuVendor", "gpuRenderer"));
        boolean realmCoherent = complete
                && main.get("gpuVendor").equals(workerGpuValues.get(0))
                && main.get("gpuRenderer").equals(workerGpuValues.get(1));
        return new Snapshot(complete, realmCoherent, main);
    }

    private static Snapshot incomplete() {
        return new Snapshot(false, false, Map.of());
    }

    private static List<String> gpuValues(List<String> lines, int start, int end) {
        List<String> values = new ArrayList<>(2);
        for (int index = Math.max(0, start); index < Math.min(lines.size(), end); index++) {
            String candidate = lines.get(index).trim();
            String lower = candidate.toLowerCase(java.util.Locale.ROOT);
            if (lower.startsWith("confidence:")) continue;
            if (isUnavailable(candidate) || ELAPSED_TIME.matcher(candidate).matches()) {
                return List.of();
            }
            values.add(candidate);
            if (values.size() == 2) return List.copyOf(values);
        }
        return List.of();
    }

    private static boolean isUnavailable(String value) {
        if (value == null || value.isBlank() || value.endsWith(":")) return true;
        String normalized = value.trim().toLowerCase(java.util.Locale.ROOT);
        return normalized.equals("blocked")
                || normalized.equals("unsupported")
                || normalized.equals("undefined")
                || normalized.equals("null");
    }

    private static int indexMatching(List<String> lines, Pattern pattern, int start) {
        for (int index = Math.max(0, start); index < lines.size(); index++) {
            if (pattern.matcher(lines.get(index)).matches()) return index;
        }
        return -1;
    }

    private static int indexOfExact(List<String> lines, String value, int start, int end) {
        for (int index = Math.max(0, start); index < Math.min(end, lines.size()); index++) {
            if (lines.get(index).equalsIgnoreCase(value)) return index;
        }
        return -1;
    }
}
