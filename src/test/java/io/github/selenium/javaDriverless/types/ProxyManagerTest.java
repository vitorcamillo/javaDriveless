package io.github.selenium.javaDriverless.types;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Rotação sequencial/aleatória de proxies + carga de arquivo com linhas inválidas. */
class ProxyManagerTest {

    @Test
    void loadLinesSkipsCommentsBlanksAndInvalidEntries(@TempDir Path tempDir) throws Exception {
        Path file = tempDir.resolve("proxies.txt");
        Files.writeString(file, String.join("\n",
            "# comentário",
            "",
            "   ",
            "10.0.0.1:3128",
            "linha-sem-porta-valida!",
            "10.0.0.2:8080"));

        ProxyManager manager = new ProxyManager();
        manager.loadFromFile(file.toString());

        assertEquals(2, manager.getProxyCount());
        assertTrue(manager.hasProxies());
    }

    @Test
    void loadLinesResetsStateBetweenLoads() {
        ProxyManager manager = new ProxyManager();
        List<String> warnings = new ArrayList<>();

        manager.loadLines(List.of("10.0.0.1:3128"), ProxyConfig.ProxyType.HTTP, warnings::add);
        assertEquals(1, manager.getProxyCount());

        manager.getNextProxy(); // avança índice
        manager.loadLines(List.of("10.0.0.2:8080"), ProxyConfig.ProxyType.HTTP, warnings::add);

        assertEquals(1, manager.getProxyCount());
        assertEquals("10.0.0.2", manager.getCurrentProxy().getHost());
    }

    @Test
    void invalidLineProducesOperationalWarningWithoutSecrets() {
        ProxyManager manager = new ProxyManager();
        List<String> warnings = new ArrayList<>();

        manager.loadLines(List.of("sem-porta"), ProxyConfig.ProxyType.HTTP, warnings::add);

        assertEquals(1, warnings.size());
        assertTrue(warnings.get(0).contains("linha 1"));
        assertEquals(0, manager.getProxyCount());
    }

    @Test
    void getNextProxyRotatesSequentiallyAndWrapsAround() {
        ProxyManager manager = new ProxyManager();
        manager.addProxy(new ProxyConfig("10.0.0.1", 3128, ProxyConfig.ProxyType.HTTP));
        manager.addProxy(new ProxyConfig("10.0.0.2", 8080, ProxyConfig.ProxyType.HTTP));

        assertEquals("10.0.0.1", manager.getNextProxy().getHost());
        assertEquals("10.0.0.2", manager.getNextProxy().getHost());
        assertEquals("10.0.0.1", manager.getNextProxy().getHost()); // wrap
    }

    @Test
    void emptyManagerReturnsNullAndReportsEmpty() {
        ProxyManager manager = new ProxyManager();

        assertNull(manager.getNextProxy());
        assertNull(manager.getRandomProxy());
        assertNull(manager.getCurrentProxy());
        assertFalse(manager.hasProxies());
        assertTrue(manager.getAllProxies().isEmpty());
    }

    @Test
    void getRandomProxyAlwaysPicksFromThePool() {
        ProxyManager manager = new ProxyManager();
        manager.addProxy(new ProxyConfig("10.0.0.1", 3128, ProxyConfig.ProxyType.HTTP));
        manager.addProxy(new ProxyConfig("10.0.0.2", 8080, ProxyConfig.ProxyType.HTTP));

        for (int i = 0; i < 20; i++) {
            String host = manager.getRandomProxy().getHost();
            assertTrue(host.equals("10.0.0.1") || host.equals("10.0.0.2"), host);
        }
    }

    @Test
    void setCurrentIndexBoundsAreAppliedToRotation() {
        ProxyManager manager = new ProxyManager();
        manager.addProxy(new ProxyConfig("10.0.0.1", 3128, ProxyConfig.ProxyType.HTTP));
        manager.addProxy(new ProxyConfig("10.0.0.2", 8080, ProxyConfig.ProxyType.HTTP));

        manager.setCurrentIndex(1);
        assertEquals("10.0.0.2", manager.getCurrentProxy().getHost());

        manager.setCurrentIndex(0);
        assertEquals("10.0.0.1", manager.getCurrentProxy().getHost());
    }

    @Test
    void removeClearAndShuffleKeepManagerConsistent() {
        ProxyManager manager = new ProxyManager();
        ProxyConfig a = new ProxyConfig("10.0.0.1", 3128, ProxyConfig.ProxyType.HTTP);
        ProxyConfig b = new ProxyConfig("10.0.0.2", 8080, ProxyConfig.ProxyType.HTTP);
        manager.addProxy(a);
        manager.addProxy(b);

        assertTrue(manager.removeProxy(a));
        assertEquals(1, manager.getProxyCount());

        manager.shuffle();
        assertEquals(1, manager.getProxyCount());

        manager.clear();
        assertEquals(0, manager.getProxyCount());
        assertFalse(manager.removeProxy(b));
    }

    @Test
    void setCurrentIndexOutsideRangeIsIgnored() {
        ProxyManager manager = new ProxyManager();
        manager.addProxy(new ProxyConfig("10.0.0.1", 3128, ProxyConfig.ProxyType.HTTP));
        manager.addProxy(new ProxyConfig("10.0.0.2", 8080, ProxyConfig.ProxyType.HTTP));

        manager.setCurrentIndex(1);
        manager.setCurrentIndex(99);
        assertEquals("10.0.0.2", manager.getCurrentProxy().getHost());

        manager.setCurrentIndex(-5);
        assertEquals("10.0.0.2", manager.getCurrentProxy().getHost());
    }

    @Test
    void getAllProxiesReturnsTheLoadedContent() {
        ProxyManager manager = new ProxyManager();
        manager.addProxy(new ProxyConfig("10.0.0.1", 3128, ProxyConfig.ProxyType.HTTP));
        manager.addProxy(new ProxyConfig("10.0.0.2", 8080, ProxyConfig.ProxyType.HTTP));

        var all = manager.getAllProxies();
        assertEquals(2, all.size());
        assertEquals("10.0.0.1", all.get(0).getHost());
        assertEquals("10.0.0.2", all.get(1).getHost());
    }

    @Test
    void statisticsReportExactCountsByTypeAndAuth() {
        ProxyManager manager = new ProxyManager();
        assertEquals("Nenhum proxy carregado.", manager.getStatistics());

        manager.addProxy(new ProxyConfig("10.0.0.1", 3128, ProxyConfig.ProxyType.HTTP));
        manager.addProxy(new ProxyConfig("10.0.0.2", 8080, ProxyConfig.ProxyType.HTTPS));
        manager.addProxy(new ProxyConfig("10.0.0.3", 1080, ProxyConfig.ProxyType.SOCKS5, "u", "p"));
        manager.getNextProxy(); // currentIndex avança para 1

        assertEquals("Proxies: 3 total | HTTP: 1 | HTTPS: 1 | SOCKS5: 1 | Autenticados: 1 | Atual: 1",
            manager.getStatistics());
    }
}
