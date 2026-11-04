package io.github.selenium.javaDriverless.types;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

final class BrowserFingerprintProfileDefaults {
    private BrowserFingerprintProfileDefaults() {}

    static void applyTo(BrowserFingerprintProfile profile) {
        String runtimePolicy = normalizeRuntimePolicy(profile.runtimePolicy);
        profile.runtimePolicy = runtimePolicy;
        String selfCgroup = BrowserFingerprintProfile.RUNTIME_POLICY_MINIMAL_CDP.equals(runtimePolicy)
                ? readCgroupFileIfPresent(Path.of("/proc/self/cgroup"))
                : null;
        applyTo(profile, Runtime.getRuntime().availableProcessors(), Path.of("/sys/fs/cgroup"),
                selfCgroup, System.getenv());
    }

    static void applyTo(
            BrowserFingerprintProfile profile,
            int runtimeProcessors,
            Path cgroupRoot,
            String selfCgroup) {
        applyTo(profile, runtimeProcessors, cgroupRoot, selfCgroup, Map.of());
    }

    static void applyTo(
            BrowserFingerprintProfile profile,
            int runtimeProcessors,
            Path cgroupRoot,
            String selfCgroup,
            Map<String, String> environment) {
        profile.runtimePolicy = normalizeRuntimePolicy(profile.runtimePolicy);
        applyMinimalRuntimeResources(
                profile, runtimeProcessors, cgroupRoot, selfCgroup, environment);
        if (profile.colorScheme == null || profile.colorScheme.isBlank()) {
            profile.colorScheme = "light";
        } else {
            profile.colorScheme = profile.colorScheme.trim().toLowerCase(Locale.ROOT);
            if (!profile.colorScheme.equals("light") && !profile.colorScheme.equals("dark")) {
                throw new IllegalArgumentException("colorScheme deve ser light ou dark");
            }
        }
        profile.prefersReducedMotion = normalizePreference(
                profile.prefersReducedMotion, "no-preference",
                Set.of("no-preference", "reduce"), "prefersReducedMotion");
        profile.prefersReducedTransparency = normalizePreference(
                profile.prefersReducedTransparency, "no-preference",
                Set.of("no-preference", "reduce"), "prefersReducedTransparency");
        profile.prefersContrast = normalizePreference(
                profile.prefersContrast, "no-preference",
                Set.of("no-preference", "more", "less", "custom"), "prefersContrast");
        profile.forcedColors = normalizePreference(
                profile.forcedColors, "none", Set.of("none", "active"), "forcedColors");
        if (profile.formFactors == null || profile.formFactors.isEmpty()) {
            profile.formFactors = List.of(profile.mobile ? "Mobile" : "Desktop");
        } else {
            Set<String> allowed = Set.of("Desktop", "Automotive", "Mobile", "Tablet", "XR", "EInk", "Watch");
            List<String> normalized = profile.formFactors.stream()
                    .map(value -> value == null ? "" : value.trim())
                    .filter(value -> !value.isEmpty())
                    .distinct()
                    .sorted()
                    .toList();
            if (normalized.isEmpty() || normalized.stream().anyMatch(value -> !allowed.contains(value))) {
                throw new IllegalArgumentException("formFactors contém valor UA-CH inválido");
            }
            profile.formFactors = normalized;
        }
        if (profile.languages == null || profile.languages.isEmpty()) profile.languages = List.of("en-US", "en");
        if (profile.screen == null) profile.screen = new BrowserFingerprintProfile.Screen();
        if (profile.webgl == null) profile.webgl = new BrowserFingerprintProfile.WebGl();
        if (profile.webRtc == null) profile.webRtc = new BrowserFingerprintProfile.WebRtc();
        if (profile.geolocation == null) profile.geolocation = new BrowserFingerprintProfile.Geolocation();
        if (profile.permissions == null) profile.permissions = new BrowserFingerprintProfile.Permissions();
        if (profile.mediaDevices == null) profile.mediaDevices = new BrowserFingerprintProfile.MediaDevices();
        if (profile.speech == null) profile.speech = new BrowserFingerprintProfile.Speech();
        if (profile.audio == null) profile.audio = new BrowserFingerprintProfile.Audio();
        if (profile.canvas == null) profile.canvas = new BrowserFingerprintProfile.Canvas();
        profile.canvas.scope = normalizePreference(
                profile.canvas.scope, "all", Set.of("all", "detached"), "canvas.scope");
        if (profile.clientRects == null) profile.clientRects = new BrowserFingerprintProfile.ClientRects();
        if (profile.storage == null) profile.storage = new BrowserFingerprintProfile.Storage();
        profile.storage.mode = normalizePreference(
                profile.storage.mode, "spoof", Set.of("real", "spoof"), "storage.mode");
        if (profile.fonts == null) profile.fonts = new BrowserFingerprintProfile.Fonts();
        if (profile.ports == null) profile.ports = new BrowserFingerprintProfile.Ports();
        if (profile.connection == null) profile.connection = new BrowserFingerprintProfile.Connection();
    }

