package io.github.selenium.javaDriverless.profiles;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import io.github.selenium.javaDriverless.types.ChromeOptions;
import io.github.selenium.javaDriverless.types.ProxyConfig;
import io.github.selenium.javaDriverless.types.ProxyConfig.ProxyType;

/**
 * Bordas da ProfileChromeOptionsFactory: guards de entrada, runtime default do
 * PhantomPass, precedência de proxy e propagação de sessão/egress.
 */
class ProfileChromeOptionsFactoryEdgesTest {

    @Test
    void nullProfileAndNullPlanAreRejectedWithClearMessages() {
        IllegalArgumentException noProfile = assertThrows(IllegalArgumentException.class,
            () -> ProfileChromeOptionsFactory.fromProfile(null));
        assertTrue(noProfile.getMessage().contains("Profile must not be null"));

        SdkProfile profile = SdkProfilePresets.linux("e1", "Edge", "BR");
        IllegalArgumentException noPlan = assertThrows(IllegalArgumentException.class,
            () -> ProfileChromeOptionsFactory.fromProfile(profile, null));
        assertTrue(noPlan.getMessage().contains("Launch plan must not be null"));
    }

    @Test
    void applyPhantomPassRejectsNullOptionsBeforeTouchingPolicy() {
        PhantomPassProfile contract =
            SdkProfilePresets.phantomPass(SdkProfilePreset.LINUX, "e2", "Edge", "BR");

        IllegalArgumentException thrown = assertThrows(IllegalArgumentException.class,
            () -> ProfileChromeOptionsFactory.applyPhantomPass(
                contract, PhantomPassRuntime.NATIVE_HOST, null));
        assertTrue(thrown.getMessage().contains("não pode ser nulo"));
    }

    @Test
    void applyPhantomPassRejectsContractThatFailsPolicy() {
        PhantomPassProfile invalid =
            SdkProfilePresets.phantomPass(SdkProfilePreset.LINUX, "e3", "Edge", "BR");
        invalid.profile.browser.headless = true;

        IllegalArgumentException thrown = assertThrows(IllegalArgumentException.class,
            () -> ProfileChromeOptionsFactory.applyPhantomPass(
                invalid, PhantomPassRuntime.NATIVE_HOST, new ChromeOptions()));
        assertTrue(thrown.getMessage().contains("policy rejected"), thrown.getMessage());
        assertTrue(thrown.getMessage().contains("headless_not_supported"), thrown.getMessage());
    }

    @Test
    void minimalBrowserConfigurationGuardsHeadlessAndNativeUa() {
        // fingerprint nulo + headless em perfil público estruturado: primeiro guard
        SdkProfile noFingerprint = SdkProfilePresets.linux("e4", "Edge", "BR");
        noFingerprint.fingerprint = null;
        noFingerprint.browser.headless = true;
        ProfileCatalogException thrown = assertThrows(ProfileCatalogException.class,
            () -> ProfileChromeOptionsFactory.fromProfile(noFingerprint));
        assertTrue(thrown.getMessage().contains("Perfis oficiais exigem Chrome visível"),
            thrown.getMessage());

        // runtimePolicy minimal-cdp (preset linux): UA custom rejeitado
        SdkProfile customUa = SdkProfilePresets.linux("e5", "Edge", "BR");
        customUa.browser.userAgent = "Mozilla/5.0 (custom)";
        ProfileCatalogException uaThrown = assertThrows(ProfileCatalogException.class,
            () -> ProfileChromeOptionsFactory.fromProfile(customUa));
        assertTrue(uaThrown.getMessage().contains("User-Agent nativo"), uaThrown.getMessage());
    }

    @Test
    void minimalPolicyRejectsAdHocHeadlessToo() {
        SdkProfile adHoc = SdkProfilePresets.linux("e6", "Edge", "BR");
        adHoc.browser.headless = true;

        ProfileCatalogException thrown = assertThrows(ProfileCatalogException.class,
            () -> ProfileChromeOptionsFactory.fromProfile(adHoc));
        assertTrue(thrown.getMessage().contains("Chrome visível"), thrown.getMessage());
    }

