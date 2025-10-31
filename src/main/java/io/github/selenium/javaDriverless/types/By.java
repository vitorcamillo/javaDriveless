// Licensed to the Software Freedom Conservancy (SFC) under one
// or more contributor license agreements.  See the NOTICE file
// distributed with this work for additional information
// regarding copyright ownership.  The SFC licenses this file
// to you under the Apache License, Version 2.0 (the
// "License"); you may not use this file except in compliance
// with the License.  You may obtain a copy of the License at
//
//   http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing,
// software distributed under the License is distributed on an
// "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
// KIND, either express or implied.  See the License for the
// specific language governing permissions and limitations
// under the License.
//
// Editado por github/kaliiiiiiiiii
// Conversão para Java: Java Driverless
// Todas as modificações são licenciadas sob a licença fornecida em LICENSE.md

package io.github.selenium.javaDriverless.types;

/**
 * Mecanismo para localizar elementos dentro de um documento.
 * <p>
 * Inspirado no Selenium-Driverless Python: https://github.com/kaliiiiiiiiii/Selenium-Driverless
 * </p>
 * 
 * <p>Exemplo de uso:</p>
 * <pre>
 * driver.findElement(By.xpath("//input[@name='q']"));
 * driver.findElement(By.css("input[name='q']"));
 * driver.findElement(By.id("search"));
 * </pre>
 */
public class By {
    
    // Constantes para uso interno (compatibilidade com código existente)
    /** @deprecated Use By.id() ao invés */
    @Deprecated
    public static final String ID = "id";
    
    /** @deprecated Use By.name() ao invés */
    @Deprecated
    public static final String NAME = "name";
    
    /** @deprecated Use By.xpath() ao invés */
    @Deprecated
    public static final String XPATH = "xpath";
    
    /** @deprecated Use By.tagName() ao invés */
    @Deprecated
    public static final String TAG_NAME = "tag name";
    
    /** @deprecated Use By.className() ao invés */
    @Deprecated
    public static final String CLASS_NAME = "class name";
    
    /** @deprecated Use By.cssSelector() ou By.css() ao invés */
    @Deprecated
    public static final String CSS_SELECTOR = "css selector";
    
    /** @deprecated Use By.css() ao invés */
    @Deprecated
    public static final String CSS = "css selector";
    
    private final String strategy;
    private final String value;
    
    /**
     * Construtor privado - use os métodos estáticos.
     */
    private By(String strategy, String value) {
        this.strategy = strategy;
        this.value = value;
    }
    
    /**
     * Retorna a estratégia de localização.
     */
    public String getStrategy() {
        return strategy;
    }
    
    /**
     * Retorna o valor/seletor.
     */
    public String getValue() {
        return value;
    }
    
    /**
     * Localizar por atributo ID.
     * 
     * @param id o ID do elemento
     * @return By instance
     */
    public static By id(String id) {
        return new By("id", id);
    }
    
    /**
     * Localizar por atributo name.
     * 
     * @param name o name do elemento
     * @return By instance
     */
    public static By name(String name) {
        return new By("name", name);
    }
    
    /**
     * Localizar por expressão XPath.
     * 
     * @param xpathExpression a expressão XPath
     * @return By instance
     */
    public static By xpath(String xpathExpression) {
        return new By("xpath", xpathExpression);
    }
    
    /**
     * Localizar por nome da tag.
     * 
     * @param tagName o nome da tag HTML
     * @return By instance
     */
    public static By tagName(String tagName) {
        return new By("tag name", tagName);
    }
    
    /**
     * Localizar por nome da classe CSS.
     * 
     * @param className o nome da classe CSS
     * @return By instance
     */
    public static By className(String className) {
        return new By("class name", className);
    }
    
    /**
     * Localizar por seletor CSS.
     * 
     * @param cssSelector o seletor CSS
     * @return By instance
     */
    public static By cssSelector(String cssSelector) {
        return new By("css selector", cssSelector);
    }
    
    /**
     * Alias para cssSelector (mais curto).
     * 
     * @param cssSelector o seletor CSS
     * @return By instance
     */
    public static By css(String cssSelector) {
        return new By("css selector", cssSelector);
    }
    
    /**
     * Localizar por texto do link (busca por tag 'a' com texto específico).
     * 
     * @param linkText o texto exato do link
     * @return By instance
     */
    public static By linkText(String linkText) {
        String xpath = String.format("//a[text()='%s']", linkText.replace("'", "\\'"));
        return new By("xpath", xpath);
    }
    
    /**
     * Localizar por texto parcial do link.
     * 
     * @param partialLinkText parte do texto do link
     * @return By instance
     */
    public static By partialLinkText(String partialLinkText) {
        String xpath = String.format("//a[contains(text(),'%s')]", partialLinkText.replace("'", "\\'"));
        return new By("xpath", xpath);
    }
    
    @Override
    public String toString() {
        return "By." + strategy + ": " + value;
    }
    
    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        By by = (By) o;
        return strategy.equals(by.strategy) && value.equals(by.value);
    }
    
    @Override
    public int hashCode() {
        return 31 * strategy.hashCode() + value.hashCode();
    }
}
