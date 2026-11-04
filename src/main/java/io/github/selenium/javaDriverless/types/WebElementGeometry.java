package io.github.selenium.javaDriverless.types;

import com.fasterxml.jackson.databind.JsonNode;

import io.github.selenium.javaDriverless.scripts.Geometry;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

final class WebElementGeometry {
    private WebElementGeometry() {
    }

    static CompletableFuture<Boolean> isClickable(WebElement element, int listenerDepth, Map<String, Object> boxModel) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                if (!element.isVisible().join()) {
                    return false;
                }

                List<Map<String, Object>> listeners = element.getListeners(listenerDepth).join();
                if (listeners != null && !listeners.isEmpty()) {
                    for (Map<String, Object> listener : listeners) {
                        String type = (String) listener.get("type");
                        if ("click".equals(type) || "mousedown".equals(type) || "mouseup".equals(type)) {
                            return true;
                        }
                    }
                }

                String tagName = element.getTagName().join();
                if ("a".equalsIgnoreCase(tagName) || "button".equalsIgnoreCase(tagName)) {
                    return true;
                }

                String cursor = element.getValueOfCssProperty("cursor").join();
                return "pointer".equals(cursor);
            } catch (Exception e) {
                return false;
            }
        });
    }

    static CompletableFuture<Object[]> pVisible(WebElement element, Map<String, Object> boxModel) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                Map<String, Object> box = boxModel != null ? boxModel : element.getBoxModel().join();

                List<?> border = borderCoordinates(box);
                double[] bounds = bounds(border);
                double totalArea = (bounds[1] - bounds[0]) * (bounds[3] - bounds[2]);

                if (totalArea <= 0) {
                    return new Object[]{0.0, 0.0};
                }

                JsonNode metrics = element.target.executeCdpCmd("Page.getLayoutMetrics", Map.of(), 5f).join();
                JsonNode lv = metrics.path("cssLayoutViewport");
                if (lv.isMissingNode() || lv.path("clientWidth").isMissingNode()) {
                    lv = metrics.path("layoutViewport");
                }
                int viewWidth = lv.path("clientWidth").asInt();
                int viewHeight = lv.path("clientHeight").asInt();

                double visMinX = Math.max(bounds[0], 0);
                double visMaxX = Math.min(bounds[1], viewWidth);
                double visMinY = Math.max(bounds[2], 0);
                double visMaxY = Math.min(bounds[3], viewHeight);

                double visibleArea = Math.max(0, visMaxX - visMinX) * Math.max(0, visMaxY - visMinY);
                double percentage = visibleArea / totalArea;

                return new Object[]{percentage, visibleArea};
            } catch (Exception e) {
                return new Object[]{0.0, 0.0};
            }
        });
    }

    static CompletableFuture<Object[]> getCssMetrics(WebElement element) {
        return element.getNodeId().thenCompose(nodeId -> {
            Map<String, Object> args = new HashMap<>();
            args.put("nodeId", nodeId);

            return element.target.executeCdpCmd("DOM.getBoxModel", args, null);
        }).thenApply(result -> {
            JsonNode modelNode = result.get("model");
            @SuppressWarnings("unchecked")
            Map<String, Object> metrics = WebElement.objectMapper.convertValue(modelNode, Map.class);

            double zoom = 1.0;
            if (modelNode.has("width") && modelNode.has("height")) {
                JsonNode borderNode = modelNode.get("border");
                if (borderNode != null && borderNode.isArray() && borderNode.size() >= 8) {
                    double cssWidth = borderNode.get(2).asDouble() - borderNode.get(0).asDouble();
                    double actualWidth = modelNode.get("width").asDouble();
                    if (actualWidth > 0) {
                        zoom = cssWidth / actualWidth;
                    }
                }
            }

            return new Object[]{metrics, zoom};
        });
    }

    static CompletableFuture<double[]> getLocation(WebElement element) {
        // Documento-relativo (viewport + scroll), 100% NATIVO: border box (getBoxModel) + offset de
        // rolagem (Page.getLayoutMetrics). Antes era getBoundingClientRect+scrollX/Y via JS.
        element.checkStale();
        return element.getBoxModel().thenCompose(boxModel -> {
            List<?> border = borderCoordinates(boxModel);
            double[] b = bounds(border);
            return scrollOffset(element.target)
                .thenApply(scroll -> new double[]{b[0] + scroll[0], b[2] + scroll[1]});
        }).exceptionally(e -> new double[]{0, 0});
    }

    static CompletableFuture<double[]> getSize(WebElement element) {
        // Largura/altura da border box via DOM.getBoxModel nativo (antes getBoundingClientRect via JS).
        element.checkStale();
        return element.getBoxModel().thenApply(boxModel -> {
            List<?> border = borderCoordinates(boxModel);
            double[] b = bounds(border);
            return new double[]{b[1] - b[0], b[3] - b[2]};
        }).exceptionally(e -> new double[]{0, 0});
    }

    // Offset de rolagem da página (scrollX/scrollY) via Page.getLayoutMetrics — nativo, sem JS.
    private static CompletableFuture<double[]> scrollOffset(Target target) {
        return target.executeCdpCmd("Page.getLayoutMetrics", Map.of(), 5f).thenApply(res -> {
            JsonNode vv = res.path("cssVisualViewport");
            if (vv.isMissingNode() || vv.path("pageX").isMissingNode()) {
                vv = res.path("visualViewport");
            }
            return new double[]{vv.path("pageX").asDouble(0), vv.path("pageY").asDouble(0)};
        });
    }

    static CompletableFuture<double[][]> getQuads(WebElement element) {
        // 100% NATIVO: cantos da border box via DOM.getBoxModel, ordem [TL,TR,BR,BL],
        // viewport-relative — mesmo sistema de coordenadas do getBoundingClientRect antigo, SEM JS.
        // Se o nó não tem layout (display:none/detached), getBoxModel completa excepcionalmente e a
        // falha propaga (o caminho antigo também falhava, via rect zerado → randMidLoc por área 0).
        element.checkStale();
        return element.getBoxModel().thenApply(boxModel -> {
            List<?> border = borderCoordinates(boxModel);
            double[][] quads = new double[4][2];
            for (int i = 0; i < 4; i++) {
                quads[i][0] = coordinate(border, i * 2);
                quads[i][1] = coordinate(border, i * 2 + 1);
            }
            return quads;
        });
    }

    static CompletableFuture<double[]> getMidLocation(WebElement element) {
        // O click() passa por aqui; deriva o ponto dos quads NATIVOS (getBoxModel) e aplica o mesmo
        // Geometry.randMidLoc (humanização/jitter idênticos). Zero JS — injetar aqui derruba a sessão.
        return getQuads(element)
            .thenApply(quads -> Geometry.randMidLoc(quads, 1.0, 1.0, 0.5, 0.5, 0.05));
    }

    static CompletableFuture<Map<String, Object>> getMidLocation(WebElement element, float spreadA,
                                                                 float spreadB, float biasA,
                                                                 float biasB, float timeout) {
        return element.getBoxModel().thenApply(boxModel -> {
            List<?> border = borderCoordinates(boxModel);
            double[] bounds = bounds(border);

            double midX = bounds[0] + (bounds[1] - bounds[0]) * biasA;
            double midY = bounds[2] + (bounds[3] - bounds[2]) * biasB;

            Map<String, Object> location = new HashMap<>();
            location.put("x", midX);
            location.put("y", midY);
            location.put("type", "interactable");
            return location;
        });
    }

    static CompletableFuture<Boolean> isVisible(WebElement element) {
        // Área renderizada REAL (largura E altura > 0), não apenas box presente — mesmo critério do
        // NativeDomElement dos projetos bet365 (um box 0×0 não conta como visível).
        return element.getBoxModel()
            .thenApply(WebElementGeometry::hasPositiveArea)
            .exceptionally(e -> false);
    }

    static CompletableFuture<Map<String, Object>> locationOnceScrolledIntoView(WebElement element) {
        return scrollTo(element, null).thenCompose(v -> element.getLocation().thenApply(loc -> {
            Map<String, Object> result = new HashMap<>();
            result.put("x", loc[0]);
            result.put("y", loc[1]);
            return result;
        }));
    }

    static CompletableFuture<Void> scrollTo(WebElement element, Map<String, Object> rect) {
        if (rect != null) {
            return element.getNodeId().thenCompose(nodeId -> {
                Map<String, Object> args = new HashMap<>();
                args.put("nodeId", nodeId);
                args.put("rect", rect);

                return element.target.executeCdpCmd("DOM.scrollIntoViewIfNeeded", args, null)
                    .thenApply(v -> null);
            });
        }

        return element.getNodeId().thenCompose(nodeId ->
                element.target.executeCdpCmd("DOM.scrollIntoViewIfNeeded", Map.of("nodeId", nodeId), null))
            .thenApply(v -> null);
    }

    static CompletableFuture<Map<String, Object>> getRect(WebElement element) {
        return element.getBoxModel().thenApply(boxModel -> {
            List<?> border = borderCoordinates(boxModel);
            double[] bounds = bounds(border);

            Map<String, Object> rect = new HashMap<>();
            rect.put("x", bounds[0]);
            rect.put("y", bounds[2]);
            rect.put("width", bounds[1] - bounds[0]);
            rect.put("height", bounds[3] - bounds[2]);
            return rect;
        });
    }

    static CompletableFuture<Map<String, Object>> getBoxModel(WebElement element) {
        return element.getNodeId().thenCompose(nodeId -> {
            Map<String, Object> args = new HashMap<>();
            args.put("nodeId", nodeId);

            return element.target.executeCdpCmd("DOM.getBoxModel", args, null);
        }).thenApply(result -> {
            JsonNode modelNode = result.get("model");
            @SuppressWarnings("unchecked")
            Map<String, Object> model = WebElement.objectMapper.convertValue(modelNode, Map.class);
            return model;
        });
    }

    static boolean hasPositiveArea(Map<String, Object> boxModel) {
        List<?> border = borderCoordinates(boxModel);
        double[] b = bounds(border);
        return b[1] > b[0] && b[3] > b[2];
    }

    private static List<?> borderCoordinates(Map<String, Object> boxModel) {
        Object rawBorder = boxModel != null ? boxModel.get("border") : null;
        if (!(rawBorder instanceof List<?> border) || border.size() < 8) {
            throw new IllegalArgumentException("CDP box model does not contain an 8-value border quad");
        }
        return border;
    }

    private static double coordinate(List<?> border, int index) {
        Object value = border.get(index);
        if (!(value instanceof Number number)) {
            throw new IllegalArgumentException("CDP border coordinate is not numeric at index " + index);
        }
        return number.doubleValue();
    }

    private static double[] bounds(List<?> border) {
        double minX = Math.min(Math.min(coordinate(border, 0), coordinate(border, 2)),
            Math.min(coordinate(border, 4), coordinate(border, 6)));
        double maxX = Math.max(Math.max(coordinate(border, 0), coordinate(border, 2)),
            Math.max(coordinate(border, 4), coordinate(border, 6)));
        double minY = Math.min(Math.min(coordinate(border, 1), coordinate(border, 3)),
            Math.min(coordinate(border, 5), coordinate(border, 7)));
        double maxY = Math.max(Math.max(coordinate(border, 1), coordinate(border, 3)),
            Math.max(coordinate(border, 5), coordinate(border, 7)));
        return new double[]{minX, maxX, minY, maxY};
    }
}
