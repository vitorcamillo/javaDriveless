package io.github.selenium.javaDriverless.profiles;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.assertEquals;

import java.io.ByteArrayOutputStream;
import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Workflows do catálogo sobre armazenamento real em @TempDir: listagem, patch em
 * lote, lixeira, snapshot e o ciclo export/verify/import de arquivos zip.
 */
class SdkProfileCatalogWorkflowsTest {
    @TempDir
    Path tempDir;

    private static String sha256(byte[] bytes) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    }

    private static void zipEntry(ZipOutputStream zip, String name, byte[] bytes) throws Exception {
        zip.putNextEntry(new ZipEntry(name));
        zip.write(bytes);
        zip.closeEntry();
    }

    @Test
    void listHonorsQueryFiltersSortingAndPagination() throws Exception {
        SdkProfileCatalog catalog = SdkProfileCatalog.at(tempDir);
        catalog.create("beta", "Beta");
        catalog.create("alpha", "Alpha");
        SdkProfile trash = catalog.create("apoio", "Apoio");
        trash.metadata.status = "deleted";
        catalog.save(trash);

        assertThat(catalog.list(null)).hasSize(3);

        SdkProfileListQuery query = new SdkProfileListQuery();
        query.sortBy = "name";
        query.status = "active";
        List<SdkProfile> porNome = catalog.list(query);
        assertThat(porNome).extracting(perfil -> perfil.name).containsExactly("Alpha", "Beta");

        query.descending = true;
        assertThat(catalog.list(query)).extracting(perfil -> perfil.name)
            .containsExactly("Beta", "Alpha");

        query.descending = false;
        query.offset = 1;
        query.limit = 1;
        assertThat(catalog.list(query)).extracting(perfil -> perfil.name).containsExactly("Beta");
    }

    @Test
    void bulkPatchReportsSkippedLockedPlannedAndUpdatedItems() throws Exception {
        SdkProfileCatalog catalog = SdkProfileCatalog.at(tempDir);
        SdkProfile trancado = catalog.create("trancado", "Trancado");
        trancado.metadata.locked = true;
        catalog.save(trancado);
        catalog.create("livre", "Livre");

        SdkProfileBulkPatchRequest request = new SdkProfileBulkPatchRequest();
        request.selectors = List.of("fantasma", "trancado", "livre");
        request.notes = "revisão";
        request.dryRun = true;

        SdkProfileBulkPatchResult dryRun = catalog.bulkPatch(request);
        assertThat(dryRun.items).extracting(item -> item.status)
            .containsExactly("missing", "locked", "planned");
        assertThat(catalog.load("livre")).hasValueSatisfying(perfil ->
            assertThat(perfil.metadata.notes).isNotEqualTo("revisão"));

        request.dryRun = false;
        SdkProfileBulkPatchResult efetivo = catalog.bulkPatch(request);
        assertThat(efetivo.items).extracting(item -> item.status)
            .containsExactly("missing", "locked", "updated");
        assertThat(catalog.load("livre")).hasValueSatisfying(perfil ->
            assertThat(perfil.metadata.notes).isEqualTo("revisão"));
    }

    @Test
    void bulkPatchDoesNotOverwriteConcurrentLock() throws Exception {
        SdkProfileCatalog catalog = SdkProfileCatalog.at(tempDir);
        catalog.create("alvo", "Alvo");

        SdkProfileBulkPatchRequest request = new SdkProfileBulkPatchRequest();
        request.selectors = List.of("alvo");
        request.notes = "revisão";
        request.dryRun = false;

        CyclicBarrier start = new CyclicBarrier(2);
        AtomicReference<Throwable> failure = new AtomicReference<>();
        Thread patcher = new Thread(() -> {
            try {
                start.await(5, TimeUnit.SECONDS);
                catalog.bulkPatch(request);
            } catch (Throwable e) {
                failure.compareAndSet(null, e);
            }
        });
        Thread locker = new Thread(() -> {
            try {
                start.await(5, TimeUnit.SECONDS);
                SdkProfile profile = catalog.load("alvo").orElseThrow();
                profile.metadata.locked = true;
                catalog.save(profile);
            } catch (Throwable e) {
                failure.compareAndSet(null, e);
            }
        });
        patcher.start();
        locker.start();
        patcher.join(10_000);
        locker.join(10_000);

        assertThat(patcher.isAlive()).isFalse();
        assertThat(locker.isAlive()).isFalse();
        assertThat(failure.get()).isNull();
        // Qualquer ordem valida preserva o bloqueio: ou o patch ve o perfil
        // ja bloqueado e o ignora, ou o bloqueio e gravado depois do patch.
        assertThat(catalog.load("alvo")).hasValueSatisfying(profile ->
            assertThat(profile.metadata.locked).isTrue());
    }

    @Test
    void trashMarksDeletedAndRefusesLockedProfiles() throws Exception {
        SdkProfileCatalog catalog = SdkProfileCatalog.at(tempDir);
        catalog.create("comum", "Comum");
        SdkProfile trancado = catalog.create("sellado", "Sellado");
        trancado.metadata.locked = true;
        catalog.save(trancado);

        SdkProfile descartado = catalog.trash("comum");
        assertThat(descartado.metadata.status).isEqualTo("deleted");
        assertThat(descartado.metadata.deletedAt).isNotBlank();

        assertThatThrownBy(() -> catalog.trash("sellado"))
            .isInstanceOf(ProfileCatalogException.class)
            .hasMessageContaining("locked");
    }

    @Test
    void exportVerifyImportRoundTripThroughZip() throws Exception {
        SdkProfileCatalog catalog = SdkProfileCatalog.at(tempDir.resolve("origem"));
        catalog.create("perfil-a", "Perfil A");
        catalog.create("perfil-b", "Perfil B");
        Path arquivo = tempDir.resolve("arquivos").resolve("lote.zip");

        catalog.exportArchive(arquivo);
        assertThat(arquivo).exists();

        SdkProfileArchiveReport verificacao = catalog.verifyArchive(arquivo);
        assertThat(verificacao.valid).isTrue();
        assertThat(verificacao.errors).isEmpty();
        assertThat(verificacao.profileIds).containsExactlyInAnyOrder("perfil-a", "perfil-b");

        SdkProfileCatalog destino = SdkProfileCatalog.at(tempDir.resolve("destino"));
        List<SdkProfile> importados = destino.importArchive(arquivo, true);
        assertThat(importados).hasSize(2);
        assertThat(destino.list()).isEmpty(); // dryRun: nada é gravado

        destino.importArchive(arquivo, false);
        assertThat(destino.list()).extracting(perfil -> perfil.id)
            .containsExactlyInAnyOrder("perfil-a", "perfil-b");
    }

    @Test
    void verifyArchiveFlagsUnsafeEntriesDuplicatesAndChecksumDrift() throws Exception {
        SdkProfileCatalog catalog = SdkProfileCatalog.at(tempDir);
        catalog.create("bom", "Bom");
        Path arquivo = tempDir.resolve("suspeito.zip");
        SdkProfile bom = catalog.load("bom").orElseThrow();
        String jsonBom = catalog.toJson(bom);
        byte[] bytesBom = jsonBom.getBytes(StandardCharsets.UTF_8);
        byte[] corrompido = jsonBom.replace("Bom", "Tam").getBytes(StandardCharsets.UTF_8);

        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(buffer, StandardCharsets.UTF_8)) {
            zipEntry(zip, "profiles/bom.json", bytesBom);
            zipEntry(zip, "profiles/../escapou.json", bytesBom);      // id/caminho inseguro
            zipEntry(zip, "solto.txt", "não sou perfil".getBytes(StandardCharsets.UTF_8));
            // manifest só cobre o bom e com checksum que não bate (aponta p/ corrompido)
            String manifest = sha256(corrompido) + "  profiles/bom.json\n"
                + sha256(bytesBom) + "  profiles/ausente.json\n";
            zipEntry(zip, "manifest.sha256", manifest.getBytes(StandardCharsets.UTF_8));
        }
        Files.write(arquivo, buffer.toByteArray());

        SdkProfileArchiveReport relatorio = catalog.verifyArchive(arquivo);
        assertThat(relatorio.valid).isFalse();
        assertThat(relatorio.errors).anyMatch(e -> e.contains("Unsafe archive entry"));
        assertThat(relatorio.errors).anyMatch(e -> e.contains("Checksum mismatch"));
        assertThat(relatorio.errors).anyMatch(e -> e.contains("Manifest references missing entry"));

        assertThatThrownBy(() -> catalog.importArchive(arquivo, false))
            .isInstanceOf(ProfileCatalogException.class)
            .hasMessageContaining("Invalid profile archive");
    }

    @Test
    void archiveWithoutManifestIsRejectedAndMissingFileThrows() throws Exception {
        SdkProfileCatalog catalog = SdkProfileCatalog.at(tempDir);
        SdkProfile perfil = catalog.create("sem-manifest", "Sem Manifest");
        byte[] json = catalog.toJson(perfil).getBytes(StandardCharsets.UTF_8);

        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(buffer, StandardCharsets.UTF_8)) {
            zipEntry(zip, "profiles/sem-manifest.json", json);
        }
        Path arquivo = tempDir.resolve("sem-manifest.zip");
        Files.write(arquivo, buffer.toByteArray());

        SdkProfileArchiveReport relatorio = catalog.verifyArchive(arquivo);
        assertThat(relatorio.valid).isFalse();
        assertThat(relatorio.errors).contains("Missing archive manifest");

        assertThatThrownBy(() -> catalog.verifyArchive(tempDir.resolve("não-existe.zip")))
            .isInstanceOf(ProfileCatalogException.class)
            .hasMessageContaining("Failed to verify");
    }

    @Test
    void archiveEntryLargerThanLimitIsRejectedDuringVerificationAndImport() throws Exception {
        SdkProfileCatalog catalog = SdkProfileCatalog.at(tempDir.resolve("catalogo"));
        Path arquivo = tempDir.resolve("bomb.zip");
        byte[] zeros = new byte[SdkProfileArchiveSupport.MAX_ENTRY_BYTES + 1];

        try (ZipOutputStream zip = new ZipOutputStream(Files.newOutputStream(arquivo), StandardCharsets.UTF_8)) {
            zipEntry(zip, "profiles/bomb.json", zeros);
        }

        assertThatThrownBy(() -> catalog.verifyArchive(arquivo))
            .isInstanceOf(ProfileCatalogException.class)
            .hasMessageContaining("Failed to verify");
        assertThatThrownBy(() -> catalog.importArchive(arquivo, false))
            .isInstanceOf(ProfileCatalogException.class)
            .hasMessageContaining("Failed to verify");
    }

    @Test
    void directoryEntryLargerThanLimitIsRejectedDuringVerificationAndImport() throws Exception {
        SdkProfileCatalog catalog = SdkProfileCatalog.at(tempDir.resolve("catalogo"));
        Path arquivo = tempDir.resolve("directory-bomb.zip");
        byte[] payload = new byte[SdkProfileArchiveSupport.MAX_ENTRY_BYTES + 1];

        try (ZipOutputStream zip = new ZipOutputStream(Files.newOutputStream(arquivo), StandardCharsets.UTF_8)) {
            zipEntry(zip, "directory/", payload);
        }

        assertThatThrownBy(() -> catalog.verifyArchive(arquivo))
            .isInstanceOf(ProfileCatalogException.class)
            .hasMessageContaining("Failed to verify");
        assertThatThrownBy(() -> catalog.importArchive(arquivo, false))
            .isInstanceOf(ProfileCatalogException.class)
            .hasMessageContaining("Failed to verify");
    }

    @Test
    void exportToBlockedDestinationAndMalformedSnapshotFailWithCatalogException()
        throws Exception {
        SdkProfileCatalog catalog = SdkProfileCatalog.at(tempDir);
        catalog.create("x", "X");
        Path bloqueio = tempDir.resolve("arquivo-bloqueio");
        Files.writeString(bloqueio, "não é diretório");

        assertThatThrownBy(() -> catalog.exportArchive(bloqueio.resolve("lote.zip")))
            .isInstanceOf(ProfileCatalogException.class)
            .hasMessageContaining("Failed to export");

        Path snapshot = tempDir.resolve("snapshot-quebrado.json");
        Files.writeString(snapshot, "{não é json");
        assertThatThrownBy(() -> catalog.restoreSnapshot(snapshot, true))
            .isInstanceOf(ProfileCatalogException.class)
            .hasMessageContaining("Failed to restore");
    }

    @Test
    void jsonRoundTripAndParseFailure() throws Exception {
        SdkProfileCatalog catalog = SdkProfileCatalog.at(tempDir);
        SdkProfile perfil = catalog.create("serializavel", "Serializável");
        perfil.metadata.notes = "anotação";

        String json = catalog.toJson(perfil);
        SdkProfile reidratado = catalog.fromJson(json);
        assertThat(reidratado.id).isEqualTo("serializavel");
        assertThat(reidratado.metadata.notes).isEqualTo("anotação");

        assertThatThrownBy(() -> catalog.fromJson("{quebrado"))
            .isInstanceOf(ProfileCatalogException.class)
            .hasMessageContaining("Failed to parse profile JSON");
    }

    @Test
    void snapshotMaiorQueLimiteERejeitadoAntesDeParsear() throws Exception {
        SdkProfileCatalog catalog = SdkProfileCatalog.at(tempDir.resolve("catalogo"));
        // Arquivo sparse: ocupa quase nada em disco, mas tem 9 MiB de tamanho.
        Path snapshot = tempDir.resolve("snapshot-grande.json");
        try (RandomAccessFile raf = new RandomAccessFile(snapshot.toFile(), "rw")) {
            raf.setLength(9L * 1024 * 1024);
        }

        assertThatThrownBy(() -> catalog.restoreSnapshot(snapshot, true))
            .isInstanceOf(ProfileCatalogException.class)
            .hasMessageContaining("size limit");
        assertThat(catalog.list()).isEmpty(); // nada é gravado
    }

    @Test
    void snapshotComPerfisDemaisERejeitadoAposParsear() throws Exception {
        SdkProfileCatalog catalog = SdkProfileCatalog.at(tempDir.resolve("catalogo"));
        Path snapshot = tempDir.resolve("snapshot-lote.json");
        StringBuilder json = new StringBuilder("[");
        for (int i = 0; i <= SdkProfileCatalogWorkflows.MAX_SNAPSHOT_PROFILES; i++) {
            if (i > 0) {
                json.append(",");
            }
            json.append("{\"id\":\"lote-").append(i).append("\",\"name\":\"Lote ").append(i).append("\"}");
        }
        json.append("]");
        Files.writeString(snapshot, json.toString());

        assertThatThrownBy(() -> catalog.restoreSnapshot(snapshot, true))
            .isInstanceOf(ProfileCatalogException.class)
            .hasMessageContaining("profile limit");
        assertThat(catalog.list()).isEmpty(); // nada é gravado
    }

    @Test
    void exportArchiveNasceComPermissao0600ESemSeguirSymlink() throws Exception {
        SdkProfileCatalog catalog = SdkProfileCatalog.at(tempDir);
        catalog.create("perfil-0600", "Perfil 0600");
        Path arquivo = tempDir.resolve("lote-0600.zip");

        catalog.exportArchive(arquivo);
        assertEquals("rw-------", PosixFilePermissions.toString(Files.getPosixFilePermissions(arquivo)));

        Path alvo = tempDir.resolve("alvo.txt");
        Files.writeString(alvo, "conteúdo externo");
        Path link = tempDir.resolve("link.zip");
        Files.createSymbolicLink(link, alvo);
        assertThatThrownBy(() -> catalog.exportArchive(link))
            .isInstanceOf(ProfileCatalogException.class);
    }

    @Test
    void importArchiveComJsonCorrompidoEManifestOkLançaExceçãoDeImport() throws Exception {
        SdkProfileCatalog catalog = SdkProfileCatalog.at(tempDir);
        byte[] corrompido = "{não é json".getBytes(StandardCharsets.UTF_8);
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(buffer, StandardCharsets.UTF_8)) {
            zipEntry(zip, "profiles/x.json", corrompido);
            zipEntry(zip, "manifest.sha256",
                (sha256(corrompido) + "  profiles/x.json\n").getBytes(StandardCharsets.UTF_8));
        }
        Path arquivo = tempDir.resolve("corrompido.zip");
        Files.write(arquivo, buffer.toByteArray());

        assertThatThrownBy(() -> catalog.importArchive(arquivo, false))
            .isInstanceOf(ProfileCatalogException.class)
            .hasMessageContaining("Failed to import profile archive");
    }

    @Test
    void bulkPatchAtualizaTresPerfisEmLoteMantendoOrdem() throws Exception {
        SdkProfileCatalog catalog = SdkProfileCatalog.at(tempDir);
        catalog.create("lote-a", "Lote A");
        catalog.create("lote-b", "Lote B");
        catalog.create("lote-c", "Lote C");

        SdkProfileBulkPatchRequest request = new SdkProfileBulkPatchRequest();
        request.selectors = List.of("lote-a", "lote-b", "lote-c");
        request.notes = "revisão em lote";
        request.dryRun = false;

        SdkProfileBulkPatchResult resultado = catalog.bulkPatch(request);
        assertThat(resultado.items).extracting(item -> item.status)
            .containsExactly("updated", "updated", "updated");
        assertThat(resultado.items).extracting(item -> item.id)
            .containsExactly("lote-a", "lote-b", "lote-c");
        assertThat(catalog.load("lote-a")).hasValueSatisfying(perfil ->
            assertThat(perfil.metadata.notes).isEqualTo("revisão em lote"));
        assertThat(catalog.load("lote-b")).hasValueSatisfying(perfil ->
            assertThat(perfil.metadata.notes).isEqualTo("revisão em lote"));
        assertThat(catalog.load("lote-c")).hasValueSatisfying(perfil ->
            assertThat(perfil.metadata.notes).isEqualTo("revisão em lote"));
    }
}
