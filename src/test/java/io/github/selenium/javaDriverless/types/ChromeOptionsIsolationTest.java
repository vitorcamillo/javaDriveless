package io.github.selenium.javaDriverless.types;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNoException;

class ChromeOptionsIsolationTest {

    @Test
    void fingerprintIsCopiedWhenAssignedToDifferentOptions() {
        BrowserFingerprintProfile shared = BrowserFingerprintProfile.brLinux();
        shared.screen.width = 1440;
        shared.fonts.families = java.util.List.of("Original Font");

        ChromeOptions first = new ChromeOptions();
        ChromeOptions second = new ChromeOptions();
        first.setFingerprintProfile(shared);
        second.setFingerprintProfile(shared);

        shared.screen.width = 800;
        first.getFingerprintProfile().screen.width = 1024;
        first.getFingerprintProfile().fonts.families = java.util.List.of("First Font");

        assertThat(second.getFingerprintProfile()).isNotSameAs(shared);
        assertThat(second.getFingerprintProfile().screen).isNotSameAs(shared.screen);
        assertThat(second.getFingerprintProfile().screen.width).isEqualTo(1440);
        assertThat(second.getFingerprintProfile().fonts.families).containsExactly("Original Font");
        assertThatNoException().isThrownBy(second.getFingerprintProfile()::toJsonString);
    }

    @Test
    void proxyIsCopiedWhenAssignedToDifferentOptions() {
        ProxyConfig shared = new ProxyConfig("proxy.internal", 8080, ProxyConfig.ProxyType.HTTP);

        ChromeOptions first = new ChromeOptions();
        ChromeOptions second = new ChromeOptions();
        first.setProxyConfig(shared);
        second.setProxyConfig(shared);

        shared.setHost("mutated.invalid");
        first.getProxyConfig().setPort(9090);

        assertThat(second.getProxyConfig()).isNotSameAs(shared);
        assertThat(second.getProxyConfig().getHost()).isEqualTo("proxy.internal");
        assertThat(second.getProxyConfig().getPort()).isEqualTo(8080);
    }

