package io.github.selenium.javaDriverless.types;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import com.fasterxml.jackson.databind.node.NullNode;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import io.github.selenium.javaDriverless.AuthenticatedProxyBridge;
import io.github.selenium.javaDriverless.Chrome;

/**
 * Operações do Context contra Target/BaseTarget mockados: permissões, downloads,
 * nova janela, cookies de contexto, ponte de proxy e quit completo.
 */
class ContextOperationsTest {

    private Target currentTarget;
    private Chrome driver;
    private BaseTarget base;
    private Context context;

    @BeforeEach
    void setUp() {
        currentTarget = mock(Target.class);
        driver = mock(Chrome.class);
        base = mock(BaseTarget.class);
        when(driver.getBaseTarget()).thenReturn(CompletableFuture.completedFuture(base));
        when(base.executeCdpCmd(anyString(), anyMap(), any()))
            .thenReturn(CompletableFuture.completedFuture(NullNode.getInstance()));
        context = new Context(currentTarget, driver, "ctx-7", false, 10 * 1024 * 1024);
    }

    @Test
    void setPermissionsAndDownloadBehaviorCarryTheContextId() throws Exception {
        context.setPermissions("https://app.test/", List.of("geolocation"))
            .get(5, TimeUnit.SECONDS);
        verify(base).executeCdpCmd(eq("Browser.grantPermissions"), argThat(args ->
            "https://app.test/".equals(args.get("origin"))
                && "ctx-7".equals(args.get("browserContextId"))), isNull());

        context.setDownloadBehaviour("allow", "/tmp/dl").get(5, TimeUnit.SECONDS);
        verify(base).executeCdpCmd(eq("Browser.setDownloadBehavior"), argThat(args ->
            "allow".equals(args.get("behavior"))
                && "/tmp/dl".equals(args.get("downloadPath"))
                && "ctx-7".equals(args.get("browserContextId"))), isNull());

        // path nulo: a chave não vai no comando
        context.setDownloadBehaviour("default", null).get(5, TimeUnit.SECONDS);
        verify(base).executeCdpCmd(eq("Browser.setDownloadBehavior"), argThat(args ->
            !args.containsKey("downloadPath")), isNull());
    }

    @Test
    void newWindowRegistersTheCreatedTargetInTheContext() throws Exception {
        when(base.executeCdpCmd(eq("Target.createTarget"), anyMap(), any()))
            .thenReturn(CompletableFuture.completedFuture(
                com.fasterxml.jackson.databind.node.JsonNodeFactory.instance
                    .objectNode().put("targetId", "novo-1")));

        Target criada = context.newWindow("window").get(5, TimeUnit.SECONDS);

        assertEquals("novo-1", criada.getId());
        assertEquals(criada, context.getTarget("novo-1").get(5, TimeUnit.SECONDS));
        assertTrue(context.getTargets().get(5, TimeUnit.SECONDS).contains(criada));
        assertTrue(context.getWindowHandles().get(5, TimeUnit.SECONDS).contains("novo-1"));
    }

