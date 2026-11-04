package io.github.selenium.javaDriverless.sync;

import io.github.selenium.javaDriverless.Chrome;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class SyncChromeFingerprintRuntimeFailureTest {
    @Test
    void delegatesRuntimeFingerprintHealthToAsyncChrome() {
        Chrome chrome = mock(Chrome.class);
        when(chrome.hasFingerprintRuntimeFailure()).thenReturn(true);
        when(chrome.getFingerprintRuntimeFailure()).thenReturn(Optional.of("worker injection failed"));

        SyncChrome sync = new SyncChrome(chrome);

        assertThat(sync.hasFingerprintRuntimeFailure()).isTrue();
        assertThat(sync.getFingerprintRuntimeFailure()).contains("worker injection failed");
    }
}
