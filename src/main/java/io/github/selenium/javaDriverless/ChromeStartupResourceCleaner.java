package io.github.selenium.javaDriverless;

import io.github.selenium.javaDriverless.logging.JavaDriverlessLogger;
import io.github.selenium.javaDriverless.cdp.CDPSocket;
import io.github.selenium.javaDriverless.types.BaseTarget;
import io.github.selenium.javaDriverless.types.Target;
import org.slf4j.Logger;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

/** Libera recursos adquiridos parcialmente durante a inicialização de uma sessão. */
final class ChromeStartupResourceCleaner {
    private static final long DEFAULT_BROWSER_CLOSE_GRACE_MILLIS = 5_000L;
    private static final long MAX_BROWSER_CLOSE_GRACE_MILLIS = 30_000L;

    private ChromeStartupResourceCleaner() {
    }

    static void cleanup(
            Logger logger,
            Target target,
            BaseTarget browserTarget,
            BaseTarget contextTarget,
            Process process,
            Path ownedTempDir,
            boolean ownsPageTarget,
            Runnable proxyCloser) {
        CDPSocket browserSocket = browserTarget == null ? null : browserTarget.getSocket();
        boolean browserCloseRequested = browserTarget != null
                && browserSocket != null
                && browserSocket.isConnected()
                && ownsPageTarget;
        close(logger, "navegador via Browser.close", !browserCloseRequested
                ? null
                : () -> browserTarget.executeCdpCmd("Browser.close", Map.of(), null)
                        .thenApply(ignored -> null));
        if (browserCloseRequested) {
            run(logger, "encerramento gracioso do processo Chrome",
                    () -> waitForNaturalProcessExit(
                            process, browserCloseGraceMillis(), TimeUnit.MILLISECONDS));
        }
        close(logger, "target CDP", target == null ? null
                : ownsPageTarget ? target::close : target::disconnect);
        close(logger, "target CDP do navegador", browserTarget == null ? null : browserTarget::close);
        if (contextTarget != null && contextTarget != browserTarget) {
            close(logger, "target CDP do contexto", contextTarget::close);
        }
        run(logger, "árvore do processo Chrome", () -> terminateProcessTree(process));
        run(logger, "ponte local de proxy autenticado", proxyCloser);
        deleteOwnedDirectory(logger, ownedTempDir);
    }

    private static void close(
            Logger logger, String resource, Supplier<CompletableFuture<Void>> operationSupplier) {
        if (operationSupplier == null) {
            return;
        }
        try {
            CompletableFuture<Void> operation = operationSupplier.get();
            if (operation == null) {
                return;
            }
            operation.orTimeout(2, TimeUnit.SECONDS).join();
        } catch (Exception e) {
            JavaDriverlessLogger.warn(logger, "Erro ao fechar {} durante limpeza de sessão: {}",
                    resource, e.getMessage());
        }
    }

    private static void run(Logger logger, String resource, Runnable operation) {
        if (operation == null) {
            return;
        }
        try {
            operation.run();
        } catch (Exception e) {
            JavaDriverlessLogger.warn(logger, "Erro ao liberar {} durante limpeza de sessão: {}",
                    resource, e.getMessage());
        }
    }

    private static void terminateProcessTree(Process process) {
        if (process == null) {
            return;
        }
        ProcessHandle root = process.toHandle();
        List<ProcessHandle> descendants;
        try {
            descendants = root.descendants().toList();
        } catch (Exception e) {
            descendants = List.of();
        }

        descendants.forEach(ChromeStartupResourceCleaner::destroyQuietly);
        destroyQuietly(root);
        waitForExit(root, descendants, 1, TimeUnit.SECONDS);
        descendants.stream().filter(ProcessHandle::isAlive)
                .forEach(ChromeStartupResourceCleaner::destroyForciblyQuietly);
        destroyForciblyQuietly(root);
        waitForExit(root, descendants, 2, TimeUnit.SECONDS);
    }

    private static void waitForNaturalProcessExit(Process process, long timeout, TimeUnit unit) {
        if (process == null) {
            return;
        }
        ProcessHandle root = process.toHandle();
        List<ProcessHandle> descendants;
        try {
            descendants = root.descendants().toList();
        } catch (Exception e) {
            descendants = List.of();
        }
        waitForExit(root, descendants, timeout, unit);
    }

    private static long browserCloseGraceMillis() {
        String configured = System.getProperty("jdl.browserCloseGraceMillis");
        if (configured == null || configured.isBlank()) {
            configured = System.getenv("JDL_BROWSER_CLOSE_GRACE_MILLIS");
        }
        if (configured == null || configured.isBlank()) {
            return DEFAULT_BROWSER_CLOSE_GRACE_MILLIS;
        }
        try {
            long parsed = Long.parseLong(configured.trim());
            return Math.max(100L, Math.min(MAX_BROWSER_CLOSE_GRACE_MILLIS, parsed));
        } catch (NumberFormatException ignored) {
            return DEFAULT_BROWSER_CLOSE_GRACE_MILLIS;
        }
    }

    private static void destroyQuietly(ProcessHandle handle) {
        try {
            if (handle.isAlive()) {
                handle.destroy();
            }
        } catch (Exception ignored) {
            // O fallback forçado ainda será tentado.
        }
    }

    private static void destroyForciblyQuietly(ProcessHandle handle) {
        try {
            if (handle.isAlive()) {
                handle.destroyForcibly();
            }
        } catch (Exception ignored) {
            // Best-effort: os demais recursos ainda precisam ser liberados.
        }
    }

    private static void waitForExit(
            ProcessHandle root, List<ProcessHandle> descendants, long timeout, TimeUnit unit) {
        long deadline = System.nanoTime() + unit.toNanos(timeout);
        while ((root.isAlive() || descendants.stream().anyMatch(ProcessHandle::isAlive))
                && System.nanoTime() < deadline) {
            try {
                Thread.sleep(20);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
        }
    }

    private static void deleteOwnedDirectory(Logger logger, Path ownedTempDir) {
        if (ownedTempDir == null || !Files.exists(ownedTempDir)) {
            return;
        }
        try {
            ProfileStartupFiles.deleteDirectory(ownedTempDir);
        } catch (Exception e) {
            JavaDriverlessLogger.warn(logger,
                    "Erro ao remover diretório temporário após falha de startup {}: {}",
                    ownedTempDir, e.getMessage());
        }
    }
}
