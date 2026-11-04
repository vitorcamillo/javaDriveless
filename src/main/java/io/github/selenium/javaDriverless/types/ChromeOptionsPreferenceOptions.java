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

import java.util.HashMap;
import java.util.Map;

abstract class ChromeOptionsPreferenceOptions extends ChromeOptionsArgumentOptions {
    /**
     * Retorna as preferências como JSON.
     *
     * @return mapa de preferências
     */
    public Map<String, Object> getPrefs() {
        return ChromeOptionsPrefs.deepCopyMap(prefs);
    }

    /**
     * Atualiza uma preferência.
     *
     * @param pref nome da preferência (caminho com ponto)
     * @param value valor para definir a preferência
     */
    public void updatePref(String pref, Object value) {
        ChromeOptionsPrefs.mergeDotPref(this.prefs, pref, value);
    }

    /**
     * Adiciona uma opção experimental que é passada ao Chromium.
     * <p>
     * <strong>Aviso:</strong> Apenas {@code name="prefs"} é suportado.
     * Este método está deprecated e será removido. Use {@link #updatePref} em vez disso.
     * </p>
     *
     * @param name nome da opção experimental
     * @param value valor da opção
     */
    @Deprecated
    public void addExperimentalOption(String name, Object value) {
        if ("prefs".equals(name) && value instanceof Map) {
            @SuppressWarnings("unchecked")
            Map<String, Object> prefsMap = (Map<String, Object>) value;
            ChromeOptionsPrefs.mergeDotPrefs(this.prefs, prefsMap);
        } else {
            throw new UnsupportedOperationException("Apenas opção 'prefs' é suportada");
        }
    }
}
