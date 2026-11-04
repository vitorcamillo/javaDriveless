package io.github.selenium.javaDriverless.types;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

/**
 * Busca de elementos — 100% CDP NATIVO (zero injeção de JS). Antes cada By rodava executeScript
 * (querySelectorAll/document.evaluate via Runtime.callFunctionOn), detectável por anti-bots. Agora:
 * CSS/ID/CLASS_NAME/NAME/TAG_NAME → DOM.querySelectorAll escopado no nodeId do elemento; XPATH →
 * DOM.performSearch. Resultados são construídos por backendNodeId (via DOM.describeNode), a mesma
 * identidade estável que getChildren/getParent usam — sobrevive a reload e casa no equals().
 */
final class WebElementFinder {
    private WebElementFinder() {
    }

    static CompletableFuture<WebElement> findElement(WebElement element, String by, String value, Float timeout) {
        element.checkStale();

        return CompletableFuture.supplyAsync(() -> {
            long startNanos = System.nanoTime();
            List<WebElement> elems = new ArrayList<>();
            long sleepMs = 10;

            while (elems.isEmpty()) {
                try {
                    elems = findElements(element, by, value).join();
                } catch (Exception e) {
                    // Ignorar erros e tentar novamente
                }

                if (timeout != null) {
                    double elapsed = (System.nanoTime() - startNanos) / 1_000_000_000.0;
                    if (elapsed > timeout) {
                        break;
                    }
                }

                if (elems.isEmpty()) {
                    try {
                        Thread.sleep(sleepMs);
                        sleepMs = Math.min(sleepMs * 2, 200);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        break;
                    }
                }
            }

            if (elems.isEmpty()) {
                throw new WebElement.NoSuchElementException(
                    String.format("Elemento não encontrado: %s='%s'", by, value)
                );
            }

            return elems.get(0);
        });
    }

    static CompletableFuture<List<WebElement>> findElements(WebElement element, String by, String value) {
        element.checkStale();

        SearchSpec search = normalize(by, value);
        if (By.XPATH.equals(search.by)) {
            return findByXPath(element, search.value);
        }
        if (By.CSS_SELECTOR.equals(search.by)) {
            return element.getNodeId().thenCompose(nodeId ->
                    element.target.executeCdpCmd("DOM.querySelectorAll",
                        Map.of("nodeId", nodeId, "selector", search.value), 10f))
                .thenCompose(res -> toElements(element, res.path("nodeIds")));
        }
        return CompletableFuture.failedFuture(
            new IllegalArgumentException("Estratégia de busca inesperada: " + search.by)
        );
    }

    // XPath via DOM.performSearch (document-wide — performSearch não aceita nó de contexto). Cobre
    // driver.findElement (escopo = documento), inclusive XPath por texto. Para XPath RELATIVO
    // escopado num elemento não há filtro de subárvore aqui (limitação conhecida do performSearch).
    private static CompletableFuture<List<WebElement>> findByXPath(WebElement element, String xpath) {
        return element.target.executeCdpCmd("DOM.performSearch", Map.of("query", xpath), 10f)
            .thenCompose(s -> {
                String searchId = s.path("searchId").asText(null);
                int count = s.path("resultCount").asInt();
                if (searchId == null) {
                    return CompletableFuture.completedFuture(new ArrayList<WebElement>());
                }
                if (count == 0) {
                    discard(element, searchId);
                    return CompletableFuture.completedFuture(new ArrayList<WebElement>());
                }
                return element.target.executeCdpCmd("DOM.getSearchResults",
                        Map.of("searchId", searchId, "fromIndex", 0, "toIndex", count), 10f)
                    .thenCompose(r -> toElements(element, r.path("nodeIds")))
                    .whenComplete((res, err) -> discard(element, searchId));
            });
    }

    private static void discard(WebElement element, String searchId) {
        try {
            element.target.executeCdpCmd("DOM.discardSearchResults", Map.of("searchId", searchId), 5f);
        } catch (Exception ignored) {
        }
    }

    // Resolve cada nodeId para backendNodeId (DOM.describeNode) e constrói o WebElement pela via de
    // identidade estável (WebElementNode.fromBackendNode) — o backendNodeId sobrevive a mutação/reload
    // e casa no equals() com elementos vindos de getChildren/getParent.
    private static CompletableFuture<List<WebElement>> toElements(WebElement scope, JsonNode nodeIds) {
        List<CompletableFuture<WebElement>> futures = new ArrayList<>();
        for (JsonNode idNode : nodeIds) {
            int nodeId = idNode.asInt();
            futures.add(scope.target.executeCdpCmd("DOM.describeNode", Map.of("nodeId", nodeId), 5f)
                .thenApply(res -> WebElementNode.fromBackendNode(
                    scope, res.path("node").path("backendNodeId").asInt())));
        }
        return CompletableFuture.allOf(futures.toArray(new CompletableFuture[0]))
            .thenApply(v -> {
                List<WebElement> out = new ArrayList<>();
                for (CompletableFuture<WebElement> f : futures) {
                    out.add(f.join());
                }
                return out;
            });
    }

    // ID/CLASS_NAME/NAME/TAG_NAME viram seletor CSS (querySelectorAll escopado no nodeId) — mais
    // consistente e nativo que o antigo //* XPath document-wide. CSS/XPATH passam direto.
    private static SearchSpec normalize(String by, String value) {
        if ("css".equals(by) || By.CSS_SELECTOR.equals(by) || By.CSS.equals(by)) {
            return new SearchSpec(By.CSS_SELECTOR, value);
        }
        if (By.TAG_NAME.equals(by)) {
            return new SearchSpec(By.CSS_SELECTOR, value);
        }
        if (By.ID.equals(by)) {
            return new SearchSpec(By.CSS_SELECTOR, "[id=\"" + cssEscape(value) + "\"]");
        }
        if (By.CLASS_NAME.equals(by)) {
            return new SearchSpec(By.CSS_SELECTOR, "[class~=\"" + cssEscape(value) + "\"]");
        }
        if (By.NAME.equals(by)) {
            return new SearchSpec(By.CSS_SELECTOR, "[name=\"" + cssEscape(value) + "\"]");
        }
        if (By.XPATH.equals(by)) {
            return new SearchSpec(By.XPATH, value);
        }
        return new SearchSpec(by, value);
    }

    private static String cssEscape(String value) {
        return value.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    private static final class SearchSpec {
        private final String by;
        private final String value;

        private SearchSpec(String by, String value) {
            this.by = by;
            this.value = value;
        }
    }
}
