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

package io.github.selenium.javaDriverless.types;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import io.github.selenium.javaDriverless.logging.JavaDriverlessLogger;

abstract class ChromeOptionsArgumentOptions extends ChromeOptionsState {
    /**
     * Retorna os argumentos usados para o executável do Chrome.
     *
     * @return lista de argumentos
     */
    public List<String> getArguments() {
        return new ArrayList<>(arguments);
    }

    /**
     * Adiciona um argumento para iniciar o Chrome.
     *
     * @param argument argumento a adicionar
     */
    public void addArgument(String argument) {
        if (argument == null || argument.isEmpty()) {
            throw new IllegalArgumentException("Argumento não pode ser nulo ou vazio");
        }

        if (argument.startsWith("--lang=")) {
            argument = replaceSingleValueArgument(argument, "--lang=", "Idioma");
        } else if (argument.startsWith("--accept-lang=")) {
            argument = replaceSingleValueArgument(argument, "--accept-lang=", "Accept-Language");
        } else if (argument.startsWith("--user-agent=")) {
            String userAgent = argument.substring("--user-agent=".length()).trim();
            if (userAgent.isEmpty()) {
                throw new IllegalArgumentException("User-Agent não pode ser vazio");
            }
            this.arguments.removeIf(value -> value.startsWith("--user-agent="));
            argument = "--user-agent=" + userAgent;
        } else if (argument.startsWith("--webrtc-ip-handling-policy=")) {
            String policy = argument.substring("--webrtc-ip-handling-policy=".length()).trim();
            if (policy.isEmpty()) {
                throw new IllegalArgumentException("Politica WebRTC nao pode ser vazia");
            }
            this.arguments.removeIf(value -> value.startsWith("--webrtc-ip-handling-policy=")
                    || value.startsWith("--force-webrtc-ip-handling-policy="));
            argument = "--webrtc-ip-handling-policy=" + policy;
        } else if (argument.startsWith("--user-data-dir=")) {
            String userDataDir = argument.substring(16);
            ChromeOptionsDirectories.ensureDirectory(userDataDir);
            this.userDataDir = userDataDir;
        } else if (argument.startsWith("--remote-debugging-port=")) {
            int port = Integer.parseInt(argument.substring(24));
            if (this.debuggerAddress == null) {
                this.debuggerAddress = "127.0.0.1:" + port;
            }
            this.isRemote = false;
        } else if (argument.startsWith("--load-extension=")) {
            String[] extensions = argument.substring(17).split(",");
            this.extensionPaths.addAll(Arrays.asList(extensions));
            return;
        } else if (argument.startsWith("--headless")) {
            this.headless = true;
            if (argument.length() <= 10 || !argument.substring(11).equals("new")) {
                JavaDriverlessLogger.warn(logger,
                    "Headless sem '--headless=new' pode ser instável, detectável e quebrar proxies");
            }
        }

        this.arguments.add(argument);
    }

    private String replaceSingleValueArgument(String argument, String prefix, String label) {
        String value = argument.substring(prefix.length()).trim();
        if (value.isEmpty()) {
            throw new IllegalArgumentException(label + " não pode ser vazio");
        }
        this.arguments.removeIf(existing -> existing.startsWith(prefix));
        return prefix + value;
    }

    /**
     * Retorna o User-Agent explícito desta instância de opções, se configurado.
     */
    public String getUserAgent() {
        for (int index = arguments.size() - 1; index >= 0; index--) {
            String argument = arguments.get(index);
            if (argument.startsWith("--user-agent=")) {
                return argument.substring("--user-agent=".length());
            }
        }
        return null;
    }

    /**
     * Define o User-Agent somente para as sessões criadas com estas opções.
     */
    public void setUserAgent(String userAgent) {
        if (userAgent == null || userAgent.isBlank()) {
            throw new IllegalArgumentException("User-Agent não pode ser nulo ou vazio");
        }
        addArgument("--user-agent=" + userAgent.trim());
    }

    /**
     * Adiciona múltiplos argumentos.
     *
     * @param arguments argumentos a adicionar
     */
    public void addArguments(String... arguments) {
        for (String arg : arguments) {
            addArgument(arg);
        }
    }

    /**
     * Indica se o Chrome inicia em modo headless.
     * Padrão é {@code false}.
     *
     * @return true se headless
     */
    public boolean isHeadless() {
        return headless;
    }

    /**
     * Define o modo headless.
     *
     * @param value true para ativar headless
     */
    public void setHeadless(boolean value) {
        if (!value && this.headless) {
            throw new UnsupportedOperationException(
                "Definir headless=true não pode ser desfeito nas opções no momento"
            );
        }
        if (value) {
            if (!this.arguments.contains("--headless=new")) {
                addArgument("--headless=new");
            }
        }
    }
}
