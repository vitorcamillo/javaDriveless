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

import com.fasterxml.jackson.databind.JsonNode;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Permite trabalhar com alertas JavaScript.
 * <p>
 * Use esta classe para interagir com prompts de alerta. Ela contém métodos para
 * descartar, aceitar, inserir texto e obter texto de prompts de alerta.
 * </p>
 *
 * <h3>Aceitando / Descartando prompts de alerta:</h3>
 * <pre>{@code
 * Alert alert = new Alert(target);
 * alert.accept().join();
 * alert.dismiss().join();
 * }</pre>
 *
 * <h3>Inserindo um valor em um prompt de alerta:</h3>
 * <pre>{@code
 * Alert namePrompt = new Alert(target);
 * namePrompt.sendKeys("William Shakespeare").join();
 * namePrompt.accept().join();
 * }</pre>
 *
 * <h3>Lendo o texto de um prompt para verificação:</h3>
 * <pre>{@code
 * String alertText = new Alert(target).getText().join();
 * assertEquals("Do you wish to quit?", alertText);
 * }</pre>
 */
public class Alert {
    
    private final Target target;
    private final float timeout;
    private boolean started = false;
    private JsonNode alertData;
    
    /**
     * Cria um novo Alert.
     *
     * @param target instância do Target que executa ações do usuário
     * @param timeout timeout em segundos para aguardar o alerta aparecer
     */
    public Alert(Target target, float timeout) {
        this.target = target;
        this.timeout = timeout;
    }
    
    /**
     * Cria um novo Alert com timeout padrão de 5 segundos.
     *
     * @param target instância do Target
     */
    public Alert(Target target) {
        this(target, 5.0f);
    }
    
    /**
     * Inicializa o alert, aguardando ele aparecer se necessário.
     *
     * @return CompletableFuture com o Alert inicializado
     */
    public CompletableFuture<Alert> init() {
        if (started) {
            return CompletableFuture.completedFuture(this);
        }
        
        return CompletableFuture.supplyAsync(() -> {
            // Verificar se já há um alert detectado
            if (target.getSocket() != null) {
                // O alert já foi detectado via listener
                started = true;
                return this;
            }
            
            // Aguardar pelo evento de alerta
            try {
                alertData = target.waitForCdp("Page.javascriptDialogOpening", timeout).get(
                    (long) (timeout * 1000), TimeUnit.MILLISECONDS
                );
                started = true;
                return this;
            } catch (TimeoutException e) {
                warnNotDetected();
                started = true;
                return this;
            } catch (Exception e) {
                throw new RuntimeException("Erro ao aguardar alerta", e);
            }
        });
    }
    
    /**
     * Aviso quando o alerta não é detectado.
     */
    private static void warnNotDetected() {
        System.err.println("AVISO: Não foi possível detectar se o diálogo está sendo exibido, " +
            "você pode precisar executar Page.enable antes");
    }
    
    /**
     * Obtém o texto do alert.
     *
     * @return CompletableFuture com o texto
     */
    public CompletableFuture<String> getText() {
        return init().thenApply(a -> {
            if (alertData != null && alertData.has("message")) {
                return alertData.get("message").asText();
            }
            warnNotDetected();
            return "";
        });
    }
    
    /**
     * Obtém a URL do alert.
     *
     * @return CompletableFuture com a URL
     */
    public CompletableFuture<String> getUrl() {
        return init().thenApply(a -> {
            if (alertData != null && alertData.has("url")) {
                return alertData.get("url").asText();
            }
            warnNotDetected();
            return "";
        });
    }
    
    /**
     * Obtém o tipo do alert.
     *
     * @return CompletableFuture com o tipo ("alert", "confirm", "prompt", "beforeunload")
     */
    public CompletableFuture<String> getType() {
        return init().thenApply(a -> {
            if (alertData != null && alertData.has("type")) {
                return alertData.get("type").asText();
            }
            warnNotDetected();
            return "";
        });
    }
    
    /**
     * Verifica se o alert tem handler do navegador.
     *
     * @return CompletableFuture com true se tem handler
     */
    public CompletableFuture<Boolean> hasBrowserHandler() {
        return init().thenApply(a -> {
            if (alertData != null && alertData.has("hasBrowserHandler")) {
                return alertData.get("hasBrowserHandler").asBoolean();
            }
            warnNotDetected();
            return false;
        });
    }
    
    /**
     * Obtém o texto padrão do prompt.
     *
     * @return CompletableFuture com o texto padrão
     */
    public CompletableFuture<String> getDefaultPrompt() {
        return init().thenApply(a -> {
            if (alertData != null && alertData.has("defaultPrompt")) {
                return alertData.get("defaultPrompt").asText();
            }
            warnNotDetected();
            return "";
        });
    }
    
    /**
     * Descarta o alerta disponível.
     *
     * @return CompletableFuture que completa quando o alerta é descartado
     */
    public CompletableFuture<Void> dismiss() {
        return init().thenCompose(a -> {
            Map<String, Object> args = new HashMap<>();
            args.put("accept", false);
            return target.executeCdpCmd("Page.handleJavaScriptDialog", args, null)
                .thenApply(v -> null);
        });
    }
    
    /**
     * Aceita o alerta disponível.
     *
     * @return CompletableFuture que completa quando o alerta é aceito
     */
    public CompletableFuture<Void> accept() {
        return init().thenCompose(a -> {
            Map<String, Object> args = new HashMap<>();
            args.put("accept", true);
            return target.executeCdpCmd("Page.handleJavaScriptDialog", args, null)
                .thenApply(v -> null);
        });
    }
    
    /**
     * Envia teclas para o Alert (para prompts).
     *
     * @param keysToSend texto a ser enviado para o alerta
     * @return CompletableFuture que completa quando as teclas são enviadas
     */
    public CompletableFuture<Void> sendKeys(String keysToSend) {
        return init().thenCompose(a -> {
            Map<String, Object> args = new HashMap<>();
            args.put("accept", true);
            args.put("promptText", keysToSend);
            return target.executeCdpCmd("Page.handleJavaScriptDialog", args, null)
                .thenApply(v -> null);
        });
    }
}

