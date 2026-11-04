package io.github.selenium.javaDriverless.fpmapping;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FingerprintMappingCatalogContractTest {
    private static final ObjectMapper JSON = new ObjectMapper();

    @TempDir
    Path tempDir;

    @Test
    void catalogRejectsAbsoluteAndTraversalIds() throws Exception {
        for (String id : List.of(tempDir.resolve("outside").toString(), "../escape")) {
            Path catalog = tempDir.resolve("catalog-" + Math.abs(id.hashCode()) + ".json");
            JSON.writeValue(catalog.toFile(), Map.of("tests", List.of(Map.of(
                    "id", id, "url", "https://example.com/"))));

            assertThrows(IllegalArgumentException.class,
                    () -> FingerprintMappingCatalog.loadSites(catalog, Set.of()));
        }
    }

    @Test
    void siteDirectoryKeepsCaptureInsideArtifactRoot() {
        Path root = tempDir.resolve("captures");
        assertThrows(IllegalArgumentException.class,
                () -> FingerprintMappingCatalog.siteDirectory(root, "../x"));
        assertThrows(IllegalArgumentException.class,
                () -> FingerprintMappingCatalog.siteDirectory(root, "/abs"));
        assertTrue(FingerprintMappingCatalog.siteDirectory(root, "pixelscan").startsWith(root));
    }

    @Test
    void siteDirectoryRejectsSymbolicLinkComponents() throws Exception {
        Path root = tempDir.resolve("captures");
        Path outside = tempDir.resolve("outside");
        Files.createDirectories(root);
        Files.createDirectories(outside);
        Files.createSymbolicLink(root.resolve("linked"), outside);

        // Sessao como symlink direto, como raiz e como componente intermediario.
        assertThrows(IllegalArgumentException.class,
                () -> FingerprintMappingCatalog.siteDirectory(root, "linked"));
        assertThrows(IllegalArgumentException.class,
                () -> FingerprintMappingCatalog.siteDirectory(root, "linked/site"));
        assertThrows(IllegalArgumentException.class,
                () -> FingerprintMappingCatalog.siteDirectory(root.resolve("linked"), "site"));
        assertTrue(FingerprintMappingCatalog.siteDirectory(root, "pixelscan").startsWith(root));
    }

    @Test
    @SuppressWarnings("unchecked")
    void catalogContractsAndFallbacksDescribeTheSameUniqueSites() throws Exception {
        Map<String, Object> catalog = JSON.readValue(
                Path.of("docs", "FINGERPRINT_TEST_CATALOG.json").toFile(), Map.class);
        List<Map<String, Object>> tests = (List<Map<String, Object>>) catalog.get("tests");
        Set<String> catalogIds = new HashSet<>();
        Set<String> urls = new HashSet<>();
        for (Map<String, Object> site : tests) {
            assertTrue(catalogIds.add(site.get("id").toString()), "duplicate catalog id: " + site.get("id"));
            assertTrue(urls.add(site.get("url").toString()), "duplicate catalog URL: " + site.get("url"));
        }

        Map<String, Object> contracts = JSON.readValue(
                Path.of("docs", "FINGERPRINT_SITE_CONTRACTS.json").toFile(), Map.class);
        Map<String, Object> sites = (Map<String, Object>) contracts.get("sites");
        assertEquals(28, catalogIds.size());
        assertEquals(catalogIds, sites.keySet());

        Set<String> fallbackIds = new HashSet<>();
        for (Map<String, String> site : FingerprintMappingCatalog.loadSites(
                Path.of("does-not-exist.json"), Set.of())) {
            assertTrue(fallbackIds.add(site.get("id")), "duplicate Java fallback id: " + site.get("id"));
        }
        assertEquals(catalogIds, fallbackIds);
    }

    @Test
    void everyContractPinsNavigationAndHasStructuralFields() {
        for (Map<String, String> site : FingerprintMappingCatalog.loadSites(
                Path.of("docs", "FINGERPRINT_TEST_CATALOG.json"), Set.of())) {
            FingerprintSiteContracts.Site contract = FingerprintSiteContracts.site(site.get("id"));
            assertFalse(contract.allowedOrigins.isEmpty(), site.get("id") + " allowedOrigins");
            assertFalse(contract.titleTerms.isEmpty(), site.get("id") + " titleTerms");
            assertFalse(contract.requiredDom.isEmpty(), site.get("id") + " requiredDom");
            contract.requiredDom.forEach(field -> {
                assertFalse(field.id.isBlank(), site.get("id") + " blank field id");
                assertTrue(!field.terms.isEmpty()
                                || (!"terms".equals(field.matchPolicy) && !field.selectors.isEmpty()),
                        site.get("id") + "/" + field.id
                                + " must have search terms or an explicit value policy and selector");
            });
        }
    }

    @Test
    void catalogPreservesRequiredActionMetadataForTheJavaRunners() {
        Map<String, String> site = FingerprintMappingCatalog.loadSites(
                        Path.of("docs", "FINGERPRINT_TEST_CATALOG.json"), Set.of("coveryourtracks"))
                .getFirst();

        assertEquals("click", site.get("runType"));
        assertEquals("#kcarterlink", site.get("runSelector"));
        assertEquals("TEST YOUR BROWSER", site.get("runLabel"));

        Map<String, String> timezone = FingerprintMappingCatalog.loadSites(
                        Path.of("docs", "FINGERPRINT_TEST_CATALOG.json"), Set.of("trustmyip-timezone"))
                .getFirst();
        assertEquals("click", timezone.get("runType"));
        assertEquals("button[onclick^=\"runTimezoneTest\"]", timezone.get("runSelector"));
        assertEquals("Run Timezone Dissonance Test", timezone.get("runLabel"));
    }

    @Test
    void browserDomCaptureExcludesGlobalAndExecutableNodes() {
        String script = FingerprintMappingDom.captureScript("browserleaks-webgl");
        assertTrue(script.contains("'HTML','BODY','SCRIPT','STYLE','NOSCRIPT','TEMPLATE'"));
        assertTrue(script.contains("rect.width > 0 && rect.height > 0"));
        assertFalse(script.contains("document.body.innerText.includes"));
    }

    @Test
    void stableSelectorsObservedInLiveAuditRemainPinned() {
        assertEquals(List.of("#hero-status", "h1", "[role=status]"),
                field("iphey", "iphey-verdict").selectors);
        assertEquals(List.of("tr:has(#webdriver-result)", "tr:has(#advanced-webdriver-result)"),
                field("sannysoft", "sannysoft-webdriver").selectors);
        assertEquals(List.of("#browser-timezone"),
                field("antidetect-timezone", "antidetect-timezone-browser").selectors);
        assertEquals(List.of("#ip-timezone"),
                field("antidetect-timezone", "antidetect-timezone-ip-location").selectors);
        assertEquals(List.of("#timezone-status"),
                field("antidetect-timezone", "antidetect-timezone-status").selectors);
        assertEquals(List.of("#latitude"), field("browserleaks-geo", "browserleaks-geo-latitude").selectors);
        assertEquals("numeric", field("browserleaks-geo", "browserleaks-geo-latitude").matchPolicy);
        assertEquals(List.of("#longitude"), field("browserleaks-geo", "browserleaks-geo-longitude").selectors);
        assertEquals(List.of("#accuracy"), field("browserleaks-geo", "browserleaks-geo-accuracy").selectors);
        assertEquals(List.of("#canvas-hash"),
                field("browserleaks-canvas", "browserleaks-canvas-fingerprint").selectors);
        assertEquals("hex32", field("browserleaks-canvas", "browserleaks-canvas-fingerprint").matchPolicy);
        assertEquals(List.of("#fonts-metrics-report"),
                field("browserleaks-fonts", "browserleaks-fonts-report").selectors);
        assertEquals("fontMetricsReport",
                field("browserleaks-fonts", "browserleaks-fonts-report").matchPolicy);
        assertEquals(List.of("#fonts-metrics-hash"),
                field("browserleaks-fonts", "browserleaks-fonts-metrics-hash").selectors);
        assertEquals(List.of("#fonts-glyphs-hash"),
                field("browserleaks-fonts", "browserleaks-fonts-glyphs-hash").selectors);
        assertEquals(List.of(".box:has(#rtc_placeholder) > .title"),
                field("ipleak", "ipleak-webrtc").selectors);
        assertEquals(List.of("#dnsdetection_title"),
                field("ipleak", "ipleak-dns").selectors);
        assertEquals("nonEmpty", field("ipleak", "ipleak-dns").matchPolicy);
        assertEquals(List.of(".browser-details-table .row:has(> .ip.name)"),
                field("whatsmybrowser", "whatsmybrowser-ip").selectors);
        assertEquals(List.of(".browser-details > .wrap > h2.header"),
                field("whatsmybrowser", "whatsmybrowser-details").selectors);
        assertEquals(List.of(".browser-details .user-agent-cont"),
                field("whatsmybrowser", "whatsmybrowser-ua").selectors);
        assertEquals(List.of("#res-hash"), field("trustmyip-canvas", "trustmyip-canvas-hash").selectors);
        assertEquals("canvasHash", field("trustmyip-canvas", "trustmyip-canvas-hash").matchPolicy);
        assertEquals(List.of("#item-system-font .fp-item__name"),
                field("printleaks", "printleaks-font").selectors);
        assertEquals(List.of("#item-browser-webRTC .fp-item__name"),
                field("printleaks", "printleaks-webrtc").selectors);
    }

    @Test
    void creepJsLiveHeadingsAndNonHeadingValuesRemainAddressable() {
        String script = FingerprintMappingDom.captureScript("creepjs");
        assertTrue(script.contains("h1,h2,h3,h4,h5,strong,th"),
                "CreepJS renders its section headings as visible <strong> elements");
        assertEquals(List.of(".fingerprint-header .ellipsis-all"),
                field("creepjs", "creepjs-fp-id").selectors);
        assertEquals(List.of(".block-text-large[title*='StorageManager.estimate']"),
                field("creepjs", "creepjs-storage").selectors);
    }

    @Test
    void webglReportPinsTheActiveWebglOneTabAndOnlyComparesConfiguredGpuValues() {
        FingerprintSiteContracts.RequiredDom webglOne = field("webgl-report", "webgl-report-webgl1");
        assertEquals(List.of(".webglVersionTabs a.active"), webglOne.selectors);
        assertEquals(List.of("webgl 1"), webglOne.terms);
        assertEquals("equalsIfConfigured", expected("webgl-report", "webgl-report.vendor").policy);
        assertEquals("equalsIfConfigured", expected("webgl-report", "webgl-report.renderer").policy);
    }

    @Test
    void pixelScanUsesConcreteLocationValuesInsteadOfTheRemovedMarketingWrapper() {
        Set<String> fields = FingerprintSiteContracts.site("pixelscan").requiredDom.stream()
                .map(value -> value.id)
                .collect(java.util.stream.Collectors.toSet());

        assertFalse(fields.contains("pixelscan-location-section"));
        assertTrue(fields.containsAll(Set.of(
                "pixelscan-ip-address", "pixelscan-country", "pixelscan-city",
                "pixelscan-isp", "pixelscan-latitude", "pixelscan-longitude")));
    }

    private static FingerprintSiteContracts.RequiredDom field(String siteId, String fieldId) {
        return FingerprintSiteContracts.site(siteId).requiredDom.stream()
                .filter(field -> fieldId.equals(field.id)).findFirst().orElseThrow();
    }

    private static FingerprintSiteContracts.ExpectedValue expected(String siteId, String key) {
        return FingerprintSiteContracts.site(siteId).expectedValues.stream()
                .filter(value -> key.equals(value.key)).findFirst().orElseThrow();
    }
}
