package io.github.selenium.javaDriverless.profiles;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.Map;
import java.util.zip.ZipInputStream;

/** ZIP stream and manifest parsing primitives for catalog archives. */
final class SdkProfileArchiveSupport {
    static final int MAX_ENTRY_BYTES = 4 * 1024 * 1024;
    static final long MAX_TOTAL_BYTES = 64L * 1024 * 1024;
    static final int MAX_ENTRIES = 10_000;

    private SdkProfileArchiveSupport() {}

    static final class ArchiveBudget {
        long totalBytes;
        int entries;

        void countEntry() throws IOException {
            if (entries >= MAX_ENTRIES) {
                throw new IOException("Archive exceeds the maximum number of entries");
            }
            entries++;
        }
    }

    static byte[] readEntryBytes(ZipInputStream zip, ArchiveBudget budget) throws IOException {
        budget.countEntry();
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        byte[] buffer = new byte[8 * 1024];
        int read;
        long entryBytes = 0;
        while ((read = zip.read(buffer)) != -1) {
            if (entryBytes + read > MAX_ENTRY_BYTES) {
                throw new IOException("Archive entry exceeds the maximum size");
            }
            if (budget.totalBytes + read > MAX_TOTAL_BYTES) {
                throw new IOException("Archive exceeds the maximum uncompressed size");
            }
            entryBytes += read;
            budget.totalBytes += read;
            output.write(buffer, 0, read);
        }
        return output.toByteArray();
    }

    static void parseManifest(String value, Map<String, String> manifest, SdkProfileArchiveReport report) {
        for (String line : value.split("\\R")) {
            if (line.isBlank()) {
                continue;
            }
            String[] parts = line.trim().split("\\s+", 2);
            if (parts.length != 2 || parts[0].length() != 64) {
                report.addError("Invalid manifest line");
                continue;
            }
            String path = parts[1].trim();
            if (path.contains("..") || !path.startsWith("profiles/") || !path.endsWith(".json")) {
                report.addError("Unsafe manifest path: " + path);
                continue;
            }
            manifest.put(path, parts[0]);
        }
    }
}
