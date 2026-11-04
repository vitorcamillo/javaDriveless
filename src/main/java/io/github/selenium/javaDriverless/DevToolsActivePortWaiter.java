package io.github.selenium.javaDriverless;

import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.channels.SeekableByteChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.NoSuchFileException;
import java.nio.file.OpenOption;
import java.nio.file.Path;
import java.nio.file.DirectoryStream;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.FileOwnerAttributeView;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.PosixFileAttributeView;
import java.nio.file.attribute.PosixFilePermission;
import java.util.HashSet;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.TimeUnit;

/** Uso interno da lib; não é API estável. */
public final class DevToolsActivePortWaiter {
    static final String FILE_NAME = "DevToolsActivePort";
    private static final int MAX_PORT_FILE_BYTES = 4096;
    static volatile Path procRoot = Paths.get("/proc"); // substituível só por testes do pacote

    interface ListenerPids {
        Set<Long> lookup(int port) throws IOException;
    }

    static volatile ListenerPids listenerPids = DevToolsActivePortWaiter::systemListenerPids; // substituível só por testes do pacote

    private DevToolsActivePortWaiter() {}

    /** Restringe o diretório e remove o arquivo de uma execução anterior antes de iniciar o Chrome. */
    public static void deleteStale(Path userDataDir) throws IOException {
        restrictToOwner(userDataDir);
        Files.deleteIfExists(userDataDir.resolve(FILE_NAME));
    }

    static void restrictToOwner(Path userDataDir) throws IOException {
        Path realDir;
        try {
            realDir = userDataDir.toRealPath();
        } catch (NoSuchFileException missing) {
            return;
        }
        if (Files.getFileAttributeView(realDir, PosixFileAttributeView.class, LinkOption.NOFOLLOW_LINKS) == null) {
            return;
        }
        try {
            Set<PosixFilePermission> permissions = Files.getPosixFilePermissions(realDir,
                    LinkOption.NOFOLLOW_LINKS);
            if (permissions.contains(PosixFilePermission.GROUP_WRITE)
                    || permissions.contains(PosixFilePermission.OTHERS_WRITE)) {
                // O perfil guarda cookies e sessões; sem escrita de terceiros, ninguém renomeia entradas (A→B→A).
                permissions.remove(PosixFilePermission.GROUP_WRITE);
                permissions.remove(PosixFilePermission.OTHERS_WRITE);
                Files.setPosixFilePermissions(realDir, permissions);
            }
        } catch (IOException | SecurityException ignored) {
            // O await recusará o diretório se não foi possível restringi-lo.
        }
    }

    /** Aguarda o arquivo publicado pelo Chrome confirmar a porta do CDP. */
    public static int await(Path userDataDir, int expectedPort, Process process, float timeoutSeconds) {
        long timeoutNanos = (long) (timeoutSeconds * 1_000_000_000L);
        long deadline = System.nanoTime() + Math.max(0L, timeoutNanos);
        boolean socketOwnershipRejected = false;

        while (true) {
            if (process != null && !process.isAlive()) {
                throw new IllegalStateException("Chrome encerrou antes de publicar a porta DevTools");
            }

            try {
                Path dir = userDataDir.toRealPath();
                String content = readPortFile(dir.resolve(FILE_NAME), dir);
                if (content == null) {
                    // Um arquivo acima do limite ainda pode estar sendo publicado; continue aguardando.
                    continueWaiting(deadline, socketOwnershipRejected
                            ? "Timeout aguardando DevToolsActivePort do Chrome (porta publicada não pertence ao processo iniciado)"
                            : "Timeout aguardando DevToolsActivePort do Chrome");
                    continue;
                }
                String[] lines = content.split("\\R", -1);
                if (lines.length >= 2 && !lines[1].isBlank()) {
                    int port;
                    try {
                        port = Integer.parseInt(lines[0].trim());
                    } catch (NumberFormatException ignored) {
                        port = -1;
                    }
                    if (port >= 1 && port <= 65535) {
                        if (expectedPort > 0 && port != expectedPort) {
                            throw new IllegalStateException("Chrome publicou porta DevTools diferente da solicitada; "
                                    + "porta possivelmente ocupada por outro processo");
                        }
                        // Autentica o resultado em vez do caminho; fecha troca A→B→A de arquivo, diretório ou ancestral.
                        if (process != null && !listenSocketOwnedBy(port, process.toHandle(), procRoot)) {
                            socketOwnershipRejected = true;
                            continueWaiting(deadline, "Timeout aguardando DevToolsActivePort do Chrome "
                                    + "(porta publicada não pertence ao processo iniciado)");
                            continue;
                        }
                        return port;
                    }
                }
            } catch (NoSuchFileException ignored) {
                // O Chrome ainda não publicou o arquivo.
            } catch (IOException ignored) {
                // Leituras transitórias durante a gravação ainda não indicam que está pronto.
            }

            continueWaiting(deadline, socketOwnershipRejected
                    ? "Timeout aguardando DevToolsActivePort do Chrome (porta publicada não pertence ao processo iniciado)"
                    : "Timeout aguardando DevToolsActivePort do Chrome");
        }
    }

