package examples;

import io.github.selenium.javaDriverless.JavaDriverless;
import io.github.selenium.javaDriverless.types.WebElement;

public class TesteSendKeysDebug {
    public static void main(String[] args) {
        JavaDriverless driver = new JavaDriverless("SendKeysDebug");
        
        System.out.println("Navegando para google.com...");
        driver.get("https://www.google.com");
        driver.sleep(2);
        
        System.out.println("Buscando textarea...");
        WebElement searchBox = driver.findElement("css", "textarea[name='q']");
        
        System.out.println("Testando getQuads()...");
        double[][] quads = searchBox.getQuads().join();
        for (double[] point : quads) {
            System.out.println("[" + point[0] + ", " + point[1] + "]");
        }
        
        System.out.println("Testando getSize() e getLocation()...");
        double[] size = searchBox.getSize().join();
        double[] loc = searchBox.getLocation().join();
        System.out.println("Tamanho: " + size[0] + "x" + size[1]);
        System.out.println("Localização: " + loc[0] + ", " + loc[1]);
        
        System.out.println("Testando clear() e sendKeys()...");
        searchBox.clear().join();
        searchBox.sendKeys("javaDriverless testando...").join();
        
        driver.sleep(3);
        driver.quit();
    }
}
