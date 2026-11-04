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

abstract class TargetStateOperations extends TargetCaptureOperations {

    protected TargetStateOperations(String host, String targetId, Object driver, Object context,
                     boolean isRemote, float timeout, String type, boolean startSocket, int maxWsSize) {
        super(host, targetId, driver, context, isRemote, timeout, type, startSocket, maxWsSize);
    }

    /**
     * Obtém todos os cookies visíveis na sessão atual.
     *
     * @return CompletableFuture com a lista de cookies
     */
    public CompletableFuture<List<Map<String, Object>>> getCookies() {
        return executeCdpCmd("Network.getCookies", null, null)
            .thenApply(result -> {
                JsonNode cookies = result.get("cookies");
                List<Map<String, Object>> cookieList = new ArrayList<>();

                for (JsonNode cookie : cookies) {
                    Map<String, Object> cookieMap = new HashMap<>();
                    cookie.fields().forEachRemaining(entry -> {
                        JsonNode value = entry.getValue();
                        cookieMap.put(entry.getKey(),
                            value.isTextual() ? value.asText() : value);
                    });
                    cookieList.add(cookieMap);
                }

                return cookieList;
            });
    }

    /**
     * Obtém um cookie pelo nome.
     *
     * @param name nome do cookie
     * @return CompletableFuture com o cookie ou null
     */
    public CompletableFuture<Map<String, Object>> getCookie(String name) {
        return getCookies().thenApply(cookies -> {
            for (Map<String, Object> cookie : cookies) {
                if (name.equals(cookie.get("name"))) {
                    return cookie;
                }
            }
            return null;
        });
    }

    /**
     * Adiciona um cookie à sessão atual.
     *
     * @param cookieDict dicionário com chaves "name" e "value" (obrigatórias);
     *                  opcionais: "path", "domain", "secure", "httpOnly", "expiry", "sameSite"
     * @return CompletableFuture que completa quando o cookie é adicionado
     */
    public CompletableFuture<Void> addCookie(Map<String, Object> cookieDict) {
        if (cookieDict.containsKey("sameSite")) {
            String sameSite = cookieDict.get("sameSite").toString();
            if (!Arrays.asList("Strict", "Lax", "None").contains(sameSite)) {
                return CompletableFuture.failedFuture(
                    new IllegalArgumentException("sameSite deve ser 'Strict', 'Lax' ou 'None'")
                );
            }
        }

        Map<String, Object> args = new HashMap<>();
        args.put("cookies", Collections.singletonList(cookieDict));

        return executeCdpCmd("Storage.setCookies", args, null)
            .thenApply(v -> null);
    }

    /**
     * Deleta um cookie pelo nome.
     *
     * @param name nome do cookie
     * @return CompletableFuture que completa quando o cookie é deletado
     */
    public CompletableFuture<Void> deleteCookie(String name) {
        Map<String, Object> args = new HashMap<>();
        args.put("name", name);

        return executeCdpCmd("Network.deleteCookies", args, null)
            .thenApply(v -> null);
    }

    /**
     * Deleta todos os cookies.
     *
     * @return CompletableFuture que completa quando os cookies são deletados
     */
    public CompletableFuture<Void> deleteAllCookies() {
        return executeCdpCmd("Network.clearBrowserCookies", null, null)
            .thenApply(v -> null);
    }

