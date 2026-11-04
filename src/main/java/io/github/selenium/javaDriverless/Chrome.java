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
// Ported from: https://github.com/kaliiiiiiiiii/Selenium-Driverless
// Original Author: kaliiiiiiiiii | Aurin Aegerter
// Java Port: Vitor Camillo (io.github.vitorcamillo)
// Logic: Chrome DevTools Protocol (CDP) direct automation without driver

package io.github.selenium.javaDriverless;

import io.github.selenium.javaDriverless.types.ChromeOptions;

import java.util.concurrent.CompletableFuture;

/**
 * Public Chrome facade. Browser behavior is split across package-private responsibility layers.
 */
public class Chrome extends ChromeBrowserActions {
    Chrome(ChromeOptions options, float timeout, boolean debug, int maxWsSize) {
        super(options, timeout, debug, maxWsSize);
    }

    public static CompletableFuture<Chrome> create(ChromeOptions options) {
        return create(options, 30.0f, false, 1 << 27);
    }

    public static CompletableFuture<Chrome> create(ChromeOptions options, float timeout,
            boolean debug, int maxWsSize) {
        Chrome chrome = new Chrome(options, timeout, debug, maxWsSize);
        return chrome.startSession().thenApply(v -> chrome);
    }
}
