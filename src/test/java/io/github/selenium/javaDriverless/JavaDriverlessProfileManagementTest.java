package io.github.selenium.javaDriverless;

import io.github.selenium.javaDriverless.types.ChromeOptions;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class JavaDriverlessProfileManagementTest {
    @Test
    void enablesLegacyProfileManagementForUnstructuredOptions() {
        assertTrue(JavaDriverless.effectiveProfileManagement(true, null));
        assertTrue(JavaDriverless.effectiveProfileManagement(true, new ChromeOptions()));
    }

    @Test
    void disablesLegacyProfileManagementForStructuredOptions() {
        ChromeOptions options = new ChromeOptions();
        options.bindProfileLaunchArgumentGuard();

        assertFalse(JavaDriverless.effectiveProfileManagement(true, options));
    }

    @Test
    void explicitDisableWinsForAnyOptions() {
        assertFalse(JavaDriverless.effectiveProfileManagement(false, null));
        assertFalse(JavaDriverless.effectiveProfileManagement(false, new ChromeOptions()));

        ChromeOptions structured = new ChromeOptions();
        structured.bindProfileLaunchArgumentGuard();
        assertFalse(JavaDriverless.effectiveProfileManagement(false, structured));
    }
}
