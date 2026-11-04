package io.github.selenium.javaDriverless.types;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Leitura de atributos/estado do elemento — 100% CDP NATIVO (zero injeção de JS). Antes cada método
 * rodava executeScript (Runtime.callFunctionOn), detectável por anti-bots (bet365 derruba a sessão).
 * Agora ancorado em nodeId: texto→describeNode, visibilidade→CSS+box, atributo→getAttributes, estado
 * live (enabled/selected)→Accessibility, css→getComputedStyleForNode. É o mesmo caminho nativo
 * validado em integrações de produção, agora como comportamento padrão do WebElement.
 *
 * <p>As primitivas package-static (cssProperty/axProperty/axValue/getAttribute) são reutilizadas
 * pelos demais helpers do WebElement (Finder/Interactions/Select) — a engine nativa vive aqui, não
 * numa classe paralela.
 */
final class WebElementAttributes {
    private WebElementAttributes() {
    }

    // CSS.enable / Accessibility.enable são pré-requisito de getComputedStyleForNode / getPartialAXTree.
    // Idempotente por target: cacheia o FUTURE do enable (não um booleano) para chamadas concorrentes
    // esperarem o MESMO enable completar. O estado vive no próprio Target (morre junto com ele);
    // chave = nome do domínio ("CSS"/"Accessibility"), sem crescimento ilimitado nem colisão.
    // NÃO usar um Map estático nem o próprio Target como chave — seu equals/hashCode se apoia no
    // campo `socket`, mutável e null em iframe antes do init (colapsaria iframes e quebraria o enable).
    private static Map<String, CompletableFuture<Void>> domainEnables(Target target) {
        Map<String, CompletableFuture<Void>> enables = target.cdpDomainEnables;
        if (enables == null) {
            synchronized (WebElementAttributes.class) {
                enables = target.cdpDomainEnables;
                if (enables == null) {
                    enables = new ConcurrentHashMap<>();
                    target.cdpDomainEnables = enables;
                }
            }
        }
        return enables;
    }

    private static CompletableFuture<Void> ensureEnabled(Target target, String domain) {
        Map<String, CompletableFuture<Void>> enables = domainEnables(target);
        CompletableFuture<Void> cached = enables.get(domain);
        if (cached != null) {
            return cached;
        }
        CompletableFuture<Void> created = target.executeCdpCmd(domain + ".enable", Map.of(), 5f)
            .<Void>thenApply(v -> null);
        CompletableFuture<Void> winner = enables.putIfAbsent(domain, created);
        if (winner != null) {
            return winner;
        }
        // Inserção garantida ANTES de anexar a limpeza: se o future já completou falho, o
        // callback roda agora e o remove(k, future) condicional realmente limpa a entrada.
        // (Com computeIfAbsent + whenComplete, o callback podia disparar antes da inserção e
        // o future falho ficava cacheado para sempre.)
        created.whenComplete((v, e) -> {
            if (e != null) {
                enables.remove(domain, created);
            }
        });
        return created;
    }

    private static CompletableFuture<Void> ensureCss(Target target) {
        return ensureEnabled(target, "CSS");
    }

    private static CompletableFuture<Void> ensureAx(Target target) {
        return ensureEnabled(target, "Accessibility");
    }

    // Properties com equivalente nativo por atributo HTML (getProperty sem JS).
    private static final Set<String> ATTR_PROPS =
        Set.of("id", "className", "name", "href", "src", "type", "title", "alt", "placeholder", "role");

    static CompletableFuture<String> getText(WebElement element) {
        element.checkStale();
        return element.getNodeId().thenCompose(nodeId ->
                element.target.executeCdpCmd("DOM.describeNode",
                    Map.of("nodeId", nodeId, "depth", -1, "pierce", false), 5f))
            .thenApply(res -> {
                StringBuilder sb = new StringBuilder();
                appendText(res.path("node"), sb);
                return sb.toString().trim();
            })
            .exceptionally(e -> "");
    }

