package io.github.selenium.javaDriverless;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.selenium.javaDriverless.types.BrowserFingerprintProfile;
import io.github.selenium.javaDriverless.types.Target;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ChromeMinimalCdpLocaleTest {

    @Test
    void acceptsNativeCanonicalizationInsideRequestedLanguageFamily() {
        BrowserFingerprintProfile profile = profile("de-DE", "de-DE");
        Target target = targetReturning("de", "de-DE");

        assertEquals("de", ChromeMinimalCdpLocale.captureAndValidate(target, profile));
    }

    @Test
    void rejectsIntlFallbackOutsideRequestedLanguageFamily() {
        BrowserFingerprintProfile profile = profile("zz-ZZ", "zz-ZZ");
        Target target = targetReturning("en-US", "zz-ZZ");

        assertThrows(IllegalStateException.class,
                () -> ChromeMinimalCdpLocale.captureAndValidate(target, profile));
    }

    @Test
    void rejectsNavigatorLanguageDifferentFromPrimaryProfileLanguage() {
        BrowserFingerprintProfile profile = profile("de-DE", "de-DE");
        Target target = targetReturning("de", "en-US");

        assertThrows(IllegalStateException.class,
                () -> ChromeMinimalCdpLocale.captureAndValidate(target, profile));
    }

    private static BrowserFingerprintProfile profile(String locale, String primary) {
        BrowserFingerprintProfile profile = BrowserFingerprintProfile.brLinux();
        profile.locale = locale;
        profile.languages = List.of(primary, primary.substring(0, 2));
        return profile;
    }

    private static Target targetReturning(String intlLocale, String navigatorLanguage) {
        ObjectNode response = new ObjectMapper().createObjectNode();
        ObjectNode value = response.putObject("result").putObject("value");
        value.put("intlLocale", intlLocale);
        value.put("navigatorLanguage", navigatorLanguage);
        Target target = mock(Target.class);
        when(target.executeCdpCmd(eq("Runtime.evaluate"), any(), isNull()))
                .thenReturn(CompletableFuture.completedFuture(response));
        return target;
    }
}
