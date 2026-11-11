package io.github.selenium.javaDriverless;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.UserPrincipal;
import java.time.Instant;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Perfis temporários gerenciados ({@code selenium_driverless_*}). Antes só {@code quit()} os
 * removia: SIGTERM ou {@code kill -9} deixavam ~10 MB por sessão no diretório temporário.
 * Agora há shutdown hook (encerramento normal/SIGTERM) e varredura de órfãos na próxima criação
 * (kill -9), que só apaga diretórios com marcador de dono cujo processo morreu.
 */
final class ManagedTempProfiles {

    static final String PREFIX = "selenium_driverless_";
    static final String MARKER = ".javadriverless-owner";
    private static final int MAX_MARKER_BYTES = 256;
    private static final Set<Path> OWNED = ConcurrentHashMap.newKeySet();
    private static final java.util.Map<Path, ProcessHandle> PROCESSES = new ConcurrentHashMap<>();
    private static final AtomicBoolean HOOK_REGISTERED = new AtomicBoolean();

    private ManagedTempProfiles() {
    }

    static Path create() throws IOException {
        Path tmpRoot = Path.of(System.getProperty("java.io.tmpdir"));
        try {
            sweepOrphans(tmpRoot);
        } catch (IOException | RuntimeException ignored) {
            // Varredura é melhor esforço: nunca impede iniciar o navegador.
        }
        Path dir = Files.createTempDirectory(PREFIX);
        OWNED.add(dir);
        registerHook();
        ProcessHandle self = ProcessHandle.current();
        long start = self.info().startInstant().map(Instant::toEpochMilli).orElse(-1L);
        Files.writeString(dir.resolve(MARKER), self.pid() + " " + start, StandardCharsets.US_ASCII);
        return dir;
    }

    static void release(Path dir) {
        if (dir != null) {
            OWNED.remove(dir);
            PROCESSES.remove(dir);
        }
    }

    private static void registerHook() {
        if (HOOK_REGISTERED.compareAndSet(false, true)) {
            Thread hook = new Thread(() -> {
                stopProfileUsers();
                for (Path dir : OWNED) {
                    try {
                        ProfileStartupFiles.deleteDirectory(dir);
                    } catch (IOException | RuntimeException ignored) {
                        // Sobra será removida pela varredura da próxima execução.
                    }
                }
            }, "java-driverless-temp-profile-cleanup");
            Runtime.getRuntime().addShutdownHook(hook);
        }
    }

    /** Associa o processo lançado (e seus descendentes) ao perfil, para o shutdown hook. */
    static void attachProcess(Path dir, ProcessHandle process) {
        if (dir != null && process != null && OWNED.contains(dir)) {
            PROCESSES.put(dir, process);
        }
    }

    /**
     * Chrome sobrevive alguns instantes ao fim da JVM e, no encerramento gracioso, recriaria
     * arquivos no perfil já apagado: encerra à força os processos dos perfis e espera até 3 s.
     */
    private static void stopProfileUsers() {
        var users = PROCESSES.values().stream()
                .flatMap(p -> java.util.stream.Stream.concat(p.descendants(), java.util.stream.Stream.of(p)))
                .toList();
        users.forEach(ProcessHandle::destroyForcibly);
        long deadline = System.nanoTime() + 3_000_000_000L;
        while (users.stream().anyMatch(ProcessHandle::isAlive) && System.nanoTime() < deadline) {
            try {
                Thread.sleep(50);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
    }

    /** Remove perfis órfãos em {@code tmpRoot}; retorna quantos foram apagados. */
    static int sweepOrphans(Path tmpRoot) throws IOException {
        UserPrincipal me = tmpRoot.getFileSystem().getUserPrincipalLookupService()
                .lookupPrincipalByName(System.getProperty("user.name"));
        int removed = 0;
        try (DirectoryStream<Path> entries = Files.newDirectoryStream(tmpRoot, PREFIX + "*")) {
            for (Path dir : entries) {
                if (isOrphan(dir, me)) {
                    ProfileStartupFiles.deleteDirectory(dir);
                    removed++;
                }
            }
        }
        return removed;
    }

    static boolean isOrphan(Path dir, UserPrincipal me) {
        try {
            if (OWNED.contains(dir) || !Files.isDirectory(dir, LinkOption.NOFOLLOW_LINKS)
                    || !me.equals(Files.getOwner(dir, LinkOption.NOFOLLOW_LINKS))) {
                return false;
            }
            Path marker = dir.resolve(MARKER);
            if (!Files.isRegularFile(marker, LinkOption.NOFOLLOW_LINKS)
                    || Files.size(marker) > MAX_MARKER_BYTES) {
                return false;
            }
            String[] parts = Files.readString(marker, StandardCharsets.US_ASCII).trim().split(" ");
            if (parts.length != 2) {
                return false;
            }
            long pid = Long.parseLong(parts[0]);
            long start = Long.parseLong(parts[1]);
            return !isAlive(pid, start) && !chromeLockAlive(dir);
        } catch (IOException | RuntimeException invalid) {
            return false;
        }
    }

    private static boolean isAlive(long pid, long startMillis) {
        Optional<ProcessHandle> handle = ProcessHandle.of(pid);
        if (handle.isEmpty() || !handle.get().isAlive()) {
            return false;
        }
        // PID reaproveitado por outro processo: início diferente do gravado.
        Optional<Instant> start = handle.get().info().startInstant();
        return startMillis < 0 || start.isEmpty() || start.get().toEpochMilli() == startMillis;
    }

    /** Chrome ainda vivo usando o perfil (SingletonLock aponta para "host-pid"). */
    private static boolean chromeLockAlive(Path dir) {
        try {
            String target = Files.readSymbolicLink(dir.resolve("SingletonLock")).toString();
            long pid = Long.parseLong(target.substring(target.lastIndexOf('-') + 1));
            return ProcessHandle.of(pid).map(ProcessHandle::isAlive).orElse(false);
        } catch (IOException | RuntimeException noLock) {
            return false;
        }
    }
}
