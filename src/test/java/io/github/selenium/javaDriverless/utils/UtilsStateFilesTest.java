package io.github.selenium.javaDriverless.utils;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;

/**
 * Ciclo de vida dos arquivos de estado do Utils (is_first_run e useragent).
 * Os arquivos vivem no DATA_DIR real do usuário: cada teste faz snapshot,
 * exercita o cenário e restaura o conteúdo original no finally — um hook de
 * shutdown cobre o caso de a JVM morrer entre a escrita e a restauração.
 */
class UtilsStateFilesTest {

    private static final Path FIRST_RUN = Utils.DATA_DIR.resolve("is_first_run");
    private static final Path USERAGENT = Utils.DATA_DIR.resolve("useragent");
    private static final java.util.Map<Path, String> SNAPSHOTS = new java.util.HashMap<>();

    static {
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            for (java.util.Map.Entry<Path, String> entrada : SNAPSHOTS.entrySet()) {
                try {
                    restore(entrada.getKey(), entrada.getValue());
                } catch (Exception ignorado) {
                    // última linha de defesa: nada mais a fazer
                }
            }
        }));
    }

    private static void registrar(Path arquivo) throws Exception {
        SNAPSHOTS.putIfAbsent(arquivo, snapshot(arquivo));
    }

    @Test
    void utilityClassIsInstantiableForTooling() {
        // construtor implícito precisa existir: ferramentas de serialização/diagnóstico o usam
        assertNotNull(new Utils());
    }

    @Test
    void firstRunIsTrueWhenMarkerIsMissingAndWritesTheCurrentVersion() throws Exception {
        registrar(FIRST_RUN);
        String original = snapshot(FIRST_RUN);
        try {
            Files.deleteIfExists(FIRST_RUN);

            Boolean firstRun = Utils.isFirstRun().join();

            assertEquals(Boolean.TRUE, firstRun);
            assertEquals(Utils.VERSION, Files.readString(FIRST_RUN), "marcador criado com a versão atual");
        } finally {
            restore(FIRST_RUN, original);
        }
    }

    @Test
    void markerWithCurrentVersionMeansNotFirstRun() throws Exception {
        registrar(FIRST_RUN);
        String original = snapshot(FIRST_RUN);
        try {
            Files.createDirectories(FIRST_RUN.getParent());
            Files.writeString(FIRST_RUN, Utils.VERSION);

            assertEquals(Boolean.FALSE, Utils.isFirstRun().join());
            assertEquals(Utils.VERSION, Files.readString(FIRST_RUN));
        } finally {
            restore(FIRST_RUN, original);
        }
    }

    @Test
    void markerFromOlderVersionUpgradesInPlaceAndReportsNewVersion() throws Exception {
        registrar(FIRST_RUN);
        String original = snapshot(FIRST_RUN);
        try {
            Files.createDirectories(FIRST_RUN.getParent());
            Files.writeString(FIRST_RUN, "0.0.0-teste");

            // null = nova versão detectada (licença reexibida)
            assertNull(Utils.isFirstRun().join());
            assertEquals(Utils.VERSION, Files.readString(FIRST_RUN), "marcador atualizado para a versão atual");
        } finally {
            restore(FIRST_RUN, original);
        }
    }

    @Test
    void defaultUserAgentRoundTripThroughDeprecatedPair() throws Exception {
        registrar(USERAGENT);
        String original = snapshot(USERAGENT);
        try {
            Utils.setDefaultUA("Mozilla/5.0 TesteUA").join();
            assertEquals("Mozilla/5.0 TesteUA", Utils.getDefaultUA().join());
        } finally {
            restore(USERAGENT, original);
        }
    }

    @Test
    void defaultUserAgentIsAbsentWhenNothingWasStored() throws Exception {
        registrar(USERAGENT);
        String original = snapshot(USERAGENT);
        try {
            Files.deleteIfExists(USERAGENT);

            assertNull(Utils.getDefaultUA().join());
        } finally {
            restore(USERAGENT, original);
        }
    }

    private static String snapshot(Path file) throws Exception {
        return Files.exists(file) ? Files.readString(file) : null;
    }

    private static void restore(Path file, String content) throws Exception {
        if (content == null) {
            Files.deleteIfExists(file);
            assertFalse(Files.exists(file));
            return;
        }
        Files.createDirectories(file.getParent());
        Files.writeString(file, content);
    }

    private static void assertNotNull(Object value) {
        assertTrue(value != null, "instância esperada");
    }
}
