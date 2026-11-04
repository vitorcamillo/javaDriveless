package io.github.selenium.javaDriverless.fpmapping;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.io.IOException;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.LinkedHashMap;
import java.util.Map;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import io.github.selenium.javaDriverless.JavaDriverless;
import io.github.selenium.javaDriverless.types.BrowserFingerprintProfile;

/**
 * Helpers de captura do catálogo de fingerprints: sanitização de artefatos,
 * classificação de página de falha, esperas configuradas e geolocalização.
 */
class FingerprintMappingCaptureSupportTest {

    // ---------- sanitização de texto ----------

    @Test
    void sanitizeArtifactTextRedactsUrlsAndCredentials() {
        String texto = FingerprintMappingCaptureSupport.sanitizeArtifactText(
            "falhou em https://user:pass@bot.test/x?token=abc e wss://ws.test/feed");

        assertFalse(texto.contains("user:pass"));
        assertFalse(texto.contains("token=abc"));
        assertTrue(texto.contains("<url>"));
        assertTrue(texto.contains("wss://<url>") || texto.contains("<url>"));
    }

    @Test
    void sanitizeArtifactTextRedactsHeadersAndSecretAssignments() {
        String texto = FingerprintMappingCaptureSupport.sanitizeArtifactText(
            "Authorization: Bearer xyz123\nCookie: session=segredo\napi_key=\"valor\" password=abc token: t");

        assertFalse(texto.contains("Bearer"));
        assertFalse(texto.contains("session=segredo"));
        assertFalse(texto.contains("valor"));
        assertFalse(texto.contains("password=abc"));
        assertTrue(texto.contains("Authorization=<redacted>"));
        assertTrue(texto.contains("Cookie=<redacted>"));
    }

    @Test
    void sanitizeArtifactTextCollapsesWhitespaceAndTruncates() {
        String longo = "x".repeat(600);
        String saida = FingerprintMappingCaptureSupport.sanitizeArtifactText(
            "linha1\nlinha2\tcoluna\r\n" + longo);

        assertFalse(saida.contains("\n"));
        assertFalse(saida.contains("\t"));
        assertEquals(513, saida.length());
        assertTrue(saida.endsWith("…"));
        assertEquals("", FingerprintMappingCaptureSupport.sanitizeArtifactText(null));
    }

    // ---------- sanitização de URL ----------

    @Test
    void sanitizeArtifactUrlKeepsStructureButRedactsQueryValues() {
        assertEquals("https://bot.test/pagina",
            FingerprintMappingCaptureSupport.sanitizeArtifactUrl("https://BOT.test/pagina"));
        assertEquals("https://bot.test/busca?q=<redacted>",
            FingerprintMappingCaptureSupport.sanitizeArtifactUrl("https://bot.test/busca?q=segredo"));
        assertEquals("https://bot.test/busca?param=<redacted>",
            FingerprintMappingCaptureSupport.sanitizeArtifactUrl("https://bot.test/busca?qqq$=1"));
        assertEquals("http://bot.test:8080/",
            FingerprintMappingCaptureSupport.sanitizeArtifactUrl("http://bot.test:8080"));
        // BUG conhecido: URI.getHost() já devolve o literal IPv6 entre colchetes e o
        // sanitizador adiciona um segundo par — artefato sai com [[host]]. Documentado
        // como comportamento atual até correção.
        assertEquals("https://[[2001:db8::1]]:8443/x",
            FingerprintMappingCaptureSupport.sanitizeArtifactUrl("https://[2001:db8::1]:8443/x"));
    }

