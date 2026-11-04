package io.github.selenium.javaDriverless;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
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
import org.mockito.InOrder;
import org.slf4j.LoggerFactory;

import io.github.selenium.javaDriverless.types.BrowserFingerprintProfile;
import io.github.selenium.javaDriverless.types.ChromeOptions;
import io.github.selenium.javaDriverless.types.Target;

class FingerprintClientHintGuardTest {

    @Test
    void rewritesOnlyDeviceMemoryHeadersThatWereAlreadyPresentIgnoringCase() {
        Map<String, String> original = new LinkedHashMap<>();
        original.put("Accept", "text/html");
        original.put("sEc-Ch-DeViCe-MeMoRy", "32");
        original.put("DEVICE-MEMORY", "32");

        Map<String, String> rewritten = FingerprintClientHintGuard.rewritePresentHeaders(
                original, profileWithMemory(8));

        assertThat(rewritten).containsEntry("Accept", "text/html");
        assertThat(rewritten).containsEntry("sEc-Ch-DeViCe-MeMoRy", "8");
        assertThat(rewritten).containsEntry("DEVICE-MEMORY", "8");
        assertThat(rewritten).doesNotContainKey("Sec-CH-Device-Memory");
        assertThat(rewritten).doesNotContainKey("Device-Memory");
        assertThat(original).containsEntry("sEc-Ch-DeViCe-MeMoRy", "32");
    }

    @Test
    void minimalCdpPreservesNativeDeviceMemoryHeaders() {
        BrowserFingerprintProfile profile = profileWithMemory(8);
        profile.runtimePolicy = BrowserFingerprintProfile.RUNTIME_POLICY_MINIMAL_CDP;
        Map<String, String> original = Map.of(
                "Sec-CH-Device-Memory", "32",
                "Device-Memory", "32",
                "Accept", "text/html");

        assertThat(FingerprintClientHintGuard.rewritePresentHeaders(original, profile))
                .isEqualTo(original);
    }

    @Test
    void minimalCdpWithNativeConnectionDoesNotInstallFetchInterception() {
        Target target = mock(Target.class);
        BrowserFingerprintProfile profile = profileWithMemory(8);
        profile.runtimePolicy = BrowserFingerprintProfile.RUNTIME_POLICY_MINIMAL_CDP;
        profile.connection.mode = "real";

        FingerprintClientHintGuard.install(target, profile, failure -> {
            throw new AssertionError("Não deveria registrar falha", failure);
        }).join();

        org.mockito.Mockito.verifyNoInteractions(target);
    }

    @Test
    void minimalCdpRejectsConnectionSpoofInsteadOfCreatingHeaderOnlyCoherence() {
        BrowserFingerprintProfile profile = profileWithMemory(8);
        profile.runtimePolicy = BrowserFingerprintProfile.RUNTIME_POLICY_MINIMAL_CDP;
        profile.connection.mode = "spoof";

        assertThat(org.assertj.core.api.Assertions.catchThrowable(
                () -> FingerprintClientHintGuard.install(mock(Target.class), profile, ignored -> {}).join()))
                .hasRootCauseInstanceOf(IllegalArgumentException.class)
                .rootCause()
                .hasMessageContaining("connection.mode=spoof");
    }

    @Test
    void spoofedConnectionRewritesOnlyPresentNetworkQualityHintsFromTheProfile() {
        BrowserFingerprintProfile profile = new BrowserFingerprintProfile();
        profile.deviceMemory = 8;
        profile.connection.mode = "spoof";
        profile.connection.rtt = 75;
        profile.connection.downlink = 5.5;
        profile.connection.effectiveType = "3g";
        profile.connection.saveData = true;
        Map<String, String> original = new LinkedHashMap<>();
        original.put("Sec-CH-RTT", "50");
        original.put("rTt", "50");
        original.put("SEC-CH-DOWNLINK", "10");
        original.put("Downlink", "10");
        original.put("Sec-CH-ECT", "4g");
        original.put("ect", "4g");
        original.put("sAvE-dAtA", "off");
        original.put("Accept", "text/html");

        Map<String, String> rewritten =
                FingerprintClientHintGuard.rewritePresentHeaders(original, profile);

        assertThat(rewritten)
                .containsEntry("Sec-CH-RTT", "75")
                .containsEntry("rTt", "75")
                .containsEntry("SEC-CH-DOWNLINK", "5.5")
                .containsEntry("Downlink", "5.5")
                .containsEntry("Sec-CH-ECT", "3g")
                .containsEntry("ect", "3g")
                .containsEntry("sAvE-dAtA", "on")
                .containsEntry("Accept", "text/html")
                .doesNotContainKeys("Sec-CH-Device-Memory", "Device-Memory");
    }

