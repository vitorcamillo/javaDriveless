package io.github.selenium.javaDriverless.types;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class BrowserFingerprintProfileCgroupDefaultsTest {
    @TempDir
    Path cgroupRoot;

    @Test
    void minimalCdpUsesFiniteCgroupV2CpuQuotaInsteadOfHostProcessors() throws Exception {
        Path leaf = Files.createDirectories(cgroupRoot.resolve("docker/session"));
        Files.writeString(leaf.resolve("cpu.max"), "150000 100000\n");
        BrowserFingerprintProfile profile = minimalProfile(64, 32);

        BrowserFingerprintProfileDefaults.applyTo(
                profile, 16, cgroupRoot, "0::/docker/session\n");

        assertThat(profile.hardwareConcurrency).isEqualTo(2);
    }

    @Test
    void minimalCdpUsesStrictestFiniteCpuQuotaFromCgroupV2Ancestors() throws Exception {
        Path parent = Files.createDirectories(cgroupRoot.resolve("tenant"));
        Files.writeString(parent.resolve("cpu.max"), "300000 100000\n");
        Path leaf = Files.createDirectories(parent.resolve("browser"));
        Files.writeString(leaf.resolve("cpu.max"), "max 100000\n");
        BrowserFingerprintProfile profile = minimalProfile(64, 32);

        BrowserFingerprintProfileDefaults.applyTo(
                profile, 16, cgroupRoot, "0::/tenant/browser\n");

        assertThat(profile.hardwareConcurrency).isEqualTo(3);
    }

    @Test
    void minimalCdpUsesFiniteCgroupV1CpuQuota() throws Exception {
        Path leaf = Files.createDirectories(cgroupRoot.resolve("cpu/docker/session"));
        Files.writeString(leaf.resolve("cpu.cfs_quota_us"), "250000\n");
        Files.writeString(leaf.resolve("cpu.cfs_period_us"), "100000\n");
        BrowserFingerprintProfile profile = minimalProfile(64, 32);

        BrowserFingerprintProfileDefaults.applyTo(
                profile, 16, cgroupRoot, "2:cpu,cpuacct:/docker/session\n");

        assertThat(profile.hardwareConcurrency).isEqualTo(3);
    }

    @Test
    void minimalCdpFailsFastForFiniteCgroupV2MemoryLimit() throws Exception {
        Path leaf = Files.createDirectories(cgroupRoot.resolve("docker/session"));
        Files.writeString(leaf.resolve("memory.max"), "1073741824\n");
        BrowserFingerprintProfile profile = minimalProfile(16, 32);

        assertThatThrownBy(() -> BrowserFingerprintProfileDefaults.applyTo(
                profile, 4, cgroupRoot, "0::/docker/session\n"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("minimal-cdp")
                .hasMessageContaining("deviceMemory")
                .hasMessageContaining("memory.max")
                .hasMessageContaining("legacy-spoof");

        assertThat(profile.hardwareConcurrency).isEqualTo(16);
        assertThat(profile.deviceMemory).isEqualTo(32);
    }

    @Test
    void minimalCdpAcceptsFiniteCgroupMemoryOnlyWithMatchingNativeChromeAttestation()
            throws Exception {
        Path leaf = Files.createDirectories(cgroupRoot.resolve("docker/session"));
        Files.writeString(leaf.resolve("memory.max"), "4294967296\n");
        BrowserFingerprintProfile profile = minimalProfile(16, 32);

        BrowserFingerprintProfileDefaults.applyTo(
                profile, 16, cgroupRoot, "0::/docker/session\n", Map.of(
                        "JAVA_DRIVERLESS_NATIVE_DEVICE_MEMORY_PROVISIONED", "1",
                        "JAVA_DRIVERLESS_NATIVE_DEVICE_MEMORY_GB", "32"));

        assertThat(profile.hardwareConcurrency).isEqualTo(16);
        assertThat(profile.deviceMemory).isEqualTo(32);
    }

    @Test
    void minimalCdpRejectsFiniteCgroupMemoryWhenNativeChromeAttestationDiffers()
            throws Exception {
        Path leaf = Files.createDirectories(cgroupRoot.resolve("docker/session"));
        Files.writeString(leaf.resolve("memory.max"), "4294967296\n");
        BrowserFingerprintProfile profile = minimalProfile(16, 32);

        assertThatThrownBy(() -> BrowserFingerprintProfileDefaults.applyTo(
                profile, 16, cgroupRoot, "0::/docker/session\n", Map.of(
                        "JAVA_DRIVERLESS_NATIVE_DEVICE_MEMORY_PROVISIONED", "1",
                        "JAVA_DRIVERLESS_NATIVE_DEVICE_MEMORY_GB", "8")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("attestation")
                .hasMessageContaining("esperado=32")
                .hasMessageContaining("observado=8");
    }

    @Test
    void minimalCdpFailsFastForFiniteCgroupV1MemoryLimit() throws Exception {
        Path leaf = Files.createDirectories(cgroupRoot.resolve("memory/docker/session"));
        Files.writeString(leaf.resolve("memory.limit_in_bytes"), "2147483648\n");
        BrowserFingerprintProfile profile = minimalProfile(16, 32);

        assertThatThrownBy(() -> BrowserFingerprintProfileDefaults.applyTo(
                profile, 16, cgroupRoot, "3:memory:/docker/session\n"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("minimal-cdp")
                .hasMessageContaining("deviceMemory")
                .hasMessageContaining("memory.limit_in_bytes")
                .hasMessageContaining("legacy-spoof");

        assertThat(profile.deviceMemory).isEqualTo(32);
    }

    @Test
    void minimalCdpFailsClosedForMalformedCgroupV2MemoryLimit() throws Exception {
        Files.writeString(cgroupRoot.resolve("memory.max"), "not-a-limit\n");
        BrowserFingerprintProfile profile = minimalProfile(16, 32);

        assertThatThrownBy(() -> BrowserFingerprintProfileDefaults.applyTo(
                profile, 16, cgroupRoot, "0::/\n"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("minimal-cdp")
                .hasMessageContaining("memory.max")
                .hasMessageContaining("inválido");

        assertThat(profile.hardwareConcurrency).isEqualTo(16);
        assertThat(profile.deviceMemory).isEqualTo(32);
    }

    @Test
    void minimalCdpFailsClosedForMalformedCgroupV1MemoryLimit() throws Exception {
        Path memory = Files.createDirectories(cgroupRoot.resolve("memory"));
        Files.writeString(memory.resolve("memory.limit_in_bytes"), "max\n");
        BrowserFingerprintProfile profile = minimalProfile(16, 32);

        assertThatThrownBy(() -> BrowserFingerprintProfileDefaults.applyTo(
                profile, 16, cgroupRoot, "3:memory:/\n"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("minimal-cdp")
                .hasMessageContaining("memory.limit_in_bytes")
                .hasMessageContaining("inválido");

        assertThat(profile.hardwareConcurrency).isEqualTo(16);
        assertThat(profile.deviceMemory).isEqualTo(32);
    }

    @Test
    void minimalCdpTreatsEveryNumericCgroupV2MemoryLimitAsFinite() throws Exception {
        Files.writeString(cgroupRoot.resolve("memory.max"), "9223372036854771712\n");
        BrowserFingerprintProfile profile = minimalProfile(16, 32);

        assertThatThrownBy(() -> BrowserFingerprintProfileDefaults.applyTo(
                profile, 16, cgroupRoot, "0::/\n"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("memory.max=9223372036854771712")
                .hasMessageContaining("limite cgroup finito");
    }

    @Test
    void minimalCdpFailsClosedWhenPresentMemoryFileCannotBeRead() throws Exception {
        Files.createDirectory(cgroupRoot.resolve("memory.max"));
        BrowserFingerprintProfile profile = minimalProfile(16, 32);

        assertThatThrownBy(() -> BrowserFingerprintProfileDefaults.applyTo(
                profile, 16, cgroupRoot, "0::/\n"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("minimal-cdp")
                .hasMessageContaining("memory.max")
                .hasMessageContaining("não pôde ser lido");

        assertThat(profile.hardwareConcurrency).isEqualTo(16);
        assertThat(profile.deviceMemory).isEqualTo(32);
    }

    @Test
    void minimalCdpFailsClosedWhenPresentV1MemoryFileCannotBeRead() throws Exception {
        Path memory = Files.createDirectories(cgroupRoot.resolve("memory"));
        Files.createDirectory(memory.resolve("memory.limit_in_bytes"));
        BrowserFingerprintProfile profile = minimalProfile(16, 32);

        assertThatThrownBy(() -> BrowserFingerprintProfileDefaults.applyTo(
                profile, 16, cgroupRoot, "3:memory:/\n"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("minimal-cdp")
                .hasMessageContaining("memory.limit_in_bytes")
                .hasMessageContaining("não pôde ser lido");

        assertThat(profile.hardwareConcurrency).isEqualTo(16);
        assertThat(profile.deviceMemory).isEqualTo(32);
    }

    @Test
    void minimalCdpFailsClosedWhenPresentCpuFileCannotBeRead() throws Exception {
        Files.createDirectory(cgroupRoot.resolve("cpu.max"));
        BrowserFingerprintProfile profile = minimalProfile(16, 32);

        assertThatThrownBy(() -> BrowserFingerprintProfileDefaults.applyTo(
                profile, 16, cgroupRoot, "0::/\n"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("minimal-cdp")
                .hasMessageContaining("cpu.max")
                .hasMessageContaining("não pôde ser lido");

        assertThat(profile.hardwareConcurrency).isEqualTo(16);
        assertThat(profile.deviceMemory).isEqualTo(32);
    }

    @Test
    void minimalCdpFailsClosedWhenPresentV1CpuControllerCannotBeTraversed() throws Exception {
        Files.writeString(cgroupRoot.resolve("cpu,cpuacct"), "not-a-directory\n");
        BrowserFingerprintProfile profile = minimalProfile(16, 32);

        assertThatThrownBy(() -> BrowserFingerprintProfileDefaults.applyTo(
                profile, 16, cgroupRoot, "2:cpu,cpuacct:/\n"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("minimal-cdp")
                .hasMessageContaining("controlador cgroup")
                .hasMessageContaining("cpu,cpuacct")
                .hasMessageContaining("não pôde ser lido");

        assertThat(profile.hardwareConcurrency).isEqualTo(16);
        assertThat(profile.deviceMemory).isEqualTo(32);
    }

    @Test
    void minimalCdpPreservesHostMemoryAndUsesJvmProcessorsWithoutCgroupLimits() {
        BrowserFingerprintProfile profile = minimalProfile(64, 32);

        BrowserFingerprintProfileDefaults.applyTo(profile, 12, cgroupRoot, "0::/\n");

        assertThat(profile.hardwareConcurrency).isEqualTo(12);
        assertThat(profile.deviceMemory).isEqualTo(32);
    }

    @Test
    void minimalCdpPreservesAConfiguredCpuCountBelowTheRuntimeCapacity() {
        BrowserFingerprintProfile profile = minimalProfile(8, 32);

        BrowserFingerprintProfileDefaults.applyTo(profile, 16, cgroupRoot, "0::/\n");

        assertThat(profile.hardwareConcurrency).isEqualTo(8);
    }

    @Test
    void minimalCdpIgnoresCgroupV2UnlimitedMarkers() throws Exception {
        Files.writeString(cgroupRoot.resolve("cpu.max"), "max 100000\n");
        Files.writeString(cgroupRoot.resolve("memory.max"), "max\n");
        BrowserFingerprintProfile profile = minimalProfile(64, 32);

        BrowserFingerprintProfileDefaults.applyTo(profile, 12, cgroupRoot, "0::/\n");

        assertThat(profile.hardwareConcurrency).isEqualTo(12);
        assertThat(profile.deviceMemory).isEqualTo(32);
    }

    @Test
    void minimalCdpIgnoresCgroupV1UnlimitedSentinels() throws Exception {
        Path cpu = Files.createDirectories(cgroupRoot.resolve("cpu"));
        Files.writeString(cpu.resolve("cpu.cfs_quota_us"), "-1\n");
        Files.writeString(cpu.resolve("cpu.cfs_period_us"), "100000\n");
        Path memory = Files.createDirectories(cgroupRoot.resolve("memory"));
        Files.writeString(memory.resolve("memory.limit_in_bytes"), "9223372036854771712\n");
        BrowserFingerprintProfile profile = minimalProfile(64, 32);

        BrowserFingerprintProfileDefaults.applyTo(
                profile, 12, cgroupRoot, "2:cpu,cpuacct:/\n3:memory:/\n");

        assertThat(profile.hardwareConcurrency).isEqualTo(12);
        assertThat(profile.deviceMemory).isEqualTo(32);
    }

    @Test
    void legacySpoofKeepsConfiguredResourcesEvenUnderFiniteCgroupLimits() throws Exception {
        Files.writeString(cgroupRoot.resolve("cpu.max"), "100000 100000\n");
        Files.writeString(cgroupRoot.resolve("memory.max"), "1073741824\n");
        BrowserFingerprintProfile profile = new BrowserFingerprintProfile();
        profile.runtimePolicy = BrowserFingerprintProfile.RUNTIME_POLICY_LEGACY_SPOOF;
        profile.hardwareConcurrency = 16;
        profile.deviceMemory = 32;

        BrowserFingerprintProfileDefaults.applyTo(profile, 12, cgroupRoot, "0::/\n");

        assertThat(profile.hardwareConcurrency).isEqualTo(16);
        assertThat(profile.deviceMemory).isEqualTo(32);
    }

    @Test
    void legacySpoofDoesNotReadMalformedOrUnreadableCgroupFiles() throws Exception {
        Files.createDirectory(cgroupRoot.resolve("cpu.max"));
        Files.writeString(cgroupRoot.resolve("memory.max"), "not-a-limit\n");
        BrowserFingerprintProfile profile = new BrowserFingerprintProfile();
        profile.runtimePolicy = BrowserFingerprintProfile.RUNTIME_POLICY_LEGACY_SPOOF;
        profile.hardwareConcurrency = 16;
        profile.deviceMemory = 32;

        BrowserFingerprintProfileDefaults.applyTo(profile, 12, cgroupRoot, "0::/\n");

        assertThat(profile.hardwareConcurrency).isEqualTo(16);
        assertThat(profile.deviceMemory).isEqualTo(32);
    }

    @Test
    void negativeV2MemoryLimitIsRejectedAsInvalidInsteadOfUnlimited() throws Exception {
        Path leaf = Files.createDirectories(cgroupRoot.resolve("docker/session"));
        Files.writeString(leaf.resolve("memory.max"), "-1\n");

        assertThatThrownBy(() -> BrowserFingerprintProfileDefaults.applyTo(
                minimalProfile(16, 32), 16, cgroupRoot, "0::/docker/session\n"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("memory.max=-1")
                .hasMessageContaining("valor cgroup inválido");
    }

    @Test
    void blankV2MemoryLimitIsRejectedAsInvalid() throws Exception {
        Path leaf = Files.createDirectories(cgroupRoot.resolve("docker/session"));
        Files.writeString(leaf.resolve("memory.max"), "   \n");

        assertThatThrownBy(() -> BrowserFingerprintProfileDefaults.applyTo(
                minimalProfile(16, 32), 16, cgroupRoot, "0::/docker/session\n"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("valor cgroup inválido");
    }

    @Test
    void negativeV1MemoryLimitIsRejectedAsInvalid() throws Exception {
        Path leaf = Files.createDirectories(cgroupRoot.resolve("memory/docker/session"));
        Files.writeString(leaf.resolve("memory.limit_in_bytes"), "-5\n");

        assertThatThrownBy(() -> BrowserFingerprintProfileDefaults.applyTo(
                minimalProfile(16, 32), 16, cgroupRoot, "3:memory:/docker/session\n"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("memory.limit_in_bytes=-5");
    }

    @Test
    void nonNumericCpuQuotasSilentlyFallBackToHostProcessors() throws Exception {
        Path leafV2 = Files.createDirectories(cgroupRoot.resolve("v2/session"));
        Files.writeString(leafV2.resolve("cpu.max"), "abc 100000\n");
        Files.writeString(leafV2.resolve("memory.max"), "max\n");

        BrowserFingerprintProfile v2 = minimalProfile(16, 32);
        BrowserFingerprintProfileDefaults.applyTo(v2, 16, cgroupRoot, "0::/v2/session\n");
        assertThat(v2.hardwareConcurrency).isEqualTo(16);

        Path leafV1 = Files.createDirectories(cgroupRoot.resolve("cpu/v1/session"));
        Files.writeString(leafV1.resolve("cpu.cfs_quota_us"), "abc\n");
        Files.writeString(leafV1.resolve("cpu.cfs_period_us"), "xyz\n");

        BrowserFingerprintProfile v1 = minimalProfile(16, 32);
        BrowserFingerprintProfileDefaults.applyTo(v1, 16, cgroupRoot, "2:cpu,cpuacct:/v1/session\n");
        assertThat(v1.hardwareConcurrency).isEqualTo(16);
    }

    @Test
    void v1MemoryControllerLineLocatesTheMemoryLeaf() throws Exception {
        Path cpuLeaf = Files.createDirectories(cgroupRoot.resolve("cpu/docker/session"));
        Files.writeString(cpuLeaf.resolve("cpu.cfs_quota_us"), "max\n");
        Path memoryLeaf = Files.createDirectories(cgroupRoot.resolve("memory/docker/session"));
        Files.writeString(memoryLeaf.resolve("memory.limit_in_bytes"), "-1\n");

        BrowserFingerprintProfile profile = minimalProfile(16, 32);
        BrowserFingerprintProfileDefaults.applyTo(profile, 16, cgroupRoot,
            "2:cpu,cpuacct:/docker/session\n3:memory:/docker/session\n");

        // limites "max"/sentinela: nada é rebaixado
        assertThat(profile.hardwareConcurrency).isEqualTo(16);
        assertThat(profile.deviceMemory).isEqualTo(32);
    }

    private static BrowserFingerprintProfile minimalProfile(int processors, int memoryGb) {
        BrowserFingerprintProfile profile = new BrowserFingerprintProfile();
        profile.runtimePolicy = BrowserFingerprintProfile.RUNTIME_POLICY_MINIMAL_CDP;
        profile.hardwareConcurrency = processors;
        profile.deviceMemory = memoryGb;
        return profile;
    }
}