    /**
     * Envia teclas para o target (simula digitação).
     *
     * @param text texto a enviar
     * @return CompletableFuture que completa quando as teclas são enviadas
     */
    public CompletableFuture<Void> sendKeys(String text) {
        sendKeyLock.lock();
        try {
            // Continua após a digitação anterior; falha alheia não envenena esta chamada.
            CompletableFuture<Void> previous = sendKeyTail == null
                ? CompletableFuture.completedFuture(null)
                : sendKeyTail.handle((v, e) -> null);

            CompletableFuture<Void> result = previous;

            for (char c : text.toCharArray()) {
                KeyInfo keyInfo = KEY_MAPPING.get(c);

                if (keyInfo == null) {
                    // Nunca registrar o caractere (pode ser senha); só a categoria Unicode
                    JavaDriverlessLogger.warn(logger,
                        "Tecla não mapeada (categoria Unicode {}), ignorada", Character.getType(c));
                    continue;
                }

                boolean needsShift = SHIFT_KEY_NEEDED.indexOf(c) >= 0;
                int modifiers = needsShift ? 8 : 0;  // SHIFT = 8

                // KeyDown
                Map<String, Object> downArgs = new HashMap<>();
                downArgs.put("type", "keyDown");
                downArgs.put("code", keyInfo.code);
                downArgs.put("key", String.valueOf(c));
                downArgs.put("windowsVirtualKeyCode", keyInfo.keyCode);
                downArgs.put("nativeVirtualKeyCode", keyInfo.keyCode);
                downArgs.put("modifiers", modifiers);

                // KeyUp
                Map<String, Object> upArgs = new HashMap<>();
                upArgs.put("type", "keyUp");
                upArgs.put("code", keyInfo.code);
                upArgs.put("key", String.valueOf(c));
                upArgs.put("windowsVirtualKeyCode", keyInfo.keyCode);
                upArgs.put("nativeVirtualKeyCode", keyInfo.keyCode);
                upArgs.put("modifiers", modifiers);

                long delayMs = (long) (Math.random() * 40 + 10);  // 10-50ms

                // Cadeia SEQUENCIAL: keyDown -> atraso não bloqueante -> keyUp
                result = result
                    .thenCompose(v ->
                        executeCdpCmd("Input.dispatchKeyEvent", downArgs, null)
                            .thenApply(v2 -> null))
                    // FIX BUG-13: delayedExecutor em vez de Thread.sleep para não bloquear ForkJoinPool
                    .thenCompose(v ->
                        CompletableFuture.runAsync(() -> {},
                            CompletableFuture.delayedExecutor(delayMs, TimeUnit.MILLISECONDS)))
                    .thenCompose(v ->
                        executeCdpCmd("Input.dispatchKeyEvent", upArgs, null)
                            .thenApply(v2 -> null));
            }

            // Publica a nova cauda e libera o lock; o lock só protege a troca de referência.
            sendKeyTail = result.handle((v, e) -> null);
            return result;
        } finally {
            sendKeyLock.unlock();
        }
    }

    /**
     * Remove o foco do target.
     *
     * @return CompletableFuture que completa quando o foco é removido
     */
    public CompletableFuture<Void> unfocus() {
        // 100% NATIVO: Emulation.setFocusEmulationEnabled(false) emula o blur do documento sem JS
        // (antes window.blur() via Runtime.evaluate, detectável). Paridade com selenium_driverless.
        return executeCdpCmd("Emulation.setFocusEmulationEnabled", Map.of("enabled", false), null)
            .thenApply(v -> null);
    }

    /**
     * Imprime a página e retorna o PDF em base64.
     *
     * @return CompletableFuture com o PDF em base64
     */
    public CompletableFuture<String> printPage() {
        return executeCdpCmd("Page.printToPDF", null, null)
            .thenApply(result -> result.get("data").asText());
    }

    /**
     * Obtém o histórico de navegação.
     *
     * @return CompletableFuture com o histórico
     */
    public CompletableFuture<Map<String, Object>> getHistory() {
        return executeCdpCmd("Page.getNavigationHistory", null, null)
            .thenApply(result -> {
                Map<String, Object> history = new HashMap<>();
                history.put("currentIndex", result.get("currentIndex").asInt());

                List<Map<String, Object>> entries = new ArrayList<>();
                JsonNode entriesNode = result.get("entries");
                for (JsonNode entry : entriesNode) {
                    Map<String, Object> entryMap = new HashMap<>();
                    entry.fields().forEachRemaining(field ->
                        entryMap.put(field.getKey(), field.getValue())
                    );
                    entries.add(entryMap);
                }
                history.put("entries", entries);

                return history;
            });
    }

    /**
     * Define o código-fonte (OuterHTML) do target.
     *
     * @param source HTML para definir
     * @param timeout timeout em segundos
     * @return CompletableFuture que completa quando o source é definido
     */
    public CompletableFuture<Void> setSource(String source, float timeout) {
        return CompletableFuture.supplyAsync(() -> {
            long startNanos = System.nanoTime();

            while (true) {
                try {
                    WebElement document = getDocumentElem().join();
                    document.setSource(source).join();
                    return null;
                } catch (Exception e) {
                    onLoaded();
                }

                double elapsed = (System.nanoTime() - startNanos) / 1_000_000_000.0;
                if (elapsed > timeout) {
                    throw new RuntimeException(
                        "Não foi possível obter elemento documento não-stale"
                    );
                }

                try {
                    Thread.sleep(10);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new RuntimeException("Interrompido");
                }
            }
        });
    }

