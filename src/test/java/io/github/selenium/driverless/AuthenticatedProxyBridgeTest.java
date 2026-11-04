package io.github.selenium.javaDriverless;

import io.github.selenium.javaDriverless.AuthenticatedProxyBridge;
import io.github.selenium.javaDriverless.JavaDriverless;
import io.github.selenium.javaDriverless.types.ChromeOptions;
import io.github.selenium.javaDriverless.types.ProxyConfig;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AuthenticatedProxyBridgeTest {
    @Test
    void connectTunnelAddsProxyAuthorizationToUpstreamOnly() throws Exception {
        String expectedAuth = "Basic " + Base64.getEncoder()
                .encodeToString("user:pass".getBytes(StandardCharsets.UTF_8));

        try (ServerSocket upstream = new ServerSocket(0)) {
            CompletableFuture<String> upstreamRequest = CompletableFuture.supplyAsync(() -> {
                try (Socket socket = upstream.accept()) {
                    String request = readHeaders(socket.getInputStream());
                    OutputStream out = socket.getOutputStream();
                    out.write("HTTP/1.1 200 Connection Established\r\n\r\n".getBytes(StandardCharsets.ISO_8859_1));
                    out.flush();
                    return request;
                } catch (Exception e) {
                    throw new RuntimeException(e);
                }
            });

            ProxyConfig config = new ProxyConfig(
                    "127.0.0.1",
                    upstream.getLocalPort(),
                    ProxyConfig.ProxyType.HTTP,
                    "user",
                    "pass"
            );

            try (AuthenticatedProxyBridge bridge = AuthenticatedProxyBridge.start(config, true, true)) {
                bridge.bindOwner(ProcessHandle.current().pid());
                try (Socket client = new Socket(
                        "127.0.0.1", Integer.parseInt(bridge.asChromeProxyServer().split(":")[2]))) {
                client.getOutputStream().write(("""
                        CONNECT example.com:443 HTTP/1.1\r
                        Host: example.com:443\r
                        \r
                        """).getBytes(StandardCharsets.ISO_8859_1));
                client.getOutputStream().flush();
                assertThat(readHeaders(client.getInputStream())).contains("200 Connection Established");
                }
            }

            assertThat(upstreamRequest.get(3, TimeUnit.SECONDS))
                    .contains("CONNECT example.com:443 HTTP/1.1")
                    .contains("Proxy-Authorization: " + expectedAuth);
        }
    }

    @Test
    void connectTunnelForwardsPayloadSentWithHandshake() throws Exception {
        byte[] payload = "ping-tunnel".getBytes(StandardCharsets.ISO_8859_1);

        try (ServerSocket upstream = new ServerSocket(0)) {
            CompletableFuture<Void> upstreamTask = CompletableFuture.runAsync(() -> {
                try (Socket socket = upstream.accept()) {
                    socket.setSoTimeout(5000);
                    readHeaders(socket.getInputStream());
                    OutputStream out = socket.getOutputStream();
                    out.write("HTTP/1.1 200 Connection Established\r\n\r\n".getBytes(StandardCharsets.ISO_8859_1));
                    out.flush();
                    byte[] received = socket.getInputStream().readNBytes(11);
                    out.write(received);
                    out.flush();
                } catch (Exception e) {
                    throw new RuntimeException(e);
                }
            });

            ProxyConfig config = new ProxyConfig(
                    "127.0.0.1",
                    upstream.getLocalPort(),
                    ProxyConfig.ProxyType.HTTP,
                    "user",
                    "pass"
            );

            try (AuthenticatedProxyBridge bridge = AuthenticatedProxyBridge.start(config, true, true)) {
                bridge.bindOwner(ProcessHandle.current().pid());
                try (Socket client = new Socket(
                        "127.0.0.1", Integer.parseInt(bridge.asChromeProxyServer().split(":")[2]))) {
                    client.setSoTimeout(5000);
                    byte[] handshake = ("CONNECT example.com:443 HTTP/1.1\r\n"
                            + "Host: example.com:443\r\n\r\n").getBytes(StandardCharsets.ISO_8859_1);
                    byte[] singleWrite = new byte[handshake.length + payload.length];
                    System.arraycopy(handshake, 0, singleWrite, 0, handshake.length);
                    System.arraycopy(payload, 0, singleWrite, handshake.length, payload.length);
                    client.getOutputStream().write(singleWrite);
                    client.getOutputStream().flush();
                    assertThat(readHeaders(client.getInputStream())).contains("200 Connection Established");
                    assertThat(client.getInputStream().readNBytes(11))
                            .isEqualTo(payload);
                }
            }

            upstreamTask.get(5, TimeUnit.SECONDS);
        }
    }

    @Test
    void upstreamBytesAfter200ReachClient() throws Exception {
        byte[] extraData = "EXTRA-DATA".getBytes(StandardCharsets.ISO_8859_1);

        try (ServerSocket upstream = new ServerSocket(0)) {
            CompletableFuture<Void> upstreamTask = CompletableFuture.runAsync(() -> {
                try (Socket socket = upstream.accept()) {
                    socket.setSoTimeout(5000);
                    readHeaders(socket.getInputStream());
                    OutputStream out = socket.getOutputStream();
                    out.write(("HTTP/1.1 200 Connection Established\r\n\r\n"
                            + "EXTRA-DATA").getBytes(StandardCharsets.ISO_8859_1));
                    out.flush();
                } catch (Exception e) {
                    throw new RuntimeException(e);
                }
            });

            ProxyConfig config = new ProxyConfig(
                    "127.0.0.1",
                    upstream.getLocalPort(),
                    ProxyConfig.ProxyType.HTTP,
                    "user",
                    "pass"
            );

            try (AuthenticatedProxyBridge bridge = AuthenticatedProxyBridge.start(config, true, true)) {
                bridge.bindOwner(ProcessHandle.current().pid());
                try (Socket client = new Socket(
                        "127.0.0.1", Integer.parseInt(bridge.asChromeProxyServer().split(":")[2]))) {
                    client.setSoTimeout(5000);
                    client.getOutputStream().write(("CONNECT example.com:443 HTTP/1.1\r\n"
                            + "Host: example.com:443\r\n\r\n").getBytes(StandardCharsets.ISO_8859_1));
                    client.getOutputStream().flush();
                    assertThat(readHeaders(client.getInputStream())).contains("200 Connection Established");
                    assertThat(client.getInputStream().readNBytes(10))
                            .isEqualTo(extraData);
                }
            }

            upstreamTask.get(5, TimeUnit.SECONDS);
        }
    }

    @Test
    void plainHttpForwardsBodySentWithHeaders() throws Exception {
        byte[] body = "jd-body-123".getBytes(StandardCharsets.ISO_8859_1);

        try (ServerSocket upstream = new ServerSocket(0)) {
            CompletableFuture<Void> upstreamTask = CompletableFuture.runAsync(() -> {
                try (Socket socket = upstream.accept()) {
                    socket.setSoTimeout(5000);
                    readHeaders(socket.getInputStream());
                    byte[] received = socket.getInputStream().readNBytes(11);
                    if (!new String(received, StandardCharsets.ISO_8859_1).equals("jd-body-123")) {
                        throw new RuntimeException(
                                "Corpo inesperado recebido pelo upstream: " + new String(received, StandardCharsets.ISO_8859_1));
                    }
                    OutputStream out = socket.getOutputStream();
                    out.write(("HTTP/1.1 200 OK\r\nContent-Length: 11\r\n\r\n"
                            + "jd-body-123").getBytes(StandardCharsets.ISO_8859_1));
                    out.flush();
                } catch (Exception e) {
                    throw new RuntimeException(e);
                }
            });

            ProxyConfig config = new ProxyConfig(
                    "127.0.0.1",
                    upstream.getLocalPort(),
                    ProxyConfig.ProxyType.HTTP,
                    "user",
                    "pass"
            );

            try (AuthenticatedProxyBridge bridge = AuthenticatedProxyBridge.start(config, true, true)) {
                bridge.bindOwner(ProcessHandle.current().pid());
                try (Socket client = new Socket(
                        "127.0.0.1", Integer.parseInt(bridge.asChromeProxyServer().split(":")[2]))) {
                    client.setSoTimeout(5000);
                    byte[] singleWrite = ("POST http://example.com/api HTTP/1.1\r\n"
                            + "Host: example.com\r\n"
                            + "Content-Length: 11\r\n\r\n"
                            + "jd-body-123").getBytes(StandardCharsets.ISO_8859_1);
                    client.getOutputStream().write(singleWrite);
                    client.getOutputStream().flush();
                    assertThat(readHeaders(client.getInputStream())).contains("200 OK");
                    assertThat(client.getInputStream().readNBytes(11))
                            .isEqualTo(body);
                }
            }

            upstreamTask.get(5, TimeUnit.SECONDS);
        }
    }

    @Test
    void startupFailureClosesAuthenticatedProxyBridge() throws Exception {
        try (ServerSocket upstream = new ServerSocket(0)) {
            ChromeOptions options = new ChromeOptions();
            options.setBinaryLocation("/tmp/java-driverless-missing-chrome-binary");
            options.setUserDataDir(Files.createTempDirectory("jd-bridge-fail-").toString());
            options.setAuthenticatedProxyBridge("http://user:pass@127.0.0.1:" + upstream.getLocalPort());

            assertThatThrownBy(() -> new JavaDriverless("BridgeStartupFailure", options, false))
                    .isInstanceOf(RuntimeException.class)
                    .hasMessageContaining("Erro ao iniciar JavaDriverless");

            assertThat(options.getArguments())
                    .noneMatch(arg -> arg.startsWith("--proxy-server=http://127.0.0.1:"));

            deleteDirectory(Path.of(options.getUserDataDir()));
        }
    }

    private static void deleteDirectory(Path path) throws Exception {
        if (!Files.exists(path)) {
            return;
        }
        try (var stream = Files.walk(path)) {
            for (Path current : stream.sorted((a, b) -> b.compareTo(a)).toList()) {
                Files.deleteIfExists(current);
            }
        }
    }

    private static String readHeaders(InputStream input) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        int previous3 = -1;
        int previous2 = -1;
        int previous1 = -1;
        int current;
        while ((current = input.read()) != -1) {
            out.write(current);
            if (previous3 == '\r' && previous2 == '\n' && previous1 == '\r' && current == '\n') {
                break;
            }
            previous3 = previous2;
            previous2 = previous1;
            previous1 = current;
        }
        return out.toString(StandardCharsets.ISO_8859_1);
    }
}
