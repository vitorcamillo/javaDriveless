package io.github.selenium.javaDriverless;

import io.github.selenium.javaDriverless.types.ChromeOptions;
import io.github.selenium.javaDriverless.types.BaseTarget;
import io.github.selenium.javaDriverless.types.BrowserFingerprintProfile;
import io.github.selenium.javaDriverless.types.Target;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CompletableFuture;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.nullable;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ChromeAttachModeTest {
    @TempDir
    Path tempDir;

    @Test
    void debuggerAddressSelectsRemoteAttachMode() {
        ChromeOptions options = new ChromeOptions();
        options.setBinaryLocation("/bin/true");
        options.setDebuggerAddress("127.0.0.1:9222");
        Chrome chrome = new Chrome(options, 0.2f, false, 1 << 20);

        assertThat(options.isRemote()).isTrue();
        assertThat(chrome.resolveAttachModeForStartup(options.getDebuggerAddress())).isTrue();
    }

    @Test
    void localOwnershipRemainsLocalWhenRetrySeesInternallyGeneratedDebuggerAddress() {
        ChromeOptions options = new ChromeOptions();
        options.setBinaryLocation("/bin/true");
        Chrome chrome = new Chrome(options, 0.2f, false, 1 << 20);

        assertThat(chrome.resolveAttachModeForStartup(options.getDebuggerAddress())).isFalse();

        // Simula a mutação interna feita no primeiro launch antes de um retry.
        options.setDebuggerAddress("127.0.0.1:43123");
        assertThat(options.isRemote()).isTrue();
        assertThat(chrome.resolveAttachModeForStartup(options.getDebuggerAddress())).isFalse();
    }

    @Test
    void chromeArgumentLogSummaryNeverIncludesValuesOrPositionalArguments() {
        List<String> flags = ChromeSessionCore.safeChromeArgumentFlags(List.of(
                "--proxy-server=sensitive-value",
                "--user-data-dir=/private/profile",
                "about:blank",
                "--headless"));

        assertThat(flags).containsExactly(
                "--proxy-server", "--user-data-dir", "--headless", "<positional:1>");
        assertThat(String.join(" ", flags))
                .doesNotContain("sensitive-value", "/private/profile", "about:blank");
    }

    @Test
    void attachFailureNeverLaunchesConfiguredLocalBinary() throws Exception {
        Path marker = tempDir.resolve("local-browser-launched");
        Path fakeBrowser = tempDir.resolve("fake-browser.sh");
        Files.writeString(fakeBrowser, "#!/bin/sh\ntouch '" + marker + "'\n");
        assertThat(fakeBrowser.toFile().setExecutable(true)).isTrue();

        ChromeOptions options = new ChromeOptions();
        options.setBinaryLocation(fakeBrowser.toString());
        options.setUserDataDir(tempDir.resolve("profile").toString());
        options.setDebuggerAddress("127.0.0.1:9");

        assertThatThrownBy(() -> Chrome.create(options, 0.2f, false, 1 << 20).join())
                .isInstanceOf(CompletionException.class);
        assertThat(marker).doesNotExist();
        assertThat(tempDir.resolve("profile/Default/Preferences")).doesNotExist();
    }

    @Test
    void minimalCdpAttachFailsBeforeConnectingOrLaunchingAnything() throws Exception {
        Path marker = tempDir.resolve("minimal-attach-local-browser-launched");
        Path fakeBrowser = tempDir.resolve("minimal-attach-fake-browser.sh");
        Files.writeString(fakeBrowser, "#!/bin/sh\ntouch '" + marker + "'\n");
        assertThat(fakeBrowser.toFile().setExecutable(true)).isTrue();

        BrowserFingerprintProfile profile = BrowserFingerprintProfile.nativeDefaults();
        profile.runtimePolicy = BrowserFingerprintProfile.RUNTIME_POLICY_MINIMAL_CDP;
        ChromeOptions options = new ChromeOptions();
        options.setBinaryLocation(fakeBrowser.toString());
        options.setDebuggerAddress("127.0.0.1:9");
        options.setFingerprintProfile(profile);

        assertThatThrownBy(() -> Chrome.create(options, 0.2f, false, 1 << 20).join())
                .isInstanceOf(CompletionException.class)
                .hasRootCauseInstanceOf(IllegalStateException.class)
                .hasStackTraceContaining("minimal-cdp não suporta attach a Chrome externo")
                .hasStackTraceContaining("primeiro statement");
        assertThat(marker).doesNotExist();
    }

    @Test
    void attachQuitDisconnectsWithoutClosingCallerOwnedPage() {
        ChromeOptions options = new ChromeOptions();
        options.setBinaryLocation("/bin/true");
        Chrome chrome = new Chrome(options, 0.2f, false, 1 << 20);
        Target target = mock(Target.class);
        BaseTarget browser = mock(BaseTarget.class);
        when(target.disconnect()).thenReturn(CompletableFuture.completedFuture(null));
        when(browser.close()).thenReturn(CompletableFuture.completedFuture(null));
        chrome.isRemote = true;
        chrome.currentTarget = target;
        chrome.baseTarget = browser;
        chrome.started = true;

        chrome.quit(false).join();

        verify(target).disconnect();
        verify(target, never()).close();
        verify(browser).close();
        assertThat(chrome.started).isFalse();
        assertThat(chrome.currentTarget).isNull();
        assertThat(chrome.baseTarget).isNull();
    }

    @Test
    void attachDoesNotConfigureOrCreateLocalDownloads() {
        Path downloads = tempDir.resolve("caller-owned-downloads");
        ChromeOptions options = new ChromeOptions();
        options.setBinaryLocation("/bin/true");
        options.setDeferredCatalogStorage(null, downloads.toString());
        Chrome chrome = new Chrome(options, 0.2f, false, 1 << 20);
        Target target = mock(Target.class);
        when(target.executeCdpCmd(eq("Browser.setDownloadBehavior"), anyMap(), nullable(Float.class)))
                .thenReturn(CompletableFuture.completedFuture(null));
        chrome.currentTarget = target;

        chrome.configureDownloadBehavior(true);

        verify(target, never()).executeCdpCmd(
                eq("Browser.setDownloadBehavior"), anyMap(), nullable(Float.class));
        assertThat(downloads).doesNotExist();
    }
}
