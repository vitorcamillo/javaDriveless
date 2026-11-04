package io.github.selenium.javaDriverless;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.selenium.javaDriverless.types.BrowserFingerprintProfile;
import io.github.selenium.javaDriverless.types.ChromeOptions;
import io.github.selenium.javaDriverless.types.Context;
import io.github.selenium.javaDriverless.types.Target;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.nullable;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ChromeNewWindowIsolationTest {

    @Test
    void attachNewWindowDoesNotApplyLibraryFingerprint() {
        Chrome chrome = spy(chrome());
        Context context = mock(Context.class);
        Target target = mock(Target.class);
        when(context.newWindow("tab")).thenReturn(CompletableFuture.completedFuture(target));
        chrome.currentContext = context;
        chrome.isRemote = true;

        chrome.newWindow("tab").join();

        verify(chrome, never()).applyFingerprintProfileToTarget(target);
    }

    @Test
    void explicitLocalNewWindowRegistersPersistentSurfaceBeforeReturning() {
        Chrome chrome = spy(chrome());
        Context context = mock(Context.class);
        Target target = mock(Target.class);
        JsonNode success = new ObjectMapper().createObjectNode();
        when(context.newWindow("tab")).thenReturn(CompletableFuture.completedFuture(target));
        when(target.executeCdpCmd(anyString(), any(), nullable(Float.class)))
                .thenReturn(CompletableFuture.completedFuture(success));
        doReturn(CompletableFuture.completedFuture(null))
                .when(chrome).installWorkerSpoofForRelatedPage(target);
        chrome.currentContext = context;

        Target created = chrome.newWindow("tab").join();

        assertSame(target, created);
        org.mockito.InOrder order = inOrder(target);
        order.verify(target).executeCdpCmd(eq("Page.enable"), any(Map.class), nullable(Float.class));
        order.verify(target).executeCdpCmd(
                eq("Page.addScriptToEvaluateOnNewDocument"), any(Map.class), nullable(Float.class));
        verify(chrome).installWorkerSpoofForRelatedPage(target);
    }

    private static Chrome chrome() {
        ChromeOptions options = new ChromeOptions();
        BrowserFingerprintProfile fingerprint = BrowserFingerprintProfile.brLinux();
        fingerprint.browserVersion = "150.0.0.0";
        options.setFingerprintProfile(fingerprint);
        return new Chrome(options, 0.25f, false, 1 << 20);
    }
}