    /**
     * Aguarda por um download.
     *
     * @param timeout timeout em segundos
     * @return CompletableFuture com dados do download
     */
    public CompletableFuture<Map<String, Object>> waitDownload(float timeout) {
        return CompletableFuture.supplyAsync(() -> {
            // TODO: Implementar completamente quando base_frame estiver disponível
            throw new UnsupportedOperationException(
                "waitDownload requer implementação completa de frames"
            );
        });
    }

    /**
     * Obtém informações do target.
     *
     * @return CompletableFuture com as informações
     */
    public CompletableFuture<Map<String, Object>> getInfo() {
        Map<String, Object> args = new HashMap<>();
        args.put("targetId", id);

        return executeCdpCmd("Target.getTargetInfo", args, null)
            .thenApply(result -> {
                JsonNode targetInfo = result.get("targetInfo");
                Map<String, Object> info = new HashMap<>();
                targetInfo.fields().forEachRemaining(field ->
                    info.put(field.getKey(), field.getValue())
                );
                return info;
            });
    }

    /**
     * Obtém a árvore de frames.
     *
     * @return CompletableFuture com a árvore de frames
     */
    public CompletableFuture<Map<String, Object>> getFrameTree() {
        return executeCdpCmd("Page.getFrameTree", null, null)
            .thenApply(result -> {
                JsonNode frameTree = result.get("frameTree");
                return objectMapper.convertValue(frameTree, Map.class);
            });
    }

    /**
     * Obtém o frame base.
     *
     * @return CompletableFuture com o frame base
     */
    public CompletableFuture<Map<String, Object>> getBaseFrame() {
        return getFrameTree().thenApply(frameTree -> {
            @SuppressWarnings("unchecked")
            Map<String, Object> frame = (Map<String, Object>) frameTree.get("frame");
            return frame;
        });
    }

    /**
     * Obtém o tipo do target.
     *
     * @return CompletableFuture com o tipo
     */
    public CompletableFuture<String> getType() {
        if (type != null) {
            return CompletableFuture.completedFuture(type);
        }
        return getInfo().thenApply(info -> info.get("type").toString());
    }

    /**
     * Obtém o título do target (via CDP).
     *
     * @return CompletableFuture com o título
     */
    public CompletableFuture<String> getTitleViaCDP() {
        return targetInfoField("title");
    }

    /**
     * Obtém a URL do target (via CDP).
     *
     * @return CompletableFuture com a URL
     */
    public CompletableFuture<String> getUrlViaCDP() {
        return targetInfoField("url");
    }

    /**
     * Lê um campo textual de Target.getTargetInfo — nativo, sem JS. Usa asText() porque o valor
     * chega como JsonNode e toString() traria aspas literais.
     */
    private CompletableFuture<String> targetInfoField(String field) {
        Map<String, Object> args = new HashMap<>();
        args.put("targetId", id);
        return executeCdpCmd("Target.getTargetInfo", args, null)
            .thenApply(result -> {
                JsonNode ti = result.get("targetInfo");
                JsonNode node = ti != null ? ti.get(field) : null;
                return node != null && !node.isNull() ? node.asText() : "";
            });
    }

    /**
     * Obtém o ID da janela.
     *
     * @return CompletableFuture com o window ID
     */
    public CompletableFuture<Integer> getWindowId() {
        if (windowId != null) {
            return CompletableFuture.completedFuture(windowId);
        }

        return executeCdpCmd("Browser.getWindowForTarget",
            Map.of("targetId", id), null)
            .thenApply(result -> {
                int winId = result.get("windowId").asInt();
                windowId = winId;
                return winId;
            });
    }

    /**
     * Obtém condições de emulação de rede atuais.
     *
     * @return CompletableFuture com as condições
     */
    public CompletableFuture<Map<String, Object>> getNetworkConditions() {
        throw new UnsupportedOperationException("Não iniciado com chromedriver");
    }

    /**
     * Reseta condições de emulação de rede.
     *
     * @return CompletableFuture que completa quando as condições são resetadas
     */
    public CompletableFuture<Void> deleteNetworkConditions() {
        throw new UnsupportedOperationException("Não iniciado com chromedriver");
    }

}
