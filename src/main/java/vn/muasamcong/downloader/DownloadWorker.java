package vn.muasamcong.downloader;

import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Queue;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.openqa.selenium.By;
import org.openqa.selenium.NoSuchElementException;
import org.openqa.selenium.TimeoutException;
import org.openqa.selenium.WebDriver;
import org.openqa.selenium.WebElement;

public final class DownloadWorker implements Runnable {

    private static final Duration DEFAULT_WAIT = Duration.ofSeconds(25);
    private static final Pattern PDF_URL_PATTERN = Pattern.compile("https?://[^'\\\"\\s>]+\\.pdf", Pattern.CASE_INSENSITIVE);

    private static final By HOME_INPUT = By.cssSelector("main#home input[name='keyword']");
    private static final By HOME_SEARCH_BUTTON = By.cssSelector("main#home button.search-button");
    private static final By SEARCH_INPUT = By.cssSelector("main#search-home input[name='keyword']");
    private static final By SEARCH_BUTTON = By.cssSelector("main#search-home button.button__search");
    private static final By RESULT_ITEM = By.cssSelector("div.content__body__left__item");
    private static final By FIRST_DETAIL_LINK = By.xpath(
        "(//div[contains(@class,'content__body__left__item')]//a[contains(@href,'render=detail-v2')])[1]"
    );

    private static final By DECISION_ATTACHMENT_PRIMARY = By.xpath(
        "//div[contains(@class,'card')][.//div[contains(@class,'card-header')]"
            + "[contains(normalize-space(),'Thong tin quyet dinh phe duyet') or "
            + "contains(normalize-space(),'Thông tin quyết định phê duyệt')]]"
            + "//div[contains(@class,'infomation__content')][.//div[contains(@class,'infomation__content__title')]"
            + "[contains(normalize-space(),'Quyet dinh phe duyet') or "
            + "contains(normalize-space(),'Quyết định phê duyệt')]]"
            + "//*[contains(@class,'tags-fileAttach')]"
    );

    private static final By DECISION_ATTACHMENT_FALLBACK_1 = By.xpath(
        "//div[contains(@class,'card-header')][contains(normalize-space(),'Thong tin quyet dinh phe duyet')"
            + " or contains(normalize-space(),'Thông tin quyết định phê duyệt')]"
            + "/following::span[contains(@class,'tags-fileAttach') or self::tags[contains(@class,'tags-fileAttach')]][1]"
    );

    private static final By DECISION_ATTACHMENT_FALLBACK_2 = By.xpath(
        "//*[contains(@class,'tags-fileAttach') and contains(translate(normalize-space(), 'PDF', 'pdf'),'.pdf')]"
    );

    private final Queue<String> keywordQueue;
    private final String baseUrl;
    private final Path baseDownloadDir;
    private final int maxRetries;
    private final Duration downloadTimeout;
    private final RunStats stats;

    public DownloadWorker(
        Queue<String> keywordQueue,
        String baseUrl,
        Path baseDownloadDir,
        int maxRetries,
        Duration downloadTimeout,
        RunStats stats
    ) {
        this.keywordQueue = keywordQueue;
        this.baseUrl = baseUrl;
        this.baseDownloadDir = baseDownloadDir;
        this.maxRetries = maxRetries;
        this.downloadTimeout = downloadTimeout;
        this.stats = stats;
    }

    @Override
    public void run() {
        WebDriver driver = null;
        long threadId = Thread.currentThread().threadId();
        Path threadDownloadDir = baseDownloadDir.resolve(String.valueOf(threadId));

        int maxAttempts = maxRetries + 1;

        try {
            driver = SeleniumHelper.createDriver(threadDownloadDir);

            while (true) {
                String keyword = keywordQueue.poll();
                if (keyword == null) {
                    break;
                }

                boolean success = false;
                Utils.logStatus(keyword, "START", 0, "Begin processing keyword.");

                for (int attempt = 1; attempt <= maxAttempts; attempt++) {
                    try {
                        SeleniumHelper.closeExtraWindows(driver);
                        performDownloadFlow(driver, threadDownloadDir, keyword);

                        Utils.logStatus(keyword, "SUCCESS", attempt, "Downloaded and saved to keyword folder.");
                        stats.markSuccess();
                        success = true;
                        break;
                    } catch (Exception ex) {
                        Utils.logStatus(keyword, "FAIL", attempt, rootMessage(ex));
                        if (attempt == maxAttempts) {
                            stats.markFail();
                        }
                    }
                }

                if (!success) {
                    Utils.logStatus(keyword, "FAIL", maxAttempts, "Exceeded retry limit, moving to next keyword.");
                } else {
                    Utils.logStatus(keyword, "DONE", maxAttempts, "Finished keyword.");
                }
            }
        } finally {
            if (driver != null) {
                driver.quit();
            }
        }
    }

