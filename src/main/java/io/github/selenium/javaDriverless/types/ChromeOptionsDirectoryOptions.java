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

abstract class ChromeOptionsDirectoryOptions extends ChromeOptionsPreferenceOptions {
    /**
     * Retorna o diretório para salvar todos os dados do navegador.
     * {@code null} (padrão) criará temporariamente um diretório em $temp.
     *
     * @return caminho do diretório de dados do usuário
     */
    public String getUserDataDir() {
        return userDataDir;
    }

    /**
     * Define o diretório de dados do usuário.
     *
     * @param dir caminho do diretório
     */
    public void setUserDataDir(String dir) {
        this.userDataDir = dir;
        if (dir != null) {
            addArgument("--user-data-dir=" + dir);
        }
    }

    /**
     * Retorna o diretório padrão para baixar arquivos.
     * <p>
     * <strong>Aviso:</strong> o caminho deve ser absoluto
     * </p>
     *
     * @return caminho do diretório de downloads
     */
    public String getDownloadsDir() {
        return downloadsDir;
    }

    /**
     * Define o diretório de downloads.
     *
     * @param directoryPath caminho do diretório
     */
    public void setDownloadsDir(String directoryPath) {
        this.downloadsDir = ChromeOptionsDirectories.resolveDownloadsDir(directoryPath);
    }
}
