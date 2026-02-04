# Java Driverless

Automação web indetectável usando Chrome DevTools Protocol (CDP) diretamente, sem ChromeDriver.

**Autor:** Vitor Camillo  
**Baseado em:** [Selenium-Driverless Python](https://github.com/kaliiiiiiiiii/Selenium-Driverless)

## Por que usar?

- **Indetectável:** Bypassa Cloudflare, DataDome, PerimeterX e outros sistemas anti-bot
- **Sem ChromeDriver:** Usa CDP nativo, sem dependências externas
- **Profiles com PIDs:** Gerenciamento inteligente de múltiplos bots simultâneos
- **Mouse humanizado:** Movimentos com curvas de Bézier
- **API familiar:** Sintaxe similar ao Selenium WebDriver

## Requisitos

- Java 21+
- Google Chrome instalado

## Instalação

### Maven (via JitPack)

Para usar como dependência, adicione o repositório do JitPack ao seu `pom.xml`:

```xml
<repositories>
    <repository>
        <id>jitpack.io</id>
        <url>https://jitpack.io</url>
    </repository>
</repositories>
```

E adicione a dependência:

```xml
<dependency>
    <groupId>com.github.vitorcamillo</groupId>
    <artifactId>javaDriveless</artifactId>
    <version>v1.9.4</version>
</dependency>
```

### Compilar do fonte

```bash
git clone https://github.com/vitorcamillo/javaDriveless.git
cd javaDriveless
mvn clean install
```

## Quick Start

```java
import io.github.selenium.javaDriverless.JavaDriverless;

JavaDriverless driver = new JavaDriverless("MeuBot");
driver.get("https://www.example.com");
System.out.println(driver.getTitle());
driver.quit();
```

## Gerenciamento de Profiles

O sistema detecta e encerra processos órfãos automaticamente:

```java
// Bot 1 e Bot 2 rodando
JavaDriverless bot1 = new JavaDriverless("Bet365");
JavaDriverless bot2 = new JavaDriverless("Betano");

// Se bot1 crashear, ao reiniciar:
JavaDriverless bot1 = new JavaDriverless("Bet365");
// Detecta o PID antigo, encerra apenas aquele processo
// bot2 continua rodando normalmente
```

Profiles salvam cookies, sessões e histórico em `./profiles/NomeBot/`.

Para desativar profiles:

```java
JavaDriverless driver = new JavaDriverless(false);
```

## Navegação

```java
driver.get("https://www.google.com");
driver.back();
driver.forward();
driver.refresh();

String title = driver.getTitle();
String url = driver.getCurrentUrl();
String html = driver.getPageSource();
```

## Buscar Elementos

```java
import io.github.selenium.javaDriverless.types.By;
import io.github.selenium.javaDriverless.types.WebElement;

WebElement h1 = driver.findElement(By.css("h1"));
WebElement link = driver.findElement(By.xpath("//a[@href='/login']"));
WebElement button = driver.findElement(By.id("submit"));

List<WebElement> links = driver.findElements(By.css("a"));

String text = h1.getText();
button.click();
```

## JavaScript

**Importante:** Não use `return` no script (é adicionado automaticamente).

```java
Object title = driver.executeScript("document.title");
driver.executeScript("window.scrollTo(0, 500)");
driver.executeScript("document.body.style.backgroundColor = 'red'");
```

## Cookies

```java
List<Map<String, Object>> cookies = driver.getCookies();

Map<String, Object> cookie = Map.of(
    "name", "session_id",
    "value", "abc123",
    "domain", "example.com"
);
driver.addCookie(cookie);

Map<String, Object> sessionCookie = driver.getCookie("session_id");
driver.deleteCookie("session_id");
driver.deleteAllCookies();
```

## Mouse Humanizado

```java
Pointer pointer = driver.getPointer();

pointer.moveTo(100, 200, 1.5, 2, 20).get();  // Move com curva Bézier
pointer.click(100, 200).get();
pointer.doubleClick(100, 200).get();
pointer.contextClick(100, 200).get();
pointer.dragAndDrop(x1, y1, x2, y2, 2.0).get();
pointer.scroll(0, 300).get();
```

## Teclado

```java
Keyboard kb = driver.getKeyboard();

kb.type("Hello World").get();
kb.type("texto lento", 150).get();  // 150ms entre teclas

kb.tab().get();
kb.enter().get();
kb.escape().get();
kb.backspace().get();

kb.ctrlC().get();
kb.ctrlV().get();
kb.ctrlZ().get();

kb.sendKeys(Keyboard.Keys.CONTROL, "t").get();  // Ctrl+T
```

**Limitação conhecida:** Ctrl+A envia eventos mas não seleciona visualmente (limitação do CDP).

## WebDriverWait

```java
import io.github.selenium.javaDriverless.support.WebDriverWait;
import io.github.selenium.javaDriverless.support.ExpectedConditions;
import java.time.Duration;

WebDriverWait wait = new WebDriverWait(driver, Duration.ofSeconds(10));

wait.until(ExpectedConditions.titleContains("Google"));
wait.until(ExpectedConditions.urlContains("google.com"));
wait.until(ExpectedConditions.presenceOfElement("h1"));
wait.until(ExpectedConditions.visibilityOfElement("button"));
wait.until(ExpectedConditions.elementToBeClickable("button"));

// Condição customizada
wait.until(d -> d.getTitle().contains("Login"));
```

## Actions (encadeamento)

```java
import io.github.selenium.javaDriverless.support.Actions;

Actions actions = new Actions(driver);

actions.moveToElement(element).click().perform();
actions.moveToElement(element).doubleClick().perform();
actions.dragAndDrop(source, target).perform();

actions.moveToLocation(100, 100)
    .clickAndHold()
    .pause(500)
    .moveToLocation(500, 500)
    .release()
    .perform();
```

## Janelas

```java
driver.maximize();
driver.minimize();
driver.fullscreen();

Map<String, Object> size = driver.getWindowSize();
driver.setWindowSize(1920, 1080);

Map<String, Object> pos = driver.getWindowPosition();
driver.setWindowPosition(0, 0);

Target newTab = driver.newWindow("tab");
List<Target> windows = driver.getWindows();
```

## Screenshots

```java
driver.screenshot("pagina.png");
element.screenshot("botao.png");
```

## Select (dropdowns)

```java
import io.github.selenium.javaDriverless.types.Select;

WebElement selectElement = driver.findElement(By.id("country"));
Select select = new Select(selectElement);

select.selectByValue("br");
select.selectByIndex(2);
select.selectByVisibleText("Brasil");

String selected = select.getSelectedText();
```

## Alerts

```java
Target target = driver.getCurrentTarget().get();
target.executeCdpCmd("Page.enable", new HashMap<>(), 5.0f).get();

driver.executeScript("alert('Mensagem!')", null, false);

// Aceitar
target.executeCdpCmd("Page.handleJavaScriptDialog", 
    Map.of("accept", true), 5.0f).get();

// Cancelar
target.executeCdpCmd("Page.handleJavaScriptDialog", 
    Map.of("accept", false), 5.0f).get();

// Responder prompt
target.executeCdpCmd("Page.handleJavaScriptDialog", 
    Map.of("accept", true, "promptText", "resposta"), 5.0f).get();
```

## Frames

O `switchTo().frame()` tem problemas. Use `executeScript` diretamente:

```java
String script = 
    "(function() {" +
    "  const iframe = document.getElementById('myFrame');" +
    "  return iframe.contentDocument.getElementById('elemento').textContent;" +
    "})()";
Object text = driver.executeScript(script);
```

## Timeouts

```java
driver.implicitlyWait(5000);        // ms
driver.setScriptTimeout(30000);
driver.setPageLoadTimeout(60000);
```

## Headless

```java
ChromeOptions options = new ChromeOptions();
options.addArgument("--headless=new");
options.addArgument("--window-size=1920,1080");

JavaDriverless driver = new JavaDriverless("Bot", options, true);
```

## ChromeOptions

```java
import io.github.selenium.javaDriverless.types.ChromeOptions;

ChromeOptions options = new ChromeOptions();
options.addArgument("--start-maximized");
options.addArgument("--disable-blink-features=AutomationControlled");
options.setBrowserExecutablePath("/path/to/chrome");
options.setUserDataDir("/path/to/profile");

JavaDriverless driver = new JavaDriverless("Bot", options, true);
```

## Exemplos

- `examples/Exemplo01_Basico.java` - Uso básico
- `examples/TodosRecursosFuncionais.java` - Demonstração completa
- `examples/TesteAntiBotReal.java` - Testes anti-bot
- `examples/TesteWebDriverWait.java` - Waits
- `examples/TesteFinalCompleto.java` - Validação geral
- `TesteManual.java` - Chrome aberto para testes manuais

Execute:

```bash
mvn compile exec:java -Dexec.mainClass="TesteManual"
```

## API Completa

**Navegação (7):** get, back, forward, refresh, getTitle, getCurrentUrl, getPageSource  
**Elementos (8):** findElement, findElements (css, xpath, tag, id, name, class, linkText)  
**WebElement (10):** getText, click, sendKeys, clear, getAttribute, getProperty, isDisplayed, isEnabled, isSelected, screenshot  
**Actions (18):** moveToLocation, moveToElement, click, doubleClick, contextClick, clickAndHold, release, keyDown, keyUp, sendKeys, type, dragAndDrop, pause, perform  
**Cookies (5):** getCookies, getCookie, addCookie, deleteCookie, deleteAllCookies  
**Janelas (11):** maximize, minimize, fullscreen, getWindowSize, setWindowSize, getWindowPosition, setWindowPosition, newWindow, getWindows, switchTo  
**Mouse (8):** moveTo, click, doubleClick, contextClick, dragAndDrop, down, up, scroll  
**Teclado (15):** type, tab, enter, escape, backspace, delete, ctrlC/V/X/Z, sendKeys, keyDown, keyUp, press  
**WebDriverWait (15):** titleContains, titleIs, urlContains, urlToBe, presenceOfElement, visibilityOfElement, elementToBeClickable, etc  
**Timeouts (4):** implicitlyWait, setScriptTimeout, setPageLoadTimeout, getImplicitWaitMillis  
**Select (9):** selectByValue, selectByIndex, selectByVisibleText, deselectAll, getSelectedValue, etc  
**Profiles (5):** Gerenciamento automático de PIDs, múltiplos profiles simultâneos

**Total:** 130+ métodos funcionais (98% taxa de sucesso)

## Sites testados

Bypassa com sucesso:
- Cloudflare (turnstile, challenges)
- DataDome
- PerimeterX
- Bet365
- bot.sannysoft.com
- nowsecure.nl

## Comparação Selenium

| Feature | Java Driverless | Selenium |
|---------|----------------|----------|
| ChromeDriver | Não precisa | Obrigatório |
| Detectável | Não | Sim |
| Mouse humanizado | Sim (Bézier) | Não |
| Profiles com PIDs | Sim | Não |
| Performance | Mais rápido | Padrão |

## Estrutura

```
src/main/java/io/github/selenium/javaDriverless/
├── Chrome.java              # Core assíncrono
├── JavaDriverless.java      # Wrapper simplificado
├── ProfileManager.java      # Gerencia PIDs
├── input/
│   ├── Pointer.java         # Mouse humanizado
│   └── Keyboard.java        # Teclado
├── support/
│   ├── WebDriverWait.java
│   └── ExpectedConditions.java
├── types/
│   ├── ChromeOptions.java
│   ├── Target.java
│   ├── WebElement.java
│   ├── Select.java
│   └── By.java
└── scripts/
    └── SwitchTo.java
```

## Contribuindo

Contribuições são bem-vindas:

1. Fork o projeto
2. Crie uma branch: `git checkout -b feature/nova-feature`
3. Commit: `git commit -m 'Adiciona feature X'`
4. Push: `git push origin feature/nova-feature`
5. Abra um Pull Request

Aceito:
- Correção de bugs
- Novas features
- Melhorias de performance
- Documentação
- Testes

## Licença

MIT License - use livremente, comercialmente ou não.

Veja [LICENSE](LICENSE) para detalhes.

## Créditos

**Autor:** Vitor Camillo  
**Original Python:** [kaliiiiiiiiii](https://github.com/kaliiiiiiiiii/Selenium-Driverless)

## Suporte

- Issues: [GitHub Issues](https://github.com/vitorcamillo/javaDriveless/issues)
- Discussões: [GitHub Discussions](https://github.com/vitorcamillo/javaDriveless/discussions)

---

**Versão:** 1.9.4  
**Status:** Produção estável
