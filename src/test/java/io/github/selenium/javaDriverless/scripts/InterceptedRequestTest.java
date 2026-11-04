package io.github.selenium.javaDriverless.scripts;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.Map;
import java.util.concurrent.CompletableFuture;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.junit.jupiter.api.Test;

import io.github.selenium.javaDriverless.scripts.NetworkInterceptor.InterceptedRequest;
import io.github.selenium.javaDriverless.scripts.NetworkInterceptor.Request;
import io.github.selenium.javaDriverless.scripts.NetworkInterceptor.RequestDoneException;
import io.github.selenium.javaDriverless.scripts.NetworkInterceptor.RequestPattern;
import io.github.selenium.javaDriverless.scripts.NetworkInterceptor.RequestStages;
import io.github.selenium.javaDriverless.types.Target;

/**
 * InterceptedRequest: dados da requisição interceptada e os três desfechos
 * CDP (continue simples, continue modificado, fail) com guarda de reuso.
 */
class InterceptedRequestTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static JsonNode requestPayload() {
        try {
            return MAPPER.readTree("""
                {
                  "url": "https://api.test/v1?x=1",
                  "method": "POST",
                  "headers": {"Authorization": "Bearer t", "Accept": "application/json"},
                  "postData": "corpo=1"
                }
                """);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static Target executorRespondendoOk() {
        Target target = mock(Target.class);
        when(target.executeCdpCmd(any(), anyMap(), isNull()))
            .thenReturn(CompletableFuture.completedFuture(MAPPER.createObjectNode()));
        return target;
    }

    @Test
    void requestExposesUrlMethodHeadersPostDataAndTarget() {
        Target target = mock(Target.class);
        Request request = new Request(requestPayload(), target);

        assertEquals("https://api.test/v1?x=1", request.getUrl());
        assertEquals("POST", request.getMethod());
        assertEquals("Bearer t", request.getHeaders().get("Authorization"));
        assertEquals("application/json", request.getHeaders().get("Accept"));
        assertEquals("corpo=1", request.getPostData());
        assertSame(target, request.getTarget());
        // diagnóstico sempre redacta a URL
        assertTrue(request.toString().contains("url=<redacted>"));
        assertTrue(request.toString().contains("headerCount=2"));
    }

    @Test
    void requestWithoutOptionalFieldsYieldsNulls() {
        try {
            JsonNode minimo = MAPPER.readTree("{\"url\":\"https://x.test/\"}");
            Request request = new Request(minimo, null);

            assertNull(request.getMethod());
            assertTrue(request.getHeaders().isEmpty());
            assertNull(request.getPostData());
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    @Test
    void interceptedRequestExposesIdsAndStartsUndone() {
        InterceptedRequest request = new InterceptedRequest(
            "req-1", "frame-9", requestPayload(), executorRespondendoOk());

        assertEquals("req-1", request.getRequestId());
        assertEquals("frame-9", request.getFrameId());
        assertFalse(request.isDone());
    }

    @Test
    void continueRequestSendsFetchContinueAndMarksDone() throws Exception {
        Target target = executorRespondendoOk();
        InterceptedRequest request = new InterceptedRequest(
            "req-2", "frame-1", requestPayload(), target);

        request.continueRequest().get(5, java.util.concurrent.TimeUnit.SECONDS);

        assertTrue(request.isDone());
        verify(target).executeCdpCmd(eq("Fetch.continueRequest"),
            org.mockito.ArgumentMatchers.argThat(args -> "req-2".equals(args.get("requestId"))),
            isNull());

        RequestDoneException done = assertThrows(RequestDoneException.class,
            request::continueRequest);
        assertSame(request, done.getRequest());
        assertTrue(done.getMessage().contains("<redacted>"));
    }

    @Test
    void failRequestSendsErrorReasonAndMarksDone() throws Exception {
        Target target = executorRespondendoOk();
        InterceptedRequest request = new InterceptedRequest(
            "req-3", "frame-1", requestPayload(), target);

        request.failRequest("Aborted").get(5, java.util.concurrent.TimeUnit.SECONDS);

        assertTrue(request.isDone());
        verify(target).executeCdpCmd(eq("Fetch.failRequest"),
            org.mockito.ArgumentMatchers.argThat(args -> "Aborted".equals(args.get("errorReason"))
                && "req-3".equals(args.get("requestId"))),
            isNull());
        assertThrows(RequestDoneException.class, () -> request.failRequest("Failed"));
    }

    @Test
    void modifiedContinueCarriesUrlMethodBodyAndHeadersAsEntries() throws Exception {
        Target target = executorRespondendoOk();
        InterceptedRequest request = new InterceptedRequest(
            "req-4", "frame-1", requestPayload(), target);

        request.continueRequest("https://api.test/v2", "PUT", "novo", Map.of("X-A", "1"))
            .get(5, java.util.concurrent.TimeUnit.SECONDS);

        verify(target).executeCdpCmd(eq("Fetch.continueRequest"),
            org.mockito.ArgumentMatchers.argThat(args -> {
                @SuppressWarnings("unchecked")
                java.util.List<Map<String, String>> headers =
                    (java.util.List<Map<String, String>>) args.get("headers");
                return "https://api.test/v2".equals(args.get("url"))
                    && "PUT".equals(args.get("method"))
                    && "novo".equals(args.get("postData"))
                    && headers != null && headers.stream()
                        .anyMatch(h -> "X-A".equals(h.get("name")) && "1".equals(h.get("value")));
            }),
            isNull());
    }

    @Test
    void headerGuardTransformsHeadersOnModifiedContinue() throws Exception {
        Target target = executorRespondendoOk();
        InterceptedRequest request = new InterceptedRequest(
            "req-5", "frame-1", requestPayload(), target,
            null, headers -> Map.of("X-Guardado", "sim"), ignored -> { });

        request.continueRequest(null, null, null, Map.of("X-Sai", "fora"))
            .get(5, java.util.concurrent.TimeUnit.SECONDS);

        verify(target).executeCdpCmd(eq("Fetch.continueRequest"),
            org.mockito.ArgumentMatchers.argThat(args -> {
                @SuppressWarnings("unchecked")
                java.util.List<Map<String, String>> headers =
                    (java.util.List<Map<String, String>>) args.get("headers");
                return headers != null && headers.stream()
                    .allMatch(h -> "X-Guardado".equals(h.get("name")));
            }),
            isNull());
    }

    @Test
    void failureSinkObservesCdpErrors() throws Exception {
        Target target = mock(Target.class);
        when(target.executeCdpCmd(any(), anyMap(), isNull()))
            .thenReturn(CompletableFuture.failedFuture(new IllegalStateException("cdp caiu")));
        java.util.List<Throwable> falhas = new java.util.ArrayList<>();
        InterceptedRequest request = new InterceptedRequest(
            "req-6", "frame-1", requestPayload(), target, null,
            java.util.function.UnaryOperator.identity(), falhas::add);

        var falhou = assertThrows(java.util.concurrent.ExecutionException.class,
            () -> request.continueRequest().get(5, java.util.concurrent.TimeUnit.SECONDS));

        assertTrue(request.isDone());
        assertInstanceOf(IllegalStateException.class, falhou.getCause());
        assertTrue(falhou.getCause().getMessage().contains("cdp caiu"));
        assertEquals(1, falhas.size());
        assertTrue(falhas.get(0).getMessage().contains("cdp caiu"));
    }

    @Test
    void invalidTargetFailsFutureWithIllegalState() {
        InterceptedRequest request = new InterceptedRequest(
            "req-7", "frame-1", requestPayload(), new Object());

        var falhou = assertThrows(java.util.concurrent.CompletionException.class,
            () -> request.continueRequest().join());
        assertInstanceOf(IllegalStateException.class, falhou.getCause());
    }

    @Test
    void requestStagesAndPatternsExposeCanonicalValues() {
        assertEquals(0, RequestStages.REQUEST);
        assertEquals(1, RequestStages.RESPONSE);
        assertEquals("*", RequestPattern.ANY_REQUEST.get("urlPattern"));
        assertEquals("Request", RequestPattern.ANY_REQUEST.get("requestStage"));
        assertEquals("Response", RequestPattern.ANY_RESPONSE.get("requestStage"));
    }
}
