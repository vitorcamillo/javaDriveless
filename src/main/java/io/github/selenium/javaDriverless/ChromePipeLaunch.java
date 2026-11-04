// Licensed to the Software Freedom Conservancy (SFC) under one
// or more contributor license agreements.  See the NOTICE file
// distributed with this work for additional information
// regarding copyright ownership.  The SFC licenses this file
// to you under the Apache License, Version 2.0 (the
// "License"); you may not use this file except in compliance
// with the License.  You may obtain a copy of the License at
//
//   http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing,
// software distributed under the License is distributed on an
// "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
// KIND, either express or implied.  See the License for the
// specific language governing permissions and limitations
// under the License.

package io.github.selenium.javaDriverless;

import io.github.selenium.javaDriverless.logging.JavaDriverlessLogger;
import org.slf4j.Logger;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

final class ChromePipeLaunch {
    static final String PIPE_ARGUMENT = "--remote-debugging-pipe";

    private ChromePipeLaunch() {
    }

    /**
     * Decide se o pipe pode ser usado. Devolve false quando requested e: attachMode,
     * debuggerAddress configurado, ou --remote-debugging-port explícito (log em DEBUG);
     * ou SO Windows (osName começa com "windows", case-insensitive, log em WARN). Mensagem:
     * "CDP pipe indisponível ({motivo}); usando transporte TCP". requested=false → false sem log.
     */
    static boolean resolvePipe(boolean requested, boolean attachMode, String configuredDebuggerAddress,
                               boolean explicitPortArgument, String osName, Logger logger) {
        if (!requested) {
            return false;
        }

        if (attachMode) {
            JavaDriverlessLogger.debug(logger, "CDP pipe indisponível ({}); usando transporte TCP", "attach mode");
            return false;
        }
        if (configuredDebuggerAddress != null && !configuredDebuggerAddress.isEmpty()) {
            JavaDriverlessLogger.debug(logger, "CDP pipe indisponível ({}); usando transporte TCP", "debuggerAddress explícito");
            return false;
        }
        if (explicitPortArgument) {
            JavaDriverlessLogger.debug(logger, "CDP pipe indisponível ({}); usando transporte TCP", "--remote-debugging-port explícito");
            return false;
        }
        if (osName != null && osName.toLowerCase(Locale.ROOT).startsWith("windows")) {
            JavaDriverlessLogger.warn(logger, "CDP pipe indisponível ({}); usando transporte TCP", "Windows");
            return false;
        }
        return true;
    }

    /**
     * Garante falha com IOException (igual ao TCP) quando o binário sumiu; sem isso o
     * /bin/sh do pipe sairia 127 e a causa viraria RuntimeException. Mensagem sem caminho.
     */
    static void requireExecutable(String binary) throws IOException {
        requireExecutable(binary, System.getenv("PATH"));
    }

    // searchPath explícito: o env configurado para o Chrome substitui integralmente o
    // herdado (ChromeOptionsEnvironment), então o PATH aplicável pode divergir da JVM.
    static void requireExecutable(String binary, String searchPath) throws IOException {
        if (binary == null || binary.isEmpty()) {
            throw new IOException("Binário do Chrome ausente ou não executável");
        }
        if (binary.indexOf('/') >= 0 || binary.indexOf(File.separatorChar) >= 0) {
            if (!Files.isRegularFile(Paths.get(binary)) || !Files.isExecutable(Paths.get(binary))) {
                throw new IOException("Binário do Chrome ausente ou não executável");
            }
            return;
        }
        // Nome puro sem verificação deixaria o sh falhar depois com exit 127.
        // PATH="" é uma única entrada nula (diretório atual) para o shell, então só null falha aqui.
        if (searchPath == null) {
            throw new IOException("Binário do Chrome ausente ou não executável");
        }
        // Limite -1 preserva entradas vazias finais: PATH terminado em separador
        // inclui o diretório atual, como o shell que executará o comando.
        for (String dir : searchPath.split(File.pathSeparator, -1)) {
            // Entrada vazia no PATH significa diretório atual para o shell.
            Path candidate = Paths.get(dir.isEmpty() ? "." : dir, binary);
            if (Files.isRegularFile(candidate) && Files.isExecutable(candidate)) {
                return;
            }
        }
        throw new IOException("Binário do Chrome ausente ou não executável");
    }

    /**
     * Embrulha o comando do Chrome para remapear fds: fd3 (Chrome lê) = stdin do processo,
     * fd4 (Chrome escreve) = stdout do processo, stdin do Chrome = /dev/null, stdout do Chrome = stderr.
     */
    static List<String> wrapCommand(List<String> command) {
        if (command == null || command.isEmpty()) {
            throw new IllegalArgumentException("Comando do Chrome não pode ser nulo ou vazio");
        }
        List<String> wrapped = new ArrayList<>(command.size() + 3);
        wrapped.add("/bin/sh");
        wrapped.add("-c");
        wrapped.add("exec \"$0\" \"$@\" 3<&0 4>&1 0</dev/null 1>&2");
        wrapped.addAll(command);
        return List.copyOf(wrapped);
    }
}
