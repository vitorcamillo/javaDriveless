// Baseado em https://github.com/ultrafunkamsterdam/undetected-chromedriver
// Editado por kaliiiiiiiiii | Aurin Aegerter
// Conversão para Java: Java Driverless

package io.github.selenium.javaDriverless.utils;

import java.io.File;
import java.io.IOException;
import java.net.ServerSocket;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;

/**
 * Utilitários gerais para o Selenium Driverless.
 */
public class Utils {
    
    /**
     * Versão do Java Driverless.
     */
    public static final String VERSION = "1.9.4";
    
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
            System.err.println("Aviso: Não foi possível criar diretório de dados: " + e.getMessage());
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
        Set<String> candidates = new HashSet<>();
        
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
                    var stream = Utils.class.getClassLoader().getResourceAsStream(filename);
                    if (stream == null) {
                        throw new IOException("Recurso não encontrado: " + filename);
                    }
                    return new String(stream.readAllBytes(), encoding);
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
                        System.err.println(LICENSE);
                        return null;  // Nova versão
                    }
                } catch (IOException e) {
                    return true;
                }
            } else {
                // Primeira execução
                try {
                    System.err.println(LICENSE);
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