    @Test
    void getTargetUnknownIdFailsWithClearMessage() {
        Exception thrown = assertThrows(Exception.class,
            () -> context.getTarget("fantasma").get(5, TimeUnit.SECONDS));
        assertTrue(thrown.getCause().getMessage().contains("Target não encontrado: fantasma"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void getAllCookiesFlattensNestedValuesAndToleratesMissingCookieList() throws Exception {
        com.fasterxml.jackson.databind.node.ObjectNode comCookies =
            com.fasterxml.jackson.databind.node.JsonNodeFactory.instance.objectNode()
                .set("cookies", com.fasterxml.jackson.databind.node.JsonNodeFactory.instance
                    .arrayNode()
                    .add(com.fasterxml.jackson.databind.node.JsonNodeFactory.instance
                        .objectNode().put("name", "sessao").put("value", "abc")
                        .put("httpOnly", true).put("expires", 1800))
                    .add(com.fasterxml.jackson.databind.node.JsonNodeFactory.instance
                        .objectNode().set("blob",
                            com.fasterxml.jackson.databind.node.JsonNodeFactory.instance
                                .objectNode().put("x", 1))));
        com.fasterxml.jackson.databind.node.ObjectNode semCookies =
            com.fasterxml.jackson.databind.node.JsonNodeFactory.instance.objectNode();
        when(base.executeCdpCmd(eq("Storage.getCookies"), anyMap(), any()))
            .thenReturn(CompletableFuture.completedFuture(comCookies))
            .thenReturn(CompletableFuture.completedFuture(semCookies));

        List<Map<String, Object>> cookies = context.getAllCookies().get(5, TimeUnit.SECONDS);
        assertEquals(2, cookies.size());
        assertEquals("abc", cookies.get(0).get("value"));
        // tipos JSON são preservados: booleano vira Boolean, número vira Long/Double
        assertEquals(Boolean.TRUE, cookies.get(0).get("httpOnly"));
        assertEquals(1800L, cookies.get(0).get("expires"));
        // valor não-escalar vira texto JSON, não explode
        assertEquals("{\"x\":1}", cookies.get(1).get("blob"));

        // resposta sem "cookies": lista vazia
        assertTrue(context.getAllCookies().get(5, TimeUnit.SECONDS).isEmpty());
    }

    @Test
    void addCookieRejectsSameSiteForaDoVocabulario() {
        CompletableFuture<Void> futuro = context.addCookie(
            Map.of("name", "x", "sameSite", "Sempre"));

        Exception thrown = assertThrows(Exception.class, () -> futuro.get(5, TimeUnit.SECONDS));
        assertTrue(thrown.getCause().getMessage().contains("sameSite"));
        verify(base, never()).executeCdpCmd(eq("Storage.setCookies"), anyMap(), any());
    }

    @Test
    void closeProxyBridgeNullsItOutAndSurvivesExplosiveCloses() {
        AuthenticatedProxyBridge boa = mock(AuthenticatedProxyBridge.class);
        AuthenticatedProxyBridge explosiva = mock(AuthenticatedProxyBridge.class);
        org.mockito.Mockito.doThrow(new IllegalStateException("já fechada"))
            .when(explosiva).close();

        context.setProxyBridge(boa);
        assertSame(boa, context.getProxyBridge());
        context.closeProxyBridge();
        verify(boa).close();

        context.setProxyBridge(explosiva);
        context.closeProxyBridge(); // não pode propagar
        verify(explosiva).close();

        assertNull(context.getProxyBridge());
    }

    @Test
    void quitClosesTargetsDisposesContextAndIsIdempotent() throws Exception {
        when(base.executeCdpCmd(eq("Target.createTarget"), anyMap(), any()))
            .thenReturn(CompletableFuture.completedFuture(
                com.fasterxml.jackson.databind.node.JsonNodeFactory.instance
                    .objectNode().put("targetId", "alvo-quit")));
        context.newWindow("tab").get(5, TimeUnit.SECONDS);

        context.quit().get(5, TimeUnit.SECONDS);

        verify(base).executeCdpCmd(eq("Target.disposeBrowserContext"), argThat(args ->
            "ctx-7".equals(args.get("browserContextId"))), isNull());

        // segundo quit é idempotente: nada é reexecutado
        context.quit().get(5, TimeUnit.SECONDS);
        verify(base, org.mockito.Mockito.times(1)).executeCdpCmd(
            eq("Target.disposeBrowserContext"), anyMap(), any());
    }

    @Test
    void pageCaptureNetworkAndCastingDelegatesPassThrough() throws Exception {
        when(currentTarget.getScreenshotAsPng())
            .thenReturn(CompletableFuture.completedFuture(new byte[]{9}));
        when(currentTarget.getScreenshotAsFile(eq("/tmp/a.png")))
            .thenReturn(CompletableFuture.completedFuture(null));
        when(currentTarget.saveScreenshot(eq("/tmp/b.png")))
            .thenReturn(CompletableFuture.completedFuture(null));
        when(currentTarget.snapshot()).thenReturn(CompletableFuture.completedFuture("<mhtml/>"));
        when(currentTarget.saveSnapshot(eq("/tmp/s.mhtml")))
            .thenReturn(CompletableFuture.completedFuture(null));
        when(currentTarget.setNetworkConditions(eq(true), eq(10), eq(20), eq(30), eq("offline")))
            .thenReturn(CompletableFuture.completedFuture(null));
        when(currentTarget.sendKeys(eq("txt")))
            .thenReturn(CompletableFuture.completedFuture(null));
        when(currentTarget.waitForCdp(eq("Ev.X"), any()))
            .thenReturn(CompletableFuture.completedFuture(NullNode.getInstance()));
        when(currentTarget.executeCdpCmd(eq("Cmd.Y"), anyMap(), any()))
            .thenReturn(CompletableFuture.completedFuture(NullNode.getInstance()));
        when(currentTarget.setSinkToUse(eq("sink")))
            .thenReturn(CompletableFuture.completedFuture(Map.of("ok", true)));
        when(currentTarget.startDesktopMirroring(eq("sink")))
            .thenReturn(CompletableFuture.completedFuture(Map.of()));
        when(currentTarget.startTabMirroring(eq("sink")))
            .thenReturn(CompletableFuture.completedFuture(Map.of()));
        when(currentTarget.stopCasting(eq("sink")))
            .thenReturn(CompletableFuture.completedFuture(Map.of()));
        when(currentTarget.getInfo()).thenReturn(CompletableFuture.completedFuture(
            Map.of("url", "https://ctx.test/")));

        assertNotNull(context.getScreenshotAsPng().get(5, TimeUnit.SECONDS));
        context.getScreenshotAsFile("/tmp/a.png").get(5, TimeUnit.SECONDS);
        context.saveScreenshot("/tmp/b.png").get(5, TimeUnit.SECONDS);
        assertEquals("<mhtml/>", context.snapshot().get(5, TimeUnit.SECONDS));
        context.saveSnapshot("/tmp/s.mhtml").get(5, TimeUnit.SECONDS);
        context.setNetworkConditions(true, 10, 20, 30, "offline").get(5, TimeUnit.SECONDS);
        context.sendKeys("txt").get(5, TimeUnit.SECONDS);
        assertNotNull(context.waitForCdp("Ev.X", 5f).get(5, TimeUnit.SECONDS));
        assertNotNull(context.executeCdpCmd("Cmd.Y", Map.of(), 5f).get(5, TimeUnit.SECONDS));
        assertEquals(Map.of("ok", true), context.setSinkToUse("sink").get(5, TimeUnit.SECONDS));
        context.startDesktopMirroring("sink").get(5, TimeUnit.SECONDS);
        context.startTabMirroring("sink").get(5, TimeUnit.SECONDS);
        context.stopCasting("sink").get(5, TimeUnit.SECONDS);
        assertEquals("https://ctx.test/",
            context.getCurrentTargetInfo().get(5, TimeUnit.SECONDS).get("url"));
        // sem diretório de downloads cacheado: nulo e não explode
        assertNull(context.getDownloadsDir().get(5, TimeUnit.SECONDS));
    }

    @Test
    void removeTargetClosesAndUnregistersIt() throws Exception {
        when(base.executeCdpCmd(eq("Target.createTarget"), anyMap(), any()))
            .thenReturn(CompletableFuture.completedFuture(
                com.fasterxml.jackson.databind.node.JsonNodeFactory.instance
                    .objectNode().put("targetId", "alvo-fora")));
        Target criada = context.newWindow("tab").get(5, TimeUnit.SECONDS);
        when(criada.close()).thenReturn(CompletableFuture.completedFuture(null));

        context.removeTarget(criada).get(5, TimeUnit.SECONDS);

        assertFalse(context.getTargets().get(5, TimeUnit.SECONDS).contains(criada));
    }

    @Test
    void sleepCompletesWithoutCdpTraffic() throws Exception {
        context.sleep(0.0).get(5, TimeUnit.SECONDS);
    }
}
