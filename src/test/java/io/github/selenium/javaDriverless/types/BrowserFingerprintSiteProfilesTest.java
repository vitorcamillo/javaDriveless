package io.github.selenium.javaDriverless.types;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Set;

import org.junit.jupiter.api.Test;

/**
 * Catálogo de perfis por tipo de site: chave normalizada, fallback para default e
 * determinismo do perfil base (mesma seed -> mesmo resultado).
 */
class BrowserFingerprintSiteProfilesTest {

    @Test
    void knownSiteTypesAreCatalogued() {
        Set<String> sites = BrowserFingerprintSiteProfiles.supportedSites();

        assertTrue(sites.contains("default"));
        assertTrue(sites.contains("kambi"));
        assertTrue(sites.contains("altenar"));
        assertTrue(sites.contains("obg"));
        assertTrue(sites.contains("openengage"));
        assertTrue(sites.contains("softswiss"));
        assertTrue(sites.contains("betano"));
    }

    @Test
    void forSiteBuildsHardenedProfileWithSiteKeyName() {
        BrowserFingerprintProfile profile =
            BrowserFingerprintSiteProfiles.forSite("kambi", "BR", "Windows", "seed-conta-1");

        assertNotNull(profile);
        assertEquals("site-kambi-windows", profile.name);
    }

    @Test
    void nullSiteTypeFallsBackToDefaultKey() {
        BrowserFingerprintProfile profile =
            BrowserFingerprintSiteProfiles.forSite(null, "BR", "Linux", "seed-conta-1");

        assertEquals("site-default-linux", profile.name);
    }

    @Test
    void unknownSiteTypeFallsBackToDefaultTweak() {
        BrowserFingerprintProfile profile =
            BrowserFingerprintSiteProfiles.forSite("nao-existe", "BR", "Windows", "seed-conta-1");

        assertEquals("site-nao-existe-windows", profile.name);
    }

    @Test
    void siteTypeIsCaseInsensitiveForLookup() {
        BrowserFingerprintProfile upper =
            BrowserFingerprintSiteProfiles.forSite("KAMBI", "BR", "Windows", "seed-conta-1");

        assertEquals("site-kambi-windows", upper.name);
    }

    @Test
    void nullOsFallsBackToWindowsDefaultProfile() {
        // Regressão: randomProfile fazia os.toLowerCase() sem guarda e explodia com NPE antes
        // mesmo do ternário defensivo de forSite; agora os=null cai no default (Windows).
        BrowserFingerprintProfile profile =
            BrowserFingerprintSiteProfiles.forSite("default", "BR", null, "seed-conta-1");

        assertEquals("site-default-", profile.name);
        assertEquals("Win32", profile.platform);
    }

    @Test
    void sameSeedProducesSameProfile() {
        BrowserFingerprintProfile a =
            BrowserFingerprintSiteProfiles.forSite("default", "BR", "Windows", "seed-estavel");
        BrowserFingerprintProfile b =
            BrowserFingerprintSiteProfiles.forSite("default", "BR", "Windows", "seed-estavel");

        assertEquals(a.platform, b.platform);
        assertEquals(a.browserVersion, b.browserVersion);
        assertEquals(a.noiseSeed, b.noiseSeed);
        assertEquals(a.languages, b.languages);
    }
}
