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

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.attribute.PosixFilePermissions;

final class ChromeOptionsDirectories {
    private ChromeOptionsDirectories() {
    }

    static void ensureDirectory(String directoryPath) {
        Path path;
        try {
            path = Paths.get(directoryPath);
        } catch (InvalidPathException e) {
            // InvalidPathException carrega o caminho na mensagem: não preservar a causa
            throw new IllegalArgumentException("Caminho de perfil inválido");
        }
        if (Files.exists(path)) {
            return;
        }
        try {
            if (path.getFileSystem().supportedFileAttributeViews().contains("posix")) {
                // user-data-dir guarda Cookies/Login Data: nasce 700, nunca altera dir pré-existente
                Files.createDirectories(path, PosixFilePermissions.asFileAttribute(
                        PosixFilePermissions.fromString("rwx------")));
            } else {
                Files.createDirectories(path);
            }
        } catch (IOException e) {
            // Sem o caminho na mensagem nem na causa (AccessDeniedException/FileSystemException o carregam)
            throw new UncheckedIOException("Erro ao criar diretório de perfil",
                    new IOException(e.getClass().getSimpleName()));
        }
        // FS posix que não aplica permissão (ex.: NTFS montado) não é falha: criação já bastou
    }

    static String resolveDownloadsDir(String directoryPath) {
        if (directoryPath == null) {
            return null;
        }

        Path path = Paths.get(directoryPath);
        if (Files.isRegularFile(path)) {
            throw new IllegalArgumentException("Caminho não pode apontar para um arquivo");
        }

        if (!Files.exists(path)) {
            try {
                Files.createDirectories(path);
            } catch (IOException e) {
                throw new RuntimeException("Erro ao criar diretório de downloads", e);
            }
        }

        return path.toAbsolutePath().toString();
    }
}
