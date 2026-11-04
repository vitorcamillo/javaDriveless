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

abstract class ChromeOptionsProxyOptions extends ChromeOptionsDirectoryOptions {
    /**
     * Define um único proxy a ser aplicado.
     * <p>
     * <strong>Nota:</strong> Apenas proxies sem autenticação são suportados.
     * Use formato: "http://host:port/" ou "socks5://host:port/"
     * </p>
     *
     * @return URL do proxy
     */
    public String getSingleProxy() {
        return proxySettings.getSingleProxy();
    }

    /**
     * Define o proxy único.
     * <p>
     * <strong>Nota:</strong> Proxies com autenticação não são suportados.
     * Use apenas formato: "http://host:port/" ou "socks5://host:port/"
     * </p>
     *
     * @param proxy URL do proxy (ex: "http://proxy.com:5001/" ou "socks5://proxy.com:1080/")
     */
    public void setSingleProxy(String proxy) {
        proxySettings.setSingleProxy(proxy, logger);
    }

    /**
     * Habilita uma ponte local isolada para proxies HTTP autenticados.
     * <p>
     * Quando ativo e o proxy tiver usuário/senha, o Chrome recebe apenas um
     * proxy local sem autenticação em 127.0.0.1. A autenticação no upstream é
     * feita fora do processo do navegador.
     * </p>
     */
    public void enableAuthenticatedProxyBridge(boolean enabled) {
        proxySettings.setAuthenticatedProxyBridgeEnabled(enabled);
    }

    public boolean isAuthenticatedProxyBridgeEnabled() {
        return proxySettings.isAuthenticatedProxyBridgeEnabled();
    }

    public void setAuthenticatedProxyBridge(String proxyUrl) {
        setProxyConfig(ProxyConfig.fromUrl(proxyUrl));
        enableAuthenticatedProxyBridge(true);
    }

    /**
     * Define a configuração de proxy.
     *
     * @param proxyConfig configuração do proxy
     */
    public void setProxyConfig(ProxyConfig proxyConfig) {
        proxySettings.setProxyConfig(proxyConfig);
    }

    /**
     * Retorna a configuração de proxy.
     *
     * @return configuração do proxy ou null se não configurado
     */
    public ProxyConfig getProxyConfig() {
        return proxySettings.getProxyConfig();
    }

    /**
     * Configura proxy a partir de uma string no formato host:port:username:password
     *
     * @param proxyString string do proxy
     * @param type tipo do proxy (HTTP, HTTPS, SOCKS5)
     */
    public void setProxyFromString(String proxyString, ProxyConfig.ProxyType type) {
        proxySettings.setProxyFromString(proxyString, type);
    }

    /**
     * Configura proxy HTTP a partir de uma string no formato host:port:username:password
     *
     * @param proxyString string do proxy
     */
    public void setHttpProxy(String proxyString) {
        setProxyFromString(proxyString, ProxyConfig.ProxyType.HTTP);
    }

    /**
     * Configura proxy HTTPS a partir de uma string no formato host:port:username:password
     *
     * @param proxyString string do proxy
     */
    public void setHttpsProxy(String proxyString) {
        setProxyFromString(proxyString, ProxyConfig.ProxyType.HTTPS);
    }

    /**
     * Configura proxy SOCKS5 a partir de uma string no formato host:port:username:password
     *
     * @param proxyString string do proxy
     */
    public void setSocks5Proxy(String proxyString) {
        setProxyFromString(proxyString, ProxyConfig.ProxyType.SOCKS5);
    }
}
