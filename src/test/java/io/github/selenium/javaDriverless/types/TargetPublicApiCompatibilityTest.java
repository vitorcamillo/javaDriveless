package io.github.selenium.javaDriverless.types;

import com.fasterxml.jackson.databind.JsonNode;
import io.github.selenium.javaDriverless.cdp.CDPCommandExecutor;
import io.github.selenium.javaDriverless.cdp.CDPSocket;
import io.github.selenium.javaDriverless.input.Pointer;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;

class TargetPublicApiCompatibilityTest {

    private static final List<ApiMethod> V1_11_0_PUBLIC_METHODS = List.of(
            method("getId", String.class),
            method("getAttached", CompletableFuture.class),
            method("getOpenerId", CompletableFuture.class),
            method("getOpenerFrameId", CompletableFuture.class),
            method("getCanAccessOpener", CompletableFuture.class),
            method("getSubtype", CompletableFuture.class),
            method("getSocket", CDPSocket.class),
            method("getPointer", Pointer.class),
            method("init", CompletableFuture.class),
            method("get", CompletableFuture.class, String.class, String.class, boolean.class, float.class),
            method("get", CompletableFuture.class, String.class, boolean.class),
            method("back", CompletableFuture.class),
            method("forward", CompletableFuture.class),
            method("refresh", CompletableFuture.class, boolean.class),
            method("getCurrentUrl", CompletableFuture.class),
            method("getTitle", CompletableFuture.class),
            method("getPageSource", CompletableFuture.class),
            method("executeScript", CompletableFuture.class, String.class, Object[].class, boolean.class),
            method("executeScript", CompletableFuture.class, String.class, Object[].class, boolean.class, String.class),
            method("executeRawScript", CompletableFuture.class, String.class, boolean.class, String.class, Object[].class),
            method("executeAsyncScript", CompletableFuture.class, String.class, int.class, String.class, Object[].class),
            method("evalAsync", CompletableFuture.class, String.class, float.class, boolean.class, Object[].class),
            method("getAlert", CompletableFuture.class, float.class),
            method("findElement", CompletableFuture.class, String.class, String.class, Float.class),
            method("findElements", CompletableFuture.class, String.class, String.class, float.class),
            method("searchElements", CompletableFuture.class, String.class),
            method("getTargetsForIframes", CompletableFuture.class, List.class),
            method("getTargetForIframe", CompletableFuture.class, WebElement.class),
            method("getScreenshotAsPng", CompletableFuture.class),
            method("getScreenshotAsFile", CompletableFuture.class, String.class),
            method("saveScreenshot", CompletableFuture.class, String.class),
            method("snapshot", CompletableFuture.class),
            method("saveSnapshot", CompletableFuture.class, String.class),
            method("setNetworkConditions", CompletableFuture.class, boolean.class, int.class, int.class,
                    int.class, String.class),
            method("focus", CompletableFuture.class),
            method("activate", CompletableFuture.class),
            method("getCookies", CompletableFuture.class),
            method("getCookie", CompletableFuture.class, String.class),
            method("addCookie", CompletableFuture.class, Map.class),
            method("deleteCookie", CompletableFuture.class, String.class),
            method("deleteAllCookies", CompletableFuture.class),
            method("sendKeys", CompletableFuture.class, String.class),
            method("unfocus", CompletableFuture.class),
            method("printPage", CompletableFuture.class),
            method("getHistory", CompletableFuture.class),
            method("setSource", CompletableFuture.class, String.class, float.class),
            method("waitDownload", CompletableFuture.class, float.class),
            method("getInfo", CompletableFuture.class),
            method("getFrameTree", CompletableFuture.class),
            method("getBaseFrame", CompletableFuture.class),
            method("getType", CompletableFuture.class),
            method("getTitleViaCDP", CompletableFuture.class),
            method("getUrlViaCDP", CompletableFuture.class),
            method("getWindowId", CompletableFuture.class),
            method("getNetworkConditions", CompletableFuture.class),
            method("deleteNetworkConditions", CompletableFuture.class),
            method("fetch", CompletableFuture.class, String.class, String.class, Map.class, Object.class, float.class),
            method("xhr", CompletableFuture.class, String.class, String.class, Map.class, Object.class, float.class),
            method("getSinks", CompletableFuture.class),
            method("getIssueMessage", CompletableFuture.class),
            method("setSinkToUse", CompletableFuture.class, String.class),
            method("startDesktopMirroring", CompletableFuture.class, String.class),
            method("startTabMirroring", CompletableFuture.class, String.class),
            method("stopCasting", CompletableFuture.class, String.class),
            method("close", CompletableFuture.class),
            method("close", CompletableFuture.class, float.class),
            method("waitForCdp", CompletableFuture.class, String.class, Float.class),
            method("addCdpListener", CompletableFuture.class, String.class, Consumer.class),
            method("removeCdpListener", CompletableFuture.class, String.class, Consumer.class),
            method("getCdpEventIter", CompletableFuture.class, String.class),
            method("executeCdpCmd", CompletableFuture.class, String.class, Map.class, Float.class),
            method("executeCdpCmd", CompletableFuture.class, String.class, Map.class, Float.class, String.class),
            method("sleep", CompletableFuture.class, double.class),
            method("toString", String.class),
            method("equals", boolean.class, Object.class),
            method("hashCode", int.class)
    );

