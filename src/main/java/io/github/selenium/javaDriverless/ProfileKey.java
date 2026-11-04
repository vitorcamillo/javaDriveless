package io.github.selenium.javaDriverless;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Locale;
import java.util.Objects;
import java.util.regex.Pattern;
import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.StandardCopyOption;
import java.util.stream.Stream;

/**
 * Converte identificadores lógicos de profile em componentes de caminho seguros
 * sem fazer nomes distintos colidirem após a sanitização.
 */
final class ProfileKey {
    private static final Pattern SAFE_COMPONENT = Pattern.compile("[A-Za-z0-9._-]+");
    private static final Pattern UNSAFE_RUN = Pattern.compile("[^A-Za-z0-9_-]+");
    private static final Pattern WINDOWS_RESERVED = Pattern.compile(
            "(?i)(con|prn|aux|nul|com[1-9]|lpt[1-9])(?:\\..*)?");
    private static final int MAX_SLUG_LENGTH = 48;
    private static final String ENCODED_PREFIX = "~jdl-x-";
    private static final String CASE_PREFIX = "~jdl-c-";

    private ProfileKey() {
    }

    static String safeComponent(String profileName) {
        String raw = Objects.requireNonNullElse(profileName, "");
        String candidate = raw.trim();
        if (raw.equals(candidate)
                && !candidate.isEmpty()
                && !candidate.equals(".")
                && !candidate.equals("..")
                && !candidate.contains("..")
                && !candidate.endsWith(".")
                && candidate.length() <= MAX_SLUG_LENGTH
                && !WINDOWS_RESERVED.matcher(candidate).matches()
                && SAFE_COMPONENT.matcher(candidate).matches()) {
            return candidate;
        }

        String slug = UNSAFE_RUN.matcher(candidate).replaceAll("-")
                .replaceAll("^-+|-+$", "")
                .toLowerCase(Locale.ROOT);
        if (slug.isEmpty()) {
            slug = "profile";
        }
        if (slug.length() > MAX_SLUG_LENGTH) {
            slug = slug.substring(0, MAX_SLUG_LENGTH);
        }
        // '~' fica fora de SAFE_COMPONENT: nenhum ID literal considerado seguro consegue
        // reivindicar o namespace gerado. IDs que contêm '~' entram novamente neste ramo e
        // recebem hash do valor bruto, evitando colisão slug-hash vs. literal slug-hash.
        return ENCODED_PREFIX + slug + "-" + sha256(raw);
    }

    static Path resolveUnder(Path root, String profileName) {
        Path normalizedRoot = normalizeRoot(root);
        return resolveComponentUnder(normalizedRoot,
                storageComponent(normalizedRoot, profileName, ""));
    }

    static Path resolveFileUnder(Path root, String profileName, String suffix) {
        String safeSuffix = Objects.requireNonNullElse(suffix, "");
        if (!safeSuffix.matches("[A-Za-z0-9._-]*")) {
            throw new IllegalArgumentException("Sufixo de profile inválido");
        }
        Path normalizedRoot = normalizeRoot(root);
        return resolveComponentUnder(normalizedRoot,
                storageComponent(normalizedRoot, profileName, safeSuffix));
    }

    static Path migrateLegacyDirectory(Path root, String profileName, Path target) {
        Path normalizedRoot = normalizeRoot(root);
        Path normalizedTarget = Objects.requireNonNull(target, "target").toAbsolutePath().normalize();
        if (!normalizedTarget.startsWith(normalizedRoot)) {
            throw new IllegalArgumentException("Destino de profile fora da raiz configurada");
        }
        String raw = Objects.requireNonNullElse(profileName, "");
        Path rawPath;
        try {
            rawPath = Path.of(raw);
        } catch (RuntimeException invalid) {
            return normalizedTarget;
        }
        if (rawPath.isAbsolute()) return normalizedTarget;
        Path cursor = normalizedRoot;
        for (Path component : rawPath) {
            if (".".equals(component.toString()) || "..".equals(component.toString())) {
                return normalizedTarget;
            }
            cursor = cursor.resolve(component);
            if (Files.isSymbolicLink(cursor)) {
                throw new IllegalStateException("Caminho legado do profile contém link simbólico");
            }
        }
        Path legacy = normalizedRoot.resolve(rawPath).normalize();
        if (!legacy.startsWith(normalizedRoot) || legacy.equals(normalizedTarget)
                || !Files.exists(legacy, java.nio.file.LinkOption.NOFOLLOW_LINKS)) {
            return normalizedTarget;
        }
        if (Files.isSymbolicLink(legacy)) {
            throw new IllegalStateException("Profile legado não pode ser link simbólico");
        }
        if (Files.exists(normalizedTarget, java.nio.file.LinkOption.NOFOLLOW_LINKS)) {
            throw new IllegalStateException("Profile legado e profile codificado existem simultaneamente");
        }
        try {
            Files.createDirectories(normalizedTarget.getParent());
            try {
                Files.move(legacy, normalizedTarget, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException unsupported) {
                Files.move(legacy, normalizedTarget);
            }
            return normalizedTarget;
        } catch (IOException e) {
            throw new IllegalStateException("Não foi possível migrar o diretório legado do profile", e);
        }
    }

    private static Path resolveComponentUnder(Path root, String component) {
        rejectCaseFoldAlias(root, component);
        Path resolved = root.resolve(component).normalize();
        if (!resolved.startsWith(root)) {
            throw new IllegalArgumentException("Identificador de profile escapou da raiz configurada");
        }
        if (Files.isSymbolicLink(resolved)) {
            throw new IllegalArgumentException("Diretório de profile não pode ser link simbólico");
        }
        return resolved;
    }

    private static Path normalizeRoot(Path root) {
        return Objects.requireNonNull(root, "root").toAbsolutePath().normalize();
    }

    private static String storageComponent(Path root, String profileName, String suffix) {
        String raw = Objects.requireNonNullElse(profileName, "");
        String safe = safeComponent(raw);
        String legacy = safe + suffix;
        if (!safe.equals(raw) || raw.equals(raw.toLowerCase(Locale.ROOT))) {
            return legacy;
        }
        if (hasExactEntry(root, legacy)) {
            return legacy;
        }
        return CASE_PREFIX + safe.toLowerCase(Locale.ROOT) + "-" + sha256(raw) + suffix;
    }

    private static boolean hasExactEntry(Path root, String component) {
        if (!Files.isDirectory(root)) {
            return false;
        }
        try (Stream<Path> entries = Files.list(root)) {
            return entries.map(path -> path.getFileName().toString())
                    .anyMatch(component::equals);
        } catch (IOException e) {
            throw new IllegalArgumentException("Não foi possível validar storage legado do profile", e);
        }
    }

    private static void rejectCaseFoldAlias(Path root, String component) {
        if (!Files.isDirectory(root)) {
            return;
        }
        try (Stream<Path> entries = Files.list(root)) {
            boolean alias = entries.map(path -> path.getFileName().toString())
                    .anyMatch(existing -> existing.equalsIgnoreCase(component)
                            && !existing.equals(component));
            if (alias) {
                throw new IllegalArgumentException(
                        "Identificador colide por maiúsculas/minúsculas com profile existente");
            }
        } catch (IOException e) {
            throw new IllegalArgumentException("Não foi possível validar aliases do profile", e);
        }
    }

    private static String sha256(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 indisponível", impossible);
        }
    }
}
