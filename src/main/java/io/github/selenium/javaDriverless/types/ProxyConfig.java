package io.github.selenium.javaDriverless.types;

/**
 * Classe para configuração de proxy.
 * Suporta diferentes tipos de proxy (HTTP, HTTPS, SOCKS5).
 */
public class ProxyConfig {
    
    public enum ProxyType {
        HTTP,
        HTTPS,
        SOCKS5
    }
    
    private String host;
    private int port;
    private ProxyType type;
    private String username;
    private String password;
    
    public ProxyConfig() {
    }
    
    public ProxyConfig(String host, int port, ProxyType type) {
        this.host = host;
        this.port = port;
        this.type = type;
    }
    
    public ProxyConfig(String host, int port, ProxyType type, String username, String password) {
        this.host = host;
        this.port = port;
        this.type = type;
        this.username = username;
        this.password = password;
    }
    
    /**
     * Cria um ProxyConfig a partir de uma string no formato "host:port" ou "host:port:username:password"
     */
    public static ProxyConfig fromString(String proxyString, ProxyType type) {
        String[] parts = proxyString.split(":");
        
        if (parts.length < 2) {
            throw new IllegalArgumentException("Formato de proxy inválido. Use: host:port ou host:port:username:password");
        }
        
        String host = parts[0];
        int port = Integer.parseInt(parts[1]);
        
        if (parts.length >= 4) {
            String username = parts[2];
            String password = parts[3];
            return new ProxyConfig(host, port, type, username, password);
        }
        
        return new ProxyConfig(host, port, type);
    }
    
    /**
     * Cria um ProxyConfig a partir de uma URL no formato "scheme://host:port" ou "scheme://username:password@host:port"
     */
    public static ProxyConfig fromUrl(String url) {
        try {
            // Parse da URL
            String protocol = url.substring(0, url.indexOf("://"));
            String rest = url.substring(url.indexOf("://") + 3);
            
            ProxyType type;
            switch (protocol.toLowerCase()) {
                case "http":
                    type = ProxyType.HTTP;
                    break;
                case "https":
                    type = ProxyType.HTTPS;
                    break;
                case "socks5":
                    type = ProxyType.SOCKS5;
                    break;
                default:
                    throw new IllegalArgumentException("Protocolo não suportado: " + protocol);
            }
            
            String host;
            int port;
            String username = null;
            String password = null;
            
            // Verificar se há credenciais
            if (rest.contains("@")) {
                String[] credsAndServer = rest.split("@");
                String[] creds = credsAndServer[0].split(":");
                username = creds[0];
                password = creds[1];
                rest = credsAndServer[1];
            }
            
            // Parse de host e porta
            String[] hostPort = rest.split(":");
            host = hostPort[0];
            port = Integer.parseInt(hostPort[1].replace("/", ""));
            
            if (username != null && password != null) {
                return new ProxyConfig(host, port, type, username, password);
            }
            
            return new ProxyConfig(host, port, type);
            
        } catch (Exception e) {
            throw new IllegalArgumentException("Formato de URL inválido: " + url + ". Erro: " + e.getMessage());
        }
    }
    
    /**
     * Converte o ProxyConfig para uma string no formato "host:port" ou "host:port:username:password"
     */
    public String toString() {
        if (username != null && password != null) {
            return host + ":" + port + ":" + username + ":" + password;
        }
        return host + ":" + port;
    }
    
    /**
     * Converte o ProxyConfig para uma URL
     */
    public String toUrl() {
        String scheme;
        switch (type) {
            case HTTP:
                scheme = "http";
                break;
            case HTTPS:
                scheme = "https";
                break;
            case SOCKS5:
                scheme = "socks5";
                break;
            default:
                scheme = "http";
        }
        
        if (username != null && password != null) {
            return scheme + "://" + username + ":" + password + "@" + host + ":" + port + "/";
        }
        
        return scheme + "://" + host + ":" + port + "/";
    }
    
    // Getters and Setters
    
    public String getHost() {
        return host;
    }
    
    public void setHost(String host) {
        this.host = host;
    }
    
    public int getPort() {
        return port;
    }
    
    public void setPort(int port) {
        this.port = port;
    }
    
    public ProxyType getType() {
        return type;
    }
    
    public void setType(ProxyType type) {
        this.type = type;
    }
    
    public String getUsername() {
        return username;
    }
    
    public void setUsername(String username) {
        this.username = username;
    }
    
    public String getPassword() {
        return password;
    }
    
    public void setPassword(String password) {
        this.password = password;
    }
    
    public boolean hasAuthentication() {
        return username != null && password != null;
    }
}



