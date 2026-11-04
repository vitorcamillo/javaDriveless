package io.github.selenium.javaDriverless;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.util.Map;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.NullNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.slf4j.LoggerFactory;

import io.github.selenium.javaDriverless.types.BaseTarget;
import io.github.selenium.javaDriverless.types.BrowserFingerprintProfile;
import io.github.selenium.javaDriverless.types.ChromeOptions;
import io.github.selenium.javaDriverless.types.Target;
import io.github.selenium.javaDriverless.utils.UserAgentResolver;

/**
 * Contrato da nativização das injeções de fingerprint (regra de ouro: provar o transporte CDP sem
 * subir Chrome). Prova que, no caminho de SUCESSO, o spoof é registrado SÓ via
 * Page.enable + Page.addScriptToEvaluateOnNewDocument (runImmediately) e NENHUM
 * Runtime.evaluate/executeScript imediato é emitido; falha no preparo ou no registro persistente
 * aborta em vez de fingir sucesso apenas no realm atual. A injeção pausada de iframe/popup usa um
 * único registro com runImmediately.
 */
abstract class ChromeFingerprintNativeInjectionSupport {
    ChromeFingerprintSupport newSupport() {
        return new ChromeFingerprintSupport(
            new ChromeOptions(),
            new BrowserFingerprintProfile(),
            new ObjectMapper(),
            LoggerFactory.getLogger(ChromeFingerprintNativeInjectionTest.class),
            () -> "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 "
                + "(KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36",
            browser -> browser);
    }

    static BrowserFingerprintProfile supportedMinimalProfile() {
        BrowserFingerprintProfile profile = BrowserFingerprintProfile.nativeDefaults();
        profile.runtimePolicy = BrowserFingerprintProfile.RUNTIME_POLICY_MINIMAL_CDP;
        profile.webgl.mode = "real";
        profile.webRtc.mode = "altered";
        profile.webRtc.disableUdp = true;
        profile.mediaDevices.mode = "real";
        profile.speech.mode = "auto";
        profile.audio.mode = "real";
        profile.canvas.mode = "real";
        profile.clientRects.mode = "real";
        profile.storage.mode = "real";
        profile.fonts.mode = "real";
        profile.ports.mode = "real";
        profile.connection.mode = "real";
        profile.maxTouchPoints = 0;
        return profile;
    }

    // ---------- surface script (startup real + newWindow) ----------

}