    @Test
    void retainedMutableReferencesCannotBypassSealedCatalogOptions() {
        BrowserSessionProfile session = new BrowserSessionProfile();
        session.proxy = new ProxyConfig("session.proxy", 7000, ProxyConfig.ProxyType.HTTP);
        session.fingerprint = BrowserFingerprintProfile.brLinux();
        session.fingerprint.locale = "session-stable";
        session.location.city = "Session City";
        session.permissions.extra.put("camera", "deny");

        BrowserFingerprintProfile fingerprint = BrowserFingerprintProfile.brLinux();
        fingerprint.locale = "fingerprint-stable";
        ProxyConfig proxy = new ProxyConfig("proxy.stable", 8080, ProxyConfig.ProxyType.HTTP);
        EgressInfo egress = new EgressInfo();
        egress.timezone = "America/Sao_Paulo";
        Map<String, String> environment = new HashMap<>();
        environment.put("JDL_REFERENCE_GUARD", "stable");

        List<Object> preferenceList = new ArrayList<>();
        preferenceList.add("list-stable");
        preferenceList.add(new LinkedHashMap<>(Map.of("nested", "stable")));
        Set<Object> preferenceSet = new LinkedHashSet<>(List.of("set-stable"));
        Object[] preferenceArray = {
                "array-stable", new LinkedHashMap<>(Map.of("nested", "stable"))
        };
        Map<String, Object> preferenceInput = new LinkedHashMap<>();
        preferenceInput.put("list", preferenceList);
        preferenceInput.put("set", preferenceSet);
        preferenceInput.put("array", preferenceArray);
        Map<String, Object> experimentalInput = new LinkedHashMap<>();
        experimentalInput.put("nested", new ArrayList<>(List.of("experimental-stable")));

        ChromeOptions options = new ChromeOptions();
        options.setSessionProfile(session);
        options.setFingerprintProfile(fingerprint);
        options.setProxyConfig(proxy);
        options.setEgressInfo(egress);
        options.setEnv(environment);
        options.updatePref("reference_guard.payload", preferenceInput);
        options.addExperimentalOption(
                "prefs", Map.of("reference_guard.experimental", experimentalInput));

        BrowserSessionProfile sessionView = options.getSessionProfile();
        BrowserFingerprintProfile fingerprintView = options.getFingerprintProfile();
        ProxyConfig proxyView = options.getProxyConfig();
        EgressInfo egressView = options.getEgressInfo();
        Map<String, String> environmentView = options.getEnv();
        Map<String, Object> prefsView = options.getPrefs();

        options.bindCatalogLaunchValidator(ignored -> () -> { });
        try (var ignored = options.acquireCatalogLaunchLease()) {
            options.sealCatalogLaunchOptions();

            session.proxy.setHost("input-mutated.invalid");
            session.fingerprint.locale = "input-mutated";
            session.location.city = "Input Mutated";
            session.permissions.extra.put("camera", "allow");
            sessionView.proxy.setHost("view-mutated.invalid");
            sessionView.fingerprint.locale = "view-mutated";
            sessionView.location.city = "View Mutated";
            sessionView.permissions.extra.put("camera", "allow");
            fingerprint.locale = "input-mutated";
            fingerprintView.locale = "view-mutated";
            proxy.setHost("input-mutated.invalid");
            proxyView.setHost("view-mutated.invalid");
            egress.timezone = "Input/Mutated";
            egressView.timezone = "View/Mutated";
            environment.put("JDL_REFERENCE_GUARD", "input-mutated");
            environmentView.put("JDL_REFERENCE_GUARD", "view-mutated");
            preferenceList.set(0, "input-mutated");
            preferenceSet.add("input-mutated");
            ((Map<String, Object>) preferenceArray[1]).put("nested", "input-mutated");
            experimentalInput.put("nested", new ArrayList<>(List.of("input-mutated")));

            Map<String, Object> guardView = nestedMap(prefsView, "reference_guard");
            Map<String, Object> payloadView = nestedMap(guardView, "payload");
            ((List<Object>) payloadView.get("list")).set(0, "view-mutated");
            ((Set<Object>) payloadView.get("set")).add("view-mutated");
            ((Map<String, Object>) ((Object[]) payloadView.get("array"))[1])
                    .put("nested", "view-mutated");
            ((List<Object>) nestedMap(guardView, "experimental").get("nested"))
                    .set(0, "view-mutated");

            assertThat(options.getFingerprintProfile().locale).isEqualTo("fingerprint-stable");
            assertThat(options.getProxyConfig().getHost()).isEqualTo("proxy.stable");
            assertThat(options.getEgressInfo().timezone).isEqualTo("America/Sao_Paulo");
            assertThat(options.getSessionProfile().proxy.getHost()).isEqualTo("session.proxy");
            assertThat(options.getSessionProfile().fingerprint.locale).isEqualTo("session-stable");
            assertThat(options.getSessionProfile().location.city).isEqualTo("Session City");
            assertThat(options.getSessionProfile().permissions.extra).containsEntry("camera", "deny");
            assertThat(options.getEnv()).containsEntry("JDL_REFERENCE_GUARD", "stable");

            Map<String, Object> storedGuard = nestedMap(options.getPrefs(), "reference_guard");
            Map<String, Object> storedPayload = nestedMap(storedGuard, "payload");
            assertThat((List<?>) storedPayload.get("list")).first().isEqualTo("list-stable");
            assertThat(storedPayload.get("set")).isEqualTo(Set.of("set-stable"));
            assertThat((Object[]) storedPayload.get("array")).containsExactly(
                    "array-stable", Map.of("nested", "stable"));
            assertThat(nestedMap(storedGuard, "experimental").get("nested"))
                    .isEqualTo(List.of("experimental-stable"));
        }
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> nestedMap(Map<String, Object> parent, String key) {
        return (Map<String, Object>) parent.get(key);
    }
}
