package io.github.selenium.javaDriverless.profiles;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.MockedStatic;
import org.mockito.Mockito;

/**
 * Lote de storage no catálogo: cada perfil é resolvido no disco no máximo duas
 * vezes por lote (próprio + uma vez como "existente"), colisão entre candidatos
 * do mesmo lote continua sendo rejeitada e o id igual ao do candidato segue sem
 * ser resolvido (salvar por cima de registro com storage inválido).
 */
class SdkProfileCatalogStorageBatchTest {
    @TempDir
    Path tempDir;

    @Test
    void loteResolveCadaPerfilNoMaximoDuasVezes() {
        Path catalogRoot = tempDir.resolve("lote-catalog");
        SdkProfileCatalog catalog = SdkProfileCatalog.at(catalogRoot);
        List<SdkProfile> perfis = new ArrayList<>();
        for (int i = 0; i < 20; i++) {
            SdkProfile perfil = new SdkProfile("lote-" + i, "Lote " + i);
            perfil.storage.userDataDir = tempDir.resolve("lote-storage-" + i).toString();
            perfil.storage.downloadsDir = tempDir.resolve("lote-downloads-" + i).toString();
            perfis.add(perfil);
        }

        try (MockedStatic<SdkProfileStorageIdentity> identidade =
                Mockito.mockStatic(SdkProfileStorageIdentity.class, Mockito.CALLS_REAL_METHODS)) {
            catalog.saveAllUnlocked(perfis);
            // Sem cache: 2 resoluções do candidato + 2 por perfil já salvo no lote,
            // por candidato => O(N²). Com cache: no máximo 4 por perfil (limite 80).
            identidade.verify(() -> SdkProfileStorageIdentity.resolve(
                    Mockito.any(), Mockito.any(), Mockito.any()), Mockito.atMost(80));
        }

        assertThat(catalog.list()).hasSize(20);
    }

    @Test
    void loteAindaDetectaColisaoEntreCandidatos() {
        Path catalogRoot = tempDir.resolve("colisao-lote-catalog");
        SdkProfileCatalog catalog = SdkProfileCatalog.at(catalogRoot);
        String userDataDirCompartilhado = tempDir.resolve("colisao-lote-storage").toString();
        SdkProfile primeiro = new SdkProfile("lote-a", "Lote A");
        primeiro.storage.userDataDir = userDataDirCompartilhado;
        SdkProfile segundo = new SdkProfile("lote-b", "Lote B");
        segundo.storage.userDataDir = userDataDirCompartilhado;

        assertThatCode(() -> catalog.saveAllUnlocked(List.of(primeiro, segundo)))
                .isInstanceOf(ProfileCatalogException.class)
                .hasMessageContaining("collides");
        assertThat(catalog.load("lote-b")).isEmpty();
    }

    @Test
    void salvarPorCimaDeRegistroComStorageInvalidoContinuaFuncionando() throws Exception {
        Path catalogRoot = Files.createDirectories(tempDir.resolve("invalido-catalog"));
        Path bloqueio = tempDir.resolve("bloqueio");
        Files.writeString(bloqueio, "arquivo, não diretório");
        // Registro gravado diretamente: parse do catálogo aceita storage que resolve rejeita.
        SdkProfile invalido = new SdkProfile("profile-x", "Profile X");
        invalido.storage.userDataDir = bloqueio.toString();
        SdkProfileCatalog catalog = SdkProfileCatalog.at(catalogRoot);
        Files.writeString(catalogRoot.resolve("profile-x.json"), catalog.toJson(invalido),
                StandardCharsets.UTF_8);

        SdkProfile candidato = new SdkProfile("profile-x", "Profile X Atualizado");
        candidato.storage.userDataDir =
                Files.createDirectories(tempDir.resolve("storage-valido")).toString();

        // A validação preguiçosa não resolve o registro de mesmo id; o estágio de
        // escrita (lease) é validado pelos testes existentes do lease manager.
        assertThatCode(() -> SdkProfileCatalogLock.with(catalogRoot, () -> {
            catalog.validateStorageBatchUnlocked(List.of(candidato));
            return null;
        })).doesNotThrowAnyException();
    }
}