    private static String readPortFile(Path portFile, Path userDataDir) throws IOException {
        if (Files.getFileAttributeView(userDataDir, PosixFileAttributeView.class, LinkOption.NOFOLLOW_LINKS) != null) {
            Set<PosixFilePermission> permissions = Files.getPosixFilePermissions(userDataDir,
                    LinkOption.NOFOLLOW_LINKS);
            if (permissions.contains(PosixFilePermission.GROUP_WRITE)
                    || permissions.contains(PosixFilePermission.OTHERS_WRITE)) {
                throw new IllegalStateException("user-data-dir gravável por terceiros; DevToolsActivePort recusado");
            }
            if (!ownerIsCurrentUser(Files.getOwner(userDataDir, LinkOption.NOFOLLOW_LINKS).getName(),
                    System.getProperty("user.name"), processDefaultOwner())) {
                throw new IllegalStateException("user-data-dir de outro usuário; DevToolsActivePort recusado");
            }
        } else if (Files.getFileAttributeView(userDataDir, FileOwnerAttributeView.class) != null
                && !ownerIsCurrentUser(Files.getOwner(userDataDir).getName(), System.getProperty("user.name"),
                        processDefaultOwner())) {
            throw new IllegalStateException("user-data-dir de outro usuário; DevToolsActivePort recusado");
        }
        if (Files.isSymbolicLink(portFile)) {
            throw new IllegalStateException("DevToolsActivePort é link simbólico; recusado");
        }
        BasicFileAttributes before = Files.readAttributes(portFile, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
        if (!before.isRegularFile()) {
            throw new IllegalStateException("DevToolsActivePort não é arquivo regular; recusado");
        }

        ByteBuffer buffer = ByteBuffer.allocate(MAX_PORT_FILE_BYTES + 1);
        Set<OpenOption> openOptions = Set.of(StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS);
        try (SeekableByteChannel channel = Files.newByteChannel(portFile, openOptions)) {
            while (buffer.hasRemaining() && channel.read(buffer) != -1) {
                // A capacidade inclui um byte sentinela para detectar conteúdo acima do limite.
            }
            BasicFileAttributes after = Files.readAttributes(portFile, BasicFileAttributes.class,
                    LinkOption.NOFOLLOW_LINKS);
            // No Linux, fileKey identifica o inode: se mudou, o caminho aponta para outro arquivo.
            if (before.fileKey() != null && !Objects.equals(before.fileKey(), after.fileKey())) return null;
            // Valida o mesmo arquivo lido enquanto o canal permanece aberto.
            if (Files.getFileAttributeView(portFile, FileOwnerAttributeView.class, LinkOption.NOFOLLOW_LINKS) != null
                    && !Files.getOwner(portFile, LinkOption.NOFOLLOW_LINKS)
                            .equals(Files.getOwner(userDataDir, LinkOption.NOFOLLOW_LINKS))) {
                throw new IllegalStateException("DevToolsActivePort com dono diferente do user-data-dir; recusado");
            }
        } catch (IOException openFailure) {
            if (Files.isSymbolicLink(portFile)) {
                throw new IllegalStateException("DevToolsActivePort é link simbólico; recusado", openFailure);
            }
            throw openFailure;
        }
        if (buffer.position() > MAX_PORT_FILE_BYTES) return null;

        return new String(buffer.array(), 0, buffer.position(), StandardCharsets.UTF_8);
    }

    static boolean ownerIsCurrentUser(String ownerName, String userName, String processDefaultOwner) {
        if (ownerName.equalsIgnoreCase(userName)) return true;
        if (ownerName.toLowerCase(Locale.ROOT).endsWith("\\" + userName.toLowerCase(Locale.ROOT))) return true;
        // Em Windows elevado, arquivo criado pelo processo pertence a BUILTIN\Administrators.
        return processDefaultOwner != null && ownerName.equalsIgnoreCase(processDefaultOwner);
    }

    private static String processDefaultOwner() {
        return DefaultOwnerHolder.VALUE;
    }

    private static final class DefaultOwnerHolder {
        static final String VALUE = probeDefaultOwner();
    }

    private static String probeDefaultOwner() {
        Path tmp = null;
        try {
            tmp = Files.createTempFile("jdl-owner", ".tmp");
            return Files.getOwner(tmp).getName();
        } catch (IOException | SecurityException | UnsupportedOperationException ignored) {
            // Sem dono padrão detectável, só valem os nomes do usuário atual.
            return null;
        } finally {
            if (tmp != null) {
                try {
                    Files.deleteIfExists(tmp);
                } catch (IOException ignored) {
                    // Arquivo temporário órfão não afeta a verificação.
                }
            }
        }
    }

    static boolean listenSocketOwnedBy(int port, ProcessHandle process, Path procRoot) {
        Path tcp = procRoot.resolve("net/tcp");
        if (!Files.exists(tcp)) {
            // Sem /proc/net/tcp, autentica via PIDs do socket LISTEN informados pelo SO.
            Set<Long> pids;
            try {
                pids = listenerPids.lookup(port);
            } catch (IOException | RuntimeException ignored) {
                // Falha na consulta não autentica a porta.
                return false;
            }
            if (pids == null || pids.isEmpty()) return false;
            if (pids.contains(process.pid())) return true;
            try {
                return process.descendants().map(ProcessHandle::pid).anyMatch(pids::contains);
            } catch (RuntimeException ignored) {
                return false;
            }
        }

        Set<String> listeningInodes = new HashSet<>();
        for (Path table : new Path[] {tcp, procRoot.resolve("net/tcp6")}) {
            if (!Files.exists(table)) continue;
            try {
                for (String line : Files.readAllLines(table)) {
                    String[] fields = line.trim().split("\\s+");
                    if (fields.length <= 9) continue;
                    try {
                        String[] address = fields[1].split(":", -1);
                        if (address.length != 2 || !address[0].matches("[0-9a-fA-F]+")
                                || !address[1].matches("[0-9a-fA-F]+")
                                || !fields[9].matches("\\d+")
                                || Integer.parseInt(address[1], 16) != port
                                || !fields[3].equalsIgnoreCase("0A")) continue;
                        listeningInodes.add(fields[9]);
                    } catch (NumberFormatException ignored) {
                        // Ignora linhas inválidas da tabela do kernel.
                    }
                }
            } catch (IOException ignored) {
                // Sem tabela legível não há inode que possa autenticar a porta.
            }
        }
        if (listeningInodes.isEmpty()) return false;

        Set<Long> pids = new HashSet<>();
        pids.add(process.pid());
        process.descendants().map(ProcessHandle::pid).forEach(pids::add);
        for (long pid : pids) {
            Path fdDir = procRoot.resolve(Long.toString(pid)).resolve("fd");
            try (DirectoryStream<Path> descriptors = Files.newDirectoryStream(fdDir)) {
                for (Path descriptor : descriptors) {
                    try {
                        String target = Files.readSymbolicLink(descriptor).toString();
                        for (String inode : listeningInodes) {
                            if (target.equals("socket:[" + inode + "]")) return true;
                        }
                    } catch (IOException ignored) {
                        // Um descritor pode desaparecer enquanto /proc/<pid>/fd é percorrido.
                    }
                }
            } catch (IOException ignored) {
                // O processo pode ter encerrado ou /proc pode ocultar seus descritores.
            }
        }
        return false;
    }

    private static Set<Long> systemListenerPids(int port) throws IOException {
        String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        String[] command;
        boolean lsof;
        if (os.contains("mac") || os.contains("darwin")) {
            // Caminho absoluto evita hijack via PATH.
            command = new String[] {"/usr/sbin/lsof", "-nP", "-iTCP:" + port, "-sTCP:LISTEN", "-Fp"};
            lsof = true;
        } else if (os.contains("win")) {
            // Caminho absoluto evita hijack via PATH.
            String systemRoot = System.getenv("SystemRoot");
            if (systemRoot == null || systemRoot.isBlank()) {
                systemRoot = "C:\\Windows";
            }
            command = new String[] {systemRoot + "\\System32\\netstat.exe", "-ano", "-p", "TCP"};
            lsof = false;
        } else {
            return Set.of();
        }
        // Saída vai para arquivo: waitFor continua eficaz mesmo com filho travado.
        Path tmp = Files.createTempFile("jdl-listen", ".txt");
        try {
            Process child = new ProcessBuilder(command).redirectErrorStream(true)
                    .redirectOutput(tmp.toFile()).start();
            try {
                child.getOutputStream().close();
            } catch (IOException ignored) {
                // stdin fechada por padrão; falha ao fechar não afeta a leitura.
            }
            try {
                if (!child.waitFor(5, TimeUnit.SECONDS)) {
                    child.destroyForcibly();
                    throw new IOException("Timeout consultando sockets LISTEN da porta " + port);
                }
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                child.destroyForcibly();
                throw new IOException("Interrompido consultando sockets LISTEN", interrupted);
            }
            String output;
            try (InputStream in = Files.newInputStream(tmp)) {
                output = new String(in.readNBytes(1024 * 1024), StandardCharsets.UTF_8);
            }
            return lsof ? parseLsofPids(output) : parseNetstatListenerPids(output, port);
        } finally {
            try {
                Files.deleteIfExists(tmp);
            } catch (IOException ignored) {
                // Temporário órfão não afeta a consulta.
            }
        }
    }

    static Set<Long> parseLsofPids(String output) {
        Set<Long> pids = new HashSet<>();
        if (output == null) return pids;
        for (String line : output.split("\\R")) {
            String trimmed = line.trim();
            if (trimmed.length() < 2 || trimmed.charAt(0) != 'p') continue;
            try {
                pids.add(Long.parseLong(trimmed.substring(1).trim()));
            } catch (NumberFormatException ignored) {
                // Ignora linhas que não são PID (ex.: "f7").
            }
        }
        return pids;
    }

    static Set<Long> parseNetstatListenerPids(String output, int port) {
        Set<Long> pids = new HashSet<>();
        if (output == null) return pids;
        String suffix = ":" + port;
        for (String line : output.split("\\R")) {
            String[] fields = line.trim().split("\\s+");
            if (fields.length < 5) continue;
            try {
                // Ignora o texto do estado (localizado: LISTENING, ESCUTANDO, ABHÖREN...).
                if (!fields[0].equalsIgnoreCase("TCP")) continue;
                if (!fields[1].endsWith(suffix)) continue;
                if (!fields[2].endsWith(":0")) continue;
                pids.add(Long.parseLong(fields[fields.length - 1]));
            } catch (NumberFormatException ignored) {
                // Ignora linhas inválidas (cabeçalho, PID não numérico).
            }
        }
        return pids;
    }

    private static void continueWaiting(long deadline) {
        continueWaiting(deadline, "Timeout aguardando DevToolsActivePort do Chrome");
    }

    private static void continueWaiting(long deadline, String timeoutMessage) {
        long remainingNanos = deadline - System.nanoTime();
        if (remainingNanos <= 0) {
            throw new IllegalStateException(timeoutMessage);
        }
        try {
            long sleepMillis = Math.min(100L, Math.max(1L, remainingNanos / 1_000_000L));
            Thread.sleep(sleepMillis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrompido aguardando DevToolsActivePort do Chrome", e);
        }
    }
}
