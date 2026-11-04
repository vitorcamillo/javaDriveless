package io.github.selenium.javaDriverless;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.selenium.javaDriverless.cdp.CDPSocket;
import io.github.selenium.javaDriverless.types.BaseTarget;
import io.github.selenium.javaDriverless.types.BrowserFingerprintProfile;
import io.github.selenium.javaDriverless.types.CdpTransport;
import io.github.selenium.javaDriverless.types.ChromeOptions;
import io.github.selenium.javaDriverless.types.EgressInfo;
import io.github.selenium.javaDriverless.types.ProxyConfig;
import io.github.selenium.javaDriverless.types.Target;
import io.github.selenium.javaDriverless.utils.UserAgentResolver;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.slf4j.LoggerFactory;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.function.BiConsumer;
import java.util.stream.Collectors;
import java.lang.reflect.Field;
import java.lang.reflect.Method;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.nullable;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ChromeSessionStartupSafetyTest extends ChromeSessionStartupTestSupport {
    @Test
    void cleanupClearsRuntimeFingerprintFailureBeforeSessionReuse() throws Exception {
        Chrome chrome = new Chrome(new ChromeOptions(), 0.25f, false, 1 << 20);
        Field supportField = declaredField(ChromeSessionCore.class, "fingerprintSupport");
        supportField.setAccessible(true);
        ChromeFingerprintSupport support = (ChromeFingerprintSupport) supportField.get(chrome);
        Method recordFailure = ChromeFingerprintSupport.class
                .getDeclaredMethod("recordRuntimeFailure", Throwable.class);
        recordFailure.setAccessible(true);
        recordFailure.invoke(support, new IllegalStateException("worker spoof failed"));
        assertThat(chrome.hasFingerprintRuntimeFailure()).isTrue();

        chrome.cleanupAfterStartupFailure();

        assertThat(chrome.hasFingerprintRuntimeFailure()).isFalse();
        assertThat(chrome.getFingerprintRuntimeFailure()).isEmpty();
    }

    @Test
    void deferredStartupNavigationTransportFailurePropagates() {
        Chrome chrome = new Chrome(new ChromeOptions(), 0.25f, false, 1 << 20);
        Target target = mock(Target.class);
        chrome.currentTarget = target;
        when(target.executeCdpCmd(eq("Page.navigate"), anyMap(), nullable(Float.class)))
                .thenReturn(CompletableFuture.failedFuture(
                        new IllegalStateException("navigation transport failed")));

        assertThatThrownBy(() -> chrome.navigateDeferredStartupUrl("https://example.invalid"))
                .hasRootCauseMessage("navigation transport failed");
    }

    @Test
    void deferredStartupNavigationErrorTextFailsWithoutEchoingPayload() {
        Chrome chrome = new Chrome(new ChromeOptions(), 0.25f, false, 1 << 20);
        Target target = mock(Target.class);
        chrome.currentTarget = target;
        JsonNode result = new ObjectMapper().createObjectNode()
                .put("errorText", "net::ERR_FAILED https://capture-user:capture-password@example.invalid");
        when(target.executeCdpCmd(eq("Page.navigate"), anyMap(), nullable(Float.class)))
                .thenReturn(CompletableFuture.completedFuture(result));

        assertThatThrownBy(() -> chrome.navigateDeferredStartupUrl("https://example.invalid"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("StartupUrl navigation failed")
                .hasMessageNotContaining("capture-user")
                .hasMessageNotContaining("capture-password")
                .hasMessageNotContaining("example.invalid");
    }

    @Test
    void existingPageTargetIsSelectedWithoutCreatingAnotherPage() {
        var targets = new ObjectMapper().createArrayNode();
        targets.addObject().put("id", "other-target").put("type", "other");
        targets.addObject().put("id", "page-target").put("type", "page");
        AtomicBoolean creatorCalled = new AtomicBoolean();

        String targetId = ChromeSessionCore.resolveInitialPageTargetId(targets, false, () -> {
            creatorCalled.set(true);
            return new ObjectMapper().createObjectNode().put("targetId", "unexpected");
        });

        assertThat(targetId).isEqualTo("page-target");
        assertThat(creatorCalled).isFalse();
    }

    @Test
    void ownedChromeCreatesBlankPageWhenDiscoveryOnlyContainsOtherTargets() {
        var targets = new ObjectMapper().createArrayNode();
        targets.addObject().put("id", "other-target").put("type", "other");

        String targetId = ChromeSessionCore.resolveInitialPageTargetId(targets, false,
                () -> new ObjectMapper().createObjectNode().put("targetId", "created-page"));

        assertThat(targetId).isEqualTo("created-page");
    }

    @Test
    void attachModeWithoutPageFailsInsteadOfPublishingNullTarget() {
        var targets = new ObjectMapper().createArrayNode();
        targets.addObject().put("id", "external-other-target").put("type", "other");
        AtomicBoolean creatorCalled = new AtomicBoolean();

        assertThatThrownBy(() -> ChromeSessionCore.resolveInitialPageTargetId(targets, true, () -> {
            creatorCalled.set(true);
            return new ObjectMapper().createObjectNode().put("targetId", "must-not-create");
        })).isInstanceOf(IllegalStateException.class)
                .hasMessage("Chrome externo sem target page disponível");
        assertThat(creatorCalled).isFalse();
    }

    @Test
    void startupFailureTerminatesProcessTreeAndDeletesOwnedTempDirectory() throws Exception {
        Path childPidFile = testDir.resolve("child.pid");
        Path fakeChrome = testDir.resolve("fake-chrome.sh");
        Files.writeString(fakeChrome, """
                #!/bin/sh
                sleep 60 &
                child=$!
                printf '%s' "$child" > "$JDL_TEST_CHILD_PID_FILE"
                wait "$child"
                """);
        assertThat(fakeChrome.toFile().setExecutable(true)).isTrue();

        ChromeOptions options = new ChromeOptions();
        options.setBinaryLocation(fakeChrome.toString());
        // Evita depender da alocação de ServerSocket do sandbox; este argumento continua
        // representando launch local, não attach a um Chrome externo.
        options.addArgument("--remote-debugging-port=1");
        Map<String, String> environment = new HashMap<>(options.getEnv());
        environment.put("JDL_TEST_CHILD_PID_FILE", childPidFile.toString());
        options.setEnv(environment);
        Chrome chrome = new Chrome(options, 0.25f, false, 1 << 20);

        try {
            CompletableFuture<Chrome> firstAttempt = chrome.startSession();
            CompletableFuture<Chrome> concurrentAttempt = chrome.startSession();
            assertThat(concurrentAttempt).isSameAs(firstAttempt);
            assertThatThrownBy(firstAttempt::join)
                    .hasRootCauseInstanceOf(RuntimeException.class);

            Path ownedTempDirectory = Path.of(options.getUserDataDir());
            long childPid = Long.parseLong(Files.readString(childPidFile));

            assertThat(chrome.started).isFalse();
            assertThat(chrome.process).isNotNull();
            assertThat(chrome.process.isAlive()).isFalse();
            assertThat(ProcessHandle.of(childPid).map(ProcessHandle::isAlive).orElse(false)).isFalse();
            assertThat(ownedTempDirectory).doesNotExist();
            assertThat(options.getArguments())
                    .contains("--webrtc-ip-handling-policy=disable_non_proxied_udp")
                    .noneMatch(argument -> argument.startsWith("--force-webrtc-ip-handling-policy="));
            @SuppressWarnings("unchecked")
            Map<String, Object> webRtcPrefs = (Map<String, Object>) options.getPrefs().get("webrtc");
            assertThat(webRtcPrefs)
                    .containsEntry("ip_handling_policy", "disable_non_proxied_udp")
                    .containsEntry("multiple_routes_enabled", false)
                    .containsEntry("nonproxied_udp_enabled", false);
        } finally {
            forceCleanup(chrome.process);
            if (options.getUserDataDir() != null) {
                deleteDirectory(Path.of(options.getUserDataDir()));
            }
        }
    }

    @Test
    void minimalHeadlessFailsBeforeProcessSpawnAndNeverAddsAUserAgentArgument() throws Exception {
        Path marker = testDir.resolve("minimal-headless-process-started");
        Path fakeChrome = testDir.resolve("must-not-start-chrome.sh");
        Files.writeString(fakeChrome, """
                #!/bin/sh
                printf 'started' > "$JDL_MINIMAL_HEADLESS_MARKER"
                exit 1
                """);
        assertThat(fakeChrome.toFile().setExecutable(true)).isTrue();

        BrowserFingerprintProfile profile = BrowserFingerprintProfile.nativeDefaults();
        profile.runtimePolicy = BrowserFingerprintProfile.RUNTIME_POLICY_MINIMAL_CDP;
        ChromeOptions options = new ChromeOptions();
        options.setBinaryLocation(fakeChrome.toString());
        options.setFingerprintProfile(profile);
        options.setHeadless(true);
        Map<String, String> environment = new HashMap<>(options.getEnv());
        environment.put("JDL_MINIMAL_HEADLESS_MARKER", marker.toString());
        options.setEnv(environment);
        Chrome chrome = new Chrome(options, 0.25f, false, 1 << 20);

        assertThatThrownBy(() -> chrome.startSession().join())
                .hasRootCauseInstanceOf(IllegalStateException.class)
                .hasStackTraceContaining("minimal-cdp não suporta Chrome --headless")
                .hasStackTraceContaining("scripts/run-visible-xvfb.sh");
        assertThat(marker).doesNotExist();
        assertThat(chrome.process).isNull();
        assertThat(options.getArguments())
                .noneMatch(argument -> argument.startsWith("--user-agent="));
    }

    @Test
    void egressResolverFailureAbortsBeforeBrowserProcessSpawn() throws Exception {
        Path marker = testDir.resolve("egress-failure-process-started");
        Path fakeChrome = testDir.resolve("must-not-start-after-egress-failure.sh");
        Files.writeString(fakeChrome, """
                #!/bin/sh
                printf 'started' > "$JDL_EGRESS_FAILURE_MARKER"
                exit 1
                """);
        assertThat(fakeChrome.toFile().setExecutable(true)).isTrue();

        ChromeOptions options = new ChromeOptions();
        options.setBinaryLocation(fakeChrome.toString());
        options.setProxyConfig(new ProxyConfig("127.0.0.1", 18080, ProxyConfig.ProxyType.HTTP));
        options.setEgressResolver(proxy -> {
            throw new IllegalStateException("resolver unavailable");
        });
        Map<String, String> environment = new HashMap<>(options.getEnv());
        environment.put("JDL_EGRESS_FAILURE_MARKER", marker.toString());
        options.setEnv(environment);
        Chrome chrome = new Chrome(options, 0.25f, false, 1 << 20);

        assertThatThrownBy(() -> chrome.startSession().join())
                .hasRootCauseMessage("resolver unavailable")
                .hasStackTraceContaining("refusing an unverified egress");
        assertThat(marker).doesNotExist();
        assertThat(chrome.process).isNull();
    }

    @Test
    void minimalEgressLocaleIsResolvedBeforeChromeProcessArguments() throws Exception {
        Path capturedArguments = testDir.resolve("minimal-egress-arguments.txt");
        Path fakeChrome = testDir.resolve("minimal-egress-chrome.sh");
        Files.writeString(fakeChrome, """
                #!/bin/sh
                if [ "$1" = "--version" ]; then
                  printf 'Google Chrome 150.0.7871.114\n'
                  exit 0
                fi
                printf '%s\n' "$@" > "$JDL_EGRESS_ARGS_FILE"
                exit 1
                """);
        assertThat(fakeChrome.toFile().setExecutable(true)).isTrue();

        BrowserFingerprintProfile profile = minimalNativeProfile();

        EgressInfo egress = new EgressInfo();
        egress.countryCode = "BR";
        egress.timezone = "America/Sao_Paulo";

        ChromeOptions options = new ChromeOptions();
        options.setBinaryLocation(fakeChrome.toString());
        options.setFingerprintProfile(profile);
        options.setEgressInfo(egress);
        options.addArgument("--remote-debugging-port=1");
        Map<String, String> environment = new HashMap<>(options.getEnv());
        environment.put("JDL_EGRESS_ARGS_FILE", capturedArguments.toString());
        options.setEnv(environment);
        Chrome chrome = new Chrome(options, 0.25f, false, 1 << 20);

        assertThatThrownBy(() -> chrome.startSession().join())
                .hasRootCauseInstanceOf(RuntimeException.class);

        assertThat(capturedArguments).exists();
        assertThat(Files.readAllLines(capturedArguments))
                .contains(
                        "--lang=pt-BR",
                        "--accept-lang=pt-BR,pt,en-US,en",
                        "--webrtc-ip-handling-policy=disable_non_proxied_udp")
                .doesNotContain("--lang=en-US", "--accept-lang=en-US,en");
    }

    @Test
    void minimalRetryWithoutEgressDoesNotReusePreviousEgressLaunchState() throws Exception {
        Path capturedArguments = testDir.resolve("minimal-egress-retry-arguments.txt");
        Path firstAttemptPreferences = testDir.resolve("minimal-egress-first-preferences.json");
        Path fakeChrome = testDir.resolve("minimal-egress-retry-chrome.sh");
        Files.writeString(fakeChrome, """
                #!/bin/sh
                if [ "$1" = "--version" ]; then
                  printf 'Google Chrome 150.0.7871.114\n'
                  exit 0
                fi
                printf '%s\n' '---attempt---' >> "$JDL_EGRESS_RETRY_ARGS_FILE"
                printf '%s\n' "$@" >> "$JDL_EGRESS_RETRY_ARGS_FILE"
                if [ ! -e "$JDL_EGRESS_RETRY_PREFS_FILE" ]; then
                  for arg do
                    case "$arg" in
                      --user-data-dir=*) profile_dir=${arg#--user-data-dir=} ;;
                    esac
                  done
                  cp "$profile_dir/Default/Preferences" "$JDL_EGRESS_RETRY_PREFS_FILE"
                fi
                exit 1
                """);
        assertThat(fakeChrome.toFile().setExecutable(true)).isTrue();

        EgressInfo egress = new EgressInfo();
        egress.countryCode = "BR";
        egress.timezone = "America/Sao_Paulo";

        Path profileDir = testDir.resolve("minimal-egress-retry-profile");
        Path preferences = profileDir.resolve("Default/Preferences");
        Files.createDirectories(preferences.getParent());
        Map<String, Object> originalWebRtc = Map.of(
                "custom_preserved", "caller-value",
                "ip_handling_policy", "default_public_interface_only",
                "multiple_routes_enabled", true,
                "nonproxied_udp_enabled", true);
        new ObjectMapper().writeValue(preferences.toFile(), Map.of(
                "webrtc", originalWebRtc,
                "profile", Map.of("caller_preserved", true)));

        ChromeOptions options = new ChromeOptions();
        options.setBinaryLocation(fakeChrome.toString());
        options.setFingerprintProfile(minimalNativeProfile());
        options.setEgressInfo(egress);
        options.setUserDataDir(profileDir.toString());
        options.setAutoCleanDirs(false);
        options.addArgument("--remote-debugging-port=1");
        Map<String, String> environment = new HashMap<>(options.getEnv());
        environment.put("JDL_EGRESS_RETRY_ARGS_FILE", capturedArguments.toString());
        environment.put("JDL_EGRESS_RETRY_PREFS_FILE", firstAttemptPreferences.toString());
        options.setEnv(environment);
        Chrome chrome = new Chrome(options, 0.25f, false, 1 << 20);

        assertThatThrownBy(() -> chrome.startSession().join())
                .hasRootCauseInstanceOf(RuntimeException.class);
        @SuppressWarnings("unchecked")
        Map<String, Object> firstPersistedPrefs = new ObjectMapper().readValue(
                Files.readString(firstAttemptPreferences), Map.class);
        @SuppressWarnings("unchecked")
        Map<String, Object> firstWebRtc = (Map<String, Object>) firstPersistedPrefs.get("webrtc");
        assertThat(firstWebRtc)
                .containsEntry("custom_preserved", "caller-value")
                .containsEntry("ip_handling_policy", "disable_non_proxied_udp")
                .containsEntry("multiple_routes_enabled", false)
                .containsEntry("nonproxied_udp_enabled", false);
        @SuppressWarnings("unchecked")
        Map<String, Object> restoredAfterFailure = new ObjectMapper().readValue(
                Files.readString(preferences), Map.class);
        assertThat(restoredAfterFailure.get("webrtc")).isEqualTo(originalWebRtc);
        options.setEgressInfo(null);
        // Sem proxy/egress o idioma vem do host; fixa o host em en-US para isolar o vazamento do egress anterior.
        java.util.Locale hostLocale = java.util.Locale.getDefault();
        java.util.Locale.setDefault(java.util.Locale.US);
        try {
            assertThatThrownBy(() -> chrome.startSession().join())
                    .hasRootCauseInstanceOf(RuntimeException.class);
        } finally {
            java.util.Locale.setDefault(hostLocale);
        }

        String[] attempts = Files.readString(capturedArguments).split("---attempt---\\R");
        assertThat(attempts).hasSize(3);
        assertThat(attempts[1])
                .contains("--lang=pt-BR", "--accept-lang=pt-BR,pt,en-US,en",
                        "--webrtc-ip-handling-policy=disable_non_proxied_udp");
        assertThat(attempts[2])
                .contains("--lang=en-US", "--accept-lang=en-US,en")
                .doesNotContain("--lang=pt-BR", "--accept-lang=pt-BR,pt,en-US,en",
                        "--webrtc-ip-handling-policy=disable_non_proxied_udp");
        @SuppressWarnings("unchecked")
        Map<String, Object> persistedPrefs = new ObjectMapper().readValue(
                Files.readString(preferences), Map.class);
        assertThat(persistedPrefs)
                .containsEntry("profile", Map.of(
                        "caller_preserved", true,
                        "password_manager_enabled", false));
        assertThat(persistedPrefs.get("webrtc")).isEqualTo(originalWebRtc);
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void retryAfterResolvingEphemeralPortReadsTheNewProcessPort(boolean explicitlyConfigured) throws Exception {
        Path countFile = testDir.resolve("ephemeral-port-attempt-count.txt");
        Path fakeChrome = testDir.resolve("ephemeral-port-retry-chrome.sh");
        Path profileDir = testDir.resolve("ephemeral-port-retry-profile");
        Files.writeString(fakeChrome, """
                #!/bin/sh
                if [ "$1" = "--version" ]; then
                  printf 'Google Chrome 150.0.7871.114\\n'
                  exit 0
                fi
                count=0
                if [ -f "$JDL_EPHEMERAL_COUNT_FILE" ]; then
                  count=$(cat "$JDL_EPHEMERAL_COUNT_FILE")
                fi
                count=$((count + 1))
                printf '%s' "$count" > "$JDL_EPHEMERAL_COUNT_FILE"
                for arg do
                  case "$arg" in
                    --user-data-dir=*) profile_dir=${arg#--user-data-dir=} ;;
                  esac
                done
                if [ "$count" -eq 1 ]; then port=39101; else port=39102; fi
                printf '%s\\n/devtools/browser/test\\n' "$port" > "$profile_dir/DevToolsActivePort"
                sleep 60
                """);
        assertThat(fakeChrome.toFile().setExecutable(true)).isTrue();

        ChromeOptions options = new ChromeOptions();
        // Retry de porta efêmera é TCP; o padrão agora é PIPE.
        options.setCdpTransport(CdpTransport.TCP);
        if (explicitlyConfigured) {
            options.addArgument("--remote-debugging-port=0");
        }
        options.setBinaryLocation(fakeChrome.toString());
        options.setUserDataDir(profileDir.toString());
        options.setAutoCleanDirs(false);
        Map<String, String> environment = new HashMap<>(options.getEnv());
        environment.put("JDL_EPHEMERAL_COUNT_FILE", countFile.toString());
        options.setEnv(environment);
        Chrome chrome = new Chrome(options, 0.25f, false, 1 << 20);
        DevToolsActivePortWaiter.ListenerPids originalListenerPids = DevToolsActivePortWaiter.listenerPids;
        // O Chrome falso não abre socket: confirma via PIDs que a porta pertence ao processo filho.
        DevToolsActivePortWaiter.listenerPids = port -> ProcessHandle.current().descendants()
                .map(ProcessHandle::pid).collect(Collectors.toSet());
        DevToolsActivePortWaiter.procRoot = testDir; // simula FS sem /proc/net/tcp

        try {
            assertThatThrownBy(() -> chrome.startSession().join());
            String firstPortAddress = options.getDebuggerAddress();
            assertThat(firstPortAddress).isEqualTo("127.0.0.1:39101");

            assertThatThrownBy(() -> chrome.startSession().join());

            assertThat(Files.readString(countFile)).isEqualTo("2");
            assertThat(chrome.host).isEqualTo("127.0.0.1:39102");
        } finally {
            DevToolsActivePortWaiter.listenerPids = originalListenerPids;
            DevToolsActivePortWaiter.procRoot = java.nio.file.Paths.get("/proc");
            forceCleanup(chrome.process);
            deleteDirectory(profileDir);
        }
    }

}
