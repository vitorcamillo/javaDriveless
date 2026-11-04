package io.github.selenium.javaDriverless;

/** Signals that an essential fingerprint stage did not complete during browser startup. */
final class FingerprintStartupException extends RuntimeException {
    private final String stage;

    FingerprintStartupException(String stage, Throwable cause) {
        super("Falha na etapa essencial de fingerprint '" + stage + "'; sessão não iniciada", cause);
        this.stage = stage;
    }

    String getStage() {
        return stage;
    }
}
