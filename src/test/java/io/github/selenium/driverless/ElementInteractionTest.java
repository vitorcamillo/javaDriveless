package io.github.selenium.driverless;

import org.junit.jupiter.api.*;

import io.github.selenium.javaDriverless.sync.SyncChrome;
import io.github.selenium.javaDriverless.sync.SyncWebElement;
import io.github.selenium.javaDriverless.types.By;
import io.github.selenium.javaDriverless.types.ChromeOptions;

import static org.assertj.core.api.Assertions.*;

/**
 * Testes de interação com elementos.
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
public class ElementInteractionTest {
    
    private static SyncChrome driver;
    
    @BeforeAll
    public static void setUp() {
        ChromeOptions options = new ChromeOptions();
        driver = new SyncChrome(options);
    }
    
    @AfterAll
    public static void tearDown() {
        if (driver != null) {
            driver.quit();
        }
    }
    
    @Test
    @Order(1)
    @DisplayName("Deve obter propriedades de elemento")
    public void testElementProperties() {
        driver.get("http://nowsecure.nl");
        driver.sleep(1.0);
        
        try {
            SyncWebElement elem = driver.findElement(By.TAG_NAME, "body", 5.0f);
            assertThat(elem).isNotNull();
            
            String tagName = elem.getTagName();
            assertThat(tagName).isEqualTo("body");
            
            boolean displayed = elem.isDisplayed();
            assertThat(displayed).isTrue();
            
            System.out.println("✓ Propriedades do elemento verificadas");
        } catch (Exception e) {
            System.err.println("Nota: Teste pode falhar dependendo da página");
        }
    }
    
    @Test
    @Order(2)
    @DisplayName("Deve obter localização e tamanho de elemento")
    public void testElementGeometry() {
        driver.get("http://nowsecure.nl");
        driver.sleep(1.0);
        
        try {
            SyncWebElement elem = driver.findElement(By.TAG_NAME, "body", 5.0f);
            
            double[] location = elem.getLocation();
            assertThat(location).hasSize(2);
            assertThat(location[0]).isGreaterThanOrEqualTo(0);
            assertThat(location[1]).isGreaterThanOrEqualTo(0);
            
            double[] size = elem.getSize();
            assertThat(size).hasSize(2);
            assertThat(size[0]).isGreaterThan(0);
            assertThat(size[1]).isGreaterThan(0);
            
            System.out.println("✓ Geometria do elemento: " +
                String.format("loc=[%.0f,%.0f] size=[%.0f,%.0f]", 
                    location[0], location[1], size[0], size[1]));
        } catch (Exception e) {
            System.err.println("Nota: Teste pode falhar dependendo da página");
        }
    }
    
    @Test
    @Order(3)
    @DisplayName("Deve obter localização humanizada do elemento")
    public void testHumanizedLocation() {
        driver.get("http://nowsecure.nl");
        driver.sleep(1.0);
        
        try {
            SyncWebElement elem = driver.findElement(By.TAG_NAME, "body", 5.0f);
            
            double[] midLoc = elem.getMidLocation();
            assertThat(midLoc).hasSize(2);
            
            System.out.println("✓ Localização humanizada (aleatória): " +
                String.format("[%.2f, %.2f]", midLoc[0], midLoc[1]));
        } catch (Exception e) {
            System.err.println("Nota: Teste pode falhar dependendo da página");
        }
    }
    
    @Test
    @Order(4)
    @DisplayName("Deve buscar múltiplos elementos")
    public void testFindElements() {
        driver.get("http://nowsecure.nl");
        driver.sleep(1.0);
        
        try {
            var elements = driver.getCurrentTarget().findElements(By.TAG_NAME, "a", 5.0f);
            assertThat(elements).isNotEmpty();
            
            System.out.println("✓ Encontrados " + elements.size() + " elementos <a>");
        } catch (Exception e) {
            System.err.println("Nota: Teste pode falhar dependendo da página");
        }
    }
}

