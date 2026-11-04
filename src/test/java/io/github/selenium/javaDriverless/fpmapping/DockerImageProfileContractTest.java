package io.github.selenium.javaDriverless.fpmapping;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;

import static org.assertj.core.api.Assertions.assertThat;

class DockerImageProfileContractTest {
    @Test
    void imageDefaultsToTheCanonicalVisibleDockerPresetUnderXvfb() throws Exception {
        String dockerfile = Files.readString(Path.of("Dockerfile"));
        String compose = Files.readString(Path.of("compose.yaml"));
        String profileDocs = Files.readString(Path.of("docs/JAVADRIVERLESS_SITE_PROFILES.md"));
        byte[] seccomp = Files.readAllBytes(Path.of("deploy/chrome-seccomp.json"));

        assertThat(dockerfile).contains(
                "JAVA_DRIVERLESS_HEADLESS=0",
                "JAVA_DRIVERLESS_PROFILE_PRESET=docker",
                "MAVEN_CONFIG=/home/javadriveless/.m2",
                "XDG_DATA_HOME=/tmp/java-driverless-data",
                "XDG_CONFIG_HOME=/tmp/java-driverless-config",
                "XDG_CACHE_HOME=/tmp/java-driverless-cache",
                "USER javadriveless",
                "ENTRYPOINT []",
                "java-driverless-visible-xvfb",
                "libnss3-tools",
                "java-driverless-trust-controlled-doh",
                "/etc/opt/chrome/policies/managed",
                "--screen-width 1365",
                "--screen-height 900",
                "--avail-width 1365",
                "--avail-height 860");
        assertThat(dockerfile).doesNotContain(
                "JAVA_DRIVERLESS_HEADLESS=1",
                "JAVA_DRIVERLESS_CONTAINER_FLAGS=true",
                "JAVA_DRIVERLESS_DOCKER_PROFILE=docs/site-profiles/linux-docker-server.json",
                "ignore-certificate-errors-spki-list");

        String trustScript = Files.readString(
                Path.of("scripts/trust-controlled-doh-certificate.sh"));
        assertThat(trustScript).contains(
                "certutil -N --empty-password",
                "certutil -A",
                "-t \"P,,\"",
                "$HOME/.pki/nssdb");

        assertThat(compose).contains(
                "cap_drop:",
                "- ALL",
                "cap_add:",
                "- SYS_CHROOT",
                "seccomp=./deploy/chrome-seccomp.json",
                "read_only: true");
        assertThat(compose).doesNotContain("SYS_ADMIN", "seccomp=unconfined");
        assertThat(profileDocs)
                .contains(
                        "docker compose run --rm --build javadriveless-proof",
                        "A plain `docker run` with Docker's default seccomp profile",
                        "work around that condition with `--no-sandbox`")
                .doesNotContain("docker run --rm javadriveless-docker-profile:test");
        assertThat(HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(seccomp)))
                .isEqualTo("4f39b0464c73569afbf72c7ab4db967913896052f257e62fee5503157254e7a6");
    }
}
