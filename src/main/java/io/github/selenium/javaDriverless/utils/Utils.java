// Baseado em https://github.com/ultrafunkamsterdam/undetected-chromedriver
// Editado por kaliiiiiiiiii | Aurin Aegerter
// Conversão para Java: Java Driverless

package io.github.selenium.javaDriverless.utils;

import io.github.selenium.javaDriverless.logging.JavaDriverlessLogger;
import org.slf4j.Logger;

import java.io.File;
import java.io.IOException;
import java.net.ServerSocket;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;

/**
 * Utilitários gerais para o Selenium Driverless.
 */
public class Utils {
    private static final Logger logger = JavaDriverlessLogger.getLogger(Utils.class);
    private static final int MAX_CHROME_VERSION_CACHE_ENTRIES = 32;
    private static final Map<String, String> CHROME_VERSION_CACHE = Collections.synchronizedMap(
            new LinkedHashMap<>(MAX_CHROME_VERSION_CACHE_ENTRIES, 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<String, String> eldest) {
                    return size() > MAX_CHROME_VERSION_CACHE_ENTRIES;
                }
            });

    /**
     * Versão do Java Driverless.
     */
    public static final String VERSION = "1.11.7";

    /**
     * Indica se o sistema é POSIX (Unix-like).
     */
    public static final boolean IS_POSIX = isPosixSystem();

    /**
     * Diretório de dados do aplicativo.
     */
    public static final Path DATA_DIR = getUserDataDir();

    /**
     * Texto da licença.
     */
    public static final String LICENSE =
        "\nEste projeto é licenciado sob \"Attribution-NonCommercial-ShareAlike\" conforme " +
        "https://github.com/kaliiiiiiiiii/Selenium-Driverless/blob/master/LICENSE.md#license\n";

    /**
     * Verifica se o sistema é POSIX (Unix-like).
     *
     * @return true se for POSIX
     */
    private static boolean isPosixSystem() {
        String os = System.getProperty("os.name").toLowerCase();
        return os.contains("mac") || os.contains("darwin") ||
               os.contains("linux") || os.contains("unix");
    }

    /**
     * Verifica se o sistema atual é POSIX.
     *
     * @return true se POSIX
     */
    public static boolean isPosix() {
        return IS_POSIX;
    }

    /**
     * Retorna o diretório de dados do usuário para a aplicação.
     *
     * @return caminho do diretório
     */
    private static Path getUserDataDir() {
        String userHome = System.getProperty("user.home");
        Path dataDir;

        if (IS_POSIX) {
            String os = System.getProperty("os.name").toLowerCase();
            if (os.contains("mac") || os.contains("darwin")) {
                // macOS
                dataDir = Paths.get(userHome, "Library", "Application Support", "selenium-driverless");
            } else {
                // Linux/Unix
                String xdgDataHome = System.getenv("XDG_DATA_HOME");
                if (xdgDataHome != null && !xdgDataHome.isEmpty()) {
                    dataDir = Paths.get(xdgDataHome, "selenium-driverless");
                } else {
                    dataDir = Paths.get(userHome, ".local", "share", "selenium-driverless");
                }
            }
        } else {
            // Windows
            String appData = System.getenv("LOCALAPPDATA");
            if (appData == null || appData.isEmpty()) {
                appData = System.getenv("APPDATA");
            }
            if (appData == null || appData.isEmpty()) {
                appData = userHome;
            }
            dataDir = Paths.get(appData, "selenium-driverless");
        }

        try {
            Files.createDirectories(dataDir);
        } catch (IOException e) {
            JavaDriverlessLogger.warn(logger, "Não foi possível criar diretório de dados: {}", e.getMessage());
        }

        return dataDir;
    }

    /**
     * Encontra o executável do Chrome, Chrome beta, Chrome canary ou Chromium.
     *
     * @return caminho completo do executável encontrado
     * @throws RuntimeException se o executável não for encontrado
     */
    public static String findChromeExecutable() {
        // FIX BUG-12: Usar LinkedHashSet para manter ordem de prioridade determinística
        Set<String> candidates = new LinkedHashSet<>();

        if (IS_POSIX) {
            // Unix-like systems
            String path = System.getenv("PATH");
            if (path != null) {
                for (String item : path.split(File.pathSeparator)) {
                    for (String subitem : new String[]{
                        "google-chrome",
                        "chromium",
                        "chromium-browser",
                        "chrome",
                        "google-chrome-stable"
                    }) {
                        candidates.add(item + File.separator + subitem);
                    }
                }
            }

            String os = System.getProperty("os.name").toLowerCase();
            if (os.contains("mac") || os.contains("darwin")) {
                candidates.add("/Applications/Google Chrome.app/Contents/MacOS/Google Chrome");
                candidates.add("/Applications/Chromium.app/Contents/MacOS/Chromium");
            }
        } else {
            // Windows - Caminhos padrão
            candidates.add("C:\\Program Files\\Google\\Chrome\\Application\\chrome.exe");
            candidates.add("C:\\Program Files (x86)\\Google\\Chrome\\Application\\chrome.exe");

            // Windows - Variáveis de ambiente
            Map<String, String> env = System.getenv();
            for (String envVar : new String[]{"PROGRAMFILES", "PROGRAMFILES(X86)", "LOCALAPPDATA", "PROGRAMW6432"}) {
                String envValue = env.get(envVar);
                if (envValue != null) {
                    for (String subitem : new String[]{
                        "Google\\Chrome\\Application",
                        "Google\\Chrome Beta\\Application",
                        "Google\\Chrome Canary\\Application"
                    }) {
                        candidates.add(envValue + File.separator + subitem + File.separator + "chrome.exe");
                    }
                }
            }
        }

        for (String candidate : candidates) {
            File file = new File(candidate);
            if (file.exists() && file.canExecute()) {
                try {
                    return file.getCanonicalPath();
                } catch (IOException e) {
                    return file.getAbsolutePath();
                }
            }
        }

        throw new RuntimeException("Não foi possível encontrar o executável do Chrome ou Chromium instalado");
    }

    /**
     * Detecta a versão real do Chrome instalado sem deixar a leitura do processo
     * bloquear indefinidamente. No Windows, consulta os metadados do executável:
     * executar {@code chrome.exe --version} abre o navegador gráfico e pode nunca
     * fechar o stdout.
     */
    public static String detectChromeVersion() {
        try {
            return detectChromeVersion(findChromeExecutable());
        } catch (Exception ignored) {
            return null;
        }
    }

    /** Detecta a versão do binário exato selecionado para a sessão. */
    public static String detectChromeVersion(String chromePath) {
        if (chromePath == null || chromePath.isBlank()) {
            return null;
        }
        String cacheKey = chromeVersionCacheKey(chromePath);
        String cached = CHROME_VERSION_CACHE.get(cacheKey);
        if (cached != null) {
            return cached;
        }
        String version = null;
        VersionCommandResult lastResult = null;
        if (System.getProperty("os.name", "").toLowerCase(java.util.Locale.ROOT).contains("win")) {
            String escapedPath = chromePath.replace("'", "''");
            String script = "$v=(Get-Item -LiteralPath '" + escapedPath
                    + "').VersionInfo.ProductVersion; if ($v) {[Console]::Out.Write($v)}";
            for (String shell : new String[]{"pwsh.exe", "powershell.exe"}) {
                lastResult = runVersionCommand(
                        shell, "-NoLogo", "-NoProfile", "-NonInteractive", "-Command", script);
                version = extractBrowserVersion(lastResult.output());
                if (version != null) {
                    break;
                }
            }
        } else {
            lastResult = runVersionCommand(chromePath, "--version");
            version = extractBrowserVersion(lastResult.output());
        }
        if (version != null) {
            CHROME_VERSION_CACHE.put(cacheKey, version);
            return version;
        }
        VersionCommandFailure failure = lastResult == null
                ? VersionCommandFailure.SPAWN
                : lastResult.failure() == VersionCommandFailure.NONE
                        ? VersionCommandFailure.UNPARSABLE_OUTPUT : lastResult.failure();
        JavaDriverlessLogger.debug(logger,
                "Chrome version detection failed ({})", failure);
        return null;
    }

    private static String chromeVersionCacheKey(String chromePath) {
        try {
            Path path = Path.of(chromePath).toRealPath();
            return path + "|" + Files.size(path) + "|" + Files.getLastModifiedTime(path).toMillis();
        } catch (Exception ignored) {
            return chromePath;
        }
    }

    private static VersionCommandResult runVersionCommand(String... command) {
        Process process = null;
        try {
            process = new ProcessBuilder(command).redirectErrorStream(true).start();
            if (!process.waitFor(5, java.util.concurrent.TimeUnit.SECONDS)) {
                process.destroyForcibly();
                process.waitFor(1, java.util.concurrent.TimeUnit.SECONDS);
                return new VersionCommandResult(null, VersionCommandFailure.TIMEOUT);
            }
            if (process.exitValue() != 0) {
                return new VersionCommandResult(null, VersionCommandFailure.NONZERO_EXIT);
            }
            return new VersionCommandResult(
                    new String(process.getInputStream().readAllBytes()).trim(),
                    VersionCommandFailure.NONE);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            return new VersionCommandResult(null, VersionCommandFailure.INTERRUPTED);
        } catch (Exception ignored) {
            return new VersionCommandResult(null, VersionCommandFailure.SPAWN);
        } finally {
            if (process != null && process.isAlive()) {
                process.destroyForcibly();
            }
        }
    }

    private enum VersionCommandFailure {
        NONE,
        TIMEOUT,
        NONZERO_EXIT,
        INTERRUPTED,
        SPAWN,
        UNPARSABLE_OUTPUT
    }

    private record VersionCommandResult(String output, VersionCommandFailure failure) {
    }

    private static String extractBrowserVersion(String output) {
        if (output == null || output.isBlank()) {
            return null;
        }
        java.util.regex.Matcher matcher = java.util.regex.Pattern
                .compile("(\\d+\\.\\d+\\.\\d+\\.\\d+)")
                .matcher(output);
        return matcher.find() ? matcher.group(1) : null;
    }

    /**
     * Gera um User-Agent realista para o sistema operacional local baseado na
     * versão real do Chrome instalado.
     */
    public static String generateRealisticUA() {
        String version = detectChromeVersion();
        if (version == null) {
            version = "133.0.0.0"; // fallback conservador
        }
        version = UserAgentResolver.reduceChromeVersionForUserAgent(version);
        String os = System.getProperty("os.name", "").toLowerCase(java.util.Locale.ROOT);
        if (os.contains("win")) {
            return "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 "
                    + "(KHTML, like Gecko) Chrome/" + version + " Safari/537.36";
        }
        if (os.contains("mac") || os.contains("darwin")) {
            return "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 "
                    + "(KHTML, like Gecko) Chrome/" + version + " Safari/537.36";
        }
        return "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 "
                + "(KHTML, like Gecko) Chrome/" + version + " Safari/537.36";
    }

    /**
     * Lê um arquivo de forma assíncrona.
     *
     * @param filename nome do arquivo
     * @param encoding codificação (padrão UTF-8)
     * @param fromResources se deve ler de resources do classpath
     * @return CompletableFuture com o conteúdo do arquivo
     */
    public static CompletableFuture<String> read(String filename, String encoding, boolean fromResources) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                if (fromResources) {
                    // Ler de resources
                    try (var stream = Utils.class.getClassLoader().getResourceAsStream(filename)) {
                        if (stream == null) {
                            throw new IOException("Recurso não encontrado: " + filename);
                        }
                        return new String(stream.readAllBytes(), encoding);
                    }
                } else {
                    // Ler de arquivo
                    return Files.readString(Paths.get(filename), java.nio.charset.Charset.forName(encoding));
                }
            } catch (IOException e) {
                throw new RuntimeException("Erro ao ler arquivo: " + filename, e);
            }
        });
    }

    /**
     * Lê um arquivo de forma assíncrona com encoding UTF-8.
     *
     * @param filename nome do arquivo
     * @return CompletableFuture com o conteúdo do arquivo
     */
    public static CompletableFuture<String> read(String filename) {
        return read(filename, "UTF-8", false);
    }

    /**
     * Escreve em um arquivo de forma assíncrona.
     *
     * @param filename nome do arquivo
     * @param content conteúdo a escrever
     * @param encoding codificação (padrão UTF-8)
     * @return CompletableFuture que completa quando a escrita termina
     */
    public static CompletableFuture<Void> write(String filename, String content, String encoding) {
        return CompletableFuture.runAsync(() -> {
            try {
                Files.writeString(Paths.get(filename), content, java.nio.charset.Charset.forName(encoding));
            } catch (IOException e) {
                throw new RuntimeException("Erro ao escrever arquivo: " + filename, e);
            }
        });
    }

    /**
     * Escreve em um arquivo de forma assíncrona com encoding UTF-8.
     *
     * @param filename nome do arquivo
     * @param content conteúdo a escrever
     * @return CompletableFuture que completa quando a escrita termina
     */
    public static CompletableFuture<Void> write(String filename, String content) {
        return write(filename, content, "UTF-8");
    }

    /**
     * Obtém uma porta aleatória disponível.
     *
     * @param host endereço do host (null para qualquer)
     * @return número da porta
     */
    public static int randomPort(String host) {
        try (ServerSocket socket = new ServerSocket(0, 1,
                host != null ? java.net.InetAddress.getByName(host) : null)) {
            socket.setReuseAddress(true);
            return socket.getLocalPort();
        } catch (IOException e) {
            throw new RuntimeException("Erro ao obter porta aleatória", e);
        }
    }

    /**
     * Obtém uma porta aleatória disponível em qualquer host.
     *
     * @return número da porta
     */
    public static int randomPort() {
        return randomPort(null);
    }

    /**
     * Verifica se o timeout foi excedido.
     *
     * @param startNanos tempo de início em nanosegundos
     * @param timeoutSeconds timeout em segundos
     * @throws RuntimeException se o timeout foi excedido
     */
    public static void checkTimeout(long startNanos, double timeoutSeconds) {
        double elapsedSeconds = (System.nanoTime() - startNanos) / 1_000_000_000.0;
        if (elapsedSeconds > timeoutSeconds) {
            throw new RuntimeException(String.format(
                "Operação excedeu o timeout: %.2f segundos", timeoutSeconds));
        }
    }

    /**
     * Verifica se é a primeira execução do programa.
     *
     * @return CompletableFuture com true se primeira execução, false caso contrário, null se nova versão
     */
    public static CompletableFuture<Boolean> isFirstRun() {
        return CompletableFuture.supplyAsync(() -> {
            Path path = DATA_DIR.resolve("is_first_run");

            if (Files.exists(path)) {
                try {
                    String content = Files.readString(path);
                    if (content.equals(VERSION)) {
                        return false;
                    } else {
                        Files.writeString(path, VERSION);
                        JavaDriverlessLogger.info(logger, LICENSE.trim());
                        return null;  // Nova versão
                    }
                } catch (IOException e) {
                    return true;
                }
            } else {
                // Primeira execução
                try {
                    JavaDriverlessLogger.info(logger, LICENSE.trim());
                    Files.writeString(path, VERSION);
                    return true;
                } catch (IOException e) {
                    throw new RuntimeException("Erro ao criar arquivo de primeira execução", e);
                }
            }
        });
    }

    /**
     * Obtém o User-Agent padrão salvo.
     *
     * @return CompletableFuture com o User-Agent ou null se não existir
     */
    @Deprecated
    public static CompletableFuture<String> getDefaultUA() {
        return CompletableFuture.supplyAsync(() -> {
            Path path = DATA_DIR.resolve("useragent");
            if (Files.exists(path)) {
                try {
                    return Files.readString(path);
                } catch (IOException e) {
                    return null;
                }
            }
            return null;
        });
    }

    /**
     * Define o User-Agent padrão.
     *
     * @param ua User-Agent a salvar
     * @return CompletableFuture que completa quando a escrita termina
     */
    @Deprecated
    public static CompletableFuture<Void> setDefaultUA(String ua) {
        return CompletableFuture.runAsync(() -> {
            Path path = DATA_DIR.resolve("useragent");
            try {
                Files.writeString(path, ua);
            } catch (IOException e) {
                throw new RuntimeException("Erro ao salvar User-Agent", e);
            }
        });
    }
}
