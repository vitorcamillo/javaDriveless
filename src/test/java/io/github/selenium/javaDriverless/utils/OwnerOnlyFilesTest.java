package io.github.selenium.javaDriverless.utils;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class OwnerOnlyFilesTest {

    private static void assumePosix() {
        // FS sem POSIX (ex.: NTFS) não tem perms rw-------; pula em vez de falhar.
        org.junit.jupiter.api.Assumptions.assumeTrue(
                FileSystems.getDefault().supportedFileAttributeViews().contains("posix"));
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
}
