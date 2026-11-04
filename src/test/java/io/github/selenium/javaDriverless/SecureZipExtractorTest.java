package io.github.selenium.javaDriverless;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Caracterizacao da extracao segura de ZIP: path traversal, limites de
 * bomba de zip, componentes simbolicos e ausencia de residuos temporarios.
 */
class SecureZipExtractorTest {

    private static final ChromeTargetActions.ExtractionLimits GENEROUS_LIMITS =
            new ChromeTargetActions.ExtractionLimits(100, 1024 * 1024, 8 * 1024 * 1024);

    @TempDir
    Path temp;

    @Test
    void extractsFilesAndNestedDirectoriesWithoutTempResidue() throws IOException {
        Path archive = zip(z -> {
            entry(z, "a.txt", "alpha");
            entry(z, "dir/b.txt", "beta");
            entry(z, "dir/sub/c.txt", "gamma");
            entry(z, "empty/", new byte[0]);
        });
        Path destination = temp.resolve("dest");
        AtomicInteger leafOpens = new AtomicInteger();

        SecureZipExtractor.extract(archive, destination, GENEROUS_LIMITS, leafOpens::incrementAndGet);

        assertThat(destination.resolve("a.txt")).hasContent("alpha");
        assertThat(destination.resolve("dir/b.txt")).hasContent("beta");
        assertThat(destination.resolve("dir/sub/c.txt")).hasContent("gamma");
        assertThat(destination.resolve("empty")).isDirectory();
        assertThat(leafOpens.get()).isEqualTo(3);
        assertThat(tempResidues(destination)).isEmpty();
    }

    @Test
    void rejectsParentTraversalEntry() throws IOException {
        Path archive = zip(z -> entry(z, "../evil.txt", "escape"));
        Path destination = temp.resolve("dest");

        assertThatThrownBy(() ->
                SecureZipExtractor.extract(archive, destination, GENEROUS_LIMITS, () -> {}))
                .isInstanceOf(IOException.class)
                .hasMessageContaining("fora do diretorio de destino");
        assertThat(temp.resolve("evil.txt")).doesNotExist();
    }

    @Test
    void rejectsInnerParentTraversalEntry() throws IOException {
        Path archive = zip(z -> entry(z, "safe/../../evil.txt", "escape"));
        Path destination = temp.resolve("dest");

        assertThatThrownBy(() ->
                SecureZipExtractor.extract(archive, destination, GENEROUS_LIMITS, () -> {}))
                .isInstanceOf(IOException.class)
                .hasMessageContaining("fora do diretorio de destino");
        assertThat(temp.resolve("evil.txt")).doesNotExist();
    }

    @Test
    void rejectsAbsoluteEntryName() throws IOException {
        Path archive = zip(z -> entry(z, "/etc/javadriveless-pwned", "escape"));
        Path destination = temp.resolve("dest");

        assertThatThrownBy(() ->
                SecureZipExtractor.extract(archive, destination, GENEROUS_LIMITS, () -> {}))
                .isInstanceOf(IOException.class)
                .hasMessageContaining("fora do diretorio de destino");
    }

    @Test
    void rejectsEntryExceedingPathDepth() throws IOException {
        String deep = String.join("/", Collections.nCopies(64, "d")) + "/f.txt";
        Path archive = zip(z -> entry(z, deep, "deep"));
        Path destination = temp.resolve("dest");

        assertThatThrownBy(() ->
                SecureZipExtractor.extract(archive, destination, GENEROUS_LIMITS, () -> {}))
                .isInstanceOf(IOException.class)
                .hasMessageContaining("profundidade maxima");
    }

    @Test
    void acceptsEntryExactlyAtPathDepthBoundary() throws IOException {
        String boundary = String.join("/", Collections.nCopies(63, "d")) + "/f.txt";
        Path archive = zip(z -> entry(z, boundary, "ok"));
        Path destination = temp.resolve("dest");

        SecureZipExtractor.extract(archive, destination, GENEROUS_LIMITS, () -> {});

        assertThat(destination.resolve(boundary)).hasContent("ok");
    }

    @Test
    void rejectsOversizedComponentName() throws IOException {
        String oversized = "x".repeat(256) + ".txt";
        Path archive = zip(z -> entry(z, oversized, "big"));
        Path destination = temp.resolve("dest");

        assertThatThrownBy(() ->
                SecureZipExtractor.extract(archive, destination, GENEROUS_LIMITS, () -> {}))
                .isInstanceOf(IOException.class)
                .hasMessageContaining("componente excedendo");
    }

    @Test
    void enforcesMaxEntries() throws IOException {
        Path archive = zip(z -> {
            entry(z, "one.txt", "1");
            entry(z, "two.txt", "2");
            entry(z, "three.txt", "3");
        });
        Path destination = temp.resolve("dest");
        ChromeTargetActions.ExtractionLimits limits =
                new ChromeTargetActions.ExtractionLimits(2, 1024, 8192);

        assertThatThrownBy(() ->
                SecureZipExtractor.extract(archive, destination, limits, () -> {}))
                .isInstanceOf(IOException.class)
                .hasMessageContaining("limite de entradas");
    }

