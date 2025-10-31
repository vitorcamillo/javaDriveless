package io.github.selenium.javaDriverless.scripts;

import org.apache.commons.math3.analysis.interpolation.SplineInterpolator;
import org.apache.commons.math3.analysis.polynomials.PolynomialSplineFunction;
import org.apache.commons.math3.distribution.NormalDistribution;
import org.apache.commons.math3.distribution.BetaDistribution;

import java.util.*;
import java.util.stream.IntStream;

/**
 * Funções geométricas e de geração de trajetórias humanizadas de mouse.
 * <p>
 * Este módulo fornece funções para cálculos geométricos, incluindo:
 * - Interseções de retângulos e polígonos
 * - Geração de trajetórias humanizadas com curvas suaves
 * - Cálculos de áreas e sobreposições
 * </p>
 */
public class Geometry {
    
    private static final Random random = new Random();
    
    /**
     * Gera valores aleatórios distribuídos gaussianamente com bias.
     *
     * @param spread espalhamento da distribuição
     * @param border margem mínima das bordas (padrão 0.05)
     * @param bias viés central (padrão 0.5)
     * @return valor aleatório entre border e 1-border
     */
    public static double gaussianBiasRand(double spread, double border, double bias) {
        if (spread == 0) {
            return bias;
        }
        
        NormalDistribution dist = new NormalDistribution(bias, spread / 6.0);
        double res;
        
        do {
            res = dist.sample();
        } while (res < border || res > (1 - border));
        
        return res;
    }
    
    /**
     * Calcula um ponto dentro de um retângulo dados parâmetros a e b.
     *
     * @param points quatro pontos definindo o retângulo [[x1,y1], [x2,y2], [x3,y3], [x4,y4]]
     * @param a ponto variando de 0 a 1 na linha |AB|
     * @param b ponto variando de 0 a 1 na linha |BC|
     * @return coordenadas [x, y] do ponto dentro do retângulo
     */
    public static double[] pointInRectangle(double[][] points, double a, double b) {
        if (points.length != 4) {
            throw new IllegalArgumentException("Entrada deve conter quatro pontos definindo um retângulo");
        }
        
        double x = (1 - b) * (points[0][0] + a * (points[1][0] - points[0][0])) +
                   b * (points[3][0] + a * (points[2][0] - points[3][0]));
        
        double y = (1 - b) * (points[0][1] + a * (points[1][1] - points[0][1])) +
                   b * (points[3][1] + a * (points[2][1] - points[3][1]));
        
        return new double[]{x, y};
    }
    
    /**
     * Encontra a interseção de dois segmentos de linha (p1p2 e p3p4).
     *
     * @param p1 primeiro ponto do primeiro segmento
     * @param p2 segundo ponto do primeiro segmento
     * @param p3 primeiro ponto do segundo segmento
     * @param p4 segundo ponto do segundo segmento
     * @return coordenadas da interseção ou null se não houver interseção
     */
    public static double[] edgeIntersection(double[] p1, double[] p2, double[] p3, double[] p4) {
        double a1 = p2[1] - p1[1];
        double b1 = p1[0] - p2[0];
        double c1 = a1 * p1[0] + b1 * p1[1];
        
        double a2 = p4[1] - p3[1];
        double b2 = p3[0] - p4[0];
        double c2 = a2 * p3[0] + b2 * p3[1];
        
        double determinant = a1 * b2 - a2 * b1;
        
        if (determinant == 0) {
            return null; // Linhas paralelas
        }
        
        double x = (b2 * c1 - b1 * c2) / determinant;
        double y = (a1 * c2 - a2 * c1) / determinant;
        
        // Verificar se o ponto de interseção está dentro dos dois segmentos
        if (x >= Math.min(p1[0], p2[0]) && x <= Math.max(p1[0], p2[0]) &&
            y >= Math.min(p1[1], p2[1]) && y <= Math.max(p1[1], p2[1]) &&
            x >= Math.min(p3[0], p4[0]) && x <= Math.max(p3[0], p4[0]) &&
            y >= Math.min(p3[1], p4[1]) && y <= Math.max(p3[1], p4[1])) {
            return new double[]{x, y};
        }
        
        return null;
    }
    