    @Test
    void declaresEveryV1_11_0PublicMethodWithItsOriginalDescriptor() throws Exception {
        for (ApiMethod api : V1_11_0_PUBLIC_METHODS) {
            Method declared = Target.class.getDeclaredMethod(api.name(), api.parameterTypes());

            assertThat(declared.getReturnType())
                    .as("return type of %s%s", api.name(), Arrays.toString(api.parameterTypes()))
                    .isEqualTo(api.returnType());
            assertThat(Modifier.isPublic(declared.getModifiers())).as(declared.toString()).isTrue();
            assertThat(declared.isSynthetic()).as(declared.toString()).isFalse();
            assertThat(declared.isBridge()).as(declared.toString()).isFalse();
        }

        assertThat(Arrays.stream(Target.class.getDeclaredMethods())
                .filter(method -> Modifier.isPublic(method.getModifiers())))
                .noneMatch(Method::isSynthetic);
    }

    @Test
    void retainsRepresentativeGenericSignatures() throws Exception {
        assertGenericReturn("getInfo",
                "java.util.concurrent.CompletableFuture<java.util.Map<java.lang.String, java.lang.Object>>");
        assertGenericReturn("getCookies",
                "java.util.concurrent.CompletableFuture<java.util.List<java.util.Map<java.lang.String, java.lang.Object>>>");
        assertGenericReturn("findElements",
                "java.util.concurrent.CompletableFuture<java.util.List<io.github.selenium.javaDriverless.types.WebElement>>",
                String.class, String.class, float.class);
        assertGenericReturn("fetch",
                "java.util.concurrent.CompletableFuture<java.util.Map<java.lang.String, java.lang.Object>>",
                String.class, String.class, Map.class, Object.class, float.class);

        Method fetch = Target.class.getDeclaredMethod(
                "fetch", String.class, String.class, Map.class, Object.class, float.class);
        assertThat(fetch.getGenericParameterTypes()[2].getTypeName())
                .isEqualTo("java.util.Map<java.lang.String, java.lang.String>");
    }

    @Test
    void remainsADirectTypedCdpCommandExecutorAndKeepsDisconnect() throws Exception {
        assertThat(Target.class.getInterfaces()).contains(CDPCommandExecutor.class);
        assertThat(CDPCommandExecutor.class.isAssignableFrom(Target.class)).isTrue();

        Method command = Target.class.getDeclaredMethod(
                "executeCdpCmd", String.class, Map.class, Float.class);
        assertThat(command.getGenericReturnType().getTypeName())
                .isEqualTo("java.util.concurrent.CompletableFuture<com.fasterxml.jackson.databind.JsonNode>");

        Method disconnect = Target.class.getDeclaredMethod("disconnect");
        assertThat(disconnect.getGenericReturnType().getTypeName())
                .isEqualTo("java.util.concurrent.CompletableFuture<java.lang.Void>");
        assertThat(disconnect.isSynthetic()).isFalse();
    }

    @Test
    void retainsVarargsMetadata() throws Exception {
        assertThat(Target.class.getDeclaredMethod(
                "executeRawScript", String.class, boolean.class, String.class, Object[].class).isVarArgs()).isTrue();
        assertThat(Target.class.getDeclaredMethod(
                "executeAsyncScript", String.class, int.class, String.class, Object[].class).isVarArgs()).isTrue();
        assertThat(Target.class.getDeclaredMethod(
                "evalAsync", String.class, float.class, boolean.class, Object[].class).isVarArgs()).isTrue();
    }

    private static void assertGenericReturn(String name, String expected, Class<?>... parameterTypes)
            throws Exception {
        assertThat(Target.class.getDeclaredMethod(name, parameterTypes).getGenericReturnType().getTypeName())
                .isEqualTo(expected);
    }

    private static ApiMethod method(String name, Class<?> returnType, Class<?>... parameterTypes) {
        return new ApiMethod(name, returnType, parameterTypes);
    }

    private record ApiMethod(String name, Class<?> returnType, Class<?>[] parameterTypes) {
    }
}
