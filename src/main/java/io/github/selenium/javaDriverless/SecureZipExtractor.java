package io.github.selenium.javaDriverless;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.channels.Channels;
import java.nio.channels.SeekableByteChannel;
import java.nio.file.DirectoryStream;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.NoSuchFileException;
import java.nio.file.OpenOption;
import java.nio.file.Path;
import java.nio.file.SecureDirectoryStream;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributeView;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/** Extrai ZIPs usando descritores de diretório para impedir troca concorrente por symlink. */
final class SecureZipExtractor {
    private static final int BUFFER_SIZE = 8192;
    private static final int MAX_ENTRY_NAME_BYTES = 4096;
    private static final int MAX_PATH_DEPTH = 64;
    private static final int MAX_COMPONENT_NAME_BYTES = 255;

    private SecureZipExtractor() {}

    static void extract(
            Path archive,
            Path destination,
            ChromeTargetActions.ExtractionLimits limits,
            Runnable beforeLeafOpen) throws IOException {
        Path absoluteDestination = destination.toAbsolutePath().normalize();
        try (DirectoryChain root = openOrCreateAbsoluteDirectory(absoluteDestination);
             ZipInputStream input = new ZipInputStream(Files.newInputStream(archive))) {
            int entries = 0;
            long totalBytes = 0;
            ZipEntry entry;
            while ((entry = input.getNextEntry()) != null) {
                entries++;
                if (entries > limits.maxEntries()) {
                    throw new IOException("Arquivo ZIP excede o limite de entradas: " + limits.maxEntries());
                }
                Path relative = safeRelativePath(entry.getName());
                if (entry.isDirectory()) {
                    try (DirectoryChain ignored = openOrCreateRelativeDirectory(
                            root.leaf(), absoluteDestination, relative)) {
                        // Opening every component with NOFOLLOW is the validation.
                    }
                } else {
                    Path parent = relative.getParent();
                    try (DirectoryChain directory = openOrCreateRelativeDirectory(
                            root.leaf(), absoluteDestination, parent)) {
                        totalBytes += writeEntry(
                                directory.leaf(),
                                relative.getFileName(),
                                input,
                                entry.getName(),
                                limits,
                                totalBytes,
                                beforeLeafOpen);
                    }
                }
                input.closeEntry();
            }
        }
    }

    private static Path safeRelativePath(String entryName) throws IOException {
        if (entryName.getBytes(StandardCharsets.UTF_8).length > MAX_ENTRY_NAME_BYTES) {
            throw new IOException("Entrada ZIP possui nome excedendo " + MAX_ENTRY_NAME_BYTES + " bytes");
        }
        final Path raw;
        try {
            raw = Path.of(entryName);
        } catch (RuntimeException invalid) {
            throw new IOException("Entrada ZIP invalida: " + entryName, invalid);
        }
        Path normalized = raw.normalize();
        if (raw.isAbsolute()
                || normalized.getNameCount() == 0
                || "..".equals(normalized.getName(0).toString())) {
            throw new IOException("Entrada ZIP fora do diretorio de destino: " + entryName);
        }
        if (normalized.getNameCount() > MAX_PATH_DEPTH) {
            throw new IOException("Entrada ZIP excede a profundidade maxima: " + MAX_PATH_DEPTH);
        }
        for (Path component : normalized) {
            if (component.toString().getBytes(StandardCharsets.UTF_8).length > MAX_COMPONENT_NAME_BYTES) {
                throw new IOException(
                        "Entrada ZIP possui componente excedendo " + MAX_COMPONENT_NAME_BYTES + " bytes");
            }
        }
        return normalized;
    }

    private static long writeEntry(
            SecureDirectoryStream<Path> directory,
            Path leaf,
            ZipInputStream input,
            String entryName,
            ChromeTargetActions.ExtractionLimits limits,
            long totalBytes,
            Runnable beforeLeafOpen) throws IOException {
            beforeLeafOpen.run();
            Set<OpenOption> options = Set.of(
                StandardOpenOption.CREATE_NEW,
                StandardOpenOption.WRITE,
                LinkOption.NOFOLLOW_LINKS);
        Path temporaryLeaf;
        SeekableByteChannel channel;
        while (true) {
            temporaryLeaf = Path.of(".jdl-extract-" + UUID.randomUUID() + ".tmp");
            try {
                channel = directory.newByteChannel(temporaryLeaf, options);
                break;
            } catch (FileAlreadyExistsException collision) {
                // UUID collisions are extraordinarily unlikely; CREATE_NEW keeps this race-safe.
            }
        }

        boolean committed = false;
        long entryBytes = 0;
        try {
            try (SeekableByteChannel temporaryChannel = channel;
                 OutputStream output = Channels.newOutputStream(temporaryChannel)) {
                byte[] buffer = new byte[BUFFER_SIZE];
                int read;
                while ((read = input.read(buffer)) != -1) {
                    if (read > limits.maxEntryBytes() - entryBytes) {
                        throw new IOException("Entrada ZIP excede o limite descompactado: " + entryName);
                    }
                    if (read > limits.maxTotalBytes() - totalBytes - entryBytes) {
                        throw new IOException("Arquivo ZIP excede o limite total descompactado");
                    }
                    output.write(buffer, 0, read);
                    entryBytes += read;
                }
            }

            requireLeafAbsent(directory, leaf, entryName);
            directory.move(temporaryLeaf, directory, leaf);
            committed = true;
            return entryBytes;
        } finally {
            if (!committed) {
                try {
                    directory.deleteFile(temporaryLeaf);
                } catch (NoSuchFileException ignored) {
                    // Nothing to roll back when creation or the final move already removed it.
                }
            }
        }
    }

