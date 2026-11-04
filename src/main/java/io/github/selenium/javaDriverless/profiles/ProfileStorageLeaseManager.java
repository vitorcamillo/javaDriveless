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
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

final class ProfileStorageLeaseManager {
    private static final String LEASE_FILE = ".javadriveless.storage.lease";
    private static final Object JVM_LOCK = new Object();
    private static final Map<Path, SharedState> JVM_SHARED_LOCKS = new HashMap<>();

    private ProfileStorageLeaseManager() {
    }

    static Lease acquire(Collection<Path> directories) {
        Map<Path, TargetPlan> unique = new LinkedHashMap<>();
        for (Path directory : directories) {
            if (directory == null) continue;
            TargetPlan plan = inspectTarget(directory);
            unique.putIfAbsent(plan.path, plan);
        }
        Map<Path, Mode> requirements = new HashMap<>();
        for (TargetPlan plan : unique.values()) {
            List<Path> chain = plan.lockChain;
            for (int index = 0; index < chain.size(); index++) {
                Mode requested = index == chain.size() - 1 ? Mode.EXCLUSIVE : Mode.SHARED;
                requirements.merge(chain.get(index), requested,
                        (existing, candidate) -> existing == Mode.EXCLUSIVE ? existing : candidate);
            }
        }
        List<Map.Entry<Path, Mode>> ordered = requirements.entrySet().stream()
                .sorted(Comparator.<Map.Entry<Path, Mode>>comparingInt(entry -> entry.getKey().getNameCount())
                        .thenComparing(entry -> entry.getKey().toString().toLowerCase(Locale.ROOT))
                        .thenComparing(entry -> entry.getKey().toString()))
                .toList();
        List<LeasePart> held = new ArrayList<>();
        try {
            for (Map.Entry<Path, Mode> entry : ordered) {
                ensureDirectoryForLock(entry.getKey());
                held.add(acquireOne(entry.getKey(), entry.getValue()));
                TargetPlan target = unique.get(entry.getKey());
                if (target != null) {
                    target.lockedIdentity = captureIdentity(target.path);
                    if (target.expectedIdentity != null) {
                        requireMatchingIdentity(target.expectedIdentity, target.lockedIdentity);
                    }
                }
            }
            unique.values().forEach(target -> requireSameIdentity(target.lockedIdentity));
            return new Lease(held);
        } catch (RuntimeException failure) {
            closeAll(held);
            throw failure;
        }
    }

    private static LeasePart acquireOne(Path directory, Mode mode) {
        Path leaseFile = directory.resolve(LEASE_FILE);
        synchronized (JVM_LOCK) {
            if (mode == Mode.SHARED) {
                SharedState existing = JVM_SHARED_LOCKS.get(leaseFile);
                if (existing != null) {
                    existing.references++;
                    return new SharedPart(leaseFile, existing);
                }
            }
            FileChannel channel = openLeaseFile(directory, leaseFile);
            try {
                FileLock lock = mode == Mode.SHARED
                        ? channel.tryLock(0L, Long.MAX_VALUE, true) : channel.tryLock();
                if (lock == null) {
                    channel.close();
                    throw unavailable(directory);
                }
                if (mode == Mode.SHARED) {
                    SharedState state = new SharedState(channel, lock);
                    JVM_SHARED_LOCKS.put(leaseFile, state);
                    return new SharedPart(leaseFile, state);
                }
                return new ExclusivePart(channel, lock);
            } catch (OverlappingFileLockException overlap) {
                close(channel);
                throw unavailable(directory);
            } catch (RuntimeException | IOException failure) {
                close(channel);
                throw failure instanceof RuntimeException runtime
                        ? runtime : new ProfileCatalogException("Failed to acquire profile storage lease", failure);
            }
        }
    }

    private static FileChannel openLeaseFile(Path directory, Path leaseFile) {
        try {
            if (Files.exists(leaseFile, LinkOption.NOFOLLOW_LINKS)
                    && (Files.isSymbolicLink(leaseFile)
                    || !Files.isRegularFile(leaseFile, LinkOption.NOFOLLOW_LINKS))) {
                throw unavailable(directory);
            }
            return FileChannel.open(leaseFile,
                    StandardOpenOption.CREATE, StandardOpenOption.READ,
                    StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS);
        } catch (IOException e) {
            throw new ProfileCatalogException("Failed to open profile storage lease", e);
        }
    }

    private static TargetPlan inspectTarget(Path directory) {
        try {
            Path absolute = directory.toAbsolutePath().normalize();
            Path current = absolute.getRoot();
            if (current == null) throw unavailable(directory);
            requireDirectory(current);
            Path deepestExisting = current;
            List<Path> missingSuffix = new ArrayList<>();
            boolean missing = false;
            for (Path component : absolute) {
                current = current.resolve(component);
                if (missing) {
                    missingSuffix.add(component);
                    continue;
                }
                try {
                    requireDirectory(current);
                    deepestExisting = current;
                } catch (NoSuchFileException absent) {
                    missing = true;
                    missingSuffix.add(component);
                }
            }

            Path canonicalExisting = deepestExisting.toRealPath(LinkOption.NOFOLLOW_LINKS);
            List<Path> reversedExisting = new ArrayList<>();
            Path ancestor = canonicalExisting;
            while (ancestor != null && Files.isWritable(ancestor)) {
                reversedExisting.add(ancestor);
                Path parent = ancestor.getParent();
                if (parent == null || !Files.isWritable(parent)) break;
                ancestor = parent;
            }
            if (reversedExisting.isEmpty()) throw unavailable(directory);
            java.util.Collections.reverse(reversedExisting);

            List<Path> lockChain = new ArrayList<>(reversedExisting);
            Path plannedTarget = canonicalExisting;
            for (Path component : missingSuffix) {
                plannedTarget = plannedTarget.resolve(component);
                lockChain.add(plannedTarget);
            }
            DirectoryIdentity expected = missing ? null : captureIdentity(plannedTarget);
            return new TargetPlan(plannedTarget, List.copyOf(lockChain), expected);
        } catch (IOException e) {
            throw new ProfileCatalogException("Failed to inspect profile storage lease", e);
        }
    }

