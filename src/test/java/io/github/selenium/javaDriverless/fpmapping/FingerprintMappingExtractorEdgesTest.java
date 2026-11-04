package io.github.selenium.javaDriverless.fpmapping;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

/**
 * Variações de parse do extrator: tabela de exclusão de IPs privados/reservados
 * do DNS do BrowserLeaks, contagem relatada vs observada e formatos de corpo.
 */
class FingerprintMappingExtractorEdgesTest {

    @SuppressWarnings("unchecked")
    private static Map<String, Object> dns(String serversSection) {
        String body = "Your IP Address\n203.0.113.20\nISP Home\nSão Paulo, BR\n\n"
            + "DNS Leak Test\nTest Results: Found 3 Servers\n\n"
            + "Your DNS Servers\n" + serversSection
            + "Leave a Comment";
        return FingerprintMappingPixelScanExtractor.extract("browserleaks-dns", body);
    }

    @Test
    void publicIpsAreKeptAndPrivateRangesAreExcluded() {
        Map<String, Object> out = dns(String.join("\n",
            "8.8.8.8\tGoogle\tMountain View, US",
            "1.1.1.1\tCloudflare\tSydney, AU",
            "2606:4700:4700::1111\tCloudflare\tGlobal",
            // faixas privadas/reservadas: nenhuma pode virar servidor observado
            "10.0.0.1\tPrivado\tLAN",
            "192.168.0.1\tPrivado\tLAN",
            "172.16.0.1\tPrivado\tLAN",
            "127.0.0.1\tLoopback\tLocal",
            "169.254.1.1\tLinkLocal\tLocal",
            "100.64.0.1\tCGNAT\tISP",
            "0.0.0.0\tInválido\tN/D",
            "224.0.0.1\tMulticast\tN/D",
            "999.1.1.1\tForaDeFaixa\tN/D",
            "fe80::1%eth0\tComZona\tN/D",
            "sem-ip\tHostname\tN/D"));

        List<Map<String, String>> servers = (List<Map<String, String>>) out.get("servers");
        assertEquals(3, servers.size(), "" + servers);
        assertEquals("8.8.8.8", servers.get(0).get("ip"));
        assertEquals("2606:4700:4700::1111", servers.get(2).get("ip"));
        assertEquals("matched", out.get("countConsistency"));
        assertEquals("3", out.get("reportedServerCount"));
        assertEquals("3", out.get("observedServerCount"));
    }

    @Test
    void countMismatchIsFlaggedWhenReportedAndObservedDiverge() {
        Map<String, Object> out = dns("9.9.9.9\tQuad9\tZurich, CH");

        assertEquals("1", out.get("observedServerCount"));
        assertEquals("3", out.get("reportedServerCount"));
        assertEquals("mismatch", out.get("countConsistency"));
    }

    @Test
    void duplicateServersByIpAreCollapsed() {
        Map<String, Object> out = dns(String.join("\n",
            "8.8.4.4\tGoogle\tMountain View, US",
            "8.8.4.4\tGoogle\tMountain View, US"));

        List<Map<String, String>> servers = (List<Map<String, String>>) out.get("servers");
        assertEquals(1, servers.size());
    }

    @Test
    void linesWithBlankColumnsAreIgnored() {
        Map<String, Object> out = dns(String.join("\n",
            "8.8.8.8\tGoogle\t",
            "9.9.9.9\t\tZurich",
            "1.0.0.1\tCloudflare\tGlobal"));

        List<Map<String, String>> servers = (List<Map<String, String>>) out.get("servers");
        assertEquals(1, servers.size());
        assertEquals("1.0.0.1", servers.get(0).get("ip"));
    }

    @Test
    void missingDnsSectionYieldsEmptyServers() {
        String body = "Your IP Address\n203.0.113.20\nISP Home\nSão Paulo, BR\n"
            + "DNS Leak Test\nTest Results: Found 0 Servers\n";
        Map<String, Object> out = FingerprintMappingPixelScanExtractor.extract("browserleaks-dns", body);

        assertEquals("0", out.get("observedServerCount"));
        assertEquals(List.of(), out.get("servers"));
        // reported 0 -> nem matched nem comparável
        assertEquals("mismatch", out.get("countConsistency"));
    }

    @Test
    void unknownSiteIdYieldsEmptyMap() {
        Map<String, Object> out = FingerprintMappingPixelScanExtractor.extract("site-inexistente", "corpo");

        assertEquals(Map.of(), out);
    }
}
