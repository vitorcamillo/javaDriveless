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

abstract class TargetNavigationOperations extends TargetConnectionCore {

    protected TargetNavigationOperations(String host, String targetId, Object driver, Object context,
                     boolean isRemote, float timeout, String type, boolean startSocket, int maxWsSize) {
        super(host, targetId, driver, context, isRemote, timeout, type, startSocket, maxWsSize);
    }

    /**
     * Navega para uma URL.
     *
     * @param url URL para navegar
     * @param referrer referrer opcional
     * @param waitLoad se deve aguardar o carregamento
     * @param timeout timeout em segundos
     * @return CompletableFuture com dados do resultado (ou download)
     */
    public CompletableFuture<Map<String, Object>> get(String url, String referrer,
                                                       boolean waitLoad, float timeout) {
        boolean shouldWaitLoad = waitLoad;

        if ("about:blank".equals(url)) {
            shouldWaitLoad = false;
        }

        // Lidar com fragmentos (#)
        if (url.contains("#") && shouldWaitLoad) {
            final String finalUrl = url;
            // getUrlViaCDP (nativo) — evita Runtime.evaluate durante a própria navegação.
            return getUrlViaCDP().thenCompose(currentUrl -> {
                String currentBase = currentUrl.split("#")[0];
                String urlBase = finalUrl.split("#")[0];

                String navUrl = finalUrl;
                boolean skipWait = false;
                if (finalUrl.startsWith("#")) {
                    navUrl = currentBase + finalUrl;
                    skipWait = true;
                } else if (urlBase.equals(currentBase)) {
                    skipWait = true;
                }

                return navigateInternal(navUrl, referrer, !skipWait, timeout);
            });
        }

        return navigateInternal(url, referrer, shouldWaitLoad, timeout);
    }

    /**
     * Navega para uma URL (versão simplificada).
     *
     * @param url URL para navegar
     * @param waitLoad se deve aguardar o carregamento
     * @return CompletableFuture que completa quando a navegação termina
     */
    public CompletableFuture<Map<String, Object>> get(String url, boolean waitLoad) {
        return get(url, null, waitLoad, timeout);
    }

    /**
     * Implementação interna da navegação.
     */
    private CompletableFuture<Map<String, Object>> navigateInternal(String url, String referrer,
                                                                     boolean waitLoad, float timeout) {
        CompletableFuture<Map<String, Object>> result = new CompletableFuture<>();

        // Usar timeout fornecido ou padrão
        float actualTimeout = timeout > 0 ? timeout : this.timeout;
        Float timeoutObj = actualTimeout;

        // Habilitar Page domain se necessário (deve ser feito antes da navegação)
        CompletableFuture<Void> enableFuture = CompletableFuture.completedFuture(null);
        if (waitLoad) {
            if (pageEnabled == null || !pageEnabled) {
                enableFuture = executeCdpCmd("Page.enable", null, timeoutObj)
                    .thenAccept(enabled -> pageEnabled = true);
            }
        }

        // Preparar argumentos de navegação
        Map<String, Object> args = new HashMap<>();
        args.put("url", url);
        args.put("transitionType", "link");
        if (referrer != null) {
            args.put("referrer", referrer);
        }

        if (waitLoad) {
            // Primeiro habilitar Page, depois agendar espera pelo evento, depois navegar
            return enableFuture
                .thenCompose(enabled -> {
                    // IMPORTANTE: Agendar espera pelo evento ANTES de navegar
                    CompletableFuture<JsonNode> loadFuture = waitForCdp("Page.loadEventFired", actualTimeout);

                    // Navegar - isso vai disparar o evento que estamos aguardando
                    CompletableFuture<JsonNode> navFuture = executeCdpCmd("Page.navigate", args, timeoutObj);

                    // Aguardar que a navegação seja iniciada e depois esperar o evento de carregamento
                    return navFuture
                        .thenCompose(navResult -> loadFuture)
                        .thenApply(data -> {
                            Map<String, Object> resultMap = objectMapper.convertValue(data, Map.class);
                            onLoaded();
                            return resultMap;
                        });
                })
                .orTimeout((long) (actualTimeout * 1000), TimeUnit.MILLISECONDS);
        } else {
            // Navegar sem aguardar carregamento
            return enableFuture
                .thenCompose(enabled -> executeCdpCmd("Page.navigate", args, timeoutObj))
                .thenApply(navResult -> {
                    onLoaded();
                    return new HashMap<String, Object>();
                });
        }
    }

    /**
     * Volta para a página anterior.
     *
     * @return CompletableFuture que completa quando a ação termina
     */
    public CompletableFuture<Void> back() {
        // 100% NATIVO: Page.getNavigationHistory + navigateToHistoryEntry (antes window.history.back()
        // via Runtime.evaluate, detectável).
        return navigateHistory(-1);
    }

    /**
     * Avança para a próxima página.
     *
     * @return CompletableFuture que completa quando a ação termina
     */
    public CompletableFuture<Void> forward() {
        // 100% NATIVO: Page.getNavigationHistory + navigateToHistoryEntry (antes window.history.forward()
        // via Runtime.evaluate, detectável).
        return navigateHistory(1);
    }

