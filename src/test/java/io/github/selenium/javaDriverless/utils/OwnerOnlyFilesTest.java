package io.github.selenium.javaDriverless.utils;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.PosixFilePermissions;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class OwnerOnlyFilesTest {

    private static void assumePosix() {
        // FS sem POSIX (ex.: NTFS) não tem perms rw-------; pula em vez de falhar.
        org.junit.jupiter.api.Assumptions.assumeTrue(
                FileSystems.getDefault().supportedFileAttributeViews().contains("posix"));
    }

    /** Nenhum temp ".owner-only-*" sobrou no diretório de destino. */
    private static void assertSemTempOwnerOnly(Path dir) throws IOException {
        try (var arquivos = Files.list(dir)) {
            assertThat(arquivos
                    .filter(p -> p.getFileName().toString().startsWith(".owner-only-"))
                    .findAny())
                    .isEmpty();
        }
    }

    @Test
    void arquivoNovoNasce0600(@TempDir Path dir) throws Exception {
        assumePosix();
        Path target = dir.resolve("novo.txt");

        OwnerOnlyFiles.writeString(target, "ola");

        assertThat(Files.readString(target, StandardCharsets.UTF_8)).isEqualTo("ola");
        assertThat(Files.getAttribute(target, "posix:permissions"))
                .isEqualTo(PosixFilePermissions.fromString("rw-------"));
    }

    @Test
    void arquivoPreExistente0644Vira0600ETruncado(@TempDir Path dir) throws Exception {
        assumePosix();
        Path target = dir.resolve("legado.txt");
        Files.writeString(target, "conteudo-antigo-bem-longo", StandardCharsets.UTF_8);
        Files.setPosixFilePermissions(target, PosixFilePermissions.fromString("rw-r--r--"));

        OwnerOnlyFiles.writeString(target, "novo");
        OwnerOnlyFiles.writeBytes(dir.resolve("bin.dat"), new byte[]{1, 2, 3});

        assertThat(Files.readString(target, StandardCharsets.UTF_8)).isEqualTo("novo");
        assertThat(Files.getPosixFilePermissions(target))
                .isEqualTo(PosixFilePermissions.fromString("rw-------"));
        assertThat(Files.readAllBytes(dir.resolve("bin.dat"))).isEqualTo(new byte[]{1, 2, 3});
        assertThat(Files.getPosixFilePermissions(dir.resolve("bin.dat")))
                .isEqualTo(PosixFilePermissions.fromString("rw-------"));
    }

    @Test
    void symlinkPreExistenteEhRecusadoEAlvoNaoMuda(@TempDir Path dir) throws Exception {
        Path alvo = dir.resolve("alvo.txt");
        Files.writeString(alvo, "vitima", StandardCharsets.UTF_8);
        Path elo = dir.resolve("elo.txt");
        Files.createSymbolicLink(elo, alvo);

        assertThatThrownBy(() -> OwnerOnlyFiles.writeString(elo, "atacante"))
                .isInstanceOf(java.io.IOException.class);
        assertThatThrownBy(() -> OwnerOnlyFiles.writeBytes(elo, new byte[]{9}))
                .isInstanceOf(java.io.IOException.class);
        // alvo intacto: nada foi escrito através do elo.
        assertThat(Files.readString(alvo, StandardCharsets.UTF_8)).isEqualTo("vitima");
    }

    @Test
    void createOwnerOnlyRestringePreExistente0644SemTruncar(@TempDir Path dir) throws Exception {
        assumePosix();
        Path target = dir.resolve("pre-dono.txt");
        Files.writeString(target, "legado", StandardCharsets.UTF_8);
        Files.setPosixFilePermissions(target, PosixFilePermissions.fromString("rw-r--r--"));

        OwnerOnlyFiles.createOwnerOnly(target);

        assertThat(Files.getPosixFilePermissions(target))
                .isEqualTo(PosixFilePermissions.fromString("rw-------"));
        assertThat(Files.readString(target, StandardCharsets.UTF_8)).isEqualTo("legado");
        assertSemTempOwnerOnly(dir);
    }

    @Test
    void createOwnerOnlyRecusaSymlinkENaoAlteraPermissoesDoAlvo(@TempDir Path dir) throws Exception {
        assumePosix();
        Path alvo = dir.resolve("alvo-owner.txt");
        Files.writeString(alvo, "vitima", StandardCharsets.UTF_8);
        Files.setPosixFilePermissions(alvo, PosixFilePermissions.fromString("rw-r--r--"));
        Path elo = dir.resolve("elo-owner.txt");
        Files.createSymbolicLink(elo, alvo);

        assertThatThrownBy(() -> OwnerOnlyFiles.createOwnerOnly(elo))
                .isInstanceOf(IOException.class);

        assertThat(Files.getPosixFilePermissions(alvo))
                .isEqualTo(PosixFilePermissions.fromString("rw-r--r--"));
        assertThat(Files.readString(alvo, StandardCharsets.UTF_8)).isEqualTo("vitima");
        assertSemTempOwnerOnly(dir);
    }

    @Test
    void writeAtomicallyTrocaInodeDeArquivoPreExistente(@TempDir Path dir) throws Exception {
        assumePosix();
        Path target = dir.resolve("inode.txt");
        Files.writeString(target, "antigo", StandardCharsets.UTF_8);
        Object keyAntes = Files.readAttributes(target, BasicFileAttributes.class,
                LinkOption.NOFOLLOW_LINKS).fileKey();
        org.junit.jupiter.api.Assumptions.assumeTrue(keyAntes != null);

        OwnerOnlyFiles.writeAtomically(target,
                out -> out.write("novo".getBytes(StandardCharsets.UTF_8)));

        Object keyDepois = Files.readAttributes(target, BasicFileAttributes.class,
                LinkOption.NOFOLLOW_LINKS).fileKey();
        assertThat(keyDepois).isNotEqualTo(keyAntes);
        assertThat(Files.readString(target, StandardCharsets.UTF_8)).isEqualTo("novo");
        assertThat(Files.getPosixFilePermissions(target))
                .isEqualTo(PosixFilePermissions.fromString("rw-------"));
    }

    @Test
    void writerQueLancaPreservaConteudoAntigoENaoDeixaTemp(@TempDir Path dir) throws Exception {
        Path target = dir.resolve("falha.txt");
        Files.writeString(target, "conteudo-antigo", StandardCharsets.UTF_8);

        assertThatThrownBy(() -> OwnerOnlyFiles.writeAtomically(target, out -> {
            throw new IOException("boom");
        })).isInstanceOf(IOException.class);

        assertThat(Files.readString(target, StandardCharsets.UTF_8)).isEqualTo("conteudo-antigo");
        assertSemTempOwnerOnly(dir);
    }

    @Test
    void writeAtomicallyRecusaSymlinkSemAlterarAlvo(@TempDir Path dir) throws Exception {
        Path alvo = dir.resolve("alvo-atomico.txt");
        Files.writeString(alvo, "vitima", StandardCharsets.UTF_8);
        Path elo = dir.resolve("elo-atomico.txt");
        Files.createSymbolicLink(elo, alvo);

        assertThatThrownBy(() -> OwnerOnlyFiles.writeAtomically(elo,
                out -> out.write("atacante".getBytes(StandardCharsets.UTF_8))))
                .isInstanceOf(IOException.class)
                .hasMessageContaining("link simbólico");

        assertThat(Files.readString(alvo, StandardCharsets.UTF_8)).isEqualTo("vitima");
        assertSemTempOwnerOnly(dir);
    }

    @Test
    void writeAtomicallyRecusaDiretorioComoDestino(@TempDir Path dir) throws Exception {
        Path sub = dir.resolve("subdir");
        Files.createDirectory(sub);

        assertThatThrownBy(() -> OwnerOnlyFiles.writeAtomically(sub,
                out -> out.write("x".getBytes(StandardCharsets.UTF_8))))
                .isInstanceOf(IOException.class);

        assertSemTempOwnerOnly(dir);
    }
}
