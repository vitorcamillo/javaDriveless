package io.github.selenium.javaDriverless.support;

import io.github.selenium.javaDriverless.Chrome;
import io.github.selenium.javaDriverless.types.Target;

import java.util.function.Function;

/**
 * Condições comuns para uso com WebDriverWait
 */
public class ExpectedConditions {

    /**
     * Aguarda até o título da página conter o texto especificado
     */
    public static Function<Object, Boolean> titleContains(String title) {
        return driver -> {
            try {
                if (driver instanceof Chrome) {
                    String currentTitle = ((Chrome) driver).getCurrentTarget()
                        .thenCompose(Target::getTitle).get();
                    return currentTitle != null && currentTitle.contains(title);
                }
                return false;
            } catch (Exception e) {
                return false;
            }
        };
    }

    /**
     * Aguarda até o título ser exatamente o especificado
     */
    public static Function<Object, Boolean> titleIs(String title) {
        return driver -> {
            try {
                if (driver instanceof Chrome) {
                    String currentTitle = ((Chrome) driver).getCurrentTarget()
                        .thenCompose(Target::getTitle).get();
                    return title.equals(currentTitle);
                }
                return false;
            } catch (Exception e) {
                return false;
            }
        };
    }

    /**
     * Aguarda até a URL conter o texto especificado
     */
    public static Function<Object, Boolean> urlContains(String url) {
        return driver -> {
            try {
                if (driver instanceof Chrome) {
                    String currentUrl = ((Chrome) driver).getCurrentUrl().get();
                    return currentUrl != null && currentUrl.contains(url);
                }
                return false;
            } catch (Exception e) {
                return false;
            }
        };
    }

    /**
     * Aguarda até a URL ser exatamente a especificada
     */
    public static Function<Object, Boolean> urlToBe(String url) {
        return driver -> {
            try {
                if (driver instanceof Chrome) {
                    String currentUrl = ((Chrome) driver).getCurrentUrl().get();
                    return url.equals(currentUrl);
                }
                return false;
            } catch (Exception e) {
                return false;
            }
        };
    }

    /**
     * Aguarda até a URL corresponder ao padrão regex
     */
    public static Function<Object, Boolean> urlMatches(String regex) {
        return driver -> {
            try {
                if (driver instanceof Chrome) {
                    String currentUrl = ((Chrome) driver).getCurrentUrl().get();
                    return currentUrl != null && currentUrl.matches(regex);
                }
                return false;
            } catch (Exception e) {
                return false;
            }
        };
    }

    /**
     * Aguarda até JavaScript retornar true
     */
    public static Function<Object, Boolean> jsReturnsTrue(String script) {
        return driver -> {
            try {
                if (driver instanceof Chrome) {
                    Object result = ((Chrome) driver).executeScript(script, null, false).get();
                    if (result instanceof Boolean) {
                        return (Boolean) result;
                    }
                }
                return false;
            } catch (Exception e) {
                return false;
            }
        };
    }

    /**
     * Aguarda até elemento existir (via JavaScript)
     */
    public static Function<Object, Boolean> presenceOfElement(String selector) {
        String script = String.format(
            "document.querySelector('%s') !== null",
            selector.replace("'", "\\'")
        );
        return jsReturnsTrue(script);
    }

    /**
     * Aguarda até elemento estar visível (via JavaScript)
     */
    public static Function<Object, Boolean> visibilityOfElement(String selector) {
        String script = String.format(
            "(function() {" +
            "  const el = document.querySelector('%s');" +
            "  if (!el) return false;" +
            "  const style = window.getComputedStyle(el);" +
            "  return style.display !== 'none' && style.visibility !== 'hidden' && style.opacity !== '0';" +
            "})()",
            selector.replace("'", "\\'")
        );
        return jsReturnsTrue(script);
    }

    /**
     * Aguarda até elemento ser clicável (visível e habilitado)
     */
    public static Function<Object, Boolean> elementToBeClickable(String selector) {
        String script = String.format(
            "(function() {" +
            "  const el = document.querySelector('%s');" +
            "  if (!el) return false;" +
            "  const style = window.getComputedStyle(el);" +
            "  const visible = style.display !== 'none' && style.visibility !== 'hidden';" +
            "  const enabled = !el.disabled;" +
            "  return visible && enabled;" +
            "})()",
            selector.replace("'", "\\'")
        );
        return jsReturnsTrue(script);
    }

    /**
     * Aguarda até texto estar presente no elemento
     */
    public static Function<Object, Boolean> textToBePresentInElement(String selector, String text) {
        String script = String.format(
            "(function() {" +
            "  const el = document.querySelector('%s');" +
            "  if (!el) return false;" +
            "  return el.textContent.includes('%s');" +
            "})()",
            selector.replace("'", "\\'"),
            text.replace("'", "\\'")
        );
        return jsReturnsTrue(script);
    }

    /**
     * Aguarda até número de janelas ser o especificado
     */
    public static Function<Object, Boolean> numberOfWindowsToBe(int expectedWindows) {
        return driver -> {
            try {
                if (driver instanceof Chrome) {
                    int windows = ((Chrome) driver).getTargets().get().size();
                    return windows == expectedWindows;
                }
                return false;
            } catch (Exception e) {
                return false;
            }
        };
    }

    /**
     * Condição customizada (sempre verdadeira - use para polling simples)
     */
    public static Function<Object, Boolean> customCondition(Function<Object, Boolean> condition) {
        return condition;
    }
}

