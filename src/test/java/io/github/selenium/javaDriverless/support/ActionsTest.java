package io.github.selenium.javaDriverless.support;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.concurrent.CompletableFuture;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import io.github.selenium.javaDriverless.Chrome;
import io.github.selenium.javaDriverless.input.Keyboard;
import io.github.selenium.javaDriverless.input.Pointer;
import io.github.selenium.javaDriverless.types.Target;
import io.github.selenium.javaDriverless.types.WebElement;

/** Ordem de encadeamento e propagação de falha da API Actions (mouse+teclado). */
class ActionsTest {

    private Chrome driver;
    private Pointer pointer;
    private Keyboard keyboard;
    private Actions actions;

    @BeforeEach
    void setUp() {
        driver = mock(Chrome.class);
        pointer = mock(Pointer.class);
        keyboard = mock(Keyboard.class);
        when(driver.getCurrentPointer()).thenReturn(pointer);
        when(driver.getCurrentKeyboard()).thenReturn(keyboard);
        actions = new Actions(driver);
    }

    private void stubPointer() {
        when(pointer.moveTo(anyInt(), anyInt(), anyDouble(), anyInt(), anyInt()))
            .thenReturn(CompletableFuture.completedFuture(null));
        when(pointer.click()).thenReturn(CompletableFuture.completedFuture(null));
        when(pointer.doubleClick()).thenReturn(CompletableFuture.completedFuture(null));
        when(pointer.contextClick()).thenReturn(CompletableFuture.completedFuture(null));
        when(pointer.down()).thenReturn(CompletableFuture.completedFuture(null));
        when(pointer.up()).thenReturn(CompletableFuture.completedFuture(null));
        when(pointer.dragAndDrop(anyInt(), anyInt(), anyInt(), anyInt(), anyDouble()))
            .thenReturn(CompletableFuture.completedFuture(null));
    }

    private void stubKeyboard() {
        when(keyboard.keyDown(anyString())).thenReturn(CompletableFuture.completedFuture(null));
        when(keyboard.keyUp(anyString())).thenReturn(CompletableFuture.completedFuture(null));
        when(keyboard.sendKeys(any(String[].class))).thenReturn(CompletableFuture.completedFuture(null));
        when(keyboard.type(anyString())).thenReturn(CompletableFuture.completedFuture(null));
    }

    @Test
    void moveToLocationDelegatesToPointer() {
        stubPointer();
        actions.moveToLocation(10, 20).perform();
        verify(pointer).moveTo(10, 20, 1.0, 2, 20);
    }

    @Test
    void clickDoubleClickContextClickHoldAndReleaseRunInOrder() {
        stubPointer();
        actions.click().doubleClick().contextClick().clickAndHold().release().perform();

        verify(pointer).click();
        verify(pointer).doubleClick();
        verify(pointer).contextClick();
        verify(pointer).down();
        verify(pointer).up();
    }

    @Test
    void keyboardActionsDelegateToKeyboard() {
        stubKeyboard();
        actions.keyDown("Shift").sendKeys("a", "b").keyUp("Shift").type("txt").perform();

        verify(keyboard).keyDown("Shift");
        verify(keyboard).sendKeys("a", "b");
        verify(keyboard).keyUp("Shift");
        verify(keyboard).type("txt");
    }

    @Test
    void moveToElementUsesMidLocationCoordinates() {
        stubPointer();
        WebElement element = mock(WebElement.class);
        when(element.getMidLocation())
            .thenReturn(CompletableFuture.completedFuture(new double[]{100.0, 200.0}));

        actions.moveToElement(element, 5, 10).perform();

        verify(pointer).moveTo(105, 210, 1.0, 2, 20);
    }

    @Test
    void clickOnElementMovesThenClicks() {
        stubPointer();
        WebElement element = mock(WebElement.class);
        when(element.getMidLocation())
            .thenReturn(CompletableFuture.completedFuture(new double[]{1.0, 2.0}));

        actions.click(element).perform();

        verify(pointer).moveTo(1, 2, 1.0, 2, 20);
        verify(pointer).click();
    }

    @Test
    void dragAndDropWithElementsUsesBothMidLocations() {
        stubPointer();
        WebElement source = mock(WebElement.class);
        WebElement target = mock(WebElement.class);
        when(source.getMidLocation())
            .thenReturn(CompletableFuture.completedFuture(new double[]{10.0, 20.0}));
        when(target.getMidLocation())
            .thenReturn(CompletableFuture.completedFuture(new double[]{30.0, 40.0}));

        actions.dragAndDrop(source, target).perform();

        verify(pointer).dragAndDrop(10, 20, 30, 40, 2.0);
    }