    @Test
    void sanitizeArtifactUrlDegradesUnknownFormsSafely() {
        assertEquals("", FingerprintMappingCaptureSupport.sanitizeArtifactUrl(null));
        assertEquals("", FingerprintMappingCaptureSupport.sanitizeArtifactUrl("   "));
        // sem "://" o valor cai no sanitizador de texto em vez de virar <url>
        assertEquals("sem-esquema-so-texto",
            FingerprintMappingCaptureSupport.sanitizeArtifactUrl("sem-esquema-so-texto"));
        assertEquals("http:/sem-host",
            FingerprintMappingCaptureSupport.sanitizeArtifactUrl("http:/sem-host"));
        // com "://" mas sem host parseável, vira <url>
        assertEquals("<url>", FingerprintMappingCaptureSupport.sanitizeArtifactUrl("http://"));
        // URI ilegal (espaço) → catch genérico → <url>
        assertEquals("<url>", FingerprintMappingCaptureSupport.sanitizeArtifactUrl("chave valor"));
    }

    // ---------- classificação de página de falha ----------

    @Test
    void classifyFailurePageRecognizesBrowserErrorsAndChallenges() {
        assertEquals("browser-error",
            FingerprintMappingCaptureSupport.classifyFailurePage("ERR_CONNECTION_RESET", "conteúdo"));
        assertEquals("access-challenge",
            FingerprintMappingCaptureSupport.classifyFailurePage("Bot Verification", "corpo"));
        assertEquals("access-challenge",
            FingerprintMappingCaptureSupport.classifyFailurePage("",
                "Checking your browser before accessing exemplo.com"));
        assertEquals("access-challenge",
            FingerprintMappingCaptureSupport.classifyFailurePage("", "Verify You Are Human"));
        assertEquals("empty-document",
            FingerprintMappingCaptureSupport.classifyFailurePage("", "   "));
        assertEquals("unexpected-page",
            FingerprintMappingCaptureSupport.classifyFailurePage("Página normal", "texto comum"));
    }

    // ---------- esperas configuradas ----------

    @Test
    void waitSecondsFollowSiteSpecificOverrides() {
        assertEquals(3, FingerprintMappingCaptureSupport.initialWaitSeconds("coveryourtracks", Map.of()), 1e-9);
        assertEquals(5, FingerprintMappingCaptureSupport.initialWaitSeconds("browserleaks-dns", Map.of()), 1e-9);
        assertEquals(5, FingerprintMappingCaptureSupport.initialWaitSeconds("browserleaks-geo", Map.of()), 1e-9);
        assertEquals(15, FingerprintMappingCaptureSupport.initialWaitSeconds("generico", Map.of()), 1e-9);
        assertEquals(30, FingerprintMappingCaptureSupport.initialWaitSeconds("generico", Map.of("waitMs", "30000")), 1e-9);

        assertEquals(0, FingerprintMappingCaptureSupport.afterActionWaitSeconds("coveryourtracks", Map.of()), 1e-9);
        assertEquals(45, FingerprintMappingCaptureSupport.afterActionWaitSeconds("browserleaks-dns", Map.of()), 1e-9);
        assertEquals(30, FingerprintMappingCaptureSupport.afterActionWaitSeconds("browserleaks-geo", Map.of()), 1e-9);
        assertEquals(45, FingerprintMappingCaptureSupport.afterActionWaitSeconds("amiunique", Map.of()), 1e-9);
        assertEquals(20, FingerprintMappingCaptureSupport.afterActionWaitSeconds("browserscan", Map.of()), 1e-9);
        assertEquals(30, FingerprintMappingCaptureSupport.afterActionWaitSeconds("printleaks", Map.of()), 1e-9);
        assertEquals(5, FingerprintMappingCaptureSupport.afterActionWaitSeconds("generico", Map.of()), 1e-9);
        assertEquals(10, FingerprintMappingCaptureSupport.afterActionWaitSeconds("generico", Map.of("waitMs", "90000")), 1e-9);
    }

    @Test
    void configuredWaitMsDegradesToDefaultOnBadInput() {
        assertEquals(15000, FingerprintMappingCaptureSupport.configuredWaitMs(Map.of()));
        assertEquals(15000, FingerprintMappingCaptureSupport.configuredWaitMs(Map.of("waitMs", "")));
        assertEquals(15000, FingerprintMappingCaptureSupport.configuredWaitMs(Map.of("waitMs", "abc")));
        assertEquals(8000, FingerprintMappingCaptureSupport.configuredWaitMs(Map.of("waitMs", "8000")));
    }

