package io.github.selenium.javaDriverless.scripts;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.selenium.javaDriverless.sync.SyncChrome;
import io.github.selenium.javaDriverless.types.BrowserFingerprintProfile;
import io.github.selenium.javaDriverless.types.ChromeOptions;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Diagnostico opt-in das causas exatas registradas pelo proprio CreepJS.
 *
 * <p>O DOM informa apenas quais secoes receberam {@code .lies.hash}. O objeto publico
 * {@code window.Fingerprint.lies} preserva a relacao API -> motivos usada pelo relatorio e e a
 * fonte mais precisa para investigar a causa sem copiar o detector para este projeto.</p>
 */
@Tag("external")
class CreepJsLieDiagnosticsTest {
    private static final String ENABLE_PROPERTY = "java.driverless.creepjsDiagnostics";
    private static final String PROFILE_PROPERTY = "java.driverless.creepjsProfile";
    private static final String CREEPJS_URL = "https://abrahamjuliot.github.io/creepjs/";
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String READ_DIAGNOSTIC = """
            (() => {
              if (location.origin !== 'https://abrahamjuliot.github.io' ||
                  !location.pathname.startsWith('/creepjs')) return null;
              const fp = window.Fingerprint;
              if (!fp || !fp.lies || typeof fp.lies.totalLies !== 'number' ||
                  !fp.lies.data || typeof fp.lies.data !== 'object') return null;

              const data = Object.fromEntries(Object.entries(fp.lies.data)
                .sort(([a], [b]) => a.localeCompare(b))
                .map(([api, reasons]) => [api,
                  (Array.isArray(reasons) ? reasons : [reasons]).map(String).sort()
                ]));
              const sections = [...new Set([...document.querySelectorAll('span.lies.hash')]
                .map(node => {
                  const label = node.previousElementSibling;
                  return label && label.tagName === 'STRONG' ? label.textContent.trim() : '';
                })
                .filter(Boolean))].sort();
              const liedModules = Object.entries(fp)
                .filter(([, value]) => value && typeof value === 'object' && Boolean(value.lied))
                .map(([name]) => name)
                .sort();

              return JSON.stringify({
                totalLies: fp.lies.totalLies,
                data,
                sections,
                liedModules
              });
            })()
            """;

    @ParameterizedTest(name = "CreepJS detalha lies com headless={0}")
    @ValueSource(booleans = {false, true})
    @Timeout(value = 4, unit = TimeUnit.MINUTES)
    void capturesExactCreepJsLieRecordsInVisibleAndHeadlessChrome(boolean headless) throws Exception {
        Assumptions.assumeTrue(Boolean.getBoolean(ENABLE_PROPERTY),
                "diagnostico externo opt-in; habilite -D" + ENABLE_PROPERTY + "=true");

        BrowserFingerprintProfile profile = BrowserFingerprintProfile.fromJson(Path.of(
                System.getProperty(PROFILE_PROPERTY,
                        "docs/site-profiles/linux-docker-server.json")));
        ChromeOptions options = new ChromeOptions();
        options.setFingerprintProfile(profile);
        options.setHeadless(headless);

        try (SyncChrome driver = new SyncChrome(options)) {
            driver.get(CREEPJS_URL, false);
            LieDiagnostic diagnostic = awaitDiagnostic(driver, 120);

            assertConsistent(diagnostic);
            if (BrowserFingerprintProfile.RUNTIME_POLICY_MINIMAL_CDP.equals(profile.runtimePolicy)) {
                assertThat(diagnostic.totalLies())
                        .as("minimal-cdp nao deve produzir lies por wrappers JavaScript")
                        .isZero();
            }
            System.out.println("CREEPJS_LIE_DIAGNOSTIC mode=" + (headless ? "headless" : "visible")
                    + " " + sanitizeForLog(JSON.writeValueAsString(diagnostic)));
            assertThat(driver.hasFingerprintRuntimeFailure())
                    .as("runtime de fingerprint permaneceu saudavel durante o diagnostico CreepJS")
                    .isFalse();
        }
    }

    private static LieDiagnostic awaitDiagnostic(SyncChrome driver, int timeoutSeconds) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(timeoutSeconds);
        RuntimeException lastFailure = null;
        while (System.nanoTime() < deadline) {
            try {
                Object raw = driver.executeScript(READ_DIAGNOSTIC);
                if (raw instanceof String json && !json.isBlank()) {
                    return JSON.readValue(json, LieDiagnostic.class);
                }
            } catch (RuntimeException transientNavigationFailure) {
                lastFailure = transientNavigationFailure;
            }
            driver.sleep(0.5);
        }
        String failureSummary = lastFailure == null ? "none" : sanitizedRootFailure(lastFailure);
        throw new AssertionError("CreepJS nao expos window.Fingerprint.lies em " + timeoutSeconds
                + "s; ultimaFalha=" + failureSummary);
    }

    private static void assertConsistent(LieDiagnostic diagnostic) {
        assertThat(diagnostic.totalLies()).isNotNegative();
        assertThat(diagnostic.data()).isNotNull();
        assertThat(diagnostic.sections()).doesNotHaveDuplicates();
        assertThat(diagnostic.liedModules()).doesNotHaveDuplicates();
        diagnostic.data().forEach((api, reasons) -> {
            assertThat(api).isNotBlank();
            assertThat(reasons).as("motivos CreepJS para " + api).isNotEmpty();
            assertThat(reasons).allSatisfy(reason -> assertThat(reason).isNotBlank());
        });
        int countedReasons = diagnostic.data().values().stream().mapToInt(List::size).sum();
        assertThat(countedReasons)
                .as("totalLies deve ser exatamente a soma de Fingerprint.lies.data")
                .isEqualTo(diagnostic.totalLies());
    }

    private static String sanitizeForLog(String value) {
        return value
                .replaceAll("(?i)(authorization|proxy-authorization|cookie|set-cookie|x-api-key|"
                        + "password|token|secret)(\\\"?\\s*[:=]\\s*\\\"?)[^,}\\s]+", "$1$2<redacted>")
                .replaceAll("(?i)\\b(Bearer|Basic)\\s+[^\\s\\\"']+", "$1 <redacted>")
                .replaceAll("(?<![\\d.])(?:\\d{1,3}\\.){3}\\d{1,3}(?![\\d.])", "<ip-redacted>")
                .replaceAll("(?i)(https?://)[^/@\\s]+@", "$1<redacted>@");
    }

    private static String sanitizedRootFailure(Throwable failure) {
        Throwable root = failure;
        while (root.getCause() != null && root.getCause() != root) {
            root = root.getCause();
        }
        return sanitizeForLog(root.getClass().getSimpleName() + ":" + String.valueOf(root.getMessage()));
    }

    private record LieDiagnostic(
            int totalLies,
            Map<String, List<String>> data,
            List<String> sections,
            List<String> liedModules) {
    }
}
