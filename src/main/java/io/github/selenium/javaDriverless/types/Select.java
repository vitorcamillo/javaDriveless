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

package io.github.selenium.javaDriverless.types;

import java.util.ArrayList;
import java.util.List;

/**
 * Classe para manipular elementos SELECT (dropdowns).
 * 
 * <p>Exemplo de uso:</p>
 * <pre>
 * WebElement selectElement = driver.findElement(By.id("mySelect"));
 * Select select = new Select(selectElement);
 * select.selectByValue("option1");
 * select.selectByIndex(0);
 * select.selectByVisibleText("Opção 1");
 * </pre>
 */
public class Select {
    
    private final WebElement element;
    private final Target target;
    
    /**
     * Cria uma instância Select para manipular um elemento &lt;select&gt;.
     * 
     * @param element o WebElement que representa o &lt;select&gt;
     * @throws IllegalArgumentException se o elemento não for um &lt;select&gt;
     */
    public Select(WebElement element) {
        this.element = element;
        this.target = element.getTarget();
        
        // Verificar se é um select
        String tagName = element.getTagName().join().toLowerCase();
        if (!"select".equals(tagName)) {
            throw new IllegalArgumentException(
                "Elemento não é um <select>. Tag encontrada: " + tagName
            );
        }
    }
    
    /**
     * Seleciona uma opção pelo atributo value.
     * 
     * @param value o valor do atributo value da option
     */
    public void selectByValue(String value) {
        String script = 
            "const select = arguments[0];" +
            "const targetValue = arguments[1];" +
            "let found = false;" +
            "for (let i = 0; i < select.options.length; i++) {" +
            "    if (select.options[i].value === targetValue) {" +
            "        select.selectedIndex = i;" +
            "        select.dispatchEvent(new Event('change', { bubbles: true }));" +
            "        found = true;" +
            "        break;" +
            "    }" +
            "}" +
            "found";
        
        try {
            Object result = target.executeScript(script, new Object[]{element, value}, false).join();
            if (!(result instanceof Boolean) || !(Boolean) result) {
                throw new RuntimeException("Opção com value '" + value + "' não encontrada");
            }
        } catch (Exception e) {
            throw new RuntimeException("Não foi possível selecionar opção com value: " + value, e);
        }
    }
    
    /**
     * Seleciona uma opção pelo índice.
     * 
     * @param index índice da opção (começa em 0)
     */
    public void selectByIndex(int index) {
        String script = 
            "const select = arguments[0];" +
            "const index = arguments[1];" +
            "if (index >= 0 && index < select.options.length) {" +
            "    select.selectedIndex = index;" +
            "    select.dispatchEvent(new Event('change', { bubbles: true }));" +
            "    true;" +
            "} else {" +
            "    false;" +
            "}";
        
        try {
            Object result = target.executeScript(script, new Object[]{element, index}, false).join();
            if (!(result instanceof Boolean) || !(Boolean) result) {
                throw new RuntimeException("Índice " + index + " fora do range");
            }
        } catch (Exception e) {
            throw new RuntimeException("Não foi possível selecionar opção no índice: " + index, e);
        }
    }
    
    /**
     * Seleciona uma opção pelo texto visível.
     * 
     * @param text o texto visível da opção
     */
    public void selectByVisibleText(String text) {
        String script = 
            "const select = arguments[0];" +
            "const targetText = arguments[1];" +
            "let found = false;" +
            "for (let i = 0; i < select.options.length; i++) {" +
            "    if (select.options[i].text.trim() === targetText.trim()) {" +
            "        select.selectedIndex = i;" +
            "        select.dispatchEvent(new Event('change', { bubbles: true }));" +
            "        found = true;" +
            "        break;" +
            "    }" +
            "}" +
            "found";
        
        try {
            Object result = target.executeScript(script, new Object[]{element, text}, false).join();
            if (!(result instanceof Boolean) || !(Boolean) result) {
                throw new RuntimeException("Opção com texto '" + text + "' não encontrada");
            }
        } catch (Exception e) {
            throw new RuntimeException("Não foi possível selecionar opção com texto: " + text, e);
        }
    }
    
    /**
     * Desmarca todas as opções (apenas para SELECT múltiplos).
     */
    public void deselectAll() {
        String script = 
            "const select = arguments[0];" +
            "if (!select.multiple) { false; }" +
            "for (let i = 0; i < select.options.length; i++) {" +
            "    select.options[i].selected = false;" +
            "}" +
            "select.dispatchEvent(new Event('change', { bubbles: true }));" +
            "true";
        
        try {
            Object result = target.executeScript(script, new Object[]{element}, false).join();
            if (!(result instanceof Boolean) || !(Boolean) result) {
                throw new UnsupportedOperationException("Não é possível desmarcar todas as opções de um SELECT simples");
            }
        } catch (Exception e) {
            throw new RuntimeException("Erro ao desmarcar todas as opções", e);
        }
    }
    
    /**
     * Desmarca uma opção pelo value (apenas para SELECT múltiplos).
     * 
     * @param value o valor a desmarcar
     */
    public void deselectByValue(String value) {
        String script = 
            "const select = arguments[0];" +
            "if (!select.multiple) { false; }" +
            "let found = false;" +
            "for (let i = 0; i < select.options.length; i++) {" +
            "    if (select.options[i].value === arguments[1]) {" +
            "        select.options[i].selected = false;" +
            "        select.dispatchEvent(new Event('change', { bubbles: true }));" +
            "        found = true;" +
            "        break;" +
            "    }" +
            "}" +
            "found";
        
        try {
            target.executeScript(script, new Object[]{element, value}, false).join();
        } catch (Exception e) {
            throw new RuntimeException("Erro ao desmarcar opção com value: " + value, e);
        }
    }
    
    /**
     * Retorna o valor selecionado atualmente.
     * 
     * @return valor da opção selecionada, ou null se nenhuma selecionada
     */
    public String getSelectedValue() {
        String script = 
            "const select = arguments[0];" +
            "const selected = select.options[select.selectedIndex];" +
            "selected ? selected.value : null";
        
        try {
            Object result = target.executeScript(script, new Object[]{element}, false).join();
            return result != null ? result.toString() : null;
        } catch (Exception e) {
            return null;
        }
    }
    
    /**
     * Retorna o texto visível da opção selecionada.
     * 
     * @return texto da opção selecionada, ou null se nenhuma selecionada
     */
    public String getSelectedText() {
        String script = 
            "const select = arguments[0];" +
            "const selected = select.options[select.selectedIndex];" +
            "selected ? selected.text.trim() : null";
        
        try {
            Object result = target.executeScript(script, new Object[]{element}, false).join();
            return result != null ? result.toString() : null;
        } catch (Exception e) {
            return null;
        }
    }
    
    /**
     * Verifica se é um SELECT múltiplo.
     * 
     * @return true se permite múltiplas seleções
     */
    public boolean isMultiple() {
        String script = "arguments[0].multiple";
        try {
            Object result = target.executeScript(script, new Object[]{element}, false).join();
            return result instanceof Boolean && (Boolean) result;
        } catch (Exception e) {
            return false;
        }
    }
    
    /**
     * Retorna o número total de opções.
     * 
     * @return quantidade de options no select
     */
    public int getOptionsCount() {
        String script = "arguments[0].options.length";
        try {
            Object result = target.executeScript(script, new Object[]{element}, false).join();
            return result instanceof Number ? ((Number) result).intValue() : 0;
        } catch (Exception e) {
            return 0;
        }
    }
}