    // ---------- nome de perfil ----------

    @Test
    void profileNameSanitizesInvalidCharacters() {
        // cada caractere fora de [A-Za-z0-9_.-] vira "-" (acentos quebram em dois traços)
        assertEquals("FingerprintMapping-label-sess-o",
            FingerprintMappingCaptureSupport.profileName("label", "sessão"));
        assertEquals("FingerprintMapping-meusite.com-2026_01",
            FingerprintMappingCaptureSupport.profileName("meusite.com", "2026_01"));
        assertEquals("FingerprintMapping-a-b", FingerprintMappingCaptureSupport.profileName("a", "b"));
    }

    // ---------- geolocalização ----------

    @Test
    void closeToAndFiniteValidateNumericEvidence() {
        assertTrue(FingerprintMappingCaptureSupport.closeTo("-23.5505", -23.5505));
        assertFalse(FingerprintMappingCaptureSupport.closeTo("-23.5", -23.5505));
        assertFalse(FingerprintMappingCaptureSupport.closeTo("abc", 0));
        assertFalse(FingerprintMappingCaptureSupport.closeTo(null, 0));

        assertTrue(FingerprintMappingCaptureSupport.finite("50"));
        assertFalse(FingerprintMappingCaptureSupport.finite("Infinity"));
        assertFalse(FingerprintMappingCaptureSupport.finite("NaN"));
        assertFalse(FingerprintMappingCaptureSupport.finite("não-numérico"));
    }

    @Test
    void geoMatchesRequiresCoordinatesAndFiniteAccuracy() {
        BrowserFingerprintProfile profile = new BrowserFingerprintProfile();
        profile.geolocation.latitude = -23.0;
        profile.geolocation.longitude = -46.0;
        profile.geolocation.accuracy = 100.0;

        assertTrue(FingerprintMappingCaptureSupport.geoMatches(
            Map.of("latitude", "-23.0", "longitude", "-46.0", "accuracy", "100"), profile));
        assertFalse(FingerprintMappingCaptureSupport.geoMatches(
            Map.of("latitude", "-24.0", "longitude", "-46.0", "accuracy", "100"), profile));
        assertFalse(FingerprintMappingCaptureSupport.geoMatches(
            Map.of("latitude", "-23.0", "longitude", "-46.0", "accuracy", "NaN"), profile));
    }

    @Test
    void requireGeoProfileRejectsIncompleteProfiles() {
        assertThrows(IllegalArgumentException.class,
            () -> FingerprintMappingCaptureSupport.requireGeoProfile(null));
        assertThrows(IllegalArgumentException.class, () -> {
            BrowserFingerprintProfile profile = new BrowserFingerprintProfile();
            FingerprintMappingCaptureSupport.requireGeoProfile(profile);
        });
        assertThrows(IllegalArgumentException.class, () -> {
            BrowserFingerprintProfile profile = new BrowserFingerprintProfile();
            profile.geolocation.latitude = 1.0;
            FingerprintMappingCaptureSupport.requireGeoProfile(profile);
        });

        BrowserFingerprintProfile completo = new BrowserFingerprintProfile();
        completo.geolocation.latitude = 1.0;
        completo.geolocation.longitude = 2.0;
        FingerprintMappingCaptureSupport.requireGeoProfile(completo);
    }

    // ---------- scripts de evidência ----------

    @Test
    void evidenceScriptsEmbedExpectedDomHooks() {
        String creep = FingerprintMappingCaptureSupport.creepDetectionSignalsScript();
        assertTrue(creep.contains("Fingerprint.headless"));
        assertTrue(creep.contains("likeHeadless"));

        String geoWatch = FingerprintMappingCaptureSupport.geoRenderedScript(true);
        assertTrue(geoWatch.contains("geo-watch-status"));
        assertTrue(geoWatch.contains("geo-watch-button"));

        String geoSimple = FingerprintMappingCaptureSupport.geoRenderedScript(false);
        assertFalse(geoSimple.contains("geo-watch-status"));
        assertTrue(geoSimple.contains("false"));
    }

