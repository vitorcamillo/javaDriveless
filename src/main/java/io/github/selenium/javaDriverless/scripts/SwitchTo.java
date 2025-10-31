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

package io.github.selenium.javaDriverless.scripts;

import java.util.List;
import java.util.Map;
import java.util.HashMap;
import java.util.concurrent.CompletableFuture;

import io.github.selenium.javaDriverless.types.Alert;
import io.github.selenium.javaDriverless.types.By;
import io.github.selenium.javaDriverless.types.Target;
import io.github.selenium.javaDriverless.types.WebElement;

/**
 * A classe SwitchTo.
 * <p>
 * <strong>Aviso:</strong> Exceto para trocar entre targets, não use esta classe.
 * Use {@code WebElement.contentDocument} em vez de {@code switchTo().frame()}
 * </p>
 */
public class SwitchTo {
    
    private final Object context;  // Context object
    private final String contextId;
    private boolean started = false;
    private final boolean isIncognito;
    
    /**
     * Cria um novo SwitchTo.
     *
     * @param context instância do Context
     * @param contextId ID do contexto
     */
    public SwitchTo(Object context, String contextId) {
        this.context = context;
        this.contextId = contextId;
        // TODO: Obter isIncognito do context
        this.isIncognito = false;
    }
    
    /**
     * Cria um novo SwitchTo com valores padrão.
     *
     * @param context instância do Context
     */
    public SwitchTo(Object context) {
        this(context, null);
    }
    
    /**
     * Troca o foco para um alerta na página.
     *
     * @return CompletableFuture com o Alert
     */
    public CompletableFuture<Alert> alert() {
        return getAlert(5.0f);
    }
    
    /**
     * Troca o foco para um alerta na página com timeout customizado.
     *
     * @param timeout timeout em segundos
     * @return CompletableFuture com o Alert
     */
    public CompletableFuture<Alert> getAlert(float timeout) {
        // Delegar para context.getCurrentTarget()
        try {
            var method = context.getClass().getMethod("getCurrentTarget");
            Target target = (Target) method.invoke(context);
            
            Alert alert = new Alert(target, timeout);
            return alert.init();
        } catch (Exception e) {
            return CompletableFuture.failedFuture(
                new RuntimeException("Erro ao obter alerta", e)
            );
        }
    }
    
    /**
     * Troca o foco para o conteúdo padrão (frame principal).
     *
     * @param activate se deve trazer o target para frente
     * @return CompletableFuture com o Target
     */
    public CompletableFuture<Target> defaultContent(boolean activate) {
        // Retornar ao target base
        try {
            var getCurrentTargetMethod = context.getClass().getMethod("getCurrentTarget");
            @SuppressWarnings("unchecked")
            CompletableFuture<Target> currentTargetFuture = 
                (CompletableFuture<Target>) getCurrentTargetMethod.invoke(context);
            
            return currentTargetFuture.thenCompose(currentTarget -> {
                // TODO: Implementar lógica de base_target quando Context estiver completo
                return CompletableFuture.completedFuture(currentTarget);
            });
        } catch (Exception e) {
            return CompletableFuture.failedFuture(
                new RuntimeException("Erro ao trocar para conteúdo padrão", e)
            );
        }
    }
    
