package io.github.selenium.javaDriverless.profiles;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

class SdkProfileCatalogLockTest {

    @Test
    void lockRefusesSymlinkPointingOutsideRoot(@TempDir Path root) throws Exception {
        Path external = Files.createTempFile("jd-lock-target-", ".bin");
        try {
            String conteudo = Files.readString(external);
            Files.createSymbolicLink(root.resolve(".catalog.lock"), external.toAbsolutePath());
            assertThatThrownBy(() -> SdkProfileCatalogLock.with(root, () -> "ok"))
                    .isInstanceOf(ProfileCatalogException.class)
                    .hasMessageContaining("Failed to lock profile catalog");
            // O alvo externo não pode ter sido usado como lock nem alterado.
            assertThat(Files.readString(external)).isEqualTo(conteudo);
        } finally {
            Files.deleteIfExists(external);
        }
    }

    @Test
    void lockAllowsPlainRegularFileAndReusesExistingLock(@TempDir Path root) {
        assertThat(SdkProfileCatalogLock.with(root, () -> "primeiro")).isEqualTo("primeiro");
        // Segunda chamada reusa o .catalog.lock pré-existente (caminho CREATE_NEW -> open existente).
        assertThat(SdkProfileCatalogLock.with(root, () -> "segundo")).isEqualTo("segundo");
    }

    @Test
    void lockNasceComPermissao0600(@TempDir Path root) throws Exception {
        assumeTrue(FileSystems.getDefault().supportedFileAttributeViews().contains("posix"),
                "Requer FS com atributos POSIX");
        SdkProfileCatalogLock.with(root, () -> "ok");
        Path lock = root.resolve(".catalog.lock");
        assertEquals("rw-------",
                PosixFilePermissions.toString(Files.getPosixFilePermissions(lock)));
    }

    @Test
    void lockLegado0644EAceitoERestringidoPara0600(@TempDir Path root) throws Exception {
        assumeTrue(FileSystems.getDefault().supportedFileAttributeViews().contains("posix"),
                "Requer FS com atributos POSIX");
        Path lock = root.resolve(".catalog.lock");
        Files.write(lock, new byte[0]);
        Files.setPosixFilePermissions(lock, PosixFilePermissions.fromString("rw-r--r--"));

        assertThat(SdkProfileCatalogLock.with(root, () -> "aceito")).isEqualTo("aceito");
        // restringido na abertura para eliminar lock compartilhado entre usuários locais
        assertEquals("rw-------",
                PosixFilePermissions.toString(Files.getPosixFilePermissions(lock)));
    }
    @Test
    void permissionFailureClosesAlreadyOpenedChannel(@TempDir Path root) throws Exception {
        Path path = root.resolve(".catalog.lock");
        var channel = org.mockito.Mockito.mock(java.nio.channels.FileChannel.class);
        var failure = new java.io.IOException("permission denied");
        var permissions = PosixFilePermissions.fromString("rw-------");
        var method = SdkProfileCatalogLock.class.getDeclaredMethod("openExistingNoFollow", Path.class);
        method.setAccessible(true);
        try (var channels = org.mockito.Mockito.mockStatic(java.nio.channels.FileChannel.class);
             var files = org.mockito.Mockito.mockStatic(Files.class)) {
            channels.when(() -> java.nio.channels.FileChannel.open(path,
                    java.nio.file.StandardOpenOption.WRITE, java.nio.file.LinkOption.NOFOLLOW_LINKS))
                    .thenReturn(channel);
            files.when(() -> Files.setAttribute(path, "posix:permissions", permissions,
                    java.nio.file.LinkOption.NOFOLLOW_LINKS)).thenThrow(failure);
            assertThatThrownBy(() -> method.invoke(null, path)).hasCause(failure);
            org.mockito.Mockito.verify(channel).close();
        }
    }

