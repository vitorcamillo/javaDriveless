package io.github.selenium.javaDriverless;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

import io.github.selenium.javaDriverless.types.WebElement;

/**
 * Regressão da raiz: implicit wait consumido por findElement(s), timeouts de
 * script/page load respeitados nas variantes síncronas e restauração do flag
 * de interrupção ao encerrar processos no ProfileManager.
 */
class AuditoriaRaizRegressaoTest {

    private Chrome chrome;
    private Probe driver;

    /** probe concreto: o campo protected `chrome` vem da hierarquia real. */
    private static final class Probe extends JavaDriverlessNavigationOperations {
        void attach(Chrome chrome) {
            this.chrome = chrome;
        }

        /** os setters fazem cast final para JavaDriverless, que falha no probe;
         * a configuração do campo já aconteceu quando o cast estoura. */
        void configurar(Runnable op) {
            try {
                op.run();
            } catch (ClassCastException e) {
                // cast (JavaDriverless) this não se aplica ao probe
            }
        }
    }

    @BeforeEach
    void setUp() {
        chrome = mock(Chrome.class);
        driver = new Probe();
        driver.attach(chrome);
    }

    @Test
    void findElementSemImplicitWaitDelegaComOPadraoDeDezSegundos() {
        WebElement elemento = mock(WebElement.class);
        when(chrome.findElement(eq("css"), eq("a"), eq(10.0f)))
            .thenReturn(CompletableFuture.completedFuture(elemento));

        assertEquals(elemento, driver.findElement("css", "a"));
        verify(chrome).findElement("css", "a", 10.0f);
    }

    @Test
    void findElementsSemImplicitWaitDelegaComOPadraoDeDezSegundos() {
        when(chrome.findElements(eq("css"), eq("a"), eq(10.0f)))
            .thenReturn(CompletableFuture.completedFuture(List.of(mock(WebElement.class))));

        assertEquals(1, driver.findElements("css", "a").size());
        verify(chrome).findElements("css", "a", 10.0f);
    }

    @Test
    void implicitWaitDe2500MillisDelegaFindElementComDoisVirgulaCincoSegundos() {
        driver.configurar(() -> driver.implicitlyWait(2500));
        WebElement elemento = mock(WebElement.class);
        when(chrome.findElement(eq("css"), eq("a"), eq(2.5f)))
            .thenReturn(CompletableFuture.completedFuture(elemento));

        assertEquals(elemento, driver.findElement("css", "a"));
        verify(chrome).findElement("css", "a", 2.5f);
    }

    @Test
    void implicitWaitDe2500MillisDelegaFindElementsComDoisVirgulaCincoSegundos() {
        driver.configurar(() -> driver.implicitlyWait(2500));
        when(chrome.findElements(eq("css"), eq("a"), eq(2.5f)))
            .thenReturn(CompletableFuture.completedFuture(List.of(mock(WebElement.class))));

        assertEquals(1, driver.findElements("css", "a").size());
        verify(chrome).findElements("css", "a", 2.5f);
    }

    @Test
    void executeScriptComScriptTimeoutLancaTimeoutExceptionEmMenosDeCincoSegundos() {
        driver.configurar(() -> driver.setScriptTimeout(50));
        when(chrome.executeScript(eq("x"), isNull(), eq(true)))
            .thenReturn(new CompletableFuture<>()); // nunca completa

        RuntimeException thrown = assertTimeoutPreemptively(Duration.ofSeconds(5),
            () -> assertThrows(RuntimeException.class, () -> driver.executeScript("x")));

        assertEquals("Erro ao executar script", thrown.getMessage());
        assertTrue(thrown.getCause() instanceof TimeoutException,
            "causa deveria ser TimeoutException, foi: " + thrown.getCause());
    }

    @Test
    void getComPageLoadTimeoutLancaTimeoutExceptionEmMenosDeCincoSegundos() {
        driver.configurar(() -> driver.setPageLoadTimeout(50));
        when(chrome.get(eq("https://lento.test/"), eq(true)))
            .thenReturn(new CompletableFuture<>()); // nunca completa

        RuntimeException thrown = assertTimeoutPreemptively(Duration.ofSeconds(5),
            () -> assertThrows(RuntimeException.class, () -> driver.get("https://lento.test/")));

        assertEquals("Erro ao navegar para: https://lento.test/", thrown.getMessage());
        assertTrue(thrown.getCause() instanceof TimeoutException,
            "causa deveria ser TimeoutException, foi: " + thrown.getCause());
    }

