package io.github.selenium.javaDriverless;

import com.fasterxml.jackson.databind.JsonNode;
import io.github.selenium.javaDriverless.types.BaseTarget;
import io.github.selenium.javaDriverless.types.FingerprintCdpOverrides;
import io.github.selenium.javaDriverless.types.Target;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

/** Aplica somente overrides CDP nativos que são locais ao processo isolado do OOPIF. */
final class ChromeMinimalCdpOopifOverrides {
    private ChromeMinimalCdpOopifOverrides() {}

    static CompletableFuture<Void> installScopes(
            ChromeWorkerSpoofSupport support, Target page, BaseTarget browser) {
        String targetId = page.getId() == null ? "" : page.getId();
        BaseTarget pageBrowser = support.auxiliaryBrowserFactory.apply(browser);
        if (pageBrowser == null || pageBrowser == browser) {
            return CompletableFuture.failedFuture(new IllegalStateException(
                    "Conexao CDP independente ausente para popups minimal-cdp"));
        }
        support.sharedWorkerBrowser = pageBrowser;
        CompletableFuture<Void> browserScope = support.installWorkerSpoofOnScope(
                "browser-native-oopif", targetId, "", "",
                browser::addCdpListener, browser::addCdpSessionListener,
                (cmd, args, sessionId) -> browser.executeCdpCmd(cmd, args, null, sessionId));
        CompletableFuture<Void> pageScope = support.installWorkerSpoofOnScope(
                "page-native-resume", targetId, "", "",
                page::addCdpListener, page::addCdpSessionListener,
                (cmd, args, sessionId) -> page.executeCdpCmd(cmd, args, null, sessionId));
        CompletableFuture<Void> popupScope = support.installWorkerSpoofOnScope(
                "browser-native-pages", targetId, "", "",
                pageBrowser::addCdpListener, pageBrowser::addCdpSessionListener,
                (cmd, args, sessionId) -> pageBrowser.executeCdpCmd(cmd, args, null, sessionId));
        CompletableFuture<Void> installed = CompletableFuture.allOf(
                browserScope, pageScope, popupScope);
        installed.whenComplete((ignored, failure) -> {
            if (failure != null) pageBrowser.close();
        });
        return installed;
    }

    static CompletableFuture<Void> installRelatedPage(
            ChromeWorkerSpoofSupport support,
            Target page,
            BaseTarget browser,
            String targetId) {
        CompletableFuture<Void> pageScope = support.installWorkerSpoofOnScope(
                "page-native-resume:" + targetId, targetId, "", "",
                page::addCdpListener, page::addCdpSessionListener,
                (cmd, args, sessionId) -> page.executeCdpCmd(cmd, args, null, sessionId));
        Map<String, Object> related = new HashMap<>();
        related.put("targetId", targetId);
        related.put("waitForDebuggerOnStart", true);
        related.put("filter", ChromeWorkerSpoofSupportBase.autoAttachFilter("browser-native-oopif"));
        CompletableFuture<Void> browserScope = browser.executeCdpCmd(
                        "Target.autoAttachRelated", related, null, null)
                .thenApply(ignored -> null);
        return CompletableFuture.allOf(browserScope, pageScope);
    }

    static boolean isManagedTarget(
            String type, String targetId, String primaryPageTargetId, String openerId) {
        return "iframe".equals(type)
                || ("page".equals(type)
                    && !targetId.equals(primaryPageTargetId)
                    && openerId != null
                    && !openerId.isBlank());
    }

    static boolean requiresResumeDuringInstallation(String type, JsonNode targetInfo) {
        if (!"page".equals(type) || targetInfo == null
                || targetInfo.path("openerId").asText("").isBlank()) {
            return false;
        }
        return !targetInfo.has("canAccessOpener")
                || !targetInfo.path("canAccessOpener").asBoolean();
    }

    static CompletableFuture<Void> resumeUnmodifiedTarget(
            ChromeWorkerSpoofSupport support,
            ChromeWorkerSpoofSupportBase.SessionCdp cdp,
            String scope,
            String type,
            String targetId,
            String sessionId,
            String primaryPageTargetId,
            boolean waitingForDebugger) {
        if ("iframe".equals(type)) {
            return CompletableFuture.failedFuture(new IllegalArgumentException(
                    "Target nativo gerenciado nao pode ser liberado sem overrides: " + type));
        }
        if ("page".equals(type) && targetId.equals(primaryPageTargetId)) {
            return CompletableFuture.completedFuture(null);
        }
        if (waitingForDebugger) {
            return support.dispatchObserved(cdp, scope, type, targetId, sessionId,
                    "Runtime.runIfWaitingForDebugger", new HashMap<>());
        }
        return CompletableFuture.completedFuture(null);
    }

