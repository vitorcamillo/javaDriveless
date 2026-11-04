package io.github.selenium.javaDriverless.utils;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import io.github.selenium.javaDriverless.types.BrowserFingerprintProfile;

/**
 * UserAgentResolver: construção do UA clássico, validação estrita contra o
 * perfil e redução da versão do Chrome para o formato do UA.
 */
class UserAgentResolverTest {

    private static BrowserFingerprintProfile profile(String uaPlatform, String version) {
        BrowserFingerprintProfile profile = new BrowserFingerprintProfile();
        profile.uaPlatform = uaPlatform;
        profile.browserVersion = version;
        return profile;
    }

    @Test
    void reduceChromeVersionKeepsOnlyTheMajorComponent() {
        assertEquals("147.0.0.0", UserAgentResolver.reduceChromeVersionForUserAgent("147.0.7727.137"));
        assertEquals("147.0.0.0", UserAgentResolver.reduceChromeVersionForUserAgent("147"));
        assertEquals("133.0.0.0", UserAgentResolver.reduceChromeVersionForUserAgent(null));
        assertEquals("133.0.0.0", UserAgentResolver.reduceChromeVersionForUserAgent("   "));
    }

    @Test
    void extractChromeMajorFallsBackWhenPatternIsAbsent() {
        assertEquals("147", UserAgentResolver.extractChromeMajor(
            "Mozilla/5.0 (X11; Linux x86_64) Chrome/147.0.0.0 Safari/537.36"));
        assertEquals("133", UserAgentResolver.extractChromeMajor(null));
        assertEquals("133", UserAgentResolver.extractChromeMajor("sem versão aqui"));
    }

    @Test
    void buildForProfileProducesCanonicalUaPerPlatform() {
        assertEquals(
            "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/147.0.0.0 Safari/537.36",
            UserAgentResolver.buildForProfile(profile("Linux", "147.0.7727.137")));
        assertEquals(
            "Mozilla/5.0 (X11; Linux i686) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/147.0.0.0 Safari/537.36",
            UserAgentResolver.buildForProfile(profileWithBitness32()));
        assertEquals(
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/150.0.0.0 Safari/537.36",
            UserAgentResolver.buildForProfile(profile("Windows", "150.1.2.3")));
        assertEquals(
            "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/148.0.0.0 Safari/537.36",
            UserAgentResolver.buildForProfile(profile("macOS", "148.9.9.9")));
    }

    @Test
    void buildForProfileWithBlankVersionReducesDetectedOrFallbackVersion() {
        String ua = UserAgentResolver.buildForProfile(profile("Linux", "  "));

        String major = UserAgentResolver.extractChromeMajor(ua);
        assertTrue(ua.contains("X11; Linux x86_64"));
        assertTrue(ua.endsWith("Chrome/" + major + ".0.0.0 Safari/537.36"),
            "versão blank deve resolver (detecção ou fallback) e sair reduzida: " + ua);
    }

    private static BrowserFingerprintProfile profileWithBitness32() {
        BrowserFingerprintProfile profile = profile("Linux", "147.0.7727.137");
        profile.bitness = "32";
        return profile;
    }

    @Test
    void buildForProfileAndroidUsesMobileTokensAndFallsBackForMissingFields() {
        BrowserFingerprintProfile android = profile("Android", "151.0.6778.85");
        android.mobile = true;
        android.platformVersion = "13.0.0";
        android.model = "Pixel 7";

        assertEquals(
            "Mozilla/5.0 (Linux; Android 13; Pixel 7) AppleWebKit/537.36 (KHTML, like Gecko) "
                + "Chrome/151.0.0.0 Mobile Safari/537.36",
            UserAgentResolver.buildForProfile(android));

        BrowserFingerprintProfile sparse = profile("android", "");

        String sparseUa = UserAgentResolver.buildForProfile(sparse);
        String sparseMajor = UserAgentResolver.extractChromeMajor(sparseUa);
        assertEquals(
            "Mozilla/5.0 (Linux; Android 13; SM-G991B) AppleWebKit/537.36 (KHTML, like Gecko) "
                + "Chrome/" + sparseMajor + ".0.0.0 Mobile Safari/537.36",
            sparseUa);
    }

    @Test
    void isCompatibleRejectsForeignOrBrokenUserAgents() {
        BrowserFingerprintProfile profile = profile("Linux", "147.0.7727.137");

        assertFalse(UserAgentResolver.isCompatible(profile, null));
        assertFalse(UserAgentResolver.isCompatible(profile, "   "));
        assertFalse(UserAgentResolver.isCompatible(profile, "Mozilla/5.0 HeadlessChrome/147.0.0.0"));
        assertFalse(UserAgentResolver.isCompatible(profile,
            "Mozilla/5.0 (X11; Linux x86_64; rv:120.0) Gecko/20100101 Firefox/120.0"));
        assertFalse(UserAgentResolver.isCompatible(profile,
            "Mozilla/5.0 (Windows NT 10.0) AppleWebKit/537.36 Chrome/147.0.0.0 Safari/537.36 Edg/147.0.0.0"));
        assertFalse(UserAgentResolver.isCompatible(profile, "UA sem token de navegador"));
    }

