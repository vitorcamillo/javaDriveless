package io.github.selenium.javaDriverless;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.selenium.javaDriverless.cdp.CDPSocket;
import io.github.selenium.javaDriverless.profiles.ProfileLaunchPlan;
import io.github.selenium.javaDriverless.profiles.ProfileLaunchPlanner;
import io.github.selenium.javaDriverless.profiles.SdkProfile;
import io.github.selenium.javaDriverless.profiles.SdkProfileGenerator;
import io.github.selenium.javaDriverless.profiles.SdkProfilePresetRequest;
import io.github.selenium.javaDriverless.scripts.NetworkInterceptor;
import io.github.selenium.javaDriverless.types.ChromeOptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

class DiagnosticRedactionTest {
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    @TempDir
    Path tempDir;

    @Test
    void startupFailureDoesNotExposePathsAddressesOrRawChromeOutput() throws Exception {
        ChromeOptions options = new ChromeOptions();
        options.setBinaryLocation(tempDir.resolve("BINARY_PATH_SENTINEL/chrome").toString());
        options.setDebuggerAddress("127.0.0.1:9222-DEBUGGER_SENTINEL");
        options.setUserDataDir(tempDir.resolve("USER_DATA_PATH_SENTINEL").toString());

        Process outputProcess = new ProcessBuilder(
                "printf",
                "Authorization: Bearer HEADER_SENTINEL\\n"
                        + "https://user:URL_PASSWORD_SENTINEL@example.invalid/private?q=QUERY_SENTINEL\\n"
                        + "postData=POST_DATA_SENTINEL\\n")
                .start();
        ChromeStartupOutput startupOutput = new ChromeStartupOutput();
        startupOutput.start(outputProcess, false, LoggerFactory.getLogger(DiagnosticRedactionTest.class));
        outputProcess.waitFor();
        startupOutput.reader.join();

        String diagnostics = ChromeStartupFailureDiagnostics.build(
                options, false, outputProcess, startupOutput);

        assertThat(diagnostics)
                .contains("binaryConfigured=true", "debuggerAddressConfigured=true",
                        "userDataDirConfigured=true", "chromeOutputAvailable=true")
                .doesNotContain("BINARY_PATH_SENTINEL", "DEBUGGER_SENTINEL",
                        "USER_DATA_PATH_SENTINEL", "HEADER_SENTINEL", "URL_PASSWORD_SENTINEL",
                        "QUERY_SENTINEL", "POST_DATA_SENTINEL", tempDir.toString());
    }

    @Test
    void interceptedRequestDiagnosticsExposeOnlyShapeNotPayload() throws Exception {
        JsonNode params = OBJECT_MAPPER.readTree("""
                {
                  "url": "https://url-user:URL_PASSWORD_SENTINEL@example.invalid/private?q=QUERY_SENTINEL",
                  "method": "POST",
                  "headers": {
                    "Authorization": "Bearer HEADER_SENTINEL",
                    "Cookie": "session=COOKIE_SENTINEL"
                  },
                  "postData": "POST_DATA_SENTINEL"
                }
                """);
        NetworkInterceptor.Request request = new NetworkInterceptor.Request(params, new Object());
        String rendered = request.toString();
        String doneMessage = new NetworkInterceptor.RequestDoneException(request, request.getUrl()).getMessage();

        assertThat(rendered)
                .contains("method=POST", "url=<redacted>", "headerCount=2", "hasPostData=true")
                .doesNotContain("URL_PASSWORD_SENTINEL", "QUERY_SENTINEL", "HEADER_SENTINEL",
                        "COOKIE_SENTINEL", "POST_DATA_SENTINEL", "Authorization", "Cookie");
        assertThat(doneMessage)
                .contains("url:<redacted>")
                .doesNotContain("URL_PASSWORD_SENTINEL", "QUERY_SENTINEL");
    }

    @Test
    void nonStandardRequestMethodIsNotCopiedToDiagnostics() throws Exception {
        JsonNode params = OBJECT_MAPPER.readTree("""
                {"url":"https://example.invalid", "method":"METHOD_SECRET_SENTINEL"}
                """);

        String rendered = new NetworkInterceptor.Request(params, new Object()).toString();

        assertThat(rendered)
                .contains("method=<other>")
                .doesNotContain("METHOD_SECRET_SENTINEL");
    }

