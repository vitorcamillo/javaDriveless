package io.github.selenium.javaDriverless.cdp.exceptions;

/**
 * Exceção base para erros relacionados ao Chrome DevTools Protocol (CDP).
 * <p>
 * Esta exceção encapsula erros que ocorrem durante a comunicação com o Chrome
 * via CDP, incluindo o código de erro e mensagem retornados pelo Chrome.
 * </p>
 */
public class CDPException extends RuntimeException {
    
    private final int code;
    private final String cdpMessage;
    
    /**
     * Cria uma nova exceção CDP.
     *
     * @param code código de erro CDP
     * @param message mensagem de erro CDP
     */
    public CDPException(int code, String message) {
        super(String.format("CDP Error (code: %d): %s", code, message));
        this.code = code;
        this.cdpMessage = message;
    }
    
    /**
     * Cria uma nova exceção CDP com causa.
     *
     * @param code código de erro CDP
     * @param message mensagem de erro CDP
     * @param cause causa da exceção
     */
    public CDPException(int code, String message, Throwable cause) {
        super(String.format("CDP Error (code: %d): %s", code, message), cause);
        this.code = code;
        this.cdpMessage = message;
    }
    
    /**
     * Cria uma nova exceção CDP apenas com mensagem.
     *
     * @param message mensagem de erro
     */
    public CDPException(String message) {
        super(message);
        this.code = -1;
        this.cdpMessage = message;
    }
    
    /**
     * Cria uma nova exceção CDP com mensagem e causa.
     *
     * @param message mensagem de erro
     * @param cause causa da exceção
     */
    public CDPException(String message, Throwable cause) {
        super(message, cause);
        this.code = -1;
        this.cdpMessage = message;
    }
    
    /**
     * Retorna o código de erro CDP.
     *
     * @return código de erro, ou -1 se não houver código específico
     */
    public int getCode() {
        return code;
    }
    
    /**
     * Retorna a mensagem de erro CDP.
     *
     * @return mensagem de erro CDP
     */
    public String getCdpMessage() {
        return cdpMessage;
    }
}