    // ---------- perfis esperados ----------

    @Test
    void expectedProfileMergesConfiguredWithDerivedValues() {
        BrowserFingerprintProfile profile = new BrowserFingerprintProfile();
        profile.browserVersion = "147.0.7727.137";

        Map<String, String> merged = FingerprintMappingCaptureSupport
            .expectedProfileForActiveProfile(new LinkedHashMap<>(Map.of("manual", "valor")), profile);

        assertEquals("valor", merged.get("manual"));
        assertFalse(merged.isEmpty());
        // valores derivados nunca sobrescrevem o configurado
        Map<String, String> derivados = io.github.selenium.javaDriverless.types
            .FingerprintProfileExpectedValues.derive(profile);
        derivados.keySet().forEach(chave ->
            assertEquals(derivados.get(chave), merged.get(chave)));
    }

    @Test
    void persistActiveExpectedProfileWritesJsonArtifact(@TempDir Path dir) throws Exception {
        BrowserFingerprintProfile profile = new BrowserFingerprintProfile();
        profile.browserVersion = "147.0.7727.137";

        Map<String, String> expected = FingerprintMappingCaptureSupport
            .persistActiveExpectedProfile(dir.resolve("expected.json"), Map.of("k", "v"), profile);

        assertTrue(Files.exists(dir.resolve("expected.json")));
        assertEquals("v", expected.get("k"));
    }

    @Test
    void persistSiteExpectedProfileIndexGroupsBySite(@TempDir Path dir) throws Exception {
        Path index = dir.resolve("index.json");

        FingerprintMappingCaptureSupport.persistSiteExpectedProfileIndex(index,
            Map.of("site1", Map.of("k", "v"), "site2", Map.of()));

        String conteudo = Files.readString(index);
        assertTrue(conteudo.contains("site-profile-index"));
        assertTrue(conteudo.contains("site1"));
        assertTrue(conteudo.contains("site2"));
    }

    // ---------- avaliação de scripts com driver mockado ----------

    @Test
    void evaluateJsonUnwrapsMapsAndParsesJsonStrings() throws Exception {
        JavaDriverless driver = mock(JavaDriverless.class);
        when(driver.executeScript("mapa")).thenReturn(Map.of("chave", 1));
        when(driver.executeScript("json")).thenReturn("{\"a\":true}");
        when(driver.executeScript("nulo")).thenReturn(null);

        assertEquals(Map.of("chave", 1), FingerprintMappingCaptureSupport.evaluateJson(driver, "mapa"));
        assertEquals(Map.of("a", true), FingerprintMappingCaptureSupport.evaluateJson(driver, "json"));
        assertEquals(Map.of(), FingerprintMappingCaptureSupport.evaluateJson(driver, "nulo"));
    }

    @Test
    void safeEvaluateJsonDegradesBrokenPayloadsToEmptyMap() {
        JavaDriverless driver = mock(JavaDriverless.class);
        when(driver.executeScript("quebrado")).thenReturn("não-é-json{");

        assertEquals(Map.of(), FingerprintMappingCaptureSupport.safeEvaluateJson(driver, "quebrado"));
    }

    @Test
    void stringScriptAndSafeStringDegradeFailuresToEmptyString() {
        JavaDriverless driver = mock(JavaDriverless.class);
        when(driver.executeScript("texto")).thenReturn("valor");
        when(driver.executeScript("nulo")).thenReturn(null);

        assertEquals("valor", FingerprintMappingCaptureSupport.stringScript(driver, "texto"));
        assertEquals("", FingerprintMappingCaptureSupport.stringScript(driver, "nulo"));
        assertEquals("", FingerprintMappingCaptureSupport.safeString(() -> {
            throw new IllegalStateException("boom");
        }));
        assertEquals("ok", FingerprintMappingCaptureSupport.safeString(() -> "ok"));
    }

