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

abstract class ChromeOptionsFingerprintOptions extends ChromeOptionsRuntimeOptions {

    private EgressInfo egressInfo;
    private EgressResolver egressResolver;

    /**
     * Indica se o launch gerenciado deve aplicar argumentos, overrides CDP e scripts de
     * fingerprint. O padrão é {@code true}. Desabilitar existe para baselines diagnósticos; uma
     * sessão de produto não deve ser publicada como anti-detect com esta opção desligada.
     */
    public boolean isFingerprintEnabled() {
        return fingerprintEnabled;
    }

    /**
     * Habilita ou desabilita toda a camada de fingerprint no launch gerenciado.
     *
     * <p>Desligar troca os argumentos e prefs PADRAO do construtor pelo minimo de diagnostico; o
     * que o chamador ja adicionou (proxy, sandbox, headless, prefs) e preservado, em qualquer ordem
     * de chamada.</p>
     *
     * @param enabled {@code false} somente para Chrome-controle/baseline
     */
    public void setFingerprintEnabled(boolean enabled) {
        if (!enabled && fingerprintEnabled) {
            ChromeOptionsDefaults.useDiagnosticBaselineDefaults(this);
        }
        this.fingerprintEnabled = enabled;
    }

    /**
     * Geo do IP de egress aplicado ao fingerprint no início da sessão (timezone, idioma,
     * geolocalização e WebRTC passam a seguir o IP que o site enxerga). Tem precedência sobre o
     * {@link #getEgressResolver() resolver}. Quando ausente, a engine mantém os defaults coerentes
     * por locale.
     *
     * @return egress configurado ou {@code null}
     */
    public EgressInfo getEgressInfo() {
        return egressInfo == null ? null : egressInfo.copy();
    }

    /** @param egressInfo geo do IP de saída (ex.: de um proxy-check) ou {@code null} */
    public void setEgressInfo(EgressInfo egressInfo) {
        this.egressInfo = egressInfo == null ? null : egressInfo.copy();
    }

    /**
     * Resolvedor plugável do {@link EgressInfo} a partir do proxy — alternativa ao
     * {@link #setEgressInfo} para derivar o egress sob demanda (base geo-IP offline, provider HTTP,
     * dado próprio do consumidor, etc.). Só é consultado quando não há EgressInfo explícito.
     *
     * @return resolver configurado ou {@code null}
     */
    public EgressResolver getEgressResolver() {
        return egressResolver;
    }

    /** @param egressResolver resolvedor de egress a partir do proxy, ou {@code null} */
    public void setEgressResolver(EgressResolver egressResolver) {
        this.egressResolver = egressResolver;
    }

    /**
     * Retorna o perfil de fingerprint usado pelos overrides CDP/scripts.
     * Se nenhum perfil foi definido explicitamente, usa variáveis de ambiente
     * e defaults coerentes.
     *
     * @return perfil de fingerprint
     */
    public BrowserFingerprintProfile getFingerprintProfile() {
        if (fingerprintProfile == null) {
            fingerprintProfile = BrowserFingerprintProfile.fromEnvironment();
        }
        fingerprintProfile.applyDefaults();
        return fingerprintProfile.copy();
    }

    /**
     * Define perfil de fingerprint estruturado.
     *
     * @param fingerprintProfile perfil de fingerprint
     */
    public void setFingerprintProfile(BrowserFingerprintProfile fingerprintProfile) {
        BrowserFingerprintProfile isolatedProfile = null;
        if (fingerprintProfile != null) {
            isolatedProfile = fingerprintProfile.copy();
            isolatedProfile.applyDefaults();
        }
        this.fingerprintProfile = isolatedProfile;
    }

    /**
     * Applies the complete PhantomPass session contract in one operation.
     *
     * @param sessionProfile profile containing fingerprint, proxy, DNS and permissions
     */
    public void setSessionProfile(BrowserSessionProfile sessionProfile) {
        BrowserSessionProfile isolatedProfile = sessionProfile == null ? null : sessionProfile.copy();
        ChromeOptionsSessionProfiles.apply(this, isolatedProfile);
        this.sessionProfile = isolatedProfile;
    }

    /**
     * Returns the complete session contract when one was supplied explicitly.
     *
     * @return structured session profile or null when legacy setters were used
     */
    public BrowserSessionProfile getSessionProfile() {
        return sessionProfile == null ? null : sessionProfile.copy();
    }
}
