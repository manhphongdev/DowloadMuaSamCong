package vn.muasamcong.downloader.util;

import io.github.bonigarcia.wdm.WebDriverManager;
import java.io.IOException;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
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
import vn.muasamcong.downloader.store.BrowserProfileConfigStore;

public final class SeleniumHelper {

    private static final Set<WebDriver> MANAGED_DRIVERS =
        Collections.newSetFromMap(new ConcurrentHashMap<WebDriver, Boolean>());
    private static final Map<String, WebDriver> REUSABLE_DRIVERS = new ConcurrentHashMap<>();

    private SeleniumHelper() {
    }

    public static WebDriver createDriver(Path downloadDirectory) {
        return createDriver(downloadDirectory, 1, null);
    }

    public static WebDriver createDriver(Path downloadDirectory, int workerSlot) {
        return createDriver(downloadDirectory, workerSlot, null);
    }

    /**
     * @param profilePrefix optional prefix for the Chrome profile directory name.
     *                      Use different prefixes to avoid profile lock conflicts.
     *                      If null, defaults to "profile-".
     */
    public static WebDriver createDriver(Path downloadDirectory, int workerSlot, String profilePrefix) {
        Utils.ensureDirectory(downloadDirectory);

        WebDriverManager.chromedriver().setup();

        String prefix = profilePrefix == null || profilePrefix.isBlank() ? "profile-" : profilePrefix;
        int safeSlot = Math.max(1, workerSlot);
        WebDriver primary;
        try {
            primary = tryCreateDriverWithProfile(downloadDirectory, safeSlot, prefix);
        } catch (Exception ex) {
            if (!isDevToolsActivePortError(ex)) {
                throw ex;
            }
            Utils.logPlain("Chrome startup crash detected (DevToolsActivePort). Force-killing chrome/chromedriver and retrying...");
            forceKillChromeProcesses();
            primary = tryCreateDriverWithProfile(downloadDirectory, safeSlot, prefix);
        }
        if (primary != null) {
            MANAGED_DRIVERS.add(primary);
            return primary;
        }

        int fallbackSlot = safeSlot == 1 ? 2 : 1;
        WebDriver fallback = tryCreateDriverWithProfile(downloadDirectory, fallbackSlot, prefix);
        if (fallback != null) {
            Utils.logPlain("Worker slot " + safeSlot + " profile is locked. Fallback to " + prefix + fallbackSlot + ".");
            MANAGED_DRIVERS.add(fallback);
            return fallback;
        }

        throw new IllegalStateException("Unable to start ChromeDriver for worker slot " + safeSlot + ". Check profile lock or Chrome processes.");
    }

    private static WebDriver tryCreateDriverWithProfile(Path downloadDirectory, int workerSlot, String profilePrefix) {
        ChromeOptions options = buildChromeOptions(downloadDirectory, workerSlot, profilePrefix);
        try {
            ChromeDriver driver = new ChromeDriver(options);
            injectStealthScripts(driver);
            return driver;
        } catch (Exception ex) {
            if (!isProfileLockError(ex)) {
                throw ex;
            }
            return null;
        }
    }

    /** User-Agent pool — different real Chrome versions on different OS to diversify worker fingerprints. */
    private static final String[] USER_AGENT_POOL = new String[] {
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/125.0.0.0 Safari/537.36",
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36",
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/123.0.0.0 Safari/537.36",
        "Mozilla/5.0 (Windows NT 11.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/125.0.0.0 Safari/537.36",
        "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/125.0.0.0 Safari/537.36"
    };

    /** Common viewport sizes to vary window dimensions per worker. */
    private static final int[][] VIEWPORT_POOL = new int[][] {
        {1920, 1080},
        {1536, 864},
        {1440, 900},
        {1366, 768},
        {1600, 900}
    };

    /** WebGL renderer pool — varies hardware fingerprint between workers. */
    private static final String[][] WEBGL_POOL = new String[][] {
        {"Intel Inc.", "Intel Iris OpenGL Engine"},
        {"Google Inc. (NVIDIA)", "ANGLE (NVIDIA, NVIDIA GeForce GTX 1660 Direct3D11 vs_5_0 ps_5_0, D3D11)"},
        {"Google Inc. (Intel)", "ANGLE (Intel, Intel(R) UHD Graphics 620 Direct3D11 vs_5_0 ps_5_0, D3D11)"},
        {"Google Inc. (AMD)", "ANGLE (AMD, AMD Radeon RX 580 Direct3D11 vs_5_0 ps_5_0, D3D11)"}
    };

    private static String userAgentForSlot(int slot) {
        return USER_AGENT_POOL[Math.floorMod(slot, USER_AGENT_POOL.length)];
    }

