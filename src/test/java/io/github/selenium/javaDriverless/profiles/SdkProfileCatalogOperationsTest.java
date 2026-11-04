package io.github.selenium.javaDriverless.profiles;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

/**
 * Operações puras do catálogo de perfis: nome de clone isolado, query,
 * ordenação, patch e validação de entrada.
 */
class SdkProfileCatalogOperationsTest {

    private static SdkProfile profile(String id, String name) {
        SdkProfile profile = new SdkProfile(id, name);
        profile.metadata.folder = "bots";
        profile.metadata.status = "active";
        profile.metadata.tags = new ArrayList<>(List.of("bet", "br"));
        profile.metadata.notes = "nota do perfil";
        profile.metadata.mainWebsite = "https://example.test";
        return profile;
    }

    @Test
    void isolatedSiblingKeepsTheParentDirectory() {
        assertEquals("/tmp/perfis/base-clone-1",
            SdkProfileCatalogOperations.isolatedSibling("/tmp/perfis/base", "clone-1"));
        // raiz relativa sem diretório: só o nome composto
        assertEquals("base-clone-2", SdkProfileCatalogOperations.isolatedSibling("base", "clone-2"));
        // caminho em branco volta intacto (sem sanitização)
        assertEquals("  ", SdkProfileCatalogOperations.isolatedSibling("  ", "clone-3"));
    }

    @Test
    void deterministicDiversityProducesNoiseWithinBoundsAndSameSeedSameValues() {
        SdkProfile primeiro = profile("p1", "P1");
        SdkProfile segundo = profile("p2", "P2");

        SdkProfileCatalogOperations.applyDeterministicDiversity(primeiro, 42L);
        SdkProfileCatalogOperations.applyDeterministicDiversity(segundo, 42L);

        assertEquals(primeiro.fingerprint.canvas.noise, segundo.fingerprint.canvas.noise);
        assertEquals(primeiro.fingerprint.audio.noise, segundo.fingerprint.audio.noise);
        assertEquals(primeiro.fingerprint.clientRects.noise, segundo.fingerprint.clientRects.noise);
        assertTrue(primeiro.fingerprint.canvas.noise >= 0.001 && primeiro.fingerprint.canvas.noise < 0.005);
        assertTrue(primeiro.fingerprint.audio.noise >= 0.001 && primeiro.fingerprint.audio.noise < 0.005);
        assertTrue(primeiro.fingerprint.clientRects.noise >= 0.01
            && primeiro.fingerprint.clientRects.noise < 0.05);

        SdkProfileCatalogOperations.applyDeterministicDiversity(segundo, 43L);
        assertNotEquals(primeiro.fingerprint.canvas.noise, segundo.fingerprint.canvas.noise);
    }

    @Test
    void queryFiltersByFolderStatusPinnedLockedTagsAndFreeText() {
        SdkProfile p = profile("bot-1", "Bot Principal");

        SdkProfileListQuery folderErrada = new SdkProfileListQuery();
        folderErrada.folder = "outro";
        assertFalse(SdkProfileCatalogOperations.matchesQuery(p, folderErrada));

        SdkProfileListQuery statusErrado = new SdkProfileListQuery();
        statusErrado.status = "trash";
        assertFalse(SdkProfileCatalogOperations.matchesQuery(p, statusErrado));

        SdkProfileListQuery pinnedErrado = new SdkProfileListQuery();
        pinnedErrado.pinned = true;
        assertFalse(SdkProfileCatalogOperations.matchesQuery(p, pinnedErrado));

        SdkProfileListQuery lockedErrado = new SdkProfileListQuery();
        lockedErrado.locked = true;
        assertFalse(SdkProfileCatalogOperations.matchesQuery(p, lockedErrado));

        SdkProfileListQuery tagAusente = new SdkProfileListQuery();
        tagAusente.tags.add("casino");
        assertFalse(SdkProfileCatalogOperations.matchesQuery(p, tagAusente));

        SdkProfileListQuery textoPresente = new SdkProfileListQuery();
        textoPresente.query = "PRINCIPAL";
        assertTrue(SdkProfileCatalogOperations.matchesQuery(p, textoPresente));

        SdkProfileListQuery textoAusente = new SdkProfileListQuery();
        textoAusente.query = "poker";
        assertFalse(SdkProfileCatalogOperations.matchesQuery(p, textoAusente));

        SdkProfileListQuery casando = new SdkProfileListQuery();
        casando.folder = "BOTS";
        casando.status = "ACTIVE";
        casando.pinned = false;
        casando.locked = false;
        casando.tags.add("BET");
        assertTrue(SdkProfileCatalogOperations.matchesQuery(p, casando));

        SdkProfileListQuery vazia = new SdkProfileListQuery();
        assertTrue(SdkProfileCatalogOperations.matchesQuery(p, vazia));
    }