    /**
     * Calcula a área de um polígono usando a fórmula do cadarço (shoelace).
     *
     * @param vertices vértices do polígono
     * @return área do polígono
     */
    public static double polygonArea(double[][] vertices) {
        int n = vertices.length;
        double area = 0.0;
        
        for (int i = 0; i < n; i++) {
            int j = (i + 1) % n;
            area += vertices[i][0] * vertices[j][1];
            area -= vertices[j][0] * vertices[i][1];
        }
        
        return Math.abs(area) / 2.0;
    }
    
    /**
     * Verifica se um ponto está dentro de um polígono usando ray casting.
     *
     * @param point ponto a verificar [x, y]
     * @param polygon vértices do polígono
     * @return true se o ponto está dentro do polígono
     */
    public static boolean isPointInPolygon(double[] point, double[][] polygon) {
        double x = point[0];
        double y = point[1];
        int n = polygon.length;
        boolean inside = false;
        
        double p1x = polygon[0][0];
        double p1y = polygon[0][1];
        
        for (int i = 0; i <= n; i++) {
            int idx = i % n;
            double p2x = polygon[idx][0];
            double p2y = polygon[idx][1];
            
            if (y > Math.min(p1y, p2y)) {
                if (y <= Math.max(p1y, p2y)) {
                    if (x <= Math.max(p1x, p2x)) {
                        if (p1y != p2y) {
                            double xints = (y - p1y) * (p2x - p1x) / (p2y - p1y) + p1x;
                            if (p1x == p2x || x <= xints) {
                                inside = !inside;
                            }
                        }
                    }
                }
            }
            p1x = p2x;
            p1y = p2y;
        }
        
        return inside;
    }
    
    /**
     * Calcula a interseção de dois retângulos.
     *
     * @param rect1 primeiro retângulo (4 pontos)
     * @param rect2 segundo retângulo (4 pontos)
     * @return polígono de interseção (vazio se não houver interseção)
     */
    public static double[][] intersectRectangles(double[][] rect1, double[][] rect2) {
        List<double[]> intersectionPoints = new ArrayList<>();
        
        // Definir arestas dos retângulos
        for (int i = 0; i < 4; i++) {
            for (int j = 0; j < 4; j++) {
                double[] point = edgeIntersection(
                    rect1[i], rect1[(i + 1) % 4],
                    rect2[j], rect2[(j + 1) % 4]
                );
                if (point != null) {
                    intersectionPoints.add(point);
                }
            }
        }
        
        // Verificar se algum canto do retângulo 1 está dentro do retângulo 2
        for (double[] corner : rect1) {
            if (isPointInPolygon(corner, rect2)) {
                intersectionPoints.add(corner);
            }
        }
        
        // Verificar se algum canto do retângulo 2 está dentro do retângulo 1
        for (double[] corner : rect2) {
            if (isPointInPolygon(corner, rect1)) {
                intersectionPoints.add(corner);
            }
        }
        
        if (intersectionPoints.isEmpty()) {
            return new double[0][0];
        }
        
        // Remover duplicatas
        Set<String> seen = new HashSet<>();
        List<double[]> unique = new ArrayList<>();
        
        for (double[] point : intersectionPoints) {
            String key = String.format("%.2f,%.2f", point[0], point[1]);
            if (!seen.contains(key)) {
                seen.add(key);
                unique.add(point);
            }
        }
        
        if (unique.size() < 3) {
            return new double[0][0];
        }
        
        // Ordenar pontos para formar um polígono válido
        double[] centroid = new double[2];
        for (double[] point : unique) {
            centroid[0] += point[0];
            centroid[1] += point[1];
        }
        centroid[0] /= unique.size();
        centroid[1] /= unique.size();
        
        unique.sort((a, b) -> {
            double angle1 = Math.atan2(a[1] - centroid[1], a[0] - centroid[0]);
            double angle2 = Math.atan2(b[1] - centroid[1], b[0] - centroid[0]);
            return Double.compare(angle1, angle2);
        });
        
        return unique.toArray(new double[0][]);
    }
    
    /**
     * Calcula a porcentagem de sobreposição de dois retângulos.
     *
     * @param rect1 primeiro retângulo
     * @param rect2 segundo retângulo
     * @return array [porcentagem_sobreposição, polígono_interseção]
     */
    public static Object[] overlap(double[][] rect1, double[][] rect2) {
        double[][] intersection = intersectRectangles(rect1, rect2);
        
        if (intersection.length == 0) {
            return new Object[]{0.0, new double[0][0]};
        }
        
        double overlapArea = polygonArea(intersection);
        double rect1Area = polygonArea(rect1);
        double rect2Area = polygonArea(rect2);
        
        double smallerArea = Math.min(rect1Area, rect2Area);
        double percentageOverlap = (overlapArea / smallerArea) * 100.0;
        
        return new Object[]{percentageOverlap, intersection};
    }
    
