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
//
// Modificado por kaliiiiiiiiii | Aurin Aegerter
// Conversão para Java: Java Driverless
// Todas as modificações são licenciadas sob a licença fornecida em LICENSE.md

package io.github.selenium.javaDriverless.types;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Classe webdriver.ChromeOptions
 * <p>
 * <strong>Aviso:</strong> as opções não devem ser reutilizadas
 * </p>
 */
abstract class ChromeOptionsMutableFields extends ChromeOptionsFingerprintOptions {
    abstract void requireCatalogMutationAllowed();
    abstract void transferManagedWebRtcOwnership(String pref, Object value);

    public synchronized void setUserAgent(String userAgent) {
        requireCatalogMutationAllowed();
        super.setUserAgent(userAgent);
    }

    @Override
    public Map<String, Object> getPrefs() { return super.getPrefs(); }

    @Override
    public synchronized void updatePref(String pref, Object value) {
        requireCatalogMutationAllowed();
        transferManagedWebRtcOwnership(pref, value);
        super.updatePref(pref, value);
    }

    @Override
    public String getUserDataDir() { return super.getUserDataDir(); }

    @Override
    public synchronized void setUserDataDir(String dir) {
        requireCatalogMutationAllowed();
        super.setUserDataDir(dir);
    }

    @Override
    public String getDownloadsDir() { return super.getDownloadsDir(); }

    @Override
    public synchronized void setDownloadsDir(String directoryPath) {
        requireCatalogMutationAllowed();
        super.setDownloadsDir(directoryPath);
    }

    /**
     * Configura paths ja validados por um catalogo sem cria-los antes da aquisicao do lease.
     * O startup protegido cria os diretorios somente depois de bloquear todos os ancestrais.
     */
    public synchronized void setDeferredCatalogStorage(String userDataPath, String downloadsPath) {
        requireCatalogMutationAllowed();
        arguments.removeIf(value -> value.startsWith("--user-data-dir="));
        userDataDir = userDataPath;
        if (userDataPath != null) arguments.add("--user-data-dir=" + userDataPath);
        downloadsDir = downloadsPath;
    }

    @Override
    public boolean isHeadless() { return super.isHeadless(); }

    @Override
    public synchronized void setHeadless(boolean value) {
        requireCatalogMutationAllowed();
        super.setHeadless(value);
    }

    @Override
    public String getStartupUrl() { return super.getStartupUrl(); }

    @Override
    public synchronized void setStartupUrl(String url) {
        requireCatalogMutationAllowed();
        super.setStartupUrl(url);
    }

    @Override
    public String getSingleProxy() { return super.getSingleProxy(); }

    @Override
    public synchronized void setSingleProxy(String proxy) {
        requireCatalogMutationAllowed();
        super.setSingleProxy(proxy);
    }

    @Override
    public synchronized void enableAuthenticatedProxyBridge(boolean enabled) {
        requireCatalogMutationAllowed();
        super.enableAuthenticatedProxyBridge(enabled);
    }

    @Override
    public boolean isAuthenticatedProxyBridgeEnabled() { return super.isAuthenticatedProxyBridgeEnabled(); }

    @Override
    public synchronized void setAuthenticatedProxyBridge(String proxyUrl) {
        requireCatalogMutationAllowed();
        super.setAuthenticatedProxyBridge(proxyUrl);
    }

    @Override
    public synchronized void setProxyConfig(ProxyConfig proxyConfig) {
        requireCatalogMutationAllowed();
        super.setProxyConfig(proxyConfig);
    }

    @Override
    public ProxyConfig getProxyConfig() { return super.getProxyConfig(); }

    @Override
    public synchronized void setProxyFromString(String proxyString, ProxyConfig.ProxyType type) {
        requireCatalogMutationAllowed();
        super.setProxyFromString(proxyString, type);
    }

    @Override
    public synchronized void setHttpProxy(String proxyString) {
        requireCatalogMutationAllowed();
        super.setHttpProxy(proxyString);
    }