    @Test
    void sortingSupportsNameDatesFolderAndIdWithNullSafety() {
        SdkProfile alpha = profile("z-perfil", "Alpha");
        SdkProfile beta = profile("a-perfil", "beta");
        beta.metadata.createdAt = "2026-01-02T00:00:00Z";
        alpha.metadata.createdAt = "2026-01-01T00:00:00Z";
        beta.metadata.updatedAt = "2026-02-01T00:00:00Z";
        alpha.metadata.updatedAt = "2026-02-02T00:00:00Z";
        beta.metadata.folder = null;
        SdkProfile semNome = profile("m-perfil", null);

        List<SdkProfile> porNome = new ArrayList<>(List.of(alpha, beta, semNome));
        porNome.sort(SdkProfileCatalogOperations.comparatorFor("NAME"));
        // nome ausente vira "" e ordena antes
        assertEquals(List.of(semNome, alpha, beta), porNome);

        List<SdkProfile> porCriacao = new ArrayList<>(List.of(beta, alpha));
        porCriacao.sort(SdkProfileCatalogOperations.comparatorFor("createdat"));
        assertEquals(List.of(alpha, beta), porCriacao);

        List<SdkProfile> porAtualizacao = new ArrayList<>(List.of(alpha, beta));
        porAtualizacao.sort(SdkProfileCatalogOperations.comparatorFor("UPDATEDAT"));
        assertEquals(List.of(beta, alpha), porAtualizacao);

        List<SdkProfile> porPasta = new ArrayList<>(List.of(alpha, beta));
        porPasta.sort(SdkProfileCatalogOperations.comparatorFor("folder"));
        assertEquals(List.of(beta, alpha), porPasta);

        List<SdkProfile> porId = new ArrayList<>(List.of(alpha, beta));
        porId.sort(SdkProfileCatalogOperations.comparatorFor(null));
        assertEquals(List.of(beta, alpha), porId);
    }

    @Test
    void patchAppliesOnlyProvidedFieldsAndBumpsUpdatedAt() {
        SdkProfile p = profile("patch-1", "Patch");
        String antes = p.metadata.updatedAt;

        SdkProfileBulkPatchRequest patch = new SdkProfileBulkPatchRequest();
        patch.folder = "nova-pasta";
        patch.status = "paused";
        patch.pinned = true;
        patch.locked = true;
        patch.notes = "revisado";
        patch.mainWebsite = "https://novo.test";
        patch.addTags = java.util.Arrays.asList("casino", "  ", "casino", null);
        patch.removeTags = java.util.Arrays.asList("bet", "inexistente");

        SdkProfileCatalogOperations.applyPatch(p, patch);

        assertEquals("nova-pasta", p.metadata.folder);
        assertEquals("paused", p.metadata.status);
        assertTrue(p.metadata.pinned);
        assertTrue(p.metadata.locked);
        assertEquals("revisado", p.metadata.notes);
        assertEquals("https://novo.test", p.metadata.mainWebsite);
        assertEquals(List.of("br", "casino"), p.metadata.tags);
        assertNotEquals(antes, p.metadata.updatedAt);

        // patch vazio não muda campos, mas ainda carimba updatedAt
        SdkProfileBulkPatchRequest vazio = new SdkProfileBulkPatchRequest();
        String depois = p.metadata.updatedAt;
        SdkProfileCatalogOperations.applyPatch(p, vazio);
        assertNotNull(p.metadata.updatedAt);
        assertEquals("nova-pasta", p.metadata.folder);
    }

    @Test
    void selectorAndNameAreRequiredAndTrimmed() {
        assertEquals("bot-1", SdkProfileCatalogOperations.requireSelector("  bot-1  "));
        assertEquals("Nome", SdkProfileCatalogOperations.requireName(" Nome "));

        assertThrows(IllegalArgumentException.class,
            () -> SdkProfileCatalogOperations.requireSelector("   "));
        assertThrows(IllegalArgumentException.class,
            () -> SdkProfileCatalogOperations.requireSelector(null));
        assertThrows(IllegalArgumentException.class,
            () -> SdkProfileCatalogOperations.requireName(""));
        assertThrows(IllegalArgumentException.class,
            () -> SdkProfileCatalogOperations.requireName(null));
    }
}
