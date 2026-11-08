package io.github.selenium.javaDriverless.fpmapping;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** canonicalIpSet só canoniza literais de IP; nomes e octetos inválidos não podem disparar DNS. */
class FingerprintMappingExpectationsIpTest {
    @Test
    void keepsValidDottedQuadAddresses() {
        assertEquals(Set.of("1.2.3.4"), FingerprintMappingExpectations.canonicalIpSet("1.2.3.4"));
    }

    @Test
    void canonicalizesIpv6LiteralsWithoutDns() {
        assertEquals(Set.of("0:0:0:0:0:0:0:1"), FingerprintMappingExpectations.canonicalIpSet("::1"));
    }

    @Test
    void acceptsBracketedIpv6MixedWithIpv4() {
        List<String> canonical = List.copyOf(
                FingerprintMappingExpectations.canonicalIpSet("[2001:db8::1], 8.8.8.8"));

        assertEquals(List.of("2001:db8:0:0:0:0:0:1", "8.8.8.8"), canonical);
    }

    @Test
    void keepsBoundaryOctetValueAndRejectsOctetOverflow() {
        assertEquals(Set.of("1.2.3.255"), FingerprintMappingExpectations.canonicalIpSet("1.2.3.255"));
        assertTrue(FingerprintMappingExpectations.canonicalIpSet("256.1.1.1").isEmpty());
    }

    @Test
    void ignoresHostnamesAndMalformedAddresses() {
        for (String value : List.of("cafe.bad.face", "999.1.1.1", "1.2.3", "1.2.3.4.5", "a.b")) {
            assertTrue(FingerprintMappingExpectations.canonicalIpSet(value).isEmpty(),
                    "esperava conjunto vazio para " + value);
        }
    }
}
