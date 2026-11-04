package io.github.selenium.javaDriverless.profiles;

import io.github.selenium.javaDriverless.types.ProxyConfig;

/**
 * Credential-free proxy definition suitable for persisted profiles.
 */
public class SdkProfileProxy {
    public String host;
    public int port;
    public ProxyConfig.ProxyType type = ProxyConfig.ProxyType.HTTP;
    public String label;

    public SdkProfileProxy() {
    }

    public SdkProfileProxy(String host, int port, ProxyConfig.ProxyType type) {
        this.host = host;
        this.port = port;
        this.type = type;
    }

    public ProxyConfig toProxyConfig() {
        validate();
        return new ProxyConfig(host, port, type);
    }

    /**
     * Rejeita proxy persistido malformado (perfil importado é entrada não confiável):
     * host vazio ou com caracteres de URL/credencial, porta fora de 1..65535 ou tipo ausente.
     */
    void validate() {
        if (type == null) {
            throw new IllegalArgumentException("Proxy de perfil sem tipo");
        }
        if (port < 1 || port > 65535) {
            throw new IllegalArgumentException("Porta de proxy de perfil inválida: " + port);
        }
        if (host == null || host.isBlank() || host.length() > 253
                || !HOST_PATTERN.matcher(host).matches()) {
            throw new IllegalArgumentException("Host de proxy de perfil inválido");
        }
    }

    private static final java.util.regex.Pattern HOST_PATTERN =
            java.util.regex.Pattern.compile("[A-Za-z0-9._-]+|\\[[0-9A-Fa-f:.]+]");

    public static SdkProfileProxy fromProxyConfig(ProxyConfig proxyConfig) {
        if (proxyConfig == null) {
            return null;
        }
        return new SdkProfileProxy(proxyConfig.getHost(), proxyConfig.getPort(), proxyConfig.getType());
    }
}
