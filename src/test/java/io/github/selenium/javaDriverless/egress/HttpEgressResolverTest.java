package io.github.selenium.javaDriverless.egress;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import io.github.selenium.javaDriverless.types.EgressInfo;
import io.github.selenium.javaDriverless.types.ProxyConfig;
import io.github.selenium.javaDriverless.types.ProxyConfig.ProxyType;
import org.junit.jupiter.api.Test;

import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Cobre o parse das respostas dos providers → EgressInfo (parte testável sem rede). */
class HttpEgressResolverTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Test
    void parsesSuccessfulResponse() throws Exception {
        String json = """
                {"status":"success","countryCode":"BR","timezone":"America/Sao_Paulo",
                 "lat":-23.55,"lon":-46.63,"query":"200.1.2.3","mobile":false}""";
        EgressInfo info = HttpEgressResolver.parseIpApi(json, MAPPER);

        assertEquals("BR", info.countryCode);
        assertEquals("America/Sao_Paulo", info.timezone);
        assertEquals(-23.55, info.latitude, 1e-9);
        assertEquals(-46.63, info.longitude, 1e-9);
        assertEquals("200.1.2.3", info.publicIp);
        assertEquals(50_000.0, info.accuracy, 1e-9);
        assertEquals(false, info.mobile);
    }

    @Test
    void detectsMobileEgress() throws Exception {
        String json = """
                {"status":"success","countryCode":"BR","timezone":"America/Sao_Paulo",
                 "lat":-23.5,"lon":-46.6,"query":"100.2.3.4","mobile":true}""";
        EgressInfo info = HttpEgressResolver.parseIpApi(json, MAPPER);
        assertTrue(info.mobile);
    }

    @Test
    void returnsNullOnNonSuccessStatus() throws Exception {
        String json = "{\"status\":\"fail\",\"message\":\"reserved range\",\"query\":\"127.0.0.1\"}";
        assertNull(HttpEgressResolver.parseIpApi(json, MAPPER));
    }

    @Test
    void parsesSuccessfulIpWhoIsResponse() throws Exception {
        String json = """
                {"ip":"8.8.8.8","success":true,"country_code":"US",
                 "latitude":37.33,"longitude":-121.89,
                 "timezone":{"id":"America/Los_Angeles"}}""";
        EgressInfo info = HttpEgressResolver.parseIpWhoIs(json, MAPPER);

        assertEquals("US", info.countryCode);
        assertEquals("America/Los_Angeles", info.timezone);
        assertEquals(37.33, info.latitude, 1e-9);
        assertEquals(-121.89, info.longitude, 1e-9);
        assertEquals("8.8.8.8", info.publicIp);
        assertEquals(false, info.mobile);
        assertEquals(50_000.0, info.accuracy, 1e-9);
    }

    @Test
    void returnsNullForUnsuccessfulIpWhoIsResponse() throws Exception {
        String json = "{\"ip\":\"999.1.1.1\",\"success\":false,\"message\":\"Invalid IP address\"}";
        assertNull(HttpEgressResolver.parseIpWhoIs(json, MAPPER));
    }

    @Test
    void dispatchesBothResponseFormats() throws Exception {
        String ipApiJson = """
                {"status":"success","countryCode":"BR","timezone":"America/Sao_Paulo",
                 "lat":-23.55,"lon":-46.63,"query":"200.1.2.3","mobile":false}""";
        String ipWhoIsJson = """
                {"ip":"8.8.8.8","success":true,"country_code":"US",
                 "timezone":{"id":"America/Los_Angeles"}}""";

        assertEquals("200.1.2.3", HttpEgressResolver.parseResponse(ipApiJson, MAPPER).publicIp);
        assertEquals("8.8.8.8", HttpEgressResolver.parseResponse(ipWhoIsJson, MAPPER).publicIp);
    }

    @Test
    void defaultsToHttpsEndpoint() {
        assertTrue(HttpEgressResolver.DEFAULT_ENDPOINT.startsWith("https://"));
    }

    @Test
    void invalidDirectHostReturnsNullWithoutNetwork() throws Exception {
        // Host inválido nunca vira URI nem abre rede: endpoint mira porta fechada,
        // então qualquer tentativa de conexão estouraria em vez de voltar null.
        String[] invalid = {"evil/path?x=1", "a b", "100%", "[]", "[1.2.3.4]", "[zz::1]", "[::1", "::g",
                "a".repeat(254), "..", "a..b", ".a", "a..", ":::", "[:::]", "[1:2:3:4:5:6:7:8:9]",
                "1:2:3", "[1:2:3]", "999.1.1.1", "256.10.0.1", "1.2.3", "1.2.3.4.5", "1.2.3.4."};
        for (String host : invalid) {
            HttpEgressResolver resolver = new HttpEgressResolver(
                    "http://127.0.0.1:1/%s", Duration.ofMillis(500), false);
            assertNull(resolver.resolve(new ProxyConfig(host, 8080, ProxyType.SOCKS5)),
                    "host inválido deve retornar null: " + host);
        }
    }

    @Test
    void validDirectHostStillBuildsUri() throws Exception {
        // Host válido continua montando a URI: servidor HTTP local devolve IP-geo.
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        try {
            server.createContext("/", exchange -> {
                byte[] body = ("{\"status\":\"success\",\"countryCode\":\"BR\","
                        + "\"timezone\":\"America/Sao_Paulo\",\"query\":\"1.2.3.4\",\"mobile\":false}")
                        .getBytes(StandardCharsets.UTF_8);
                exchange.sendResponseHeaders(200, body.length);
                try (OutputStream out = exchange.getResponseBody()) {
                    out.write(body);
                }
            });
            server.start();
            HttpEgressResolver resolver = new HttpEgressResolver(
                    "http://127.0.0.1:" + server.getAddress().getPort() + "/%s",
                    Duration.ofSeconds(3), false);
            EgressInfo info = resolver.resolve(new ProxyConfig("1.2.3.4", 8080, ProxyType.HTTP));

            assertNotNull(info);
            assertEquals("1.2.3.4", info.publicIp);
        } finally {
            server.stop(0);
        }
    }

    @Test
    void bareIpv6DirectHostStillBuildsUri() throws Exception {
        // IPv6 sem colchetes passa na validação (contém ':' e casa IPV6_CORE).
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        try {
            server.createContext("/", exchange -> {
                byte[] body = ("{\"status\":\"success\",\"countryCode\":\"BR\","
                        + "\"timezone\":\"America/Sao_Paulo\",\"query\":\"1.2.3.4\",\"mobile\":false}")
                        .getBytes(StandardCharsets.UTF_8);
                exchange.sendResponseHeaders(200, body.length);
                try (OutputStream out = exchange.getResponseBody()) {
                    out.write(body);
                }
            });
            server.start();
            for (String host : new String[]{"::1", "2001:db8::1"}) {
                HttpEgressResolver resolver = new HttpEgressResolver(
                        "http://127.0.0.1:" + server.getAddress().getPort() + "/%s",
                        Duration.ofSeconds(3), false);
                assertNotNull(resolver.resolve(new ProxyConfig(host, 8080, ProxyType.SOCKS5)),
                        "IPv6 sem colchetes deve passar na validação: " + host);
            }
        } finally {
            server.stop(0);
        }
    }

    @Test
    void slowAndOversizedBodiesRespectTimeoutAndLimit() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        try {
            server.createContext("/", exchange -> {
                boolean big = exchange.getRequestURI().getPath().contains("big");
                exchange.sendResponseHeaders(200, 0);
                try (OutputStream out = exchange.getResponseBody()) {
                    for (int i = 0; i < 400; i++, out.flush()) {
                        out.write(new byte[big ? 4096 : 1]);
                        if (!big) Thread.sleep(50);
                    }
                } catch (Exception ignored) { /* cliente encerrou */ }
            });
            server.start();
            String base = "http://127.0.0.1:" + server.getAddress().getPort();
            ProxyConfig proxy = new ProxyConfig("1.2.3.4", 8080, ProxyType.HTTP);
            var slow = new HttpEgressResolver(base + "/slow/%s", Duration.ofMillis(800), false);
            assertNull(org.junit.jupiter.api.Assertions.assertTimeout(Duration.ofSeconds(5), () -> slow.resolve(proxy)));
            var big = new HttpEgressResolver(base + "/big/%s", Duration.ofSeconds(3), false);
            org.junit.jupiter.api.Assertions.assertThrows(java.io.IOException.class, () -> big.resolve(proxy));
        } finally {
            server.stop(0);
        }
    }
}
