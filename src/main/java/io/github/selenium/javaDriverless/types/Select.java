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

import java.util.List;
import java.util.Map;

/**
 * Manipula elementos SELECT (dropdowns) — 100% CDP NATIVO (zero injeção de JS). Antes cada operação
 * rodava executeScript (select.selectedIndex = i / dispatchEvent), detectável por anti-bots. Agora:
 * options via findElements nativo, estado selecionado via Accessibility, e a SELEÇÃO é feita por
 * foco (DOM.focus) + teclado físico (Home/ArrowDown via Input.dispatchKeyEvent), disparando 'change'
 * REAL — não setando selectedIndex sinteticamente.
 *
 * <p>Exemplo:</p>
 * <pre>
 * Select select = new Select(driver.findElement(By.id("mySelect")));
 * select.selectByValue("option1");
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

        String tagName = element.getTagName().join().toLowerCase();
        if (!"select".equals(tagName)) {
            throw new IllegalArgumentException(
                "Elemento não é um <select>. Tag encontrada: " + tagName
            );
        }
    }

    /** Seleciona a opção cujo atributo value casa. */
    public void selectByValue(String value) {
        List<WebElement> opts = options();
        for (int i = 0; i < opts.size(); i++) {
            if (value.equals(opts.get(i).getAttribute("value").join())) {
                navigateTo(i);
                return;
            }
        }
        throw new RuntimeException("Opção com value '" + value + "' não encontrada");
    }

    /** Seleciona a opção pelo índice (começa em 0). */
    public void selectByIndex(int index) {
        int count = getOptionsCount();
        if (index < 0 || index >= count) {
            throw new RuntimeException("Índice " + index + " fora do range (0.." + (count - 1) + ")");
        }
        navigateTo(index);
    }

    /** Seleciona a opção pelo texto visível. */
    public void selectByVisibleText(String text) {
        List<WebElement> opts = options();
        for (int i = 0; i < opts.size(); i++) {
            if (text.trim().equals(opts.get(i).getText().join().trim())) {
                navigateTo(i);
                return;
            }
        }
        throw new RuntimeException("Opção com texto '" + text + "' não encontrada");
    }

    /**
     * Desmarca todas as opções (só &lt;select multiple&gt;). NÃO suportado em modo stealth: exige
     * Ctrl+click por option (frágil, dependente de layout) e leitura do estado 'selected' vivo.
     */
    public void deselectAll() {
        throw new UnsupportedOperationException(
            "deselectAll em <select multiple> nao e suportado em modo stealth (sem JS).");
    }

    /** Desmarca uma opção pelo value (só &lt;select multiple&gt;). NÃO suportado em modo stealth. */
    public void deselectByValue(String value) {
        throw new UnsupportedOperationException(
            "deselectByValue em <select multiple> nao e suportado em modo stealth (sem JS).");
    }

    /** value da opção selecionada (mapeia o texto selecionado do AX para o value da option). null se nenhuma. */
    public String getSelectedValue() {
        String selText = getSelectedText();
        if (selText == null) {
            return null;
        }
        for (WebElement opt : options()) {
            if (selText.equals(opt.getText().join().trim())) {
                return opt.getAttribute("value").join();
            }
        }
        return null;
    }

    /** Texto da opção selecionada, via Accessibility (value do &lt;select&gt;). null se nenhuma. */
    public String getSelectedText() {
        String v = WebElementAttributes.axValue(element).join();
        return (v == null || v.isEmpty()) ? null : v.trim();
    }

    /** true se é &lt;select multiple&gt; (atributo 'multiple' presente). */
    public boolean isMultiple() {
        return element.getAttribute("multiple").join() != null;
    }

    /** Número de options (querySelectorAll nativo). */
    public int getOptionsCount() {
        return options().size();
    }

    // ---------- privados (nativos) ----------

    private List<WebElement> options() {
        return element.findElements(By.TAG_NAME, "option").join();
    }

    // Foca o <select> e navega ao índice por teclado físico: Home (topo) + ArrowDown x index. Cada
    // seta muda selectedIndex e dispara 'change' REAL — sem setar selectedIndex por JS.
    private void navigateTo(int index) {
        target.executeCdpCmd("DOM.focus", Map.of("nodeId", element.getNodeId().join()), 5f).join();
        pressKey("Home", 36);
        for (int i = 0; i < index; i++) {
            pressKey("ArrowDown", 40);
        }
    }

    private void pressKey(String key, int vk) {
        Map<String, Object> down = Map.of("type", "keyDown", "key", key,
            "windowsVirtualKeyCode", vk, "nativeVirtualKeyCode", vk);
        Map<String, Object> up = Map.of("type", "keyUp", "key", key,
            "windowsVirtualKeyCode", vk, "nativeVirtualKeyCode", vk);
        target.executeCdpCmd("Input.dispatchKeyEvent", down, 5f).join();
        target.executeCdpCmd("Input.dispatchKeyEvent", up, 5f).join();
    }
}
