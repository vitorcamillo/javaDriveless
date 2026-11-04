package io.github.selenium.javaDriverless;

import io.github.selenium.javaDriverless.logging.JavaDriverlessLogger;
import org.slf4j.Logger;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.ByteBuffer;
import java.nio.channels.SeekableByteChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.Set;

final class ProfileStartupFiles {
    private static final Logger logger = JavaDriverlessLogger.getLogger(ProfileStartupFiles.class);
    private static final boolean UNLOCK_STALE_PROFILE_LOCKS = envBool(
            "JAVA_DRIVERLESS_UNLOCK_STALE_PROFILE_LOCKS", false);

    private ProfileStartupFiles() {}

    static void deleteDirectory(Path directory) throws IOException {
        if (!Files.exists(directory)) {
            return;
        }
        Files.walkFileTree(directory, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                deleteIfExistsQuietly(file);
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult postVisitDirectory(Path dir, IOException exc) {
                deleteIfExistsQuietly(dir);
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFileFailed(Path file, IOException exc) {
                deleteIfExistsQuietly(file);
                return FileVisitResult.CONTINUE;
            }
        });
    }

    static boolean unlockStaleLocks(Path userDataDir) {
        return unlockStaleLocks(userDataDir, UNLOCK_STALE_PROFILE_LOCKS, () -> {});
    }

    static boolean unlockStaleLocks(Path userDataDir, Runnable beforeFirstDeletion) {
        return unlockStaleLocks(userDataDir, true, beforeFirstDeletion);
    }

    static boolean unlockStaleLocks(
            Path userDataDir, boolean allowUnsafeStaleUnlock, Runnable beforeFirstDeletion) {
        if (userDataDir == null) {
            return true;
        }
        Objects.requireNonNull(beforeFirstDeletion, "beforeFirstDeletion");

        Path profileDir = userDataDir.toAbsolutePath().normalize();
        if (!Files.exists(profileDir) || !Files.isDirectory(profileDir)) {
            return true;
        }

        Path lockFile = profileDir.resolve("SingletonLock");
        Optional<LockSnapshot> initialSnapshot = captureLockSnapshot(lockFile);
        if (initialSnapshot.isEmpty()) {
            return !hasReachableDevTools(profileDir.resolve("DevToolsActivePort"));
        }
        if (!allowUnsafeStaleUnlock
                || lockState(initialSnapshot.get()) != LockState.PROVEN_STALE
                || hasReachableDevTools(profileDir.resolve("DevToolsActivePort"))) {
            JavaDriverlessLogger.profile(logger,
                    "Locks do profile preservados; auto-unlock exige opt-in e owner stale comprovado em {}",
                    profileDir);
            return false;
        }

        beforeFirstDeletion.run();

        int removed = 0;
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(profileDir, "Singleton*")) {
            for (Path startupFile : stream) {
                if (startupFile.toAbsolutePath().normalize().equals(lockFile)) {
                    continue;
                }
                if (!deletionStillSafe(profileDir, lockFile, initialSnapshot.get())) {
                    logConcurrentLockChange(profileDir);
                    return false;
                }
                if (deleteProfileStartupFile(startupFile)) {
                    removed++;
                }
            }
        } catch (IOException e) {
            JavaDriverlessLogger.warn(logger, "Nao foi possivel listar locks do profile {}: {}", profileDir, e.getMessage());
            return false;
        }

        Path devToolsPort = profileDir.resolve("DevToolsActivePort");
        if (!deletionStillSafe(profileDir, lockFile, initialSnapshot.get())) {
            logConcurrentLockChange(profileDir);
            return false;
        }
        if (deleteProfileStartupFile(devToolsPort)) {
            removed++;
        }

        if (!deletionStillSafe(profileDir, lockFile, initialSnapshot.get())) {
            logConcurrentLockChange(profileDir);
            return false;
        }
        boolean lockRemoved = deleteProfileStartupFile(lockFile);
        if (lockRemoved) {
            removed++;
        }

