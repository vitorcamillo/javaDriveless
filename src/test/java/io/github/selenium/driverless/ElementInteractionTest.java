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
@Tag("browser")
public class ElementInteractionTest {

    private static final String ELEMENT_FIXTURE = "data:text/html," +
        "<html><head><title>element-fixture</title></head>" +
        "<body style='margin:0;min-width:320px;min-height:240px'>" +
        "<a href='about:blank'>link</a><div>block</div><span>inline</span>" +
        "</body></html>";
    
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
        driver.get(ELEMENT_FIXTURE);

        SyncWebElement elem = driver.findElement(By.TAG_NAME, "body", 5.0f);
        assertThat(elem).isNotNull();

        String tagName = elem.getTagName();
        assertThat(tagName).isEqualTo("body");

        boolean displayed = elem.isDisplayed();
        assertThat(displayed).isTrue();

        System.out.println("✓ Propriedades do elemento verificadas");
    }
    
    @Test
    @Order(2)
    @DisplayName("Deve obter localização e tamanho de elemento")
    public void testElementGeometry() {
        driver.get(ELEMENT_FIXTURE);

        SyncWebElement elem = driver.findElement(By.TAG_NAME, "body", 5.0f);

        var rawBoxModel = driver.getCurrentTarget().executeCdpCmd(
            "DOM.getBoxModel", java.util.Map.of("nodeId", elem.getNodeId()), 5.0f);
        assertThat(rawBoxModel.path("model").path("border")).hasSize(8);

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
    }
    
    @Test
    @Order(3)
    @DisplayName("Deve obter localização humanizada do elemento")
    public void testHumanizedLocation() {
        driver.get(ELEMENT_FIXTURE);
        SyncWebElement elem = driver.findElement(By.TAG_NAME, "body", 5.0f);

        double[] midLoc = elem.getMidLocation();
        assertThat(midLoc).hasSize(2);
        assertThat(midLoc[0]).isGreaterThanOrEqualTo(0);
        assertThat(midLoc[1]).isGreaterThanOrEqualTo(0);

        System.out.println("✓ Localização humanizada (aleatória): " +
            String.format("[%.2f, %.2f]", midLoc[0], midLoc[1]));
    }
    
    @Test
    @Order(4)
    @DisplayName("Deve buscar múltiplos elementos")
    public void testFindElements() {
        driver.get(ELEMENT_FIXTURE);
        
        // Tentar buscar por diferentes tipos de elementos para aumentar chances de sucesso
        var elements = driver.getCurrentTarget().findElements(By.TAG_NAME, "a", 5.0f);
        var divs = driver.getCurrentTarget().findElements(By.TAG_NAME, "div", 5.0f);
        var spans = driver.getCurrentTarget().findElements(By.TAG_NAME, "span", 5.0f);
        
        assertThat(elements).hasSize(1);
        assertThat(divs).hasSize(1);
        assertThat(spans).hasSize(1);
    }
}