    static CompletableFuture<Boolean> isDisplayed(WebElement element) {
        element.checkStale();
        return element.getNodeId().thenCompose(nodeId ->
                ensureCss(element.target).thenCompose(v ->
                    element.target.executeCdpCmd("CSS.getComputedStyleForNode",
                        Map.of("nodeId", nodeId), 5f)))
            .thenCompose(res -> {
                for (JsonNode p : res.path("computedStyle")) {
                    String n = p.path("name").asText();
                    String val = p.path("value").asText();
                    if (("display".equals(n) && "none".equals(val))
                            || ("visibility".equals(n) && "hidden".equals(val))
                            || ("opacity".equals(n) && "0".equals(val))) {
                        return CompletableFuture.completedFuture(false);
                    }
                }
                return hasArea(element);
            })
            .exceptionally(e -> false);
    }

    static CompletableFuture<Boolean> isEnabled(WebElement element) {
        element.checkStale();
        return axProperty(element, "disabled").thenCompose(axDisabled -> {
            if ("true".equals(axDisabled)) return CompletableFuture.completedFuture(false);
            return getAttribute(element, "disabled").thenApply(attr -> attr == null);
        }).exceptionally(e -> true);
    }

    static CompletableFuture<Boolean> isSelected(WebElement element) {
        element.checkStale();
        return axProperty(element, "checked").thenCompose(checked -> {
            if ("true".equals(checked)) return CompletableFuture.completedFuture(true);
            return axProperty(element, "selected").thenApply(sel -> "true".equals(sel));
        }).exceptionally(e -> false);
    }

    static CompletableFuture<String> getTagName(WebElement element) {
        element.checkStale();
        return element.getNodeId().thenCompose(nodeId ->
                element.target.executeCdpCmd("DOM.describeNode", Map.of("nodeId", nodeId), 5f))
            .thenApply(res -> res.path("node").path("nodeName").asText("").toLowerCase(Locale.ROOT))
            .exceptionally(e -> "");
    }

    static CompletableFuture<String> getAttribute(WebElement element, String name) {
        element.checkStale();
        return element.getNodeId().thenCompose(nodeId ->
                element.target.executeCdpCmd("DOM.getAttributes", Map.of("nodeId", nodeId), 5f))
            .thenApply(res -> {
                JsonNode attrs = res.path("attributes");
                for (int i = 0; i + 1 < attrs.size(); i += 2) {
                    if (name.equals(attrs.get(i).asText())) return attrs.get(i + 1).asText();
                }
                return (String) null;
            })
            .exceptionally(e -> null);
    }

    /**
     * Property do elemento sem injetar JS: cobre um allowlist mapeado para fonte nativa (value→AX,
     * checked/selected/disabled→AX live, tagName→describeNode, textContent→describeNode, id/class/
     * href/...→getAttributes). Property viva ARBITRÁRIA (offsetTop, dataset, props de framework) não
     * tem primitiva CDP e falha com UnsupportedOperationException — modo stealth não injeta JS.
     */
    static CompletableFuture<Object> getProperty(WebElement element, String name) {
        element.checkStale();
        if ("value".equals(name)) {
            return axValue(element).thenApply(v -> (Object) v);
        }
        if ("textContent".equals(name) || "innerText".equals(name)) {
            return getText(element).thenApply(v -> (Object) v);
        }
        if ("tagName".equals(name) || "nodeName".equals(name)) {
            return getTagName(element).thenApply(v -> (Object) v.toUpperCase(Locale.ROOT));
        }
        if ("checked".equals(name) || "selected".equals(name) || "disabled".equals(name)) {
            return axProperty(element, name).thenApply(v -> (Object) Boolean.valueOf("true".equals(v)));
        }
        if (ATTR_PROPS.contains(name)) {
            String attr = "className".equals(name) ? "class" : name;
            return getAttribute(element, attr).thenApply(v -> (Object) v);
        }
        return CompletableFuture.failedFuture(new UnsupportedOperationException(
            "getProperty(\"" + name + "\") nao tem equivalente CDP nativo (property viva arbitraria); "
            + "modo stealth nao injeta JS. Use um getter especifico ou element.executeRawScript(...)."));
    }

    static CompletableFuture<String> getValueOfCssProperty(WebElement element, String propertyName) {
        element.checkStale();
        return cssProperty(element, propertyName);
    }