        if (removed > 0) {
            JavaDriverlessLogger.info(logger, "Removidos {} arquivos stale do profile antes do startup: {}", removed, profileDir);
        }
        return lockRemoved || !Files.exists(lockFile, LinkOption.NOFOLLOW_LINKS);
    }

    private static void deleteIfExistsQuietly(Path path) {
        try {
            Files.deleteIfExists(path);
        } catch (IOException ignored) {
            // Chrome can remove or keep transient profile files while shutdown cleanup walks them.
        }
    }

    private static boolean deleteProfileStartupFile(Path file) {
        try {
            if (Files.deleteIfExists(file)) {
                JavaDriverlessLogger.profile(logger, "Arquivo stale removido do profile: {}", file.getFileName());
                return true;
            }
        } catch (IOException e) {
            JavaDriverlessLogger.warn(logger, "Nao foi possivel remover arquivo stale {}: {}", file, e.getMessage());
        }
        return false;
    }

    private static boolean deletionStillSafe(Path profileDir, Path lockFile, LockSnapshot initialSnapshot) {
        Optional<LockSnapshot> currentSnapshot = captureLockSnapshot(lockFile);
        return currentSnapshot.filter(initialSnapshot::equals).isPresent()
                && lockState(currentSnapshot.get()) == LockState.PROVEN_STALE
                && !hasReachableDevTools(profileDir.resolve("DevToolsActivePort"));
    }

    private static void logConcurrentLockChange(Path profileDir) {
        JavaDriverlessLogger.warn(logger,
                "Remocao de locks stale abortada: SingletonLock mudou durante a validacao em {}",
                profileDir);
    }

    private static LockState lockState(LockSnapshot snapshot) {
        if (snapshot.ownerPid().isEmpty()) {
            return LockState.UNKNOWN;
        }
        return ProcessHandle.of(snapshot.ownerPid().getAsLong()).filter(ProcessHandle::isAlive).isPresent()
                ? LockState.ACTIVE
                : LockState.PROVEN_STALE;
    }

    private static Optional<LockSnapshot> captureLockSnapshot(Path lockFile) {
        if (!Files.exists(lockFile, LinkOption.NOFOLLOW_LINKS)) {
            return Optional.empty();
        }
        try {
            BasicFileAttributes before = Files.readAttributes(
                    lockFile, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
            boolean symbolicLink = before.isSymbolicLink();
            String owner = symbolicLink
                    ? Files.readSymbolicLink(lockFile).toString()
                    : readRegularLockOwner(lockFile);
            BasicFileAttributes after = Files.readAttributes(
                    lockFile, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
            AttributeSnapshot beforeAttributes = AttributeSnapshot.from(before);
            AttributeSnapshot afterAttributes = AttributeSnapshot.from(after);
            if (!beforeAttributes.equals(afterAttributes) || symbolicLink != after.isSymbolicLink()) {
                return Optional.empty();
            }
            return Optional.of(new LockSnapshot(
                    symbolicLink,
                    owner,
                    parseOwnerPid(owner),
                    afterAttributes));
        } catch (IOException unreadable) {
            return Optional.empty();
        }
    }

    private static String readRegularLockOwner(Path lockFile) throws IOException {
        ByteBuffer buffer = ByteBuffer.allocate(4097);
        try (SeekableByteChannel channel = Files.newByteChannel(
                lockFile, Set.of(StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS))) {
            while (buffer.hasRemaining() && channel.read(buffer) != -1) {
                // SingletonLock is a tiny owner marker; a bounded read avoids following or loading hostile files.
            }
        }
        if (buffer.position() > 4096) {
            throw new IOException("SingletonLock excede o tamanho maximo esperado");
        }
        buffer.flip();
        return StandardCharsets.UTF_8.decode(buffer).toString().trim();
    }

    private static OptionalLong parseOwnerPid(String owner) {
        try {
            int separator = owner.lastIndexOf('-');
            String pid = separator >= 0 ? owner.substring(separator + 1) : owner;
            long parsed = Long.parseLong(pid.trim());
            return parsed > 0 ? OptionalLong.of(parsed) : OptionalLong.empty();
        } catch (NumberFormatException invalidPid) {
            return OptionalLong.empty();
        }
    }

    private static boolean hasReachableDevTools(Path portFile) {
        if (!Files.isRegularFile(portFile)) {
            return false;
        }
        try {
            String firstLine = Files.readAllLines(portFile).stream().findFirst().orElse("").trim();
            int port = Integer.parseInt(firstLine);
            if (port <= 0 || port > 65_535) {
                return false;
            }
            try (Socket socket = new Socket()) {
                socket.connect(new InetSocketAddress(java.net.InetAddress.getLoopbackAddress(), port), 200);
                return true;
            }
        } catch (IOException | NumberFormatException unreachable) {
            return false;
        }
    }

    private enum LockState {
        ACTIVE,
        PROVEN_STALE,
        UNKNOWN
    }

    private record LockSnapshot(
            boolean symbolicLink,
            String ownerEvidence,
            OptionalLong ownerPid,
            AttributeSnapshot attributes) {}

    private record AttributeSnapshot(
            Object fileKey,
            long size,
            java.nio.file.attribute.FileTime creationTime,
            java.nio.file.attribute.FileTime lastModifiedTime) {
        private static AttributeSnapshot from(BasicFileAttributes attributes) {
            return new AttributeSnapshot(
                    attributes.fileKey(),
                    attributes.size(),
                    attributes.creationTime(),
                    attributes.lastModifiedTime());
        }
    }

    private static boolean envBool(String key, boolean defaultValue) {
        String raw = System.getenv(key);
        if (raw == null || raw.isBlank()) {
            return defaultValue;
        }
        String value = raw.trim().toLowerCase(Locale.ROOT);
        return value.equals("1") || value.equals("true") || value.equals("yes") || value.equals("on");
    }
}