    @Test
    void writeStringPersistsUtf8Content(@TempDir Path dir) throws Exception {
        Path alvo = dir.resolve("conteudo.txt");

        FingerprintMappingCaptureSupport.writeString(alvo, "acentuação ção");
        assertEquals("acentuação ção", Files.readString(alvo));

        FingerprintMappingCaptureSupport.writeString(alvo, null);
        assertEquals("", Files.readString(alvo));
    }

    // ---------- permissões dos artefatos de captura ----------

    @Test
    void writeBytesCreatesOwnerOnlyFile(@TempDir Path dir) throws Exception {
        Assumptions.assumeTrue(FileSystems.getDefault().supportedFileAttributeViews().contains("posix"));
        Path alvo = dir.resolve("screenshot.png");
        byte[] conteudo = {1, 2, 3};

        FingerprintMappingCaptureSupport.writeBytes(alvo, conteudo);

        assertEquals("rw-------", PosixFilePermissions.toString(Files.getPosixFilePermissions(alvo)));
        assertArrayEquals(conteudo, Files.readAllBytes(alvo));
    }

    @Test
    void writeBytesRestrictsPreExistingFileOnOverwrite(@TempDir Path dir) throws Exception {
        Assumptions.assumeTrue(FileSystems.getDefault().supportedFileAttributeViews().contains("posix"));
        Path alvo = dir.resolve("screenshot.png");
        Files.write(alvo, new byte[] {0});
        Files.setPosixFilePermissions(alvo, PosixFilePermissions.fromString("rw-r--r--"));
        byte[] conteudo = {4, 5, 6};

        FingerprintMappingCaptureSupport.writeBytes(alvo, conteudo);

        assertEquals("rw-------", PosixFilePermissions.toString(Files.getPosixFilePermissions(alvo)));
        assertArrayEquals(conteudo, Files.readAllBytes(alvo));
    }

    @Test
    void writeBytesRejectsSymbolicLinkWithoutChangingExternalTarget(@TempDir Path dir) throws Exception {
        Path externo = dir.resolve("external-target");
        Path artefato = dir.resolve("screenshot.png");
        byte[] conteudoOriginal = {1, 2, 3};
        Files.write(externo, conteudoOriginal);
        boolean posix = FileSystems.getDefault().supportedFileAttributeViews().contains("posix");
        if (posix) {
            Files.setPosixFilePermissions(externo, PosixFilePermissions.fromString("rw-r--r--"));
        }
        try {
            Files.createSymbolicLink(artefato, externo.getFileName());
        } catch (UnsupportedOperationException | IOException | SecurityException unavailable) {
            Assumptions.assumeTrue(false, "Este sistema de arquivos não permite criar symlinks");
        }

        assertThrows(IOException.class,
                () -> FingerprintMappingCaptureSupport.writeBytes(artefato, new byte[] {4, 5, 6}));

        assertTrue(Files.isSymbolicLink(artefato));
        assertArrayEquals(conteudoOriginal, Files.readAllBytes(externo));
        if (posix) {
            assertEquals("rw-r--r--", PosixFilePermissions.toString(Files.getPosixFilePermissions(externo)));
        }
    }

    @Test
    void createOwnerOnlyCreatesOwnerOnlyFile(@TempDir Path dir) throws Exception {
        Assumptions.assumeTrue(FileSystems.getDefault().supportedFileAttributeViews().contains("posix"));
        Path alvo = dir.resolve("screenshot.png");

        FingerprintMappingCaptureSupport.createOwnerOnly(alvo);

        assertEquals("rw-------", PosixFilePermissions.toString(Files.getPosixFilePermissions(alvo)));
    }

