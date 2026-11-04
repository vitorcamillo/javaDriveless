package io.github.selenium.javaDriverless;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

class FingerprintSitePermissionPolicyTest {

    @Test
    void grantsOnlyGeolocationToSitesWhoseContractExecutesThatSurface() {
        assertThat(FingerprintSitePermissionPolicy.requiredPermissions("browserleaks-geo"))
                .containsExactly("geolocation");
        assertThat(FingerprintSitePermissionPolicy.requiredPermissions("iphey"))
                .containsExactly("geolocation");
        assertThat(FingerprintSitePermissionPolicy.requiredPermissions("printleaks"))
                .containsExactly("local-fonts");
        assertThat(FingerprintSitePermissionPolicy.requiredPermissions("pixelscan")).isEmpty();
        assertThat(FingerprintSitePermissionPolicy.requiredPermissions("amiunique")).isEmpty();
    }

    @Test
    void derivesExactOriginWithoutPathQueryOrFragment() {
        assertThat(FingerprintSitePermissionPolicy.originOf(
                "https://browserleaks.com:443/geo?source=test#result"))
                .isEqualTo("https://browserleaks.com:443");
    }

    @Test
    void rejectsNonHttpOriginsAndCredentials() {
        for (String invalid : List.of(
                "file:///tmp/test.html",
                "https://user:password@browserleaks.com/geo",
                "javascript:alert(1)")) {
            assertThatIllegalArgumentException()
                    .isThrownBy(() -> FingerprintSitePermissionPolicy.originOf(invalid));
        }
    }
}
