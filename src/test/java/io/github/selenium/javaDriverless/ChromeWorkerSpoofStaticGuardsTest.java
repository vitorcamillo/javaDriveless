package io.github.selenium.javaDriverless;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.fasterxml.jackson.databind.ObjectMapper;

import org.junit.jupiter.api.Test;

/**
 * Categorização fail-closed de targets privilegiados no worker spoof: URL crua
 * nunca vira categoria identificável (nem dominio, nem IP).
 */
class ChromeWorkerSpoofStaticGuardsTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static com.fasterxml.jackson.databind.JsonNode node(String raw) throws Exception {
        return MAPPER.readTree(raw);
    }

    @Test
    void privilegedSchemesAreRecognizedCaseInsensitively() throws Exception {
        assertEquals("chrome", ChromeWorkerSpoofSupportBase.privilegedScheme(
            node("{\"url\":\"CHROME://settings\"}")));
        assertEquals("devtools", ChromeWorkerSpoofSupportBase.privilegedScheme(
            node("{\"url\":\"devtools://devtools/bundled\"}")));
        assertEquals("chrome-extension", ChromeWorkerSpoofSupportBase.privilegedScheme(
            node("{\"url\":\"chrome-extension://abc/preview.html\"}")));
    }

    @Test
    void webAndUnknownSchemesAreNotPrivileged() throws Exception {
        assertEquals("", ChromeWorkerSpoofSupportBase.privilegedScheme(
            node("{\"url\":\"https://example.test/pagina\"}")));
        assertEquals("", ChromeWorkerSpoofSupportBase.privilegedScheme(
            node("{\"url\":\"cosmo://planeta\"}")));
    }

    @Test
    void blankUrlsAndMissingSchemeGetNoPrivilege() throws Exception {
        assertEquals("", ChromeWorkerSpoofSupportBase.privilegedScheme(null));
        assertEquals("", ChromeWorkerSpoofSupportBase.privilegedScheme(node("{\"url\":\"  \"}")));
        assertEquals("", ChromeWorkerSpoofSupportBase.privilegedScheme(node("{\"url\":\"sem-esquema\"}")));
    }

    @Test
    void safeTargetOriginOnlyRevealsCoarseCategories() throws Exception {
        assertEquals("<web>", ChromeWorkerSpoofSupportBase.safeTargetOrigin(
            node("{\"url\":\"https://sigilo.test/pagina?query=1\"}")));
        assertEquals("<web>", ChromeWorkerSpoofSupportBase.safeTargetOrigin(
            node("{\"url\":\"http://10.0.0.5:8080/\"}")));
        assertEquals("<empty>", ChromeWorkerSpoofSupportBase.safeTargetOrigin(null));
        assertEquals("<empty>", ChromeWorkerSpoofSupportBase.safeTargetOrigin(node("{\"url\":\"\"}")));
        assertEquals("<relative>", ChromeWorkerSpoofSupportBase.safeTargetOrigin(
            node("{\"url\":\"/caminho/relativo\"}")));
        // opaque: blob/data carregam conteúdo embutido — só a categoria sai
        assertEquals("<opaque>", ChromeWorkerSpoofSupportBase.safeTargetOrigin(
            node("{\"url\":\"blob:https://sigilo.test/uuid\"}")));
        assertEquals("<opaque>", ChromeWorkerSpoofSupportBase.safeTargetOrigin(
            node("{\"url\":\"data:text/html;base64,PGh0bWw+\"}")));
        assertEquals("<invalid>", ChromeWorkerSpoofSupportBase.safeTargetOrigin(
            node("{\"url\":\"http://[sem-colchete/\"}")));
    }
}
