// Licensed to the Software Freedom Conservancy (SFC) under one
// or more contributor license agreements.  See the NOTICE file
// distributed with this work for additional information
// regarding copyright ownership.  The SFC licenses this file
// to you under the Apache License, Version 2.0 (the
// "License"); you may not use this file except in compliance
// with the License.  You may obtain a copy of the License at
//
//      http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing,
// software distributed under the License is distributed on an
// "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
// KIND, either express or implied.  See the License for the
// specific language governing permissions and limitations
// under the License.

package io.github.selenium.javaDriverless.types;

import java.util.ListIterator;
import java.util.Locale;
import java.util.Set;

final class ChromeOptionsMinimalCdpArguments {
    private static final Set<String> REMOVED_EXACT_ARGUMENTS = Set.of(
            "--no-service-autorun",
            "--disable-auto-reload",
            "--disable-backgrounding-occluded-windows",
            "--disable-renderer-backgrounding",
            "--disable-background-timer-throttling",
            "--wm-window-animations-disabled",
            "--animation-duration-scale=0",
            "--enable-privacy-sandbox-ads-apis",
            "--enable-webgl",
            "--disable-accelerated-2d-canvas",
            "--disable-blink-features=AutomationControlled",
            "--disable-hang-monitor",
            "--disable-ipc-flooding-protection");
    private static final Set<String> REMOVED_DISABLED_FEATURES = Set.of(
            "TranslateUI", "InterestCohort");
    private static final String DISABLE_FEATURES_PREFIX = "--disable-features=";
    private static final String SITE_PER_PROCESS_ARGUMENT = "--site-per-process";

    private ChromeOptionsMinimalCdpArguments() {
    }

    static void apply(ChromeOptions options) {
        try {
            MinimalCdpProcessArgumentPolicy.requireSafe(options.arguments);
        } catch (IllegalArgumentException unsafe) {
            throw new IllegalStateException(unsafe.getMessage());
        }
        rejectSyntheticGraphics(options);
        options.arguments.removeIf(REMOVED_EXACT_ARGUMENTS::contains);
        ListIterator<String> iterator = options.arguments.listIterator();
        while (iterator.hasNext()) {
            String argument = iterator.next();
            if (!argument.startsWith(DISABLE_FEATURES_PREFIX)) {
                continue;
            }
            String retained = java.util.Arrays.stream(
                            argument.substring(DISABLE_FEATURES_PREFIX.length()).split(","))
                    .map(String::trim)
                    .filter(feature -> !feature.isEmpty())
                    .filter(feature -> !REMOVED_DISABLED_FEATURES.contains(feature))
                    .collect(java.util.stream.Collectors.joining(","));
            if (retained.isEmpty()) {
                iterator.remove();
            } else if (!retained.equals(argument.substring(DISABLE_FEATURES_PREFIX.length()))) {
                iterator.set(DISABLE_FEATURES_PREFIX + retained);
            }
        }
        // Timezone/media/geolocation são overrides target-local no Chromium. Forçar OOPIFs
        // garante que o auto-attach consiga aplicá-los antes do JavaScript cross-site.
        options.arguments.removeIf(argument -> argument != null
                && SITE_PER_PROCESS_ARGUMENT.equals(argument.trim()));
        options.arguments.add(SITE_PER_PROCESS_ARGUMENT);
    }

    private static void rejectSyntheticGraphics(ChromeOptions options) {
        boolean unsafe = options.arguments.stream()
                .filter(java.util.Objects::nonNull)
                .map(argument -> argument.trim().toLowerCase(Locale.ROOT))
                .anyMatch(argument -> argument.contains("swiftshader")
                        || argument.equals("--disable-gpu")
                        || argument.startsWith("--disable-gpu="));
        if (unsafe) {
            throw new IllegalStateException(
                    "runtimePolicy=minimal-cdp não aceita SwiftShader ou GPU sintética/desativada");
        }
    }
}
