package io.github.selenium.javaDriverless.types;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Random;

/**
 * Gerenciador de proxies.
 * Carrega proxies de um arquivo e fornece métodos para rotação e gerenciamento.
 */
public class ProxyManager {
    
    private List<ProxyConfig> proxies;
    private int currentIndex;
    private Random random;
    
    public ProxyManager() {
        this.proxies = new ArrayList<>();
        this.currentIndex = 0;
        this.random = new Random();
    }
    
    /**
     * Carrega proxies de um arquivo.
     * Formato esperado: uma linha por proxy no formato "host:port" ou "host:port:username:password"
     */
    public void loadFromFile(String filePath) throws IOException {
        this.loadFromFile(filePath, ProxyConfig.ProxyType.HTTP);
    }
    
    /**
     * Carrega proxies de um arquivo com tipo específico.
     */
    public void loadFromFile(String filePath, ProxyConfig.ProxyType type) throws IOException {
        proxies.clear();
        currentIndex = 0;
        
        List<String> lines = Files.readAllLines(Paths.get(filePath));
        for (String line : lines) {
            line = line.trim();
            if (line.isEmpty() || line.startsWith("#")) {
                continue;
            }
            
            try {
                ProxyConfig proxy = ProxyConfig.fromString(line, type);
                proxies.add(proxy);
            } catch (Exception e) {
                System.err.println("Erro ao carregar proxy '" + line + "': " + e.getMessage());
            }
        }
    }
    
    /**
     * Adiciona um proxy à lista.
     */
    public void addProxy(ProxyConfig proxy) {
        proxies.add(proxy);
    }
    
    /**
     * Remove um proxy da lista.
     */
    public boolean removeProxy(ProxyConfig proxy) {
        return proxies.remove(proxy);
    }
    
    /**
     * Obtém o próximo proxy na sequência.
     */
    public ProxyConfig getNextProxy() {
        if (proxies.isEmpty()) {
            return null;
        }
        
        ProxyConfig proxy = proxies.get(currentIndex);
        currentIndex = (currentIndex + 1) % proxies.size();
        return proxy;
    }
    
    /**
     * Obtém um proxy aleatório.
     */
    public ProxyConfig getRandomProxy() {
        if (proxies.isEmpty()) {
            return null;
        }
        
        return proxies.get(random.nextInt(proxies.size()));
    }
    
    /**
     * Obtém o proxy atual sem avançar o índice.
     */
    public ProxyConfig getCurrentProxy() {
        if (proxies.isEmpty()) {
            return null;
        }
        
        return proxies.get(currentIndex);
    }
    
    /**
     * Define o índice atual.
     */
    public void setCurrentIndex(int index) {
        if (index >= 0 && index < proxies.size()) {
            this.currentIndex = index;
        }
    }
    
    /**
     * Obtém todos os proxies.
     */
    public List<ProxyConfig> getAllProxies() {
        return new ArrayList<>(proxies);
    }
    
    /**
     * Obtém o número total de proxies.
     */
    public int getProxyCount() {
        return proxies.size();
    }
    
    /**
     * Verifica se há proxies disponíveis.
     */
    public boolean hasProxies() {
        return !proxies.isEmpty();
    }
    
    /**
     * Embaralha a lista de proxies.
     */
    public void shuffle() {
        Collections.shuffle(proxies);
    }
    
    /**
     * Limpa todos os proxies.
     */
    public void clear() {
        proxies.clear();
        currentIndex = 0;
    }
    
    /**
     * Obtém estatísticas dos proxies.
     */
    public String getStatistics() {
        if (proxies.isEmpty()) {
            return "Nenhum proxy carregado.";
        }
        
        long httpCount = proxies.stream().filter(p -> p.getType() == ProxyConfig.ProxyType.HTTP).count();
        long httpsCount = proxies.stream().filter(p -> p.getType() == ProxyConfig.ProxyType.HTTPS).count();
        long socks5Count = proxies.stream().filter(p -> p.getType() == ProxyConfig.ProxyType.SOCKS5).count();
        long authenticatedCount = proxies.stream().filter(ProxyConfig::hasAuthentication).count();
        
        return String.format(
            "Proxies: %d total | HTTP: %d | HTTPS: %d | SOCKS5: %d | Autenticados: %d | Atual: %d",
            proxies.size(), httpCount, httpsCount, socks5Count, authenticatedCount, currentIndex
        );
    }
}



