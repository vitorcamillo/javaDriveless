package io.github.selenium.javaDriverless.fpmapping;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

class FingerprintMappingIpAllowlistPolicyTest {
    @Test
    void acceptsOnlyMeasuredIpv4AndIpv6EgressAddresses() {
        Map<String, Object> ipv4 = validate("20.168.93.148");
        Map<String, Object> ipv6 = validate("[2603:1030:805:2::32]");

        assertEquals(List.of(), ipv4.get("mismatches"), ipv4.toString());
        assertEquals(List.of(), ipv6.get("mismatches"), ipv6.toString());
    }

    @Test
    void rejectsAnyAddressOutsideTheMeasuredDualStackAllowlist() {
        Map<String, Object> result = validate("203.0.113.77");

        @SuppressWarnings("unchecked")
        List<String> mismatches = (List<String>) result.get("mismatches");
        assertEquals(1, mismatches.size(), result.toString());
    }

    private static Map<String, Object> validate(String actualIp) {
        FingerprintSiteContracts.ExpectedValue value = new FingerprintSiteContracts.ExpectedValue();
        value.key = "dualstack.ip";
        value.expectedKey = "webRtc.allowedIps";
        value.policy = "ipInAllowedSet";
        FingerprintSiteContracts.Site contract = new FingerprintSiteContracts.Site();
        contract.expectedValues = List.of(value);
        return FingerprintMappingExpectations.validateExpectedProfile(
                "dualstack",
                Map.of("ip", actualIp),
                Map.of("webRtc.allowedIps", "20.168.93.148,2603:1030:805:2::32"),
                contract);
    }
}
