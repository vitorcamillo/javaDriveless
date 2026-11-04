package io.github.selenium.javaDriverless.types;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

class TargetScriptErrorMessageTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static String message(String json) throws Exception {
        JsonNode details = MAPPER.readTree(json);
        return TargetScriptOperations.javaScriptErrorMessage(details);
    }

    @Test
    void messageHasOnlyTypeAndPosition() throws Exception {
        String message = message("""
                {"text":"Uncaught","lineNumber":3,"columnNumber":7,
                 "exception":{"type":"object","className":"Error",
                   "description":"Error: sentinel-abc https://x.test/?token=SEGREDO123\\n    at <anonymous>:3:7",
                   "value":"VALOR_PRIVADO",
                   "preview":{"properties":[{"name":"k","value":"VALOR_PRIVADO"}]}},
                 "stackTrace":{"callFrames":[{"url":"https://x.test/app.js"}]}}""");

        assertThat(message).isEqualTo("JavaScript error: Error (line 3, column 7)");
    }

    @Test
    void descriptionFreeTextNeverLeaks() throws Exception {
        String message = message("{\"exception\":{\"description\":"
                + "\"Error: {\\\"password\\\":\\\"FAKE_ABC_123\\\"} Authorization: Basic ZmFrZTpmYWtl\"}}");

        assertThat(message).isEqualTo("JavaScript error: Error");
        assertThat(message).doesNotContain("FAKE_ABC_123", "ZmFrZTpmYWtl", "password");
    }

    @Test
    void textFallbackKeepsOnlyTheTypeIdentifier() throws Exception {
        assertThat(message("{\"text\":\"ReferenceError: x is not defined\"}"))
                .isEqualTo("JavaScript error: ReferenceError");
    }

    @Test
    void maliciousClassNameFallsBack() throws Exception {
        assertThat(message("{\"exception\":{\"className\":\"Error x=SECRET\"}}"))
                .isEqualTo("JavaScript error: exception");
        assertThat(message("{\"exception\":{\"className\":\"Error x=SECRET\","
                + "\"description\":\"TypeError: boom\"}}"))
                .isEqualTo("JavaScript error: TypeError");
    }

    @Test
    void missingDetailsAreStable() throws Exception {
        assertThat(message("{}")).isEqualTo("JavaScript error: exception");
        assertThat(message("{\"lineNumber\":5}")).isEqualTo("JavaScript error: exception (line 5)");
        assertThat(TargetScriptOperations.javaScriptErrorMessage(null))
                .isEqualTo("JavaScript error: exception");
    }

    @Test
    void customErrorClassNamesNeverLeak() throws Exception {
        assertThat(message("{\"exception\":{\"className\":\"PRIVATE_ABC123\","
                + "\"description\":\"PRIVATE_ABC123: boom\"},\"text\":\"PRIVATE_ABC123: boom\"}"))
                .isEqualTo("JavaScript error: exception");
        assertThat(message("{\"exception\":{\"className\":\"DOMException\"}}"))
                .isEqualTo("JavaScript error: DOMException");
    }

    @Test
    void longIdentifierIsRejected() throws Exception {
        assertThat(message("{\"exception\":{\"className\":\"" + "a".repeat(5000) + "\"}}"))
                .isEqualTo("JavaScript error: exception");
    }
}
