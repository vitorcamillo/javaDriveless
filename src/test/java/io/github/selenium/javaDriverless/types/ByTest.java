package io.github.selenium.javaDriverless.types;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/** Contrato das estratégias de localização (seletor gerado a partir de entrada do usuário). */
class ByTest {

    @Test
    void factoryMethodsMapToCanonicalStrategies() {
        assertEquals("id", By.id("x").getStrategy());
        assertEquals("name", By.name("x").getStrategy());
        assertEquals("xpath", By.xpath("//div").getStrategy());
        assertEquals("tag name", By.tagName("div").getStrategy());
        assertEquals("class name", By.className("btn").getStrategy());
        assertEquals("css selector", By.cssSelector("div.a").getStrategy());
        assertEquals("css selector", By.css("div.a").getStrategy());
    }

    @Test
    void valueIsPreservedVerbatim() {
        assertEquals("input[name='q']", By.css("input[name='q']").getValue());
        assertEquals("//input[@name='q']", By.xpath("//input[@name='q']").getValue());
    }

    @Test
    void linkTextEscapesApostropheWithDoubleQuotedLiteral() {
        // XPath 1.0 não tem escape \': texto com apóstrofo delimita com aspas duplas
        By by = By.linkText("don't");
        assertEquals("xpath", by.getStrategy());
        assertEquals("//a[text()=\"don't\"]", by.getValue());
    }

    @Test
    void linkTextKeepsSingleQuotesForPlainAndDoubleQuoteTexts() {
        assertEquals("//a[text()='click here']", By.linkText("click here").getValue());
        // aspas duplas dentro de literal single-quoted são válidas em XPath
        assertEquals("//a[text()='say \"hi\"']", By.linkText("say \"hi\"").getValue());
    }

    @Test
    void partialLinkTextUsesSafeLiteralForQuotes() {
        By apostrophe = By.partialLinkText("it's");
        assertEquals("xpath", apostrophe.getStrategy());
        assertEquals("//a[contains(text(),\"it's\")]", apostrophe.getValue());

        By doubleQuotes = By.partialLinkText("a\"b");
        assertEquals("//a[contains(text(),'a\"b')]", doubleQuotes.getValue());
    }

    @Test
    void textWithBothQuoteTypesFallsBackToXPathConcat() {
        By by = By.partialLinkText("a'\"b");
        String xpath = by.getValue();
        assertTrue(xpath.startsWith("//a[contains(text(),concat("), xpath);
        assertTrue(xpath.contains("\"'\""), "apóstrofo isolado como parte: " + xpath);
    }

    @Test
    void deprecatedConstantsMatchStrategyNames() {
        assertEquals(By.id("").getStrategy(), By.ID);
        assertEquals(By.name("").getStrategy(), By.NAME);
        assertEquals(By.xpath("").getStrategy(), By.XPATH);
        assertEquals(By.tagName("").getStrategy(), By.TAG_NAME);
        assertEquals(By.className("").getStrategy(), By.CLASS_NAME);
        assertEquals(By.cssSelector("").getStrategy(), By.CSS_SELECTOR);
        assertEquals(By.cssSelector("").getStrategy(), By.CSS);
    }

    @Test
    void hashCodeFollowsTheDocumentedFormula() {
        // 31 * strategy.hashCode() + value.hashCode(): mata mutações aritméticas
        int expected = 31 * "css selector".hashCode() + "div".hashCode();
        assertEquals(expected, By.css("div").hashCode());
        assertTrue(By.css("div").hashCode() != 0);
    }

    @Test
    void xpathLiteralConcatSkipsEmptyPartsAndKeepsQuoteSeparator() {
        // apóstrofos nas pontas geram partes vazias: nada de literal vazio no concat
        // texto com AMBOS os tipos de aspa e apóstrofos nas pontas: partes vazias são omitidas
        String xpath = By.partialLinkText("'a\"b'").getValue();
        String separator = "\"" + "'" + "\"";
        String expected = "//a[contains(text(),concat(" + separator + ", 'a\"b', " + separator + "))]";
        assertEquals(expected, xpath);

        // textos com apenas um tipo de aspa usam o delimitador oposto (não o concat)
        assertEquals("//a[text()=\"a'\"]", By.linkText("a'").getValue());
    }

    @Test
    void equalsAndHashCodeUseStrategyAndValue() {
        By a = By.css("div");
        By b = By.cssSelector("div");
        By c = By.css("span");

        assertEquals(a, b);
        assertEquals(a.hashCode(), b.hashCode());
        assertNotEquals(a, c);
        assertNotEquals(a, null);
        assertNotEquals(a, "div");
    }

    @Test
    void toStringDescribesStrategyAndValue() {
        assertEquals("By.css selector: div", By.css("div").toString());
        assertTrue(By.id("k").toString().contains("id"));
    }
}
