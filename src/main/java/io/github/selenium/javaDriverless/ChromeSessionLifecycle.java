package io.github.selenium.javaDriverless;

import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.function.Supplier;

/** Serializa startup e fechamento para uma instância de Chrome. */
final class ChromeSessionLifecycle {
    /** Tentativa interna real; nunca é exposta para cancelamento pelo chamador. */
    private CompletableFuture<Chrome> startupFuture;
    /** View cancelável e substituível que observa a tentativa interna sem controlá-la. */
    private CompletableFuture<Chrome> startupViewFuture;
    private CompletableFuture<Void> closeFuture;
    private boolean closing;

    synchronized CompletableFuture<Chrome> start(
            boolean started, Supplier<CompletableFuture<Chrome>> starter) {
        if (closing) {
            return CompletableFuture.failedFuture(
                    new IllegalStateException("Sessão está em processo de fechamento"));
        }
        if (started && startupFuture == null) {
            return CompletableFuture.failedFuture(
                    new IllegalStateException("Sessão iniciada sem future de lifecycle"));
        }
        if (startupFuture != null) {
            return activeStartupView();
        }

        CompletableFuture<Chrome> attempt;
        try {
            attempt = Objects.requireNonNull(starter.get(), "starter retornou future nulo");
        } catch (Throwable failure) {
            return CompletableFuture.failedFuture(failure);
        }
        startupFuture = attempt;
        CompletableFuture<Chrome> callerView = attempt.copy();
        startupViewFuture = callerView;
        attempt.whenComplete((ignored, failure) -> {
            if (failure != null) {
                synchronized (ChromeSessionLifecycle.this) {
                    if (startupFuture == attempt) {
                        startupFuture = null;
                        startupViewFuture = null;
                    }
                }
            }
        });
        return callerView;
    }

    private CompletableFuture<Chrome> activeStartupView() {
        if (startupViewFuture == null || startupViewFuture.isCancelled()) {
            startupViewFuture = startupFuture.copy();
        }
        return startupViewFuture;
    }

    synchronized CompletableFuture<Void> close(
            Supplier<CompletableFuture<Void>> closer, Runnable afterClose) {
        if (closeFuture != null) {
            return closeFuture;
        }
        closing = true;
        CompletableFuture<Void> startupSettled = startupFuture == null
                ? CompletableFuture.completedFuture(null)
                : startupFuture.handle((ignored, failure) -> null);
        CompletableFuture<Void> operation = startupSettled.thenCompose(ignored -> {
            try {
                return Objects.requireNonNull(closer.get(), "closer retornou future nulo");
            } catch (Throwable failure) {
                return CompletableFuture.failedFuture(failure);
            }
        });
        closeFuture = operation;
        operation.whenComplete((ignored, failure) -> {
            synchronized (ChromeSessionLifecycle.this) {
                if (closeFuture == operation) {
                    closeFuture = null;
                }
                startupFuture = null;
                startupViewFuture = null;
                closing = false;
                afterClose.run();
            }
        });
        return operation;
    }
}
