package io.github.selenium.javaDriverless.types;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import java.util.concurrent.CompletableFuture;

import com.fasterxml.jackson.databind.node.ObjectNode;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Screenshot de elemento: clip derivado do border box + decode base64 + gravação em disco. */
class WebElementScreenshotsTest {

    private static final com.fasterxml.jackson.databind.ObjectMapper MAPPER =
        new com.fasterxml.jackson.databind.ObjectMapper();

    @TempDir
    Path tempDir;

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

    private WebElement elementWithBorderBox() {
        when(target.executeCdpCmd(eq("DOM.getBoxModel"), anyMap(), any()))
            .thenReturn(CompletableFuture.completedFuture(
                json("{\"model\":{\"border\":[10,20,40,20,40,60,10,60]}}")));
        return new WebElement(target, null, null, "obj-1", 42, null, null, false);
    }

    @Test
    @SuppressWarnings("unchecked")
    void getScreenshotAsBase64ClipsToBorderBounds() {
        byte[] png = new byte[]{1, 2, 3, 4};
        when(target.executeCdpCmd(eq("Page.captureScreenshot"), anyMap(), any()))
            .thenReturn(CompletableFuture.completedFuture(
                json("{\"data\":\"" + Base64.getEncoder().encodeToString(png) + "\"}")));
        WebElement element = elementWithBorderBox();

        String base64 = element.getScreenshotAsBase64().join();

        assertEquals(Base64.getEncoder().encodeToString(png), base64);
        verify(target).executeCdpCmd(eq("Page.captureScreenshot"), argThat(args -> {
            java.util.Map<String, Object> clip = (java.util.Map<String, Object>) args.get("clip");
            return Double.valueOf(10.0).equals(clip.get("x"))
                && Double.valueOf(20.0).equals(clip.get("y"))
                && Double.valueOf(30.0).equals(clip.get("width"))
                && Double.valueOf(40.0).equals(clip.get("height"))
                && Boolean.TRUE.equals(args.get("captureBeyondViewport"));
        }), any());
    }

    @Test
    void getScreenshotAsPngDecodesBase64Payload() {
        byte[] png = new byte[]{9, 8, 7};
        when(target.executeCdpCmd(eq("Page.captureScreenshot"), anyMap(), any()))
            .thenReturn(CompletableFuture.completedFuture(
                json("{\"data\":\"" + Base64.getEncoder().encodeToString(png) + "\"}")));
        WebElement element = elementWithBorderBox();

        assertArrayEquals(png, element.getScreenshotAsPng().join());
    }

    @Test
    void screenshotWritesDecodedBytesToFile() throws java.io.IOException {
        byte[] png = new byte[]{5, 5, 5, 5, 5};
        when(target.executeCdpCmd(eq("Page.captureScreenshot"), anyMap(), any()))
            .thenReturn(CompletableFuture.completedFuture(
                json("{\"data\":\"" + Base64.getEncoder().encodeToString(png) + "\"}")));
        WebElement element = elementWithBorderBox();
        Path file = tempDir.resolve("element.png");

        Boolean written = element.screenshot(file.toString()).join();

        assertEquals(Boolean.TRUE, written);
        assertArrayEquals(png, Files.readAllBytes(file));
        assertTrue(Files.exists(file));
    }
}
