package io.github.selenium.javaDriverless.fpmapping;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Test;

/**
 * Tabelas de termos de validação do catálogo e helpers de normalização de texto:
 * sem termos certos, a validação dos sites vira falso positivo/negativo.
 */
class FingerprintMappingTermsTest {

    // ---------- requiredFields ----------

    @Test
    void requiredFieldsFallBackToCsvWhenNoContract() {
        assertEquals(List.of("campo-a", "campo-b"),
            FingerprintMappingTerms.requiredFields("campo-a, campo-b,, ", null));
        assertTrue(FingerprintMappingTerms.requiredFields("", null).isEmpty());
        assertTrue(FingerprintMappingTerms.requiredFields(null, null).isEmpty());
    }

    @Test
    void requiredFieldsComeFromContractDomIdsWhenPresent() {
        FingerprintSiteContracts.Site contract = new FingerprintSiteContracts.Site();
        FingerprintSiteContracts.RequiredDom entrada1 = new FingerprintSiteContracts.RequiredDom();
        entrada1.id = "pixelscan-verdict";
        FingerprintSiteContracts.RequiredDom entrada2 = new FingerprintSiteContracts.RequiredDom();
        entrada2.id = "  ";
        contract.requiredDom = List.of(entrada1, entrada2);

        List<String> campos = FingerprintMappingTerms.requiredFields("csv,ignorado", contract);

        assertEquals(List.of("pixelscan-verdict"), campos);
    }

    // ---------- termsFor ----------

    @Test
    void termsForKnownSitesComeFromSiteTable() {
        assertEquals(List.of("fingerprint scan"),
            FingerprintMappingTerms.termsFor("pixelscan", "pixelscan-main-heading", null));
        assertEquals(List.of("mdns", "local ip address", "your webrtc ip"),
            FingerprintMappingTerms.termsFor("browserleaks-webrtc", "mdns", null));
        assertEquals(List.of("canvas fingerprint", "signature", "fingerprint"),
            FingerprintMappingTerms.termsFor("browserleaks-canvas", "canvas-hash", null));
        assertEquals(List.of("font metrics", "fonts", "unicode glyphs"),
            FingerprintMappingTerms.termsFor("browserleaks-fonts", "font-list", null));
        assertEquals(List.of("broken image dimensions", "hairline", "transparent_pixel"),
            FingerprintMappingTerms.termsFor("sannysoft", "hairline", null));
        assertTrue(FingerprintMappingTerms.termsFor("coveryourtracks",
            "tracking-protection", null).contains("tracking protection"));
        assertTrue(FingerprintMappingTerms.termsFor("checkup-000r",
            "browser-revealed-data", null).contains("privacy summary"));
        assertTrue(FingerprintMappingTerms.termsFor("antidetect-timezone",
            "timezone-match", null).contains("perfect match"));
        assertTrue(FingerprintMappingTerms.termsFor("pixelscan",
            "bot/headless/masking", null).contains("headless"));
    }

    @Test
    void termsForUnknownFieldFallsBackToGenericTable() {
        assertTrue(FingerprintMappingTerms.termsFor("site-desconhecido", "webgl", null)
            .contains("renderer"));
        assertTrue(FingerprintMappingTerms.termsFor("site-desconhecido", "tls-fingerprint", null)
            .contains("ja3"));
        assertTrue(FingerprintMappingTerms.termsFor("site-desconhecido", "trust-score", null)
            .contains("trust score"));
    }

    @Test
    void termsForTotallyUnknownFieldDerivesFromItsOwnName() {
        // hífen e barra viram espaço nas variações derivadas
        assertEquals(List.of("campo-novo", "campo novo"),
            FingerprintMappingTerms.termsFor("site-desconhecido", "campo-novo", null));
        assertEquals(List.of("a/b", "a b"),
            FingerprintMappingTerms.termsFor("site-desconhecido", "a/b", null));
    }