    private static void applyMinimalRuntimeResources(
            BrowserFingerprintProfile profile,
            int runtimeProcessors,
            Path cgroupRoot,
            String selfCgroup,
            Map<String, String> environment) {
        if (!BrowserFingerprintProfile.RUNTIME_POLICY_MINIMAL_CDP.equals(profile.runtimePolicy)) {
            return;
        }
        int configuredProcessors = profile.hardwareConcurrency > 0
                ? profile.hardwareConcurrency : runtimeProcessors;
        int processors = Math.max(1, Math.min(configuredProcessors, runtimeProcessors));
        Path leaf = resolveV2Leaf(cgroupRoot, selfCgroup);
        Path root = cgroupRoot.toAbsolutePath().normalize();
        for (Path current = leaf; current != null && current.startsWith(root); current = current.getParent()) {
            processors = applyCpuMax(processors, readCgroupFileIfPresent(current.resolve("cpu.max")));
            if (current.equals(root)) break;
        }
        Path v1CpuRoot = firstExistingDirectory(root.resolve("cpu"), root.resolve("cpu,cpuacct"));
        Path v1CpuLeaf = resolveV1Leaf(v1CpuRoot, selfCgroup, "cpu");
        for (Path current = v1CpuLeaf;
                current != null && current.startsWith(v1CpuRoot);
                current = current.getParent()) {
            processors = applyCpuQuota(
                    processors,
                    readCgroupFileIfPresent(current.resolve("cpu.cfs_quota_us")),
                    readCgroupFileIfPresent(current.resolve("cpu.cfs_period_us")));
            if (current.equals(v1CpuRoot)) break;
        }
        requireNoFiniteV2MemoryLimit(
                leaf, root, profile.deviceMemory, environment);
        Path v1MemoryRoot = root.resolve("memory");
        requireNoFiniteV1MemoryLimit(
                resolveV1Leaf(v1MemoryRoot, selfCgroup, "memory"), v1MemoryRoot,
                profile.deviceMemory, environment);
        profile.hardwareConcurrency = processors;
    }

    private static void requireNoFiniteV2MemoryLimit(
            Path leaf,
            Path root,
            int expectedDeviceMemory,
            Map<String, String> environment) {
        for (Path current = leaf; current != null && current.startsWith(root); current = current.getParent()) {
            Path memoryMax = current.resolve("memory.max");
            String rawLimit = readCgroupFileIfPresent(memoryMax);
            if (isFiniteV2MemoryLimit(memoryMax, rawLimit)) {
                requireNativeDeviceMemoryAttestation(
                        memoryMax, rawLimit, expectedDeviceMemory, environment);
            }
            if (current.equals(root)) break;
        }
    }

    private static void requireNoFiniteV1MemoryLimit(
            Path leaf,
            Path root,
            int expectedDeviceMemory,
            Map<String, String> environment) {
        for (Path current = leaf; current != null && current.startsWith(root); current = current.getParent()) {
            Path memoryLimit = current.resolve("memory.limit_in_bytes");
            String rawLimit = readCgroupFileIfPresent(memoryLimit);
            if (isFiniteV1MemoryLimit(memoryLimit, rawLimit)) {
                requireNativeDeviceMemoryAttestation(
                        memoryLimit, rawLimit, expectedDeviceMemory, environment);
            }
            if (current.equals(root)) break;
        }
    }

