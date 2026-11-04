package io.github.selenium.javaDriverless.types;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.util.Set;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ChromeOptionsDirectoriesPermissionTest {

    @Test
    void createsProfileDirectoryWithOwnerOnlyPermissions(@TempDir Path temp) throws Exception {
        assumeTrue(FileSystems.getDefault().supportedFileAttributeViews().contains("posix"));

        Path target = temp.resolve("a").resolve("b");
        ChromeOptionsDirectories.ensureDirectory(target.toString());

        assertThat(Files.getPosixFilePermissions(target))
                .isEqualTo(Set.of(
                        PosixFilePermission.OWNER_READ,
                        PosixFilePermission.OWNER_WRITE,
                        PosixFilePermission.OWNER_EXECUTE));
    }

    @Test
    void keepsPermissionsOfPreExistingDirectory(@TempDir Path temp) throws Exception {
        assumeTrue(FileSystems.getDefault().supportedFileAttributeViews().contains("posix"));

        Path existing = temp.resolve("existente");
        Files.createDirectory(existing);
        Set<PosixFilePermission> permissions755 = Set.of(
                PosixFilePermission.OWNER_READ,
                PosixFilePermission.OWNER_WRITE,
                PosixFilePermission.OWNER_EXECUTE,
                PosixFilePermission.GROUP_READ,
                PosixFilePermission.GROUP_EXECUTE,
                PosixFilePermission.OTHERS_READ,
                PosixFilePermission.OTHERS_EXECUTE);
        Files.setPosixFilePermissions(existing, permissions755);

        ChromeOptionsDirectories.ensureDirectory(existing.toString());

        assertThat(Files.getPosixFilePermissions(existing)).isEqualTo(permissions755);
    }
}
