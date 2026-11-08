package io.github.selenium.javaDriverless;

import io.github.selenium.javaDriverless.logging.JavaDriverlessLogger;
import io.github.selenium.javaDriverless.utils.OwnerOnlyFiles;
import org.slf4j.Logger;

import java.io.*;
import java.nio.file.*;
import java.time.Instant;
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
    private static final String PROFILE_NAME_PREFIX = "profileNameBase64=";
    private static final String START_INSTANT_PREFIX = "start=";
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
     * Obtém o caminho do arquivo de PID para um profile.
     *
     * @param profileName nome do profile
     * @return caminho do arquivo de PID
     */
    public Path getPidFilePath(String profileName) {
        Path target = ProfileKey.resolveFileUnder(profilesDirectory, profileName, PID_FILE_EXTENSION);
        migrateLegacyPidFile(profileName, target);
        return target;
    }

    private void migrateLegacyPidFile(String profileName, Path target) {
        String raw = Objects.requireNonNullElse(profileName, "");
        String legacyName = raw.replaceAll("[\\\\/:*?\"<>|]", "_")
                .replaceAll("\\s+", "_");
        if (legacyName.isEmpty()) legacyName = "default_profile";
        Path root = profilesDirectory.toAbsolutePath().normalize();
        Path legacy = root.resolve(legacyName + PID_FILE_EXTENSION).normalize();
        Path normalizedTarget = target.toAbsolutePath().normalize();
        if (legacy.equals(normalizedTarget) || !legacy.startsWith(root)
                || Files.exists(normalizedTarget, LinkOption.NOFOLLOW_LINKS)
                || !Files.exists(legacy, LinkOption.NOFOLLOW_LINKS)) {
            return;
        }
        if (Files.isSymbolicLink(legacy)) {
            throw new IllegalStateException("Arquivo PID legado não pode ser link simbólico");
        }
        // A sanitização antiga era muitos-para-um (por exemplo, tenant/a e tenant:a
        // viravam tenant_a). Sem o nome lógico gravado no próprio arquivo não há
        // evidência suficiente para atribuir e possivelmente encerrar um processo.
        if (logicalProfileName(legacy).filter(raw::equals).isEmpty()) {
            return;
        }
        try {
            try {
                Files.move(legacy, normalizedTarget, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException unsupported) {
                Files.move(legacy, normalizedTarget);
            }
        } catch (IOException e) {
            throw new IllegalStateException("Não foi possível migrar o PID legado do profile", e);
        }
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

        // Salvar PID, timestamp e identidade de início do processo quando disponível.
        String content = pid + "\n" + System.currentTimeMillis() + "\n"
                + PROFILE_NAME_PREFIX + Base64.getUrlEncoder().withoutPadding()
                .encodeToString(Objects.requireNonNullElse(profileName, "")
                        .getBytes(java.nio.charset.StandardCharsets.UTF_8));
        Optional<Instant> startInstant = ProcessHandle.of(pid)
                .flatMap(handle -> handle.info().startInstant());
        if (startInstant.isPresent()) {
            content += "\n" + START_INSTANT_PREFIX + startInstant.get().toEpochMilli();
        }
        // 0600 e sem seguir symlink: fecha a janela entre o resolve do profile e a escrita.
        OwnerOnlyFiles.writeString(pidFile, content);

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

        return readPid(pidFile);
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
     * Não verifica identidade do processo; para o PID salvo de um profile use killProcessByProfile.
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

        return killProcess(processHandle.get());
    }

    private boolean killProcess(ProcessHandle handle) {
        long pid = handle.pid();

        if (!handle.isAlive()) {
            JavaDriverlessLogger.profile(logger, "Processo {} já está encerrado", pid);
            return false;
        }

        JavaDriverlessLogger.profile(logger, "Encerrando processo {}", pid);

        // Tentar encerrar graciosamente primeiro
        boolean destroyed = handle.destroy();

        // Interrupção é registrada e restaurada ao final: restaurar dentro do
        // catch faria a segunda espera falhar na hora.
        boolean interrompido = false;

        try {
            if (destroyed) {
                try {
                    // Aguardar até 5 segundos pelo encerramento
                    handle.onExit().get(5, java.util.concurrent.TimeUnit.SECONDS);
                    JavaDriverlessLogger.info(logger, "Processo {} encerrado com sucesso", pid);
                    return true;
                } catch (Exception e) {
                    // O JDK pode manter o flag ao lançar; limpar para a segunda espera funcionar.
                    interrompido |= Thread.interrupted() || e instanceof InterruptedException;
                    // Se não encerrou graciosamente, forçar
                    JavaDriverlessLogger.warn(logger, "Forçando encerramento do processo {}", pid);
                    boolean forciblyDestroyed = handle.destroyForcibly();

                    // destroyForcibly devolve false se o processo já saiu depois do SIGTERM.
                    if (forciblyDestroyed || !handle.isAlive()) {
                        try {
                            handle.onExit().get(3, java.util.concurrent.TimeUnit.SECONDS);
                            JavaDriverlessLogger.warn(logger, "Processo {} encerrado forçadamente", pid);
                            return true;
                        } catch (Exception ex) {
                            interrompido |= ex instanceof InterruptedException;
                            JavaDriverlessLogger.warn(logger, "Erro ao aguardar encerramento: {}", ex.getMessage());
                        }
                    }
                }
            }
        } finally {
            if (interrompido) Thread.currentThread().interrupt();
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

        return killProcessByProfile(profileName, processId, ProcessHandle.of(processId));
    }

    boolean killProcessByProfile(String profileName, long processId,
            Optional<ProcessHandle> processHandle) {
        if (processHandle.isEmpty() || processHandle.get().pid() != processId
                || !processHandle.get().isAlive()) {
            JavaDriverlessLogger.profile(logger, "Processo {} do profile {} não está rodando", processId, profileName);
            deletePidFile(profileName);
            return false;
        }

        ProcessHandle handle = processHandle.get();
        if (!isSameProcess(getPidFilePath(profileName), handle)) {
            JavaDriverlessLogger.warn(logger,
                    "PID {} do profile {} não comprova ser o processo salvo (PID reciclado ou arquivo legado); não será encerrado",
                    processId, profileName);
            deletePidFile(profileName);
            return false;
        }

        boolean killed = killProcess(handle);

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

            try (var paths = Files.list(profilesDirectory)) {
                paths.filter(path -> path.toString().endsWith(PID_FILE_EXTENSION))
                        .forEach(path -> profiles.add(logicalProfileName(path).orElseGet(() -> {
                            String fileName = path.getFileName().toString();
                            return fileName.substring(0, fileName.length() - PID_FILE_EXTENSION.length());
                        })));
            }
        } catch (IOException e) {
            JavaDriverlessLogger.warn(logger, "Erro ao listar profiles: {}", e.getMessage());
        }

        return profiles;
    }

    /**
     * Limpa todos os processos órfãos (processos que não estão mais rodando).
     */
    public void cleanupOrphanedPidFiles() {
        try (var paths = Files.list(profilesDirectory)) {
            for (Path pidFile : paths.filter(path -> path.toString().endsWith(PID_FILE_EXTENSION)).toList()) {
                Optional<Long> pid = readPid(pidFile);
                if (pid.isPresent() && !isProcessRunning(pid.get())) {
                    String profile = logicalProfileName(pidFile).orElse("<unknown>");
                    JavaDriverlessLogger.profile(logger, "Limpando arquivo órfão do profile {}", profile);
                    Files.deleteIfExists(pidFile);
                }
            }
        } catch (IOException e) {
            JavaDriverlessLogger.warn(logger, "Erro ao limpar arquivos PID órfãos: {}", e.getMessage());
        }
    }

    private static Optional<Long> readPid(Path pidFile) {
        try {
            List<String> lines = Files.readAllLines(pidFile);
            if (lines.isEmpty()) return Optional.empty();
            return Optional.of(Long.parseLong(lines.get(0).trim()));
        } catch (IOException | NumberFormatException e) {
            return Optional.empty();
        }
    }

    private static Optional<String> logicalProfileName(Path pidFile) {
        try {
            return Files.readAllLines(pidFile).stream()
                    .filter(line -> line.startsWith(PROFILE_NAME_PREFIX))
                    .findFirst()
                    .map(line -> line.substring(PROFILE_NAME_PREFIX.length()))
                    .map(encoded -> new String(Base64.getUrlDecoder().decode(encoded),
                            java.nio.charset.StandardCharsets.UTF_8));
        } catch (IOException | IllegalArgumentException e) {
            return Optional.empty();
        }
    }

    private static Optional<Long> readStartInstantMillis(Path pidFile) {
        try {
            return Files.readAllLines(pidFile).stream()
                    .filter(line -> line.startsWith(START_INSTANT_PREFIX))
                    .findFirst()
                    .map(line -> Long.parseLong(line.substring(START_INSTANT_PREFIX.length()).trim()));
        } catch (IOException | NumberFormatException e) {
            return Optional.empty();
        }
    }

    private boolean isSameProcess(Path pidFile, ProcessHandle handle) {
        Optional<Long> stored = readStartInstantMillis(pidFile);
        if (stored.isEmpty()) {
            return false;
        }

        Optional<Long> actual = handle.info().startInstant()
                .map(Instant::toEpochMilli);
        return actual.isPresent() && stored.get().equals(actual.get());
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