    private static void requireNativeDeviceMemoryAttestation(
            Path source,
            String rawLimit,
            int expectedDeviceMemory,
            Map<String, String> environment) {
        Map<String, String> safeEnvironment = environment == null ? Map.of() : environment;
        String provisioned = safeEnvironment.get(
                "JAVA_DRIVERLESS_NATIVE_DEVICE_MEMORY_PROVISIONED");
        String rawObserved = safeEnvironment.get("JAVA_DRIVERLESS_NATIVE_DEVICE_MEMORY_GB");
        if (!"1".equals(provisioned)) {
            throw unsupportedFiniteMemoryLimit(source, rawLimit);
        }
        int observed;
        try {
            observed = Integer.parseInt(rawObserved == null ? "" : rawObserved.trim());
        } catch (NumberFormatException failure) {
            throw invalidNativeDeviceMemoryAttestation(rawObserved, expectedDeviceMemory);
        }
        if (observed <= 0 || observed != expectedDeviceMemory) {
            throw invalidNativeDeviceMemoryAttestation(rawObserved, expectedDeviceMemory);
        }
    }

    private static IllegalStateException invalidNativeDeviceMemoryAttestation(
            String observed,
            int expected) {
        String printable = observed == null || observed.isBlank() ? "<ausente>" : observed.trim();
        return new IllegalStateException(
                "minimal-cdp: attestation nativa de navigator.deviceMemory divergiu; esperado="
                        + expected + ", observado=" + printable
                        + "; refaça a medição com o mesmo Chrome/display/cgroup antes do launch");
    }

    private static boolean isFiniteV2MemoryLimit(Path source, String rawLimit) {
        if (rawLimit == null) {
            return false;
        }
        String normalized = rawLimit.trim();
        if (normalized.equalsIgnoreCase("max")) {
            return false;
        }
        long bytes = parseMemoryLimit(source, normalized);
        if (bytes < 0) {
            throw invalidMemoryLimit(source, rawLimit);
        }
        return true;
    }

    private static boolean isFiniteV1MemoryLimit(Path source, String rawLimit) {
        if (rawLimit == null) {
            return false;
        }
        String normalized = rawLimit.trim();
        long bytes = parseMemoryLimit(source, normalized);
        if (bytes == -1 || bytes >= (1L << 60)) {
            return false;
        }
        if (bytes < 0) {
            throw invalidMemoryLimit(source, rawLimit);
        }
        return true;
    }

    private static long parseMemoryLimit(Path source, String rawLimit) {
        if (rawLimit.isBlank()) {
            throw invalidMemoryLimit(source, rawLimit);
        }
        try {
            return Long.parseLong(rawLimit);
        } catch (NumberFormatException e) {
            throw invalidMemoryLimit(source, rawLimit);
        }
    }

    private static IllegalStateException invalidMemoryLimit(Path source, String rawLimit) {
        return new IllegalStateException(
                "minimal-cdp encontrou valor cgroup inválido em "
                        + source.getFileName() + "=" + rawLimit
                        + ": esperado limite inteiro, max no cgroup v2 ou sentinela ilimitada no cgroup v1");
    }

    private static IllegalStateException unsupportedFiniteMemoryLimit(Path source, String rawLimit) {
        return new IllegalStateException(
                "minimal-cdp não pode garantir deviceMemory sob limite cgroup finito "
                        + source.getFileName() + "=" + rawLimit
                        + ": Chrome não oferece override CDP para essa superfície; forneça uma "
                        + "attestation nativa medida no mesmo runtime, remova o limite de memória "
                        + "ou use legacy-spoof");
    }

    private static int applyCpuMax(int processors, String cpuMax) {
        if (cpuMax == null || cpuMax.isBlank()) {
            return processors;
        }
        String[] parts = cpuMax.trim().split("\\s+");
        if (parts.length >= 2 && !parts[0].equalsIgnoreCase("max")) {
            try {
                long quota = Long.parseLong(parts[0]);
                long period = Long.parseLong(parts[1]);
                if (quota > 0 && period > 0) {
                    long quotaProcessors = Math.max(
                            1, quota / period + (quota % period == 0 ? 0 : 1));
                    return (int) Math.min(processors, quotaProcessors);
                }
            } catch (NumberFormatException ignored) {
                // Runtime.availableProcessors continua sendo o fallback seguro.
            }
        }
        return processors;
    }

