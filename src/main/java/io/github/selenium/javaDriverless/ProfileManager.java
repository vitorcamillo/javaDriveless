package io.github.selenium.javaDriverless;

import io.github.selenium.javaDriverless.logging.JavaDriverlessLogger;
import org.slf4j.Logger;

import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.regex.Pattern;

/**
 * Gerenciador de profiles do Chrome com controle de PIDs.
 *
 * <p>Permite que múltiplas instâncias do Chrome rodem simultaneamente,
 * cada uma com seu próprio nome/identificador. Ao reiniciar a aplicação,
 * detecta e fecha apenas o Chrome específico do profile, não todos.</p>
 *
 * <h3>Exemplo de uso:</h3>
 * <pre>{@code
 * ProfileManager manager = new ProfileManager();
 *
 * // Verificar e limpar processos antigos
 * manager.cleanupOldProcess("minha_janela");
 *
 * // Criar Chrome
 * Chrome chrome = Chrome.create(options).get();
 *
 * // Salvar PID
 * manager.savePid("minha_janela", chrome.getBrowserPid());
 * }</pre>
 */
public class ProfileManager {

    private static final Logger logger = JavaDriverlessLogger.getLogger(ProfileManager.class);
    private static final String DEFAULT_PROFILES_DIR = ".javadriverless_profiles";
    private static final String PID_FILE_EXTENSION = ".pid";
    private final Path profilesDirectory;

    /**
     * Cria um ProfileManager com diretório padrão.
     */
    public ProfileManager() {
        this(DEFAULT_PROFILES_DIR);
    }

    /**
     * Cria um ProfileManager com diretório customizado.
     *
     * @param profilesDir diretório onde os arquivos de PID serão salvos
     */
    public ProfileManager(String profilesDir) {
        this.profilesDirectory = Paths.get(profilesDir);
        try {
            Files.createDirectories(profilesDirectory);
        } catch (IOException e) {
            throw new RuntimeException("Erro ao criar diretório de profiles: " + e.getMessage(), e);
        }
    }

    /**
     * Sanitiza o nome do profile para uso seguro em nomes de arquivo.
     *
     * @param profileName nome original do profile
     * @return nome sanitizado
     */
    private String sanitizeProfileName(String profileName) {
        // Substituir caracteres problemáticos por underscore
        String sanitized = profileName
            .replaceAll("[\\\\/:*?\"<>|]", "_")
            .replaceAll("\\s+", "_");

        // Garantir que não está vazio
        if (sanitized.isEmpty()) {
            sanitized = "default_profile";
        }

        return sanitized;
    }

    /**
     * Obtém o caminho do arquivo de PID para um profile.
     *
     * @param profileName nome do profile
     * @return caminho do arquivo de PID
     */
    public Path getPidFilePath(String profileName) {
        String sanitized = sanitizeProfileName(profileName);
        return profilesDirectory.resolve(sanitized + PID_FILE_EXTENSION);
    }

    /**
     * Salva o PID de um processo Chrome para um profile.
     *
     * @param profileName nome do profile
     * @param pid PID do processo Chrome
     * @throws IOException se houver erro ao salvar
     */
    public void savePid(String profileName, long pid) throws IOException {
        Path pidFile = getPidFilePath(profileName);

        // Salvar PID e timestamp
        String content = pid + "\n" + System.currentTimeMillis();
        Files.writeString(pidFile, content, StandardOpenOption.CREATE,
            StandardOpenOption.TRUNCATE_EXISTING);

        JavaDriverlessLogger.profile(logger, "PID {} salvo para profile {} em {}", pid, profileName, pidFile);
    }

    /**
     * Carrega o PID de um profile.
     *
     * @param profileName nome do profile
     * @return Optional com o PID se o arquivo existir e for válido
     */
    public Optional<Long> loadPid(String profileName) {
        Path pidFile = getPidFilePath(profileName);

        if (!Files.exists(pidFile)) {
            return Optional.empty();
        }

        try {
            String content = Files.readString(pidFile);
            String[] lines = content.split("\n");

            if (lines.length > 0) {
                long pid = Long.parseLong(lines[0].trim());
                return Optional.of(pid);
            }
        } catch (IOException | NumberFormatException e) {
            JavaDriverlessLogger.warn(logger, "Erro ao ler PID do profile {}: {}", profileName, e.getMessage());
        }

        return Optional.empty();
    }

    /**
     * Deleta o arquivo de PID de um profile.
     *
     * @param profileName nome do profile
     */
    public void deletePidFile(String profileName) {
        Path pidFile = getPidFilePath(profileName);
        try {
            Files.deleteIfExists(pidFile);
            JavaDriverlessLogger.profile(logger, "Arquivo de PID deletado para profile {}", profileName);
        } catch (IOException e) {
            JavaDriverlessLogger.warn(logger, "Erro ao deletar arquivo de PID: {}", e.getMessage());
        }
    }

    /**
     * Verifica se um processo está rodando.
     *
     * @param pid PID do processo
     * @return true se o processo está rodando
     */
    public boolean isProcessRunning(long pid) {
        Optional<ProcessHandle> processHandle = ProcessHandle.of(pid);
        return processHandle.isPresent() && processHandle.get().isAlive();
    }