    static CompletableFuture<Void> install(
            ChromeWorkerSpoofSupport support,
            ChromeWorkerSpoofSupportBase.SessionCdp cdp,
            String scope,
            String type,
            String targetId,
            String sessionId,
            CompletableFuture<Void> listenerReady) {
        if (!"iframe".equals(type) && !"page".equals(type)) return listenerReady;
        if ("page".equals(type)
                && support.resumeDuringInstallationTargets.contains(targetId)) {
            return installRendererNewPage(
                    support, cdp, scope, type, targetId, sessionId, listenerReady);
        }
        CompletableFuture<Void> chain = listenerReady;
        if (support.fingerprintProfile.timezoneId == null
                || support.fingerprintProfile.timezoneId.isBlank()) {
            return CompletableFuture.failedFuture(support.reportFailure(
                    scope, type, targetId, sessionId, "Emulation.setTimezoneOverride",
                    new IllegalStateException("Perfil minimal-cdp sem timezoneId")));
        }
        chain = support.thenDispatch(
                chain, cdp, scope, type, targetId, sessionId,
                "Emulation.setTimezoneOverride",
                Map.of("timezoneId", support.fingerprintProfile.timezoneId));
        chain = support.thenDispatch(chain, cdp, scope, type, targetId, sessionId,
                "Emulation.setEmulatedMedia",
                FingerprintCdpOverrides.emulatedMedia(support.fingerprintProfile));
        if (MinimalCdpCapabilityContract.requiresGeolocationOverride(
                support.fingerprintProfile.geolocation)) {
            chain = support.thenDispatch(chain, cdp, scope, type, targetId, sessionId,
                    "Emulation.setGeolocationOverride",
                    FingerprintCdpOverrides.geolocation(support.fingerprintProfile));
        }
        if ("page".equals(type)) {
            Map<String, Object> pageScope = new HashMap<>();
            pageScope.put("autoAttach", true);
            pageScope.put("waitForDebuggerOnStart", true);
            pageScope.put("flatten", true);
            pageScope.put("filter", ChromeWorkerSpoofSupportBase.autoAttachFilter("page"));
            chain = support.thenDispatch(chain, cdp, scope, type, targetId, sessionId,
                    "Target.setAutoAttach", pageScope);

            Map<String, Object> browserScope = new HashMap<>();
            browserScope.put("targetId", targetId);
            browserScope.put("waitForDebuggerOnStart", true);
            browserScope.put("filter", ChromeWorkerSpoofSupportBase.autoAttachFilter("browser-native-oopif"));
            ChromeWorkerSpoofSupportBase.SessionCdp browserRoot = support.browserRootCdp;
            if (browserRoot == null) {
                return CompletableFuture.failedFuture(support.reportFailure(
                        scope, type, targetId, sessionId, "Target.autoAttachRelated",
                        new IllegalStateException("Conexao CDP browser root ausente")));
            }
            chain = chain.thenCompose(ignored -> support.dispatchObserved(
                    browserRoot, scope, type, targetId, null,
                    "Target.autoAttachRelated", browserScope));
        }
        return chain;
    }

    private static CompletableFuture<Void> installRendererNewPage(
            ChromeWorkerSpoofSupport support,
            ChromeWorkerSpoofSupportBase.SessionCdp cdp,
            String scope,
            String type,
            String targetId,
            String sessionId,
            CompletableFuture<Void> listenerReady) {
        CompletableFuture<Void> ready = listenerReady;
        if (support.fingerprintProfile.timezoneId == null
                || support.fingerprintProfile.timezoneId.isBlank()) {
            return CompletableFuture.failedFuture(support.reportFailure(
                    scope, type, targetId, sessionId, "Emulation.setTimezoneOverride",
                    new IllegalStateException("Perfil minimal-cdp sem timezoneId")));
        }
        if (MinimalCdpCapabilityContract.requiresGeolocationOverride(
                support.fingerprintProfile.geolocation)) {
            ready = support.thenDispatch(ready, cdp, scope, type, targetId, sessionId,
                    "Emulation.setGeolocationOverride",
                    FingerprintCdpOverrides.geolocation(support.fingerprintProfile));
        }
        Map<String, Object> pageScope = new HashMap<>();
        pageScope.put("autoAttach", true);
        pageScope.put("waitForDebuggerOnStart", true);
        pageScope.put("flatten", true);
        pageScope.put("filter", ChromeWorkerSpoofSupportBase.autoAttachFilter("page"));
        ready = support.thenDispatch(ready, cdp, scope, type, targetId, sessionId,
                "Target.setAutoAttach", pageScope);

        ChromeWorkerSpoofSupportBase.SessionCdp browserRoot = support.browserRootCdp;
        if (browserRoot == null) {
            return CompletableFuture.failedFuture(support.reportFailure(
                    scope, type, targetId, sessionId, "Target.autoAttachRelated",
                    new IllegalStateException("Conexao CDP browser root ausente")));
        }
        Map<String, Object> related = new HashMap<>();
        related.put("targetId", targetId);
        related.put("waitForDebuggerOnStart", true);
        related.put("filter", ChromeWorkerSpoofSupportBase.autoAttachFilter("browser-native-oopif"));
        ready = ready.thenCompose(ignored -> support.dispatchObserved(
                browserRoot, scope, type, targetId, null,
                "Target.autoAttachRelated", related));

        if (ChromeMinimalNativeProcessBaseline.coversRendererNew(
                support.fingerprintProfile)) {
            return ready.thenCompose(ignored -> support.dispatchObserved(
                    cdp, scope, type, targetId, sessionId,
                    "Runtime.runIfWaitingForDebugger", new HashMap<>()));
        }

        return ready.thenCompose(ignored -> {
            // Chrome 150 segura timezone/media ate o renderer novo receber resume. Os frames CDP
            // sao enviados nesta ordem na mesma sessao; assim o primeiro statement ja os observa.
            CompletableFuture<Void> timezone = support.dispatchObserved(
                    cdp, scope, type, targetId, sessionId,
                    "Emulation.setTimezoneOverride",
                    Map.of("timezoneId", support.fingerprintProfile.timezoneId));
            CompletableFuture<Void> media = support.dispatchObserved(
                    cdp, scope, type, targetId, sessionId,
                    "Emulation.setEmulatedMedia",
                    FingerprintCdpOverrides.emulatedMedia(support.fingerprintProfile));
            CompletableFuture<Void> resume = support.dispatchObserved(
                    cdp, scope, type, targetId, sessionId,
                    "Runtime.runIfWaitingForDebugger", new HashMap<>());
            return CompletableFuture.allOf(timezone, media, resume);
        });
    }
}