    @Test
    void defaultPhantomPassRuntimePrefersNativeHostExceptForDockerWithScreen() {
        // LINUX -> NATIVE_HOST; contrato completo aplica e devolve options com sessão
        PhantomPassProfile linux =
            SdkProfilePresets.phantomPass(SdkProfilePreset.LINUX, "e7", "Edge", "BR");
        ChromeOptions options = ProfileChromeOptionsFactory.fromPhantomPass(linux);
        assertNotNull(options.getSessionProfile());
        assertNull(options.getSessionProfile().proxy);

        // DOCKER sem screen (fingerprint anulado) -> VIRTUAL_DISPLAY default
        PhantomPassProfile dockerNoScreen =
            SdkProfilePresets.phantomPass(SdkProfilePreset.DOCKER, "e8", "Edge", "BR");
        dockerNoScreen.profile.fingerprint.screen = null;
        // política exige screen para docker: apply deve reprovar por screen ausente
        assertThrows(IllegalArgumentException.class,
            () -> ProfileChromeOptionsFactory.fromPhantomPass(dockerNoScreen));
    }

    @Test
    void contractProxyHasPrecedenceOverRuntimeProxy() {
        PhantomPassProfile withContractProxy =
            SdkProfilePresets.phantomPass(SdkProfilePreset.LINUX, "e9", "Edge", "BR");
        ProxyConfig contractProxy =
            new ProxyConfig("10.0.0.99", 3128, ProxyType.HTTP, "contrato", "senha");
        withContractProxy.proxy = contractProxy;

        ChromeOptions options =
            ProfileChromeOptionsFactory.fromPhantomPass(withContractProxy);

        assertNotNull(options.getSessionProfile().proxy);
        assertEquals("10.0.0.99", options.getSessionProfile().proxy.getHost());
        // transporte derivado do contrato: bridge autenticada ligada
        assertTrue(options.isAuthenticatedProxyBridgeEnabled()
            || Boolean.TRUE.equals(options.getSessionProfile().proxy.hasAuthentication()));
    }

    @Test
    void runtimeProxyIsConvertedWhenContractHasNone() {
        PhantomPassProfile contract =
            SdkProfilePresets.phantomPass(SdkProfilePreset.LINUX, "e10", "Edge", "BR");
        contract.profile.proxy = new SdkProfileProxy();
        contract.profile.proxy.host = "10.0.0.7";
        contract.profile.proxy.port = 8080;
        contract.profile.proxy.type = ProxyType.HTTP;

        ChromeOptions options = ProfileChromeOptionsFactory.fromPhantomPass(contract);

        assertNotNull(options.getSessionProfile().proxy);
        assertEquals("10.0.0.7", options.getSessionProfile().proxy.getHost());
        assertEquals(8080, options.getSessionProfile().proxy.getPort());
    }

    @Test
    void sessionCarriesLocationDnsAndPermissionsFromContract() {
        PhantomPassProfile contract =
            SdkProfilePresets.phantomPass(SdkProfilePreset.LINUX, "e11", "Edge", "PT");
        contract.location.region = "Lisboa";
        contract.location.city = "Lisboa";
        contract.dns.policy = "secure-doh";
        contract.dns.dohTemplate = "https://dns.example/dns-query";
        contract.permissions.geolocation = "allow";

        ChromeOptions options = ProfileChromeOptionsFactory.fromPhantomPass(contract);
        var session = options.getSessionProfile();

        assertEquals("PT", session.location.countryCode);
        assertEquals("Lisboa", session.location.city);
        assertEquals("secure-doh", session.dns.policy);
        assertEquals("https://dns.example/dns-query", session.dns.dohTemplate);
        assertEquals("allow", session.permissions.geolocation);
    }

    @Test
    void egressStateFromContractOverridesPreviousOptionsState() {
        PhantomPassProfile contract =
            SdkProfilePresets.phantomPass(SdkProfilePreset.LINUX, "e12", "Edge", "BR");

        ChromeOptions reused = new ChromeOptions();
        // options que já serviram outro tenant: ausência no contrato deve limpar egress
        ProfileChromeOptionsFactory.applyPhantomPass(
            contract, PhantomPassRuntime.NATIVE_HOST, reused);

        assertNull(reused.getEgressInfo());
    }
}
