package io.github.selenium.driverless;

import io.github.selenium.javaDriverless.sync.SyncChrome;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class FingerprintRuntimeFailureGateTest {
    @Test
    void externalContractFailsClosedWhenWorkerInjectionFailed() {
        SyncChrome driver = mock(SyncChrome.class);
        when(driver.hasFingerprintRuntimeFailure()).thenReturn(true);
        when(driver.getFingerprintRuntimeFailure()).thenReturn(Optional.of(
                "WorkerSpoof[browser] falhou em Runtime.evaluate\nAuthorization: bearer-value"));

        assertThatThrownBy(() ->
                StealthFingerprintExternalSiteSupport.assertNoFingerprintRuntimeFailure(driver, "creepjs"))
                .isInstanceOf(AssertionError.class)
                .hasMessageContaining("creepjs")
                .hasMessageContaining("Runtime.evaluate")
                .hasMessageContaining("Authorization=<redacted>")
                .hasMessageNotContaining("bearer-value")
                .hasMessageNotContaining("\n");
    }

    @Test
    void healthyRuntimeDoesNotBlockExternalContract() {
        SyncChrome driver = mock(SyncChrome.class);
        when(driver.hasFingerprintRuntimeFailure()).thenReturn(false);

        assertThatCode(() ->
                StealthFingerprintExternalSiteSupport.assertNoFingerprintRuntimeFailure(driver, "creepjs"))
                .doesNotThrowAnyException();
    }
}
