package io.github.selenium.javaDriverless.scripts;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;

/**
 * Utilitários para manipulação de preferências do Chrome.
 */
public class Prefs {
    /**
     * Monitores em quantidade fixa (lock striping): read-modify-write concorrente
     * no mesmo arquivo não perde overrides, sem reter um monitor por caminho já
     * usado (diretórios temporários de sessão acumulariam memória na JVM toda).
     */
    private static final int FILE_LOCK_COUNT = 64;
    private static final Object[] FILE_LOCKS = createFileLocks();

    private static Object[] createFileLocks() {
        Object[] locks = new Object[FILE_LOCK_COUNT];
        for (int i = 0; i < locks.length; i++) {
            locks[i] = new Object();
        }
        return locks;
    }

    private static Object lockFor(Path prefsPath) {
        int index = Math.floorMod(
            prefsPath.toAbsolutePath().normalize().hashCode(), FILE_LOCK_COUNT);
        return FILE_LOCKS[index];
    }

    
    private static final ObjectMapper objectMapper = new ObjectMapper();
    
    /**
     * Converte preferências com notação de ponto para estrutura JSON aninhada.
     * <p>
     * Por exemplo: {@code {"profile.default_content_setting_values.images": 2}}
     * torna-se: {@code {"profile": {"default_content_setting_values": {"images": 2}}}}
     * </p>
     *
     * @param dotPrefs mapa de preferências com notação de ponto
     * @return mapa com estrutura aninhada
     */
    public static Map<String, Object> prefsToJson(Map<String, Object> dotPrefs) {
        Map<String, Object> result = new HashMap<>();
        
        for (Map.Entry<String, Object> entry : dotPrefs.entrySet()) {
            String key = entry.getKey();
            Object value = entry.getValue();
            
            Map<String, Object> undotted = undotKey(key, value);
            mergeMaps(result, undotted);
        }
        
        return result;
    }
    
    /**
     * Converte uma chave com notação de ponto em estrutura aninhada.
     *
     * @param key chave com pontos (ex: "profile.setting.value")
     * @param value valor final
     * @return mapa aninhado
     */
    @SuppressWarnings("unchecked")
    private static Map<String, Object> undotKey(String key, Object value) {
        if (key.contains(".")) {
            int dotIndex = key.indexOf('.');
            String firstKey = key.substring(0, dotIndex);
            String rest = key.substring(dotIndex + 1);
            Map<String, Object> nested = undotKey(rest, value);
            Map<String, Object> result = new HashMap<>();
            result.put(firstKey, nested);
            return result;
        } else {
            Map<String, Object> result = new HashMap<>();
            result.put(key, value);
            return result;
        }
    }
    
    /**
     * Mescla dois mapas recursivamente.
     *
     * @param target mapa de destino
     * @param source mapa de origem
     */
    @SuppressWarnings("unchecked")
    private static void mergeMaps(Map<String, Object> target, Map<String, Object> source) {
        for (Map.Entry<String, Object> entry : source.entrySet()) {
            String key = entry.getKey();
            Object value = entry.getValue();
            
            if (target.containsKey(key) && target.get(key) instanceof Map && value instanceof Map) {
                // Ambos são mapas, mesclar recursivamente
                mergeMaps((Map<String, Object>) target.get(key), (Map<String, Object>) value);
            } else {
                // Sobrescrever ou adicionar novo valor
                target.put(key, value);
            }
        }
    }
    
    /**
     * Escreve preferências em um arquivo de forma assíncrona.
     *
     * @param prefs preferências como mapa
     * @param prefsPath caminho do arquivo
     * @return CompletableFuture que completa quando a escrita termina
     */
    public static CompletableFuture<Void> writePrefs(Map<String, Object> prefs, Path prefsPath) {
        return CompletableFuture.runAsync(() -> {
            synchronized (lockFor(prefsPath)) {
                try {
                    writeAtomically(prefs, prefsPath);
                } catch (IOException e) {
                    throw new RuntimeException("Erro ao escrever preferências", e);
                }
            }
        });
    }

    /**
     * Preserva preferências existentes e aplica recursivamente apenas os overrides informados.
     * A troca final do arquivo ocorre de forma atômica quando suportada pelo filesystem.
     */
    public static CompletableFuture<Void> mergeAndWritePrefs(Map<String, Object> overrides, Path prefsPath) {
        return CompletableFuture.runAsync(() -> {
            synchronized (lockFor(prefsPath)) {
                try {
                    Map<String, Object> merged = Files.isRegularFile(prefsPath)
                            ? readPrefsSync(prefsPath)
                            : new HashMap<>();
                    mergeMaps(merged, overrides);
                    writeAtomically(merged, prefsPath);
                } catch (IOException e) {
                    throw new RuntimeException("Erro ao mesclar preferências", e);
                }
            }
        });
    }