    private static void requireSameIdentity(DirectoryIdentity expected) {
        if (expected == null) {
            throw new ProfileCatalogException("Profile storage identity was not captured under lease");
        }
        requireMatchingIdentity(expected, captureIdentity(expected.path));
    }

    private static void requireMatchingIdentity(DirectoryIdentity expected, DirectoryIdentity current) {
        if (!current.path.equals(expected.path) || !expected.fileKey.equals(current.fileKey)) {
            throw new ProfileCatalogException("Profile storage changed while acquiring lease");
        }
    }

    private static DirectoryIdentity captureIdentity(Path target) {
        try {
            Path absolute = target.toAbsolutePath().normalize();
            Path current = absolute.getRoot();
            if (current == null) throw unavailable(target);
            requireDirectory(current);
            for (Path component : absolute) {
                current = current.resolve(component);
                requireDirectory(current);
            }
            Path canonical = absolute.toRealPath(LinkOption.NOFOLLOW_LINKS);
            BasicFileAttributes attributes = Files.readAttributes(
                    canonical, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
            if (!attributes.isDirectory() || attributes.fileKey() == null) throw unavailable(target);
            return new DirectoryIdentity(canonical, attributes.fileKey());
        } catch (IOException e) {
            throw new ProfileCatalogException("Profile storage changed while acquiring lease", e);
        }
    }

    private static void ensureDirectoryForLock(Path directory) {
        try {
            requireDirectory(directory);
        } catch (NoSuchFileException missing) {
            try {
                Files.createDirectory(directory);
            } catch (FileAlreadyExistsException raced) {
                // A validacao NOFOLLOW abaixo decide se a criacao concorrente e segura.
            } catch (IOException e) {
                throw new ProfileCatalogException("Failed to create profile storage under lease", e);
            }
            try {
                requireDirectory(directory);
            } catch (IOException e) {
                throw new ProfileCatalogException("Profile storage changed while acquiring lease", e);
            }
        } catch (IOException e) {
            throw new ProfileCatalogException("Profile storage changed while acquiring lease", e);
        }
    }

    private static void requireDirectory(Path directory) throws IOException {
        if (!Files.exists(directory, LinkOption.NOFOLLOW_LINKS)) throw new NoSuchFileException(directory.toString());
        if (Files.isSymbolicLink(directory)
                || !Files.isDirectory(directory, LinkOption.NOFOLLOW_LINKS)) throw unavailable(directory);
    }

    private static ProfileCatalogException unavailable(Path directory) {
        return new ProfileCatalogException("Profile storage is leased or unsafe: " + directory.getFileName());
    }

    private static void closeAll(List<LeasePart> held) {
        for (int index = held.size() - 1; index >= 0; index--) held.get(index).close();
    }

    private static void close(FileChannel channel) {
        try {
            channel.close();
        } catch (IOException ignored) {
        }
    }

    static final class Lease implements AutoCloseable {
        private final List<LeasePart> held;
        private final AtomicBoolean closed = new AtomicBoolean();

        private Lease(List<LeasePart> held) {
            this.held = List.copyOf(held);
        }

        @Override
        public void close() {
            if (closed.compareAndSet(false, true)) closeAll(held);
        }
    }

    private interface LeasePart {
        void close();
    }

    private static final class SharedPart implements LeasePart {
        private final Path leaseFile;
        private final SharedState state;
        private final AtomicBoolean closed = new AtomicBoolean();

        private SharedPart(Path leaseFile, SharedState state) {
            this.leaseFile = leaseFile;
            this.state = state;
        }

        @Override
        public void close() {
            if (!closed.compareAndSet(false, true)) return;
            synchronized (JVM_LOCK) {
                if (--state.references == 0) {
                    JVM_SHARED_LOCKS.remove(leaseFile, state);
                    state.close();
                }
            }
        }
    }

    private static final class SharedState {
        private final FileChannel channel;
        private final FileLock lock;
        private int references = 1;

        private SharedState(FileChannel channel, FileLock lock) {
            this.channel = channel;
            this.lock = lock;
        }

        private void close() {
            try {
                lock.release();
            } catch (IOException ignored) {
            }
            ProfileStorageLeaseManager.close(channel);
        }
    }

    private static final class ExclusivePart implements LeasePart {
        private final FileChannel channel;
        private final FileLock lock;
        private final AtomicBoolean closed = new AtomicBoolean();

        private ExclusivePart(FileChannel channel, FileLock lock) {
            this.channel = channel;
            this.lock = lock;
        }

        @Override
        public void close() {
            if (!closed.compareAndSet(false, true)) return;
            try {
                lock.release();
            } catch (IOException ignored) {
            }
            ProfileStorageLeaseManager.close(channel);
        }
    }

    private enum Mode { SHARED, EXCLUSIVE }
    private record DirectoryIdentity(Path path, Object fileKey) { }

    private static final class TargetPlan {
        private final Path path;
        private final List<Path> lockChain;
        private final DirectoryIdentity expectedIdentity;
        private DirectoryIdentity lockedIdentity;

        private TargetPlan(Path path, List<Path> lockChain, DirectoryIdentity expectedIdentity) {
            this.path = path;
            this.lockChain = lockChain;
            this.expectedIdentity = expectedIdentity;
        }
    }
}
