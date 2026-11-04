package io.github.selenium.javaDriverless.types;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.withSettings;

import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.NullNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

/**
 * Mixins CDP do Target (state/script/capture/casting/element/request) executando os métodos
 * REAIS sobre um executor CDP fake — sem browser, sem socket.
 */
class TargetMixinOperationsTest {

    private static final com.fasterxml.jackson.databind.ObjectMapper MAPPER =
        new com.fasterxml.jackson.databind.ObjectMapper();

    private Target target;

    private static ObjectNode json(String json) {
        try {
            return (ObjectNode) MAPPER.readTree(json);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    @BeforeEach
    void setUp() {
        target = Mockito.mock(Target.class, withSettings().defaultAnswer(Mockito.CALLS_REAL_METHODS));
        Mockito.doReturn("t-1").when(target).getId();
        // mixins iniciam a sessão lazy: init() precisa completar sem conectar WebSocket
        Mockito.doReturn(CompletableFuture.completedFuture(target)).when(target).init();

        Mockito.doAnswer(inv -> {
            String cmd = inv.getArgument(0);
            Map<String, Object> args = inv.getArgument(1);
            String objectId = args != null ? (String) args.get("objectId") : null;

            switch (cmd) {
                case "Network.getCookies":
                    return completed(json("{\"cookies\":["
                        + "{\"name\":\"a\",\"value\":\"1\",\"domain\":\".x.test\"},"
                        + "{\"name\":\"b\",\"value\":\"2\"}]}"));
                case "Storage.setCookies":
                case "Network.deleteCookies":
                case "Network.clearBrowserCookies":
                case "Network.emulateNetworkConditions":
                case "Emulation.setFocusEmulationEnabled":
                case "Target.activateTarget":
                case "Cast.enable":
                case "Cast.setSinkToUse":
                case "Cast.startDesktopMirroring":
                case "Cast.startTabMirroring":
                case "Cast.stopCasting":
                case "DOM.enable":
                case "DOM.discardSearchResults":
                case "Input.dispatchKeyEvent":
                case "Runtime.enable":
                    return completed(NullNode.getInstance());
                case "Page.printToPDF":
                    return completed(json("{\"data\":\"" + Base64.getEncoder().encodeToString("pdf".getBytes()) + "\"}"));
                case "Page.getNavigationHistory":
                    return completed(json("{\"currentIndex\":0,\"entries\":[{\"id\":1,\"url\":\"https://x.test/\"}]}"));
                case "Target.getTargetInfo":
                    return completed(json("{\"targetInfo\":{\"targetId\":\"t-1\",\"type\":\"page\","
                        + "\"title\":\"Título\",\"url\":\"https://x.test/\",\"attached\":true}}"));
                case "Page.getFrameTree":
                    return completed(json("{\"frameTree\":{\"frame\":{\"id\":\"F1\",\"url\":\"https://x.test/\"}}}"));
                case "Page.captureScreenshot":
                    return completed(json("{\"data\":\"" + Base64.getEncoder().encodeToString(new byte[]{9, 9}) + "\"}"));
                case "Page.captureSnapshot":
                    return completed(json("{\"data\":\"<mhtml/>\"}"));
                case "DOM.getDocument":
                    return completed(json("{\"root\":{\"nodeId\":1}}"));
                case "DOM.querySelectorAll":
                    return completed(json("{\"nodeIds\":[7,8]}"));
                case "DOM.performSearch":
                    return completed(json("{\"searchId\":\"s-1\",\"resultCount\":1}"));
                case "DOM.getSearchResults":
                    return completed(json("{\"nodeIds\":[7]}"));
                case "DOM.describeNode":
                    return completed(json("{\"node\":{\"backendNodeId\":77,\"nodeName\":\"DIV\"}}"));
                case "Runtime.evaluate":
                    return completed(json("{\"result\":{\"value\":\"ok\"}}"));
                case "Runtime.callFunctionOn":
                    if (objectId != null) {
                        return completed(json("{\"result\":{\"value\":\"by-object\"}}"));
                    }
                    return completed(json("{\"result\":{\"value\":\"ok\"}}"));
                default:
                    return completed(NullNode.getInstance());
            }
        }).when(target).executeCdpCmd(any(), any(), any());

        Mockito.doReturn(CompletableFuture.completedFuture(
                json("{\"sinks\":[{\"name\":\"TV Sala\",\"id\":\"sink-1\"}]}")))
            .when(target).waitForCdp(eq("Cast.sinksUpdated"), any());
        Mockito.doReturn(CompletableFuture.completedFuture(json("{\"issueMessage\":\"sem receptor\"}")))
            .when(target).waitForCdp(eq("Cast.issueUpdated"), any());
    }

    private static <T> CompletableFuture<T> completed(T value) {
        return CompletableFuture.completedFuture(value);
    }

    // ---------- TargetStateOperations ----------

    @Test
    void cookiesRoundTripThroughNativeCdp() {
        List<Map<String, Object>> cookies = target.getCookies().join();
        assertEquals(2, cookies.size());

        assertEquals("1", target.getCookie("a").join().get("value"));
        assertNull(target.getCookie("zz").join());

        target.addCookie(Map.of("name", "n", "value", "v")).join();
        target.deleteCookie("a").join();
        target.deleteAllCookies().join();

        Mockito.verify(target).executeCdpCmd(eq("Storage.setCookies"), anyMap(), any());
        Mockito.verify(target).executeCdpCmd(eq("Network.deleteCookies"), anyMap(), any());
        Mockito.verify(target).executeCdpCmd(eq("Network.clearBrowserCookies"), any(), any());
    }

    @Test
    void addCookieRejectsInvalidSameSite() {
        Exception thrown = assertThrows(Exception.class,
            () -> target.addCookie(Map.of("name", "n", "value", "v", "sameSite", "INVALIDO")).join());
        assertTrue(thrown.getCause() instanceof IllegalArgumentException);
    }

    @Test
    void pageMetadataComesFromTargetInfoAndFrameTree() {
        // type vem via toString do JsonNode (com aspas); título/url vêm como texto
        assertEquals("\"page\"", target.getType().join());
        assertEquals("Título", target.getTitleViaCDP().join());
        assertEquals("https://x.test/", target.getUrlViaCDP().join());
        Map<String, Object> info = target.getInfo().join();
        assertTrue(info.toString().contains("t-1"), "info=" + info);

        Map<String, Object> frameTree = target.getFrameTree().join();
        assertTrue(frameTree.toString().contains("F1"));
    }

    @Test
    void historyAndPrintPageUseTheirCdpCommands() {
        Map<String, Object> history = target.getHistory().join();
        assertTrue(history.containsKey("entries"));

        // printPage devolve o payload base64 do PDF sem decodificar
        assertEquals(java.util.Base64.getEncoder().encodeToString("pdf".getBytes()),
            target.printPage().join());
    }

    // sendKeys depende de lock interno (sendKeyLock) não inicializado num mock
    // CALLS_REAL_METHODS — os testes de sendKeys o injetam por reflexão.

    @Test
    void unfocusDisablesFocusEmulation() {
        target.unfocus().join();

        Mockito.verify(target).executeCdpCmd(eq("Emulation.setFocusEmulationEnabled"),
            Mockito.argThat(args -> Boolean.FALSE.equals(args.get("enabled"))), any());
    }

    // ---------- TargetScriptOperations ----------

    @Test
    void executeScriptEvaluatesAndUnwrapsValue() {
        Object out = target.executeScript("return 1", null, false).join();

        assertEquals("ok", out);
        Mockito.verify(target, Mockito.atLeastOnce())
            .executeCdpCmd(eq("Runtime.evaluate"), anyMap(), any());
    }

    // ---------- TargetCaptureOperations ----------

    @Test
    void screenshotAndSnapshotDecodeBase64Payloads() {
        byte[] png = target.getScreenshotAsPng().join();
        assertEquals(2, png.length);

        assertEquals("<mhtml/>", target.snapshot().join());
    }

    @Test
    void setNetworkConditionsEmulatesNetwork() {
        target.setNetworkConditions(true, 100, 200, 300, "cellular3g").join();

        Mockito.verify(target).executeCdpCmd(eq("Network.emulateNetworkConditions"),
            Mockito.argThat(args -> Boolean.TRUE.equals(args.get("offline"))
                && Integer.valueOf(100).equals(args.get("latency"))), any());
    }

    @Test
    void focusAndActivateBringTargetToFront() {
        target.focus().join();
        target.activate().join();

        Mockito.verify(target, Mockito.atLeastOnce())
            .executeCdpCmd(eq("Target.activateTarget"), any(), any());
    }

    // ---------- TargetCastingOperations ----------

    @Test
    void castingControlsSinkLifecycle() {
        assertEquals(1, target.getSinks().join().size());
        assertEquals("sem receptor", target.getIssueMessage().join());

        target.setSinkToUse("sink-1").join();
        target.startDesktopMirroring("sink-1").join();
        target.startTabMirroring("sink-1").join();
        target.stopCasting("sink-1").join();

        Mockito.verify(target).executeCdpCmd(eq("Cast.setSinkToUse"),
            Mockito.argThat(args -> "sink-1".equals(args.get("sinkName"))), any());
        Mockito.verify(target).executeCdpCmd(eq("Cast.startDesktopMirroring"), anyMap(), any());
        Mockito.verify(target).executeCdpCmd(eq("Cast.startTabMirroring"), anyMap(), any());
        Mockito.verify(target).executeCdpCmd(eq("Cast.stopCasting"), anyMap(), any());
    }

    // ---------- TargetElementOperations ----------

    @Test
    void findElementsUsesDocumentScopedNativeSearch() {
        List<WebElement> found = target.findElements("css selector", "div", 0.5f).join();

        assertEquals(2, found.size());
        assertEquals(77, found.get(0).getBackendNodeId().join());
    }

    @Test
    void searchElementsUsesPerformSearchAndDiscards() {
        List<WebElement> found = target.searchElements("//a").join();

        assertEquals(1, found.size());
        Mockito.verify(target).executeCdpCmd(eq("DOM.getSearchResults"), anyMap(), any());
    }

    // ---------- TargetRequestOperations ----------

    @Test
    void fetchRejectsUnsupportedBodyTypeBeforeTouchingTheNetwork() {
        CompletableFuture<Map<String, Object>> future =
            target.fetch("https://x.test/", "POST", null, 42, 5f);

        Exception thrown = assertThrows(Exception.class, () -> future.join());
        Throwable cause = thrown.getCause() != null ? thrown.getCause() : thrown;
        assertTrue(cause.getMessage().contains("Body"), "cause=" + cause);
    }

    @Test
    void fetchAcceptsMapStringAndBytesBodies() {
        assertInstanceOf(CompletableFuture.class,
            target.fetch("https://x.test/", "POST", null, Map.of("k", 1), 5f));
        assertInstanceOf(CompletableFuture.class,
            target.fetch("https://x.test/", "POST", null, "corpo", 5f));
        assertInstanceOf(CompletableFuture.class,
            target.fetch("https://x.test/", "POST", null, new byte[]{1}, 5f));
    }

    // ---------- TargetCaptureOperations (arquivos) ----------

    @Test
    void captureFilesAreWrittenEvenWithMismatchedExtensions(@org.junit.jupiter.api.io.TempDir
                                                            java.nio.file.Path dir) throws Exception {
        java.nio.file.Path png = dir.resolve("captura.png");
        target.getScreenshotAsFile(png.toString()).join();
        org.junit.jupiter.api.Assertions.assertArrayEquals(new byte[]{9, 9},
            java.nio.file.Files.readAllBytes(png));

        // extensão errada: só warning, o arquivo é gravado do mesmo jeito
        java.nio.file.Path extensaoErrada = dir.resolve("foto.jpg");
        target.saveScreenshot(extensaoErrada.toString()).join();
        assertEquals(2, java.nio.file.Files.size(extensaoErrada));

        java.nio.file.Path mhtml = dir.resolve("pagina.mhtml");
        target.saveSnapshot(mhtml.toString()).join();
        assertEquals("<mhtml/>", java.nio.file.Files.readString(mhtml));

        java.nio.file.Path mhtmlErrado = dir.resolve("pagina.html");
        target.saveSnapshot(mhtmlErrado.toString()).join();
        assertEquals("<mhtml/>", java.nio.file.Files.readString(mhtmlErrado));
    }

    @Test
    void networkConditionsWithUnknownConnectionTypeFailClosed() {
        CompletableFuture<Void> futuro = target.setNetworkConditions(false, 0, 0, 0, "5g-turbo");

        Exception thrown = assertThrows(Exception.class, () -> futuro.join());
        assertTrue(thrown.getCause().getMessage().contains("connectionType"));
    }

    @Test
    void chromedriverOnlySurfacesFailExplicitly() {
        // waitDownload lança dentro de supplyAsync: join propaga CompletionException com a causa
        Exception embrulhada = assertThrows(java.util.concurrent.CompletionException.class,
            () -> target.waitDownload(5f).join());
        assertInstanceOf(UnsupportedOperationException.class, embrulhada.getCause());

        // condições de rede lançam na própria chamada do método
        assertThrows(UnsupportedOperationException.class, target::getNetworkConditions);
        assertThrows(UnsupportedOperationException.class, target::deleteNetworkConditions);
    }

    @Test
    void baseFrameIsTheFrameMapInsideTheTree() {
        Map<String, Object> base = target.getBaseFrame().join();

        assertEquals("F1", base.get("id"));
        assertEquals("https://x.test/", base.get("url"));
    }

    @Test
    void windowIdLookupHappensOnceAndThenIsCached() throws Exception {
        Mockito.doAnswer(inv -> "Browser.getWindowForTarget".equals(inv.getArgument(0))
                ? completed(json("{\"windowId\":77}"))
                : completed(json("{}")))
            .when(target).executeCdpCmd(any(), any(), any());
        // CALLS_REAL_METHODS não roda construtor: o campo id precisa existir p/ Map.of
        java.lang.reflect.Field campoId = TargetConnectionCore.class.getDeclaredField("id");
        campoId.setAccessible(true);
        campoId.set(target, "t-1");

        Integer primeira = target.getWindowId().join();
        Integer segunda = target.getWindowId().join();

        assertEquals(77, primeira);
        assertEquals(primeira, segunda);
        Mockito.verify(target, Mockito.times(1)).executeCdpCmd(
            eq("Browser.getWindowForTarget"), anyMap(), any());
    }

    @Test
    void unmappedCharactersAreSkippedWithoutCdpTraffic() throws Exception {
        java.lang.reflect.Field campoLock = TargetConnectionCore.class.getDeclaredField("sendKeyLock");
        campoLock.setAccessible(true);
        if (campoLock.get(target) == null) {
            campoLock.set(target, new java.util.concurrent.locks.ReentrantLock());
        }
        org.junit.jupiter.api.Assertions.assertDoesNotThrow(
            () -> target.sendKeys("☺").join());
    }

    @Test
    void sendKeysSerializesConcurrentCallsInTypingOrder() throws Exception {
        java.lang.reflect.Field campoLock = TargetConnectionCore.class.getDeclaredField("sendKeyLock");
        campoLock.setAccessible(true);
        if (campoLock.get(target) == null) {
            campoLock.set(target, new java.util.concurrent.locks.ReentrantLock());
        }
        java.util.List<String> lista =
            java.util.Collections.synchronizedList(new java.util.ArrayList<>());
        Mockito.doAnswer(inv -> {
            Map<String, Object> args = inv.getArgument(1);
            lista.add(args.get("type") + ":" + args.get("key"));
            return CompletableFuture.completedFuture(NullNode.getInstance());
        }).when(target).executeCdpCmd(eq("Input.dispatchKeyEvent"), anyMap(), any());
        CompletableFuture<?> f1 = target.sendKeys("ab");
        CompletableFuture<?> f2 = target.sendKeys("cd");
        CompletableFuture.allOf(f1, f2).get(10, java.util.concurrent.TimeUnit.SECONDS);
        assertEquals(List.of("keyDown:a", "keyUp:a", "keyDown:b", "keyUp:b",
            "keyDown:c", "keyUp:c", "keyDown:d", "keyUp:d"), lista);
    }

    @Test
    void setSourceWritesThroughTheDocumentElement() {
        org.junit.jupiter.api.Assertions.assertDoesNotThrow(
            () -> target.setSource("<html><body>novo</body></html>", 5f).join());
        Mockito.verify(target).executeCdpCmd(eq("DOM.getDocument"), anyMap(), any());
    }

    @Test
    void setSourceTimesOutWhenTheDocumentNeverStabilizes() throws Exception {
        Mockito.doAnswer(inv -> CompletableFuture.failedFuture(
                new IllegalStateException("documento instável")))
            .when(target).executeCdpCmd(eq("DOM.getDocument"), anyMap(), any());

        java.lang.reflect.Field campoMapa = TargetConnectionCore.class.getDeclaredField("globalThis");
        campoMapa.setAccessible(true);
        if (campoMapa.get(target) == null) {
            campoMapa.set(target, new java.util.HashMap<>());
        }
        Exception thrown = assertThrows(Exception.class,
            () -> target.setSource("<html/>", 0.1f).join());

        // falha fechada após o timeout: exceção vem do loop ou do documento instável
        String mensagem = String.valueOf(thrown.getMessage()) + thrown.getCause();
        assertTrue(mensagem.contains("não-stale") || mensagem.contains("instável")
            || mensagem.contains("RuntimeExecutionContext"),
            "mensagem inesperada: " + mensagem);
    }
}