    /**
     * Mescla prefs e trata somente os caminhos pontuados indicados como autoritativos.
     *
     * <p>Para cada caminho, o valor dos overrides vence; se ele estiver ausente, usa-se o valor
     * original de {@code fallbacks}; se também estiver ausente, somente esse leaf é removido. Os
     * siblings da mesma subárvore são sempre preservados.</p>
     */
    public static CompletableFuture<Void> mergeAndWritePrefsReplacingPaths(
            Map<String, Object> overrides,
            Path prefsPath,
            Set<String> replacePaths,
            Map<String, Object> fallbacks) {
        return CompletableFuture.runAsync(() -> {
            synchronized (lockFor(prefsPath)) {
                try {
                    Map<String, Object> merged = Files.isRegularFile(prefsPath)
                            ? readPrefsSync(prefsPath)
                            : new HashMap<>();
                    mergeMaps(merged, overrides);
                    for (String path : replacePaths == null ? Set.<String>of() : replacePaths) {
                        PathValue desired = pathValue(overrides, path);
                        if (!desired.present()) {
                            desired = pathValue(fallbacks, path);
                        }
                        if (desired.present()) {
                            setPath(merged, path, desired.value());
                        } else {
                            removePath(merged, path);
                        }
                    }
                    writeAtomically(merged, prefsPath);
                } catch (IOException e) {
                    throw new RuntimeException("Erro ao mesclar preferências", e);
                }
            }
        });
    }

    private record PathValue(boolean present, Object value) {
        private static PathValue absent() {
            return new PathValue(false, null);
        }
    }

    private static PathValue pathValue(Map<String, Object> root, String dottedPath) {
        if (root == null || dottedPath == null || dottedPath.isBlank()) {
            return PathValue.absent();
        }
        Object current = root;
        String[] parts = dottedPath.split("\\.");
        for (int index = 0; index < parts.length; index++) {
            if (!(current instanceof Map<?, ?> map) || !map.containsKey(parts[index])) {
                return PathValue.absent();
            }
            current = map.get(parts[index]);
        }
        return new PathValue(true, current);
    }

    @SuppressWarnings("unchecked")
    private static void setPath(Map<String, Object> root, String dottedPath, Object value) {
        String[] parts = dottedPath.split("\\.");
        Map<String, Object> current = root;
        for (int index = 0; index < parts.length - 1; index++) {
            Object nested = current.get(parts[index]);
            if (!(nested instanceof Map<?, ?>)) {
                nested = new HashMap<String, Object>();
                current.put(parts[index], nested);
            }
            current = (Map<String, Object>) nested;
        }
        current.put(parts[parts.length - 1], value);
    }

    @SuppressWarnings("unchecked")
    private static boolean removePath(Map<String, Object> current, String[] parts, int index) {
        if (index == parts.length - 1) {
            current.remove(parts[index]);
            return current.isEmpty();
        }
        Object nested = current.get(parts[index]);
        if (nested instanceof Map<?, ?> nestedMap
                && removePath((Map<String, Object>) nestedMap, parts, index + 1)) {
            current.remove(parts[index]);
        }
        return current.isEmpty();
    }

    private static void removePath(Map<String, Object> root, String dottedPath) {
        removePath(root, dottedPath.split("\\."), 0);
    }
    
    /**
     * Lê preferências de um arquivo de forma assíncrona.
     *
     * @param prefsPath caminho do arquivo
     * @return CompletableFuture com as preferências
     */
    @SuppressWarnings("unchecked")
    public static CompletableFuture<Map<String, Object>> readPrefs(Path prefsPath) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                return readPrefsSync(prefsPath);
            } catch (IOException e) {
                throw new RuntimeException("Erro ao ler preferências", e);
            }
        });
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> readPrefsSync(Path prefsPath) throws IOException {
        return objectMapper.readValue(Files.readString(prefsPath), Map.class);
    }

    private static void writeAtomically(Map<String, Object> prefs, Path prefsPath) throws IOException {
        Path parent = prefsPath.toAbsolutePath().normalize().getParent();
        if (parent == null) {
            throw new IOException("Caminho de Preferences sem diretório pai");
        }
        Files.createDirectories(parent);
        Path temporary = Files.createTempFile(parent, ".Preferences-", ".tmp");
        boolean moved = false;
        try {
            String json = objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(prefs);
            Files.writeString(temporary, json);
            try {
                Files.move(temporary, prefsPath,
                        StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException unsupported) {
                Files.move(temporary, prefsPath, StandardCopyOption.REPLACE_EXISTING);
            }
            moved = true;
        } finally {
            if (!moved) {
                Files.deleteIfExists(temporary);
            }
        }
    }
}

