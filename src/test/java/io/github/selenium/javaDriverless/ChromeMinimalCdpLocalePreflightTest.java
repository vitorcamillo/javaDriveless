package io.github.selenium.javaDriverless;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.NullNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.selenium.javaDriverless.types.BrowserFingerprintProfile;
import io.github.selenium.javaDriverless.types.ChromeOptions;
import io.github.selenium.javaDriverless.types.Target;
import io.github.selenium.javaDriverless.utils.UserAgentResolver;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.concurrent.CompletableFuture;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ChromeMinimalCdpLocalePreflightTest {

    @Test
    void validatesNativeLocaleBeforeOtherOverridesWithoutReplacingIt() {
        BrowserFingerprintProfile profile = BrowserFingerprintProfile.brLinux();
        profile.runtimePolicy = BrowserFingerprintProfile.RUNTIME_POLICY_MINIMAL_CDP;
        profile.connection.mode = "real";
        profile.locale = "de-DE";
        profile.languages = List.of("de-DE", "de", "en-US", "en");
        Target target = mock(Target.class);
        when(target.executeCdpCmd(any(), any(), any()))
                .thenReturn(CompletableFuture.completedFuture(NullNode.getInstance()));
        ObjectNode response = new ObjectMapper().createObjectNode();
        ObjectNode value = response.putObject("result").putObject("value");
        value.put("intlLocale", "de");
        value.put("navigatorLanguage", "de-DE");
        when(target.executeCdpCmd(eq("Runtime.evaluate"), any(), isNull()))
                .thenReturn(CompletableFuture.completedFuture(response));
        ChromeFingerprintSupport support = new ChromeFingerprintSupport(
                new ChromeOptions(), profile, new ObjectMapper(),
                LoggerFactory.getLogger(ChromeMinimalCdpLocalePreflightTest.class),
                () -> UserAgentResolver.buildForProfile(profile));

        support.applyFingerprintProfileCdpOverrides(target).join();

        InOrder order = inOrder(target);
        order.verify(target).executeCdpCmd(eq("Runtime.evaluate"), any(), isNull());
        order.verify(target).executeCdpCmd(eq("Emulation.setTimezoneOverride"), any(), isNull());
        verify(target, never()).executeCdpCmd(
                eq("Emulation.setLocaleOverride"), any(), isNull());
    }
}