    private static int[] viewportForSlot(int slot) {
        return VIEWPORT_POOL[Math.floorMod(slot, VIEWPORT_POOL.length)];
    }

    private static String[] webglForSlot(int slot) {
        return WEBGL_POOL[Math.floorMod(slot, WEBGL_POOL.length)];
    }

    private static ChromeOptions buildChromeOptions(Path downloadDirectory, int workerSlot, String profilePrefix) {
        Map<String, Object> prefs = new HashMap<>();
        prefs.put("download.default_directory", downloadDirectory.toAbsolutePath().toString());
        prefs.put("download.prompt_for_download", false);
        prefs.put("download.directory_upgrade", true);
        prefs.put("profile.default_content_settings.popups", 0);
        prefs.put("profile.default_content_setting_values.notifications", 2);
        prefs.put("profile.default_content_setting_values.automatic_downloads", 1);
        prefs.put("plugins.always_open_pdf_externally", true);
        prefs.put("safebrowsing.enabled", true);
        // Disable webRTC leak (can expose real IP behind proxy)
        prefs.put("webrtc.ip_handling_policy", "disable_non_proxied_udp");
        prefs.put("webrtc.multiple_routes_enabled", false);

        ChromeOptions options = new ChromeOptions();
        options.setExperimentalOption("prefs", prefs);
        options.setPageLoadStrategy(PageLoadStrategy.EAGER);
        options.addArguments("--disable-notifications");
        options.addArguments("--disable-popup-blocking");
        options.addArguments("--disable-features=ExternalProtocolDialog,IsolateOrigins,site-per-process");
        options.addArguments("--disable-session-crashed-bubble");
        options.addArguments("--no-first-run");
        options.addArguments("--no-default-browser-check");
        options.addArguments("--remote-allow-origins=*");

        // --- Anti-bot detection (applied to ALL modes) ---
        options.addArguments("--disable-blink-features=AutomationControlled");
        options.setExperimentalOption("excludeSwitches", java.util.List.of("enable-automation"));
        options.setExperimentalOption("useAutomationExtension", false);
        options.addArguments("--lang=vi-VN,vi;q=0.9,en-US;q=0.8,en;q=0.7");

        // Per-worker fingerprint diversification: User-Agent + viewport
        int safeSlot = Math.max(1, workerSlot);
        String userAgent = userAgentForSlot(safeSlot);
        int[] viewport = viewportForSlot(safeSlot);
        options.addArguments("--user-agent=" + userAgent);
        options.addArguments("--window-size=" + viewport[0] + "," + viewport[1]);

        Path profileDir = Utils.dataDirectory()
            .resolve("chrome-profiles")
            .resolve(profilePrefix + safeSlot)
            .toAbsolutePath()
            .normalize();
        Utils.ensureDirectory(profileDir);
        options.addArguments("--user-data-dir=" + profileDir);

        BrowserProfileConfigStore.BrowserProfileConfig browserProfile = BrowserProfileConfigStore.load().normalized();
        if ("HIDDEN".equals(browserProfile.mode())) {
            options.addArguments("--headless=new");
            options.addArguments("--disable-dev-shm-usage");
            options.addArguments("--no-sandbox");
        }

        return options;
    }