    @Test
    void profileLaunchPlanDoesNotExportStartupUrlOrStoragePaths() throws Exception {
        SdkProfile profile = SdkProfileGenerator.fromPreset(
                new SdkProfilePresetRequest("diagnostic-redaction", "Diagnostic Redaction", "BR", "linux"));
        profile.browser.startupUrl =
                "https://url-user:URL_PASSWORD_SENTINEL@example.invalid/PRIVATE_PATH_SENTINEL?q=QUERY_SENTINEL";
        profile.storage.userDataDir = tempDir.resolve("USER_DATA_PATH_SENTINEL").toString();
        profile.storage.downloadsDir = tempDir.resolve("DOWNLOAD_PATH_SENTINEL").toString();

        ProfileLaunchPlan plan = ProfileLaunchPlanner.plan(profile);
        String exported = OBJECT_MAPPER.writeValueAsString(plan);

        assertThat(plan.optionsPlan)
                .containsEntry("startupUrl", "<redacted>")
                .containsEntry("userDataDir", "<configured>")
                .containsEntry("downloadsDir", "<configured>");
        assertThat(exported).doesNotContain(
                "URL_PASSWORD_SENTINEL", "PRIVATE_PATH_SENTINEL", "QUERY_SENTINEL",
                "USER_DATA_PATH_SENTINEL", "DOWNLOAD_PATH_SENTINEL", tempDir.toString());
    }

    @Test
    void cdpErrorLogsNeverIncludeRawMessagesOrExceptionPayloads() {
        PrintStream originalError = System.err;
        ByteArrayOutputStream captured = new ByteArrayOutputStream();
        try {
            System.setErr(new PrintStream(captured, true, StandardCharsets.UTF_8));
            CDPSocket socket = new CDPSocket(
                    "ws://127.0.0.1/devtools/page/WEBSOCKET_PATH_SENTINEL", 1, 1024);
            socket.onMessage("{\"Authorization\":\"Bearer RAW_CDP_SENTINEL\"");
            socket.addListener("EVENT_METHOD_SENTINEL", ignored -> {
                throw new IllegalStateException("LISTENER_PAYLOAD_SENTINEL");
            });
            socket.onMessage("{\"method\":\"EVENT_METHOD_SENTINEL\",\"params\":{}}");
            socket.onError(new IllegalStateException(
                    "https://example.invalid/private?q=EXCEPTION_QUERY_SENTINEL"));
        } finally {
            System.setErr(originalError);
        }

        String logged = captured.toString(StandardCharsets.UTF_8);
        assertThat(logged)
                .contains("mensagem CDP inválida", "IllegalStateException")
                .doesNotContain("WEBSOCKET_PATH_SENTINEL", "RAW_CDP_SENTINEL",
                        "Authorization", "EVENT_METHOD_SENTINEL", "LISTENER_PAYLOAD_SENTINEL",
                        "EXCEPTION_QUERY_SENTINEL", "example.invalid");
    }

    @Test
    void publicStartupFailurePreservesCauseTypeWithoutExposingItsPath() throws Exception {
        Path profileDir = java.nio.file.Files.createDirectory(tempDir.resolve("profile"));
        ChromeOptions options = new ChromeOptions();
        options.setBinaryLocation(tempDir.resolve("MISSING_BINARY_SECRET_SENTINEL").toString());
        options.setUserDataDir(profileDir.toString());

        Throwable failure = catchThrowable(() -> Chrome.create(options, 0.2f, false, 1 << 20).join());
        StringBuilder messages = new StringBuilder();
        Throwable cursor = failure;
        while (cursor != null) {
            messages.append(cursor.getMessage()).append('\n');
            cursor = cursor.getCause();
        }

        assertThat(failure).hasRootCauseInstanceOf(java.io.IOException.class);
        assertThat(messages.toString())
                .doesNotContain("MISSING_BINARY_SECRET_SENTINEL", tempDir.toString());
    }
}
