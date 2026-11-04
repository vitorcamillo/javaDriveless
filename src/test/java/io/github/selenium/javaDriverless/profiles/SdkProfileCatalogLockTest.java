package io.github.selenium.javaDriverless.profiles;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

class SdkProfileCatalogLockTest {

    @Test
    void lockRefusesSymlinkPointingOutsideRoot(@TempDir Path root) throws Exception {
        Path external = Files.createTempFile("jd-lock-target-", ".bin");
        try {
            String conteudo = Files.readString(external);
            Files.createSymbolicLink(root.resolve(".catalog.lock"), external.toAbsolutePath());
            assertThatThrownBy(() -> SdkProfileCatalogLock.with(root, () -> "ok"))
                    .isInstanceOf(ProfileCatalogException.class)
                    .hasMessageContaining("Failed to lock profile catalog");
            // O alvo externo não pode ter sido usado como lock nem alterado.
            assertThat(Files.readString(external)).isEqualTo(conteudo);
        } finally {
            Files.deleteIfExists(external);
        }
    }

    @Test
    void lockAllowsPlainRegularFileAndReusesExistingLock(@TempDir Path root) {
        assertThat(SdkProfileCatalogLock.with(root, () -> "primeiro")).isEqualTo("primeiro");
        // Segunda chamada reusa o .catalog.lock pré-existente (caminho CREATE_NEW -> open existente).
        assertThat(SdkProfileCatalogLock.with(root, () -> "segundo")).isEqualTo("segundo");
    }

    @Test
    void lockNasceComPermissao0600(@TempDir Path root) throws Exception {
        assumeTrue(FileSystems.getDefault().supportedFileAttributeViews().contains("posix"),
                "Requer FS com atributos POSIX");
        SdkProfileCatalogLock.with(root, () -> "ok");
        Path lock = root.resolve(".catalog.lock");
        assertEquals("rw-------",
                PosixFilePermissions.toString(Files.getPosixFilePermissions(lock)));
    }

    @Test
    void lockLegado0644EAceitoERestringidoPara0600(@TempDir Path root) throws Exception {
        assumeTrue(FileSystems.getDefault().supportedFileAttributeViews().contains("posix"),
                "Requer FS com atributos POSIX");
        Path lock = root.resolve(".catalog.lock");
        Files.write(lock, new byte[0]);
        Files.setPosixFilePermissions(lock, PosixFilePermissions.fromString("rw-r--r--"));

        assertThat(SdkProfileCatalogLock.with(root, () -> "aceito")).isEqualTo("aceito");
        // restringido na abertura para eliminar lock compartilhado entre usuários locais
        assertEquals("rw-------",
                PosixFilePermissions.toString(Files.getPosixFilePermissions(lock)));
    }
}
