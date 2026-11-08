package io.github.selenium.javaDriverless.fpmapping;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.selenium.javaDriverless.types.ChromeOptions;
import io.github.selenium.javaDriverless.types.Target;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Regressão dos defeitos confirmados na auditoria do pacote fpmapping:
 * escapes de regex, validação de --geo, idiomas com espaço, fallback parcial
 * do catálogo, erro de JS invisível no eval e negação em sinal proibido.
 */
class AuditoriaFpmappingRegressaoTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Test
    void detectionHintScriptUsaEscapesDeRegexCorretos() {
        String script = FingerprintMappingScripts.DETECTION_HINT_SCRIPT;

        assertTrue(script.contains("webdriver\\s*(true"));
        assertTrue(script.contains("\\blies?\\b"));
        // escapes duplicados do defeito não podem voltar
        assertFalse(script.contains("webdriver\\\\s"));
        assertFalse(script.contains("\\\\blies"));
    }

    @Test
    void geoSomenteComLatitudeLancaExcecaoEComParPreencheOsDois() {
        ChromeOptions incompleto = new ChromeOptions();
        FingerprintMappingCatalog.Args argsIncompleto = FingerprintMappingCatalog.Args.parse(
                new String[]{"java-driverless", "--geo", "41.8781"});

        IllegalArgumentException erro = assertThrows(IllegalArgumentException.class,
                () -> FingerprintMappingLocationProfile.apply(incompleto, argsIncompleto));
        assertTrue(erro.getMessage().contains("--geo"));

        ChromeOptions completo = new ChromeOptions();
        FingerprintMappingCatalog.Args argsCompleto = FingerprintMappingCatalog.Args.parse(
                new String[]{"java-driverless", "--geo", "41.8781,-87.6298"});
        FingerprintMappingLocationProfile.apply(completo, argsCompleto);

        assertEquals(41.8781, completo.getFingerprintProfile().geolocation.latitude);
        assertEquals(-87.6298, completo.getFingerprintProfile().geolocation.longitude);
        assertEquals("override", completo.getFingerprintProfile().geolocation.mode);
    }

    @Test
    void idiomasComEspacoETraillingViramListaLimpaNoPerfilDeLocalizacao() {
        ChromeOptions options = new ChromeOptions();
        FingerprintMappingCatalog.Args args = FingerprintMappingCatalog.Args.parse(
                new String[]{"java-driverless", "--languages", "en-US, pt-BR,"});

        FingerprintMappingLocationProfile.apply(options, args);

        assertEquals(List.of("en-US", "pt-BR"), options.getFingerprintProfile().languages);
    }

    @Test
    void idiomasComVazioNoMeioViramListaLimpaNoPerfilDeLocalizacao() {
        ChromeOptions options = new ChromeOptions();
        FingerprintMappingCatalog.Args args = FingerprintMappingCatalog.Args.parse(
                new String[]{"java-driverless", "--languages", "en-US,,pt-BR"});

        FingerprintMappingLocationProfile.apply(options, args);

        assertEquals(List.of("en-US", "pt-BR"), options.getFingerprintProfile().languages);
    }

    @Test
    void idiomasComEspacoETraillingViramHeaderLimpoNoPerfilEsperado() {
        FingerprintMappingCatalog.Args args = FingerprintMappingCatalog.Args.parse(
                new String[]{"java-driverless", "--languages", "en-US, pt-BR,"});

        Map<String, String> expected = FingerprintExpectedProfile.load(args);

        assertEquals("en-US", expected.get("pixelscan.language.Languages from Javascript"));
        assertEquals("en-US,pt-BR;q=0.9", expected.get("http.acceptLanguage"));
        assertFalse(expected.get("http.acceptLanguage").contains(" "));
    }

    @Test
    void loadSitesComItemCorrompidoDescartaParciaisECaiNoFallback(@TempDir Path tempDir)
            throws Exception {
        Path catalogo = tempDir.resolve("catalogo-quebrado.json");
        Files.writeString(catalogo, "{\"tests\":[{\"id\":\"a\",\"url\":\"https://a.invalid\"},1]}");

        List<Map<String, String>> sites = FingerprintMappingCatalog.loadSites(catalogo, Set.of());

        assertTrue(sites.stream().noneMatch(site -> "a".equals(site.get("id"))),
                "site parcial não pode sobreviver: " + sites);
        assertTrue(sites.stream().anyMatch(site -> "pixelscan".equals(site.get("id"))),
                "fallback precisa estar presente: " + sites);
    }

    @Test
    void evalComExceptionDetailsDevolveResultadoELogaSomenteText() {
        Target target = mock(Target.class);
        when(target.executeCdpCmd(eq("Runtime.evaluate"), anyMap(), any()))
                .thenReturn(CompletableFuture.completedFuture(json("""
                        {"result":{"value":true},
                         "exceptionDetails":{"text":"Uncaught ReferenceError: x is not defined",
                                             "description":"conteudo da pagina que nao pode vazar"}}""")));

        PrintStream originalOut = System.out;
        ByteArrayOutputStream capturado = new ByteArrayOutputStream();
        System.setOut(new PrintStream(capturado, true, StandardCharsets.UTF_8));
        try {
            JsonNode result = PlainChromeCdpSupport.eval(target, "expr", 5f);
            assertTrue(result.path("result").path("value").asBoolean(false));
        } finally {
            System.setOut(originalOut);
        }

        String saida = capturado.toString(StandardCharsets.UTF_8);
        assertTrue(saida.contains("Runtime.evaluate failed: Uncaught ReferenceError: x is not defined"),
                "text do exceptionDetails precisa aparecer: " + saida);
        assertFalse(saida.contains("conteudo da pagina que nao pode vazar"),
                "description não pode vazar para o log");
    }

    @Test
    void sinalProibidoComNegacaoNaoReprovaESemNegacaoReprova() {
        FingerprintSiteContracts.Site contract = new FingerprintSiteContracts.Site();
        FingerprintSiteContracts.ForbiddenSignal sinal = new FingerprintSiteContracts.ForbiddenSignal();
        sinal.id = "bot-headless";
        sinal.terms = List.of("headless");
        contract.forbiddenSignals = List.of(sinal);

        assertTrue(((List<?>) FingerprintMappingExpectations.validateForbiddenSignals(
                contract, "you are not headless").get("hits")).isEmpty());
        assertTrue(((List<?>) FingerprintMappingExpectations.validateForbiddenSignals(
                contract, "no headless").get("hits")).isEmpty());

        Map<String, Object> reprovado = FingerprintMappingExpectations.validateForbiddenSignals(
                contract, "headless detected");
        assertTrue(((List<?>) reprovado.get("hits")).contains("bot-headless"));
        assertEquals(List.of("headless"),
                ((Map<?, ?>) reprovado.get("evidence")).get("bot-headless"));
    }

    private static JsonNode json(String raw) {
        try {
            return MAPPER.readTree(raw);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
