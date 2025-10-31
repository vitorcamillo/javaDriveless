package io.github.selenium.driverless;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import io.github.selenium.javaDriverless.scripts.Geometry;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.*;

/**
 * Testes para cálculos geométricos e trajetórias humanizadas.
 */
public class GeometryTest {
    
    @Test
    @DisplayName("Deve calcular área de polígono")
    public void testPolygonArea() {
        double[][] square = {
            {0, 0},
            {100, 0},
            {100, 100},
            {0, 100}
        };
        
        double area = Geometry.polygonArea(square);
        assertThat(area).isEqualTo(10000.0);
    }
    
    @Test
    @DisplayName("Deve verificar ponto dentro de polígono")
    public void testPointInPolygon() {
        double[][] square = {
            {0, 0},
            {100, 0},
            {100, 100},
            {0, 100}
        };
        
        boolean inside = Geometry.isPointInPolygon(new double[]{50, 50}, square);
        assertThat(inside).isTrue();
        
        boolean outside = Geometry.isPointInPolygon(new double[]{150, 150}, square);
        assertThat(outside).isFalse();
    }
    
    @Test
    @DisplayName("Deve gerar valor com bias gaussiano")
    public void testGaussianBiasRand() {
        double value = Geometry.gaussianBiasRand(1.0, 0.05, 0.5);
        assertThat(value).isBetween(0.05, 0.95);
    }
    
    @Test
    @DisplayName("Deve gerar valor com bias em 0.5")
    public void testBias0Dot5() {
        double value = Geometry.bias0Dot5(0.5, 0.5);
        assertThat(value).isBetween(0.0, 1.0);
    }
    
    @Test
    @DisplayName("Deve calcular ponto dentro de retângulo")
    public void testPointInRectangle() {
        double[][] rect = {
            {0, 0},
            {100, 0},
            {100, 100},
            {0, 100}
        };
        
        double[] point = Geometry.pointInRectangle(rect, 0.5, 0.5);
        assertThat(point).hasSize(2);
        assertThat(point[0]).isBetween(0.0, 100.0);
        assertThat(point[1]).isBetween(0.0, 100.0);
    }
    
    @Test
    @DisplayName("Deve gerar localização aleatória no meio do elemento")
    public void testRandMidLoc() {
        double[][] elem = {
            {0, 0},
            {200, 0},
            {200, 200},
            {0, 200}
        };
        
        double[] loc = Geometry.randMidLoc(elem, 1.0, 1.0, 0.5, 0.5, 0.05);
        assertThat(loc).hasSize(2);
        assertThat(loc[0]).isBetween(10.0, 190.0);
        assertThat(loc[1]).isBetween(10.0, 190.0);
        
        System.out.println("Localização aleatória gerada: " + 
            String.format("[%.2f, %.2f]", loc[0], loc[1]));
    }
    
    @Test
    @DisplayName("Deve gerar caminho suave entre pontos")
    public void testGeneratePath() {
        double[] start = {0, 0};
        double[] end = {100, 100};
        
        List<int[]> path = Geometry.generatePath(start, end, 10, 2.0);
        assertThat(path).isNotEmpty();
        assertThat(path.size()).isGreaterThan(10);
        
        // Primeiro ponto deve ser o início
        assertThat(path.get(0)[0]).isCloseTo(0, within(5));
        assertThat(path.get(0)[1]).isCloseTo(0, within(5));
        
        // Último ponto deve ser o fim
        int[] lastPoint = path.get(path.size() - 1);
        assertThat(lastPoint[0]).isCloseTo(100, within(5));
        assertThat(lastPoint[1]).isCloseTo(100, within(5));
        
        System.out.println("Caminho gerado com " + path.size() + " pontos");
    }
    
    @Test
    @DisplayName("Deve gerar caminho combinado através de múltiplas coordenadas")
    public void testGenCombinedPath() {
        List<double[]> coords = new ArrayList<>();
        coords.add(new double[]{0, 0});
        coords.add(new double[]{100, 100});
        coords.add(new double[]{200, 50});
        
        List<int[]> path = Geometry.genCombinedPath(coords, 5, 10.0, 100, 0.4);
        assertThat(path).isNotEmpty();
        
        System.out.println("Caminho combinado com " + path.size() + " pontos");
    }
    
    @Test
    @DisplayName("Deve calcular posição em tempo específico")
    public void testPosAtTime() {
        List<int[]> path = new ArrayList<>();
        for (int i = 0; i <= 100; i++) {
            path.add(new int[]{i, i});
        }
        
        int[] pos = Geometry.posAtTime(path, 1.0, 0.5, 2.0, 0.5);
        assertThat(pos).hasSize(2);
        assertThat(pos[0]).isBetween(0, 100);
        assertThat(pos[1]).isBetween(0, 100);
        
        System.out.println("Posição em t=0.5: " + 
            String.format("[%d, %d]", pos[0], pos[1]));
    }
    
    @Test
    @DisplayName("Deve calcular interseção de retângulos")
    public void testIntersectRectangles() {
        double[][] rect1 = {
            {0, 0},
            {100, 0},
            {100, 100},
            {0, 100}
        };
        
        double[][] rect2 = {
            {50, 50},
            {150, 50},
            {150, 150},
            {50, 150}
        };
        
        double[][] intersection = Geometry.intersectRectangles(rect1, rect2);
        assertThat(intersection).isNotEmpty();
        
        System.out.println("Interseção encontrada com " + intersection.length + " pontos");
    }
    
    @Test
    @DisplayName("Deve calcular sobreposição de retângulos")
    public void testOverlap() {
        double[][] rect1 = {
            {0, 0},
            {100, 0},
            {100, 100},
            {0, 100}
        };
        
        double[][] rect2 = {
            {50, 50},
            {150, 50},
            {150, 150},
            {50, 150}
        };
        
        Object[] result = Geometry.overlap(rect1, rect2);
        double percentage = (Double) result[0];
        
        assertThat(percentage).isGreaterThan(0);
        assertThat(percentage).isLessThanOrEqualTo(100);
        
        System.out.println("Sobreposição: " + String.format("%.2f%%", percentage));
    }
}

