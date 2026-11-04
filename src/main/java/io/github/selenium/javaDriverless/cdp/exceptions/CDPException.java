package io.github.selenium.javaDriverless.cdp.exceptions;

/**
 * Exceção base para erros relacionados ao Chrome DevTools Protocol (CDP).
 * <p>
 * Esta exceção encapsula erros que ocorrem durante a comunicação com o Chrome
 * via CDP. Erros recebidos do protocolo são reduzidos a código, classificação e
 * mensagem canônica segura antes de atravessarem esta camada.
 * </p>
 */
public class CDPException extends RuntimeException {

    /** Classificação segura dos erros de protocolo que alteram o fluxo do cliente. */
    public enum Kind {
        NOT_ATTACHED_TO_ACTIVE_PAGE("Not attached to an active page"),
        TOP_LEVEL_TARGET_REQUIRED("Command can only be executed on top-level targets"),
        ATTACHED_SESSION_NOT_FOUND("Sessão CDP anexada não encontrada"),
        STALE_CONTEXT("Contexto de execução JavaScript não existe mais"),
        COMMAND_REJECTED("Comando CDP rejeitado"),
        UNCLASSIFIED(null);

        private final String safeMessage;

        Kind(String safeMessage) {
            this.safeMessage = safeMessage;
        }
    }

    private final int code;
    private final String cdpMessage;
    private final Kind kind;
    
    /**
     * Cria uma nova exceção CDP.
     *
     * @param code código de erro CDP
     * @param message mensagem de erro CDP
     */
    public CDPException(int code, String message) {
        this(code, message, null, Kind.UNCLASSIFIED);
    }
    
    /**
     * Cria uma nova exceção CDP com causa.
     *
     * @param code código de erro CDP
     * @param message mensagem de erro CDP
     * @param cause causa da exceção
     */
    public CDPException(int code, String message, Throwable cause) {
        this(code, message, cause, Kind.UNCLASSIFIED);
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
        this.kind = Kind.UNCLASSIFIED;
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
        this.kind = Kind.UNCLASSIFIED;
    }

    private CDPException(int code, String message, Throwable cause, Kind kind) {
        super(String.format("CDP Error (code: %d): %s", code, message), cause);
        this.code = code;
        this.cdpMessage = message;
        this.kind = kind;
    }

    /**
     * Constrói um erro a partir do protocolo sem reter a mensagem bruta recebida.
     * Apenas mensagens conhecidas influenciam o fluxo; todo o restante vira erro genérico.
     */
    public static CDPException fromProtocolError(int code, String protocolMessage) {
        Kind kind = classify(code, protocolMessage);
        return new CDPException(code, kind.safeMessage, null, kind);
    }

    private static Kind classify(int code, String protocolMessage) {
        if (code == -32001) {
            return Kind.ATTACHED_SESSION_NOT_FOUND;
        }
        if (code == -32000 && protocolMessage != null) {
            String normalized = protocolMessage.trim();
            if (Kind.NOT_ATTACHED_TO_ACTIVE_PAGE.safeMessage.equals(normalized)) {
                return Kind.NOT_ATTACHED_TO_ACTIVE_PAGE;
            }
            if (Kind.TOP_LEVEL_TARGET_REQUIRED.safeMessage.equals(normalized)) {
                return Kind.TOP_LEVEL_TARGET_REQUIRED;
            }
            if ("Cannot find context with specified id".equals(normalized)
                    || "Could not find object with given id".equals(normalized)
                    || "Execution context was destroyed.".equals(normalized)
                    || "Inspected target navigated or closed".equals(normalized)) {
                return Kind.STALE_CONTEXT;
            }
        }
        return Kind.COMMAND_REJECTED;
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

    /** Retorna a classificação segura do erro, sem expor o texto bruto do protocolo. */
    public Kind getKind() {
        return kind;
    }

    /** Verifica uma classificação sem depender de mensagens destinadas a diagnóstico. */
    public boolean hasKind(Kind expectedKind) {
        return kind == expectedKind;
    }
}