    @Override
    public synchronized void setHttpsProxy(String proxyString) {
        requireCatalogMutationAllowed();
        super.setHttpsProxy(proxyString);
    }

    @Override
    public synchronized void setSocks5Proxy(String proxyString) {
        requireCatalogMutationAllowed();
        super.setSocks5Proxy(proxyString);
    }

    @Override
    public String getBinaryLocation() { return super.getBinaryLocation(); }

    @Override
    public synchronized void setBinaryLocation(String value) {
        requireCatalogMutationAllowed();
        super.setBinaryLocation(value);
    }

    @Override
    public Map<String, String> getEnv() { return super.getEnv(); }

    @Override
    public synchronized void setEnv(Map<String, String> env) {
        requireCatalogMutationAllowed();
        super.setEnv(env);
    }

    @Override
    public synchronized void addExtension(String path) {
        requireCatalogMutationAllowed();
        super.addExtension(path);
    }

    @Override
    public String getDebuggerAddress() { return super.getDebuggerAddress(); }

    @Override
    public synchronized void setDebuggerAddress(String value) {
        requireCatalogMutationAllowed();
        super.setDebuggerAddress(value);
    }

    @Override
    public CdpTransport getCdpTransport() { return super.getCdpTransport(); }

    @Override
    public synchronized void setCdpTransport(CdpTransport transport) {
        requireCatalogMutationAllowed();
        super.setCdpTransport(transport);
    }

    @Override
    public boolean isAutoCleanDirs() { return super.isAutoCleanDirs(); }

    @Override
    public synchronized void setAutoCleanDirs(boolean enabled) {
        requireCatalogMutationAllowed();
        super.setAutoCleanDirs(enabled);
    }

    @Override
    public synchronized void enableMobile(String androidPackage, String androidActivity, String deviceSerial) {
        requireCatalogMutationAllowed();
        super.enableMobile(androidPackage, androidActivity, deviceSerial);
    }

    @Override
    public synchronized void ignoreLocalProxyEnvironmentVariables() {
        requireCatalogMutationAllowed();
        super.ignoreLocalProxyEnvironmentVariables();
    }

    @Deprecated
    @Override
    public synchronized void addExperimentalOption(String name, Object value) {
        requireCatalogMutationAllowed();
        if ("prefs".equals(name) && value instanceof Map<?, ?> map) {
            map.forEach((pref, prefValue) -> {
                if (pref instanceof String key) transferManagedWebRtcOwnership(key, prefValue);
            });
        }
        super.addExperimentalOption(name, value);
    }

    @Override
    public List<String> getExtensionPaths() { return super.getExtensionPaths(); }

    @Override
    public boolean isRemote() { return super.isRemote(); }

    @Override
    public BrowserFingerprintProfile getFingerprintProfile() { return super.getFingerprintProfile(); }

    @Override
    public boolean isFingerprintEnabled() { return super.isFingerprintEnabled(); }

    @Override
    public synchronized void setFingerprintEnabled(boolean enabled) {
        requireCatalogMutationAllowed();
        super.setFingerprintEnabled(enabled);
    }

    @Override
    public synchronized void setFingerprintProfile(BrowserFingerprintProfile fingerprintProfile) {
        requireCatalogMutationAllowed();
        super.setFingerprintProfile(fingerprintProfile);
    }

    @Override
    public synchronized void setEgressInfo(EgressInfo egressInfo) {
        requireCatalogMutationAllowed();
        super.setEgressInfo(egressInfo);
    }

    @Override
    public synchronized void setEgressResolver(EgressResolver egressResolver) {
        requireCatalogMutationAllowed();
        super.setEgressResolver(egressResolver);
    }

    @Override
    public synchronized void setSessionProfile(BrowserSessionProfile sessionProfile) {
        requireCatalogMutationAllowed();
        super.setSessionProfile(sessionProfile);
    }}
