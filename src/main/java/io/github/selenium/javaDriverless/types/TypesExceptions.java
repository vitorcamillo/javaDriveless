package io.github.selenium.javaDriverless.types;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * Exceções relacionadas aos tipos do Selenium Driverless.
 */
public class TypesExceptions {
    
    /**
     * Exceção lançada quando ocorre um erro ao avaliar JavaScript no navegador.
     */
    public static class JSEvalException extends RuntimeException {
        private final int excId;
        private final String text;
        private final int lineNumber;
        private final int columnNumber;
        private final String type;
        private final String subtype;
        private final String className;
        private final String description;
        private final String objId;
        
        /**
         * Cria uma nova exceção de avaliação JavaScript.
         *
         * @param exceptionDetails detalhes da exceção retornados pelo CDP
         */
        public JSEvalException(JsonNode exceptionDetails) {
            super(mensagem(exceptionDetails));

            this.excId = exceptionDetails.path("exceptionId").asInt();
            this.text = exceptionDetails.path("text").asText("");
            this.lineNumber = exceptionDetails.path("lineNumber").asInt();
            this.columnNumber = exceptionDetails.path("columnNumber").asInt();

            JsonNode exception = exceptionDetails.path("exception");
            this.type = exception.path("type").asText("");
            this.subtype = exception.path("subtype").asText(null);
            this.className = exception.path("className").asText(null);
            this.description = exception.path("description").asText(null);
            this.objId = exception.path("objectId").asText(null);
        }

        /**
         * Mensagem legível a partir dos detalhes da exceção CDP:
         * exception.description, senão exception.value, senão text; "throw 'texto'"
         * chega sem description/className/objectId e não deve gerar NPE.
         */
        private static String mensagem(JsonNode detalhes) {
            JsonNode exception = detalhes.path("exception");
            String description = exception.path("description").asText(null);
            if (description != null) {
                return description;
            }
            JsonNode value = exception.path("value");
            if (!value.isMissingNode() && !value.isNull()) {
                return value.asText();
            }
            String text = detalhes.path("text").asText(null);
            if (text != null) {
                return text;
            }
            return "Erro de JavaScript";
        }
        
        public int getExcId() {
            return excId;
        }
        
        public String getText() {
            return text;
        }
        
        public int getLineNumber() {
            return lineNumber;
        }
        
        public int getColumnNumber() {
            return columnNumber;
        }
        
        public String getType() {
            return type;
        }
        
        public String getSubtype() {
            return subtype;
        }
        
        public String getClassName() {
            return className;
        }
        
        public String getDescription() {
            return description;
        }
        
        public String getObjId() {
            return objId;
        }
        
        @Override
        public String toString() {
            return description;
        }
    }
    
    /**
     * Exceção lançada quando um iframe não pode ser encontrado ou acessado.
     */
    public static class NoSuchIframe extends RuntimeException {
        private final WebElement iframe;
        
        public NoSuchIframe(WebElement iframe, String message) {
            super(message);
            this.iframe = iframe;
        }
        
        public WebElement getIframe() {
            return iframe;
        }
    }
}

