package io.github.selenium.javaDriverless.types;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.selenium.javaDriverless.cdp.CDPSocket;
import io.github.selenium.javaDriverless.cdp.exceptions.CDPException;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.catchThrowable;

class CDPErrorClassificationTest {
    private static final ObjectMapper JSON = new ObjectMapper();

    @Test
    void notAttachedProtocolErrorRemainsRetryableAfterPublicMessageRedaction() {
        ProtocolErrorSocket socket = new ProtocolErrorSocket(
                -32000, "Not attached to an active page");
        RetryingHistoryTarget target = new RetryingHistoryTarget(socket);

        assertThatCode(() -> target.back().join()).doesNotThrowAnyException();
        assertThat(target.historyAttempts()).isEqualTo(3);
    }

    @Test
    void arbitraryProtocolMessageIsAbsentFromPublicFailureAndLogs() {
        String secretUrl = "https://user:TOKEN_SENTINEL@example.invalid/private?q=QUERY_SENTINEL";
        ProtocolErrorSocket socket = new ProtocolErrorSocket(
                -32000, "Rejected " + secretUrl + " Authorization=BEARER_SENTINEL");
        ByteArrayOutputStream capturedError = new ByteArrayOutputStream();
        PrintStream originalError = System.err;
        Throwable failure;

        try {
            System.setErr(new PrintStream(capturedError, true, StandardCharsets.UTF_8));
            failure = catchThrowable(() -> socket.exec("Page.navigate", Map.of(), 1.0f).join());
        } finally {
            System.setErr(originalError);
        }

        CDPException cdpFailure = findCause(failure, CDPException.class);
        String publicFailure = failure + "\n" + cdpFailure + "\n" + cdpFailure.getCdpMessage();
        String logs = capturedError.toString(StandardCharsets.UTF_8);

        assertThat(cdpFailure.getKind()).isEqualTo(CDPException.Kind.COMMAND_REJECTED);
        assertThat(publicFailure)
                .contains("Comando CDP rejeitado")
                .doesNotContain("TOKEN_SENTINEL", "QUERY_SENTINEL", "BEARER_SENTINEL",
                        "example.invalid", "Authorization");
        assertThat(logs)
                .doesNotContain("TOKEN_SENTINEL", "QUERY_SENTINEL", "BEARER_SENTINEL",
                        "example.invalid", "Authorization");
    }

    private static final class ProtocolErrorSocket extends CDPSocket {
        private final int code;
        private final String protocolMessage;

        private ProtocolErrorSocket(int code, String protocolMessage) {
            super("ws://127.0.0.1/devtools/page/test", 1.0f, 1 << 20);
            this.code = code;
            this.protocolMessage = protocolMessage;
            onOpen(null);
        }

        @Override
        public void send(String command) {
            try {
                long id = JSON.readTree(command).path("id").asLong();
                String response = JSON.writeValueAsString(Map.of(
                        "id", id,
                        "error", Map.of("code", code, "message", protocolMessage)));
                onMessage(response);
            } catch (JsonProcessingException failure) {
                throw new AssertionError(failure);
            }
        }
    }

    private static final class RetryingHistoryTarget extends Target {
        private final ProtocolErrorSocket firstFailure;
        private final AtomicInteger historyAttempts = new AtomicInteger();

        private RetryingHistoryTarget(ProtocolErrorSocket firstFailure) {
            super("127.0.0.1:0", "target", null, null,
                    false, 0.2f, "page", false, 1 << 20);
            this.firstFailure = firstFailure;
        }

        @Override
        public CompletableFuture<JsonNode> executeCdpCmd(
                String method, Map<String, Object> args, Float commandTimeout) {
            if ("Page.getNavigationHistory".equals(method)) {
                int attempt = historyAttempts.incrementAndGet();
                if (attempt == 1) {
                    return firstFailure.exec(method, args, commandTimeout);
                }
                return CompletableFuture.completedFuture(history(attempt >= 3 ? 0 : 1));
            }
            if ("Page.navigateToHistoryEntry".equals(method)) {
                return CompletableFuture.completedFuture(JSON.createObjectNode());
            }
            if ("Target.getTargetInfo".equals(method)) {
                return CompletableFuture.completedFuture(json("""
                        {"targetInfo":{"url":"https://previous.example/"}}
                        """));
            }
            return CompletableFuture.failedFuture(
                    new AssertionError("Unexpected CDP method: " + method));
        }

        private int historyAttempts() {
            return historyAttempts.get();
        }

        private static JsonNode history(int currentIndex) {
            return json("""
                    {
                      "currentIndex": %d,
                      "entries": [
                        {"id": 10, "url": "https://previous.example/"},
                        {"id": 20, "url": "https://current.example/"}
                      ]
                    }
                    """.formatted(currentIndex));
        }
    }

    private static JsonNode json(String value) {
        try {
            return JSON.readTree(value);
        } catch (JsonProcessingException failure) {
            throw new AssertionError(failure);
        }
    }

    private static <T extends Throwable> T findCause(Throwable failure, Class<T> type) {
        for (Throwable current = failure; current != null; current = current.getCause()) {
            if (type.isInstance(current)) {
                return type.cast(current);
            }
        }
        throw new AssertionError("Expected cause " + type.getSimpleName(), failure);
    }
}