    /**
     * Impede sobrescrita silenciosa do destino: o {@code CREATE_NEW} protege apenas o
     * temporario, e o {@code move} final (renameat) substituiria um leaf ja existente.
     * Consulta atributos via fstatat relativo ao descritor (sem abrir o leaf), logo
     * um FIFO sem escritor nao bloqueia a extracao; symlink ou diretorio no leaf
     * tambem sao recusados. A janela entre a consulta e o move nao e zero — a JDK
     * nao expoe renameat2(RENAME_NOREPLACE) — mas fica restrita a microssegundos.
     */
    private static void requireLeafAbsent(
            SecureDirectoryStream<Path> directory, Path leaf, String entryName) throws IOException {
        boolean occupied;
        try {
            directory.getFileAttributeView(leaf, BasicFileAttributeView.class).readAttributes();
            occupied = true;
        } catch (NoSuchFileException absent) {
            occupied = false;
        }
        if (occupied) {
            throw new IOException("Entrada ZIP colide com arquivo existente no destino: " + entryName);
        }
    }

    private static DirectoryChain openOrCreateAbsoluteDirectory(Path absolute) throws IOException {
        Path rootPath = absolute.getRoot();
        if (rootPath == null) {
            throw new IOException("Destino de extracao sem raiz: " + absolute);
        }
        DirectoryStream<Path> rootStream = Files.newDirectoryStream(rootPath);
        if (!(rootStream instanceof SecureDirectoryStream<?> secureStream)) {
            rootStream.close();
            throw new IOException("Filesystem nao oferece SecureDirectoryStream para extracao segura");
        }
        @SuppressWarnings("unchecked")
        SecureDirectoryStream<Path> secureRoot = (SecureDirectoryStream<Path>) secureStream;
        DirectoryChain chain = new DirectoryChain(secureRoot, true);
        Path currentAbsolute = rootPath;
        try {
            for (Path component : absolute) {
                currentAbsolute = currentAbsolute.resolve(component);
                chain.append(openOrCreateChild(chain.leaf(), component, currentAbsolute));
            }
            return chain;
        } catch (IOException | RuntimeException failure) {
            chain.close();
            throw failure;
        }
    }

    private static DirectoryChain openOrCreateRelativeDirectory(
            SecureDirectoryStream<Path> root,
            Path absoluteRoot,
            Path relative) throws IOException {
        DirectoryChain chain = new DirectoryChain(root, false);
        if (relative == null) {
            return chain;
        }
        Path currentAbsolute = absoluteRoot;
        try {
            for (Path component : relative) {
                currentAbsolute = currentAbsolute.resolve(component);
                chain.append(openOrCreateChild(chain.leaf(), component, currentAbsolute));
            }
            return chain;
        } catch (IOException | RuntimeException failure) {
            chain.close();
            throw failure;
        }
    }

    private static SecureDirectoryStream<Path> openOrCreateChild(
            SecureDirectoryStream<Path> parent,
            Path component,
            Path absoluteChild) throws IOException {
        try {
            return parent.newDirectoryStream(component, LinkOption.NOFOLLOW_LINKS);
        } catch (NoSuchFileException missing) {
            try {
                Files.createDirectory(absoluteChild);
            } catch (FileAlreadyExistsException concurrent) {
                // Re-open below with NOFOLLOW; a concurrently created symlink is rejected.
            }
            return parent.newDirectoryStream(component, LinkOption.NOFOLLOW_LINKS);
        } catch (IOException unsafe) {
            if (Files.isSymbolicLink(absoluteChild)) {
                throw new IOException("Componente simbolico rejeitado na extracao: " + absoluteChild, unsafe);
            }
            throw unsafe;
        }
    }

    private static final class DirectoryChain implements AutoCloseable {
        private final List<SecureDirectoryStream<Path>> opened = new ArrayList<>();
        private final SecureDirectoryStream<Path> borrowedRoot;

        private DirectoryChain(SecureDirectoryStream<Path> root, boolean ownsRoot) {
            borrowedRoot = ownsRoot ? null : root;
            if (ownsRoot) {
                opened.add(root);
            }
        }

        private void append(SecureDirectoryStream<Path> directory) {
            opened.add(directory);
        }

        private SecureDirectoryStream<Path> leaf() {
            return opened.isEmpty() ? borrowedRoot : opened.get(opened.size() - 1);
        }

        @Override
        public void close() throws IOException {
            IOException failure = null;
            for (int index = opened.size() - 1; index >= 0; index--) {
                try {
                    opened.get(index).close();
                } catch (IOException closeFailure) {
                    if (failure == null) {
                        failure = closeFailure;
                    } else {
                        failure.addSuppressed(closeFailure);
                    }
                }
            }
            if (failure != null) {
                throw failure;
            }
        }
    }
}