    /**
     * Troca para o frame especificado.
     * <p>
     * <strong>Aviso:</strong> {@code driver.switchTo().frame()} está deprecated e não é confiável.
     * Use {@code WebElement.contentDocument()} em vez disso.
     * </p>
     *
     * @param frameReference referência por ID, nome, índice ou WebElement
     * @param focus se deve emular foco no frame
     * @return CompletableFuture com o Target do frame
     */
    @Deprecated
    public CompletableFuture<Target> frame(Object frameReference, boolean focus) {
        System.err.println("AVISO: driver.switchTo().frame() está deprecated e não é confiável. " +
            "Use WebElement.contentDocument() em vez disso");
        
        CompletableFuture<WebElement> elementFuture;
        
        try {
            var findElementMethod = context.getClass().getMethod("findElement", String.class, String.class, float.class);
            
            if (frameReference instanceof String) {
                String ref = (String) frameReference;
                // Tentar por ID primeiro
                @SuppressWarnings("unchecked")
                CompletableFuture<WebElement> byId = 
                    (CompletableFuture<WebElement>) findElementMethod.invoke(context, By.ID, ref, 5.0f);
                
                elementFuture = byId.exceptionally(e -> null).thenCompose(elem -> {
                    if (elem != null) {
                        return CompletableFuture.completedFuture(elem);
                    }
                    // Tentar por NAME
                    try {
                        @SuppressWarnings("unchecked")
                        CompletableFuture<WebElement> byName = 
                            (CompletableFuture<WebElement>) findElementMethod.invoke(context, By.NAME, ref, 5.0f);
                        return byName;
                    } catch (Exception ex) {
                        throw new RuntimeException("Frame não encontrado: " + ref);
                    }
                });
            } else if (frameReference instanceof Integer) {
                int index = (Integer) frameReference;
                var findElementsMethod = context.getClass().getMethod("findElements", 
                    String.class, String.class, float.class);
                
                @SuppressWarnings("unchecked")
                CompletableFuture<List<WebElement>> framesFuture = 
                    (CompletableFuture<List<WebElement>>) findElementsMethod.invoke(
                        context, By.TAG_NAME, "iframe", 5.0f);
                
                elementFuture = framesFuture.thenApply(frames -> {
                    if (index >= 0 && index < frames.size()) {
                        return frames.get(index);
                    }
                    throw new RuntimeException("Índice de frame inválido: " + index);
                });
            } else if (frameReference instanceof WebElement) {
                elementFuture = CompletableFuture.completedFuture((WebElement) frameReference);
            } else {
                return CompletableFuture.failedFuture(
                    new IllegalArgumentException("Referência de frame inválida: " + frameReference)
                );
            }
            
            return elementFuture.thenCompose(elem -> {
                // TODO: Implementar getTargetForIframe quando disponível
                return CompletableFuture.failedFuture(
                    new UnsupportedOperationException(
                        "switchTo().frame() requer implementação completa de iframes CORS"
                    )
                );
            });
            
        } catch (Exception e) {
            return CompletableFuture.failedFuture(
                new RuntimeException("Erro ao trocar para frame", e)
            );
        }
    }
    
    /**
     * Troca para um target específico.
     *
     * @param targetId ID do target ou instância do Target
     * @param activate se deve trazer o target para frente
     * @param focus se deve emular foco no target
     * @return CompletableFuture com o Target
     */
    public CompletableFuture<Target> target(Object targetId, boolean activate, boolean focus) {
        if (targetId instanceof Target) {
            Target t = (Target) targetId;
            
            CompletableFuture<Void> activateFuture = activate ? 
                activateTarget(t) : CompletableFuture.completedFuture(null);
            
            CompletableFuture<Void> focusFuture = focus ?
                focusTarget(t) : CompletableFuture.completedFuture(null);
            
            return activateFuture.thenCompose(v -> focusFuture)
                .thenApply(v -> t);
        }
        
        // TODO: Implementar busca de target por ID quando Context estiver completo
        return CompletableFuture.failedFuture(
            new UnsupportedOperationException("Busca de target por ID requer Context completo")
        );
    }
    
    /**
     * Troca para uma janela.
     * <p>
     * Alias para {@link #target(Object, boolean, boolean)}
     * </p>
     *
     * @param windowId ID da janela
     * @param activate se deve ativar
     * @param focus se deve focar
     * @return CompletableFuture com o Target
     */
    public CompletableFuture<Target> window(String windowId, boolean activate, boolean focus) {
        return target(windowId, activate, focus);
    }
    
    /**
     * Ativa um target (traz para frente).
     */
    private CompletableFuture<Void> activateTarget(Target target) {
        Map<String, Object> args = new HashMap<>();
        args.put("targetId", target.getId());
        return target.executeCdpCmd("Target.activateTarget", args, null)
            .thenApply(v -> null);
    }
    
    /**
     * Foca em um target.
     */
    private CompletableFuture<Void> focusTarget(Target target) {
        return target.executeScript("window.focus()", null, false)
            .thenApply(v -> null);
    }
}

