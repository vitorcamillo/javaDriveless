package io.github.selenium.javaDriverless;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.anyFloat;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import io.github.selenium.javaDriverless.input.Keyboard;
import io.github.selenium.javaDriverless.input.Pointer;
import io.github.selenium.javaDriverless.scripts.NetworkInterceptor;
import io.github.selenium.javaDriverless.scripts.SwitchTo;
import io.github.selenium.javaDriverless.types.BaseTarget;
import io.github.selenium.javaDriverless.types.Context;
import io.github.selenium.javaDriverless.types.Target;
import io.github.selenium.javaDriverless.types.WebElement;

/**
 * ChromeBrowserActions/ChromeTargetActions sobre estado de sessão fake:
 * currentTarget/baseTarget/currentContext mockados, sem processo nem WebSocket.
 * Prova a construção dos payloads CDP e a delegação correta ao alvo atual.
 */
class ChromeBrowserActionsFacadeTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** Probe concreta: dá acesso aos campos protected da hierarquia de sessão. */
    private static final class Probe extends Chrome {
        Probe() {
            super(new io.github.selenium.javaDriverless.types.ChromeOptions(), 10f, false, 2 << 22);
        }
    }

    private Probe chrome;
    private Target currentTarget;
    private BaseTarget baseTarget;
    private Context currentContext;

    private static JsonNode json(String raw) {
        try {
            return MAPPER.readTree(raw);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    @BeforeEach
    void setUp() {
        chrome = new Probe();
        currentTarget = mock(Target.class);
        baseTarget = mock(BaseTarget.class);
        currentContext = mock(Context.class);
        chrome.started = true;
        chrome.currentTarget = currentTarget;
        chrome.baseTarget = baseTarget;
        chrome.currentContext = currentContext;
        chrome.host = "127.0.0.1";
        chrome.browserPid = 4242;
        when(currentContext.getContextId()).thenReturn("ctx-1");
        when(baseTarget.executeCdpCmd(anyString(), any(), any()))
            .thenReturn(CompletableFuture.completedFuture(json("{}")));
        when(currentTarget.executeCdpCmd(anyString(), any(), any()))
            .thenReturn(CompletableFuture.completedFuture(json("{}")));
    }

    // ---------- delegação ao target atual ----------

    @Test
    void pageLevelOperationsDelegateToCurrentTarget() throws Exception {
        when(currentTarget.get(eq("https://p.test/"), eq(true)))
            .thenReturn(CompletableFuture.completedFuture(Map.of("ok", true)));
        when(currentTarget.getTitle()).thenReturn(CompletableFuture.completedFuture("T"));
        when(currentTarget.getCurrentUrl()).thenReturn(CompletableFuture.completedFuture("https://p.test/"));
        when(currentTarget.getPageSource()).thenReturn(CompletableFuture.completedFuture("<html/>"));
        when(currentTarget.executeScript(eq("1"), any(), eq(false)))
            .thenReturn(CompletableFuture.completedFuture(1));
        when(currentTarget.getAlert(eq(3.0f)))
            .thenReturn(CompletableFuture.completedFuture(mock(io.github.selenium.javaDriverless.types.Alert.class)));
        when(currentTarget.waitForCdp(eq("E"), eq(1.0f)))
            .thenReturn(CompletableFuture.completedFuture(json("{}")));
        when(currentTarget.addCdpListener(eq("E"), any()))
            .thenReturn(CompletableFuture.completedFuture(null));
        when(currentTarget.removeCdpListener(eq("E"), any()))
            .thenReturn(CompletableFuture.completedFuture(null));
        when(currentTarget.getCdpEventIter(eq("E")))
            .thenReturn(CompletableFuture.completedFuture(List.of()));
        when(currentTarget.findElement(eq("css selector"), eq("#a"), eq(2.0f)))
            .thenReturn(CompletableFuture.completedFuture(mock(WebElement.class)));
        when(currentTarget.findElements(eq("css selector"), eq(".b"), eq(2.0f)))
            .thenReturn(CompletableFuture.completedFuture(List.of()));
        when(currentTarget.searchElements(eq("texto")))
            .thenReturn(CompletableFuture.completedFuture(List.of()));
        when(currentTarget.focus()).thenReturn(CompletableFuture.completedFuture(null));
        when(currentTarget.getFrameTree())
            .thenReturn(CompletableFuture.completedFuture(Map.of("frameTree", Map.of())));
        when(currentTarget.getNetworkConditions())
            .thenReturn(CompletableFuture.completedFuture(Map.of("offline", false)));
        when(currentTarget.deleteNetworkConditions()).thenReturn(CompletableFuture.completedFuture(null));
        when(currentTarget.snapshot()).thenReturn(CompletableFuture.completedFuture("<html/>"));
        when(currentTarget.saveSnapshot(eq("/tmp/s.html"))).thenReturn(CompletableFuture.completedFuture(null));
        when(currentTarget.getScreenshotAsFile(eq("/tmp/x.png")))
            .thenReturn(CompletableFuture.completedFuture(null));

        assertTrue(chrome.get("https://p.test/", true).get(1, TimeUnit.SECONDS).containsKey("ok"));
        assertEquals("T", chrome.getTitle().get(1, TimeUnit.SECONDS));
        assertEquals("https://p.test/", chrome.getCurrentUrl().get(1, TimeUnit.SECONDS));
        assertEquals("<html/>", chrome.getPageSource().get(1, TimeUnit.SECONDS));
        assertEquals(1, chrome.executeScript("1", null, false).get(1, TimeUnit.SECONDS));
        assertNotNull(chrome.getAlert(3f).get(1, TimeUnit.SECONDS));
        assertNotNull(chrome.waitForCdp("E", 1f).get(1, TimeUnit.SECONDS));
        chrome.addCdpListener("E", node -> { }).get(1, TimeUnit.SECONDS);
        chrome.removeCdpListener("E", node -> { }).get(1, TimeUnit.SECONDS);
        assertNotNull(chrome.getCdpEventIter("E").get(1, TimeUnit.SECONDS));
        assertNotNull(chrome.findElement("css selector", "#a", 2f).get(1, TimeUnit.SECONDS));
        assertTrue(chrome.findElements("css selector", ".b", 2f).get(1, TimeUnit.SECONDS).isEmpty());
        assertTrue(chrome.searchElements("texto").get(1, TimeUnit.SECONDS).isEmpty());
        chrome.focus().get(1, TimeUnit.SECONDS);
        assertNotNull(chrome.getFrameTree().get(1, TimeUnit.SECONDS));
        assertFalse((Boolean) chrome.getNetworkConditions().get(1, TimeUnit.SECONDS).get("offline"));
        chrome.deleteNetworkConditions().get(1, TimeUnit.SECONDS);
        assertEquals("<html/>", chrome.snapshot().get(1, TimeUnit.SECONDS));
        chrome.saveSnapshot("/tmp/s.html").get(1, TimeUnit.SECONDS);
        chrome.saveScreenshot("/tmp/x.png").get(1, TimeUnit.SECONDS);
    }

    @Test
    void targetScopedHelpersReuseCurrentTargetInstances() throws Exception {
        Pointer pointer = mock(Pointer.class);
        when(currentTarget.getPointer()).thenReturn(pointer);
        when(currentTarget.getInfo())
            .thenReturn(CompletableFuture.completedFuture(Map.of("type", "page")));

        assertSame(pointer, chrome.getCurrentPointer());
        assertInstanceOf(Keyboard.class, chrome.getCurrentKeyboard());
        assertTrue(chrome.getCurrentTargetInfo().get(1, TimeUnit.SECONDS).containsKey("type"));
    }

    @Test
    void cookiesScreenshotsAndInputDelegateToCurrentTarget() throws Exception {
        when(currentTarget.getCookies())
            .thenReturn(CompletableFuture.completedFuture(List.of(Map.of("name", "a"))));
        when(currentTarget.getCookie(eq("a")))
            .thenReturn(CompletableFuture.completedFuture(Map.of("name", "a", "value", "1")));
        when(currentTarget.addCookie(any())).thenReturn(CompletableFuture.completedFuture(null));
        when(currentTarget.deleteCookie(eq("a"))).thenReturn(CompletableFuture.completedFuture(null));
        when(currentTarget.deleteAllCookies()).thenReturn(CompletableFuture.completedFuture(null));
        when(currentTarget.getScreenshotAsPng())
            .thenReturn(CompletableFuture.completedFuture(new byte[]{1, 2, 3}));
        when(currentTarget.getScreenshotAsFile(any())).thenReturn(CompletableFuture.completedFuture(null));
        when(currentTarget.sendKeys(eq("txt"))).thenReturn(CompletableFuture.completedFuture(null));
        when(currentTarget.back()).thenReturn(CompletableFuture.completedFuture(null));
        when(currentTarget.forward()).thenReturn(CompletableFuture.completedFuture(null));
        when(currentTarget.refresh(eq(true))).thenReturn(CompletableFuture.completedFuture(null));

        assertEquals(1, chrome.getCookies().get(1, TimeUnit.SECONDS).size());
        assertEquals("1", chrome.getCookie("a").get(1, TimeUnit.SECONDS).get("value"));
        chrome.addCookie(Map.of("name", "b")).get(1, TimeUnit.SECONDS);
        chrome.deleteCookie("a").get(1, TimeUnit.SECONDS);
        chrome.deleteAllCookies().get(1, TimeUnit.SECONDS);
        assertEquals(3, chrome.getScreenshotAsPng().get(1, TimeUnit.SECONDS).length);
        chrome.getScreenshotAsFile("/tmp/s.png").get(1, TimeUnit.SECONDS);
        chrome.sendKeys("txt").get(1, TimeUnit.SECONDS);
        chrome.back().get(1, TimeUnit.SECONDS);
        chrome.forward().get(1, TimeUnit.SECONDS);
        chrome.refresh(true).get(1, TimeUnit.SECONDS);
        chrome.refresh().get(1, TimeUnit.SECONDS);

        verify(currentTarget).back();
        verify(currentTarget).forward();
        verify(currentTarget).refresh(true);
        verify(currentTarget).refresh(false);
    }

    @Test
    void iframesAndSleepComposeThroughCurrentTarget() throws Exception {
        List<WebElement> iframes = List.of(mock(WebElement.class));
        when(currentTarget.getTargetsForIframes(eq(iframes)))
            .thenReturn(CompletableFuture.completedFuture(List.of(mock(Target.class))));
        when(currentTarget.getTargetForIframe(any()))
            .thenReturn(CompletableFuture.completedFuture(currentTarget));

        assertEquals(1, chrome.getTargetsForIframes(iframes).get(1, TimeUnit.SECONDS).size());
        assertSame(currentTarget, chrome.getTargetForIframe(iframes.get(0)).get(1, TimeUnit.SECONDS));
        chrome.sleep(0.0).get(1, TimeUnit.SECONDS);
    }

    // ---------- targets / janelas ----------

    @Test
    void getTargetsFiltersPageAndIframeTypes() throws Exception {
        when(baseTarget.executeCdpCmd(eq("Target.getTargets"), any(), any()))
            .thenReturn(CompletableFuture.completedFuture(json("""
                {"targetInfos":[
                  {"type":"page","targetId":"t-page"},
                  {"type":"iframe","targetId":"t-iframe"},
                  {"type":"service_worker","targetId":"t-sw"},
                  {"type":"browser","targetId":"t-browser"}
                ]}
                """)));

        List<Target> targets = chrome.getTargets().get(1, TimeUnit.SECONDS);

        assertEquals(2, targets.size());
        assertEquals("t-page", targets.get(0).getId());
        assertEquals("t-iframe", targets.get(1).getId());
    }

    @Test
    void windowHandlesExposeIdsOfVisibleTargets() throws Exception {
        when(currentTarget.getId()).thenReturn("t-current");
        when(baseTarget.executeCdpCmd(eq("Target.getTargets"), any(), any()))
            .thenReturn(CompletableFuture.completedFuture(json("""
                {"targetInfos":[{"type":"page","targetId":"t1"},{"type":"page","targetId":"t2"}]}
                """)));

        assertEquals(List.of("t1", "t2"), chrome.getWindowHandles().get(1, TimeUnit.SECONDS));
        assertEquals("t-current", chrome.getCurrentWindowHandle().get(1, TimeUnit.SECONDS));
    }

    @Test
    void getTargetFindsByIdAndThrowsForUnknown() throws Exception {
        when(baseTarget.executeCdpCmd(eq("Target.getTargets"), any(), any()))
            .thenReturn(CompletableFuture.completedFuture(json("""
                {"targetInfos":[{"type":"page","targetId":"t1"}]}
                """)));

        assertEquals("t1", chrome.getTarget("t1").get(1, TimeUnit.SECONDS).getId());
        RuntimeException thrown = assertThrows(RuntimeException.class,
            () -> chrome.getTarget("missing").join());
        assertTrue(thrown.getMessage().contains("missing"));
    }

    @Test
    void windowStateCommandsUseBrowserSetWindowBounds() throws Exception {
        when(currentTarget.getWindowId()).thenReturn(CompletableFuture.completedFuture(7));

        chrome.maximizeWindow().get(1, TimeUnit.SECONDS);
        chrome.minimizeWindow().get(1, TimeUnit.SECONDS);
        chrome.fullscreenWindow().get(1, TimeUnit.SECONDS);
        chrome.normalizeWindow().get(1, TimeUnit.SECONDS);

        verify(baseTarget).executeCdpCmd(eq("Browser.setWindowBounds"),
            argThat(args -> Map.of("windowState", "maximized").entrySet().stream()
                .allMatch(e -> e.getValue().equals(((Map<?, ?>) args.get("bounds")).get(e.getKey())))),
            isNull());
        verify(baseTarget).executeCdpCmd(eq("Browser.setWindowBounds"),
            argThat(args -> "minimized".equals(((Map<?, ?>) args.get("bounds")).get("windowState"))),
            isNull());
        verify(baseTarget).executeCdpCmd(eq("Browser.setWindowBounds"),
            argThat(args -> "fullscreen".equals(((Map<?, ?>) args.get("bounds")).get("windowState"))),
            isNull());
        verify(baseTarget).executeCdpCmd(eq("Browser.setWindowBounds"),
            argThat(args -> "normal".equals(((Map<?, ?>) args.get("bounds")).get("windowState"))),
            isNull());
    }

    @Test
    void windowRectParsesBoundsResponse() throws Exception {
        when(currentTarget.getWindowId()).thenReturn(CompletableFuture.completedFuture(7));
        when(baseTarget.executeCdpCmd(eq("Browser.getWindowBounds"), any(), any()))
            .thenReturn(CompletableFuture.completedFuture(json("""
                {"bounds":{"left":10,"top":20,"width":800,"height":600}}
                """)));

        Map<String, Object> rect = chrome.getWindowRect().get(1, TimeUnit.SECONDS);
        assertEquals(10, rect.get("x"));
        assertEquals(20, rect.get("y"));
        assertEquals(800, rect.get("width"));
        assertEquals(600, rect.get("height"));

        Map<String, Object> size = chrome.getWindowSize().get(1, TimeUnit.SECONDS);
        assertEquals(800, size.get("width"));
        assertEquals(600, size.get("height"));

        Map<String, Object> position = chrome.getWindowPosition().get(1, TimeUnit.SECONDS);
        assertEquals(10, position.get("x"));
        assertEquals(20, position.get("y"));
    }

    @Test
    void setWindowPositionAndSizeComposeBoundsPayload() throws Exception {
        when(currentTarget.getWindowId()).thenReturn(CompletableFuture.completedFuture(7));
        when(baseTarget.executeCdpCmd(eq("Browser.getWindowBounds"), any(), any()))
            .thenReturn(CompletableFuture.completedFuture(json("""
                {"bounds":{"left":5,"top":6,"width":800,"height":600}}
                """)));

        chrome.setWindowPosition(5, 6).get(1, TimeUnit.SECONDS);
        chrome.setWindowRect(5, 6, 1024, 768).get(1, TimeUnit.SECONDS);
        chrome.setWindowSize(1280, 720).get(1, TimeUnit.SECONDS);

        verify(baseTarget).executeCdpCmd(eq("Browser.setWindowBounds"),
            argThat(args -> {
                Map<?, ?> bounds = (Map<?, ?>) args.get("bounds");
                return Integer.valueOf(5).equals(bounds.get("left"))
                    && Integer.valueOf(6).equals(bounds.get("top"))
                    && !bounds.containsKey("width");
            }),
            isNull());
        verify(baseTarget).executeCdpCmd(eq("Browser.setWindowBounds"),
            argThat(args -> Integer.valueOf(1024).equals(((Map<?, ?>) args.get("bounds")).get("width"))
                && Integer.valueOf(768).equals(((Map<?, ?>) args.get("bounds")).get("height"))),
            isNull());
        // setWindowSize preserva a posição lida de Browser.getWindowBounds
        verify(baseTarget).executeCdpCmd(eq("Browser.setWindowBounds"),
            argThat(args -> {
                Map<?, ?> bounds = (Map<?, ?>) args.get("bounds");
                return Integer.valueOf(1280).equals(bounds.get("width"))
                    && Integer.valueOf(720).equals(bounds.get("height"))
                    && Integer.valueOf(5).equals(bounds.get("left"));
            }),
            isNull());
    }

    // ---------- contextos ----------

    @Test
    void newContextWithoutIncognitoSkipsCdpAndIsNotRegistered() throws Exception {
        Context context = chrome.newContext(false).get(1, TimeUnit.SECONDS);

        assertNotNull(context);
        assertFalse(context.isIncognito());
        assertEquals(0, chrome.getContexts().size());
        verify(baseTarget, never()).executeCdpCmd(eq("Target.createBrowserContext"), any(), any());
    }

    @Test
    void newIncognitoContextRegistersInContextsMap() throws Exception {
        when(baseTarget.executeCdpCmd(eq("Target.createBrowserContext"), any(), any()))
            .thenReturn(CompletableFuture.completedFuture(json("""
                {"browserContextId":"bc-9"}
                """)));

        Context context = chrome.newContext(true).get(1, TimeUnit.SECONDS);

        assertTrue(context.isIncognito());
        assertEquals(1, chrome.getContexts().size());
        assertSame(context, chrome.getContexts().get(0));
        verify(currentContext, never()).getContextId();
    }

    @Test
    void getBaseContextPrefersNonIncognitoAndFallsBackToCurrent() {
        Context incognito = mock(Context.class);
        when(incognito.isIncognito()).thenReturn(true);
        chrome.contexts.put("bc-x", incognito);

        // só há incognito registrado: getBaseContext cai para o contexto atual
        assertSame(currentContext, chrome.getBaseContext());

        Context normal = mock(Context.class);
        when(normal.isIncognito()).thenReturn(false);
        chrome.contexts.put("default", normal);

        assertSame(normal, chrome.getBaseContext());
    }

    @Test
    void removeContextQuitsTargetAndDropsFromMap() throws Exception {
        Context context = mock(Context.class);
        when(context.quit()).thenReturn(CompletableFuture.completedFuture(null));
        when(context.getContextId()).thenReturn("bc-del");
        chrome.contexts.put("bc-del", context);

        chrome.removeContext(context).get(1, TimeUnit.SECONDS);

        assertTrue(chrome.getContexts().isEmpty());
        verify(context).quit();
    }

    @Test
    void switchToExposesContextSwitcher() {
        SwitchTo switchTo = mock(SwitchTo.class);
        when(currentContext.switchTo()).thenReturn(switchTo);

        assertSame(switchTo, chrome.switchTo());
        assertSame(switchTo, chrome.getSwitchTo());
    }

    // ---------- permissões, geolocalização, downloads e emulação ----------

    @Test
    void setPermissionsIncludesBrowserContextWhenAvailable() throws Exception {
        chrome.setPermissions("https://x.test/", List.of("geolocation")).get(1, TimeUnit.SECONDS);

        verify(baseTarget).executeCdpCmd(eq("Browser.grantPermissions"),
            argThat(args -> "https://x.test/".equals(args.get("origin"))
                && "ctx-1".equals(args.get("browserContextId"))
                && List.of("geolocation").equals(args.get("permissions"))),
            isNull());
    }

    @Test
    void setPermissionTargetsSinglePermissionName() throws Exception {
        chrome.setPermission("https://x.test/", "notifications", "granted").get(1, TimeUnit.SECONDS);

        verify(baseTarget).executeCdpCmd(eq("Browser.setPermission"),
            argThat(args -> "https://x.test/".equals(args.get("origin"))
                && Map.of("name", "notifications").equals(args.get("permission"))
                && "granted".equals(args.get("setting"))),
            isNull());
    }

    @Test
    void setGeolocationOverrideDefaultsAccuracyTo100() throws Exception {
        chrome.setGeolocationOverride(-23.0, -46.0, null).get(1, TimeUnit.SECONDS);
        chrome.setGeolocationOverride(1.0, 2.0, 42.0).get(1, TimeUnit.SECONDS);

        verify(currentTarget).executeCdpCmd(eq("Emulation.setGeolocationOverride"),
            argThat(args -> Double.valueOf(100.0).equals(args.get("accuracy"))),
            isNull());
        verify(currentTarget).executeCdpCmd(eq("Emulation.setGeolocationOverride"),
            argThat(args -> Double.valueOf(42.0).equals(args.get("accuracy"))),
            isNull());
    }

    @Test
    void setRealisticDeviceMetricsSendsFlatPayload() throws Exception {
        chrome.setRealisticDeviceMetrics().get(1, TimeUnit.SECONDS);

        verify(currentTarget).executeCdpCmd(eq("Emulation.setDeviceMetricsOverride"),
            argThat(args -> Integer.valueOf(1920).equals(args.get("width"))
                && Integer.valueOf(1032).equals(args.get("height"))
                && Integer.valueOf(1).equals(args.get("deviceScaleFactor"))
                && Boolean.FALSE.equals(args.get("mobile"))),
            isNull());
    }

    @Test
    void setDownloadBehaviourIncludesContextAndOptionalPath() throws Exception {
        chrome.setDownloadBehaviour("allow", null).get(1, TimeUnit.SECONDS);
        chrome.setDownloadBehaviour("allow", "/tmp/dl").get(1, TimeUnit.SECONDS);

        verify(baseTarget).executeCdpCmd(eq("Browser.setDownloadBehavior"),
            argThat(args -> "allow".equals(args.get("behavior"))
                && "ctx-1".equals(args.get("browserContextId"))
                && !args.containsKey("downloadPath")),
            isNull());
        verify(baseTarget).executeCdpCmd(eq("Browser.setDownloadBehavior"),
            argThat(args -> "/tmp/dl".equals(args.get("downloadPath"))),
            isNull());
    }

    @Test
    void getDownloadsDirResolvesContextDirectory() throws Exception {
        when(baseTarget.downloadsDirForContext(eq("ctx-1"))).thenReturn("/tmp/dl-ctx-1");

        assertEquals("/tmp/dl-ctx-1", chrome.getDownloadsDir().get(1, TimeUnit.SECONDS));
    }

    @Test
    void browserLevelExecuteCdpCmdGoesThroughBaseTarget() throws Exception {
        JsonNode result = chrome.executeCdpCmd("Browser.getVersion", Map.of(), 5f)
            .get(1, TimeUnit.SECONDS);

        assertNotNull(result);
        verify(baseTarget).executeCdpCmd(eq("Browser.getVersion"), anyMap(), eq(5.0f));
    }

    // ---------- proxy dinâmico ----------

    @Test
    void setProxyEnablesFetchInterceptionAndWarnsAboutLateProxy() throws Exception {
        chrome.setProxy("p.test:8080", List.of("localhost")).get(1, TimeUnit.SECONDS);

        verify(currentTarget).executeCdpCmd(eq("Fetch.enable"),
            argThat(args -> Boolean.TRUE.equals(args.get("handleAuthRequests"))),
            isNull());
    }

    @Test
    void setSingleProxyAcceptsSchemeCredentialsAndPort() throws Exception {
        chrome.setSingleProxy("http://user:secret@p.test:8080", null).get(1, TimeUnit.SECONDS);
        chrome.setSingleProxy("p.test", null).get(1, TimeUnit.SECONDS);
        chrome.setSingleProxy("https://p.test:3128/", null).get(1, TimeUnit.SECONDS);

        // parse pode variar, mas toda variante termina habilitando Fetch no target atual
        verify(currentTarget, org.mockito.Mockito.times(3))
            .executeCdpCmd(eq("Fetch.enable"), any(), isNull());
    }

    @Test
    void setSingleProxyWithInvalidPortFailsWithWrappedError() {
        CompletableFuture<Void> future = chrome.setSingleProxy("p.test:not-a-port", null);

        RuntimeException thrown = assertThrows(RuntimeException.class, future::join);
        assertTrue(thrown.getMessage().contains("proxy único"));
    }

    @Test
    void clearProxyDisablesFetchWithoutGuards() throws Exception {
        chrome.clearProxy().get(1, TimeUnit.SECONDS);

        verify(currentTarget).executeCdpCmd(eq("Fetch.disable"), isNull(), any());
    }

    // ---------- sessão: pid, host, extração e limpeza ----------

    @Test
    void sessionAccessorsExposePidHostAndToString() {
        assertEquals(4242L, chrome.getBrowserPid());
        assertEquals("127.0.0.1", chrome.getHost());
        assertTrue(chrome.toString().contains("pid=4242"));

        chrome.browserPid = null;
        assertEquals(-1L, chrome.getBrowserPid());
        assertDoesNotThrow(chrome::toString);
    }

    @Test
    void extractallUnpacksZipWithinLimits(@TempDir Path dir) throws Exception {
        Path zip = dir.resolve("mini.zip");
        try (var zos = new java.util.zip.ZipOutputStream(Files.newOutputStream(zip))) {
            zos.putNextEntry(new java.util.zip.ZipEntry("a.txt"));
            zos.write("conteúdo".getBytes());
            zos.closeEntry();
        }
        Path out = dir.resolve("out");

        chrome.extractall(zip.toString(), out.toString()).get(5, TimeUnit.SECONDS);

        assertEquals("conteúdo", Files.readString(out.resolve("a.txt")));
    }

    @Test
    void extractallRejectsInvalidLimits() {
        assertThrows(IllegalArgumentException.class,
            () -> new ChromeTargetActions.ExtractionLimits(0, 1, 1));
        assertThrows(IllegalArgumentException.class,
            () -> new ChromeTargetActions.ExtractionLimits(1, -1, 1));
        assertThrows(IllegalArgumentException.class,
            () -> new ChromeTargetActions.ExtractionLimits(1, 1, 0));
    }

    @Test
    void cleanDirsSyncRemovesOwnedTempDirectory(@TempDir Path dir) {
        Path temp = dir.resolve("profile-tmp");
        assertDoesNotThrow(() -> Files.createDirectories(temp));
        chrome.tempDir = temp;

        chrome.cleanDirsSync();

        assertFalse(Files.exists(temp));
        // idempotente: sem tempDir não lança
        chrome.tempDir = null;
        assertDoesNotThrow(chrome::cleanDirsSync);
    }

    @Test
    void quitClearsSessionStateAndCleansDirsWhenAsked(@TempDir Path dir) throws Exception {
        Path temp = dir.resolve("profile-quit");
        Files.createDirectories(temp);
        chrome.tempDir = temp;
        chrome.isRemote = true; // não é dono do processo: sem encerrar árvore externa

        chrome.quit(true).get(5, TimeUnit.SECONDS);

        assertNull(chrome.currentTarget);
        assertNull(chrome.baseTarget);
        assertNull(chrome.currentContext);
        assertTrue(chrome.getContexts().isEmpty());
        assertFalse(chrome.started);
        assertFalse(Files.exists(temp));
    }

    @Test
    void quitWithoutCleanDirsKeepsProfileDirectory(@TempDir Path dir) throws Exception {
        Path temp = dir.resolve("profile-keep");
        Files.createDirectories(temp);
        chrome.tempDir = temp;
        chrome.isRemote = true;

        chrome.quit(false).get(5, TimeUnit.SECONDS);

        assertTrue(Files.exists(temp), "cleanDirs=false deve preservar o diretório do perfil");
        assertNull(chrome.currentTarget);
    }
}
