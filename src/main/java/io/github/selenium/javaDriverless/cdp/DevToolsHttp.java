package io.github.selenium.javaDriverless.cdp;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Cliente HTTP compartilhado para as sondas DevTools em loopback ({@code /json} e
 * {@code /json/version}).
 * <p>
 * Um único {@link HttpClient} do JDK atende todas as chamadas; ele usa threads daemon e não
 * impede o fim da JVM. Criar um cliente por target (pilha Netty inteira) era ~5x mais lento.
 * </p>
 */
public final class DevToolsHttp {

    private static final HttpClient SHARED_CLIENT = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .followRedirects(HttpClient.Redirect.NEVER)
            .build();

    private DevToolsHttp() {
    }

    /**
     * Faz um GET simples e retorna o corpo como String UTF-8.
     *
     * @param url endereço completo (ex: "http://localhost:9222/json/version")
     * @param timeoutNanos timeout da requisição em nanossegundos (<= 0 usa 10 s)
     * @return corpo da resposta
     * @throws IOException se o status não for 200 ou houver falha de I/O
     * @throws InterruptedException se a thread for interrompida
     */
    public static String get(String url, long timeoutNanos) throws IOException, InterruptedException {
        // HttpRequest.timeout exige duração positiva; normaliza antes de montar a requisição.
        long effectiveTimeoutNanos = timeoutNanos <= 0 ? TimeUnit.SECONDS.toNanos(10) : timeoutNanos;
        HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofNanos(effectiveTimeoutNanos))
                .GET()
                .build();
        // No JDK 21, HttpRequest.timeout expira ao receber os cabeçalhos; sem prazo no futuro,
        // um corpo interrompido bloquearia send() indefinidamente. O get abaixo limita a
        // operação completa (cabeçalhos + corpo) e cancela o futuro ao expirar.
        CompletableFuture<HttpResponse<byte[]>> future =
                SHARED_CLIENT.sendAsync(request, HttpResponse.BodyHandlers.ofByteArray());
        HttpResponse<byte[]> response;
        try {
            response = future.get(effectiveTimeoutNanos, TimeUnit.NANOSECONDS);
        } catch (TimeoutException e) {
            future.cancel(true);
            throw new HttpTimeoutException(
                    "Tempo limite da requisição excedido para " + url);
        } catch (InterruptedException e) {
            future.cancel(true);
            throw e;
        } catch (ExecutionException e) {
            Throwable cause = e.getCause();
            if (cause instanceof IOException ioException) {
                throw ioException;
            }
            if (cause instanceof InterruptedException interruptedException) {
                throw interruptedException;
            }
            if (cause instanceof RuntimeException runtimeException) {
                throw runtimeException;
            }
            if (cause instanceof Error error) {
                throw error;
            }
            throw new IOException("Falha na requisição ao DevTools para " + url, cause);
        }
        if (response.statusCode() != 200) {
            throw new IOException(
                    "Resposta inesperada do DevTools (status " + response.statusCode() + ") para " + url);
        }
        return new String(response.body(), StandardCharsets.UTF_8);
    }
}
