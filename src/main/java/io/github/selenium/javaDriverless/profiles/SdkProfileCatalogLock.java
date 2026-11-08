package io.github.selenium.javaDriverless.profiles;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Duration;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Supplier;

/** JVM and filesystem locking boundary for a profile catalog root. */
final class SdkProfileCatalogLock {
    private static final Map<Path, ReentrantLock> LOCKS = new ConcurrentHashMap<>();
    private static final String LOCK_FILE = ".catalog.lock";
    static final Duration DEFAULT_TIMEOUT = Duration.ofSeconds(60);
    private static final long POLL_MS = 25;

    private SdkProfileCatalogLock() {}

    static <T> T with(Path root, Supplier<T> operation) {
        return with(root, DEFAULT_TIMEOUT, operation);
    }

    static <T> T with(Path root, Duration timeout, Supplier<T> operation) {
        ReentrantLock processLock = LOCKS.computeIfAbsent(root, ignored -> new ReentrantLock());
        // Atalho reentrante: chamada aninhada na mesma thread não disputa locks.
        if (processLock.isHeldByCurrentThread()) return operation.get();
        // Deadline único cobre as duas fases: ReentrantLock e depois FileLock.
        long deadline = System.nanoTime() + timeout.toNanos();
        boolean acquired = false;
        try {
            acquired = aguardarProcessLock(processLock, root, timeout, deadline);
            Files.createDirectories(root);
            Path lockPath = root.resolve(LOCK_FILE);
            try (FileChannel channel = openLockFile(lockPath)) {
                return sobFileLock(channel, root, timeout, deadline, operation);
            }
        } catch (IOException e) {
            throw new ProfileCatalogException("Failed to lock profile catalog " + root, e);
        } finally {
            // Unlock só quando este fluxo de fato adquiriu o processLock.
            if (acquired) processLock.unlock();
        }
    }

    /** Fase 1: tryLock do ReentrantLock dentro do restante do deadline. */
    private static boolean aguardarProcessLock(ReentrantLock processLock, Path root,
            Duration timeout, long deadline) {
        long restanteMs = TimeUnit.NANOSECONDS.toMillis(Math.max(0, deadline - System.nanoTime()));
        try {
            if (processLock.tryLock(restanteMs, TimeUnit.MILLISECONDS)) return true;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ProfileCatalogException(
                    "Interrupted while waiting for profile catalog lock " + root, e);
        }
        throw estouro(root, timeout);
    }

    /** Fase 2: laço channel.tryLock() até o deadline; null/Overlapping = ocupado, espera curta. */
    private static <T> T sobFileLock(FileChannel channel, Path root, Duration timeout,
            long deadline, Supplier<T> operation) throws IOException {
        while (true) {
            FileLock lock = null;
            try {
                lock = channel.tryLock();
            } catch (OverlappingFileLockException ocupado) {
                // Lock da mesma JVM em outro canal: trata como ocupado e aguarda.
            }
            if (lock != null) {
                try {
                    return operation.get();
                } finally {
                    lock.release();
                }
            }
            long restante = deadline - System.nanoTime();
            if (restante <= 0) throw estouro(root, timeout);
            try {
                Thread.sleep(Math.min(POLL_MS, TimeUnit.NANOSECONDS.toMillis(restante)));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new ProfileCatalogException(
                        "Interrupted while waiting for profile catalog lock " + root, e);
            }
        }
    }

    private static ProfileCatalogException estouro(Path root, Duration timeout) {
        return new ProfileCatalogException("Timed out waiting for profile catalog lock " + root
                + " after " + timeout.toMillis() + " ms");
    }

    /** O FS de lockPath suporta atributos POSIX? (define se o lock nasce/restringe a 0600) */
    private static boolean posixSuportado(Path lockPath) {
        return lockPath.getFileSystem().supportedFileAttributeViews().contains("posix");
    }

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
        if (posixSuportado(lockPath)) {
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
        } catch (IOException failure) {
            // Sem fechar aqui, o canal vaza (AccessDenied em lock alheio, NoSuchFile na corrida).
            channel.close();
            throw failure;
        }
        return channel;
    }
}
