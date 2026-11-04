package io.github.selenium.javaDriverless.types;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.concurrent.CompletableFuture;

import com.fasterxml.jackson.databind.node.ObjectNode;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Busca nativa de elementos: CSS escopado (querySelectorAll) e XPath (performSearch).
 * Seletores derivam de entrada do usuário — o teste fixa o contrato de normalização/escape.
 */
class WebElementFinderTest {

    private static final com.fasterxml.jackson.databind.ObjectMapper MAPPER =
        new com.fasterxml.jackson.databind.ObjectMapper();

    private Target target;
    private WebElement scope;

    @BeforeEach
    void setUp() {
        target = mock(Target.class);
        scope = new WebElement(target, null, null, "obj-1", 42, null, null, false);
    }

    private static ObjectNode json(String json) {
        try {
            return (ObjectNode) MAPPER.readTree(json);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private void stubCssSearch(String selector, int... nodeIds) {
        StringBuilder ids = new StringBuilder();
        for (int id : nodeIds) {
            ids.append(id).append(",");
        }
        if (ids.length() > 0) {
            ids.setLength(ids.length() - 1);
        }
        when(target.executeCdpCmd(eq("DOM.querySelectorAll"), argThat(args ->
            selector.equals(args.get("selector"))), any()))
            .thenReturn(CompletableFuture.completedFuture(
                json("{\"nodeIds\":[" + ids + "]}")));
    }

    private void stubDescribe(int nodeId, int backendNodeId) {
        when(target.executeCdpCmd(eq("DOM.describeNode"), argThat(args ->
            Integer.valueOf(nodeId).equals(args.get("nodeId"))), any()))
            .thenReturn(CompletableFuture.completedFuture(
                json("{\"node\":{\"backendNodeId\":" + backendNodeId + "}}")));
    }

    @Test
    void findElementsByCssRunsScopedQuerySelectorAll() {
        stubCssSearch("div.card", 101, 102);
        stubDescribe(101, 901);
        stubDescribe(102, 902);

        List<WebElement> found = scope.findElements(By.css("div.card").getStrategy(), "div.card").join();

        assertEquals(2, found.size());
        assertEquals(901, found.get(0).getBackendNodeId().join());
        assertEquals(902, found.get(1).getBackendNodeId().join());
        verify(target).executeCdpCmd(eq("DOM.querySelectorAll"), argThat(args ->
            Integer.valueOf(42).equals(args.get("nodeId"))), any());
    }

    @Test
    void findByIdClassNameAndNameAreNormalizedToEscapedCssSelectors() {
        stubCssSearch("[id=\"a\\\"b\"]", 1);
        stubDescribe(1, 11);
        assertEquals(1, scope.findElements(By.id("a\"b").getStrategy(), "a\"b").join().size());

        stubCssSearch("[class~=\"btn\"]", 2);
        stubDescribe(2, 12);
        assertEquals(1, scope.findElements(By.className("btn").getStrategy(), "btn").join().size());

        stubCssSearch("[name=\"email\"]", 3);
        stubDescribe(3, 13);
        assertEquals(1, scope.findElements(By.name("email").getStrategy(), "email").join().size());

        stubCssSearch("option", 4);
        stubDescribe(4, 14);
        assertEquals(1, scope.findElements(By.tagName("option").getStrategy(), "option").join().size());
    }

    @Test
    void findElementsByXPathUsesPerformSearchAndDiscardsResults() {
        when(target.executeCdpCmd(eq("DOM.performSearch"), argThat(args ->
            "//a".equals(args.get("query"))), any()))
            .thenReturn(CompletableFuture.completedFuture(
                json("{\"searchId\":\"s-1\",\"resultCount\":1}")));
        when(target.executeCdpCmd(eq("DOM.getSearchResults"), anyMap(), any()))
            .thenReturn(CompletableFuture.completedFuture(json("{\"nodeIds\":[201]}")));
        stubDescribe(201, 911);
        when(target.executeCdpCmd(eq("DOM.discardSearchResults"), anyMap(), any()))
            .thenReturn(CompletableFuture.completedFuture(json("{}")));

        List<WebElement> found = scope.findElements(By.XPATH, "//a").join();

        assertEquals(1, found.size());
        assertEquals(911, found.get(0).getBackendNodeId().join());
        verify(target).executeCdpCmd(eq("DOM.discardSearchResults"),
            argThat(args -> "s-1".equals(args.get("searchId"))), any());
    }

    @Test
    void findElementsByXPathWithZeroResultsDiscardsAndReturnsEmpty() {
        when(target.executeCdpCmd(eq("DOM.performSearch"), anyMap(), any()))
            .thenReturn(CompletableFuture.completedFuture(
                json("{\"searchId\":\"s-2\",\"resultCount\":0}")));
        when(target.executeCdpCmd(eq("DOM.discardSearchResults"), anyMap(), any()))
            .thenReturn(CompletableFuture.completedFuture(json("{}")));

        assertEquals(List.of(), scope.findElements(By.XPATH, "//none").join());
    }

    @Test
    void findElementsByXPathWithoutSearchIdReturnsEmptyWithoutSearch() {
        when(target.executeCdpCmd(eq("DOM.performSearch"), anyMap(), any()))
            .thenReturn(CompletableFuture.completedFuture(json("{}")));

        assertEquals(List.of(), scope.findElements(By.XPATH, "//none").join());
        verify(target, org.mockito.Mockito.never())
            .executeCdpCmd(eq("DOM.getSearchResults"), anyMap(), any());
    }

    @Test
    void findElementsRejectsUnknownStrategy() {
        CompletableFuture<List<WebElement>> future =
            scope.findElements("link text", "whatever");

        Exception thrown = assertThrows(Exception.class, () -> future.join());
        assertTrue(thrown.getCause() instanceof IllegalArgumentException);
    }

    @Test
    void findElementRetriesUntilTimeoutThenThrowsNoSuchElement() {
        when(target.executeCdpCmd(eq("DOM.querySelectorAll"), anyMap(), any()))
            .thenReturn(CompletableFuture.completedFuture(json("{\"nodeIds\":[]}")));

        long start = System.nanoTime();
        Exception thrown = assertThrows(Exception.class,
            () -> scope.findElement(By.CSS_SELECTOR, ".missing", 0.05f).join());
        long elapsedMs = (System.nanoTime() - start) / 1_000_000;

        assertTrue(thrown.getCause() instanceof WebElement.NoSuchElementException);
        assertTrue(thrown.getCause().getMessage().contains(".missing"));
        // timeout de 50ms é respeitado com folga (sem esperar o default longo)
        assertTrue(elapsedMs < 5_000, "elapsed=" + elapsedMs);
    }

    @Test
    void findElementReturnsFirstMatch() {
        stubCssSearch("[id=\"login\"]", 301);
        stubDescribe(301, 931);

        WebElement found = scope.findElement(By.ID, "login", 1f).join();

        assertEquals(931, found.getBackendNodeId().join());
    }
}