    @Test
    void dragAndDropWithCoordinatesDelegatesDirectly() {
        stubPointer();
        actions.dragAndDrop(1, 2, 3, 4).perform();
        verify(pointer).dragAndDrop(1, 2, 3, 4, 2.0);
    }

    @Test
    void performSurfacesChainFailureAsRuntimeException() {
        when(pointer.click())
            .thenReturn(CompletableFuture.failedFuture(new IllegalStateException("no pointer")));

        actions.click();

        RuntimeException thrown = assertThrows(RuntimeException.class, actions::perform);
        assertEquals("no pointer", thrown.getCause().getCause().getMessage());
    }

    @Test
    void performAsyncExposesTheChainWithoutThrowing() {
        when(pointer.click())
            .thenReturn(CompletableFuture.failedFuture(new IllegalStateException("no pointer")));

        actions.click();

        assertDoesNotThrow(() -> actions.performAsync().isDone());
    }

    @Test
    void resetClearsPendingChain() throws Exception {
        stubPointer();
        actions.moveToLocation(1, 1).reset().perform();

        verify(pointer, never()).moveTo(anyInt(), anyInt(), anyDouble(), anyInt(), anyInt());
    }

    @Test
    void pauseRunsBetweenActionsWithoutBreakingChain() {
        stubPointer();
        stubKeyboard();
        actions.click().pause(5).type("x").perform();

        verify(pointer).click();
        verify(keyboard).type("x");
    }

    @Test
    void sendKeysToElementClicksThenTypes() {
        stubPointer();
        stubKeyboard();
        WebElement element = mock(WebElement.class);
        when(element.getMidLocation())
            .thenReturn(CompletableFuture.completedFuture(new double[]{0.0, 0.0}));

        actions.sendKeys(element, "hello").perform();

        ArgumentCaptor<String> typed = ArgumentCaptor.forClass(String.class);
        verify(keyboard).type(typed.capture());
        assertEquals("hello", typed.getValue());
    }

    @Test
    void javaDriverlessConstructorRoutesThroughItsChrome() {
        io.github.selenium.javaDriverless.JavaDriverless facade =
            mock(io.github.selenium.javaDriverless.JavaDriverless.class);
        when(facade.getChrome()).thenReturn(driver);
        stubPointer();

        new Actions(facade).moveToLocation(3, 4).perform();

        verify(pointer).moveTo(3, 4, 1.0, 2, 20);
    }

    @Test
    void doubleClickAndContextClickOnElementMoveBeforeClicking() {
        stubPointer();
        WebElement element = mock(WebElement.class);
        when(element.getMidLocation())
            .thenReturn(CompletableFuture.completedFuture(new double[]{7.0, 9.0}));

        actions.doubleClick(element).perform();
        actions.reset();
        actions.contextClick(element).perform();

        verify(pointer, times(2)).moveTo(7, 9, 1.0, 2, 20);
        verify(pointer).doubleClick();
        verify(pointer).contextClick();
    }

    @Test
    void moveToElementWithoutOffsetSurfacesMidLocationFailure() {
        WebElement element = mock(WebElement.class);
        when(element.getMidLocation()).thenThrow(new IllegalStateException("mid indisponível"));

        actions.moveToElement(element);

        RuntimeException thrown = assertThrows(RuntimeException.class, actions::perform);
        assertEquals("mid indisponível", thrown.getCause().getCause().getMessage());
    }

    @Test
    void moveToElementWithOffsetSurfacesMidLocationFailure() {
        WebElement element = mock(WebElement.class);
        when(element.getMidLocation()).thenThrow(new IllegalStateException("mid indisponível"));

        actions.moveToElement(element, 1, 1);

        RuntimeException thrown = assertThrows(RuntimeException.class, actions::perform);
        assertEquals("mid indisponível", thrown.getCause().getCause().getMessage());
    }

    @Test
    void dragAndDropBetweenElementsSurfacesMidLocationFailure() {
        WebElement source = mock(WebElement.class);
        when(source.getMidLocation()).thenThrow(new IllegalStateException("origem sumiu"));

        actions.dragAndDrop(source, mock(WebElement.class));

        RuntimeException thrown = assertThrows(RuntimeException.class, actions::perform);
        assertEquals("origem sumiu", thrown.getCause().getCause().getMessage());
    }
}
