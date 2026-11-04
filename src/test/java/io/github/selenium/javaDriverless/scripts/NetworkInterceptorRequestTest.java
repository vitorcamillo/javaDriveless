package io.github.selenium.javaDriverless.scripts;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Map;

import com.fasterxml.jackson.databind.JsonNode;

import org.junit.jupiter.api.Test;

/** Filtros de interceptação (RequestPattern) e envelope de requisição (Request). */
class NetworkInterceptorRequestTest {

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
    void anyRequestAndAnyResponsePatternsCoverBothStages() {
        assertEquals("Request", NetworkInterceptor.RequestPattern.ANY_REQUEST.get("requestStage"));
        assertEquals("Response", NetworkInterceptor.RequestPattern.ANY_RESPONSE.get("requestStage"));
        assertTrue(NetworkInterceptor.RequestPattern.ANY_REQUEST.containsKey("urlPattern"));
    }

    @Test
    void createBuildsPatternWithStageAndResourceType() {
        Map<String, Object> pattern = NetworkInterceptor.RequestPattern.create(
            "*://ads.example/*", "Image", "Response");

        assertEquals("*://ads.example/*", pattern.get("urlPattern"));
        assertEquals("Image", pattern.get("resourceType"));
        assertEquals("Response", pattern.get("requestStage"));
    }

    @Test
    void createWithoutResourceTypeOmitsTheField() {
        Map<String, Object> pattern =
            NetworkInterceptor.RequestPattern.create("https://x.test/", null, null);

        assertEquals("https://x.test/", pattern.get("urlPattern"));
        assertTrue(!pattern.containsKey("resourceType"));
    }

    @Test
    void requestExposesMethodUrlHeadersAndPostData() {
        JsonNode params = json("{"
            + "\"url\":\"https://api.test/login\",\"method\":\"POST\","
            + "\"headers\":{\"Content-Type\":\"application/json\",\"X-Trace\":\"t-1\"},"
            + "\"postData\":\"{\\\"u\\\":\\\"x\\\"}\"}");
        Object owner = new Object();

        NetworkInterceptor.Request request = new NetworkInterceptor.Request(params, owner);

        assertSame(owner, request.getTarget());
        assertSame(params, request.getParams());
        assertEquals("https://api.test/login", request.getUrl());
        assertEquals("POST", request.getMethod());
        assertEquals("t-1", request.getHeaders().get("X-Trace"));
        assertEquals("application/json", request.getHeaders().get("Content-Type"));
        assertEquals("{\"u\":\"x\"}", request.getPostData());
    }

    @Test
    void missingOptionalFieldsDegradeGracefully() {
        JsonNode params = json("{\"url\":\"https://x.test/\",\"method\":\"GET\"}");

        NetworkInterceptor.Request request = new NetworkInterceptor.Request(params, null);

        assertNull(request.getPostData());
        assertTrue(request.getHeaders().isEmpty());
    }

    @Test
    void toStringContainsMethodAndUrlForDiagnostics() {
        JsonNode params = json("{\"url\":\"https://x.test/a\",\"method\":\"GET\"}");

        String repr = new NetworkInterceptor.Request(params, null).toString();

        // toString redige a URL (não vaza em logs) mas mantém o método para diagnóstico
        assertTrue(repr.contains("GET"), repr);
        assertTrue(repr.contains("<redacted>"), repr);
        assertTrue(!repr.contains("https://x.test/a"), "URL não pode vazar no toString");
    }

    @Test
    void requestDoneExceptionCarriesPayloadWithRedactedUrl() {
        Object data = Map.of("requestId", "R-9");

        NetworkInterceptor.RequestDoneException ex =
            new NetworkInterceptor.RequestDoneException(data, "https://segredo.test/x");

        assertSame(data, ex.getRequest());
        assertTrue(ex.getMessage().contains("<redacted>") || ex.getMessage().contains("redacted"),
            ex.getMessage());
        assertTrue(!ex.getMessage().contains("segredo.test"), "URL não pode vazar");
    }
}