    @Test
    void enforcesMaxBytesPerEntry() throws IOException {
        Path archive = zip(z -> entry(z, "bomb.txt", "A".repeat(256)));
        Path destination = temp.resolve("dest");
        ChromeTargetActions.ExtractionLimits limits =
                new ChromeTargetActions.ExtractionLimits(10, 64, 8192);

        assertThatThrownBy(() ->
                SecureZipExtractor.extract(archive, destination, limits, () -> {}))
                .isInstanceOf(IOException.class)
                .hasMessageContaining("limite descompactado");
        assertThat(destination.resolve("bomb.txt")).doesNotExist();
    }

    @Test
    void enforcesMaxTotalBytesAcrossEntries() throws IOException {
        Path archive = zip(z -> {
            entry(z, "first.bin", "A".repeat(48));
            entry(z, "second.bin", "B".repeat(48));
        });
        Path destination = temp.resolve("dest");
        ChromeTargetActions.ExtractionLimits limits =
                new ChromeTargetActions.ExtractionLimits(10, 1024, 64);

        assertThatThrownBy(() ->
                SecureZipExtractor.extract(archive, destination, limits, () -> {}))
                .isInstanceOf(IOException.class)
                .hasMessageContaining("limite total descompactado");
    }

    @Test
    void reExtractionOverExistingFileFailsAndKeepsOriginalContent() throws IOException {
        // Fail-closed: extrair sobre destino populado precisa recusar em vez
        // de substituir silenciosamente dados ja existentes (o move final,
        // renameat, substituiria o leaf sem o probe de ocupacao).
        Path firstArchive = zip(z -> entry(z, "a.txt", "alpha"));
        Path secondArchive = zip(z -> entry(z, "a.txt", "omega"));
        Path destination = temp.resolve("dest");
        SecureZipExtractor.extract(firstArchive, destination, GENEROUS_LIMITS, () -> {});

        assertThatThrownBy(() ->
                SecureZipExtractor.extract(secondArchive, destination, GENEROUS_LIMITS, () -> {}))
                .isInstanceOf(IOException.class)
                .hasMessageContaining("colide com arquivo existente");
        assertThat(destination.resolve("a.txt")).hasContent("alpha");
    }

    @Test
    void extractionFailsWhenLeafIsOccupiedByDirectory() throws IOException {
        Path archive = zip(z -> entry(z, "a.txt", "alpha"));
        Path destination = temp.resolve("dest");
        Files.createDirectories(destination.resolve("a.txt"));

        assertThatThrownBy(() ->
                SecureZipExtractor.extract(archive, destination, GENEROUS_LIMITS, () -> {}))
                .isInstanceOf(IOException.class);
    }

    @Test
    void rejectsSymbolicLinkComponentInsideDestination() throws IOException {
        Path destination = temp.resolve("dest");
        Path outside = temp.resolve("outside");
        Files.createDirectories(destination);
        Files.createDirectories(outside);
        Files.createSymbolicLink(destination.resolve("linked"), outside);
        Path archive = zip(z -> entry(z, "linked/file.txt", "escape"));

        assertThatThrownBy(() ->
                SecureZipExtractor.extract(archive, destination, GENEROUS_LIMITS, () -> {}))
                .isInstanceOf(IOException.class);
        assertThat(outside).isEmptyDirectory();
    }

    @Test
    void extractionLimitsMustBePositive() {
        assertThatThrownBy(() ->
                new ChromeTargetActions.ExtractionLimits(0, 1024, 8192))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() ->
                new ChromeTargetActions.ExtractionLimits(10, 0, 8192))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() ->
                new ChromeTargetActions.ExtractionLimits(10, 1024, 0))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private final AtomicInteger zipSequence = new AtomicInteger();

    private Path zip(IOExceptionConsumer<ZipOutputStream> writer) throws IOException {
        // Nome unico por chamada: reutilizar temp/archive.zip faria o segundo
        // zip truncar o primeiro antes de a primeira extracao acontecer.
        Path archive = temp.resolve("archive-" + zipSequence.incrementAndGet() + ".zip");
        try (ZipOutputStream out = new ZipOutputStream(Files.newOutputStream(archive))) {
            writer.accept(out);
        }
        return archive;
    }

    private static void entry(ZipOutputStream out, String name, String content) throws IOException {
        entry(out, name, content.getBytes(StandardCharsets.UTF_8));
    }

    private static void entry(ZipOutputStream out, String name, byte[] content) throws IOException {
        out.putNextEntry(new ZipEntry(name));
        if (content.length > 0 && !name.endsWith("/")) {
            out.write(content);
        }
        out.closeEntry();
    }

    private static java.util.List<Path> tempResidues(Path root) throws IOException {
        try (Stream<Path> files = Files.walk(root)) {
            return files.filter(Files::isRegularFile)
                    .filter(path -> path.getFileName().toString().startsWith(".jdl-extract-"))
                    .toList();
        }
    }

    @FunctionalInterface
    private interface IOExceptionConsumer<T> {
        void accept(T value) throws IOException;
    }
}
