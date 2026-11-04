package io.github.selenium.javaDriverless;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Sanitização de identificadores de profile em componentes de caminho seguros:
 * slug com hash, sufixo validado, casos de colisão de caixa e migração legada.
 */
class ProfileKeyGuardTest {
    @TempDir
    Path root;

    @Test
    void garbageNamesGetHashedSlugsAndEmptySlugsFallBackToProfile() {
        Path chaves = ProfileKey.resolveUnder(root, "###");
        Path estrelas = ProfileKey.resolveUnder(root, "***");

        assertThat(chaves.getFileName().toString()).startsWith("~jdl-x-").contains("profile-");
        assertThat(chaves).isNotEqualTo(estrelas);
    }

    @Test
    void resolveFileUnderRejectsUnsafeSuffixes() {
        assertThatThrownBy(() -> ProfileKey.resolveFileUnder(root, "bot", "inválido!.pid"))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("Sufixo");
        assertThatThrownBy(() -> ProfileKey.resolveFileUnder(root, "bot", "../escapou"))
            .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void migrationRejectsTargetOutsideRootOrNullTarget() {
        Path fora = root.resolve("..").resolve("fora-da-raiz");

        assertThatThrownBy(() -> ProfileKey.migrateLegacyDirectory(root, "bot", fora))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("fora da raiz");
        assertThatThrownBy(() -> ProfileKey.migrateLegacyDirectory(root, "bot", null))
            .isInstanceOf(NullPointerException.class);
    }

    @Test
    void migrationKeepsTargetWhenTheRawNameCannotBeAPath() {
        Path alvo = ProfileKey.resolveUnder(root, "alvo");

        // NUL não compõe Path: parse falha e o alvo segue inalterado
        assertThat(ProfileKey.migrateLegacyDirectory(root, "inv\u0000álido", alvo))
            .isEqualTo(alvo);
    }

    @Test
    void migrationKeepsTargetForAbsoluteAndDotDotNames() {
        Path alvo = ProfileKey.resolveUnder(root, "alvo");

        assertThat(ProfileKey.migrateLegacyDirectory(root, "/absoluto/proibido", alvo))
            .isEqualTo(alvo);
        assertThat(ProfileKey.migrateLegacyDirectory(root, "a/../b", alvo)).isEqualTo(alvo);
        assertThat(ProfileKey.migrateLegacyDirectory(root, ".", alvo)).isEqualTo(alvo);
    }

    @Test
    void migrationIgnoresLegacyThatDoesNotExist() {
        Path alvo = ProfileKey.resolveUnder(root, "nunca-criado");

        assertThat(ProfileKey.migrateLegacyDirectory(root, "nunca-criado", alvo))
            .isEqualTo(alvo);
        assertThat(alvo).doesNotExist();
    }

    @Test
    void migrationRejectsLegacyDirectorySymlink() throws Exception {
        Path fora = Files.createTempDirectory("jdl-key-outside-");
        try {
            Files.createSymbolicLink(root.resolve("legado-link"), fora);

            Path alvo = ProfileKey.resolveUnder(root, "outro");
            assertThatThrownBy(() -> ProfileKey.migrateLegacyDirectory(root, "legado-link", alvo))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("simbólico");
        } finally {
            Files.deleteIfExists(root.resolve("legado-link"));
            Files.deleteIfExists(fora);
        }
    }

    @Test
    void migrationRejectsLegacyAndEncodedTargetCoexisting() throws Exception {
        Files.createDirectories(root.resolve("Meu Bot"));
        Path alvo = ProfileKey.resolveUnder(root, "Meu Bot");
        Files.createDirectories(alvo);

        assertThatThrownBy(() -> ProfileKey.migrateLegacyDirectory(root, "Meu Bot", alvo))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("simultaneamente");
    }

    @Test
    void migrationFailsClosedWhenTheTargetParentCannotBeCreated() throws Exception {
        Path bloqueio = root.resolve("bloqueio");
        Files.writeString(bloqueio, "arquivo no lugar do diretório pai");
        Files.createDirectories(root.resolve("legado-real"));

        Path alvo = bloqueio.resolve("alvo-migrado");

        assertThatThrownBy(() -> ProfileKey.migrateLegacyDirectory(root, "legado-real", alvo))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("Não foi possível migrar");
    }

    @Test
    void mixedCaseNamesReuseExactLegacyEntriesAndHashOtherwise() throws Exception {
        Path exata = Files.createDirectories(root.resolve("Bot"));

        assertThat(ProfileKey.resolveUnder(root, "Bot")).isEqualTo(exata);

        // sem entrada exata com essa caixa: componente com hash anti-colisão
        Path comHash = ProfileKey.resolveUnder(root, "OutroBot");
        assertThat(comHash.getFileName().toString()).startsWith("~jdl-c-");
        assertThat(comHash).isNotEqualTo(root.resolve("outrobot"));
    }
}