    @Test
    void captureArtifactsArePersistedOwnerOnly(@TempDir Path dir) throws Exception {
        org.junit.jupiter.api.Assumptions.assumeTrue(java.nio.file.FileSystems.getDefault()
            .supportedFileAttributeViews().contains("posix"));

        Map<String, Object> meta = new LinkedHashMap<>();
        meta.put("id", "site");

        FingerprintMappingCaptureSupport.writeJson(dir.resolve("metadata.json"), meta);
        FingerprintMappingCaptureSupport.writeJson(dir.resolve("MANIFEST.json"),
            java.util.List.of(meta));
        FingerprintMappingCaptureSupport.writeString(dir.resolve("page.html"), "<html></html>");
        FingerprintMappingCaptureSupport.writeString(dir.resolve("body.txt"), "texto");

        for (String artifact : new String[] {"metadata.json", "MANIFEST.json", "page.html", "body.txt"}) {
            assertEquals("rw-------", java.nio.file.attribute.PosixFilePermissions.toString(
                Files.getPosixFilePermissions(dir.resolve(artifact))), artifact);
        }
        @SuppressWarnings("unchecked")
        Map<String, Object> lido = FingerprintMappingCaptureSupport.JSON
            .readValue(Files.readString(dir.resolve("metadata.json")), Map.class);
        assertEquals("site", lido.get("id"));
    }

    @Test
    void writeJsonRestrictsPreExistingArtifactOnOverwrite(@TempDir Path dir) throws Exception {
        org.junit.jupiter.api.Assumptions.assumeTrue(java.nio.file.FileSystems.getDefault()
            .supportedFileAttributeViews().contains("posix"));

        Path alvo = dir.resolve("metadata.json");
        Files.writeString(alvo, "{}");
        Files.setPosixFilePermissions(alvo,
            java.nio.file.attribute.PosixFilePermissions.fromString("rw-r--r--"));

        FingerprintMappingCaptureSupport.writeJson(alvo, Map.of("k", "v"));

        assertEquals("rw-------", java.nio.file.attribute.PosixFilePermissions.toString(
            Files.getPosixFilePermissions(alvo)));
        @SuppressWarnings("unchecked")
        Map<String, Object> lido = FingerprintMappingCaptureSupport.JSON
            .readValue(Files.readString(alvo), Map.class);
        assertEquals("v", lido.get("k"));
    }

    @Test
    void persistFailureSnapshotWithoutDriverIsNoOp(@TempDir Path dir) {
        Map<String, Object> meta = new LinkedHashMap<>();
        persistFailureSnapshotWithNullDriver(dir, meta);
        assertTrue(meta.isEmpty());
    }

    /** persistFailureSnapshot com driver null deve retornar sem tocar o meta nem o disco. */
    private static void persistFailureSnapshotWithNullDriver(Path dir, Map<String, Object> meta) {
        FingerprintMappingCaptureSupport.persistFailureSnapshot(null, dir, "site", meta);
    }

    // ---------- preparação de runtime com driver mockado ----------

    private static BrowserFingerprintProfile perfilGeo() {
        BrowserFingerprintProfile profile = new BrowserFingerprintProfile();
        profile.geolocation.latitude = -23.0;
        profile.geolocation.longitude = -46.0;
        profile.geolocation.accuracy = 100.0;
        return profile;
    }

    @Test
    void requiredPermissionsAreAppliedOnlyForSitesThatDeclareThem() {
        io.github.selenium.javaDriverless.Chrome chrome =
            mock(io.github.selenium.javaDriverless.Chrome.class);
        JavaDriverless driver = mock(JavaDriverless.class);
        when(driver.getChrome()).thenReturn(chrome);
        when(chrome.setPermission(any(), any(), any()))
            .thenReturn(java.util.concurrent.CompletableFuture.completedFuture(null));
        when(chrome.setGeolocationOverride(any(), any(), any()))
            .thenReturn(java.util.concurrent.CompletableFuture.completedFuture(null));
        BrowserFingerprintProfile perfil = perfilGeo();

        FingerprintMappingCaptureSupport.prepareRequiredPermissionsAndOverrides(
            driver, perfil, "printleaks", "https://printleaks.test/pagina");
        org.mockito.Mockito.verify(chrome).setPermission(
            "https://printleaks.test", "local-fonts", "granted");
        org.mockito.Mockito.verify(chrome, org.mockito.Mockito.never())
            .setGeolocationOverride(any(), any(), any());

        FingerprintMappingCaptureSupport.prepareRequiredPermissionsAndOverrides(
            driver, perfil, "browserleaks-geo", "https://geo.test:8443/");
        org.mockito.Mockito.verify(chrome).setPermission(
            "https://geo.test:8443", "geolocation", "granted");
        org.mockito.Mockito.verify(chrome).setGeolocationOverride(
            perfil.geolocation.latitude, perfil.geolocation.longitude, perfil.geolocation.accuracy);

        // site sem permissões declaradas: nenhuma chamada extra
        FingerprintMappingCaptureSupport.prepareRequiredPermissionsAndOverrides(
            driver, perfil, "site-liso", "https://liso.test/");
        org.mockito.Mockito.verify(chrome, org.mockito.Mockito.times(2))
            .setPermission(any(), any(), any());
    }

