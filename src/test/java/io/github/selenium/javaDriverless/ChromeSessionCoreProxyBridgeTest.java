package io.github.selenium.javaDriverless;

import io.github.selenium.javaDriverless.types.ChromeOptions;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ChromeSessionCoreProxyBridgeTest {
    @Test
    void authenticatedProxyBridgeStartsOnlyForLocalSessions() {
        ChromeOptions localOptions = new ChromeOptions();
        localOptions.setAuthenticatedProxyBridge("http://user:pass@127.0.0.1:8899");
        Chrome localChrome = new Chrome(localOptions, 1.0f, false, 1 << 20);
        localChrome.isRemote = false;

        ChromeOptions remoteOptions = new ChromeOptions();
        remoteOptions.setAuthenticatedProxyBridge("http://user:pass@127.0.0.1:8899");
        Chrome remoteChrome = new Chrome(remoteOptions, 1.0f, false, 1 << 20);
        remoteChrome.isRemote = true;

        assertThat(localChrome.shouldStartAuthenticatedProxyBridge()).isTrue();
        assertThat(remoteChrome.shouldStartAuthenticatedProxyBridge()).isFalse();
    }
}
