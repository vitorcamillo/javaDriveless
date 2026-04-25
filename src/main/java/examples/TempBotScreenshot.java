package examples;

import io.github.selenium.javaDriverless.JavaDriverless;
import io.github.selenium.javaDriverless.types.ChromeOptions;
import java.io.File;

/**
 * Temporary test to capture bot.sannysoft.com screenshot and results
 */
public class TempBotScreenshot {
    public static void main(String[] args) {
        JavaDriverless driver = null;
        try {
            System.out.println("Loading bot.sannysoft.com for screenshot...");

            ChromeOptions options = new ChromeOptions();
            options.addArgument("--start-maximized");
            options.addArgument("--disable-blink-features=AutomationControlled");

            driver = new JavaDriverless("BotScreenshot", options, true);

            driver.get("https://bot.sannysoft.com");
            Thread.sleep(8000);

            // Take screenshot
            String filename = "/media/mahurley-linux/BackupInterno/javaDriveless/bot_sannysoft_screenshot.png";
            System.out.println("Taking screenshot...");
            driver.screenshot(filename);

            File file = new File(filename);
            if (file.exists()) {
                System.out.println("Screenshot saved: " + filename + " (" + file.length() + " bytes)");
            }

            // Get page text content to see test results
            Object bodyText = driver.executeScript("return document.body.innerText");
            System.out.println("\n=== PAGE CONTENT ===");
            System.out.println(bodyText);

            Thread.sleep(30000);

            driver.quit();
            System.out.println("\nDone!");
        } catch (Exception e) {
            e.printStackTrace();
        }
    }
}