    private void performDownloadFlow(WebDriver driver, Path downloadDir, String keyword) {
        SeleniumHelper.openUrlRobust(driver, baseUrl, DEFAULT_WAIT);
        Utils.logStatus(keyword, "INFO", 0, "Opened URL: " + driver.getCurrentUrl());
        SeleniumHelper.dismissPopupIfPresent(driver);

        WebElement homeInput = SeleniumHelper.waitVisible(driver, HOME_INPUT, DEFAULT_WAIT);
        homeInput.clear();
        homeInput.sendKeys(keyword);
        SeleniumHelper.safeClick(driver, HOME_SEARCH_BUTTON, DEFAULT_WAIT);

        List<By> resultSignals = new ArrayList<>();
        resultSignals.add(SEARCH_INPUT);
        resultSignals.add(RESULT_ITEM);
        SeleniumHelper.waitForAny(driver, resultSignals, DEFAULT_WAIT);

        if (!driver.findElements(SEARCH_INPUT).isEmpty()) {
            WebElement searchInput = SeleniumHelper.waitVisible(driver, SEARCH_INPUT, DEFAULT_WAIT);
            searchInput.clear();
            searchInput.sendKeys(keyword);
            SeleniumHelper.safeClick(driver, SEARCH_BUTTON, DEFAULT_WAIT);
        }

        SeleniumHelper.waitVisible(driver, RESULT_ITEM, DEFAULT_WAIT);
        SeleniumHelper.safeClick(driver, FIRST_DETAIL_LINK, DEFAULT_WAIT);
        SeleniumHelper.switchToNewestWindowIfNeeded(driver, Duration.ofSeconds(3));

        SeleniumHelper.dismissPopupIfPresent(driver);
        WebElement attachment = findDecisionAttachment(driver);

        Set<Path> before = Utils.snapshotPdfFiles(downloadDir);
        if (!tryNavigateByDirectPdfUrl(driver, attachment, keyword)) {
            SeleniumHelper.safeClick(driver, attachment);
        }

        Path downloaded = Utils.safeWaitForPdf(downloadDir, before, downloadTimeout);
        if (downloaded == null) {
            throw new TimeoutException("Download timeout waiting for a new PDF file.");
        }

        Path finalFile = Utils.movePdfToKeywordFolder(downloaded, baseDownloadDir, keyword);
        if (!Utils.isNonEmptyPdf(finalFile)) {
            throw new IllegalStateException("Downloaded file is invalid or empty: " + finalFile.toAbsolutePath());
        }
    }

    private WebElement findDecisionAttachment(WebDriver driver) {
        List<By> locators = List.of(
            DECISION_ATTACHMENT_PRIMARY,
            DECISION_ATTACHMENT_FALLBACK_1,
            DECISION_ATTACHMENT_FALLBACK_2
        );

        for (By locator : locators) {
            try {
                return SeleniumHelper.waitClickable(driver, locator, Duration.ofSeconds(15));
            } catch (TimeoutException ignored) {
                // continue with fallback
            }
        }

        throw new NoSuchElementException("Cannot find Quyết định phê duyệt attachment link.");
    }

    private String rootMessage(Throwable throwable) {
        Throwable root = throwable;
        while (root.getCause() != null) {
            root = root.getCause();
        }
        String message = root.getMessage();
        return (message == null || message.isBlank()) ? root.getClass().getSimpleName() : message;
    }

    private boolean tryNavigateByDirectPdfUrl(WebDriver driver, WebElement attachment, String keywordValue) {
        String[] attrs = new String[] {"href", "data-href", "data-url", "src", "onclick"};
        for (String attr : attrs) {
            String raw = attachment.getDomAttribute(attr);
            String pdfUrl = extractPdfUrl(raw);
            if (pdfUrl != null) {
                Utils.logStatus(keywordValue, "INFO", 0, "Downloading via direct PDF URL.");
                driver.get(pdfUrl);
                return true;
            }
        }
        return false;
    }

    private String extractPdfUrl(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }

        String trimmed = raw.trim();
        if (trimmed.startsWith("http") && trimmed.toLowerCase().contains(".pdf")) {
            return trimmed;
        }

        Matcher matcher = PDF_URL_PATTERN.matcher(trimmed);
        if (matcher.find()) {
            return matcher.group();
        }
        return null;
    }
}
