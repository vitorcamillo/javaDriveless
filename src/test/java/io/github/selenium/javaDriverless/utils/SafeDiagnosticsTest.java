package io.github.selenium.javaDriverless.utils;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;

class SafeDiagnosticsTest {

    @Test
    void chromeArgumentFlagsKeepOnlyNamesAndPositionalCount() {
        List<String> flags = SafeDiagnostics.safeChromeArgumentFlags(List.of(
                "--proxy-server=http://u:p@h:1", "--user-data-dir=/home/x", "about:blank"));

        assertThat(flags).containsExactly("--proxy-server", "--user-data-dir", "<positional:1>");
        assertThat(String.join(" ", flags)).doesNotContain("u:p", "/home/x", "about:blank");
    }

    @Test
    void chromeArgumentFlagsCutAtWhitespaceAndRejectInvalidNames() {
        List<String> flags = SafeDiagnostics.safeChromeArgumentFlags(List.of(
                "--proxy-server http://fake-user:fake-pass@proxy.invalid:8080",
                "--bad\"name=1", "--", "--ok-flag"));

        assertThat(flags).containsExactly(
                "--proxy-server", "<invalid-flag>", "<invalid-flag>", "--ok-flag");
        assertThat(String.join(" ", flags)).doesNotContain("fake-pass", "proxy.invalid");
    }

    @Test
    void sanitizeMessageRedactsUrlsBearerAndPaths() {
        assertThat(SafeDiagnostics.sanitizeMessage("fail https://a.test/?k=v Bearer abc123 /home/x/y"))
                .doesNotContain("a.test", "abc123", "/home/x");
        assertThat(SafeDiagnostics.sanitizeMessage(null)).isEqualTo("<redacted>");
        assertThat(SafeDiagnostics.sanitizeMessage("   ")).isEqualTo("<redacted>");
        assertThat(SafeDiagnostics.sanitizeMessage("/home/x")).isEqualTo("<path>");
    }

    @Test
    void sanitizeMessageKeepsNeutralTextAndFlagsWithoutPositionalsHaveNoCounter() {
        assertThat(SafeDiagnostics.sanitizeMessage("ReferenceError: foo is not defined"))
                .isEqualTo("ReferenceError: foo is not defined");
        assertThat(SafeDiagnostics.safeChromeArgumentFlags(List.of("--headless=new", "--mute-audio")))
                .containsExactly("--headless", "--mute-audio");
    }

    @Test
    void sanitizeMessagePreservesSafeApiIdentifiersButStillRedactsSecrets() {
        assertThat(SafeDiagnostics.sanitizeMessage("querySelector failed for #x"))
                .contains("querySelector");
        assertThat(SafeDiagnostics.sanitizeMessage("Network.getCookies timed out"))
                .contains("getCookies");
        assertThat(SafeDiagnostics.sanitizeMessage("leaked mySessionToken123"))
                .doesNotContain("mySessionToken123");
        assertThat(SafeDiagnostics.sanitizeMessage("token=abc123"))
                .doesNotContain("abc123");
        assertThat(SafeDiagnostics.sanitizeMessage("setCookie=sessval42"))
                .doesNotContain("sessval42");
    }

    @Test
    void sanitizeMessageRedactsAuthAliasesJwtAndPrefixedKeys() {
        assertThat(SafeDiagnostics.sanitizeMessage("auth=SEGREDO_REAL123"))
                .doesNotContain("SEGREDO_REAL123");
        assertThat(SafeDiagnostics.sanitizeMessage("pwd=SenhaForte9"))
                .doesNotContain("SenhaForte9");
        assertThat(SafeDiagnostics.sanitizeMessage("session=abcdef123"))
                .doesNotContain("abcdef123");
        assertThat(SafeDiagnostics.sanitizeMessage("key sk_" + "live_51HxYzAbCdEfGh"))
                .doesNotContain("51HxYzAbCdEfGh");
        assertThat(SafeDiagnostics.sanitizeMessage(
                "jwt eyJ" + "hbGciOiJIUzI1NiJ9." + "eyJzdWIiOiIxIn0.abc-_123"))
                .doesNotContain("eyJzdWIiOiIxIn0")
                .contains("<jwt>");
        assertThat(SafeDiagnostics.sanitizeMessage("bypass=1")).isEqualTo("bypass=1");
    }

    @Test
    void sanitizeMessageRedactsJsonValuesAuthSchemesAndUserinfo() {
        // Regressão: valores JSON entre aspas, "Basic <b64>" e user:senha@host vazavam.
        assertThat(SafeDiagnostics.sanitizeMessage("{\"password\": \"s3cr3tVALUE\"}"))
                .doesNotContain("VALUE");
        assertThat(SafeDiagnostics.sanitizeMessage("{\"token\":\"tokVALUE123\"}")).doesNotContain("VALUE");
        assertThat(SafeDiagnostics.sanitizeMessage("Proxy-Authorization: Basic dXNlcjpwYXNzVkFMVUU="))
                .doesNotContain("dXNlcjpwYXNzVkFMVUU");
        assertThat(SafeDiagnostics.sanitizeMessage("proxy falhou: alice:hunter2VALUE@proxy.example.com:8080"))
                .doesNotContain("hunter2VALUE", "alice")
                .contains("proxy.example.com");
        assertThat(SafeDiagnostics.sanitizeMessage("erro em 10:30 no host")).isEqualTo("erro em 10:30 no host");
    }
}
