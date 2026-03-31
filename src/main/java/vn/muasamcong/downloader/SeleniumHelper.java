package vn.muasamcong.downloader;

import io.github.bonigarcia.wdm.WebDriverManager;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;
import org.openqa.selenium.By;
import org.openqa.selenium.ElementClickInterceptedException;
import org.openqa.selenium.JavascriptExecutor;
import org.openqa.selenium.PageLoadStrategy;
import org.openqa.selenium.TimeoutException;
import org.openqa.selenium.WebDriver;
import org.openqa.selenium.WebElement;
import org.openqa.selenium.chrome.ChromeDriver;
import org.openqa.selenium.chrome.ChromeOptions;
import org.openqa.selenium.support.ui.ExpectedConditions;
import org.openqa.selenium.support.ui.WebDriverWait;

public final class SeleniumHelper {

    private SeleniumHelper() {
    }

    public static WebDriver createDriver(Path downloadDirectory) {
        Utils.ensureDirectory(downloadDirectory);
        WebDriverManager.chromedriver().setup();

        Map<String, Object> prefs = new HashMap<>();
        prefs.put("download.default_directory", downloadDirectory.toAbsolutePath().toString());
        prefs.put("download.prompt_for_download", false);
        prefs.put("download.directory_upgrade", true);
        prefs.put("profile.default_content_settings.popups", 0);
        prefs.put("profile.default_content_setting_values.notifications", 2);
        prefs.put("profile.default_content_setting_values.automatic_downloads", 1);
        prefs.put("plugins.always_open_pdf_externally", true);
        prefs.put("safebrowsing.enabled", true);

        ChromeOptions options = new ChromeOptions();
        options.setExperimentalOption("prefs", prefs);
        options.setPageLoadStrategy(PageLoadStrategy.EAGER);
        options.addArguments("--disable-notifications");
        options.addArguments("--disable-popup-blocking");
        options.addArguments("--disable-features=ExternalProtocolDialog");
        options.addArguments("--disable-session-crashed-bubble");
        options.addArguments("--no-first-run");
        options.addArguments("--no-default-browser-check");
        options.addArguments("--remote-allow-origins=*");

        return new ChromeDriver(options);
    }

    public static void openUrlRobust(WebDriver driver, String url, Duration timeout) {
        TimeoutException lastError = null;

        for (int attempt = 1; attempt <= 3; attempt++) {
            try {
                driver.navigate().to(url);
                WebDriverWait wait = new WebDriverWait(driver, timeout);
                wait.until(d -> {
                    String current = d.getCurrentUrl();
                    if (current == null || current.isBlank()) {
                        return false;
                    }
                    String lowered = current.toLowerCase();
                    return lowered.contains("muasamcong.mpi.gov.vn") || lowered.contains("render=detail-v2");
                });
                return;
            } catch (TimeoutException ex) {
                lastError = ex;
                try {
                    ((JavascriptExecutor) driver).executeScript("window.location.href = arguments[0];", url);
                } catch (Exception ignored) {
                    // continue retry loop
                }
            }
        }

        if (lastError != null) {
            throw lastError;
        }
    }

    public static WebElement waitVisible(WebDriver driver, By locator, Duration timeout) {
        WebDriverWait wait = new WebDriverWait(driver, timeout);
        return wait.until(ExpectedConditions.visibilityOfElementLocated(locator));
    }

    public static WebElement waitClickable(WebDriver driver, By locator, Duration timeout) {
        WebDriverWait wait = new WebDriverWait(driver, timeout);
        return wait.until(ExpectedConditions.elementToBeClickable(locator));
    }

    public static void waitForAny(WebDriver driver, List<By> locators, Duration timeout) {
        WebDriverWait wait = new WebDriverWait(driver, timeout);
        wait.until(current -> {
            for (By locator : locators) {
                if (!current.findElements(locator).isEmpty()) {
                    return true;
                }
            }
            return false;
        });
    }

