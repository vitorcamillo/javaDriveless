package io.github.selenium.javaDriverless;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.selenium.javaDriverless.types.BaseTarget;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.slf4j.Logger;

import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.nullable;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ChromeAuthSupportTest {
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    @Test
    @SuppressWarnings("unchecked")
    void installsOneListenerAndMatchesOnlyExactChallengeOrigin() {
        BaseTarget baseTarget = mock(BaseTarget.class);
        CompletableFuture<JsonNode> enableGate = new CompletableFuture<>();
        AtomicReference<Consumer<JsonNode>> listener = new AtomicReference<>();
        when(baseTarget.executeCdpCmd(eq("Fetch.enable"), anyMap(), nullable(Float.class)))
                .thenReturn(enableGate);
        when(baseTarget.addCdpListener(eq("Fetch.authRequired"), org.mockito.ArgumentMatchers.any()))
                .thenAnswer(invocation -> {
                    listener.set(invocation.getArgument(1));
                    return CompletableFuture.completedFuture(null);
                });
        when(baseTarget.executeCdpCmd(eq("Fetch.continueWithAuth"), anyMap(), nullable(Float.class)))
                .thenReturn(CompletableFuture.completedFuture(OBJECT_MAPPER.createObjectNode()));

        ChromeAuthSupport support = new ChromeAuthSupport(mock(Logger.class));
        CompletableFuture<Void> first = support.setAuth(
                "sensitive-user", "sensitive-password", "proxy.example:8080",
                () -> CompletableFuture.completedFuture(baseTarget));
        CompletableFuture<Void> concurrent = support.setAuth(
                "second-user", "second-password", "https://other.example:8443",
                () -> CompletableFuture.completedFuture(baseTarget));

        assertThat(concurrent).isSameAs(first);
        verify(baseTarget, times(1)).executeCdpCmd(
                eq("Fetch.enable"), eq(Map.of("handleAuthRequests", true)), nullable(Float.class));

        enableGate.complete(OBJECT_MAPPER.createObjectNode());
        first.join();
        assertThat(listener.get()).isNotNull();

        listener.get().accept(authEvent(
                "request-evil", "https://evil.test/?x=proxy.example:8080"));
        listener.get().accept(authEvent(
                "request-exact", "http://proxy.example:8080"));

        ArgumentCaptor<Map<String, Object>> arguments = ArgumentCaptor.forClass(Map.class);
        verify(baseTarget, times(2)).executeCdpCmd(
                eq("Fetch.continueWithAuth"), arguments.capture(), nullable(Float.class));
        assertThat(response(arguments.getAllValues().get(0)).get("response"))
                .isEqualTo("CancelAuth");
        assertThat(response(arguments.getAllValues().get(0)))
                .doesNotContainKeys("username", "password");
        assertThat(response(arguments.getAllValues().get(1)))
                .containsEntry("response", "ProvideCredentials")
                .containsEntry("username", "sensitive-user")
                .containsEntry("password", "sensitive-password");
    }

    @Test
    void rejectsEmptyOrPathBasedConfiguredOriginBeforeInstallingFetch() {
        ChromeAuthSupport support = new ChromeAuthSupport(mock(Logger.class));

        assertThatThrownBy(() -> support.setAuth(
                        "user", "password", "",
                        () -> CompletableFuture.failedFuture(new AssertionError("não deve instalar"))).join())
                .hasRootCauseMessage("Origem de autenticação inválida; use scheme://host:port ou host:port");
        assertThatThrownBy(() -> support.setAuth(
                        "user", "password", "https://proxy.example/path",
                        () -> CompletableFuture.failedFuture(new AssertionError("não deve instalar"))).join())
                .hasRootCauseMessage("Origem de autenticação inválida; use scheme://host:port ou host:port");
    }

    @Test
    void normalizesSchemeHostAndDefaultPortWithoutSubstringMatching() {
        assertThat(ChromeAuthSupport.normalizeOrigin("HTTPS://ExAmPlE.com", false))
                .isEqualTo(new ChromeAuthSupport.AuthOrigin("https", "example.com", 443));
        assertThat(ChromeAuthSupport.normalizeChallengeOrigin(
                "https://evil.test/?next=https://example.com"))
                .isEmpty();
    }

    @Test
    void normalizesIpv6AndIdnHostsAndRejectsOutOfRangePorts() {
        // IPv6 entre colchetes: colchetes removidos, host minúsculo, porta explícita
        assertThat(ChromeAuthSupport.normalizeOrigin("http://[::1]:8080", false))
                .isEqualTo(new ChromeAuthSupport.AuthOrigin("http", "::1", 8080));
        assertThat(ChromeAuthSupport.normalizeChallengeOrigin("http://[::1]:8080"))
                .isPresent();

        // IDN é transliterado para punycode em minúsculas
        assertThat(ChromeAuthSupport.normalizeOrigin("https://BÜCHER.example", true))
                .isEqualTo(new ChromeAuthSupport.AuthOrigin("https", "xn--bcher-kva.example", 443));
        // ß/ς: java.net.IDN daria fass.de, o navegador xn--fa-hia.de; não pode casar com outro domínio
        assertThatThrownBy(() -> ChromeAuthSupport.normalizeOrigin("https://faß.de", false)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> ChromeAuthSupport.normalizeOrigin("https://ας.gr", false)).isInstanceOf(IllegalArgumentException.class);

        // porta fora do range falha fechado, tanto configurada quanto no challenge
        assertThatThrownBy(() -> ChromeAuthSupport.normalizeOrigin("http://proxy.example:70000", false))
                .isInstanceOf(RuntimeException.class);
        assertThatThrownBy(() -> ChromeAuthSupport.normalizeOrigin("http://proxy.example:0", false))
                .isInstanceOf(RuntimeException.class);
        assertThat(ChromeAuthSupport.normalizeChallengeOrigin("http://proxy.example:70000"))
                .isEmpty();
    }

    @Test
    void resetForNewSessionForcesListenerInstallationOnTheNewTarget() {
        BaseTarget firstTarget = readyBaseTarget();
        BaseTarget restartedTarget = readyBaseTarget();
        ChromeAuthSupport support = new ChromeAuthSupport(mock(Logger.class));

        support.setAuth(
                "user", "password", "proxy.example:8080",
                () -> CompletableFuture.completedFuture(firstTarget)).join();
        support.resetForNewSession();
        support.setAuth(
                "user", "password", "proxy.example:8080",
                () -> CompletableFuture.completedFuture(restartedTarget)).join();

        verify(firstTarget, times(1)).executeCdpCmd(
                eq("Fetch.enable"), eq(Map.of("handleAuthRequests", true)), nullable(Float.class));
        verify(restartedTarget, times(1)).executeCdpCmd(
                eq("Fetch.enable"), eq(Map.of("handleAuthRequests", true)), nullable(Float.class));
    }

    @Test
    @SuppressWarnings("unchecked")
    void resetForNewSessionDoesNotReuseCredentialsFromThePreviousTenant() {
        BaseTarget firstTarget = readyBaseTarget();
        BaseTarget restartedTarget = mock(BaseTarget.class);
        AtomicReference<Consumer<JsonNode>> restartedListener = new AtomicReference<>();
        when(restartedTarget.executeCdpCmd(eq("Fetch.enable"), anyMap(), nullable(Float.class)))
                .thenReturn(CompletableFuture.completedFuture(OBJECT_MAPPER.createObjectNode()));
        when(restartedTarget.addCdpListener(
                eq("Fetch.authRequired"), org.mockito.ArgumentMatchers.any()))
                .thenAnswer(invocation -> {
                    restartedListener.set(invocation.getArgument(1));
                    return CompletableFuture.completedFuture(null);
                });
        when(restartedTarget.executeCdpCmd(
                eq("Fetch.continueWithAuth"), anyMap(), nullable(Float.class)))
                .thenReturn(CompletableFuture.completedFuture(OBJECT_MAPPER.createObjectNode()));

        ChromeAuthSupport support = new ChromeAuthSupport(mock(Logger.class));
        support.setAuth(
                "tenant-one", "tenant-one-password", "old.example:8080",
                () -> CompletableFuture.completedFuture(firstTarget)).join();
        support.resetForNewSession();
        support.setAuth(
                "tenant-two", "tenant-two-password", "new.example:8080",
                () -> CompletableFuture.completedFuture(restartedTarget)).join();

        restartedListener.get().accept(authEvent("old-origin", "http://old.example:8080"));

        ArgumentCaptor<Map<String, Object>> arguments = ArgumentCaptor.forClass(Map.class);
        verify(restartedTarget).executeCdpCmd(
                eq("Fetch.continueWithAuth"), arguments.capture(), nullable(Float.class));
        assertThat(response(arguments.getValue()))
                .containsEntry("response", "CancelAuth")
                .doesNotContainKeys("username", "password");
    }

    private JsonNode authEvent(String requestId, String origin) {
        ObjectNode event = OBJECT_MAPPER.createObjectNode();
        event.put("requestId", requestId);
        event.putObject("authChallenge").put("origin", origin);
        return event;
    }

    private BaseTarget readyBaseTarget() {
        BaseTarget target = mock(BaseTarget.class);
        when(target.executeCdpCmd(eq("Fetch.enable"), anyMap(), nullable(Float.class)))
                .thenReturn(CompletableFuture.completedFuture(OBJECT_MAPPER.createObjectNode()));
        when(target.addCdpListener(eq("Fetch.authRequired"), org.mockito.ArgumentMatchers.any()))
                .thenReturn(CompletableFuture.completedFuture(null));
        return target;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> response(Map<String, Object> arguments) {
        return (Map<String, Object>) arguments.get("authChallengeResponse");
    }
}
