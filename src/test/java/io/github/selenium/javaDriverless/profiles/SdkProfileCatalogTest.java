package io.github.selenium.javaDriverless.profiles;

import io.github.selenium.javaDriverless.types.ProxyConfig;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SdkProfileCatalogTest {
    @TempDir
    Path tempDir;

    @Test
    void savesListsAndSelectsProfilesByIdOrName() {
        SdkProfileCatalog catalog = SdkProfileCatalog.at(tempDir);
        catalog.create("br-linux", "BR Linux");
        catalog.create("us-windows", "US Windows");

        assertThat(catalog.list()).extracting(profile -> profile.id)
                .containsExactly("br-linux", "us-windows");
        assertThat(catalog.select("br-linux").name).isEqualTo("BR Linux");
        assertThat(catalog.select("US Windows").id).isEqualTo("us-windows");
    }

    @Test
    void reportsMissingAndAmbiguousSelectors() {
        SdkProfileCatalog catalog = SdkProfileCatalog.at(tempDir);
        catalog.create("first", "Shared");
        catalog.create("second", "Shared");

        assertThatThrownBy(() -> catalog.select("missing"))
                .isInstanceOfSatisfying(ProfileSelectionException.class,
                        error -> assertThat(error.getReason()).isEqualTo(ProfileSelectionException.Reason.MISSING));
        assertThatThrownBy(() -> catalog.select("Shared"))
                .isInstanceOfSatisfying(ProfileSelectionException.class,
                        error -> assertThat(error.getReason()).isEqualTo(ProfileSelectionException.Reason.AMBIGUOUS));
    }

    @Test
    void rejectsUnsafeProfileIds() {
        SdkProfileCatalog catalog = SdkProfileCatalog.at(tempDir);

        assertThatThrownBy(() -> catalog.create("../escape", "Escape"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> catalog.create("nested/path", "Nested"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> catalog.load(".."))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void exportsAndImportsWithoutProxyCredentials() throws Exception {
        SdkProfileCatalog source = SdkProfileCatalog.at(tempDir.resolve("source"));
        SdkProfile profile = new SdkProfile("proxy-profile", "Proxy Profile");
        profile.proxy = new SdkProfileProxy("127.0.0.1", 8080, ProxyConfig.ProxyType.HTTP);
        source.save(profile);

        Path exported = tempDir.resolve("exported.json");
        source.exportProfile("proxy-profile", exported);
        String json = Files.readString(exported);

        assertThat(json).contains("127.0.0.1");
        assertThat(json).doesNotContain("password").doesNotContain("username");

        SdkProfileCatalog target = SdkProfileCatalog.at(tempDir.resolve("target"));
        SdkProfile imported = target.importProfile(exported);

        assertThat(imported.id).isEqualTo("proxy-profile");
        assertThat(target.select("Proxy Profile").proxy.port).isEqualTo(8080);
    }

    @Test
    void jsonRoundTripAppliesDefaults() {
        SdkProfileCatalog catalog = SdkProfileCatalog.at(tempDir);
        SdkProfile profile = new SdkProfile("round-trip", "Round Trip");
        profile.fingerprint = null;
        profile.extensionPaths.add("/opt/extensions/a");

        SdkProfile restored = catalog.fromJson(catalog.toJson(profile));

        assertThat(restored.schemaVersion).isEqualTo(SdkProfile.CURRENT_SCHEMA_VERSION);
        assertThat(restored.fingerprint).isNotNull();
        assertThat(restored.extensionPaths).containsExactly("/opt/extensions/a");
    }
}
