package io.github.selenium.javaDriverless.types;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ProxyConfigSecurityTest {
    @Test
    void toStringNeverExposesProxyCredentials() {
        ProxyConfig proxy = new ProxyConfig(
                "proxy.example",
                8080,
                ProxyConfig.ProxyType.HTTP,
                "sensitive-user",
                "sensitive-password");

        assertThat(proxy.toString())
                .isEqualTo("proxy.example:8080")
                .doesNotContain("sensitive-user", "sensitive-password");
    }

    @Test
    void malformedProxyUrlErrorNeverExposesRawInputOrCredentials() {
        String raw = "http://sensitive-user:sensitive-password@proxy.example:not-a-port/";

        assertThatThrownBy(() -> ProxyConfig.fromUrl(raw))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Formato de URL de proxy inválido")
                .hasMessageNotContaining(raw)
                .hasMessageNotContaining("sensitive-user")
                .hasMessageNotContaining("sensitive-password")
                .hasMessageNotContaining("not-a-port");
    }

    @Test
    void proxyManagerReportsOnlyRejectedSourceLineNumber() {
        ProxyManager manager = new ProxyManager();
        List<String> warnings = new ArrayList<>();

        manager.loadLines(
                List.of(
                        "# proxy inventory",
                        "http://sensitive-user:sensitive-password@proxy.invalid:not-a-port",
                        "proxy.example:8080"),
                ProxyConfig.ProxyType.HTTP,
                warnings::add);

        assertThat(warnings).containsExactly("Erro ao carregar proxy na linha 2");
        assertThat(manager.getProxyCount()).isEqualTo(1);
        assertThat(warnings.getFirst())
                .doesNotContain("proxy.invalid", "sensitive-user", "sensitive-password", "not-a-port");
    }
}
