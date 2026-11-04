package io.github.selenium.javaDriverless.types;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import io.github.selenium.javaDriverless.cdp.CDPCommandExecutor;
import io.github.selenium.javaDriverless.cdp.CDPSocket;
import io.github.selenium.javaDriverless.cdp.exceptions.CDPException;
import io.github.selenium.javaDriverless.input.Pointer;
import io.github.selenium.javaDriverless.logging.JavaDriverlessLogger;
import io.github.selenium.javaDriverless.types.TypesExceptions.NoSuchIframe;

import org.slf4j.Logger;

import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Consumer;
import java.util.Base64;

abstract class TargetElementOperations extends TargetScriptOperations {

    protected TargetElementOperations(String host, String targetId, Object driver, Object context,
                     boolean isRemote, float timeout, String type, boolean startSocket, int maxWsSize) {
        super(host, targetId, driver, context, isRemote, timeout, type, startSocket, maxWsSize);
    }

    /**
     * Retorna o elemento documento (raiz).
     *
     * @return CompletableFuture com o WebElement do documento
     */
    protected CompletableFuture<WebElement> getDocumentElem() {
        if (documentElem != null) {
            return CompletableFuture.completedFuture((WebElement) documentElem);
        }

        return executeCdpCmd("DOM.getDocument", Map.of("pierce", true), null)
            .thenApply(result -> {
                int nodeId = result.get("root").get("nodeId").asInt();
                WebElement elem = new WebElement((Target) this, null, null,
                    null, nodeId, null, null, false);
                documentElem = elem;
                return elem;
            });
    }

    /**
     * Busca um elemento na página.
     *
     * @param by estratégia de busca (ex: By.XPATH)
     * @param value valor da busca
     * @param timeout timeout em segundos
     * @return CompletableFuture com o elemento encontrado
     */
    public CompletableFuture<WebElement> findElement(String by, String value, Float timeout) {
        return CompletableFuture.supplyAsync(() -> {
            long startNanos = System.nanoTime();
            WebElement elem = null;
            float effectiveTimeout = (timeout != null) ? timeout : 10.0f;
            long sleepMs = 10; // PERF-01: Exponential backoff starting at 10ms

            while (elem == null) {
                try {
                    WebElement parent = getDocumentElem().join();
                    // FIX BUG-06: Usar timeout curto no inner call — o loop externo já controla retry
                    elem = parent.findElement(by, value, 0.1f).join();
                } catch (Exception e) {
                    // StaleElement ou NoSuchElement - recarregar
                    onLoaded();
                }

                double elapsed = (System.nanoTime() - startNanos) / 1_000_000_000.0;
                if (elapsed > effectiveTimeout) {
                    break;
                }

                if (elem == null) {
                    try {
                        Thread.sleep(sleepMs);
                        sleepMs = Math.min(sleepMs * 2, 200); // Backoff até 200ms
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        break;
                    }
                }
            }

            if (elem == null) {
                throw new WebElement.NoSuchElementException(
                    String.format("Elemento não encontrado: %s='%s'", by, value)
                );
            }

            return elem;
        });
    }

    /**
     * Busca múltiplos elementos na página.
     *
     * @param by estratégia de busca
     * @param value valor da busca
     * @param timeout timeout em segundos
     * @return CompletableFuture com a lista de elementos
     */
    public CompletableFuture<List<WebElement>> findElements(String by, String value, float timeout) {
        return CompletableFuture.supplyAsync(() -> {
            long startNanos = System.nanoTime();
            long sleepMs = 10; // PERF-01: Exponential backoff

            while (true) {
                try {
                    WebElement parent = getDocumentElem().join();
                    List<WebElement> result = parent.findElements(by, value).join();
                    // FIX BUG-07: Só retornar se a lista não estiver vazia
                    if (!result.isEmpty()) {
                        return result;
                    }
                } catch (Exception e) {
                    // StaleElement - recarregar
                    onLoaded();
                }

                double elapsed = (System.nanoTime() - startNanos) / 1_000_000_000.0;
                if (elapsed > timeout) {
                    // Timeout: retornar lista vazia em vez de exception
                    return new ArrayList<>();
                }

                try {
                    Thread.sleep(sleepMs);
                    sleepMs = Math.min(sleepMs * 2, 200);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new RuntimeException("Interrompido durante busca de elementos");
                }
            }
        });
    }