    @Test
    void contractTermsWinOverSiteAndGenericTables() {
        FingerprintSiteContracts.Site contract = new FingerprintSiteContracts.Site();
        FingerprintSiteContracts.RequiredDom entrada = new FingerprintSiteContracts.RequiredDom();
        entrada.id = "pixelscan-verdict";
        entrada.terms = List.of("termo-do-contrato");
        contract.requiredDom = List.of(entrada);

        assertEquals(List.of("termo-do-contrato"),
            FingerprintMappingTerms.termsFor("pixelscan", "pixelscan-verdict", contract));
        // campo diferente do contrato cai na tabela do site
        assertEquals(List.of("fingerprint scan"),
            FingerprintMappingTerms.termsFor("pixelscan", "pixelscan-main-heading", contract));
    }

    @Test
    void contractEntryWithEmptyTermsIsSkipped() {
        FingerprintSiteContracts.Site contract = new FingerprintSiteContracts.Site();
        FingerprintSiteContracts.RequiredDom vazia = new FingerprintSiteContracts.RequiredDom();
        vazia.id = "pixelscan-verdict";
        vazia.terms = List.of();
        contract.requiredDom = List.of(vazia);

        assertEquals(List.of("your browser fingerprint is consistent",
                "your browser fingerprint is inconsistent"),
            FingerprintMappingTerms.termsFor("pixelscan", "pixelscan-verdict", contract));
    }

    // ---------- FingerprintMappingText ----------

    @Test
    void textContainsMatchesTermLowercasedInsidePreNormalizedText() {
        // contrato: o texto chega já normalizado; só o termo é normalizado aqui
        assertTrue(FingerprintMappingText.contains("fingerprint scan ok", "Fingerprint Scan"));
        assertFalse(FingerprintMappingText.contains("Fingerprint Scan OK", "fingerprint scan"));
        assertFalse(FingerprintMappingText.contains("texto", "TEXTO NÃO PRESENTE"));
        assertFalse(FingerprintMappingText.contains("texto", ""));
        assertFalse(FingerprintMappingText.contains("texto", "   "));
        assertFalse(FingerprintMappingText.contains("texto", null));
    }

    @Test
    void textNormalizeCollapsesWhitespaceToLowerCase() {
        assertEquals("a b", FingerprintMappingText.normalize("A\n\t B"));
        // null não é aceito: os chamadores normalizam antes (ex.: classifyFailurePage)
        org.junit.jupiter.api.Assertions.assertThrows(NullPointerException.class,
            () -> FingerprintMappingText.normalize(null));
    }

    @Test
    void splitCsvKeepsOnlyNonBlankTrimmedEntries() {
        assertEquals(List.of("a", "b"), FingerprintMappingText.splitCsv(" a , ,b,, "));
        assertTrue(FingerprintMappingText.splitCsv("").isEmpty());
        assertTrue(FingerprintMappingText.splitCsv(null).isEmpty());
        assertTrue(FingerprintMappingText.splitCsv(" , , ").isEmpty());
    }

    @Test
    void nonEmptyLinesTrimsAndDropsBlanks() {
        assertEquals(List.of("linha1", "linha2"),
            FingerprintMappingText.nonEmptyLines("linha1\n\n  linha2  \n"));
        assertTrue(FingerprintMappingText.nonEmptyLines("\n \n").isEmpty());
        assertTrue(FingerprintMappingText.nonEmptyLines(null).isEmpty());
    }

    @Test
    void indexOfFindsExactLabelIgnoringCaseWithOptionalStart() {
        List<String> linhas = List.of("A", "b", "C");
        assertEquals(0, FingerprintMappingText.indexOf(linhas, "a"));
        assertEquals(1, FingerprintMappingText.indexOf(linhas, "B"));
        assertEquals(-1, FingerprintMappingText.indexOf(linhas, "x"));
        assertEquals(2, FingerprintMappingText.indexOf(linhas, "c", 1));
        assertEquals(-1, FingerprintMappingText.indexOf(linhas, "a", 1));
        // start negativo é clampado para 0
        assertEquals(0, FingerprintMappingText.indexOf(linhas, "A", -5));
    }
}
