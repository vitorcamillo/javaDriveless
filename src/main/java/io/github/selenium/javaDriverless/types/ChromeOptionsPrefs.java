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

import java.lang.reflect.Array;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

import io.github.selenium.javaDriverless.scripts.Prefs;

final class ChromeOptionsPrefs {
    private ChromeOptionsPrefs() {
    }

    static Map<String, Object> defaultPrefs() {
        Map<String, Object> prefs = new HashMap<>();

        Map<String, Object> devtools = new HashMap<>();
        Map<String, Object> devtoolsPrefs = new HashMap<>();
        devtoolsPrefs.put("currentDockState", "\"undocked\"");
        devtoolsPrefs.put("panel-selectedTab", "\"console\"");
        devtools.put("preferences", devtoolsPrefs);
        prefs.put("devtools", devtools);

        Map<String, Object> downloadBubble = new HashMap<>();
        downloadBubble.put("partial_view_enabled", false);
        prefs.put("download_bubble", downloadBubble);

        Map<String, Object> inProductHelp = new HashMap<>();
        Map<String, Object> snoozedFeature = new HashMap<>();
        Map<String, Object> highEfficiencyMode = new HashMap<>();
        highEfficiencyMode.put("is_dismissed", true);
        snoozedFeature.put("IPH_HighEfficiencyMode", highEfficiencyMode);
        inProductHelp.put("snoozed_feature", snoozedFeature);
        prefs.put("in_product_help", inProductHelp);

        prefs.put("credentials_enable_service", false);

        Map<String, Object> profile = new HashMap<>();
        profile.put("password_manager_enabled", false);
        prefs.put("profile", profile);

        return prefs;
    }

    static void mergeDotPref(Map<String, Object> target, String pref, Object value) {
        Map<String, Object> prefMap = new HashMap<>();
        prefMap.put(pref, value);
        mergeDotPrefs(target, prefMap);
    }

    static void mergeDotPrefs(Map<String, Object> target, Map<String, Object> source) {
        Map<String, Object> converted = Prefs.prefsToJson(source);
        mergePrefs(target, converted);
    }

    /**
     * Prefs de {@code current} que nao existem ou diferem de {@code defaults}, preservando o
     * aninhamento (so as folhas alteradas de um mapa aninhado entram).
     */
    @SuppressWarnings("unchecked")
    static Map<String, Object> changedSince(Map<String, Object> defaults, Map<String, Object> current) {
        Map<String, Object> changed = new LinkedHashMap<>();
        for (Map.Entry<String, Object> entry : current.entrySet()) {
            String key = entry.getKey();
            Object value = entry.getValue();
            Object original = defaults.get(key);
            if (value instanceof Map && original instanceof Map) {
                Map<String, Object> nested = changedSince(
                        (Map<String, Object>) original, (Map<String, Object>) value);
                if (!nested.isEmpty()) {
                    changed.put(key, nested);
                }
            } else if (!defaults.containsKey(key) || !Objects.equals(original, value)) {
                changed.put(key, deepCopyValue(value));
            }
        }
        return changed;
    }

    static Map<String, Object> deepCopyMap(Map<String, Object> source) {
        Map<String, Object> copy = new LinkedHashMap<>();
        for (Map.Entry<String, Object> entry : source.entrySet()) {
            copy.put(entry.getKey(), deepCopyValue(entry.getValue()));
        }
        return copy;
    }

    static Object deepCopyValue(Object value) {
        if (value instanceof Map<?, ?> map) {
            Map<Object, Object> copy = new LinkedHashMap<>();
            map.forEach((key, nested) -> copy.put(key, deepCopyValue(nested)));
            return copy;
        }
        if (value instanceof List<?> list) {
            List<Object> copy = new ArrayList<>(list.size());
            list.forEach(item -> copy.add(deepCopyValue(item)));
            return copy;
        }
        if (value instanceof Set<?> set) {
            Set<Object> copy = new LinkedHashSet<>();
            set.forEach(item -> copy.add(deepCopyValue(item)));
            return copy;
        }
        if (value != null && value.getClass().isArray()) {
            int length = Array.getLength(value);
            Object copy = Array.newInstance(value.getClass().getComponentType(), length);
            if (value.getClass().getComponentType().isPrimitive()) {
                System.arraycopy(value, 0, copy, 0, length);
            } else {
                for (int index = 0; index < length; index++) {
                    Array.set(copy, index, deepCopyValue(Array.get(value, index)));
                }
            }
            return copy;
        }
        return value;
    }

    @SuppressWarnings("unchecked")
    private static void mergePrefs(Map<String, Object> target, Map<String, Object> source) {
        for (Map.Entry<String, Object> entry : source.entrySet()) {
            String key = entry.getKey();
            Object value = entry.getValue();

            if (target.containsKey(key) && target.get(key) instanceof Map && value instanceof Map) {
                mergePrefs((Map<String, Object>) target.get(key), (Map<String, Object>) value);
            } else {
                target.put(key, deepCopyValue(value));
            }
        }
    }
}