    public static void safeClick(WebDriver driver, WebElement element) {
        try {
            element.click();
        } catch (ElementClickInterceptedException ex) {
            ((JavascriptExecutor) driver).executeScript("arguments[0].click();", element);
        }
    }

    public static void safeClick(WebDriver driver, By locator, Duration timeout) {
        WebElement element = waitClickable(driver, locator, timeout);
        safeClick(driver, element);
    }

    public static void dismissPopupIfPresent(WebDriver driver) {
        List<By> closeButtons = new ArrayList<>();
        closeButtons.add(By.cssSelector("#popup-close"));
        closeButtons.add(By.cssSelector(".notification-popup .close"));
        closeButtons.add(By.cssSelector("[aria-label='Close']"));
        closeButtons.add(By.cssSelector("[aria-label='close']"));

        for (By closeButton : closeButtons) {
            try {
                List<WebElement> elements = driver.findElements(closeButton);
                if (!elements.isEmpty() && elements.get(0).isDisplayed()) {
                    safeClick(driver, elements.get(0));
                }
            } catch (Exception ignored) {
                // Ignore and continue with next fallback locator.
            }
        }
    }

    public static boolean switchToNewestWindowIfNeeded(WebDriver driver, Duration timeout) {
        String currentHandle = driver.getWindowHandle();
        WebDriverWait wait = new WebDriverWait(driver, timeout);

        try {
            wait.until(d -> d.getWindowHandles().size() > 1);
            Set<String> handles = driver.getWindowHandles();
            for (String handle : handles) {
                if (!handle.equals(currentHandle)) {
                    driver.switchTo().window(handle);
                    String url = safeCurrentUrl(driver);
                    if (isRelevantMuasamcongUrl(url)) {
                        return true;
                    }

                    if (isDisposableWindow(url) && handles.size() > 1) {
                        driver.close();
                    }
                }
            }
        } catch (TimeoutException ignored) {
            return false;
        }

        driver.switchTo().window(currentHandle);
        return false;
    }

    public static void normalizeWindows(WebDriver driver, Predicate<String> preferredUrl) {
        String original = driver.getWindowHandle();
        String fallbackHandle = original;

        for (String handle : driver.getWindowHandles()) {
            driver.switchTo().window(handle);
            String url = safeCurrentUrl(driver);

            if (isDisposableWindow(url) && driver.getWindowHandles().size() > 1) {
                driver.close();
                continue;
            }

            if (preferredUrl != null && preferredUrl.test(url)) {
                fallbackHandle = handle;
            }
        }

        driver.switchTo().window(fallbackHandle);
    }

    public static boolean isDisposableCurrentUrl(WebDriver driver) {
        return isDisposableWindow(safeCurrentUrl(driver));
    }

    public static boolean isRelevantCurrentUrl(WebDriver driver) {
        return isRelevantMuasamcongUrl(safeCurrentUrl(driver));
    }

    public static void closeExtraWindows(WebDriver driver) {
        String mainHandle = driver.getWindowHandle();
        for (String handle : driver.getWindowHandles()) {
            if (!handle.equals(mainHandle)) {
                driver.switchTo().window(handle);
                driver.close();
            }
        }
        driver.switchTo().window(mainHandle);
    }

    private static String safeCurrentUrl(WebDriver driver) {
        try {
            String url = driver.getCurrentUrl();
            return url == null ? "" : url;
        } catch (Exception ex) {
            return "";
        }
    }

    private static boolean isRelevantMuasamcongUrl(String url) {
        String lowered = url.toLowerCase();
        return lowered.contains("muasamcong.mpi.gov.vn") || lowered.contains("render=detail-v2");
    }

    private static boolean isDisposableWindow(String url) {
        String lowered = url.toLowerCase();
        return lowered.startsWith("data:") || lowered.startsWith("about:blank");
    }
}