    @Test
    void executeScriptSemTimeoutConfiguradoRetornaOValorDoFutureCompleto() {
        when(chrome.executeScript(eq("return 42"), isNull(), eq(true)))
            .thenReturn(CompletableFuture.completedFuture(42));

        assertEquals(42, driver.executeScript("return 42"));
    }

    @Test
    void executeScriptComScriptTimeoutZeroEsperaSemLimiteERetornaOValor() {
        driver.configurar(() -> driver.setScriptTimeout(0));
        CompletableFuture<Object> lento = new CompletableFuture<>();
        CompletableFuture.delayedExecutor(100, TimeUnit.MILLISECONDS)
            .execute(() -> lento.complete("valor-tardio"));
        when(chrome.executeScript(eq("x"), isNull(), eq(true))).thenReturn(lento);

        Object valor = assertTimeoutPreemptively(Duration.ofSeconds(5),
            () -> driver.executeScript("x"));

        assertEquals("valor-tardio", valor);
    }

    @Test
    @EnabledOnOs(OS.LINUX)
    void killProcessEncerraOProcessoEPreservaOFlagDeInterrupcao(@TempDir Path tempDir) throws Exception {
        ProfileManager manager = new ProfileManager(tempDir.resolve("perfis").toString());
        Process processo = new ProcessBuilder("sleep", "60").start();
        try {
            Thread.currentThread().interrupt();

            assertTrue(manager.killProcess(processo.pid()),
                "encerramento deve retornar true mesmo com interrupção pendente");
            assertFalse(processo.isAlive(), "processo deve terminar mesmo com interrupção");
            assertTrue(Thread.currentThread().isInterrupted(),
                "flag de interrupção deve continuar ativo após o encerramento");
        } finally {
            Thread.interrupted(); // limpa o flag para não contaminar outros testes
            processo.destroyForcibly();
        }
    }

    @Test
    @EnabledOnOs(OS.LINUX)
    void killProcessSemInterrupcaoEncerraOProcessoEMantemFlagInativo(@TempDir Path tempDir) throws Exception {
        ProfileManager manager = new ProfileManager(tempDir.resolve("perfis-sem-interrupcao").toString());
        Process processo = new ProcessBuilder("sleep", "60").start();
        try {
            assertTrue(manager.killProcess(processo.pid()),
                "encerramento gracioso deve retornar true");
            assertFalse(processo.isAlive(), "processo deve terminar");
            assertFalse(Thread.currentThread().isInterrupted(),
                "flag de interrupção deve continuar inativo");
        } finally {
            processo.destroyForcibly();
        }
    }

    @Test
    @EnabledOnOs(OS.LINUX)
    void killProcessEmProcessoJaFinalizadoRetornaFalso(@TempDir Path tempDir) throws Exception {
        ProfileManager manager = new ProfileManager(tempDir.resolve("perfis-finalizado").toString());
        Process processo = new ProcessBuilder("sleep", "60").start();
        processo.destroyForcibly();
        processo.waitFor();

        assertFalse(manager.killProcess(processo.pid()),
            "processo já encerrado não pode reportar kill bem-sucedido");
    }

    @Test
    @Timeout(20)
    @EnabledOnOs(OS.LINUX)
    void killProcessComTrapIgnoradoDependeDoEncerramentoForcado(@TempDir Path tempDir) throws Exception {
        ProfileManager manager = new ProfileManager(tempDir.resolve("perfis-trap").toString());
        Process processo = new ProcessBuilder("bash", "-c",
                "trap '' TERM; echo pronto; while true; do sleep 1; done").start();
        try {
            // bloqueia até o trap estar instalado (linha escrita no stdout)
            try (BufferedReader leitor = new BufferedReader(
                    new InputStreamReader(processo.getInputStream(), StandardCharsets.UTF_8))) {
                assertEquals("pronto", leitor.readLine(),
                    "trap precisa estar instalado antes de chamar killProcess");
            }

            long inicio = System.nanoTime();
            assertTrue(manager.killProcess(processo.pid()),
                "SIGTERM ignorado exige cair no encerramento forçado e retornar true");
            long decorridoMs = (System.nanoTime() - inicio) / 1_000_000;

            assertTrue(decorridoMs >= 4_500,
                "janela graciosa de 5s deve ser respeitada antes do force (decorrido=" + decorridoMs + "ms)");
            assertFalse(processo.isAlive(), "processo deve terminar após o encerramento forçado");
        } finally {
            processo.descendants().forEach(ProcessHandle::destroyForcibly);
            processo.destroyForcibly();
        }
    }
}