    /**
     * Tenta matar um processo.
     *
     * @param pid PID do processo
     * @return true se conseguiu matar o processo
     */
    public boolean killProcess(long pid) {
        Optional<ProcessHandle> processHandle = ProcessHandle.of(pid);

        if (processHandle.isEmpty()) {
            JavaDriverlessLogger.profile(logger, "Processo {} não encontrado", pid);
            return false;
        }

        ProcessHandle handle = processHandle.get();

        if (!handle.isAlive()) {
            JavaDriverlessLogger.profile(logger, "Processo {} já está encerrado", pid);
            return false;
        }

        JavaDriverlessLogger.profile(logger, "Encerrando processo {}", pid);

        // Tentar encerrar graciosamente primeiro
        boolean destroyed = handle.destroy();

        if (destroyed) {
            try {
                // Aguardar até 5 segundos pelo encerramento
                handle.onExit().get(5, java.util.concurrent.TimeUnit.SECONDS);
                JavaDriverlessLogger.info(logger, "Processo {} encerrado com sucesso", pid);
                return true;
            } catch (Exception e) {
                // Se não encerrou graciosamente, forçar
                JavaDriverlessLogger.warn(logger, "Forçando encerramento do processo {}", pid);
                boolean forciblyDestroyed = handle.destroyForcibly();

                if (forciblyDestroyed) {
                    try {
                        handle.onExit().get(3, java.util.concurrent.TimeUnit.SECONDS);
                        JavaDriverlessLogger.warn(logger, "Processo {} encerrado forçadamente", pid);
                        return true;
                    } catch (Exception ex) {
                        JavaDriverlessLogger.warn(logger, "Erro ao aguardar encerramento: {}", ex.getMessage());
                    }
                }
            }
        }

        return false;
    }

    /**
     * Mata o processo Chrome associado a um profile.
     *
     * @param profileName nome do profile
     * @return true se conseguiu matar o processo
     */
    public boolean killProcessByProfile(String profileName) {
        Optional<Long> pid = loadPid(profileName);

        if (pid.isEmpty()) {
            JavaDriverlessLogger.profile(logger, "Nenhum PID encontrado para profile {}", profileName);
            return false;
        }

        long processId = pid.get();

        if (!isProcessRunning(processId)) {
            JavaDriverlessLogger.profile(logger, "Processo {} do profile {} não está rodando", processId, profileName);
            deletePidFile(profileName);
            return false;
        }

        boolean killed = killProcess(processId);

        if (killed) {
            deletePidFile(profileName);
        }

        return killed;
    }

    /**
     * Limpa processos antigos de um profile.
     *
     * <p>Verifica se existe um Chrome rodando para este profile.
     * Se existir, mata o processo. Útil ao reiniciar a aplicação.</p>
     *
     * @param profileName nome do profile
     * @return true se havia um processo antigo que foi encerrado
     */
    public boolean cleanupOldProcess(String profileName) {
        Optional<Long> pid = loadPid(profileName);

        if (pid.isEmpty()) {
            JavaDriverlessLogger.profile(logger, "Nenhum processo antigo encontrado para profile {}", profileName);
            return false;
        }

        long processId = pid.get();

        if (!isProcessRunning(processId)) {
            JavaDriverlessLogger.profile(logger, "Processo antigo {} já não está rodando, limpando arquivo", processId);
            deletePidFile(profileName);
            return false;
        }

        JavaDriverlessLogger.warn(logger, "Processo antigo {} detectado para profile {}, encerrando", processId, profileName);
        return killProcessByProfile(profileName);
    }

    /**
     * Lista todos os profiles ativos (com arquivos de PID).
     *
     * @return lista de nomes de profiles
     */
    public List<String> listActiveProfiles() {
        List<String> profiles = new ArrayList<>();

        try {
            if (!Files.exists(profilesDirectory)) {
                return profiles;
            }

            Files.list(profilesDirectory)
                .filter(path -> path.toString().endsWith(PID_FILE_EXTENSION))
                .forEach(path -> {
                    String fileName = path.getFileName().toString();
                    String profileName = fileName.substring(0, fileName.length() - PID_FILE_EXTENSION.length());
                    profiles.add(profileName);
                });
        } catch (IOException e) {
            JavaDriverlessLogger.warn(logger, "Erro ao listar profiles: {}", e.getMessage());
        }

        return profiles;
    }

    /**
     * Limpa todos os processos órfãos (processos que não estão mais rodando).
     */
    public void cleanupOrphanedPidFiles() {
        List<String> profiles = listActiveProfiles();

        for (String profile : profiles) {
            Optional<Long> pid = loadPid(profile);
            if (pid.isPresent() && !isProcessRunning(pid.get())) {
                JavaDriverlessLogger.profile(logger, "Limpando arquivo órfão do profile {}", profile);
                deletePidFile(profile);
            }
        }
    }

    /**
     * Obtém informações sobre um profile.
     *
     * @param profileName nome do profile
     * @return Map com informações (pid, isRunning, timestamp)
     */
    public Map<String, Object> getProfileInfo(String profileName) {
        Map<String, Object> info = new HashMap<>();
        info.put("profileName", profileName);
        info.put("pidFile", getPidFilePath(profileName).toString());

        Optional<Long> pid = loadPid(profileName);
        if (pid.isPresent()) {
            long processId = pid.get();
            info.put("pid", processId);
            info.put("isRunning", isProcessRunning(processId));

            // Tentar obter informações do processo
            ProcessHandle.of(processId).ifPresent(handle -> {
                handle.info().command().ifPresent(cmd -> info.put("command", cmd));
                handle.info().startInstant().ifPresent(start -> info.put("startTime", start.toString()));
            });
        } else {
            info.put("pid", null);
            info.put("isRunning", false);
        }

        return info;
    }
}

