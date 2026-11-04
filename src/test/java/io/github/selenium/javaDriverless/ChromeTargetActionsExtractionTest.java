package io.github.selenium.javaDriverless;

import io.github.selenium.javaDriverless.types.ChromeOptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.RejectedExecutionException;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import java.util.zip.CRC32;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ChromeTargetActionsExtractionTest {
    @TempDir
    Path tempDir;

    @Test
    void extractsEntriesContainedByDestination() throws Exception {
        Path archive = archiveWith("nested/file.txt", "safe");
        Path destination = tempDir.resolve("safe-output");

        chrome().extractall(archive.toString(), destination.toString()).join();

        assertThat(destination.resolve("nested/file.txt")).hasContent("safe");
    }

    @Test
    void rejectsZipEntryThatEscapesDestination() throws Exception {
        Path archive = archiveWith("../escaped.txt", "owned");
        Path destination = tempDir.resolve("output");
        Path escaped = tempDir.resolve("escaped.txt");

        assertThatThrownBy(() -> chrome().extractall(archive.toString(), destination.toString()).join())
                .isInstanceOf(CompletionException.class)
                .hasRootCauseInstanceOf(IOException.class)
                .hasRootCauseMessage("Entrada ZIP fora do diretorio de destino: ../escaped.txt");
        assertThat(escaped).doesNotExist();
    }

    @Test
    void rejectsEntryWhoseExistingParentIsSymbolicLink() throws Exception {
        Path archive = archiveWith("linked/escaped.txt", "owned");
        Path destination = tempDir.resolve("symlink-output");
        Path outside = tempDir.resolve("outside");
        Files.createDirectories(destination);
        Files.createDirectories(outside);
        Files.createSymbolicLink(destination.resolve("linked"), outside);

        assertThatThrownBy(() -> chrome().extractall(archive.toString(), destination.toString()).join())
                .isInstanceOf(CompletionException.class)
                .hasRootCauseInstanceOf(IOException.class)
                .hasStackTraceContaining("Componente simbolico rejeitado na extracao");
        assertThat(outside.resolve("escaped.txt")).doesNotExist();
    }

    @Test
    void rejectsArchiveThatExceedsEntryLimit() throws Exception {
        Path archive = archiveWithEntries(
                new String[] {"one.txt", "1"},
                new String[] {"two.txt", "2"},
                new String[] {"three.txt", "3"});
        Path destination = tempDir.resolve("entry-limit-output");
        ChromeTargetActions.ExtractionLimits limits =
                new ChromeTargetActions.ExtractionLimits(2, 32, 64);

        assertThatThrownBy(() -> chrome()
                .extractall(archive.toString(), destination.toString(), limits).join())
                .isInstanceOf(CompletionException.class)
                .hasRootCauseInstanceOf(IOException.class)
                .hasStackTraceContaining("Arquivo ZIP excede o limite de entradas: 2");
        assertThat(destination.resolve("three.txt")).doesNotExist();
    }

    @Test
    void rejectsArchiveThatExceedsUncompressedSizeLimit() throws Exception {
        Path archive = archiveWith("large.txt", "12345");
        Path destination = tempDir.resolve("size-limit-output");
        ChromeTargetActions.ExtractionLimits limits =
                new ChromeTargetActions.ExtractionLimits(10, 10, 4);

        assertThatThrownBy(() -> chrome()
                .extractall(archive.toString(), destination.toString(), limits).join())
                .isInstanceOf(CompletionException.class)
                .hasRootCauseInstanceOf(IOException.class)
                .hasStackTraceContaining("Arquivo ZIP excede o limite total descompactado");
        assertThat(destination.resolve("large.txt")).doesNotExist();
    }

    @Test
    void failedOversizedEntryPreservesExistingFileAndLeavesNoTemporaryArtifact() throws Exception {
        // Arquivo pre-existente com nome DIFERENTE da entry: colisao de nome
        // agora falha fechado antes (coberto em SecureZipExtractorTest); aqui
        // o alvo e a falha por limite preservando o destino e sem temporario.
        Path archive = archiveWith("large.txt", "12345");
        Path destination = Files.createDirectories(tempDir.resolve("preserve-existing-output"));
        Path existing = destination.resolve("unrelated.txt");
        Files.writeString(existing, "keep-original", StandardCharsets.UTF_8);
        ChromeTargetActions.ExtractionLimits limits =
                new ChromeTargetActions.ExtractionLimits(10, 10, 4);

        assertThatThrownBy(() -> chrome()
                .extractall(archive.toString(), destination.toString(), limits).join())
                .isInstanceOf(CompletionException.class)
                .hasRootCauseInstanceOf(IOException.class)
                .hasStackTraceContaining("Arquivo ZIP excede o limite total descompactado");

        assertThat(existing).hasContent("keep-original");
        try (var entries = Files.list(destination)) {
            assertThat(entries.toList()).containsExactly(existing);
        }
    }

    @Test
    void corruptedEntryPreservesExistingFileAndLeavesNoTemporaryArtifact() throws Exception {
        // Mesmo padrao do teste de limite: nome distinto da entry corrompida
        // para a falha vir do CRC, e nao da recusa de colisao.
        Path archive = corruptedStoredArchive("profile.json", "corrupted-payload-marker");
        Path destination = Files.createDirectories(tempDir.resolve("preserve-corrupt-output"));
        Path existing = destination.resolve("unrelated.json");
        Files.writeString(existing, "keep-original", StandardCharsets.UTF_8);

        assertThatThrownBy(() -> chrome().extractall(archive.toString(), destination.toString()).join())
                .isInstanceOf(CompletionException.class)
                .hasRootCauseInstanceOf(IOException.class)
                .hasStackTraceContaining("CRC");

        assertThat(existing).hasContent("keep-original");
        try (var entries = Files.list(destination)) {
            assertThat(entries.toList()).containsExactly(existing);
        }
    }

    @Test
    void rejectsEntryThatExceedsPathDepthBeforeCreatingDirectories() throws Exception {
        String deepName = String.join("/", Collections.nCopies(65, "d")) + "/file.txt";
        Path archive = archiveWith(deepName, "payload");
        Path destination = tempDir.resolve("depth-limit-output");

        assertThatThrownBy(() -> chrome().extractall(archive.toString(), destination.toString()).join())
                .isInstanceOf(CompletionException.class)
                .hasRootCauseInstanceOf(IOException.class)
                .hasStackTraceContaining("profundidade maxima");

        assertThat(destination.resolve("d")).doesNotExist();
    }

    @Test
    void rejectsEntryWithOversizedComponentBeforeCreatingAnything() throws Exception {
        String oversizedComponent = "x".repeat(256);
        Path archive = archiveWith(oversizedComponent + "/file.txt", "payload");
        Path destination = tempDir.resolve("component-limit-output");

        assertThatThrownBy(() -> chrome().extractall(archive.toString(), destination.toString()).join())
                .isInstanceOf(CompletionException.class)
                .hasRootCauseInstanceOf(IOException.class)
                .hasStackTraceContaining("componente excede");

        try (var entries = Files.list(destination)) {
            assertThat(entries.toList()).isEmpty();
        }
    }

    @Test
    void rejectsEntryWithOversizedTotalNameBeforeCreatingAnything() throws Exception {
        String component = "n".repeat(220);
        String oversizedName = String.join("/", Collections.nCopies(20, component)) + "/file.txt";
        Path archive = archiveWith(oversizedName, "payload");
        Path destination = tempDir.resolve("name-limit-output");

        assertThatThrownBy(() -> chrome().extractall(archive.toString(), destination.toString()).join())
                .isInstanceOf(CompletionException.class)
                .hasRootCauseInstanceOf(IOException.class)
                .hasStackTraceContaining("nome excede");

        try (var entries = Files.list(destination)) {
            assertThat(entries.toList()).isEmpty();
        }
    }

    @Test
    void saturatedExtractionExecutorReturnsFailedFutureInsteadOfUsingCommonPool() throws Exception {
        Path archive = archiveWith("file.txt", "payload");
        Chrome instance = chrome();
        ChromeTargetActions.ExtractionLimits limits =
                new ChromeTargetActions.ExtractionLimits(10, 1024, 2048);
        CountDownLatch releaseWorkers = new CountDownLatch(1);
        List<CompletableFuture<Void>> accepted = new ArrayList<>();
        CompletableFuture<Void> rejected = null;

        try {
            for (int index = 0; index < 64; index++) {
                Path destination = tempDir.resolve("executor-output-" + index);
                CompletableFuture<Void> future = instance.extractall(
                        archive.toString(), destination.toString(), limits, () -> await(releaseWorkers));
                if (future.isCompletedExceptionally()) {
                    rejected = future;
                    break;
                }
                accepted.add(future);
            }

            assertThat(rejected).as("a bounded executor must reject excess work").isNotNull();
            assertThatThrownBy(rejected::join)
                    .isInstanceOf(CompletionException.class)
                    .hasRootCauseInstanceOf(RejectedExecutionException.class)
                    .hasStackTraceContaining("Fila de extracao saturada");
        } finally {
            releaseWorkers.countDown();
            for (CompletableFuture<Void> future : accepted) {
                future.join();
            }
        }
    }

    @Test
    void parentSwapAfterSecureOpenCannotRedirectWriteOutsideDestination() throws Exception {
        Path archive = archiveWith("nested/file.txt", "safe-after-race");
        Path destination = tempDir.resolve("race-output");
        Path outside = Files.createDirectories(tempDir.resolve("race-outside"));
        Path retainedDirectory = destination.resolve("nested-retained");
        ChromeTargetActions.ExtractionLimits limits =
                new ChromeTargetActions.ExtractionLimits(10, 1024, 2048);

        chrome().extractall(archive.toString(), destination.toString(), limits, () -> {
            Path nested = destination.resolve("nested");
            try {
                Files.move(nested, retainedDirectory);
                Files.createSymbolicLink(nested, outside);
            } catch (IOException failure) {
                throw new UncheckedIOException(failure);
            }
        }).join();

        assertThat(outside.resolve("file.txt")).doesNotExist();
        assertThat(retainedDirectory.resolve("file.txt")).hasContent("safe-after-race");
    }

    private Chrome chrome() {
        ChromeOptions options = new ChromeOptions();
        options.setBinaryLocation("/bin/true");
        return new Chrome(options, 1.0f, false, 1 << 20);
    }

    private Path archiveWith(String entryName, String content) throws IOException {
        return archiveWithEntries(new String[] {entryName, content});
    }

    private Path archiveWithEntries(String[]... entries) throws IOException {
        Path archive = Files.createTempFile(tempDir, "archive-", ".zip");
        try (ZipOutputStream output = new ZipOutputStream(Files.newOutputStream(archive))) {
            for (String[] entry : entries) {
                output.putNextEntry(new ZipEntry(entry[0]));
                output.write(entry[1].getBytes(StandardCharsets.UTF_8));
                output.closeEntry();
            }
        }
        return archive;
    }

    private Path corruptedStoredArchive(String entryName, String content) throws IOException {
        Path archive = Files.createTempFile(tempDir, "corrupt-archive-", ".zip");
        byte[] payload = content.getBytes(StandardCharsets.UTF_8);
        CRC32 crc = new CRC32();
        crc.update(payload);
        ZipEntry entry = new ZipEntry(entryName);
        entry.setMethod(ZipEntry.STORED);
        entry.setSize(payload.length);
        entry.setCompressedSize(payload.length);
        entry.setCrc(crc.getValue());
        try (ZipOutputStream output = new ZipOutputStream(Files.newOutputStream(archive))) {
            output.putNextEntry(entry);
            output.write(payload);
            output.closeEntry();
        }

        byte[] zipBytes = Files.readAllBytes(archive);
        int payloadOffset = indexOf(zipBytes, payload);
        if (payloadOffset < 0) {
            throw new IOException("Payload de teste nao encontrado no ZIP armazenado");
        }
        zipBytes[payloadOffset] ^= 1;
        Files.write(archive, zipBytes);
        return archive;
    }

    private void await(CountDownLatch latch) {
        try {
            latch.await();
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new RuntimeException(interrupted);
        }
    }

    private int indexOf(byte[] haystack, byte[] needle) {
        outer:
        for (int index = 0; index <= haystack.length - needle.length; index++) {
            for (int offset = 0; offset < needle.length; offset++) {
                if (haystack[index + offset] != needle[offset]) {
                    continue outer;
                }
            }
            return index;
        }
        return -1;
    }
}