    /**
     * Navega no histórico por offset (-1 = back, +1 = forward) usando o entryId real do
     * Page.getNavigationHistory. No-op se não houver entrada nessa direção — mesma semântica benigna
     * de window.history.back/forward no limite do histórico. 100% CDP, sem JS.
     */
    private CompletableFuture<Void> navigateHistory(int offset) {
        return CompletableFuture.runAsync(() -> {
            long timeoutNanos = TimeUnit.MILLISECONDS.toNanos(
                Math.max(1_000L, (long) (timeout * 1_000)));
            long deadline = System.nanoTime() + timeoutNanos;

            JsonNode history = executeWhilePageIsActive(
                "Page.getNavigationHistory", null, deadline);
            int currentIndex = history.path("currentIndex").asInt();
            JsonNode entries = history.path("entries");
            int targetIndex = currentIndex + offset;
            if (!entries.isArray() || targetIndex < 0 || targetIndex >= entries.size()) {
                return;
            }

            int entryId = entries.get(targetIndex).path("id").asInt();
            String expectedUrl = entries.get(targetIndex).path("url").asText();
            executeWhilePageIsActive(
                "Page.navigateToHistoryEntry", Map.of("entryId", entryId), deadline);

            while (System.nanoTime() < deadline) {
                JsonNode updated = executeWhilePageIsActive(
                    "Page.getNavigationHistory", null, deadline);
                String activeUrl = readActiveTargetUrl(deadline);
                if (updated.path("currentIndex").asInt(-1) == targetIndex
                        && expectedUrl.equals(activeUrl)) {
                    onLoaded();
                    return;
                }
                pauseHistoryPolling();
            }
            throw new IllegalStateException(
                "Timed out waiting for history navigation to index " + targetIndex);
        });
    }

    private JsonNode executeWhilePageIsActive(String method, Map<String, Object> args, long deadline) {
        while (System.nanoTime() < deadline) {
            try {
                return executeCdpCmd(method, args, Math.max(0.1f, Math.min(1.0f, timeout))).join();
            } catch (RuntimeException error) {
                if (!isTransientPageAttachmentFailure(error)) {
                    throw error;
                }
                pauseHistoryPolling();
            }
        }
        throw new IllegalStateException("Timed out waiting for an active page before " + method);
    }

    private String readActiveTargetUrl(long deadline) {
        JsonNode result = executeWhilePageIsActive(
            "Target.getTargetInfo", Map.of("targetId", id), deadline);
        return result.path("targetInfo").path("url").asText();
    }

    private static boolean isTransientPageAttachmentFailure(Throwable error) {
        for (Throwable current = error; current != null; current = current.getCause()) {
            if (current instanceof CDPException cdp
                    && cdp.getCode() == -32000
                    && cdp.hasKind(CDPException.Kind.NOT_ATTACHED_TO_ACTIVE_PAGE)) {
                return true;
            }
        }
        return false;
    }

    private static void pauseHistoryPolling() {
        try {
            TimeUnit.MILLISECONDS.sleep(10);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("History navigation interrupted", interrupted);
        }
    }

    /**
     * Recarrega a página.
     *
     * @param ignoreCache se deve ignorar o cache
     * @return CompletableFuture que completa quando a ação termina
     */
    public CompletableFuture<Void> refresh(boolean ignoreCache) {
        Map<String, Object> args = new HashMap<>();
        if (ignoreCache) {
            args.put("ignoreCache", true);
        }
        CompletableFuture<Void> enableFuture = pageEnabled != null && pageEnabled
            ? CompletableFuture.completedFuture(null)
            : executeCdpCmd("Page.enable", null, timeout)
                .thenAccept(enabled -> pageEnabled = true);
        return enableFuture.thenCompose(enabled -> {
            // Register before Page.reload so a fast data:/cached document cannot fire between
            // the command and the listener. A blocking refresh must not leak its load event into
            // the next navigation and make that operation complete against the wrong document.
            CompletableFuture<JsonNode> loaded = waitForCdp("Page.loadEventFired", timeout);
            return executeCdpCmd("Page.reload", args, timeout)
                .thenCompose(ignored -> loaded)
                .thenAccept(event -> onLoaded());
        }).orTimeout((long) (timeout * 1000), TimeUnit.MILLISECONDS);
    }

    /**
     * Retorna a URL atual.
     *
     * @return CompletableFuture com a URL
     */
    public CompletableFuture<String> getCurrentUrl() {
        // 100% NATIVO: URL via Target.getTargetInfo (antes document.location.href via Runtime.evaluate,
        // detectável). Fonte única compartilhada com getUrlViaCDP.
        return getUrlViaCDP();
    }

    /**
     * Retorna o título da página.
     *
     * @return CompletableFuture com o título
     */
    public CompletableFuture<String> getTitle() {
        // 100% NATIVO: título via Target.getTargetInfo (antes document.title via Runtime.evaluate).
        return getTitleViaCDP();
    }

    /**
     * Retorna o código-fonte da página.
     *
     * @return CompletableFuture com o HTML
     */
    public CompletableFuture<String> getPageSource() {
        // 100% NATIVO: raiz via DOM.getDocument + serialização via DOM.getOuterHTML (antes
        // document.documentElement.outerHTML via Runtime.evaluate, detectável). Busca o root a cada
        // chamada para ser robusto a navegações.
        return executeCdpCmd("DOM.getDocument", Map.of("depth", 0), null)
            .thenCompose(doc -> {
                int rootNodeId = doc.get("root").get("nodeId").asInt();
                return executeCdpCmd("DOM.getOuterHTML", Map.of("nodeId", rootNodeId), null);
            })
            .thenApply(result -> {
                JsonNode html = result.get("outerHTML");
                return html != null ? html.asText() : "";
            });
    }

}