    @Test
    void spoofedConnectionWithSaveDataDisabledRemovesOnlyAnExistingSaveDataHeader() {
        BrowserFingerprintProfile profile = new BrowserFingerprintProfile();
        profile.connection.mode = "spoof";
        profile.connection.saveData = false;
        Map<String, String> original = new LinkedHashMap<>();
        original.put("Save-Data", "on");
        original.put("Accept", "text/html");

        Map<String, String> rewritten =
                FingerprintClientHintGuard.rewritePresentHeaders(original, profile);

        assertThat(rewritten)
                .containsOnly(Map.entry("Accept", "text/html"))
                .doesNotContainKeys("Save-Data", "Sec-CH-RTT", "RTT",
                        "Sec-CH-Downlink", "Downlink", "Sec-CH-ECT", "ECT");
    }

    @Test
    void realConnectionModeLeavesNativeNetworkQualityHintsUntouched() {
        BrowserFingerprintProfile profile = new BrowserFingerprintProfile();
        profile.connection.mode = "real";
        profile.connection.rtt = 75;
        profile.connection.downlink = 5.5;
        profile.connection.effectiveType = "3g";
        profile.connection.saveData = false;
        Map<String, String> original = new LinkedHashMap<>();
        original.put("Sec-CH-RTT", "50");
        original.put("Sec-CH-Downlink", "10");
        original.put("Sec-CH-ECT", "4g");
        original.put("Save-Data", "on");

        assertThat(FingerprintClientHintGuard.rewritePresentHeaders(original, profile))
                .isEqualTo(original);
    }

    @Test
    @SuppressWarnings("unchecked")
    void installsListenerBeforeFetchAndContinuesWithOnlyExistingHintsRewritten() throws Exception {
        Target target = mock(Target.class);
        when(target.addCdpListener(eq("Fetch.requestPaused"), any()))
                .thenReturn(CompletableFuture.completedFuture(null));
        when(target.executeCdpCmd(eq("Fetch.enable"), any(), isNull()))
                .thenReturn(CompletableFuture.completedFuture(NullNode.getInstance()));
        when(target.executeCdpCmd(eq("Fetch.continueRequest"), any(), isNull()))
                .thenReturn(CompletableFuture.completedFuture(NullNode.getInstance()));

        FingerprintClientHintGuard.install(target, profileWithMemory(8), failure -> {
            throw new AssertionError("Não deveria registrar falha", failure);
        }).join();

        InOrder installOrder = inOrder(target);
        ArgumentCaptor<Consumer<JsonNode>> listenerCaptor = ArgumentCaptor.forClass(Consumer.class);
        installOrder.verify(target).addCdpListener(eq("Fetch.requestPaused"), listenerCaptor.capture());
        installOrder.verify(target).executeCdpCmd(eq("Fetch.enable"), any(), isNull());

        JsonNode paused = new ObjectMapper().readTree("""
                {
                  "requestId":"request-1",
                  "request":{
                    "url":"https://example.invalid/private?token=secret",
                    "method":"GET",
                    "headers":{
                      "Accept":"text/html",
                      "SEC-CH-DEVICE-MEMORY":"32"
                    }
                  }
                }
                """);
        listenerCaptor.getValue().accept(paused);

        ArgumentCaptor<Map<String, Object>> continueArgs = ArgumentCaptor.forClass(Map.class);
        org.mockito.Mockito.verify(target).executeCdpCmd(
                eq("Fetch.continueRequest"), continueArgs.capture(), isNull());
        assertThat(continueArgs.getValue()).containsEntry("requestId", "request-1");
        assertThat((List<Map<String, String>>) continueArgs.getValue().get("headers"))
                .containsExactlyInAnyOrder(
                        Map.of("name", "Accept", "value", "text/html"),
                        Map.of("name", "SEC-CH-DEVICE-MEMORY", "value", "8"));
    }

    @Test
    void fingerprintOverridesInstallTheGuardBeforeAnyNetworkOverride() {
        Target target = mock(Target.class);
        when(target.addCdpListener(eq("Fetch.requestPaused"), any()))
                .thenReturn(CompletableFuture.completedFuture(null));
        when(target.executeCdpCmd(anyString(), any(), isNull()))
                .thenReturn(CompletableFuture.completedFuture(NullNode.getInstance()));

        BrowserFingerprintProfile profile = new BrowserFingerprintProfile();
        profile.deviceMemory = 8;
        ChromeFingerprintSupport support = new ChromeFingerprintSupport(
                new ChromeOptions(),
                profile,
                new ObjectMapper(),
                LoggerFactory.getLogger(FingerprintClientHintGuardTest.class),
                () -> "Mozilla/5.0 Chrome/150.0.7871.46 Safari/537.36");

        support.applyFingerprintProfileCdpOverrides(target).join();

        InOrder order = inOrder(target);
        order.verify(target).addCdpListener(eq("Fetch.requestPaused"), any());
        order.verify(target).executeCdpCmd(eq("Fetch.enable"), any(), isNull());
        order.verify(target).executeCdpCmd(eq("Network.enable"), any(), isNull());
    }

