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

import java.io.File;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import io.github.selenium.javaDriverless.utils.Utils;

abstract class ChromeOptionsRuntimeOptions extends ChromeOptionsProxyOptions {
    protected CdpTransport cdpTransport = CdpTransport.fromSystemProperty();

    /**
     * Retorna a URL que a primeira aba carrega.
     * Padrão é {@code about:blank}.
     *
     * @return URL de inicialização
     */
    public String getStartupUrl() {
        return startupUrl;
    }

    /**
     * Define a URL de inicialização.
     *
     * @param url URL para carregar
     */
    public void setStartupUrl(String url) {
        this.startupUrl = (url != null) ? url : "";
    }

    /**
     * Retorna o caminho para o binário do Chromium.
     *
     * @return caminho do executável
     */
    public String getBinaryLocation() {
        if (binaryLocation == null) {
            binaryLocation = Utils.findChromeExecutable();
        }
        return binaryLocation;
    }

    /**
     * Define o caminho do binário do Chrome.
     *
     * @param value caminho do executável
     */
    public void setBinaryLocation(String value) {
        this.binaryLocation = value;
    }

    /**
     * Retorna o ambiente explícito que será usado pelo processo Chrome. O padrão contém
     * somente variáveis necessárias para compatibilidade gráfica, regional e de runtime.
     *
     * @return variáveis de ambiente
     */
    public Map<String, String> getEnv() {
        return env == null ? new HashMap<>() : new HashMap<>(env);
    }

    /**
     * Substitui integralmente o ambiente do Chrome por um mapa explicitamente confiado pelo
     * chamador. Use {@link #getEnv()} como base quando quiser apenas acrescentar uma variável.
     * Valores {@code null} produzem um ambiente vazio.
     *
     * @param env mapa de variáveis
     */
    public void setEnv(Map<String, String> env) {
        this.env = env == null ? new HashMap<>() : new HashMap<>(env);
    }

    /**
     * Adiciona uma extensão ao Chrome.
     * A extensão pode ser um arquivo compactado (zip, crx, etc.) ou extraído em um diretório.
     * <p>
     * <strong>Nota:</strong> Extensões não são mais usadas para autenticação de proxy.
     * </p>
     *
     * @param path caminho para a extensão
     */
    public void addExtension(String path) {
        File extension = new File(path);
        if (!extension.exists()) {
            throw new IllegalArgumentException("Caminho para a extensão não existe: " + path);
        }
        this.extensionPaths.add(extension.getAbsolutePath());
    }

    /**
     * Retorna o endereço da instância remota do devtools no formato "host:port".
     * Definir este valor faz o driver conectar a uma instância remota do navegador.
     *
     * @return endereço do debugger
     */
    public String getDebuggerAddress() {
        return debuggerAddress;
    }

    /**
     * Define o endereço do debugger.
     *
     * @param value endereço no formato "host:port"
     */
    public void setDebuggerAddress(String value) {
        this.debuggerAddress = value;
        this.isRemote = value != null && !value.isBlank();
    }

    /** Retorna o transporte CDP usado quando a lib lança o Chrome. */
    public CdpTransport getCdpTransport() {
        return cdpTransport;
    }

    /** Define o transporte CDP usado quando a lib lança o Chrome.
     *
     * @param transport transporte CDP
     */
    public void setCdpTransport(CdpTransport transport) {
        if (transport == null) {
            throw new IllegalArgumentException("Transporte CDP não pode ser nulo");
        }
        this.cdpTransport = transport;
    }

    /**
     * Indica se o user-data-dir deve ser limpo automaticamente.
     * Padrão é true.
     *
     * @return true se deve limpar automaticamente
     */
    public boolean isAutoCleanDirs() {
        return autoCleanDirs;
    }

    /**
     * Define se deve limpar diretórios automaticamente.
     *
     * @param enabled true para ativar
     */
    public void setAutoCleanDirs(boolean enabled) {
        this.autoCleanDirs = enabled;
    }

    /**
     * Habilita o uso de navegador móvel para navegadores que suportam.
     * <p>
     * <strong>Aviso:</strong> Não implementado ainda
     * </p>
     *
     * @param androidPackage nome do pacote Android
     * @param androidActivity atividade Android
     * @param deviceSerial serial do dispositivo
     */
    public void enableMobile(String androidPackage, String androidActivity, String deviceSerial) {
        throw new UnsupportedOperationException("Não implementado ainda");
    }

    /**
     * Remove variáveis locais de proxy, sem diferenciar maiúsculas de minúsculas.
     * Abrange HTTP(S), ALL, FTP e NO_PROXY para que a sessão use somente a
     * configuração de proxy fornecida pelas opções do Chrome.
     */
    public void ignoreLocalProxyEnvironmentVariables() {
        ChromeOptionsEnvironment.removeProxyVariables(env);
    }

    /**
     * Retorna a lista de caminhos de extensões.
     *
     * @return lista de caminhos
     */
    public List<String> getExtensionPaths() {
        return new ArrayList<>(extensionPaths);
    }

    /**
     * Indica se está configurado como remoto.
     *
     * @return true se remoto
     */
    public boolean isRemote() {
        return isRemote;
    }
}
