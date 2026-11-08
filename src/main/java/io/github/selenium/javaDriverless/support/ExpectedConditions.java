package io.github.selenium.javaDriverless.support;

import io.github.selenium.javaDriverless.Chrome;
import io.github.selenium.javaDriverless.JavaDriverless;
import io.github.selenium.javaDriverless.types.By;
import io.github.selenium.javaDriverless.types.Target;
import io.github.selenium.javaDriverless.types.WebElement;

import java.util.List;
import java.util.function.Function;

/**
 * Condições comuns para uso com WebDriverWait
 */
public class ExpectedConditions {
    private static Chrome asChrome(Object driver) {
        if (driver instanceof Chrome) {
            return (Chrome) driver;
        }
        if (driver instanceof JavaDriverless) {
            return ((JavaDriverless) driver).getChrome();
        }
        return null;
    }

    /**
     * Restaura o flag de interrupção da thread quando a exceção é InterruptedException.
     * Sem isso, o Thread.sleep do WebDriverWait não percebe a interrupção e o polling continua.
     */
    private static void restaurarInterrupcao(Exception e) {
        if (e instanceof InterruptedException) {
            Thread.currentThread().interrupt();
        }
    }

    /**
     * Aguarda até o título da página conter o texto especificado
     */
    public static Function<Object, Boolean> titleContains(String title) {
        return driver -> {
            try {
                Chrome chrome = asChrome(driver);
                if (chrome != null) {
                    String currentTitle = chrome.getCurrentTarget()
                        .thenCompose(Target::getTitle).get();
                    return currentTitle != null && currentTitle.contains(title);
                }
                return false;
            } catch (Exception e) {
                restaurarInterrupcao(e);
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
                Chrome chrome = asChrome(driver);
                if (chrome != null) {
                    String currentTitle = chrome.getCurrentTarget()
                        .thenCompose(Target::getTitle).get();
                    return title.equals(currentTitle);
                }
                return false;
            } catch (Exception e) {
                restaurarInterrupcao(e);
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
                Chrome chrome = asChrome(driver);
                if (chrome != null) {
                    String currentUrl = chrome.getCurrentUrl().get();
                    return currentUrl != null && currentUrl.contains(url);
                }
                return false;
            } catch (Exception e) {
                restaurarInterrupcao(e);
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
                Chrome chrome = asChrome(driver);
                if (chrome != null) {
                    String currentUrl = chrome.getCurrentUrl().get();
                    return url.equals(currentUrl);
                }
                return false;
            } catch (Exception e) {
                restaurarInterrupcao(e);
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
                Chrome chrome = asChrome(driver);
                if (chrome != null) {
                    String currentUrl = chrome.getCurrentUrl().get();
                    return currentUrl != null && currentUrl.matches(regex);
                }
                return false;
            } catch (Exception e) {
                restaurarInterrupcao(e);
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
                Chrome chrome = asChrome(driver);
                if (chrome != null) {
                    Object result = chrome.executeScript(script, null, false).get();
                    if (result instanceof Boolean) {
                        return (Boolean) result;
                    }
                }
                return false;
            } catch (Exception e) {
                restaurarInterrupcao(e);
                return false;
            }
        };
    }

    /**
     * Aguarda até elemento existir. 100% NATIVO (DOM.querySelectorAll via findElements) — sem JS.
     */
    public static Function<Object, Boolean> presenceOfElement(String selector) {
        return driver -> firstElement(driver, selector) != null;
    }

    /**
     * Aguarda até elemento estar visível. 100% NATIVO (isDisplayed via CSS.getComputedStyleForNode +
     * DOM.getBoxModel) — sem getComputedStyle por JS.
     */
    public static Function<Object, Boolean> visibilityOfElement(String selector) {
        return driver -> {
            try {
                WebElement el = firstElement(driver, selector);
                return el != null && el.isDisplayed().get();
            } catch (Exception e) {
                restaurarInterrupcao(e);
                return false;
            }
        };
    }

    /**
     * Aguarda até elemento ser clicável (visível e habilitado). 100% NATIVO (isDisplayed +
     * isEnabled via Accessibility) — sem JS.
     */
    public static Function<Object, Boolean> elementToBeClickable(String selector) {
        return driver -> {
            try {
                WebElement el = firstElement(driver, selector);
                return el != null && el.isDisplayed().get() && el.isEnabled().get();
            } catch (Exception e) {
                restaurarInterrupcao(e);
                return false;
            }
        };
    }

    /**
     * Aguarda até texto estar presente no elemento. 100% NATIVO (getText via DOM.describeNode) — sem JS.
     */
    public static Function<Object, Boolean> textToBePresentInElement(String selector, String text) {
        return driver -> {
            try {
                WebElement el = firstElement(driver, selector);
                if (el == null) {
                    return false;
                }
                String content = el.getText().get();
                return content != null && content.contains(text);
            } catch (Exception e) {
                restaurarInterrupcao(e);
                return false;
            }
        };
    }

    /**
     * Primeiro elemento que casa o seletor CSS, ou null. Single-shot nativo (timeout 0) — o
     * WebDriverWait externo cuida do polling. Sem injeção de JS.
     */
    private static WebElement firstElement(Object driver, String selector) {
        try {
            Chrome chrome = asChrome(driver);
            if (chrome == null) {
                return null;
            }
            Target target = chrome.getCurrentTarget().get();
            List<WebElement> els = target.findElements(By.CSS_SELECTOR, selector, 0f).get();
            return els.isEmpty() ? null : els.get(0);
        } catch (Exception e) {
            restaurarInterrupcao(e);
            return null;
        }
    }

    /**
     * Aguarda até número de janelas ser o especificado
     */
    public static Function<Object, Boolean> numberOfWindowsToBe(int expectedWindows) {
        return driver -> {
            try {
                Chrome chrome = asChrome(driver);
                if (chrome != null) {
                    int windows = chrome.getTargets().get().size();
                    return windows == expectedWindows;
                }
                return false;
            } catch (Exception e) {
                restaurarInterrupcao(e);
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