    @Test
    @SuppressWarnings("unchecked")
    void requestWithoutDeviceMemoryHintContinuesWithoutAHeadersOverride() throws Exception {
        Target target = mock(Target.class);
        when(target.addCdpListener(eq("Fetch.requestPaused"), any()))
                .thenReturn(CompletableFuture.completedFuture(null));
        when(target.executeCdpCmd(eq("Fetch.enable"), any(), isNull()))
                .thenReturn(CompletableFuture.completedFuture(NullNode.getInstance()));
        when(target.executeCdpCmd(eq("Fetch.continueRequest"), any(), isNull()))
                .thenReturn(CompletableFuture.completedFuture(NullNode.getInstance()));

        FingerprintClientHintGuard.install(target, profileWithMemory(8), failure -> {
            throw new AssertionError("Não deveria registrar falha", failure);
        }).join();

        ArgumentCaptor<Consumer<JsonNode>> listenerCaptor = ArgumentCaptor.forClass(Consumer.class);
        org.mockito.Mockito.verify(target)
                .addCdpListener(eq("Fetch.requestPaused"), listenerCaptor.capture());
        listenerCaptor.getValue().accept(new ObjectMapper().readTree("""
                {
                  "requestId":"request-without-hint",
                  "request":{
                    "url":"https://example.invalid/",
                    "method":"GET",
                    "headers":{"Accept":"text/html"}
                  }
                }
                """));

        ArgumentCaptor<Map<String, Object>> continueArgs = ArgumentCaptor.forClass(Map.class);
        org.mockito.Mockito.verify(target).executeCdpCmd(
                eq("Fetch.continueRequest"), continueArgs.capture(), isNull());
        assertThat(continueArgs.getValue())
                .containsOnly(Map.entry("requestId", "request-without-hint"));
    }

    @Test
    void listenerInstallationFailureAbortsOverridesAndRecordsOnlySafeRuntimeMessage() {
        Target target = mock(Target.class);
        when(target.addCdpListener(eq("Fetch.requestPaused"), any()))
                .thenReturn(CompletableFuture.failedFuture(new IllegalStateException(
                        "https://private.invalid/?token=secret Authorization: Bearer secret")));

        ChromeFingerprintSupport support = new ChromeFingerprintSupport(
                new ChromeOptions(),
                new BrowserFingerprintProfile(),
                new ObjectMapper(),
                LoggerFactory.getLogger(FingerprintClientHintGuardTest.class),
                () -> "Mozilla/5.0 Chrome/150.0.7871.46 Safari/537.36");

        assertThat(org.assertj.core.api.Assertions.catchThrowable(
                () -> support.applyFingerprintProfileCdpOverrides(target).join()))
                .isNotNull();
        assertThat(support.hasRuntimeFailure()).isTrue();
        assertThat(support.runtimeFailureMessage()).hasValueSatisfying(message ->
                assertThat(message)
                        .isEqualTo("Falha ao instalar guard obrigatório de requests")
                        .doesNotContain("private.invalid", "token", "Authorization", "Bearer"));
        verify(target, never()).executeCdpCmd(eq("Fetch.enable"), any(), isNull());
        verify(target, never()).executeCdpCmd(eq("Network.enable"), any(), isNull());
    }

    @Test
    @SuppressWarnings("unchecked")
    void asynchronousContinuationFailureIsRecordedWithoutRequestData() throws Exception {
        Target target = mock(Target.class);
        when(target.addCdpListener(eq("Fetch.requestPaused"), any()))
                .thenReturn(CompletableFuture.completedFuture(null));
        when(target.executeCdpCmd(eq("Fetch.enable"), any(), isNull()))
                .thenReturn(CompletableFuture.completedFuture(NullNode.getInstance()));
        when(target.executeCdpCmd(eq("Fetch.continueRequest"), any(), isNull()))
                .thenReturn(CompletableFuture.failedFuture(new IllegalStateException(
                        "https://private.invalid/?token=secret Authorization: Bearer secret")));
        AtomicReference<Throwable> runtimeFailure = new AtomicReference<>();

        FingerprintClientHintGuard.install(
                target, profileWithMemory(8), runtimeFailure::set).join();
        ArgumentCaptor<Consumer<JsonNode>> listener = ArgumentCaptor.forClass(Consumer.class);
        verify(target).addCdpListener(eq("Fetch.requestPaused"), listener.capture());
        listener.getValue().accept(new ObjectMapper().readTree("""
                {
                  "requestId":"failed-continuation",
                  "request":{
                    "url":"https://private.invalid/?token=secret",
                    "method":"GET",
                    "headers":{"Device-Memory":"32","Authorization":"Bearer secret"}
                  }
                }
                """));

        assertThat(runtimeFailure.get()).isNotNull();
        assertThat(runtimeFailure.get().getMessage())
                .isEqualTo("Falha ao concluir request interceptado")
                .doesNotContain("private.invalid", "token", "Authorization", "Bearer");
    }

    private static BrowserFingerprintProfile profileWithMemory(int deviceMemory) {
        BrowserFingerprintProfile profile = new BrowserFingerprintProfile();
        profile.deviceMemory = deviceMemory;
        return profile;
    }
}
