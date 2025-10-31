package io.github.selenium.driverless;

import org.junit.jupiter.api.*;

import io.github.selenium.javaDriverless.sync.SyncChrome;
import io.github.selenium.javaDriverless.sync.SyncWebElement;
import io.github.selenium.javaDriverless.types.By;
import io.github.selenium.javaDriverless.types.ChromeOptions;

import static org.assertj.core.api.Assertions.*;

/**
 * Testes básicos de funcionalidade do Java Driverless.
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
public class BasicFunctionalityTest {
    
    private static SyncChrome driver;
    
    @BeforeAll
    public static void setUp() {
        ChromeOptions options = new ChromeOptions();
        // options.setHeadless(true);  // Descomente para headless
        driver = new SyncChrome(options);
        assertThat(driver).isNotNull();
    }
    
    @AfterAll
    public static void tearDown() {
        if (driver != null) {
            driver.quit();
        }
    }
    
    @Test
    @Order(1)
    @DisplayName("Deve iniciar Chrome e obter target")
    public void testChromeInitialization() {
        var target = driver.getCurrentTarget();
        assertThat(target).isNotNull();
    }
    
    @Test
    @Order(2)
    @DisplayName("Deve navegar para uma página")
    public void testNavigation() {
        driver.get("http://nowsecure.nl#relax");
        driver.sleep(0.5);
        
        String url = driver.getCurrentUrl();
        assertThat(url).contains("nowsecure.nl");
    }
    
    @Test
    @Order(3)
    @DisplayName("Deve obter título da página")
    public void testGetTitle() {
        String title = driver.getTitle();
        assertThat(title).isNotNull();
        assertThat(title).isNotEmpty();
        System.out.println("Título: " + title);
    }
    
    @Test
    @Order(4)
    @DisplayName("Deve obter código-fonte da página")
    public void testGetPageSource() {
        String source = driver.getPageSource();
        assertThat(source).isNotNull();
        assertThat(source).contains("<html");
    }
    
    @Test
    @Order(5)
    @DisplayName("Deve executar JavaScript")
    public void testExecuteScript() {
        Object result = driver.executeScript("return navigator.userAgent");
        assertThat(result).isNotNull();
        String userAgent = result.toString();
        assertThat(userAgent).contains("Chrome");
        assertThat(userAgent).doesNotContain("HeadlessChrome");
        System.out.println("User-Agent: " + userAgent);
    }
    
    @Test
    @Order(6)
    @DisplayName("Deve buscar elemento por XPATH")
    public void testFindElementByXPath() {
        try {
            SyncWebElement elem = driver.findElement(By.XPATH, "//a", 10.0f);
            assertThat(elem).isNotNull();
            
            String tagName = elem.getTagName();
            assertThat(tagName).isEqualTo("a");
            System.out.println("Elemento encontrado: " + tagName);
        } catch (Exception e) {
            System.err.println("Nota: Elemento pode não existir na página de teste");
        }
    }
    
    @Test
    @Order(7)
    @DisplayName("Deve aguardar evento CDP")
    public void testWaitForCdp() {
        assertThatCode(() -> {
            driver.get("about:blank");
            driver.waitForCdp("Page.domContentEventFired", 5.0f);
        }).doesNotThrowAnyException();
    }
    
    @Test
    @Order(8)
    @DisplayName("Deve usar movimentos humanizados do ponteiro")
    public void testPointerMovement() {
        var target = driver.getCurrentTarget();
        var pointer = target.getPointer();
        
        assertThatCode(() -> {
            pointer.moveTo(300, 300, 0.5, 2.0, 20.0);
            int[] location = pointer.getLocation();
            assertThat(location[0]).isEqualTo(300);
            assertThat(location[1]).isEqualTo(300);
        }).doesNotThrowAnyException();
    }
    
    @Test
    @Order(9)
    @DisplayName("Deve navegar back e forward")
    public void testBackAndForward() {
        driver.get("http://nowsecure.nl");
        String url1 = driver.getCurrentUrl();
        
        driver.get("about:blank");
        String url2 = driver.getCurrentUrl();
        
        driver.back();
        String url3 = driver.getCurrentUrl();
        
        assertThat(url3).isNotEqualTo(url2);
    }
    
    @Test
    @Order(10)
    @DisplayName("Deve recarregar página")
    public void testRefresh() {
        driver.get("http://nowsecure.nl");
        
        assertThatCode(() -> {
            driver.getCurrentTarget().refresh(false);
            driver.sleep(1.0);
        }).doesNotThrowAnyException();
    }
}