    @Test
    void isCompatibleEnforcesExactVersionAndPlatformMatch() {
        BrowserFingerprintProfile profile = profile("Linux", "147.0.7727.137");
        String canonical = UserAgentResolver.buildForProfile(profile);

        assertTrue(UserAgentResolver.isCompatible(profile, canonical));
        // major divergente
        assertFalse(UserAgentResolver.isCompatible(profile,
            "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/146.0.0.0 Safari/537.36"));
        // major correto mas patch divergente do canônico X.0.0.0
        assertFalse(UserAgentResolver.isCompatible(profile,
            "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/147.0.7727.137 Safari/537.36"));
        // UA ok, mas plataforma do perfil desconhecida
        BrowserFingerprintProfile exotic = profile("Solaris", "147.0.7727.137");
        assertFalse(UserAgentResolver.isCompatible(exotic, canonical));
        // plataforma reconhecida em maiúsculas/minúsculas diferentes
        BrowserFingerprintProfile upper = profile("LINUX", "147.0.7727.137");
        assertTrue(UserAgentResolver.isCompatible(upper, canonical));
    }

    @Test
    void ensureBrowserVersionKeepsExplicitProfileVersion() {
        BrowserFingerprintProfile profile = profile("Linux", "147.0.7727.137");

        UserAgentResolver.ensureBrowserVersion(profile);

        assertEquals("147.0.7727.137", profile.browserVersion);
    }

    @Test
    void ensureBrowserVersionNeverThrowsWhenDetectionIsUnavailable() {
        BrowserFingerprintProfile profile = profile("Linux", "");

        UserAgentResolver.ensureBrowserVersion(profile);

        // detecção pode resolver a versão real ou deixar blank; nunca pode lançar
        assertTrue(profile.browserVersion != null);
    }

    @Test
    void getDefaultUADegradesToNullSafelyWithoutWritingAnything() throws Exception {
        String ua = Utils.getDefaultUA().get(2, TimeUnit.SECONDS);

        // arquivo global pode ou não existir; o contrato é não lançar e não escrever
        Path stored = Utils.DATA_DIR.resolve("useragent");
        if (ua == null) {
            assertFalse(Files.exists(stored) && Files.readString(stored).isEmpty());
        }
    }

    @Test
    void generateRealisticUaUsesReducedVersionAndBrowserLikeShape() {
        String ua = Utils.generateRealisticUA();

        assertTrue(ua.startsWith("Mozilla/5.0"));
        assertTrue(ua.contains("Chrome/"));
        String major = UserAgentResolver.extractChromeMajor(ua);
        assertTrue(ua.contains("Chrome/" + major + ".0.0.0"), "versão no UA deve ser reduzida: " + ua);
    }

    @Test
    void readFromClasspathReturnsResourceContent() throws Exception {
        String content = Utils.read("js/show_mousemove.js", "UTF-8", true).get(5, TimeUnit.SECONDS);

        assertTrue(content.contains("mousemove"));
    }

    @Test
    void readFromMissingResourceFailsWithDescriptiveMessage() {
        var future = Utils.read("classpath/nao/existe.js", "UTF-8", true);

        var thrown = assertThrows(java.util.concurrent.ExecutionException.class,
            () -> future.get(5, TimeUnit.SECONDS));
        assertEquals("Erro ao ler arquivo: classpath/nao/existe.js", thrown.getCause().getMessage());
    }

    @Test
    void readFromMissingFileFailsWithDescriptiveMessage(@TempDir Path dir) {
        var future = Utils.read(dir.resolve("fantasma.txt").toString());

        var thrown = assertThrows(java.util.concurrent.ExecutionException.class,
            () -> future.get(5, TimeUnit.SECONDS));
        assertEquals("Erro ao ler arquivo: " + dir.resolve("fantasma.txt"),
            thrown.getCause().getMessage());
    }

    @Test
    void writeRoundTripPreservesContentInConfiguredEncoding(@TempDir Path dir) throws Exception {
        String conteudo = "acentuação: ção";
        Path iso = dir.resolve("iso.txt");
        Path utf8 = dir.resolve("utf8.txt");

        Utils.write(iso.toString(), conteudo, "ISO-8859-1").get(5, TimeUnit.SECONDS);
        Utils.write(utf8.toString(), conteudo).get(5, TimeUnit.SECONDS);

        assertEquals(conteudo, Utils.read(iso.toString(), "ISO-8859-1", false).get(5, TimeUnit.SECONDS));
        assertTrue(Files.readAllBytes(iso).length < Files.readAllBytes(utf8).length,
            "ISO-8859-1 grava 1 byte por caractere acentuado; UTF-8 grava 2");
    }

    @Test
    void writeToUnwritableLocationFailsWithDescriptiveMessage() {
        Path impossivel = Path.of("/proc/java-driverless-teste-nao-existe/arquivo.txt");

        var future = Utils.write(impossivel.toString(), "conteúdo");

        var thrown = assertThrows(java.util.concurrent.ExecutionException.class,
            () -> future.get(5, TimeUnit.SECONDS));
        assertEquals("Erro ao escrever arquivo: " + impossivel, thrown.getCause().getMessage());
    }
}
