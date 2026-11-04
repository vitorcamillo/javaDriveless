package io.github.selenium.javaDriverless;

import io.github.selenium.javaDriverless.types.ChromeOptions;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.Arrays;

import static org.assertj.core.api.Assertions.assertThat;

class PublicFacadeReflectionCompatibilityTest {
    @Test
    void publicFacadeMethodsRemainConcreteAndDiscoverable() {
        assertNoSyntheticPublicMethods(JavaDriverless.class);
        assertNoSyntheticPublicMethods(ChromeOptions.class);
    }

    private static void assertNoSyntheticPublicMethods(Class<?> type) {
        assertThat(Arrays.stream(type.getDeclaredMethods())
                .filter(method -> Modifier.isPublic(method.getModifiers())))
                .as("public methods declared by %s", type.getName())
                .allMatch(PublicFacadeReflectionCompatibilityTest::isConcreteApiMethod);
    }

    private static boolean isConcreteApiMethod(Method method) {
        return !method.isSynthetic() && !method.isBridge();
    }
}
