package io.github.selenium.javaDriverless.types;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;

import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

/** Contrato das exceções de interação com elementos + POJO de superfície de voz. */
class TypesExceptionsContractTest {

    private static final com.fasterxml.jackson.databind.ObjectMapper MAPPER =
        new com.fasterxml.jackson.databind.ObjectMapper();

    private static JsonNode json(String json) {
        try {
            return MAPPER.readTree(json);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    @Test
    void noSuchIframeCarriesTheElementAndMessage() {
        WebElement iframe = Mockito.mock(WebElement.class);

        TypesExceptions.NoSuchIframe ex =
            new TypesExceptions.NoSuchIframe(iframe, "iframe não encontrado no target");

        assertSame(iframe, ex.getIframe());
        assertEquals("iframe não encontrado no target", ex.getMessage());
    }

    @Test
    void jsEvalExceptionExposesCdpDetailGetters() {
        JsonNode details = json("{\"exceptionId\":5,\"text\":\"x\",\"lineNumber\":2,"
            + "\"columnNumber\":4,\"exception\":{\"type\":\"object\",\"subtype\":\"error\","
            + "\"className\":\"RangeError\",\"description\":\"RangeError: x\","
            + "\"objectId\":\"o-1\"}}");

        TypesExceptions.JSEvalException ex = new TypesExceptions.JSEvalException(details);

        assertEquals(5, ex.getExcId());
        assertEquals("x", ex.getText());
        assertEquals(2, ex.getLineNumber());
        assertEquals(4, ex.getColumnNumber());
        assertEquals("object", ex.getType());
        assertEquals("error", ex.getSubtype());
        assertEquals("RangeError", ex.getClassName());
        assertEquals("RangeError: x", ex.getDescription());
        assertEquals("o-1", ex.getObjId());
        assertTrue(ex.getMessage().contains("RangeError"));
    }

    @Test
    void elementNotInteractableExposesClickPoint() {
        WebElement.ElementNotInteractableException ex =
            new WebElement.ElementNotClickableException(12.5, 7.25);

        assertEquals(12.5, ex.getX(), 1e-9);
        assertEquals(7.25, ex.getY(), 1e-9);
        assertTrue(ex.getMessage().contains("clickable"));
        assertTrue(ex.getMessage().contains("12,50") || ex.getMessage().contains("12.50"),
            ex.getMessage());
    }

    @Test
    void elementNotVisibleMessageIsPreserved() {
        WebElement.ElementNotVisibleException ex =
            new WebElement.ElementNotVisibleException("escondido por overlay");

        assertEquals("escondido por overlay", ex.getMessage());
    }

    @Test
    void browserFingerprintVoicePojoDefaultsRoundTrip() {
        BrowserFingerprintVoice voice = new BrowserFingerprintVoice();

        assertEquals("", voice.name);
        assertEquals("", voice.lang);
        assertEquals("", voice.voiceURI);
        assertEquals(true, voice.localService);
        assertEquals(false, voice.isDefault);

        voice.name = "Google português do Brasil";
        voice.lang = "pt-BR";
        voice.voiceURI = "urn:voice:1";
        voice.localService = false;
        voice.isDefault = true;

        assertEquals("pt-BR", voice.lang);
        assertFalse(voice.localService);
        assertTrue(voice.isDefault);
    }
}
