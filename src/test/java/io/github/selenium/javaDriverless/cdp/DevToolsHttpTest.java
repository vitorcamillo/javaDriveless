package io.github.selenium.javaDriverless.cdp;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DevToolsHttpTest {

    // Cada teste sobe um HttpServer real em loopback (porta 0 = efêmera).

    @Test
    void getRetornaCorpoQuandoStatus200() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        try {
            byte[] corpo = "{\"webSocketDebuggerUrl\":\"ws://127.0.0.1:9222/devtools/browser/x\"}"
                    .getBytes(StandardCharsets.UTF_8);
            server.createContext("/json/version", exchange -> {
                exchange.sendResponseHeaders(200, corpo.length);
                try (var body = exchange.getResponseBody()) {
                    body.write(corpo);
                }
            });
            server.start();

            String resposta = DevToolsHttp.get(
                    "http://127.0.0.1:" + server.getAddress().getPort() + "/json/version",
                    TimeUnit.SECONDS.toNanos(10));

            assertThat(resposta).isEqualTo(new String(corpo, StandardCharsets.UTF_8));
        } finally {
            server.stop(0);
        }
    }

    @Test
    void getLancaIOExceptionQuandoStatusNaoFor200() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        try {
            server.createContext("/json", exchange -> {
                exchange.sendResponseHeaders(404, -1);
                exchange.close();
            });
            server.start();

            String url = "http://127.0.0.1:" + server.getAddress().getPort() + "/json";

            assertThatThrownBy(() -> DevToolsHttp.get(url, TimeUnit.SECONDS.toNanos(10)))
                    .isInstanceOf(IOException.class)
                    .hasMessageContaining("404");
        } finally {
            server.stop(0);
        }
    }

    @Test
    void getLancaHttpTimeoutExceptionQuandoCorpoInterrompidoAposCabecalhos() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        try {
            server.createContext("/json", exchange -> {
                // Envia cabeçalhos e corpo parcial, depois segura o restante bem além do
                // timeout do cliente: o prazo precisa valer para a resposta completa.
                byte[] parcial = "{\"parcial\":".getBytes(StandardCharsets.UTF_8);
                exchange.sendResponseHeaders(200, 1024);
                var body = exchange.getResponseBody();
                body.write(parcial);
                body.flush();
                try {
                    Thread.sleep(TimeUnit.SECONDS.toMillis(30));
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                }
                exchange.close();
            });
            server.start();

            String url = "http://127.0.0.1:" + server.getAddress().getPort() + "/json";

            assertThatThrownBy(() -> DevToolsHttp.get(url, TimeUnit.MILLISECONDS.toNanos(300)))
                    .isInstanceOf(HttpTimeoutException.class);
        } finally {
            server.stop(0);
        }
    }

    @Test
    void getLancaHttpTimeoutExceptionQuandoServidorDemoraAlemDoTimeout() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        try {
            server.createContext("/json", exchange -> {
                // Dorme bem além do timeout do cliente; o stop(0) no finally libera o teste.
                try {
                    Thread.sleep(TimeUnit.SECONDS.toMillis(30));
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                }
                exchange.sendResponseHeaders(200, -1);
                exchange.close();
            });
            server.start();

            String url = "http://127.0.0.1:" + server.getAddress().getPort() + "/json";

            assertThatThrownBy(() -> DevToolsHttp.get(url, TimeUnit.MILLISECONDS.toNanos(300)))
                    .isInstanceOf(HttpTimeoutException.class);
        } finally {
            server.stop(0);
        }
    }
}
