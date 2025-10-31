package io.github.selenium.javaDriverless.scripts;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

/**
 * Utilitários para manipulação de preferências do Chrome.
 */
public class Prefs {
    
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
            try {
                String json = objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(prefs);
                Files.writeString(prefsPath, json);
            } catch (IOException e) {
                throw new RuntimeException("Erro ao escrever preferências", e);
            }
        });
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
                String json = Files.readString(prefsPath);
                return objectMapper.readValue(json, Map.class);
            } catch (IOException e) {
                throw new RuntimeException("Erro ao ler preferências", e);
            }
        });
    }
}