    /**
     * Busca elementos usando pesquisa de texto, CSS ou XPath.
     *
     * @param query texto plano, seletor CSS ou XPath
     * @return CompletableFuture com a lista de elementos
     */
    public CompletableFuture<List<WebElement>> searchElements(String query) {
        if (domEnabled == null || !domEnabled) {
            executeCdpCmd("DOM.enable", null, null).join();
            domEnabled = true;
        }

        // Garantir que DOM.getDocument foi chamado
        getDocumentElem().join();

        Map<String, Object> searchArgs = new HashMap<>();
        searchArgs.put("includeUserAgentShadowDOM", true);
        searchArgs.put("query", query);

        return executeCdpCmd("DOM.performSearch", searchArgs, null)
            .thenCompose(result -> {
                String searchId = result.get("searchId").asText();
                int elemCount = result.get("resultCount").asInt();

                if (elemCount <= 0) {
                    discardSearchResults(searchId);
                    return CompletableFuture.completedFuture(new ArrayList<WebElement>());
                }

                Map<String, Object> getResultsArgs = new HashMap<>();
                getResultsArgs.put("searchId", searchId);
                getResultsArgs.put("fromIndex", 0);
                getResultsArgs.put("toIndex", elemCount);

                return executeCdpCmd("DOM.getSearchResults", getResultsArgs, null)
                    .thenApply(res -> {
                        List<WebElement> elems = new ArrayList<>();
                        JsonNode nodeIds = res.get("nodeIds");

                        for (JsonNode nodeIdNode : nodeIds) {
                            int nodeId = nodeIdNode.asInt();
                            WebElement elem = new WebElement((Target) this, null, null, null,
                                nodeId, null, null, false);
                            elems.add(elem);
                        }

                        return elems;
                    })
                    // Libera o searchId no backend em sucesso ou erro (falha ignorada)
                    .whenComplete((res, err) -> discardSearchResults(searchId));
            });
    }

    // Descarta resultados de DOM.performSearch sem bloquear (fire-and-forget,
    // mesmo padrão de WebElementFinder); falha do discard é ignorada
    private void discardSearchResults(String searchId) {
        if (searchId == null) {
            return;
        }
        try {
            executeCdpCmd("DOM.discardSearchResults", Map.of("searchId", searchId), null);
        } catch (Exception ignored) {
        }
    }

    /**
     * Encontra targets para uma lista de iframes.
     *
     * AVISO: Apenas iframes CORS têm seu próprio target.
     * Para iframes same-origin, use WebElement.getContentDocument() ao invés disso.
     *
     * @param iframes lista de elementos iframe
     * @return CompletableFuture com lista de targets
     */
    public CompletableFuture<List<Target>> getTargetsForIframes(List<WebElement> iframes) {
        if (iframes == null || iframes.isEmpty()) {
            return CompletableFuture.failedFuture(
                new IllegalArgumentException("Esperado WebElements, mas recebeu vazio/nulo")
            );
        }

        return CompletableFuture.supplyAsync(() -> {
            try {
                // Obter todos os targets do tipo iframe
                Map<String, Object> args = new HashMap<>();
                if (contextId != null) {
                    args.put("browserContextId", contextId);
                }

                List<Target> foundTargets = new ArrayList<>();

                // Obter targets via CDP
                JsonNode targetsResult = executeCdpCmd("Target.getTargets", args, null).join();
                JsonNode targetInfos = targetsResult.get("targetInfos");

                if (targetInfos != null && targetInfos.isArray()) {
                    for (JsonNode targetInfo : targetInfos) {
                        String targetType = targetInfo.get("type").asText();
                        if ("iframe".equals(targetType)) {
                            String targetId = targetInfo.get("targetId").asText();

                            // Criar target (simplificado - em produção usar factory)
                            Target iframeTarget = new Target(
                                host, targetId, driver, context,
                                isRemote, timeout, "iframe", false, maxWsSize
                            );

                            // Obter base frame do target
                            Map<String, Object> baseFrame = iframeTarget.getBaseFrame().join();
                            String targetFrameId = (String) baseFrame.get("id");

                            // Verificar se algum iframe corresponde
                            for (WebElement iframe : iframes) {
                                try {
                                    String tagName = iframe.getTagName().join();
                                    if (!"IFRAME".equalsIgnoreCase(tagName)) {
                                        throw new NoSuchIframe(iframe, "elemento não é um iframe");
                                    }

                                    // Obter frame ID do iframe
                                    String iframeFrameId = iframe.getFrameId().join();

                                    if (targetFrameId.equals(iframeFrameId)) {
                                        if ("iframe".equals(type)) {
                                            iframeTarget.parentTarget = (Target) this;
                                        }
                                        foundTargets.add(iframeTarget);
                                        break;
                                    }
                                } catch (Exception e) {
                                    // Ignorar erro e continuar
                                }
                            }
                        }
                    }
                }

                return foundTargets;
            } catch (Exception e) {
                throw new RuntimeException("Erro ao obter targets para iframes: " + e.getMessage(), e);
            }
        });
    }

    /**
     * Encontra target para um iframe específico.
     *
     * AVISO: Apenas iframes CORS têm seu próprio target.
     *
     * @param iframe elemento iframe
     * @return CompletableFuture com o target do iframe
     */
    public CompletableFuture<Target> getTargetForIframe(WebElement iframe) {
        return getTargetsForIframes(List.of(iframe)).thenApply(targets -> {
            if (targets.isEmpty()) {
                throw new NoSuchIframe(iframe,
                    "Nenhum target encontrado para o iframe. Pode ser same-origin - use getContentDocument()");
            }
            return targets.get(0);
        });
    }

}
