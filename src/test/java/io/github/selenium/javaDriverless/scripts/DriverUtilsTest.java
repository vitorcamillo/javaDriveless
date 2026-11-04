package io.github.selenium.javaDriverless.scripts;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

import com.fasterxml.jackson.databind.node.NullNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import io.github.selenium.javaDriverless.types.Target;

/** Utilitários de targets/cookies — inclui validação de sameSite na entrada do usuário. */
class DriverUtilsTest {

    private static final com.fasterxml.jackson.databind.ObjectMapper MAPPER =
        new com.fasterxml.jackson.databind.ObjectMapper();

    private Target target;

    @BeforeEach
    void setUp() {
        target = mock(Target.class);
    }

    private static ObjectNode json(String json) {
        try {
            return (ObjectNode) MAPPER.readTree(json);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    @Test
    @SuppressWarnings("unchecked")
    void getCookiesParsesCookieJsonIntoMaps() {
        when(target.executeCdpCmd(eq("Network.getCookies"), isNull(), isNull()))
            .thenReturn(CompletableFuture.completedFuture(json("{\"cookies\":["
                + "{\"name\":\"session\",\"value\":\"abc\",\"secure\":true,\"sameSite\":\"Strict\","
                + "\"expires\":1790000000.5},"
                + "{\"name\":\"theme\",\"value\":\"dark\"}]}")));

        List<Map<String, Object>> cookies = DriverUtils.getCookies(target).join();

        assertEquals(2, cookies.size());
        assertEquals("session", cookies.get(0).get("name"));
        assertEquals("abc", cookies.get(0).get("value"));
        assertEquals(Boolean.TRUE, cookies.get(0).get("secure"));
        assertEquals("Strict", cookies.get(0).get("sameSite"));
        assertEquals("dark", cookies.get(1).get("value"));
    }

    @Test
    void getCookieFindsByNameAndReturnsNullWhenMissing() {
        when(target.executeCdpCmd(eq("Network.getCookies"), isNull(), isNull()))
            .thenReturn(CompletableFuture.completedFuture(json(
                "{\"cookies\":[{\"name\":\"a\",\"value\":\"1\"}]}")));

        assertEquals("1", DriverUtils.getCookie(target, "a").join().get("value"));
        assertNull(DriverUtils.getCookie(target, "zz").join());
    }

    @Test
    void deleteCookieSendsOnlyProvidedScopeArguments() {
        when(target.executeCdpCmd(eq("Network.deleteCookies"), anyMap(), isNull()))
            .thenReturn(CompletableFuture.completedFuture(NullNode.getInstance()));

        DriverUtils.deleteCookie(target, "sess", "https://x.test/", ".x.test", "/app").join();

        verify(target).executeCdpCmd(eq("Network.deleteCookies"), argThat(args ->
            "sess".equals(args.get("name"))
                && "https://x.test/".equals(args.get("url"))
                && ".x.test".equals(args.get("domain"))
                && "/app".equals(args.get("path"))), isNull());

        DriverUtils.deleteCookie(target, "bare", null, null, null).join();
        verify(target).executeCdpCmd(eq("Network.deleteCookies"), argThat(args ->
            "bare".equals(args.get("name"))
                && !args.containsKey("url") && !args.containsKey("domain")
                && !args.containsKey("path")), isNull());
    }

    @Test
    void deleteAllCookiesClearsBrowserCookies() {
        when(target.executeCdpCmd(eq("Network.clearBrowserCookies"), isNull(), isNull()))
            .thenReturn(CompletableFuture.completedFuture(NullNode.getInstance()));

        DriverUtils.deleteAllCookies(target).join();

        verify(target).executeCdpCmd(eq("Network.clearBrowserCookies"), isNull(), isNull());
    }

    @Test
    void addCookieRejectsInvalidSameSiteValue() {
        Map<String, Object> bad = new HashMap<>();
        bad.put("name", "n");
        bad.put("value", "v");
        bad.put("sameSite", "invalid");

        assertThrows(IllegalArgumentException.class,
            () -> DriverUtils.addCookie(target, bad, null));
        assertTrue(assertThrows(IllegalArgumentException.class,
                () -> DriverUtils.addCookie(target, bad, null)).getMessage().contains("sameSite"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void addCookieSendsCookieArrayAndOptionalContext() {
        when(target.executeCdpCmd(eq("Storage.setCookies"), anyMap(), isNull()))
            .thenReturn(CompletableFuture.completedFuture(NullNode.getInstance()));

        Map<String, Object> cookie = new HashMap<>();
        cookie.put("name", "k");
        cookie.put("value", "v");

        DriverUtils.addCookie(target, cookie, null).join();
        verify(target).executeCdpCmd(eq("Storage.setCookies"), argThat(args ->
            List.of(cookie).equals(args.get("cookies"))
                && !args.containsKey("browserContextId")), isNull());

        DriverUtils.addCookie(target, cookie, "ctx-7").join();
        verify(target).executeCdpCmd(eq("Storage.setCookies"), argThat(args ->
            "ctx-7".equals(args.get("browserContextId"))), isNull());
    }

    @Test
    void targetInfoParsesRequiredAndOptionalFields() {
        ObjectNode info = json("{\"targetId\":\"t1\",\"type\":\"page\",\"title\":\"T\",\"url\":\"U\","
            + "\"attached\":true,\"browserContextId\":\"c1\"}");
        Target targetRef = mock(Target.class);

        DriverUtils.TargetInfo parsed = new DriverUtils.TargetInfo(info, targetRef);

        assertEquals("t1", parsed.targetId);
        assertEquals("page", parsed.type);
        assertEquals("T", parsed.title);
        assertEquals("U", parsed.url);
        assertTrue(parsed.attached);
        assertEquals("c1", parsed.browserContextId);
        assertEquals(targetRef, parsed.target);
    }

    @Test
    void targetInfoDefaultsOptionalFields() {
        ObjectNode info = json("{\"targetId\":\"t2\",\"type\":\"iframe\"}");

        DriverUtils.TargetInfo parsed = new DriverUtils.TargetInfo(info, null);

        assertEquals("", parsed.title);
        assertEquals("", parsed.url);
        assertEquals(false, parsed.attached);
        assertNull(parsed.browserContextId);
    }

    @Test
    @SuppressWarnings("unchecked")
    void getTargetsFiltersByTypeAndBrowserContext() {
        java.util.function.Function<Map<String, Object>, CompletableFuture<com.fasterxml.jackson.databind.JsonNode>> cdpExec =
            args -> CompletableFuture.completedFuture(json("{\"targetInfos\":["
                + "{\"targetId\":\"t1\",\"type\":\"page\",\"browserContextId\":\"c1\"},"
                + "{\"targetId\":\"t2\",\"type\":\"iframe\",\"browserContextId\":\"c1\"},"
                + "{\"targetId\":\"t3\",\"type\":\"page\",\"browserContextId\":\"c2\"}]}"));
        java.util.function.Function<String, CompletableFuture<Target>> targetGetter =
            id -> CompletableFuture.completedFuture(mock(Target.class));

        Map<String, DriverUtils.TargetInfo> all =
            DriverUtils.getTargets(cdpExec, targetGetter, null, null, 10).join();
        assertEquals(3, all.size());

        Map<String, DriverUtils.TargetInfo> pages =
            DriverUtils.getTargets(cdpExec, targetGetter, "page", null, 10).join();
        assertEquals(2, pages.size());
        assertNotNull(pages.get("t1"));
        assertNotNull(pages.get("t3"));

        Map<String, DriverUtils.TargetInfo> contextPages =
            DriverUtils.getTargets(cdpExec, targetGetter, "page", "c1", 10).join();
        assertEquals(1, contextPages.size());
        assertNotNull(contextPages.get("t1"));
    }
}
