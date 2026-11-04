package io.github.selenium.javaDriverless;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DevToolsActivePortWaiterTest {
    @TempDir
    Path tempDir;

    @Test
    void returnsExpectedPortFromPublishedFile() throws Exception {
        Files.writeString(tempDir.resolve(DevToolsActivePortWaiter.FILE_NAME), "9222\n/devtools/browser/x");

        assertEquals(9222, DevToolsActivePortWaiter.await(tempDir, 9222, null, 1f));
    }

    @Test
    void acceptsAnyValidPortWhenExpectedPortIsZero() throws Exception {
        Files.writeString(tempDir.resolve(DevToolsActivePortWaiter.FILE_NAME), "43123\n/devtools/browser/x");

        assertEquals(43123, DevToolsActivePortWaiter.await(tempDir, 0, null, 1f));
    }

    @Test
    void rejectsPortDifferentFromRequestedPortImmediately() throws Exception {
        Files.writeString(tempDir.resolve(DevToolsActivePortWaiter.FILE_NAME), "9223\n/devtools/browser/x");

        IllegalStateException error = assertThrows(IllegalStateException.class,
                () -> DevToolsActivePortWaiter.await(tempDir, 9222, null, 5f));

        assertTrue(error.getMessage().contains("diferente"));
    }

    @Test
    void timesOutWhenFileIsMissing() {
        IllegalStateException error = assertThrows(IllegalStateException.class,
                () -> DevToolsActivePortWaiter.await(tempDir, 0, null, 0.3f));

        assertTrue(error.getMessage().contains("Timeout"));
    }

    @Test
    void acceptsFileCreatedAfterChromeStarts() throws Exception {
        Thread writer = new Thread(() -> {
            try {
                Thread.sleep(200);
                Files.writeString(tempDir.resolve(DevToolsActivePortWaiter.FILE_NAME),
                        "9222\n/devtools/browser/x");
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        });
        writer.start();

        assertEquals(9222, DevToolsActivePortWaiter.await(tempDir, 9222, null, 5f));
        writer.join();
    }

    @Test
    void continuesPollingAfterPartialContent() throws Exception {
        Path portFile = tempDir.resolve(DevToolsActivePortWaiter.FILE_NAME);
        Files.writeString(portFile, "");
        Thread writer = new Thread(() -> {
            try {
                Thread.sleep(200);
                Files.writeString(portFile, "9222\n/devtools/browser/x");
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        });
        writer.start();

        assertEquals(9222, DevToolsActivePortWaiter.await(tempDir, 9222, null, 5f));
        writer.join();
    }

    @Test
    void failsWhenProcessExitsBeforePublishingPort() throws Exception {
        Process process = new ProcessBuilder("/bin/true").start();
        process.onExit().get(5, TimeUnit.SECONDS);

        IllegalStateException error = assertThrows(IllegalStateException.class,
                () -> DevToolsActivePortWaiter.await(tempDir, 0, process, 1f));

        assertTrue(error.getMessage().contains("encerrou"));
    }

    @Test
    void deleteStaleRemovesExistingFileAndIsIdempotent() throws Exception {
        Path portFile = tempDir.resolve(DevToolsActivePortWaiter.FILE_NAME);
        Files.writeString(portFile, "9222\n/devtools/browser/stale");

        DevToolsActivePortWaiter.deleteStale(tempDir);
        DevToolsActivePortWaiter.deleteStale(tempDir);

        assertFalse(Files.exists(portFile));
    }
}
