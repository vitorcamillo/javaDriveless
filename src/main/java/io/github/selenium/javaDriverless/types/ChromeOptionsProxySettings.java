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

import io.github.selenium.javaDriverless.logging.JavaDriverlessLogger;
import org.slf4j.Logger;

final class ChromeOptionsProxySettings {
    private String singleProxy;
    private ProxyConfig proxyConfig;
    private boolean authenticatedProxyBridgeEnabled;

    String getSingleProxy() {
        return singleProxy;
    }

    void setSingleProxy(String proxy, Logger logger) {
        this.singleProxy = proxy;
        if (proxy != null) {
            try {
                this.proxyConfig = ProxyConfig.fromUrl(proxy);
            } catch (IllegalArgumentException e) {
                JavaDriverlessLogger.warn(logger, "Não foi possível fazer parse do proxy: {}", e.getMessage());
            }
        }
    }

    void setProxyConfig(ProxyConfig proxyConfig) {
        this.proxyConfig = proxyConfig == null ? null : proxyConfig.copy();
        this.singleProxy = this.proxyConfig == null ? null : this.proxyConfig.toUrl();
    }

    ProxyConfig getProxyConfig() {
        return proxyConfig == null ? null : proxyConfig.copy();
    }

    boolean isAuthenticatedProxyBridgeEnabled() {
        return authenticatedProxyBridgeEnabled;
    }

    void setAuthenticatedProxyBridgeEnabled(boolean authenticatedProxyBridgeEnabled) {
        this.authenticatedProxyBridgeEnabled = authenticatedProxyBridgeEnabled;
    }

    void setProxyFromString(String proxyString, ProxyConfig.ProxyType type) {
        try {
            ProxyConfig config = ProxyConfig.fromString(proxyString, type);
            setProxyConfig(config);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("Formato de proxy inválido: " + e.getMessage());
        }
    }
}