    /**
     * Inject stealth JavaScript via CDP so it runs on EVERY new document
     * (including iframes) before the page's own scripts execute.
     * Falls back to runtime injection if CDP is unavailable.
     */
    public static void injectStealthScripts(WebDriver driver) {
        if (!(driver instanceof ChromeDriver chromeDriver)) {
            return;
        }
        // Per-driver stable fingerprint: derive slot index from object identity
        int slot = Math.floorMod(System.identityHashCode(driver), USER_AGENT_POOL.length) + 1;
        String[] webgl = webglForSlot(slot);
        String webglVendor = webgl[0].replace("'", "\\'");
        String webglRenderer = webgl[1].replace("'", "\\'");

        String stealthScript =
            "(() => {"
            // Hide webdriver flag
            + "  Object.defineProperty(Navigator.prototype, 'webdriver', {get: () => undefined});"
            // Plugins (real Chrome has 3-5 plugins)
            + "  Object.defineProperty(navigator, 'plugins', {get: () => {"
            + "    const fakePlugins = [{name: 'PDF Viewer'}, {name: 'Chrome PDF Viewer'}, {name: 'Chromium PDF Viewer'}];"
            + "    fakePlugins.length = 3; return fakePlugins;"
            + "  }});"
            // Languages
            + "  Object.defineProperty(navigator, 'languages', {get: () => ['vi-VN', 'vi', 'en-US', 'en']});"
            // Hardware concurrency (avoid headless-typical low values)
            + "  Object.defineProperty(navigator, 'hardwareConcurrency', {get: () => 8});"
            + "  Object.defineProperty(navigator, 'deviceMemory', {get: () => 8});"
            // Chrome runtime stub
            + "  window.chrome = window.chrome || {};"
            + "  window.chrome.runtime = window.chrome.runtime || {};"
            // Permissions API spoof
            + "  if (window.navigator.permissions && window.navigator.permissions.query) {"
            + "    const origQuery = window.navigator.permissions.query;"
            + "    window.navigator.permissions.query = (p) => p && p.name === 'notifications'"
            + "      ? Promise.resolve({state: Notification.permission})"
            + "      : origQuery(p);"
            + "  }"
            // WebGL vendor/renderer spoof (varies per worker slot)
            + "  const _getParam = WebGLRenderingContext.prototype.getParameter;"
            + "  WebGLRenderingContext.prototype.getParameter = function(p) {"
            + "    if (p === 37445) return '" + webglVendor + "';"
            + "    if (p === 37446) return '" + webglRenderer + "';"
            + "    return _getParam.call(this, p);"
            + "  };"
            // Hide Selenium-specific window props
            + "  ['cdc_adoQpoasnfa76pfcZLmcfl_Array','cdc_adoQpoasnfa76pfcZLmcfl_Promise','cdc_adoQpoasnfa76pfcZLmcfl_Symbol']"
            + "    .forEach(k => { try { delete window[k]; delete document[k]; } catch(e){} });"
            + "})();";

        try {
            chromeDriver.executeCdpCommand(
                "Page.addScriptToEvaluateOnNewDocument",
                Map.of("source", stealthScript)
            );
        } catch (Exception ignored) {
            // Fallback: runtime injection (only affects current document)
            try {
                ((JavascriptExecutor) chromeDriver).executeScript(stealthScript);
            } catch (Exception alsoIgnored) {
                // Stealth is best-effort
            }
        }
    }

    private static boolean isProfileLockError(Throwable throwable) {
        String message = throwable == null || throwable.getMessage() == null
            ? ""
            : throwable.getMessage().toLowerCase();
        return message.contains("user data directory is already in use")
            || message.contains("cannot create default profile directory")
            || message.contains("profile error")
            || message.contains("probably user data directory is already in use");
    }

    private static boolean isDevToolsActivePortError(Throwable throwable) {
        String message = throwable == null || throwable.getMessage() == null
            ? ""
            : throwable.getMessage().toLowerCase();
        return message.contains("devtoolsactiveport")
            || (message.contains("chrome failed to start") && message.contains("crashed"));
    }

    private static void forceKillChromeProcesses() {
        killProcessImage("chromedriver.exe");
        killProcessImage("chrome.exe");
    }

