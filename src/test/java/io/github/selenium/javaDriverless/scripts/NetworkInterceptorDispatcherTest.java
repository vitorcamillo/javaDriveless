package io.github.selenium.javaDriverless.scripts;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.NullNode;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import io.github.selenium.javaDriverless.types.Target;

class NetworkInterceptorDispatcherTest {

    @Test
    @SuppressWarnings("unchecked")
    void publicInterceptorSharesOneListenerAndCannotReintroduceGuardedHeaderValue() throws Exception {
        Target target = mock(Target.class);
        when(target.addCdpListener(eq("Fetch.requestPaused"), any()))
                .thenReturn(CompletableFuture.completedFuture(null));
        when(target.executeCdpCmd(eq("Fetch.enable"), any(), isNull()))
                .thenReturn(CompletableFuture.completedFuture(NullNode.getInstance()));
        when(target.executeCdpCmd(eq("Fetch.continueRequest"), any(), isNull()))
                .thenReturn(CompletableFuture.completedFuture(NullNode.getInstance()));

        NetworkInterceptor.installEssentialRequestHeaderGuard(
                target,
                "test-device-memory",
                headers -> {
                    Map<String, String> result = new LinkedHashMap<>(headers);
                    result.replaceAll((name, value) ->
                            "Sec-CH-Device-Memory".equalsIgnoreCase(name) ? "8" : value);
                    return result;
                },
                failure -> {
                    throw new AssertionError("Não deveria registrar falha", failure);
                }).join();
        NetworkInterceptor.addRequestListener(target, request -> request.continueRequest(
                null,
                null,
                null,
                Map.of("Accept", "text/html", "sec-ch-device-memory", "64")).join()).join();

        ArgumentCaptor<Consumer<JsonNode>> listener = ArgumentCaptor.forClass(Consumer.class);
        verify(target, times(1)).addCdpListener(eq("Fetch.requestPaused"), listener.capture());
        listener.getValue().accept(new ObjectMapper().readTree("""
                {
                  "requestId":"public-request",
                  "request":{
                    "url":"https://example.invalid/",
                    "method":"GET",
                    "headers":{"Sec-CH-Device-Memory":"32"}
                  }
                }
                """));

        ArgumentCaptor<Map<String, Object>> continueArgs = ArgumentCaptor.forClass(Map.class);
        verify(target).executeCdpCmd(eq("Fetch.continueRequest"), continueArgs.capture(), isNull());
        assertThat((List<Map<String, String>>) continueArgs.getValue().get("headers"))
                .containsExactlyInAnyOrder(
                        Map.of("name", "Accept", "value", "text/html"),
                        Map.of("name", "sec-ch-device-memory", "value", "8"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void guardFailureBlocksTheRequestAndReportsOnlyASafeMessage() throws Exception {
        Target target = mock(Target.class);
        when(target.addCdpListener(eq("Fetch.requestPaused"), any()))
                .thenReturn(CompletableFuture.completedFuture(null));
        when(target.executeCdpCmd(eq("Fetch.enable"), any(), isNull()))
                .thenReturn(CompletableFuture.completedFuture(NullNode.getInstance()));
        when(target.executeCdpCmd(eq("Fetch.failRequest"), any(), isNull()))
                .thenReturn(CompletableFuture.completedFuture(NullNode.getInstance()));
        AtomicReference<Throwable> recordedFailure = new AtomicReference<>();

        NetworkInterceptor.installEssentialRequestHeaderGuard(
                target,
                "failing-guard",
                headers -> {
                    throw new IllegalStateException(
                            "https://private.invalid/?token=secret Authorization: Bearer secret");
                },
                recordedFailure::set).join();

        ArgumentCaptor<Consumer<JsonNode>> listener = ArgumentCaptor.forClass(Consumer.class);
        verify(target).addCdpListener(eq("Fetch.requestPaused"), listener.capture());
        listener.getValue().accept(new ObjectMapper().readTree("""
                {
                  "requestId":"unsafe-request",
                  "request":{
                    "url":"https://private.invalid/?token=secret",
                    "method":"GET",
                    "headers":{"Authorization":"Bearer secret"}
                  }
                }
                """));

        assertThat(recordedFailure.get()).isNotNull();
        assertThat(recordedFailure.get().getMessage())
                .isEqualTo("Falha ao aplicar guard obrigatório de request")
                .doesNotContain("private.invalid", "token", "Authorization", "Bearer");
        assertThat(recordedFailure.get()).hasNoCause();
        ArgumentCaptor<Map<String, Object>> failArgs = ArgumentCaptor.forClass(Map.class);
        verify(target).executeCdpCmd(eq("Fetch.failRequest"), failArgs.capture(), isNull());
        assertThat(failArgs.getValue()).containsOnly(
                Map.entry("requestId", "unsafe-request"),
                Map.entry("errorReason", "BlockedByClient"));
        verify(target, never()).executeCdpCmd(eq("Fetch.continueRequest"), any(), isNull());
    }

    @Test
    @SuppressWarnings("unchecked")
    void publicUrlOnlyOverrideStillCarriesTheMandatoryHeaderRewrite() throws Exception {
        Target target = mock(Target.class);
        when(target.addCdpListener(eq("Fetch.requestPaused"), any()))
                .thenReturn(CompletableFuture.completedFuture(null));
        when(target.executeCdpCmd(eq("Fetch.enable"), any(), isNull()))
                .thenReturn(CompletableFuture.completedFuture(NullNode.getInstance()));
        when(target.executeCdpCmd(eq("Fetch.continueRequest"), any(), isNull()))
                .thenReturn(CompletableFuture.completedFuture(NullNode.getInstance()));

        NetworkInterceptor.installEssentialRequestHeaderGuard(
                target,
                "url-only-guard",
                headers -> {
                    Map<String, String> result = new LinkedHashMap<>(headers);
                    result.replaceAll((name, value) ->
                            "Device-Memory".equalsIgnoreCase(name) ? "8" : value);
                    return result;
                },
                failure -> {
                    throw new AssertionError("Não deveria registrar falha", failure);
                }).join();
        NetworkInterceptor.addRequestListener(target, request -> request.continueRequest(
                "https://replacement.invalid/", null, null, null).join()).join();

        ArgumentCaptor<Consumer<JsonNode>> listener = ArgumentCaptor.forClass(Consumer.class);
        verify(target).addCdpListener(eq("Fetch.requestPaused"), listener.capture());
        listener.getValue().accept(new ObjectMapper().readTree("""
                {
                  "requestId":"url-only-request",
                  "request":{
                    "url":"https://original.invalid/",
                    "method":"GET",
                    "headers":{"Accept":"text/html","Device-Memory":"32"}
                  }
                }
                """));

        ArgumentCaptor<Map<String, Object>> continueArgs = ArgumentCaptor.forClass(Map.class);
        verify(target).executeCdpCmd(eq("Fetch.continueRequest"), continueArgs.capture(), isNull());
        assertThat(continueArgs.getValue())
                .containsEntry("url", "https://replacement.invalid/")
                .containsKey("headers");
        assertThat((List<Map<String, String>>) continueArgs.getValue().get("headers"))
                .containsExactlyInAnyOrder(
                        Map.of("name", "Accept", "value", "text/html"),
                        Map.of("name", "Device-Memory", "value", "8"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void installingAGuardPreservesPreviouslyConfiguredPublicPatternsAndAuthHandling() {
        Target target = mock(Target.class);
        when(target.addCdpListener(eq("Fetch.requestPaused"), any()))
                .thenReturn(CompletableFuture.completedFuture(null));
        when(target.executeCdpCmd(eq("Fetch.enable"), any(), isNull()))
                .thenReturn(CompletableFuture.completedFuture(NullNode.getInstance()));

        NetworkInterceptor.enableFetch(
                target, List.of(NetworkInterceptor.RequestPattern.ANY_RESPONSE)).join();
        NetworkInterceptor.installEssentialRequestHeaderGuard(
                target,
                "preserve-public-config",
                LinkedHashMap::new,
                failure -> {
                    throw new AssertionError("Não deveria registrar falha", failure);
                }).join();

        ArgumentCaptor<Map<String, Object>> enableArgs = ArgumentCaptor.forClass(Map.class);
        verify(target, times(2)).executeCdpCmd(eq("Fetch.enable"), enableArgs.capture(), isNull());
        Map<String, Object> finalEnable = enableArgs.getAllValues().get(1);
        assertThat((List<Map<String, Object>>) finalEnable.get("patterns"))
                .containsExactlyInAnyOrder(
                        NetworkInterceptor.RequestPattern.ANY_REQUEST,
                        NetworkInterceptor.RequestPattern.ANY_RESPONSE);
        assertThat(finalEnable).containsEntry("handleAuthRequests", true);
    }

    @Test
    @SuppressWarnings("unchecked")
    void responseStageRemainsOwnedByThePublicInterceptorWithoutRequestHeaderMutation()
            throws Exception {
        Target target = mock(Target.class);
        when(target.addCdpListener(eq("Fetch.requestPaused"), any()))
                .thenReturn(CompletableFuture.completedFuture(null));
        when(target.executeCdpCmd(eq("Fetch.enable"), any(), isNull()))
                .thenReturn(CompletableFuture.completedFuture(NullNode.getInstance()));
        when(target.executeCdpCmd(eq("Fetch.continueRequest"), any(), isNull()))
                .thenReturn(CompletableFuture.completedFuture(NullNode.getInstance()));

        NetworkInterceptor.installEssentialRequestHeaderGuard(
                target,
                "request-stage-only",
                headers -> {
                    Map<String, String> result = new LinkedHashMap<>(headers);
                    result.replaceAll((name, value) ->
                            "Device-Memory".equalsIgnoreCase(name) ? "8" : value);
                    return result;
                },
                failure -> {
                    throw new AssertionError("Não deveria registrar falha", failure);
                }).join();
        NetworkInterceptor.addRequestListener(
                target, request -> request.continueRequest().join()).join();

        ArgumentCaptor<Consumer<JsonNode>> listener = ArgumentCaptor.forClass(Consumer.class);
        verify(target).addCdpListener(eq("Fetch.requestPaused"), listener.capture());
        listener.getValue().accept(new ObjectMapper().readTree("""
                {
                  "requestId":"response-stage",
                  "responseStatusCode":200,
                  "request":{
                    "url":"https://example.invalid/",
                    "method":"GET",
                    "headers":{"Device-Memory":"32"}
                  }
                }
                """));

        ArgumentCaptor<Map<String, Object>> continueArgs = ArgumentCaptor.forClass(Map.class);
        verify(target).executeCdpCmd(eq("Fetch.continueRequest"), continueArgs.capture(), isNull());
        assertThat(continueArgs.getValue()).containsOnly(Map.entry("requestId", "response-stage"));
    }
}