    /**
     * Gera uma localização aleatória no meio de um elemento com bias gaussiano.
     *
     * @param elem quatro pontos definindo um retângulo
     * @param spreadA espalhamento no eixo A
     * @param spreadB espalhamento no eixo B
     * @param biasA bias no eixo A (padrão 0.5)
     * @param biasB bias no eixo B (padrão 0.5)
     * @param border margem da borda (padrão 0.05)
     * @return coordenadas [x, y] do ponto aleatório
     */
    public static double[] randMidLoc(double[][] elem, double spreadA, double spreadB,
                                     double biasA, double biasB, double border) {
        if (elem.length != 4) {
            throw new IllegalArgumentException("Entrada deve conter quatro pontos definindo um retângulo");
        }
        
        if (biasA < 0 || biasA > 1 || biasB < 0 || biasB > 1) {
            throw new IllegalArgumentException("Bias deve estar entre 0 e 1");
        }
        
        // Garantir que o elemento tem uma área
        double[] ab = {elem[1][0] - elem[0][0], elem[1][1] - elem[0][1]};
        double[] bc = {elem[2][0] - elem[1][0], elem[2][1] - elem[1][1]};
        double area = Math.abs(ab[0] * bc[1] - ab[1] * bc[0]);
        
        if (area == 0) {
            throw new IllegalArgumentException("A área do elemento é 0");
        }
        
        double pointA = gaussianBiasRand(spreadA, border, biasA);
        double pointB = gaussianBiasRand(spreadB, border, biasB);
        
        return pointInRectangle(elem, pointA, pointB);
    }
    
    /**
     * Gera um valor com bias em torno de 0.5 usando distribuição beta.
     *
     * @param strength força do bias (0 a 1)
     * @param maxOffset deslocamento máximo de 0.5
     * @return valor biased
     */
    public static double bias0Dot5(double strength, double maxOffset) {
        double alpha = 2 * strength;
        double beta = 2 * (1 - strength);
        
        double lowerBound = Math.max(0.5 - maxOffset, 0);
        double upperBound = Math.min(0.5 + maxOffset, 1);
        
        BetaDistribution betaDist = new BetaDistribution(alpha, beta);
        double randValue = betaDist.sample();
        
        return lowerBound + (randValue * (upperBound - lowerBound));
    }
    
    /**
     * Obtém os limites de um conjunto de vértices.
     *
     * @param vertices vértices do polígono
     * @return array [xMin, yMin, xMax, yMax]
     */
    public static double[] getBounds(double[][] vertices) {
        double xMin = Double.MAX_VALUE;
        double yMin = Double.MAX_VALUE;
        double xMax = Double.MIN_VALUE;
        double yMax = Double.MIN_VALUE;
        
        for (double[] vertex : vertices) {
            xMin = Math.min(xMin, vertex[0]);
            yMin = Math.min(yMin, vertex[1]);
            xMax = Math.max(xMax, vertex[0]);
            yMax = Math.max(yMax, vertex[1]);
        }
        
        return new double[]{xMin, yMin, xMax, yMax};
    }
    
    /**
     * Calcula a posição em um caminho em um determinado tempo com aceleração.
     *
     * @param path caminho como lista de pontos [x, y]
     * @param totalTime tempo total da trajetória
     * @param time tempo atual
     * @param accel fator de aceleração
     * @param midTime ponto médio da transição (padrão 0.5)
     * @return coordenadas [x, y] na posição especificada
     */
    public static int[] posAtTime(List<int[]> path, double totalTime, double time, 
                                  double accel, double midTime) {
        if (time > totalTime || time < 0) {
            throw new IllegalArgumentException("Tempo deve estar entre 0 e totalTime");
        }
        
        double normalizedTime = time / totalTime;
        
        // Aplicar easing cúbico para aceleração e desaceleração
        if (normalizedTime < midTime) {
            normalizedTime = Math.pow(normalizedTime * 2, accel) / 2;
        } else {
            normalizedTime = (1.0 - Math.pow(1.0 - (normalizedTime - 0.5) * 2, accel)) / 2 + 0.5;
        }
        
        // Encontrar o índice do valor de tempo mais próximo no caminho
        int idx = (int) Math.round(normalizedTime * (path.size() - 1));
        idx = Math.max(0, Math.min(idx, path.size() - 1));
        
        return path.get(idx);
    }
    
