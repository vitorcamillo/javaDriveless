package io.github.selenium.javaDriverless.fpmapping;

import io.github.selenium.javaDriverless.Chrome;
import io.github.selenium.javaDriverless.types.ChromeOptions;
import io.github.selenium.javaDriverless.types.BrowserFingerprintProfile;
import java.io.File;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

public class CreepJsManualRunner {
    public static void main(String[] args) throws Exception {
        System.out.println("🚀 Iniciando Teste CreepJS com JavaDriverless (Better than Dolphin)...");
        
        // 1. Criar Profile de alta integridade (US Windows 10)
        BrowserFingerprintProfile profile = BrowserFingerprintProfile.usWindows10();
        
        // 2. Configurar Chrome
        ChromeOptions options = new ChromeOptions();
        options.setFingerprintProfile(profile);
        options.addArgument("--window-size=1280,1024");
        
        // 3. Lançar Browser
        Chrome.create(options).thenCompose(driver -> {
            System.out.println("🌐 Navegando para CreepJS...");
            return driver.get("https://abrahamjuliot.github.io/creepjs/", true)
                .thenCompose(v -> {
                    System.out.println("⏳ Aguardando análise (30s)...");
                    return CompletableFuture.runAsync(() -> {
                        try { TimeUnit.SECONDS.sleep(30); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
                    });
                })
                .thenCompose(v -> {
                    System.out.println("📸 Capturando screenshot...");
                    String path = new File("creepjs_result.png").getAbsolutePath();
                    return driver.getScreenshotAsFile(path).thenApply(v2 -> path);
                })
                .thenAccept(path -> {
                    System.out.println("📸 Resultado capturado: " + path);
                })
                .exceptionally(ex -> {
                    ex.printStackTrace();
                    return null;
                })
                .thenCompose(v -> {
                    driver.close();
                    return CompletableFuture.completedFuture(null);
                });
        }).join();
        
        System.out.println("✅ Teste concluído.");
    }
}
