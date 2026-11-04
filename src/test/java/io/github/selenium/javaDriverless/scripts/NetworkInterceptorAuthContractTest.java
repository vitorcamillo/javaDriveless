package io.github.selenium.javaDriverless.scripts;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Map;

import com.fasterxml.jackson.databind.JsonNode;

import org.junit.jupiter.api.Test;

/**
 * Contrato do desafio de autenticação de proxy do NetworkInterceptor e da exceção de
 * auth já tratada (mensagem SEM a URL — redação no próprio texto).
 */
class NetworkInterceptorAuthContractTest {

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
    void authChallengeExposesCdpAuthFields() {
        JsonNode params = json("{\"source\":\"Proxy\",\"origin\":\"http://proxy.test\","
            + "\"scheme\":\"basic\",\"realm\":\"Corporativo\"}");
        Object owner = new Object();

        NetworkInterceptor.AuthChallenge challenge = new NetworkInterceptor.AuthChallenge(params, owner);

        assertEquals("Proxy", challenge.getSource());
        assertEquals("http://proxy.test", challenge.getOrigin());
        assertEquals("basic", challenge.getScheme());
        assertEquals("Corporativo", challenge.getRealm());
    }

    @Test
    void authChallengeSourceIsOptional() {
        JsonNode params = json("{\"origin\":\"http://x.test\",\"scheme\":\"negotiate\",\"realm\":\"r\"}");

        NetworkInterceptor.AuthChallenge challenge =
            new NetworkInterceptor.AuthChallenge(params, new Object());

        assertNull(challenge.getSource());
        assertEquals("negotiate", challenge.getScheme());
    }

    @Test
    void authAlreadyHandledMessageRedactsTheUrl() {
        Object requestData = Map.of("requestId", "R-1");

        NetworkInterceptor.AuthAlreadyHandledException ex =
            new NetworkInterceptor.AuthAlreadyHandledException(requestData, "https://secret.test/path");

        assertSame(requestData, ex.getRequest());
        assertTrue(ex.getMessage().contains("<redacted>"), ex.getMessage());
        assertTrue(!ex.getMessage().contains("secret.test"), "URL não pode vazar na mensagem");
    }
}
