package io.github.selenium.javaDriverless.utils;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.PosixFilePermissions;
import java.nio.file.attribute.UserPrincipal;

/**
 * Escrita de artefatos locais com dono restrito (0600) e recusa de symlink.
 * Extração fiel da lógica antes embutida em FingerprintMappingCaptureSupport.
 */
public final class OwnerOnlyFiles {

    /** Função que escreve o conteúdo no stream de destino. */
    @FunctionalInterface
    public interface OutputWriter {
        void write(OutputStream out) throws IOException;
    }

    /** Usuário do processo, detectado uma única vez via arquivo temporário de sonda. */
    private static final class ProcessUserHolder {
        private static final UserPrincipal USUARIO = detectar();

        private static UserPrincipal detectar() {
            Path probe = null;
            try {
                probe = Files.createTempFile("owner-only-probe", ".tmp");
                return Files.getOwner(probe);
            } catch (IOException | UnsupportedOperationException e) {
                // usuário indeterminado: checagem de dono fica desativada
                return null;
            } finally {
                if (probe != null) {
                    try {
                        Files.deleteIfExists(probe);
                    } catch (IOException ignored) {
                        // sonda em temp-dir: falha ao apagar é inofensiva
                    }
                }
            }
        }
    }

    private OwnerOnlyFiles() {
    }

    /**
     * Cria o arquivo já com 0600; se pré-existente, restringe antes de sobrescrever.
     * Recusa symlink para não seguir elo fora do diretório esperado.
     */
    public static void createOwnerOnly(Path path) throws IOException {
        try {
            // cria já com 0600: sem janela legível por outros entre escrita e chmod
            Files.createFile(path, PosixFilePermissions.asFileAttribute(
                    PosixFilePermissions.fromString("rw-------")));
        } catch (java.nio.file.FileAlreadyExistsException e) {
            // pré-existente: restringe antes de sobrescrever; falha real de IO propaga
            if (Files.isSymbolicLink(path)) {
                throw new IOException("Artefato de captura não pode ser link simbólico: " + path, e);
            }
            if (Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) {
                exigirMesmoDono(path);
                Files.setAttribute(path, "posix:permissions",
                        PosixFilePermissions.fromString("rw-------"),
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

    /**
     * Escreve via temp 0600 no mesmo diretório e rename atômico: o inode trocado
     * garante que quem pré-criou o arquivo não receba o novo conteúdo.
     */
    public static void writeAtomically(Path path, OutputWriter writer) throws IOException {
        Path abs = path.toAbsolutePath().normalize();
        if (Files.isSymbolicLink(abs)) {
            throw new IOException("Artefato de captura não pode ser link simbólico: " + abs);
        }
        if (Files.exists(abs, LinkOption.NOFOLLOW_LINKS)
                && !Files.isRegularFile(abs, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("Artefato de captura não é arquivo regular: " + abs);
        }
        Path temp = criarTemp0600(abs.getParent());
        try {
            try (OutputStream out = Files.newOutputStream(temp, StandardOpenOption.WRITE,
                    StandardOpenOption.TRUNCATE_EXISTING, LinkOption.NOFOLLOW_LINKS)) {
                writer.write(out);
            }
            // rename troca o inode: dono anterior não lê o novo conteúdo
            Files.move(temp, abs, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException | RuntimeException e) {
            apagarTemp(temp);
            throw e;
        }
    }

    /** Escreve texto UTF-8 atomicamente, sem seguir symlink. */
    public static void writeString(Path path, String content) throws IOException {
        String dados = content == null ? "" : content;
        writeAtomically(path, out -> out.write(dados.getBytes(StandardCharsets.UTF_8)));
    }

    /** Escreve bytes atomicamente, sem seguir symlink. */
    public static void writeBytes(Path path, byte[] content) throws IOException {
        writeAtomically(path, out -> out.write(content));
    }

    /** Temp 0600 no diretório de destino; FS sem POSIX usa o padrão restrito do usuário. */
    private static Path criarTemp0600(Path parent) throws IOException {
        try {
            return Files.createTempFile(parent, ".owner-only-", ".tmp",
                    PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")));
        } catch (UnsupportedOperationException e) {
            return Files.createTempFile(parent, ".owner-only-", ".tmp");
        }
    }

    /** Falhou: não deixa temp órfão no diretório de destino. */
    private static void apagarTemp(Path temp) {
        try {
            Files.deleteIfExists(temp);
        } catch (IOException ignored) {
            // melhor esforço; a exceção original é a que importa
        }
    }

    /** Recusa pré-existente pertencente a outro usuário (não mexe em artefato alheio). */
    private static void exigirMesmoDono(Path path) throws IOException {
        UserPrincipal dono;
        try {
            dono = Files.getOwner(path, LinkOption.NOFOLLOW_LINKS);
        } catch (UnsupportedOperationException e) {
            return; // FS sem noção de dono: pula a checagem
        }
        UserPrincipal usuario = ProcessUserHolder.USUARIO;
        if (dono != null && usuario != null && !dono.equals(usuario)) {
            throw new IOException("Artefato pré-existente pertence a outro usuário: " + path);
        }
    }
}
