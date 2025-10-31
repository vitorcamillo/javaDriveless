package examples;

import io.github.selenium.driverless.Chrome;
import io.github.selenium.driverless.types.ChromeOptions;

import java.util.concurrent.CompletableFuture;

/**
 * Exemplo de múltiplas abas simultâneas.
 * <p>
 * Demonstra:
 * - Gerenciamento de múltiplas abas
 * - Execução paralela
 * - Movimentos de ponteiro em abas diferentes
 * </p>
 */
public class MultipleTabsExample {
    
    public static void main(String[] args) {
        System.out.println("=== Java Driverless - Múltiplas Abas ===\n");
        
        ChromeOptions options = new ChromeOptions();
        
        Chrome.create(options).thenCompose(driver -> {
            System.out.println("✓ Chrome iniciado!\n");
            
            // Obter primeira aba
            return driver.getCurrentTarget().thenCompose(target1 -> {
                System.out.println("→ Aba 1: Navegando para abrahamjuliot...");
                
                // TODO: Implementar newWindow quando Context estiver completo
                CompletableFuture<Void> aba1 = target1.get("https://abrahamjuliot.github.io/creepjs/", true)
                    .thenCompose(v -> target1.getTitle())
                    .thenAccept(title -> {
                        System.out.println("✓ Aba 1 - Título: " + title);
                    });
                
                return aba1.thenCompose(v -> {
                    System.out.println("\n→ Aguardando 3 segundos...");
                    return driver.sleep(3.0);
                })
                .thenCompose(v -> {
                    System.out.println("\n→ Fechando Chrome...");
                    return driver.quit();
                })
                .thenAccept(v -> {
                    System.out.println("✓ Chrome fechado!");
                    System.out.println("\n=== Exemplo concluído! ===");
                    System.out.println("\nNota: Múltiplas abas requer Context.newWindow()");
                    System.out.println("      que será implementado em versão futura.");
                });
            });
        }).join();
    }
}