    /**
     * Gera um caminho suave entre dois pontos usando interpolação spline.
     *
     * @param start ponto inicial [x, y]
     * @param end ponto final [x, y]
     * @param n número de pontos intermediários (padrão 10)
     * @param smoothness suavidade da aleatoriedade (padrão 2)
     * @return lista de pontos do caminho
     */
    public static List<int[]> generatePath(double[] start, double[] end, int n, double smoothness) {
        NormalDistribution dist = new NormalDistribution(0, smoothness);
        
        double[] xPoints = new double[n];
        double[] yPoints = new double[n];
        
        for (int i = 0; i < n; i++) {
            double t = (double) i / (n - 1);
            xPoints[i] = start[0] + t * (end[0] - start[0]) + dist.sample();
            yPoints[i] = start[1] + t * (end[1] - start[1]) + dist.sample();
        }
        
        // Garantir que início e fim sejam exatos
        xPoints[0] = start[0];
        yPoints[0] = start[1];
        xPoints[n - 1] = end[0];
        yPoints[n - 1] = end[1];
        
        // Interpolar usando spline
        SplineInterpolator interpolator = new SplineInterpolator();
        double[] tValues = IntStream.range(0, n).mapToDouble(i -> (double) i).toArray();
        
        PolynomialSplineFunction splineX = interpolator.interpolate(tValues, xPoints);
        PolynomialSplineFunction splineY = interpolator.interpolate(tValues, yPoints);
        
        // Gerar pontos ao longo do spline
        double distance = Math.sqrt(Math.pow(end[0] - start[0], 2) + Math.pow(end[1] - start[1], 2));
        int numPoints = (int) (distance * 10);
        
        List<int[]> path = new ArrayList<>();
        for (int i = 0; i < numPoints; i++) {
            double t = (double) i / (numPoints - 1) * (n - 1);
            int x = (int) Math.round(splineX.value(t));
            int y = (int) Math.round(splineY.value(t));
            path.add(new int[]{x, y});
        }
        
        return path;
    }
    
    /**
     * Gera um caminho combinado através de múltiplas coordenadas.
     *
     * @param coordinates lista de coordenadas [x, y]
     * @param nPointsSoft número de pontos para segmento suave (padrão 5)
     * @param smoothSoft suavidade do segmento suave (padrão 10)
     * @param nPointsDistort número de pontos para segmento distorcido (padrão 100)
     * @param smoothDistort suavidade do segmento distorcido (padrão 0.4)
     * @return lista de pontos do caminho combinado
     */
    public static List<int[]> genCombinedPath(List<double[]> coordinates, int nPointsSoft,
                                             double smoothSoft, int nPointsDistort, double smoothDistort) {
        List<int[]> combinedPath = new ArrayList<>();
        
        for (int i = 0; i < coordinates.size() - 1; i++) {
            double[] start = coordinates.get(i);
            double[] end = coordinates.get(i + 1);
            
            // Gerar segmento suave
            List<int[]> segmentSoft = generatePath(start, end, nPointsSoft, smoothSoft);
            
            // Gerar segmento distorcido
            List<int[]> segmentDistort = generatePath(start, end, nPointsDistort, smoothDistort);
            
            // Combinar os segmentos com interpolação baseada em frequência
            int[] lastPoint = null;
            
            for (int j = 0; j < segmentSoft.size(); j++) {
                double t = (double) j / (segmentSoft.size() - 1);
                int distortIdx = Math.min((int) (t * (segmentDistort.size() - 1)), segmentDistort.size() - 1);
                int softIdx = j;
                
                int interpX = (int) ((1 - t) * segmentDistort.get(distortIdx)[0] + t * segmentSoft.get(softIdx)[0]);
                int interpY = (int) ((1 - t) * segmentDistort.get(distortIdx)[1] + t * segmentSoft.get(softIdx)[1]);
                
                int[] point = {interpX, interpY};
                
                if (lastPoint == null || point[0] != lastPoint[0] || point[1] != lastPoint[1]) {
                    combinedPath.add(point);
                    lastPoint = point;
                }
            }
        }
        
        return combinedPath;
    }
}

