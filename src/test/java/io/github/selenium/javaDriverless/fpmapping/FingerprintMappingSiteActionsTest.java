package io.github.selenium.javaDriverless.fpmapping;

import io.github.selenium.javaDriverless.JavaDriverless;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;

class FingerprintMappingSiteActionsTest {
    @Test
    void requiredCatalogSelectorIsEmbeddedInTheExecutedAction() {
        JavaDriverless driver = mock(JavaDriverless.class);
        when(driver.executeScript(contains("#kcarterlink"))).thenReturn(true);

        assertDoesNotThrow(() -> FingerprintMappingSiteActions.triggerIfNeeded(driver, Map.of(
                "id", "coveryourtracks",
                "runType", "click",
                "runSelector", "#kcarterlink")));
    }

    @Test
    void requiredCatalogActionFailsClosedWhenSelectorAndLabelsDoNotMatch() {
        JavaDriverless driver = mock(JavaDriverless.class);
        when(driver.executeScript(contains("#kcarterlink"))).thenReturn(false);

        assertThatThrownBy(() -> FingerprintMappingSiteActions.triggerIfNeeded(driver, Map.of(
                "id", "coveryourtracks",
                "runType", "click",
                "runSelector", "#kcarterlink",
                "runLabel", "TEST YOUR BROWSER"), 0L))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("required action was not found");
    }

    @Test
    void requiredActionIsPolledUntilTheSelectorAppears() {
        JavaDriverless driver = mock(JavaDriverless.class);
        when(driver.executeScript(contains("#kcarterlink"))).thenReturn(false, true);

        assertDoesNotThrow(() -> FingerprintMappingSiteActions.triggerIfNeeded(driver, Map.of(
                "id", "coveryourtracks",
                "runType", "click",
                "runSelector", "#kcarterlink"), 100L));
    }

    @Test
    void trustMyIpRejectsTheStaticPlaceholderResult() {
        JavaDriverless driver = mock(JavaDriverless.class);
        when(driver.executeScript(contains("res-ip-tz"))).thenReturn(false);

        assertThatThrownBy(() -> FingerprintMappingSiteActions.requireCompleted(
                driver, "trustmyip-timezone", 0L))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("did not produce a completed report");
    }

    @Test
    void trustMyIpPollsUntilTheAsynchronousReportIsComplete() {
        JavaDriverless driver = mock(JavaDriverless.class);
        when(driver.executeScript(contains("res-ip-tz"))).thenReturn(false, true);

        assertDoesNotThrow(() -> FingerprintMappingSiteActions.requireCompleted(
                driver, "trustmyip-timezone", 100L));
    }

    @Test
    void coverYourTracksRejectsTheLandingPageAfterTheWait() {
        JavaDriverless driver = mock(JavaDriverless.class);
        when(driver.executeScript(contains("kcarterlink"))).thenReturn(false);

        assertThatThrownBy(() -> FingerprintMappingSiteActions.requireCompleted(
                driver, "coveryourtracks", 0L))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("did not render the completed results");
    }

    @Test
    void coverYourTracksAcceptsOnlyThePopulatedResultsPageEvenWhenTheActionLinkRemains() {
        JavaDriverless driver = mock(JavaDriverless.class);
        when(driver.executeScript(argThat(script -> script != null
                && script.contains("fingerprintTable")
                && script.contains("bits of identifying information")
                && script.contains("characteristic"))))
                .thenReturn(true);

        assertDoesNotThrow(() -> FingerprintMappingSiteActions.requireCompleted(
                driver, "coveryourtracks"));
        verify(driver).executeScript(argThat(script -> script.contains("/results")
                && script.contains("fingerprintTable")
                && !script.contains("!document.getElementById('kcarterlink')")));
    }

    @Test
    void pixelScanRejectsAReportThatNeverLeavesTheScanningState() {
        JavaDriverless driver = mock(JavaDriverless.class);
        when(driver.executeScript(argThat(script -> script != null
                && script.contains("pxlscn-consistency-check"))))
                .thenReturn(false);

        assertThatThrownBy(() -> FingerprintMappingSiteActions.requireCompleted(
                driver, "pixelscan", 0L))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("did not render a final report");
    }

    @Test
    void pixelScanAcceptsEitherFinalVerdictInsteadOfSleepingForAFixedDelay() {
        JavaDriverless driver = mock(JavaDriverless.class);
        when(driver.executeScript(argThat(script -> script != null
                && script.contains("pxlscn-consistency-check")
                && script.contains("fingerprint is consistent")
                && script.contains("fingerprint is inconsistent"))))
                .thenReturn(true);

        assertDoesNotThrow(() -> FingerprintMappingSiteActions.requireCompleted(
                driver, "pixelscan", 100L));
    }
}
