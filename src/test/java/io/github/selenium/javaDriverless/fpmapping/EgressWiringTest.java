package io.github.selenium.javaDriverless.fpmapping;

import io.github.selenium.javaDriverless.types.BrowserFingerprintProfile;
import io.github.selenium.javaDriverless.types.ChromeOptions;
import io.github.selenium.javaDriverless.types.EgressCoherence;
import io.github.selenium.javaDriverless.types.EgressInfo;
import io.github.selenium.javaDriverless.types.EgressResolver;
import io.github.selenium.javaDriverless.types.ProxyConfig;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Cobre a fonte plugável do egress (EgressInfo direto / EgressResolver) e sua precedência. */
class EgressWiringTest {

    @Test
    void explicitEgressInfoTakesPrecedenceOverResolver() throws Exception {
        EgressInfo explicit = new EgressInfo();
        EgressResolver throwingResolver = proxy -> {
            throw new IllegalStateException("não deveria ser chamado quando há EgressInfo explícito");
        };
        EgressInfo resolved = EgressCoherence.resolveEgress(explicit, throwingResolver, null);
        assertSame(explicit, resolved);
    }

    @Test
    void resolverIsUsedWhenNoExplicitEgressAndReceivesProxy() throws Exception {
        EgressInfo info = new EgressInfo();
        AtomicReference<ProxyConfig> seen = new AtomicReference<>();
        ProxyConfig proxy = new ProxyConfig();
        EgressResolver resolver = p -> { seen.set(p); return info; };

        EgressInfo resolved = EgressCoherence.resolveEgress(null, resolver, proxy);
        assertSame(info, resolved);
        assertSame(proxy, seen.get());
    }

    @Test
    void nullWhenNeitherSourcePresent() throws Exception {
        assertNull(EgressCoherence.resolveEgress(null, null, null));
    }

    @Test
    void resolverFailurePropagatesSoCallerCanFailSafe() {
        EgressResolver boom = p -> { throw new RuntimeException("lookup falhou"); };
        assertThrows(RuntimeException.class, () -> EgressCoherence.resolveEgress(null, boom, null));
    }

    @Test
    void chromeOptionsCarriesEgressInfoAndResolver() {
        ChromeOptions options = new ChromeOptions();
        assertNull(options.getEgressInfo());
        assertNull(options.getEgressResolver());

        EgressInfo info = new EgressInfo();
        info.timezone = "America/Sao_Paulo";
        options.setEgressInfo(info);
        EgressInfo stored = options.getEgressInfo();
        assertNotSame(info, stored);
        assertEquals("America/Sao_Paulo", stored.timezone);
        info.timezone = "Input/Mutated";
        stored.timezone = "View/Mutated";
        assertEquals("America/Sao_Paulo", options.getEgressInfo().timezone);

        EgressResolver resolver = p -> null;
        options.setEgressResolver(resolver);
        assertSame(resolver, options.getEgressResolver());
    }

    @Test
    void resolvedEgressMakesProfileCoherentWithCountry() throws Exception {
        BrowserFingerprintProfile fp = new BrowserFingerprintProfile();
        EgressInfo br = new EgressInfo();
        br.countryCode = "BR";
        br.timezone = "America/Manaus";
        br.publicIp = "200.150.100.50";

        EgressInfo resolved = EgressCoherence.resolveEgress(null, proxy -> br, null);
        EgressCoherence.apply(fp, resolved);

        assertEquals("America/Manaus", fp.timezoneId);
        assertEquals("pt-BR", fp.locale);
        assertTrue(fp.languages.contains("pt-BR"));
        assertEquals("200.150.100.50", fp.webRtc.publicIp);
        assertTrue(fp.webRtc.disableUdp, "egress coerente força relay-only no WebRTC");
    }
}
