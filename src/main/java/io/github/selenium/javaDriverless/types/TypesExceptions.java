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
            super(exceptionDetails.get("exception").get("description").asText());
            
            this.excId = exceptionDetails.get("exceptionId").asInt();
            this.text = exceptionDetails.get("text").asText();
            this.lineNumber = exceptionDetails.get("lineNumber").asInt();
            this.columnNumber = exceptionDetails.get("columnNumber").asInt();
            
            JsonNode exception = exceptionDetails.get("exception");
            this.type = exception.get("type").asText();
            this.subtype = exception.has("subtype") ? exception.get("subtype").asText() : null;
            this.className = exception.get("className").asText();
            this.description = exception.get("description").asText();
            this.objId = exception.get("objectId").asText();
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