    private static int applyCpuQuota(int processors, String rawQuota, String rawPeriod) {
        if (rawQuota == null || rawPeriod == null || rawQuota.isBlank() || rawPeriod.isBlank()) {
            return processors;
        }
        try {
            long quota = Long.parseLong(rawQuota.trim());
            long period = Long.parseLong(rawPeriod.trim());
            if (quota > 0 && period > 0) {
                long quotaProcessors = Math.max(
                        1, quota / period + (quota % period == 0 ? 0 : 1));
                return (int) Math.min(processors, quotaProcessors);
            }
        } catch (NumberFormatException ignored) {
            // Runtime.availableProcessors continua sendo o fallback seguro.
        }
        return processors;
    }

    private static Path resolveV2Leaf(Path cgroupRoot, String selfCgroup) {
        Path normalizedRoot = cgroupRoot.toAbsolutePath().normalize();
        if (selfCgroup != null) {
            for (String line : selfCgroup.lines().toList()) {
                if (!line.startsWith("0::")) continue;
                String raw = line.substring(3).replaceFirst("^/+", "");
                Path candidate = normalizedRoot.resolve(raw).normalize();
                if (candidate.startsWith(normalizedRoot)) return candidate;
            }
        }
        return normalizedRoot;
    }

    private static Path resolveV1Leaf(Path controllerRoot, String selfCgroup, String controller) {
        Path normalizedRoot = controllerRoot.toAbsolutePath().normalize();
        if (selfCgroup != null) {
            for (String line : selfCgroup.lines().toList()) {
                String[] parts = line.split(":", 3);
                if (parts.length != 3 || !List.of(parts[1].split(",")).contains(controller)) {
                    continue;
                }
                String raw = parts[2].replaceFirst("^/+", "");
                Path candidate = normalizedRoot.resolve(raw).normalize();
                if (candidate.startsWith(normalizedRoot)) return candidate;
            }
        }
        return normalizedRoot;
    }

    private static Path firstExistingDirectory(Path first, Path second) {
        if (isCgroupControllerDirectory(first)) return first;
        if (isCgroupControllerDirectory(second)) return second;
        return first;
    }

    private static boolean isCgroupControllerDirectory(Path path) {
        BasicFileAttributes attributes;
        try {
            attributes = Files.readAttributes(
                    path, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
        } catch (NoSuchFileException e) {
            return false;
        } catch (IOException | SecurityException e) {
            throw unreadableCgroupController(path, e);
        }
        if (!attributes.isDirectory()) {
            throw unreadableCgroupController(path, null);
        }
        return true;
    }

    private static String readCgroupFileIfPresent(Path path) {
        BasicFileAttributes attributes;
        try {
            attributes = Files.readAttributes(
                    path, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
        } catch (NoSuchFileException e) {
            return null;
        } catch (IOException | SecurityException e) {
            throw unreadableCgroupFile(path, e);
        }
        if (!attributes.isRegularFile()) {
            throw unreadableCgroupFile(path, null);
        }
        try {
            return Files.readString(path).trim();
        } catch (IOException | SecurityException e) {
            throw unreadableCgroupFile(path, e);
        }
    }

    private static IllegalStateException unreadableCgroupFile(Path path, Exception cause) {
        String message = "minimal-cdp: arquivo cgroup relevante não pôde ser lido: " + path;
        return cause == null ? new IllegalStateException(message) : new IllegalStateException(message, cause);
    }

    private static IllegalStateException unreadableCgroupController(Path path, Exception cause) {
        String message = "minimal-cdp: controlador cgroup relevante não pôde ser lido: " + path;
        return cause == null ? new IllegalStateException(message) : new IllegalStateException(message, cause);
    }

    private static String normalizeRuntimePolicy(String raw) {
        if (raw == null || raw.isBlank()) {
            return BrowserFingerprintProfile.RUNTIME_POLICY_LEGACY_SPOOF;
        }
        String normalized = raw.trim().toLowerCase(Locale.ROOT);
        if (!normalized.equals(BrowserFingerprintProfile.RUNTIME_POLICY_LEGACY_SPOOF)
                && !normalized.equals(BrowserFingerprintProfile.RUNTIME_POLICY_MINIMAL_CDP)) {
            throw new IllegalArgumentException("runtimePolicy deve ser legacy-spoof ou minimal-cdp");
        }
        return normalized;
    }

    private static String normalizePreference(String raw, String fallback, Set<String> allowed, String field) {
        String normalized = raw == null || raw.isBlank()
                ? fallback : raw.trim().toLowerCase(Locale.ROOT);
        if (!allowed.contains(normalized)) {
            throw new IllegalArgumentException(field + " contém valor inválido");
        }
        return normalized;
    }
}
