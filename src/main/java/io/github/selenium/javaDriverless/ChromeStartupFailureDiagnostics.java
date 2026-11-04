package io.github.selenium.javaDriverless;

import io.github.selenium.javaDriverless.types.ChromeOptions;

final class ChromeStartupFailureDiagnostics {
    private ChromeStartupFailureDiagnostics() {
    }

    static String build(ChromeOptions options, boolean isRemote, Process process, ChromeStartupOutput startupOutput) {
        StringBuilder diag = new StringBuilder();
        diag.append("Erro ao iniciar sessão Chrome");
        if (!isRemote) {
            diag.append(" | binaryConfigured=").append(options.getBinaryLocation() != null);
            diag.append(" | debuggerAddressConfigured=")
                    .append(options.getDebuggerAddress() != null && !options.getDebuggerAddress().isBlank());
            diag.append(" | userDataDirConfigured=")
                    .append(options.getUserDataDir() != null && !options.getUserDataDir().isBlank());
            if (process != null) {
                diag.append(" | processAlive=").append(process.isAlive());
                if (!process.isAlive()) {
                    try {
                        diag.append(" | processExitCode=").append(process.exitValue());
                    } catch (Exception ignore) {
                        // ignore
                    }
                }
            }
            String tail = startupOutput.getTail();
            if (!tail.isBlank()) {
                // Chrome stdout/stderr can contain full URLs, local paths, request headers,
                // extension output or proxy credentials. Presence is enough for diagnostics;
                // the raw payload must never cross the exception/log boundary.
                diag.append(" | chromeOutputAvailable=true");
            }
        }
        return diag.toString();
    }
}
