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
import java.util.concurrent.CountDownLatch;
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

    @Test
    void purgeRecusaPerfilDeletedTrancadoEMantemArquivo() throws Exception {
        SdkProfileCatalog catalog = SdkProfileCatalog.at(tempDir);
        SdkProfile perfil = catalog.create("trancado-lixo", "Trancado Lixo");
        perfil.metadata.status = "deleted";
        perfil.metadata.locked = true;
        catalog.save(perfil);

        assertThatThrownBy(() -> catalog.purge("trancado-lixo"))
            .isInstanceOf(ProfileCatalogException.class)
            .hasMessageContaining("is locked");
        assertThat(catalog.load("trancado-lixo")).isPresent();
    }

    @Test
    void exportArchiveRecusaPerfilComUsernameEDestinoNaoECriado() throws Exception {
        SdkProfileCatalog catalog = SdkProfileCatalog.at(tempDir);
        SdkProfile perfil = catalog.create("com-credencial", "Com Credencial");
        // A checagem é textual sobre o JSON exportado: qualquer chave "username"
        // serializada no perfil dispara a recusa (save não valida, export sim).
        perfil.metadata.seeds.put("username", 1L);
        catalog.save(perfil);
        Path destino = tempDir.resolve("arquivos").resolve("credencial.zip");

        assertThatThrownBy(() -> catalog.exportArchive(destino))
            .isInstanceOf(ProfileCatalogException.class)
            .hasMessageContaining("must not contain proxy credentials");
        assertThat(destino).doesNotExist();
    }

    @Test
    void restoreSnapshotEImportProfileAcimaDoLimiteDeBytesSaoRejeitados() throws Exception {
        SdkProfileCatalog catalog = SdkProfileCatalog.at(tempDir.resolve("catalogo"));
        // Arquivo sparse de exatamente MAX_SNAPSHOT_BYTES + 1 bytes: ocupa quase
        // nada em disco, mas cruza a barreira anti-OOM por 1 byte.
        Path snapshot = tempDir.resolve("snapshot-limite.json");
        try (RandomAccessFile raf = new RandomAccessFile(snapshot.toFile(), "rw")) {
            raf.setLength(SdkProfileCatalogWorkflows.MAX_SNAPSHOT_BYTES + 1);
        }

        assertThatThrownBy(() -> catalog.restoreSnapshot(snapshot, true))
            .isInstanceOf(ProfileCatalogException.class)
            .hasMessageContaining("exceeds size limit");
        assertThatThrownBy(() -> catalog.importProfile(snapshot))
            .isInstanceOf(ProfileCatalogException.class)
            .hasMessageContaining("exceeds size limit");
    }

    @Test
    void trashEBulkPatchConcorrentesPreservamAmbasAsAlteracoes() throws Exception {
        SdkProfileCatalog catalog = SdkProfileCatalog.at(tempDir);
        for (int i = 0; i < 30; i++) {
            String id = "corrida-" + i;
            catalog.create(id, "Corrida " + i);

            SdkProfileBulkPatchRequest request = new SdkProfileBulkPatchRequest();
            request.selectors = List.of(id);
            request.notes = "nota " + i;
            request.dryRun = false;

            CountDownLatch largada = new CountDownLatch(1);
            AtomicReference<Throwable> failure = new AtomicReference<>();
            Thread trasher = new Thread(() -> {
                try {
                    largada.await(5, TimeUnit.SECONDS);
                    catalog.trash(id);
                } catch (Throwable e) {
                    failure.compareAndSet(null, e);
                }
            });
            Thread patcher = new Thread(() -> {
                try {
                    largada.await(5, TimeUnit.SECONDS);
                    catalog.bulkPatch(request);
                } catch (Throwable e) {
                    failure.compareAndSet(null, e);
                }
            });
            trasher.start();
            patcher.start();
            largada.countDown();
            trasher.join(30_000);
            patcher.join(30_000);

            assertThat(trasher.isAlive()).as("iteração " + i).isFalse();
            assertThat(patcher.isAlive()).as("iteração " + i).isFalse();
            assertThat(failure.get()).as("iteração " + i).isNull();
            // Qualquer ordem preserva as duas alterações: trash e bulkPatch são
            // seções críticas que partem sempre do estado já gravado no disco.
            String notaEsperada = "nota " + i;
            assertThat(catalog.load(id)).as("iteração " + i).hasValueSatisfying(perfil -> {
                assertThat(perfil.metadata.status).isEqualTo("deleted");
                assertThat(perfil.metadata.notes).isEqualTo(notaEsperada);
            });
        }
    }

    @Test
    void restoreDevolvePerfilDaLixeiraAtivoESemDeletedAt() throws Exception {
        SdkProfileCatalog catalog = SdkProfileCatalog.at(tempDir);
        catalog.create("restauravel", "Restaurável");
        catalog.trash("restauravel");
        assertThat(catalog.load("restauravel")).hasValueSatisfying(perfil ->
            assertThat(perfil.metadata.status).isEqualTo("deleted"));

        SdkProfile restaurado = catalog.restore("restauravel");

        assertThat(restaurado.metadata.status).isEqualTo("active");
        assertThat(restaurado.metadata.deletedAt).isNull();
        assertThat(catalog.load("restauravel")).hasValueSatisfying(perfil -> {
            assertThat(perfil.metadata.status).isEqualTo("active");
            assertThat(perfil.metadata.deletedAt).isNull();
        });
    }

    @Test
    void purgeRemovePerfilDaLixeiraECatalogFicaVazio() throws Exception {
        SdkProfileCatalog catalog = SdkProfileCatalog.at(tempDir);
        catalog.create("purgavel", "Purgável");
        catalog.trash("purgavel");

        assertThat(catalog.purge("purgavel")).isTrue();
        assertThat(catalog.load("purgavel")).isEmpty();
        assertThat(catalog.list()).isEmpty();
    }

    @Test
    void restoreSnapshotComExatamenteLimiteDeBytesNaoLancaERetornaVazio() throws Exception {
        SdkProfileCatalog catalog = SdkProfileCatalog.at(tempDir.resolve("catalogo"));
        // "[" + espaços + "]": JSON válido de array vazio com tamanho exato do limite.
        Path snapshot = tempDir.resolve("snapshot-no-limite.json");
        Files.writeString(snapshot,
            "[" + " ".repeat((int) SdkProfileCatalogWorkflows.MAX_SNAPSHOT_BYTES - 2) + "]");

        List<SdkProfile> restaurados = catalog.restoreSnapshot(snapshot, true);

        assertThat(restaurados).isEmpty();
        assertThat(catalog.list()).isEmpty();
    }

    @Test
    void snapshotRecusaPerfilComUsernameEDestinoNaoECriado() throws Exception {
        SdkProfileCatalog catalog = SdkProfileCatalog.at(tempDir);
        SdkProfile perfil = catalog.create("com-username", "Com Username");
        // Mesma checagem textual do exportArchive: chave "username" no JSON serializado.
        perfil.metadata.seeds.put("username", 1L);
        catalog.save(perfil);
        Path destino = tempDir.resolve("arquivos").resolve("snapshot-credencial.json");

        assertThatThrownBy(() -> catalog.snapshot(destino))
            .isInstanceOf(ProfileCatalogException.class)
            .hasMessageContaining("must not contain proxy credentials");
        assertThat(destino).doesNotExist();
    }

    @Test
    void exportArchiveDeDoisPerfisSemCredenciaisVerificaComOsDoisIds() throws Exception {
        SdkProfileCatalog catalog = SdkProfileCatalog.at(tempDir.resolve("origem"));
        catalog.create("par-a", "Par A");
        catalog.create("par-b", "Par B");
        Path destino = tempDir.resolve("arquivos").resolve("par.zip");

        catalog.exportArchive(destino);

        SdkProfileArchiveReport relatorio = catalog.verifyArchive(destino);
        assertThat(relatorio.valid).isTrue();
        assertThat(relatorio.profileIds).containsExactlyInAnyOrder("par-a", "par-b");
    }

    @Test
    void restoreSnapshotComIdsQueColidemPorCaixaLançaExceção() throws Exception {
        SdkProfileCatalog catalog = SdkProfileCatalog.at(tempDir.resolve("catalogo"));
        Path snapshot = tempDir.resolve("snapshot-colisao.json");
        Files.writeString(snapshot, "["
            + "{\"id\":\"Alias-X\",\"name\":\"Alias X\","
            + "\"storage\":{\"userDataDir\":\"" + tempDir.resolve("storage-a").toString()
            + "\",\"downloadsDir\":\"" + tempDir.resolve("downloads-a").toString() + "\"}},"
            + "{\"id\":\"alias-x\",\"name\":\"alias x\","
            + "\"storage\":{\"userDataDir\":\"" + tempDir.resolve("storage-b").toString()
            + "\",\"downloadsDir\":\"" + tempDir.resolve("downloads-b").toString() + "\"}}]");

        assertThatThrownBy(() -> catalog.restoreSnapshot(snapshot, false))
            .isInstanceOf(ProfileCatalogException.class)
            .hasMessageContaining("colide");
    }

    @Test
    void saveComIdQueColidePorCaixaComArquivoPreExistenteLançaExceção() throws Exception {
        SdkProfileCatalog catalog = SdkProfileCatalog.at(tempDir);
        catalog.save(new SdkProfile("Pre-X", "Pre X"));

        assertThatThrownBy(() -> catalog.save(new SdkProfile("pre-x", "Pre Minúsculo")))
            .isInstanceOf(ProfileCatalogException.class)
            .hasMessageContaining("colide");
    }

    @Test
    void restoreSnapshotComTresPerfisPersisteOsTres() throws Exception {
        SdkProfileCatalog catalog = SdkProfileCatalog.at(tempDir.resolve("catalogo"));
        Path snapshot = tempDir.resolve("snapshot-tres.json");
        Files.writeString(snapshot, "["
            + "{\"id\":\"rest-a\",\"name\":\"Rest A\"},"
            + "{\"id\":\"rest-b\",\"name\":\"Rest B\"},"
            + "{\"id\":\"rest-c\",\"name\":\"Rest C\"}]");

        List<SdkProfile> restaurados = catalog.restoreSnapshot(snapshot, false);
        assertThat(restaurados).extracting(perfil -> perfil.id)
            .containsExactlyInAnyOrder("rest-a", "rest-b", "rest-c");
        assertThat(catalog.list()).extracting(perfil -> perfil.id)
            .containsExactlyInAnyOrder("rest-a", "rest-b", "rest-c");
    }
}
