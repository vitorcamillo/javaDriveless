package io.github.selenium.javaDriverless.profiles;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.selenium.javaDriverless.types.ProxyConfig;
import org.junit.jupiter.api.Test;

class SdkProfileProxyValidationTest {

    @Test
    void validProxyBecomesProxyConfig() {
        ProxyConfig config = new SdkProfileProxy("proxy.example.com", 8080, ProxyConfig.ProxyType.HTTP)
                .toProxyConfig();
        assertThat(config.getHost()).isEqualTo("proxy.example.com");
        assertThat(config.getPort()).isEqualTo(8080);
        assertThat(new SdkProfileProxy("[::1]", 3128, ProxyConfig.ProxyType.HTTP).toProxyConfig().getPort())
                .isEqualTo(3128);
    }

    @Test
    void importedProxyWithUrlOrCredentialCharsIsRejected() {
        for (String host : new String[]{"", " ", "user:pw@evil", "evil.com/x", "evil.com#", "a b", null}) {
            assertThatThrownBy(() -> new SdkProfileProxy(host, 8080, ProxyConfig.ProxyType.HTTP).toProxyConfig())
                    .as("host %s", host)
                    .isInstanceOf(IllegalArgumentException.class);
        }
    }

    @Test
    void portOutOfRangeOrMissingTypeIsRejected() {
        assertThatThrownBy(() -> new SdkProfileProxy("p.example", 0, ProxyConfig.ProxyType.HTTP).toProxyConfig())
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new SdkProfileProxy("p.example", 70000, ProxyConfig.ProxyType.HTTP).toProxyConfig())
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new SdkProfileProxy("p.example", 80, null).toProxyConfig())
                .isInstanceOf(IllegalArgumentException.class);
    }
}