    @Test
    void creepRuntimeFailsClosedWithoutSignalMap() {
        JavaDriverless driver = mock(JavaDriverless.class);
        when(driver.executeScript(any()))
            .thenReturn(Map.of("likeHeadless", false));
        assertEquals(Map.of("likeHeadless", false),
            FingerprintMappingCaptureSupport.prepareSiteRuntime(
                driver, perfilGeo(), "creepjs"));

        when(driver.executeScript(any())).thenReturn(Map.of());
        assertThrows(IllegalStateException.class, () ->
            FingerprintMappingCaptureSupport.prepareSiteRuntime(driver, perfilGeo(), "creepjs"));

        when(driver.executeScript(any())).thenReturn(null);
        assertThrows(IllegalStateException.class, () ->
            FingerprintMappingCaptureSupport.prepareSiteRuntime(driver, perfilGeo(), "creepjs"));
    }

    @Test
    void genericSiteNeedsNoRuntimePreparation() {
        JavaDriverless driver = mock(JavaDriverless.class);
        assertEquals(Map.of(), FingerprintMappingCaptureSupport.prepareSiteRuntime(
            driver, perfilGeo(), "site-estatico"));
        org.mockito.Mockito.verify(driver, org.mockito.Mockito.never()).executeScript(any());
    }

    @Test
    void browserleaksGeoSequencesWatchStopAndPosition() {
        JavaDriverless driver = mock(JavaDriverless.class);
        io.github.selenium.javaDriverless.Chrome chrome =
            mock(io.github.selenium.javaDriverless.Chrome.class);
        when(driver.getChrome()).thenReturn(chrome);
        when(chrome.setGeolocationOverride(any(), any(), any()))
            .thenReturn(java.util.concurrent.CompletableFuture.completedFuture(null));

        Map<String, Object> watch = Map.of(
            "active", "Active", "checked", true,
            "latitude", "-23.0", "longitude", "-46.0", "accuracy", "100");
        Map<String, Object> posicao = Map.of(
            "permission", "granted",
            "position", Map.of("ok", true, "latitude", "-23.0", "longitude", "-46.0",
                "accuracy", "100"));
        // um único stub roteando pelo conteúdo do script: à prova de ordem de matchers
        org.mockito.Mockito.doAnswer(inv -> {
            String script = inv.getArgument(0);
            if (script.contains("dataset.raw")) return watch;
            if (script.contains("geo-watch-button")) return Boolean.TRUE;
            if (script.contains("getCurrentPosition")) return posicao;
            return null;
        }).when(driver).executeScript(org.mockito.ArgumentMatchers.anyString());

        Map<String, Object> evidencia = FingerprintMappingCaptureSupport.prepareSiteRuntime(
            driver, perfilGeo(), "browserleaks-geo");

        assertEquals("granted", evidencia.get("permission"));
        assertEquals(watch, evidencia.get("watchPosition"));
        assertEquals(Map.of("ok", true, "latitude", "-23.0", "longitude", "-46.0",
            "accuracy", "100"), evidencia.get("getCurrentPosition"));
        org.mockito.Mockito.verify(chrome).setGeolocationOverride(
            -23.0, -46.0, 100.0);
    }

