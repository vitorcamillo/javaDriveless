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

import java.util.Locale;

/** Transporte do Chrome DevTools Protocol usado quando a lib lança o Chrome. */
public enum CdpTransport {
    /** WebSocket em 127.0.0.1 com porta efêmera (padrão no Windows; obrigatório em attach). */
    TCP,
    /** --remote-debugging-pipe: sem porta DevTools; padrão em launch local Linux/macOS. */
    PIPE;

    /** Padrão do processo; {@code -DjavaDriverless.cdpTransport=tcp|pipe} força o transporte. */
    static CdpTransport fromSystemProperty() {
        return fromSystemProperty(System.getProperty("javaDriverless.cdpTransport", ""),
                System.getProperty("os.name", ""));
    }

    /** Resolve o padrão por SO: TCP no Windows, PIPE nos demais. */
    static CdpTransport fromSystemProperty(String value, String osName) {
        String normalized = value == null ? "" : value.trim();
        if ("pipe".equalsIgnoreCase(normalized)) {
            return PIPE;
        }
        if ("tcp".equalsIgnoreCase(normalized)) {
            return TCP;
        }
        if (osName != null && osName.toLowerCase(Locale.ROOT).startsWith("windows")) {
            return TCP;
        }
        return PIPE;
    }
}
