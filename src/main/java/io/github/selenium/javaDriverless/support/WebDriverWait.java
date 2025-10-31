package io.github.selenium.javaDriverless.support;

import java.time.Duration;
import java.util.function.Function;

/**
 * Implementação simples de WebDriverWait para JavaDriverless
 */
public class WebDriverWait {
    private final Object driver;
    private final Duration timeout;
    private Duration pollingInterval = Duration.ofMillis(500);
    private boolean ignoreExceptions = true;

    public WebDriverWait(Object driver, Duration timeout) {
        this.driver = driver;
        this.timeout = timeout;
    }

    public WebDriverWait pollingEvery(Duration interval) {
        this.pollingInterval = interval;
        return this;
    }

    public WebDriverWait ignoring(Class<? extends Throwable> exceptionType) {
        // Simplificação: sempre ignora exceções por padrão
        this.ignoreExceptions = true;
        return this;
    }

    /**
     * Aguarda até que a condição seja satisfeita
     */
    public <T> T until(Function<Object, T> condition) {
        long startTime = System.nanoTime();
        long timeoutNanos = timeout.toNanos();
        
        T lastResult = null;
        Exception lastException = null;
        
        while ((System.nanoTime() - startTime) < timeoutNanos) {
            try {
                lastResult = condition.apply(driver);
                
                // Se resultado é Boolean true, retornar
                if (lastResult instanceof Boolean) {
                    if ((Boolean) lastResult) {
                        return lastResult;
                    }
                }
                // Se resultado não é null e não é false, retornar
                else if (lastResult != null && !Boolean.FALSE.equals(lastResult)) {
                    return lastResult;
                }
                
            } catch (Exception e) {
                if (!ignoreExceptions) {
                    throw new RuntimeException("Erro ao aguardar condição", e);
                }
                lastException = e;
            }
            
            // Aguardar intervalo de polling
            try {
                Thread.sleep(pollingInterval.toMillis());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new RuntimeException("Espera interrompida", e);
            }
        }
        
        // Timeout
        String message = String.format(
            "Timeout após %ds aguardando condição. Último resultado: %s",
            timeout.getSeconds(),
            lastResult
        );
        if (lastException != null) {
            throw new RuntimeException(message, lastException);
        }
        throw new RuntimeException(message);
    }
}

