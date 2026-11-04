package io.github.selenium.javaDriverless;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ChromePipeLaunchTest {
    private static final Logger LOGGER = LoggerFactory.getLogger(ChromePipeLaunchTest.class);

    @Test
    void wrapsChromeCommandForPipeFileDescriptors() {
        assertThat(ChromePipeLaunch.wrapCommand(List.of("/bin/chrome", "--a")))
                .containsExactly("/bin/sh", "-c",
                        "exec \"$0\" \"$@\" 3<&0 4>&1 0</dev/null 1>&2", "/bin/chrome", "--a");
        assertThatThrownBy(() -> ChromePipeLaunch.wrapCommand(List.of()))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> ChromePipeLaunch.wrapCommand(null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void resolvesPipeAvailability() {
        assertThat(ChromePipeLaunch.resolvePipe(false, false, null, false, "Linux", LOGGER)).isFalse();
        assertThat(ChromePipeLaunch.resolvePipe(true, true, null, false, "Linux", LOGGER)).isFalse();
        assertThat(ChromePipeLaunch.resolvePipe(true, false, "127.0.0.1:9222", false, "Linux", LOGGER)).isFalse();
        assertThat(ChromePipeLaunch.resolvePipe(true, false, null, true, "Linux", LOGGER)).isFalse();
        assertThat(ChromePipeLaunch.resolvePipe(true, false, null, false, "Windows 11", LOGGER)).isFalse();
        assertThat(ChromePipeLaunch.resolvePipe(true, false, "", false, "Linux", LOGGER)).isTrue();
    }

    @Test
    void requireExecutableRejectsNullAndEmpty() {
        assertThatThrownBy(() -> ChromePipeLaunch.requireExecutable(null))
                .isInstanceOf(java.io.IOException.class)
                .hasMessage("Binário do Chrome ausente ou não executável");
        assertThatThrownBy(() -> ChromePipeLaunch.requireExecutable(""))
                .isInstanceOf(java.io.IOException.class)
                .hasMessage("Binário do Chrome ausente ou não executável");
    }

    @Test
    void requireExecutableRejectsMissingFileAndDirectory(@TempDir Path tempDir) {
        String missing = tempDir.resolve("inexistente").toString();
        assertThatThrownBy(() -> ChromePipeLaunch.requireExecutable(missing))
                .isInstanceOf(java.io.IOException.class)
                .hasMessage("Binário do Chrome ausente ou não executável")
                .hasMessageNotContaining(missing);
        assertThatThrownBy(() -> ChromePipeLaunch.requireExecutable(tempDir.toString()))
                .isInstanceOf(java.io.IOException.class)
                .hasMessage("Binário do Chrome ausente ou não executável");
    }

    @Test
    void requireExecutableChecksPermissionBits(@TempDir Path tempDir) throws Exception {
        Assumptions.assumeTrue(!System.getProperty("os.name").toLowerCase().startsWith("windows"));
        Path file = Files.createFile(tempDir.resolve("chrome-fake"));
        File asFile = file.toFile();
        asFile.setExecutable(false);
        assertThatThrownBy(() -> ChromePipeLaunch.requireExecutable(file.toString()))
                .isInstanceOf(java.io.IOException.class)
                .hasMessage("Binário do Chrome ausente ou não executável");
        asFile.setExecutable(true);
        assertThatCode(() -> ChromePipeLaunch.requireExecutable(file.toString()))
                .doesNotThrowAnyException();
    }

    @Test
    void requireExecutableRejectsMissingBareCommandName() {
        // Nome puro ausente no PATH precisa falhar antes do launch com a IOException prometida.
        assertThatThrownBy(() -> ChromePipeLaunch.requireExecutable("chrome-inexistente"))
                .isInstanceOf(java.io.IOException.class)
                .hasMessage("Binário do Chrome ausente ou não executável");
    }

    @Test
    @EnabledOnOs({OS.LINUX, OS.MAC})
    void requireExecutableAcceptsExistingSystemCommandName() {
        assertThatCode(() -> ChromePipeLaunch.requireExecutable("sh"))
                .doesNotThrowAnyException();
    }

    @Test
    @EnabledOnOs({OS.LINUX, OS.MAC})
    void requireExecutableResolvesBareCommandNameThroughSearchPath(@TempDir Path tempDir) throws Exception {
        Path binDir = Files.createDirectories(tempDir.resolve("bin"));
        Path executable = Files.createFile(binDir.resolve("chrome-falso"));
        executable.toFile().setExecutable(true);
        Path emptyDir = Files.createDirectories(tempDir.resolve("vazio"));
        String searchPath = binDir + File.pathSeparator + emptyDir;

        // Nome puro resolvido no searchPath informado passa.
        assertThatCode(() -> ChromePipeLaunch.requireExecutable("chrome-falso", searchPath))
                .doesNotThrowAnyException();
        // Mesmo nome com searchPath sem o binário falha com IOException, sem ecoar caminho.
        assertThatThrownBy(() -> ChromePipeLaunch.requireExecutable("chrome-falso", emptyDir.toString()))
                .isInstanceOf(java.io.IOException.class)
                .hasMessage("Binário do Chrome ausente ou não executável")
                .hasMessageNotContaining(tempDir.toString());
        // searchPath ausente é fail-closed; PATH="" (diretório atual) sem o binário ali também falha.
        assertThatThrownBy(() -> ChromePipeLaunch.requireExecutable("chrome-falso", null))
                .isInstanceOf(java.io.IOException.class)
                .hasMessage("Binário do Chrome ausente ou não executável");
        assertThatThrownBy(() -> ChromePipeLaunch.requireExecutable("chrome-falso", ""))
                .isInstanceOf(java.io.IOException.class)
                .hasMessage("Binário do Chrome ausente ou não executável");
    }

    @Test
    @EnabledOnOs({OS.LINUX, OS.MAC})
    void requireExecutableResolvesBareCommandNameFromTrailingEmptyPathEntry() throws Exception {
        // PATH terminado em separador inclui o diretório atual para o shell;
        // split sem limite descartaria a entrada vazia final e recusaria o binário.
        String name = "chrome-falso-" + System.nanoTime();
        Path executable = Files.createFile(Paths.get(".", name));
        executable.toFile().setExecutable(true);
        try {
            assertThatCode(() -> ChromePipeLaunch.requireExecutable(name,
                    "/diretorio-inexistente" + File.pathSeparator))
                    .doesNotThrowAnyException();
        } finally {
            Files.deleteIfExists(executable);
        }
    }

    @Test
    @EnabledOnOs({OS.LINUX, OS.MAC})
    void requireExecutableResolvesBareCommandNameFromEntirelyEmptyPath() throws Exception {
        // PATH inteiramente vazio é uma única entrada nula: o diretório atual, como no shell.
        String name = "chrome-falso-" + System.nanoTime();
        Path executable = Files.createFile(Paths.get(".", name));
        executable.toFile().setExecutable(true);
        try {
            assertThatCode(() -> ChromePipeLaunch.requireExecutable(name, ""))
                    .doesNotThrowAnyException();
        } finally {
            Files.deleteIfExists(executable);
        }
    }

    @Test
    @EnabledOnOs({OS.LINUX, OS.MAC})
    void wrapperMapsProcessStdinToFd3AndFd4ToStdout() throws Exception {
        List<String> command = ChromePipeLaunch.wrapCommand(List.of("/bin/sh", "-c", "cat <&3 >&4"));
        Process process = new ProcessBuilder(command).start();
        try {
            process.getOutputStream().write("abc".getBytes(StandardCharsets.UTF_8));
            process.getOutputStream().close();
            assertThat(process.waitFor(5, TimeUnit.SECONDS)).isTrue();
            assertThat(new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8)).isEqualTo("abc");
        } finally {
            process.destroyForcibly();
        }
    }
}
