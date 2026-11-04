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
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import io.github.selenium.javaDriverless.utils.Utils;

final class ChromeOptionsDefaults {
    private ChromeOptionsDefaults() {
    }

    static Map<String, String> localizedEnvironment() {
        return ChromeOptionsEnvironment.defaultsFrom(System.getenv());
    }

    static void addDefaultArguments(ChromeOptions options) {
        options.addArguments(
            "--no-first-run",  // desabilita página de primeira execução
            "--no-service-autorun",  // não iniciar um serviço
            "--disable-auto-reload",  // não recarregar páginas automaticamente em erros de rede
            "--disable-backgrounding-occluded-windows",
            "--disable-renderer-backgrounding",
            "--disable-background-timer-throttling",
            "--disable-background-networking",
            "--no-pings",
            "--disable-infobars",
            "--disable-breakpad",
            "--no-default-browser-check",  // desabilita mensagem de navegador padrão
            "--homepage=about:blank",  // define homepage
            "--wm-window-animations-disabled",
            "--animation-duration-scale=0",  // desabilita animações
            "--enable-privacy-sandbox-ads-apis",
            "--disable-search-engine-choice-screen"  // para chrome>=127
        );

        // Browser control arguments - keep real graphics/canvas surfaces enabled.
        options.addArguments(
            // Preserve WebGL/Canvas hardware surfaces for diagnostics and profile coherence.
            "--enable-webgl",
            "--disable-accelerated-2d-canvas",
            "--ignore-gpu-blocklist",

            // Let the window manager expose native screen/workarea metrics.
            "--start-maximized",

            // Hide automation-related features
            "--disable-blink-features=AutomationControlled",  // esconde navigator.webdriver (checagem #1 de bot)
            "--disable-hang-monitor",
            "--disable-ipc-flooding-protection",
            "--metrics-recording-only",
            "--mute-audio",

            // Reduce fingerprinting surface
            "--disable-features=TranslateUI,InterestCohort",
            "--lang=en-US",
            "--accept-lang=en-US,en"
        );

        if (containerFallbackEnabled()) {
            options.addArguments(
                "--disable-dev-shm-usage",
                "--no-sandbox",
                "--disable-setuid-sandbox"
            );
        }

        if (Utils.isPosix()) {
            options.addArgument("--password-store=basic");
        }

        applyRuntimeArgumentOverrides(options, System.getenv());
    }

    /**
     * Reduz os padroes ao minimo operacional para um Chrome-controle diagnostico. Remove inclusive
     * AutomationControlled, locale, canvas/GPU e prefs que o construtor normal instala; argumentos
     * extras explicitamente fornecidos pelo ambiente sao reaplicados depois da limpeza.
     *
     * <p>So os padroes do construtor saem: argumentos e prefs que o chamador adicionou antes desta
     * chamada (proxy, sandbox, tamanho de janela, headless...) sao preservados. Ate a 1.12.1 eles
     * eram apagados em silencio e o Chrome subia sem proxy quando a chamada vinha depois deles.</p>
     */
    static void useDiagnosticBaselineDefaults(ChromeOptionsFingerprintOptions options) {
        List<String> callerArguments = argumentsAddedSince(options.constructorArguments, options.arguments);
        Map<String, Object> callerPrefs = ChromeOptionsPrefs.changedSince(options.constructorPrefs, options.prefs);
        options.arguments.clear();
        options.prefs.clear();
        options.addArguments(
                "--no-first-run",
                "--no-service-autorun",
                "--disable-auto-reload",
                "--no-default-browser-check",
                "--homepage=about:blank",
                "--disable-search-engine-choice-screen",
                "--start-maximized");
        if (containerFallbackEnabled()) {
            options.addArguments(
                    "--disable-dev-shm-usage",
                    "--no-sandbox",
                    "--disable-setuid-sandbox");
        }
        if (Utils.isPosix()) {
            options.addArgument("--password-store=basic");
        }
        applyRuntimeArgumentOverrides(options, System.getenv());
        Set<String> baseline = new HashSet<>(options.arguments);
        for (String argument : callerArguments) {
            if (!baseline.contains(argument)) {
                options.arguments.add(argument);
            }
        }
        options.prefs.putAll(callerPrefs);
    }

    /** Diferenca de multiconjunto: o que esta em {@code current} alem dos padroes, na ordem. */
    static List<String> argumentsAddedSince(List<String> defaults, List<String> current) {
        Map<String, Integer> remaining = new HashMap<>();
        for (String argument : defaults) {
            remaining.merge(argument, 1, Integer::sum);
        }
        List<String> added = new ArrayList<>();
        for (String argument : current) {
            Integer count = remaining.get(argument);
            if (count != null && count > 0) {
                remaining.put(argument, count - 1);
            } else {
                added.add(argument);
            }
        }
        return added;
    }

    private static boolean containerFallbackEnabled() {
        return Boolean.parseBoolean(System.getenv("JAVA_DRIVERLESS_CONTAINER_FLAGS"));
    }

    static void applyRuntimeArgumentOverrides(ChromeOptionsArgumentOptions options, Map<String, String> env) {
        for (String prefix : splitArgumentList(env.get("JAVA_DRIVERLESS_REMOVE_DEFAULT_ARGS"), true)) {
            options.arguments.removeIf(argument -> argument.equals(prefix) || argument.startsWith(prefix));
        }

        for (String argument : splitArgumentList(env.get("JAVA_DRIVERLESS_EXTRA_ARGS"), false)) {
            if (isManagedLaunchArgument(argument)) {
                throw new IllegalArgumentException(
                        "JAVA_DRIVERLESS_EXTRA_ARGS cannot override a managed browser argument; "
                                + "use the dedicated profile/proxy/storage API");
            }
            options.addArgument(argument);
        }
    }

    private static boolean isManagedLaunchArgument(String argument) {
        String normalized = argument == null ? "" : argument.trim().toLowerCase(Locale.ROOT);
        int prefixLength = normalized.startsWith("--") ? 2
                : normalized.startsWith("-") || normalized.startsWith("/") ? 1 : 0;
        if (prefixLength == 0) return false;
        String body = normalized.substring(prefixLength);
        int end = body.length();
        for (int index = 0; index < body.length(); index++) {
            char current = body.charAt(index);
            if (current == '=' || Character.isWhitespace(current)) {
                end = index;
                break;
            }
        }
        String name = body.substring(0, end);
        return name.equals("user-data-dir")
                || name.startsWith("remote-debugging-")
                || name.startsWith("proxy-")
                || name.equals("no-proxy-server");
    }

    private static List<String> splitArgumentList(String raw, boolean commaSeparated) {
        List<String> values = new ArrayList<>();
        if (raw == null || raw.isBlank()) {
            return values;
        }

        String[] parts = commaSeparated ? raw.split("[,;\\n]") : raw.split("[;\\n]");
        for (String part : parts) {
            String value = part.trim();
            if (!value.isEmpty()) {
                values.add(value);
            }
        }
        return values;
    }
}
