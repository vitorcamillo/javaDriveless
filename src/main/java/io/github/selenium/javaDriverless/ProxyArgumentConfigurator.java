package io.github.selenium.javaDriverless;

import io.github.selenium.javaDriverless.logging.JavaDriverlessLogger;
import io.github.selenium.javaDriverless.types.ChromeOptions;
import org.slf4j.Logger;

/** Parses a proxy URI and applies it to {@link ChromeOptions} via Chrome CLI arguments. */
final class ProxyArgumentConfigurator {
    private ProxyArgumentConfigurator() {}

    static void configure(ChromeOptions options, String proxy, Logger logger) {
        try {
            String proxyStr = proxy;
            String scheme = null;
            String creds = null;
            String host;
            Integer port = null;

            if (proxyStr.contains("://")) {
                String[] parts = proxyStr.split("://", 2);
                scheme = parts[0];
                proxyStr = parts[1];
            }

            if (proxyStr.contains("@")) {
                String[] parts = proxyStr.split("@", 2);
                creds = parts[0];
                proxyStr = parts[1];
            }

            proxyStr = proxyStr.replace("/", "");
            if (proxyStr.contains(":")) {
                String[] parts = proxyStr.split(":", 2);
                host = parts[0];
                port = Integer.parseInt(parts[1]);
            } else {
                host = proxyStr;
            }

            // Allowlist de scheme: http/https/socks5 resolvem DNS de forma segura (socks5 faz DNS
            // remoto no proxy). socks4/socks resolvem DNS LOCALMENTE (DNS leak) e 'ssh' não é um
            // proxy válido para o Chrome (geraria --proxy-server quebrado → fallback DIRETO). Falhar
            // fechado: melhor abortar do que vazar DNS/IP.
            if (scheme != null && !scheme.isBlank()) {
                String s = scheme.toLowerCase(java.util.Locale.ROOT);
                if (!s.equals("http") && !s.equals("https") && !s.equals("socks5")) {
                    throw new RuntimeException("Scheme de proxy inseguro ou não suportado: '" + scheme
                            + "'. Use http, https ou socks5 (socks4/socks vazam DNS local; ssh não é proxy de Chrome).");
                }
            }

            String proxyServer = (scheme != null && !scheme.isBlank() ? scheme + "://" : "")
                    + host + (port != null ? ":" + port : "");

            // Proxy auth is not supported via CLI args; warn and configure without credentials.
            if (creds != null) {
                JavaDriverlessLogger.warn(logger,
                        "Autenticação de proxy não é suportada. Configurando apenas proxy sem autenticação: {}",
                        proxyServer);
            }

            options.addManagedLaunchArgument("--proxy-server=" + proxyServer);
            JavaDriverlessLogger.info(logger, "Proxy configurado via argumentos do Chrome");

        } catch (Exception e) {
            throw new RuntimeException("Erro ao configurar proxy via argumentos: " + e.getMessage(), e);
        }
    }
}
