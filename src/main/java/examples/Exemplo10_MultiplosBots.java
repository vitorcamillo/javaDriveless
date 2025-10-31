package examples;

import io.github.selenium.javaDriverless.JavaDriverless;

/**
 * Exemplo 10: Múltiplos bots rodando simultaneamente
 */
public class Exemplo10_MultiplosBots {
    
    public static void main(String[] args) {
        System.out.println("Exemplo 10: Múltiplos bots\n");
        
        // Criar 3 bots diferentes
        System.out.println("Criando 3 bots...\n");
        
        JavaDriverless bot1 = new JavaDriverless("Bot_1");
        JavaDriverless bot2 = new JavaDriverless("Bot_2");
        JavaDriverless bot3 = new JavaDriverless("Bot_3");
        
        System.out.println("✓ 3 Chromes abertos!");
        System.out.println("  Bot 1 PID: " + bot1.getPid());
        System.out.println("  Bot 2 PID: " + bot2.getPid());
        System.out.println("  Bot 3 PID: " + bot3.getPid());
        
        // Cada um navega para um site
        System.out.println("\nNavegando...");
        bot1.get("https://www.google.com");
        bot2.get("https://www.github.com");
        bot3.get("https://www.bing.com");
        
        System.out.println("\nAguardando 5 segundos...");
        bot1.sleep(5);
        
        // Fechar todos
        System.out.println("\nFechando...");
        bot1.quit();
        bot2.quit();
        bot3.quit();
        
        System.out.println("\n✓ Concluído!");
    }
}

