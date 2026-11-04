package io.github.selenium.javaDriverless.scripts;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Caracterizacao do contrato dos scripts de anti-deteccao injetados no
 * navegador: estrutura IIFE, marcos de protecao e ausencia de rastro
 * de debug (GAP-06).
 */
class AntiDetectionScriptsContractTest {

    @Test
    void comprehensiveScriptIsStrictModeIife() {
        String script = AntiDetectionScripts.comprehensive();

        assertThat(script).startsWith("(function() {");
        assertThat(script).endsWith("})();");
        assertThat(script).contains("'use strict'");
        assertThat(script.length()).isGreaterThan(5000);
    }

    @Test
    void comprehensiveScriptNeutralizesWebdriverFlag() {
        String script = AntiDetectionScripts.comprehensive();

        assertThat(script).contains("removeAttribute('webdriver')");
        assertThat(script).contains("Navigator.prototype, 'webdriver'");
        assertThat(script).contains("return undefined;");
    }

    @Test
    void comprehensiveScriptMocksHeadfulPluginsAndMimeTypes() {
        String script = AntiDetectionScripts.comprehensive();

        assertThat(script).contains("Chrome PDF Plugin");
        assertThat(script).contains("Chrome PDF Viewer");
        assertThat(script).contains("Native Client");
        assertThat(script).contains("namedItem");
        assertThat(script).contains("mimeTypes");
    }

    @Test
    void comprehensiveScriptEmbedsChromeRuntimeObject() {
        String script = AntiDetectionScripts.comprehensive();

        assertThat(script).contains("connect: function connect()");
        assertThat(script).contains("sendMessage: function sendMessage()");
        assertThat(script).contains("manifest_version: 3");
    }

    @Test
    void scriptsLeaveNoDebugTrace() {
        // GAP-06: console.log no script injetado entrega a anti-deteccao.
        assertThat(AntiDetectionScripts.comprehensive()).doesNotContain("console.log");
        assertThat(AntiDetectionChromeScripts.chromeObject()).doesNotContain("console.log");
    }

    @Test
    void chromeObjectMimicsExtensionBridgeSurface() {
        String chromeObject = AntiDetectionChromeScripts.chromeObject();

        assertThat(chromeObject).contains("getManifest");
        assertThat(chromeObject).contains("getURL");
        assertThat(chromeObject).contains("requestUpdateCheck");
        assertThat(chromeObject).contains("getIsInstalled");
    }

    @Test
    void scriptsCarryNoUnrenderedTemplatePlaceholders() {
        assertThat(AntiDetectionScripts.comprehensive())
                .doesNotContain("%s")
                .doesNotContain("${");
        assertThat(AntiDetectionChromeScripts.chromeObject())
                .doesNotContain("%s")
                .doesNotContain("${");
    }
}
