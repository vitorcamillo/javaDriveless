package io.github.selenium.javaDriverless.types;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class CdpTransportResolutionTest {
    @Test
    void defaultByOs() {
        assertThat(CdpTransport.fromSystemProperty("", "Linux")).isEqualTo(CdpTransport.PIPE);
        assertThat(CdpTransport.fromSystemProperty(null, "Mac OS X")).isEqualTo(CdpTransport.PIPE);
        assertThat(CdpTransport.fromSystemProperty("", "Windows 11")).isEqualTo(CdpTransport.TCP);
        assertThat(CdpTransport.fromSystemProperty("", "windows server")).isEqualTo(CdpTransport.TCP);
        assertThat(CdpTransport.fromSystemProperty("", "WINDOWS")).isEqualTo(CdpTransport.TCP);
    }

    @Test
    void explicitValueOverridesOs() {
        assertThat(CdpTransport.fromSystemProperty("tcp", "Linux")).isEqualTo(CdpTransport.TCP);
        assertThat(CdpTransport.fromSystemProperty(" TCP ", "Linux")).isEqualTo(CdpTransport.TCP);
        assertThat(CdpTransport.fromSystemProperty("pipe", "Windows 11")).isEqualTo(CdpTransport.PIPE);
        assertThat(CdpTransport.fromSystemProperty(" PIPE ", "Windows 11")).isEqualTo(CdpTransport.PIPE);
    }

    @Test
    void invalidValueFallsBackByOs() {
        assertThat(CdpTransport.fromSystemProperty("lixo", "Linux")).isEqualTo(CdpTransport.PIPE);
        assertThat(CdpTransport.fromSystemProperty("lixo", "Windows 10")).isEqualTo(CdpTransport.TCP);
        assertThat(CdpTransport.fromSystemProperty(null, null)).isEqualTo(CdpTransport.PIPE);
        assertThat(CdpTransport.fromSystemProperty("", "Linux windows"))
                .isEqualTo(CdpTransport.PIPE);
    }

    @Test
    void systemPropertyWithoutArgs() {
        String previous = System.getProperty("javaDriverless.cdpTransport");
        try {
            System.setProperty("javaDriverless.cdpTransport", "tcp");
            assertThat(CdpTransport.fromSystemProperty()).isEqualTo(CdpTransport.TCP);
            System.setProperty("javaDriverless.cdpTransport", "pipe");
            assertThat(CdpTransport.fromSystemProperty()).isEqualTo(CdpTransport.PIPE);
            assertThat(CdpTransport.fromSystemProperty()).isNotNull();
        } finally {
            if (previous == null) {
                System.clearProperty("javaDriverless.cdpTransport");
            } else {
                System.setProperty("javaDriverless.cdpTransport", previous);
            }
        }
    }
}