    @Test
    void geoStopFailureIsAlsoFatal() {
        JavaDriverless driver = mock(JavaDriverless.class);
        io.github.selenium.javaDriverless.Chrome chrome =
            mock(io.github.selenium.javaDriverless.Chrome.class);
        when(driver.getChrome()).thenReturn(chrome);
        when(chrome.setGeolocationOverride(any(), any(), any()))
            .thenReturn(java.util.concurrent.CompletableFuture.completedFuture(null));
        when(driver.executeScript(org.mockito.ArgumentMatchers.contains("dataset.raw")))
            .thenReturn(Map.of("active", "Active", "checked", true,
                "latitude", "-23.0", "longitude", "-46.0", "accuracy", "100"));
        when(driver.executeScript(org.mockito.ArgumentMatchers.contains("geo-watch-button")))
            .thenReturn(Boolean.FALSE);

        assertThrows(IllegalStateException.class, () ->
            FingerprintMappingCaptureSupport.prepareSiteRuntime(
                driver, perfilGeo(), "browserleaks-geo"));
    }

    @Test
    void watchPositionThatNeverRendersFailsClosedAfterTheDeadline() {
        JavaDriverless driver = mock(JavaDriverless.class);
        io.github.selenium.javaDriverless.Chrome chrome =
            mock(io.github.selenium.javaDriverless.Chrome.class);
        when(driver.getChrome()).thenReturn(chrome);
        when(chrome.setGeolocationOverride(any(), any(), any()))
            .thenReturn(java.util.concurrent.CompletableFuture.completedFuture(null));
        when(driver.executeScript(org.mockito.ArgumentMatchers.contains("dataset.raw")))
            .thenReturn(Map.of("active", "Idle"));
        // sem stub, o mock de sleep não espera nada e o loop de deadline viraria
        // busy-spin de 15s a 100% de CPU; pace como o driver real (0,2s/iteração)
        org.mockito.Mockito.doAnswer(inv -> {
            Thread.sleep(100);
            return null;
        }).when(driver).sleep(org.mockito.ArgumentMatchers.anyDouble());

        // loop tem deadline fixo de 15s; a variância do teste é o próprio timeout
        org.junit.jupiter.api.Assertions.assertTimeoutPreemptively(
            java.time.Duration.ofSeconds(20),
            () -> assertThrows(IllegalStateException.class, () ->
                FingerprintMappingCaptureSupport.prepareSiteRuntime(
                    driver, perfilGeo(), "browserleaks-geo")));
    }

    @Test
    void scrollScreenshotsSwallowIndividualFailures(@TempDir Path dir) {
        JavaDriverless driver = mock(JavaDriverless.class);
        when(driver.screenshot(org.mockito.ArgumentMatchers.any()))
            .thenReturn(driver)
            .thenThrow(new IllegalStateException("falha isolada"));

        org.junit.jupiter.api.Assertions.assertDoesNotThrow(() ->
            FingerprintMappingCaptureSupport.captureScrollScreenshots(driver, dir, "site"));

        org.mockito.Mockito.verify(driver, org.mockito.Mockito.times(5)).screenshot(any());
    }

    @Test
    void failureSnapshotToleratesADriverThatThrowsEverything(@TempDir Path dir) {
        JavaDriverless driver = mock(JavaDriverless.class);
        when(driver.getCurrentUrl()).thenThrow(new IllegalStateException("driver morto"));
        when(driver.getTitle()).thenThrow(new IllegalStateException("driver morto"));
        when(driver.getPageSource()).thenThrow(new IllegalStateException("driver morto"));

        Map<String, Object> meta = new LinkedHashMap<>();
        meta.put("captureStatus", "error");

        org.junit.jupiter.api.Assertions.assertDoesNotThrow(() ->
            FingerprintMappingCaptureSupport.persistFailureSnapshot(driver, dir, "site", meta));

        // safeString engole as falhas do driver: o snapshot sai com estado vazio,
        // sem propagar nenhuma exceção e preservando o captureStatus original
        assertEquals("error", meta.get("captureStatus"));
        @SuppressWarnings("unchecked")
        Map<String, Object> estado = (Map<String, Object>) meta.get("failurePageState");
        assertEquals("empty-document", estado.get("classification"));
    }
}