    /**
     * Visível pelo critério FROUXO: só display≠none && visibility≠hidden (SEM opacity/box). Para
     * casos como popup de OTP/2FA cujo container conta como presente mesmo com box 0×0 ou opacity
     * animando de 0→1 — isDisplayed() (com box) daria falso-negativo. Mesmo critério do
     * NativeDomElement de integrações de produção.
     */
    static CompletableFuture<Boolean> isDisplayedLoose(WebElement element) {
        element.checkStale();
        return element.getNodeId().thenCompose(nodeId ->
                ensureCss(element.target).thenCompose(v ->
                    element.target.executeCdpCmd("CSS.getComputedStyleForNode",
                        Map.of("nodeId", nodeId), 5f)))
            .thenApply(res -> {
                for (JsonNode p : res.path("computedStyle")) {
                    String n = p.path("name").asText();
                    String val = p.path("value").asText();
                    if (("display".equals(n) && "none".equals(val))
                            || ("visibility".equals(n) && "hidden".equals(val))) {
                        return false;
                    }
                }
                return true;
            })
            .exceptionally(e -> false);
    }

    /** activeElement===este nó (foco), via Accessibility (nativo). Mesmo critério do NativeDomElement. */
    static CompletableFuture<Boolean> isFocused(WebElement element) {
        element.checkStale();
        return axProperty(element, "focused").thenApply("true"::equals);
    }

    // ---------- primitivas nativas reutilizadas pelos outros helpers do WebElement ----------

    /** Valor computado de uma CSS property via CSS.getComputedStyleForNode. "" se ausente. */
    static CompletableFuture<String> cssProperty(WebElement element, String name) {
        return element.getNodeId().thenCompose(nodeId ->
                ensureCss(element.target).thenCompose(v ->
                    element.target.executeCdpCmd("CSS.getComputedStyleForNode",
                        Map.of("nodeId", nodeId), 5f)))
            .thenApply(res -> {
                for (JsonNode p : res.path("computedStyle")) {
                    if (name.equals(p.path("name").asText())) return p.path("value").asText("");
                }
                return "";
            })
            .exceptionally(e -> "");
    }

    /** Uma property do accessibility tree (estado LIVE: "disabled"/"checked"/"selected"/"focused"). */
    static CompletableFuture<String> axProperty(WebElement element, String prop) {
        return element.getNodeId().thenCompose(nodeId ->
                ensureAx(element.target).thenCompose(v ->
                    element.target.executeCdpCmd("Accessibility.getPartialAXTree",
                        Map.of("nodeId", nodeId, "fetchRelatives", false), 5f)))
            .thenApply(res -> {
                for (JsonNode n : res.path("nodes")) {
                    for (JsonNode p : n.path("properties")) {
                        if (prop.equals(p.path("name").asText())) {
                            return p.path("value").path("value").asText(null);
                        }
                    }
                }
                return (String) null;
            })
            .exceptionally(e -> null);
    }

    /** value do controle via Accessibility (password mascarado, length correto). "" se não houver. */
    static CompletableFuture<String> axValue(WebElement element) {
        return element.getNodeId().thenCompose(nodeId ->
                ensureAx(element.target).thenCompose(v ->
                    element.target.executeCdpCmd("Accessibility.getPartialAXTree",
                        Map.of("nodeId", nodeId, "fetchRelatives", false), 5f)))
            .thenApply(res -> {
                for (JsonNode n : res.path("nodes")) {
                    JsonNode val = n.path("value").path("value");
                    if (!val.isMissingNode() && val.isTextual()) return val.asText("");
                }
                return "";
            })
            .exceptionally(e -> "");
    }

    // ---------- privados ----------

    /** Box com largura E altura > 0 (getBoxModel nativo). */
    private static CompletableFuture<Boolean> hasArea(WebElement element) {
        return element.getBoxModel()
            .thenApply(WebElementGeometry::hasPositiveArea)
            .exceptionally(e -> false);
    }

    /** textContent (walk de nós de texto da subárvore, sem pierce de shadow, sem separador). */
    private static void appendText(JsonNode node, StringBuilder sb) {
        if (node.path("nodeType").asInt() == 3) sb.append(node.path("nodeValue").asText());
        node.path("children").forEach(child -> appendText(child, sb));
    }
}