    private static void killProcessImage(String imageName) {
        if (imageName == null || imageName.isBlank()) {
            return;
        }
        Process process = null;
        try {
            process = new ProcessBuilder("taskkill", "/F", "/T", "/IM", imageName)
                .redirectErrorStream(true)
                .start();
            process.waitFor(5, TimeUnit.SECONDS);
        } catch (IOException | InterruptedException ignored) {
            if (ignored instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
        } finally {
            if (process != null) {
                process.destroy();
            }
        }
    }

    public static WebDriver getOrCreateReusableDriver(String key, Path downloadDirectory, int workerSlot) {
        return getOrCreateReusableDriver(key, downloadDirectory, workerSlot, null);
    }

    public static WebDriver getOrCreateReusableDriver(String key, Path downloadDirectory, int workerSlot, String profilePrefix) {
        if (key == null || key.isBlank()) {
            return createDriver(downloadDirectory, workerSlot, profilePrefix);
        }

        WebDriver existing = REUSABLE_DRIVERS.get(key);
        if (existing != null) {
            try {
                existing.getWindowHandles();
                return existing;
            } catch (Exception ignored) {
                quitDriverQuietly(existing);
                REUSABLE_DRIVERS.remove(key, existing);
            }
        }

        WebDriver created = createDriver(downloadDirectory, workerSlot, profilePrefix);
        REUSABLE_DRIVERS.put(key, created);
        return created;
    }

    public static WebDriver replaceReusableDriver(String key, WebDriver currentDriver, Path downloadDirectory, int workerSlot) {
        return replaceReusableDriver(key, currentDriver, downloadDirectory, workerSlot, null);
    }

    public static WebDriver replaceReusableDriver(String key, WebDriver currentDriver, Path downloadDirectory, int workerSlot, String profilePrefix) {
        if (key == null || key.isBlank()) {
            quitDriverQuietly(currentDriver);
            return createDriver(downloadDirectory, workerSlot, profilePrefix);
        }

        quitDriverQuietly(currentDriver);
        WebDriver created = createDriver(downloadDirectory, workerSlot, profilePrefix);
        REUSABLE_DRIVERS.put(key, created);
        return created;
    }

    public static void discardReusableDriver(String key, WebDriver expectedDriver) {
        if (key == null || key.isBlank()) {
            quitDriverQuietly(expectedDriver);
            return;
        }

        WebDriver cached = REUSABLE_DRIVERS.get(key);
        if (cached == expectedDriver) {
            REUSABLE_DRIVERS.remove(key, expectedDriver);
        }
        quitDriverQuietly(expectedDriver);
    }

    public static void quitDriverQuietly(WebDriver driver) {
        if (driver == null) {
            return;
        }
        boolean interrupted = Thread.currentThread().isInterrupted();
        try {
            if (interrupted) {
                Thread.interrupted();
            }
            driver.quit();
        } catch (Exception ignored) {
            // best effort quit only
        } finally {
            MANAGED_DRIVERS.remove(driver);
            if (interrupted) {
                Thread.currentThread().interrupt();
            }
        }
    }

    public static void quitAllDriversNow() {
        for (Map.Entry<String, WebDriver> entry : new ArrayList<>(REUSABLE_DRIVERS.entrySet())) {
            WebDriver driver = entry.getValue();
            quitDriverQuietly(driver);
            REUSABLE_DRIVERS.remove(entry.getKey(), driver);
        }
        List<WebDriver> snapshot = new ArrayList<>(MANAGED_DRIVERS);
        for (WebDriver driver : snapshot) {
            quitDriverQuietly(driver);
        }
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
        closeButtons.add(By.cssSelector(".modal .close"));
        closeButtons.add(By.cssSelector(".modal-backdrop"));

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

        // Dismiss any full-screen overlay or banner that blocks interaction.
        try {
            ((JavascriptExecutor) driver).executeScript(
                "document.querySelectorAll('.modal-backdrop, .popup-overlay, [class*=\"overlay\"]')"
                + ".forEach(el => el.remove());"
                + "document.querySelectorAll('.modal.show, .popup.show')"
                + ".forEach(el => el.classList.remove('show'));"
                + "document.body.classList.remove('modal-open');"
                + "document.body.style.overflow = '';"
            );
        } catch (Exception ignored) {
            // best-effort DOM cleanup
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
        Set<String> handles = driver.getWindowHandles();
        if (handles.isEmpty()) {
            return;
        }

        String original = driver.getWindowHandle();
        String preferredHandle = null;
        String relevantHandle = null;
        String nonDisposableHandle = null;

        for (String handle : handles) {
            driver.switchTo().window(handle);
            String url = safeCurrentUrl(driver);

            if (preferredUrl != null && preferredUrl.test(url)) {
                preferredHandle = handle;
            }
            if (relevantHandle == null && isRelevantMuasamcongUrl(url)) {
                relevantHandle = handle;
            }
            if (nonDisposableHandle == null && !isDisposableWindow(url)) {
                nonDisposableHandle = handle;
            }
        }

        String keepHandle = preferredHandle != null
            ? preferredHandle
            : (relevantHandle != null ? relevantHandle : (nonDisposableHandle != null ? nonDisposableHandle : original));

        for (String handle : new ArrayList<>(driver.getWindowHandles())) {
            if (handle.equals(keepHandle)) {
                continue;
            }
            driver.switchTo().window(handle);
            String url = safeCurrentUrl(driver);
            if (isDisposableWindow(url)) {
                driver.close();
            }
        }

        if (!driver.getWindowHandles().contains(keepHandle)) {
            keepHandle = driver.getWindowHandles().iterator().next();
        }
        driver.switchTo().window(keepHandle);
    }

    public static boolean isDisposableCurrentUrl(WebDriver driver) {
        return isDisposableWindow(safeCurrentUrl(driver));
    }

    public static boolean isRelevantCurrentUrl(WebDriver driver) {
        return isRelevantMuasamcongUrl(safeCurrentUrl(driver));
    }

    public static void closeExtraWindows(WebDriver driver) {
        Set<String> handles = driver.getWindowHandles();
        if (handles.isEmpty()) {
            return;
        }

        String current = driver.getWindowHandle();
        String keepHandle = current;
        String currentUrl = safeCurrentUrl(driver);
        if (isDisposableWindow(currentUrl)) {
            for (String handle : handles) {
                driver.switchTo().window(handle);
                if (!isDisposableWindow(safeCurrentUrl(driver))) {
                    keepHandle = handle;
                    break;
                }
            }
        }

        for (String handle : new ArrayList<>(driver.getWindowHandles())) {
            if (!handle.equals(keepHandle)) {
                driver.switchTo().window(handle);
                driver.close();
            }
        }

        if (!driver.getWindowHandles().contains(keepHandle)) {
            keepHandle = driver.getWindowHandles().iterator().next();
        }
        driver.switchTo().window(keepHandle);
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
