package io.github.selenium.javaDriverless;

/** Blocking browser commands behind the Selenium-compatible fluent facade. */
final class JavaDriverlessBrowserOperations {
    private JavaDriverlessBrowserOperations() {}

    static void sleep(Chrome chrome, double seconds) {
        await(() -> chrome.sleep(seconds), "Erro ao aguardar");
    }

    static void back(Chrome chrome) {
        await(chrome::back, "Erro ao voltar");
    }

    static void forward(Chrome chrome) {
        await(chrome::forward, "Erro ao avançar");
    }

    static void refresh(Chrome chrome) {
        await(chrome::refresh, "Erro ao recarregar");
    }

    static void screenshot(Chrome chrome, String filename) {
        await(() -> chrome.getScreenshotAsFile(filename), "Erro ao tirar screenshot");
    }

    static void maximize(Chrome chrome) {
        await(chrome::maximizeWindow, "Erro ao maximizar");
    }

    static void minimize(Chrome chrome) {
        await(chrome::minimizeWindow, "Erro ao minimizar");
    }

    static void fullscreen(Chrome chrome) {
        await(chrome::fullscreenWindow, "Erro ao ativar tela cheia");
    }

    private static void await(
            java.util.function.Supplier<java.util.concurrent.CompletableFuture<?>> operation,
            String message) {
        try {
            operation.get().get();
        } catch (Exception e) {
            throw new RuntimeException(message, e);
        }
    }
}