    /** Thread segura with(...) enquanto outra chama com timeout curto: estouro < 5 s. */
    @Test
    void outraThreadSegurandoCausaTimeoutRapido(@TempDir Path root) throws Exception {
        CountDownLatch holderDentro = new CountDownLatch(1);
        CountDownLatch liberar = new CountDownLatch(1);
        AtomicBoolean holderConcluido = new AtomicBoolean(false);
        Thread holder = new Thread(() -> {
            SdkProfileCatalogLock.with(root, () -> {
                holderDentro.countDown();
                try {
                    liberar.await(10, TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                return "holder";
            });
            holderConcluido.set(true);
        });
        holder.start();
        assertThat(holderDentro.await(5, TimeUnit.SECONDS)).isTrue();

        long inicio = System.nanoTime();
        assertThatThrownBy(() -> SdkProfileCatalogLock.with(root, Duration.ofMillis(100), () -> "nunca"))
                .isInstanceOf(ProfileCatalogException.class)
                .hasMessageContaining("Timed out");
        assertThat(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - inicio)).isLessThan(5000);

        liberar.countDown();
        holder.join(10000);
        assertThat(holderConcluido).isTrue();
        // Após liberar, nova chamada funciona.
        assertThat(SdkProfileCatalogLock.with(root, Duration.ofSeconds(5), () -> "depois"))
                .isEqualTo("depois");
    }

    /** FileChannel.lock() externo no .catalog.lock (mesma JVM) também produz timeout. */
    @Test
    void fileLockExternoCausaTimeout(@TempDir Path root) throws Exception {
        SdkProfileCatalogLock.with(root, () -> "cria");
        Path lockPath = root.resolve(".catalog.lock");
        try (FileChannel canal = FileChannel.open(lockPath, StandardOpenOption.WRITE);
             FileLock externo = canal.lock()) {
            long inicio = System.nanoTime();
            assertThatThrownBy(() -> SdkProfileCatalogLock.with(root, Duration.ofMillis(200), () -> "nunca"))
                    .isInstanceOf(ProfileCatalogException.class)
                    .hasMessageContaining("Timed out");
            assertThat(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - inicio)).isLessThan(5000);
        }
    }

    /** Thread interrompida recebe "Interrupted" e mantém o flag de interrupção. */
    @Test
    void interrupcaoLancaInterruptedEMantemFlag(@TempDir Path root) throws Exception {
        CountDownLatch holderDentro = new CountDownLatch(1);
        CountDownLatch liberar = new CountDownLatch(1);
        Thread holder = new Thread(() -> SdkProfileCatalogLock.with(root, () -> {
            holderDentro.countDown();
            try {
                liberar.await(10, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            return "holder";
        }));
        holder.start();
        assertThat(holderDentro.await(5, TimeUnit.SECONDS)).isTrue();

        AtomicReference<Throwable> falha = new AtomicReference<>();
        AtomicBoolean flagMantido = new AtomicBoolean(false);
        Thread esperando = new Thread(() -> {
            Thread.currentThread().interrupt();
            try {
                SdkProfileCatalogLock.with(root, Duration.ofSeconds(5), () -> "nunca");
            } catch (Throwable t) {
                falha.set(t);
            }
            flagMantido.set(Thread.currentThread().isInterrupted());
        });
        esperando.start();
        esperando.join(10000);

        assertThat(falha.get()).isInstanceOf(ProfileCatalogException.class)
                .hasMessageContaining("Interrupted");
        assertThat(flagMantido).isTrue();

        liberar.countDown();
        holder.join(10000);
    }

    /** Liberação pelo holder restaura o acesso para novas chamadas. */
    @Test
    void novaChamadaFuncionaAposLiberacao(@TempDir Path root) throws Exception {
        CountDownLatch holderDentro = new CountDownLatch(1);
        CountDownLatch liberar = new CountDownLatch(1);
        Thread holder = new Thread(() -> SdkProfileCatalogLock.with(root, () -> {
            holderDentro.countDown();
            try {
                liberar.await(10, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            return "holder";
        }));
        holder.start();
        assertThat(holderDentro.await(5, TimeUnit.SECONDS)).isTrue();

        assertThatThrownBy(() -> SdkProfileCatalogLock.with(root, Duration.ofMillis(50), () -> "nunca"))
                .hasMessageContaining("Timed out");

        liberar.countDown();
        holder.join(10000);
        assertThat(SdkProfileCatalogLock.with(root, Duration.ofSeconds(5), () -> "depois"))
                .isEqualTo("depois");
    }

    /** Chamada aninhada (mesma thread) é reentrante mesmo com timeout mínimo. */
    @Test
    void chamadaAninhadaContinuaReentrante(@TempDir Path root) {
        String resultado = SdkProfileCatalogLock.with(root, () ->
                SdkProfileCatalogLock.with(root, Duration.ofMillis(1), () -> "aninhado"));
        assertThat(resultado).isEqualTo("aninhado");
    }

}
