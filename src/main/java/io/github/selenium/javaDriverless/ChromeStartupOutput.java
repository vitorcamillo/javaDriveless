package io.github.selenium.javaDriverless;

import io.github.selenium.javaDriverless.logging.JavaDriverlessLogger;
import org.slf4j.Logger;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;

/** Captures and retains a rolling tail of Chrome process stdout/stderr. */
final class ChromeStartupOutput {

    private final StringBuilder tail = new StringBuilder();
    private final Object lock = new Object();
    Thread reader;

    void start(Process process, boolean debug, Logger logger) {
        if (process == null) return;
        start(process.getInputStream(), debug, logger);
    }

    void start(InputStream stream, boolean debug, Logger logger) {
        if (stream == null) return;
        reader = new Thread(() -> {
            try (BufferedReader br = new BufferedReader(new InputStreamReader(stream))) {
                String line;
                while ((line = br.readLine()) != null) {
                    append(line);
                    if (debug || JavaDriverlessLogger.isChromeLogsEnabled()) {
                        JavaDriverlessLogger.chromeProcess(logger, safeLogSummary(line));
                    }
                }
            } catch (Exception e) {
                append("[output-reader-error] " + e.getMessage());
            }
        }, "chrome-startup-output-reader");
        reader.setDaemon(true);
        reader.start();
    }

    String getTail() {
        synchronized (lock) {
            return tail.toString();
        }
    }

    static String safeLogSummary(String line) {
        int length = line == null ? 0 : line.length();
        return "<redacted chrome output; chars=" + length + ">";
    }

    private void append(String line) {
        synchronized (lock) {
            tail.append(line).append('\n');
            int maxChars = 12000;
            if (tail.length() > maxChars) {
                tail.delete(0, tail.length() - maxChars);
            }
        }
    }
}
