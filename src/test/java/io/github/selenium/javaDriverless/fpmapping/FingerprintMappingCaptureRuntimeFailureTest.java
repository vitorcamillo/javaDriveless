package io.github.selenium.javaDriverless.fpmapping;

import io.github.selenium.javaDriverless.Chrome;
import io.github.selenium.javaDriverless.JavaDriverless;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.mockito.ArgumentMatchers.anyString;

class FingerprintMappingCaptureRuntimeFailureTest {
    @TempDir
    Path tempDir;

    @Test
    void captureFailsClosedBeforeValidationWhenWorkerInjectionFailed() {
        JavaDriverless driver = mock(JavaDriverless.class);
        Chrome chrome = mock(Chrome.class);
        when(driver.getChrome()).thenReturn(chrome);
        when(chrome.hasFingerprintRuntimeFailure()).thenReturn(true);
        when(chrome.getFingerprintRuntimeFailure()).thenReturn(Optional.of(
                "WorkerSpoof[browser] falhou em Runtime.evaluate\nCookie: session-value"));

        assertThatThrownBy(() -> FingerprintMappingCapture.requireFingerprintRuntimeHealthy(driver, "creepjs"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("creepjs")
                .hasMessageContaining("Runtime.evaluate")
                .hasMessageContaining("Cookie=<redacted>")
                .hasMessageNotContaining("session-value")
                .hasMessageNotContaining("\n");
    }

    @Test
    void healthyRuntimeAllowsCaptureToContinue() {
        JavaDriverless driver = mock(JavaDriverless.class);
        Chrome chrome = mock(Chrome.class);
        when(driver.getChrome()).thenReturn(chrome);
        when(chrome.hasFingerprintRuntimeFailure()).thenReturn(false);

        assertThatCode(() -> FingerprintMappingCapture.requireFingerprintRuntimeHealthy(driver, "creepjs"))
                .doesNotThrowAnyException();
    }

    @Test
    void failedCapturePersistsTheRealChallengePageInsteadOfAnEmptyArtifact() throws Exception {
        JavaDriverless driver = mock(JavaDriverless.class);
        when(driver.getCurrentUrl()).thenReturn("https://trustmyip.com/timezone-dissonance");
        when(driver.getTitle()).thenReturn("Bot Verification");
        when(driver.getPageSource()).thenReturn("<html><body>Verify you are human</body></html>");
        when(driver.executeScript(anyString())).thenAnswer(invocation -> {
            String script = invocation.getArgument(0, String.class);
            if ("document.body ? document.body.innerText : ''".equals(script)) {
                return "Bot Verification - Verify you are human";
            }
            return Map.of();
        });
        Map<String, Object> metadata = new LinkedHashMap<>();
        Path siteDir = tempDir.resolve("trustmyip-timezone");

        FingerprintMappingCapture.persistFailureSnapshot(
                driver, siteDir, "trustmyip-timezone", metadata);

        assertThatCode(() -> Files.readString(siteDir.resolve("page.html")))
                .doesNotThrowAnyException();
        org.junit.jupiter.api.Assertions.assertEquals(
                "Bot Verification - Verify you are human",
                Files.readString(siteDir.resolve("body.txt")));
        @SuppressWarnings("unchecked")
        Map<String, Object> state = (Map<String, Object>) metadata.get("failurePageState");
        org.junit.jupiter.api.Assertions.assertEquals("access-challenge", state.get("classification"));
        org.junit.jupiter.api.Assertions.assertEquals(
                "https://trustmyip.com/timezone-dissonance", metadata.get("finalUrl"));
    }

    @Test
    void chromeErrorPageIsClassifiedSeparatelyFromAnAccessChallenge() {
        org.junit.jupiter.api.Assertions.assertEquals("browser-error",
                FingerprintMappingCapture.classifyFailurePage(
                        "trustmyip.com", "ERR_SSL_PROTOCOL_ERROR"));
        org.junit.jupiter.api.Assertions.assertEquals("empty-document",
                FingerprintMappingCapture.classifyFailurePage("", ""));
        org.junit.jupiter.api.Assertions.assertEquals("unexpected-page",
                FingerprintMappingCapture.classifyFailurePage(
                        "Pixelscan fingerprint check", "Bot Verification\nNo automated behavior detected"));
    }

    @Test
    void artifactErrorsRedactAuthenticatedUrlsAndAuthorizationValues() {
        String sanitized = FingerprintMappingCapture.sanitizeArtifactText(
                "failed https://capture-user:capture-password@example.invalid/path "
                        + "Authorization: Bearer capture-token\n"
                        + "Cookie: session=capture-cookie; preference=dark\n"
                        + "X-Api-Key: capture-api-key");

        org.junit.jupiter.api.Assertions.assertFalse(sanitized.contains("capture-user"));
        org.junit.jupiter.api.Assertions.assertFalse(sanitized.contains("capture-password"));
        org.junit.jupiter.api.Assertions.assertFalse(sanitized.contains("capture-token"));
        org.junit.jupiter.api.Assertions.assertFalse(sanitized.contains("capture-cookie"));
        org.junit.jupiter.api.Assertions.assertFalse(sanitized.contains("capture-api-key"));
        org.junit.jupiter.api.Assertions.assertTrue(sanitized.contains("Authorization=<redacted>"));
        org.junit.jupiter.api.Assertions.assertTrue(sanitized.contains("Cookie=<redacted>"));
    }

    @Test
    void artifactUrlsDropUserInfoQueryValuesAndFragments() {
        String sanitized = FingerprintMappingCapture.sanitizeArtifactUrl(
                "https://capture-user:capture-password@example.invalid/path/to/page"
                        + "?token=capture-token&mode=visible#capture-fragment");

        org.junit.jupiter.api.Assertions.assertEquals(
                "https://example.invalid/path/to/page?token=<redacted>&mode=<redacted>", sanitized);
    }
}
