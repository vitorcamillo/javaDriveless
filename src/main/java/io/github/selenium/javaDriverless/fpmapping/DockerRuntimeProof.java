package io.github.selenium.javaDriverless.fpmapping;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.sun.net.httpserver.HttpServer;
import io.github.selenium.javaDriverless.JavaDriverless;
import io.github.selenium.javaDriverless.profiles.ProfileChromeOptionsFactory;
import io.github.selenium.javaDriverless.utils.OwnerOnlyFiles;
import io.github.selenium.javaDriverless.profiles.SdkProfilePresets;
import io.github.selenium.javaDriverless.types.BrowserFingerprintProfile;
import io.github.selenium.javaDriverless.types.ChromeOptions;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

public class DockerRuntimeProof {
    private static final ObjectMapper JSON = new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT);

    public static void main(String[] args) throws Exception {
        String configuredProfile = env("JAVA_DRIVERLESS_DOCKER_PROFILE", "");
        Path profilePath = configuredProfile.isBlank() ? null : Path.of(configuredProfile);
        Path outputPath = Path.of(env("JAVA_DRIVERLESS_DOCKER_PROOF_OUTPUT", "/tmp/java-driverless-docker-proof.json"));
        ChromeOptions options = profilePath == null ? dockerOptions() : dockerOptions(profilePath);

        Map<String, Object> report = new LinkedHashMap<>();
        report.put("schemaVersion", "java-driverless-docker-proof/v1");
        report.put("startedAt", Instant.now().toString());
        report.put("profilePath", profilePath == null ? "<preset:docker>" : profilePath.toString());
        report.put("binary", options.getBinaryLocation());

        JavaDriverless driver = null;
        HttpServer proofOrigin = null;
        try {
            proofOrigin = startProofOrigin();
            driver = new JavaDriverless("DockerRuntimeProof", options, false);
            Map<String, String> sandbox = processSecurity(driver.getPid());
            verifySandbox(sandbox);
            report.put("sandbox", sandbox);
            driver.get("http://127.0.0.1:" + proofOrigin.getAddress().getPort() + "/");
            report.put("arguments", List.copyOf(options.getArguments()));
            Object runtime = driver.executeScript("""
                    return (async () => {
                      const iceCandidates = [];
                      let webRtcSupported = typeof RTCPeerConnection === 'function';
                      if (webRtcSupported) {
                        let pc;
                        try {
                          pc = new RTCPeerConnection({iceServers: []});
                          const iceDone = new Promise(resolve => {
                            pc.onicecandidate = event => {
                              if (event.candidate && event.candidate.candidate) {
                                iceCandidates.push(event.candidate.candidate);
                              } else if (!event.candidate) {
                                resolve();
                              }
                            };
                          });
                          pc.createDataChannel('proof');
                          await pc.setLocalDescription(await pc.createOffer());
                          await Promise.race([iceDone, new Promise(resolve => setTimeout(resolve, 1500))]);
                        } finally {
                          if (pc) pc.close();
                        }
                      }
                      const candidateAddresses = iceCandidates.map(candidate =>
                        String(candidate).trim().split(/\\s+/)[4] || '');
                      const literalIpCandidateCount = candidateAddresses.filter(address =>
                        /^(?:\\d{1,3}\\.){3}\\d{1,3}$/.test(address) || address.includes(':')).length;
                      const candidateTypes = [...new Set(iceCandidates.map(candidate => {
                        const fields = String(candidate).trim().split(/\\s+/);
                        const marker = fields.indexOf('typ');
                        return marker >= 0 && fields[marker + 1] ? fields[marker + 1] : 'unknown';
                      }))];
                      return {
                      url: location.href,
                      title: document.title,
                      userAgent: navigator.userAgent,
                      webdriver: navigator.webdriver,
                      language: navigator.language,
                      languages: navigator.languages,
                      hardwareConcurrency: navigator.hardwareConcurrency,
                      deviceMemory: navigator.deviceMemory,
                      timezone: Intl.DateTimeFormat().resolvedOptions().timeZone,
                    screen: {
                      width: screen.width,
                      height: screen.height,
                      availWidth: screen.availWidth,
                      availHeight: screen.availHeight,
                      colorDepth: screen.colorDepth,
                      pixelDepth: screen.pixelDepth
                    },
                    webgl: (() => {
                      const canvas = document.createElement('canvas');
                      const gl = canvas.getContext('webgl') || canvas.getContext('experimental-webgl');
                      if (!gl) return { vendor: null, renderer: null };
                      const ext = gl.getExtension('WEBGL_debug_renderer_info');
                      return ext ? {
                        vendor: gl.getParameter(ext.UNMASKED_VENDOR_WEBGL),
                        renderer: gl.getParameter(ext.UNMASKED_RENDERER_WEBGL)
                      } : { vendor: gl.getParameter(gl.VENDOR), renderer: gl.getParameter(gl.RENDERER) };
                    })(),
                    viewport: {
                      innerWidth: innerWidth,
                      innerHeight: innerHeight,
                        outerWidth: outerWidth,
                        outerHeight: outerHeight,
                        devicePixelRatio: devicePixelRatio
                      },
                      webrtc: {
                        supported: webRtcSupported,
                        candidateCount: iceCandidates.length,
                        literalIpCandidateCount,
                        candidateTypes
                      }
                    };
                    })();
                    """);
            report.put("runtime", runtime);
            verify(runtime, options.getFingerprintProfile());
            report.put("status", "PASS");
            report.put("finishedAt", Instant.now().toString());
            write(outputPath, report);
            System.out.println("[java-driverless-docker-proof] PASS output=" + outputPath);
        } catch (Exception e) {
            report.put("status", "FAIL");
            report.put("error", e.toString());
            report.put("finishedAt", Instant.now().toString());
            try {
                write(outputPath, report);
            } catch (Exception writeError) {
                e.addSuppressed(writeError);
            }
            throw e;
        } finally {
            if (driver != null) {
                driver.quit();
            }
            if (proofOrigin != null) {
                proofOrigin.stop(0);
            }
        }
    }

    static ChromeOptions dockerOptions() {
        ChromeOptions options = ProfileChromeOptionsFactory.fromProfile(
                SdkProfilePresets.docker("docker-runtime-proof", "Docker Runtime Proof", "US"));
        configureCommonDockerOptions(options, options.getFingerprintProfile());
        return options;
    }

    /** Compatibility path for explicit legacy/site-specific JSON fixtures. */
    static ChromeOptions dockerOptions(Path profilePath) throws Exception {
        BrowserFingerprintProfile profile = BrowserFingerprintProfile.fromJson(profilePath);
        ChromeOptions options = new ChromeOptions();
        options.setFingerprintProfile(profile);
        if (BrowserFingerprintProfile.RUNTIME_POLICY_LEGACY_SPOOF.equals(profile.runtimePolicy)) {
            options.setHeadless(true);
        }
        configureCommonDockerOptions(options, profile);
        return options;
    }

    private static void configureCommonDockerOptions(
            ChromeOptions options,
            BrowserFingerprintProfile profile) {
        String binary = env("JAVA_DRIVERLESS_BROWSER_BINARY", "/usr/bin/google-chrome-stable");
        if (!binary.isBlank()) {
            options.setBinaryLocation(binary);
        }
        addIfMissing(options, "--disable-dev-shm-usage");
        addIfMissing(options, "--disable-background-networking");
        addIfMissing(options, "--disable-sync");
        addIfMissing(options, "--disable-component-update");
        addIfMissing(options, "--metrics-recording-only");
        addIfMissing(options, "--disable-default-apps");
        addIfMissing(options, "--no-first-run");
        addIfMissing(options, "--no-default-browser-check");
        if (BrowserFingerprintProfile.RUNTIME_POLICY_LEGACY_SPOOF.equals(profile.runtimePolicy)) {
            addIfMissing(options, "--window-size=" + profile.screen.width + "," + profile.screen.height);
            addIfMissing(options, "--use-gl=angle");
            addIfMissing(options, "--use-angle=swiftshader");
            addIfMissing(options, "--enable-unsafe-swiftshader");
        }
        options.setStartupUrl("data:text/html,<title>java-driverless-docker-proof</title><body>ok</body>");
    }

    private static void addIfMissing(ChromeOptions options, String argument) {
        if (!options.getArguments().contains(argument)) {
            options.addArgument(argument);
        }
    }

    @SuppressWarnings("unchecked")
    static void verify(Object runtime, BrowserFingerprintProfile profile) {
        if (!(runtime instanceof Map<?, ?> map)) {
            throw new IllegalStateException("runtime payload is not a map: " + runtime);
        }
        String userAgent = String.valueOf(map.get("userAgent"));
        if (!userAgent.contains("Chrome/") || userAgent.contains("HeadlessChrome")) {
            throw new IllegalStateException("unexpected userAgent: " + userAgent);
        }
        if (!Boolean.FALSE.equals(map.get("webdriver"))) {
            throw new IllegalStateException("navigator.webdriver must be exactly false");
        }
        requireText(map, "language", profile.locale);
        requireTextList(map, "languages", profile.languages);
        requireNumber(map, "hardwareConcurrency", profile.hardwareConcurrency);
        requireNumber(map, "deviceMemory", profile.deviceMemory);
        if (profile.timezoneId != null && !profile.timezoneId.isBlank()) {
            requireText(map, "timezone", profile.timezoneId);
        }
        Object screenValue = map.get("screen");
        if (!(screenValue instanceof Map<?, ?> screen)) {
            throw new IllegalStateException("screen payload is missing");
        }
        int width = number(screen.get("width"));
        int height = number(screen.get("height"));
        if (width != profile.screen.width || height != profile.screen.height) {
            throw new IllegalStateException("unexpected screen size: " + width + "x" + height);
        }
        requireScreenNumber(screen, "availWidth", profile.screen.availWidth);
        requireScreenNumber(screen, "availHeight", profile.screen.availHeight);
        requireScreenNumber(screen, "colorDepth", profile.screen.colorDepth);
        requireScreenNumber(screen, "pixelDepth", profile.screen.pixelDepth);
        Object webglValue = map.get("webgl");
        if (!(webglValue instanceof Map<?, ?> webgl)) {
            throw new IllegalStateException("webgl payload is missing");
        }
        String renderer = String.valueOf(webgl.get("renderer"));
        String vendor = String.valueOf(webgl.get("vendor"));
        if (vendor.isBlank() || "null".equals(vendor)) {
            throw new IllegalStateException("webgl vendor is missing: " + webgl);
        }
        if (renderer == null || renderer.isBlank() || "null".equals(renderer)) {
            throw new IllegalStateException("webgl renderer is missing: " + webgl);
        }
        if (usesMinimalCdpWithRealWebGl(profile)
                && renderer.toLowerCase(Locale.ROOT).contains("swiftshader")) {
            throw new IllegalStateException(
                    "minimal-cdp com webgl.mode=real exige renderer nativo; "
                            + "SwiftShader foi observado: " + renderer);
        }
        if (usesMinimalCdpWithRealWebGl(profile)
                && "spoof".equalsIgnoreCase(profile.screen.mode)) {
            Object viewportValue = map.get("viewport");
            if (!(viewportValue instanceof Map<?, ?> viewport)) {
                throw new IllegalStateException("viewport payload is missing");
            }
            requireScreenNumber(viewport, "outerWidth", profile.screen.width);
            requireScreenNumber(viewport, "outerHeight", profile.screen.availHeight - 8);
            Object dpr = viewport.get("devicePixelRatio");
            if (!(dpr instanceof Number number)
                    || Double.compare(number.doubleValue(), profile.screen.deviceScaleFactor) != 0) {
                throw new IllegalStateException("unexpected devicePixelRatio");
            }
        }
        if (profile.webRtc != null && profile.webRtc.disableUdp) {
            Object webRtcValue = map.get("webrtc");
            if (!(webRtcValue instanceof Map<?, ?> webRtc)
                    || !Boolean.TRUE.equals(webRtc.get("supported"))) {
                throw new IllegalStateException("WebRTC runtime proof is missing");
            }
            int literalIpCandidates = number(webRtc.get("literalIpCandidateCount"));
            if (literalIpCandidates != 0) {
                throw new IllegalStateException(
                        "WebRTC runtime proof observed a literal IP candidate");
            }
        }
    }

    static Map<String, String> processSecurity(long pid) throws Exception {
        Map<String, String> result = new LinkedHashMap<>();
        for (String line : Files.readAllLines(Path.of("/proc", Long.toString(pid), "status"))) {
            int separator = line.indexOf(':');
            if (separator < 0) continue;
            String key = line.substring(0, separator);
            String value = line.substring(separator + 1).trim();
            if ("CapEff".equals(key)) result.put("capEff", value);
            if ("NoNewPrivs".equals(key)) result.put("noNewPrivs", value);
            if ("Seccomp".equals(key)) result.put("seccomp", value);
        }
        return result;
    }

    static void verifySandbox(Map<String, String> sandbox) {
        String capabilities = sandbox == null ? "" : sandbox.getOrDefault("capEff", "");
        String noNewPrivileges = sandbox == null ? "" : sandbox.getOrDefault("noNewPrivs", "");
        String seccomp = sandbox == null ? "" : sandbox.getOrDefault("seccomp", "");
        if (!capabilities.matches("0+")
                || !"1".equals(noNewPrivileges)
                || !"2".equals(seccomp)) {
            throw new IllegalStateException(
                    "docker sandbox proof requires CapEff=0, NoNewPrivs=1 and Seccomp=2");
        }
    }

    private static void requireText(Map<?, ?> map, String field, String expected) {
        Object value = map.get(field);
        if (!(value instanceof String text) || !text.equals(expected)) {
            throw new IllegalStateException(
                    "unexpected " + field + ": expected=" + expected + ", observed=" + value);
        }
    }

    private static void requireTextList(Map<?, ?> map, String field, List<String> expected) {
        Object value = map.get(field);
        if (!(value instanceof List<?> values)
                || !values.stream().allMatch(String.class::isInstance)
                || !values.equals(expected)) {
            throw new IllegalStateException(
                    "unexpected " + field + ": expected=" + expected + ", observed=" + value);
        }
    }

    private static void requireNumber(Map<?, ?> map, String field, int expected) {
        Object value = map.get(field);
        if (!(value instanceof Number number) || number.intValue() != expected) {
            throw new IllegalStateException(
                    "unexpected " + field + ": expected=" + expected + ", observed=" + value);
        }
    }

    private static void requireScreenNumber(Map<?, ?> map, String field, int expected) {
        int observed = number(map.get(field));
        if (observed != expected) {
            throw new IllegalStateException(
                    "unexpected screen." + field + ": expected=" + expected
                            + ", observed=" + observed);
        }
    }

    private static boolean usesMinimalCdpWithRealWebGl(BrowserFingerprintProfile profile) {
        return profile != null
                && BrowserFingerprintProfile.RUNTIME_POLICY_MINIMAL_CDP.equalsIgnoreCase(profile.runtimePolicy)
                && profile.webgl != null
                && "real".equalsIgnoreCase(profile.webgl.mode);
    }

    private static int number(Object value) {
        return value instanceof Number number ? number.intValue() : -1;
    }

    private static HttpServer startProofOrigin() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        byte[] body = "<title>java-driverless-docker-proof</title><body>ok</body>"
                .getBytes(StandardCharsets.UTF_8);
        server.createContext("/", exchange -> {
            exchange.getResponseHeaders().set("Content-Type", "text/html; charset=utf-8");
            exchange.sendResponseHeaders(200, body.length);
            try (var output = exchange.getResponseBody()) {
                output.write(body);
            }
        });
        server.start();
        return server;
    }

    private static String env(String name, String fallback) {
        String value = System.getenv(name);
        return value == null || value.isBlank() ? fallback : value.trim();
    }

    private static void write(Path outputPath, Map<String, Object> report) throws Exception {
        Path parent = outputPath.getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        // serializa em memória e grava com 0600, sem seguir symlink em /tmp
        String json = JSON.writerWithDefaultPrettyPrinter().writeValueAsString(report);
        OwnerOnlyFiles.writeString(outputPath, json);
    }
}
