package io.github.selenium.driverless;

import org.junit.jupiter.api.*;

import io.github.selenium.javaDriverless.sync.SyncChrome;
import io.github.selenium.javaDriverless.sync.SyncWebElement;
import io.github.selenium.javaDriverless.types.By;
import io.github.selenium.javaDriverless.types.ChromeOptions;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.*;

/**
 * Testes básicos de funcionalidade do Java Driverless.
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@Tag("browser")
public class BasicFunctionalityTest {

    private static final String HISTORY_PAGE_ONE =
        "data:text/html,<html><head><title>history-one</title></head><body>one</body></html>";
    private static final String HISTORY_PAGE_TWO =
        "data:text/html,<html><head><title>history-two</title></head><body>two</body></html>";
    private static final String LOCAL_PAGE =
        "data:text/html,<html><head><title>local-fixture</title></head>"
            + "<body><a href='about:blank'>fixture-link</a></body></html>";
    
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
        driver.get(LOCAL_PAGE);

        String url = driver.getCurrentUrl();
        assertThat(url).startsWith("data:text/html");
    }
    
    @Test
    @Order(3)
    @DisplayName("Deve obter título da página")
    public void testGetTitle() {
        driver.get(LOCAL_PAGE);

        String title = driver.getTitle();
        assertThat(title).isEqualTo("local-fixture");
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
        // Não usar 'return' - executeScript aceita expressões JavaScript diretamente
        Object result = driver.executeScript("navigator.userAgent");
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
        driver.get(LOCAL_PAGE);
        SyncWebElement elem = driver.findElement(By.XPATH, "//a", 10.0f);
        assertThat(elem).isNotNull();

        String tagName = elem.getTagName();
        assertThat(tagName).isEqualTo("a");
        System.out.println("Elemento encontrado: " + tagName);
    }
    
    @Test
    @Order(7)
    @DisplayName("Deve aguardar evento CDP")
    public void testWaitForCdp() {
        assertThatCode(() -> {
            CompletableFuture<?> loadEvent = CompletableFuture.supplyAsync(
                () -> driver.waitForCdp("Page.loadEventFired", 10.0f));
            driver.get("data:text/html,<html><body>cdp-event</body></html>");
            assertThat(loadEvent.get(10, TimeUnit.SECONDS)).isNotNull();
        }).doesNotThrowAnyException();
    }
    
    @Test
    @Order(8)
    @DisplayName("Deve usar movimentos humanizados do ponteiro")
    public void testPointerMovement() {
        var target = driver.getCurrentTarget();
        var pointer = target.getPointer();
        
        assertThatCode(() -> {
            // Aguardar o movimento completar antes de verificar a localização
            pointer.moveTo(300, 300, 0.5, 2.0, 20.0).join();
            int[] location = pointer.getLocation();
            assertThat(location[0]).isEqualTo(300);
            assertThat(location[1]).isEqualTo(300);
        }).doesNotThrowAnyException();
    }
    
    @Test
    @Order(10)
    @DisplayName("Deve navegar back e forward")
    public void testBackAndForward() {
        driver.get(HISTORY_PAGE_ONE);
        String url1 = driver.getCurrentUrl();

        driver.get(HISTORY_PAGE_TWO);
        String url2 = driver.getCurrentUrl();

        driver.back();
        String url3 = driver.getCurrentUrl();

        assertThat(url3).isEqualTo(url1).isNotEqualTo(url2);

        driver.forward();
        assertThat(driver.getCurrentUrl()).isEqualTo(url2);
    }
    
    @Test
    @Order(9)
    @DisplayName("Deve recarregar página")
    public void testRefresh() {
        driver.get(LOCAL_PAGE);

        assertThatCode(driver::refresh).doesNotThrowAnyException();
        assertThat(driver.getTitle()).isEqualTo("local-fixture");
    }
}

