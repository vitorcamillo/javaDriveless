package io.github.selenium.javaDriverless.profiles;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Supplier;

/** JVM and filesystem locking boundary for a profile catalog root. */
final class SdkProfileCatalogLock {
    private static final Map<Path, ReentrantLock> LOCKS = new ConcurrentHashMap<>();
    private static final String LOCK_FILE = ".catalog.lock";

    private SdkProfileCatalogLock() {}

    static <T> T with(Path root, Supplier<T> operation) {
        ReentrantLock processLock = LOCKS.computeIfAbsent(root, ignored -> new ReentrantLock());
        if (processLock.isHeldByCurrentThread()) return operation.get();
        processLock.lock();
        try {
            Files.createDirectories(root);
            Path lockPath = root.resolve(LOCK_FILE);
            try (FileChannel channel = openLockFile(lockPath);
                 FileLock ignored = channel.lock()) {
                return operation.get();
            }
        } catch (IOException e) {
            throw new ProfileCatalogException("Failed to lock profile catalog " + root, e);
        } finally {
            processLock.unlock();
        }
    }

    /** FS default suporta atributos POSIX? (define se o lock nasce 0600) */
    private static final boolean POSIX_SUPPORTED =
            java.nio.file.FileSystems.getDefault().supportedFileAttributeViews().contains("posix");

    /**
     * Cria (0600 em FS POSIX) ou abre o arquivo de lock sem seguir symlink.
     * O_NOFOLLOW recusa o elo, impedindo que a exclusão mútua incida sobre
     * arquivo externo controlável por tampering local. Mesmo padrão de
     * {@link io.github.selenium.javaDriverless.utils.OwnerOnlyFiles}.
     */
    private static FileChannel openLockFile(Path lockPath) throws IOException {
        IOException lastFailure = null;
        for (int tentativa = 0; tentativa < 3; tentativa++) {
            try {
                return createNewNoFollow(lockPath);
            } catch (FileAlreadyExistsException existing) {
                try {
                    return openExistingNoFollow(lockPath);
                } catch (NoSuchFileException vanished) {
                    // corrida EEXIST -> exclusão de terceiro: recria na próxima tentativa
                    lastFailure = vanished;
                }
            }
        }
        throw lastFailure != null ? lastFailure
                : new IOException("Falha ao abrir arquivo de lock: " + lockPath);
    }

    private static FileChannel createNewNoFollow(Path lockPath) throws IOException {
        if (POSIX_SUPPORTED) {
            return FileChannel.open(lockPath,
                    Set.of(StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE,
                            LinkOption.NOFOLLOW_LINKS),
                    PosixFilePermissions.asFileAttribute(
                            PosixFilePermissions.fromString("rw-------")));
        }
        return FileChannel.open(lockPath, StandardOpenOption.CREATE_NEW,
                StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS);
    }

    private static FileChannel openExistingNoFollow(Path lockPath) throws IOException {
        // WRITE + NOFOLLOW_LINKS falha com FileSystemException se o caminho for symlink.
        FileChannel channel = FileChannel.open(lockPath, StandardOpenOption.WRITE,
                LinkOption.NOFOLLOW_LINKS);
        // Lock legado pode ter nascido 0644 (versões anteriores): restringe sem seguir symlink.
        try {
            Files.setAttribute(lockPath, "posix:permissions",
                    PosixFilePermissions.fromString("rw-------"), LinkOption.NOFOLLOW_LINKS);
        } catch (UnsupportedOperationException nonPosix) {
            // FS sem POSIX: mantém as permissões padrão.
        }
        return channel;
    }
}
