package io.github.selenium.javaDriverless.utils;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;

/**
 * Escrita de artefatos locais com dono restrito (0600) e recusa de symlink.
 * Extração fiel da lógica antes embutida em FingerprintMappingCaptureSupport.
 */
public final class OwnerOnlyFiles {

    private OwnerOnlyFiles() {
    }

    /**
     * Cria o arquivo já com 0600; se pré-existente, restringe antes de sobrescrever.
     * Recusa symlink para não seguir elo fora do diretório esperado.
     */
    public static void createOwnerOnly(Path path) throws IOException {
        try {
            // cria já com 0600: sem janela legível por outros entre escrita e chmod
            Files.createFile(path, java.nio.file.attribute.PosixFilePermissions.asFileAttribute(
                    java.nio.file.attribute.PosixFilePermissions.fromString("rw-------")));
        } catch (java.nio.file.FileAlreadyExistsException e) {
            // pré-existente: restringe antes de sobrescrever; falha real de IO propaga
            if (Files.isSymbolicLink(path)) {
                throw new IOException("Artefato de captura não pode ser link simbólico: " + path, e);
            }
            if (Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) {
                Files.setAttribute(path, "posix:permissions",
                        java.nio.file.attribute.PosixFilePermissions.fromString("rw-------"),
                        LinkOption.NOFOLLOW_LINKS);
            }
        } catch (UnsupportedOperationException e) {
            // FS sem POSIX (ex.: NTFS): mantém as permissões padrão.
            try {
                Files.createFile(path);
            } catch (java.nio.file.FileAlreadyExistsException ignored) {
                // corrida rara: a escrita abaixo apenas truncará o arquivo existente.
            }
        }
    }

    /** Escreve texto UTF-8 truncando o arquivo, sem seguir symlink. */
    public static void writeString(Path path, String content) throws IOException {
        createOwnerOnly(path);
        Files.writeString(path, content == null ? "" : content, StandardCharsets.UTF_8,
                StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING,
                LinkOption.NOFOLLOW_LINKS);
    }

    /** Escreve bytes truncando o arquivo, sem seguir symlink. */
    public static void writeBytes(Path path, byte[] content) throws IOException {
        createOwnerOnly(path);
        Files.write(path, content, StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING,
                LinkOption.NOFOLLOW_LINKS);
    }
}
