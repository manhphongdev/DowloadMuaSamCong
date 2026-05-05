package vn.muasamcong.downloader.core;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.text.Normalizer;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.format.DateTimeParseException;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.Set;
import java.util.concurrent.CancellationException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import vn.muasamcong.downloader.model.BidSheetRow;
import vn.muasamcong.downloader.model.BidSheetRowBuilder;
import vn.muasamcong.downloader.model.BidStatus;
import vn.muasamcong.downloader.model.KeywordTarget;
import vn.muasamcong.downloader.store.RunStateStore;
import vn.muasamcong.downloader.util.SeleniumHelper;
import vn.muasamcong.downloader.util.Utils;
import org.openqa.selenium.By;
import org.openqa.selenium.JavascriptExecutor;
import org.openqa.selenium.NoSuchElementException;
import org.openqa.selenium.TimeoutException;
import org.openqa.selenium.WebDriver;
import org.openqa.selenium.WebElement;
import org.openqa.selenium.support.ui.WebDriverWait;

public final class DownloadWorker implements Runnable {

    private static final String AUTO_DOWNLOAD_FOLDER_NAME = "auto-download";
    private static final Duration DEFAULT_WAIT = Duration.ofSeconds(25);
    private static final Duration DOWNLOAD_TIMEOUT = Duration.ofSeconds(60);
    private static final Duration TAB_SWITCH_WAIT = Duration.ofSeconds(6);
    private static final Duration QUICK_LOCATOR_WAIT = Duration.ofSeconds(2);
    private static final DateTimeFormatter JSON_TIME_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss");
    private static final int BID_INFO_SCHEMA_VERSION = 2;
    private static final Path BID_INFO_DATA_DIR = Utils.dataDirectory();
    private static final Path BID_INFO_JSON_FILE = BID_INFO_DATA_DIR.resolve("bid_sheet_rows.json");
    private static final Object BID_INFO_JSON_LOCK = new Object();
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
    private static final List<BidSheetRow> BID_INFO_ROWS = new ArrayList<>();

    // ── Search-page locators ─────────────────────────────────────────────────
    private static final By HOME_INPUT =
        By.cssSelector("main#home input[name='keyword']");
    private static final By HOME_SEARCH_BUTTON =
        By.cssSelector("main#home button.search-button");
    private static final By SEARCH_INPUT =
        By.cssSelector("main#search-home input[name='keyword']");
    private static final By SEARCH_BUTTON =
        By.cssSelector("main#search-home button.button__search");
    private static final By RESULT_ITEM =
        By.cssSelector("div.content__body__left__item");
    private static final By FIRST_DETAIL_LINK = By.xpath(
        "(//div[contains(@class,'content__body__left__item')]"
        + "//a[contains(@href,'render=detail-v2')])[1]"
    );

    // ── Detail-page: tab navigation ──────────────────────────────────────────
    private static final By ALL_TABS =
        By.cssSelector(".nav.nav-tabs .nav-item a[data-toggle='tab']");

    private static final String TAB_BID_OPENING_MINUTES = "Biên bản mở thầu";
    private static final String TAB_CONTRACTOR_SELECTION_RESULTS = "Kết quả lựa chọn nhà thầu";
    private static final List<String> TAB_CONTRACT_INFORMATION_ALIASES = List.of(
        "Thông tin chủ yếu của hợp đồng"
    );

    private static final Map<String, List<String>> INFO_LABELS = Map.of(
        "estimatedBudget", List.of(
            "Dự toán gói thầu"
    ));

    private static final By REMAINING_TIME = By.xpath(
        "//*[contains(@class,'detail__children-2__text-center')]"
        + "[contains(normalize-space(),'Còn lại') "
        + " or contains(normalize-space(),'Con lai')]"
    );

    // ── Detail-page: file đính kèm trong tab BBMT ───────────────────────────
    private static final By BBMT_DOWNLOAD_ICON_EXACT = By.xpath(
        "//*[(@id='bidOpeningMinutes' or contains(@class,'bidOpeningMinutes') or contains(@class,'bbmtKqm'))]"
        + "//img[@src='/o/egp-portal-contractor-selection-v2/images/icons/download_Outline.svg']"
    );
    private static final By BBMT_DOWNLOAD_ICON = By.xpath(
        "//*[(@id='bidOpeningMinutes' or contains(@class,'bidOpeningMinutes') or contains(@class,'bbmtKqm'))]"
        + "//img[contains(@src,'download_Outline.svg') and contains(@class,'content__button__icon')]"
    );
    private static final By BBMT_DOWNLOAD_ICON_FALLBACK = By.xpath(
        "//*[(@id='bidOpeningMinutes' or contains(@class,'bidOpeningMinutes') or contains(@class,'bbmtKqm'))]"
        + "//img[contains(@src,'download_Outline.svg')]"
    );

    private static final By KQLCNT_DECISION_PRIMARY = By.xpath(
        "//div[@id='contractorSelectionResults']"
        + "//div[contains(@class,'infomation__content')]"
        + "[.//div[contains(@class,'infomation__content__title')]"
        + "[contains(normalize-space(),'Quyết định phê duyệt')"
        + " or contains(normalize-space(),'Quyet dinh phe duyet')]]"
        + "//*[contains(@class,'tags-fileAttach')]"
    );
    private static final By KQLCNT_DECISION_FALLBACK = By.xpath(
        "//div[@id='contractorSelectionResults']"
        + "//*[contains(@class,'tags-fileAttach')]"
        + "[contains(translate(normalize-space(),'.PDF','.pdf'),'.pdf')]"
    );

    // ────────────────────────────────────────────────────────────────────────

    private final Queue<KeywordTarget> keywordQueue;
    private final String baseUrl;
    private final Path baseDownloadDir;
    private final int maxRetries;
    private final RunStats stats;
    private final AtomicBoolean stopRequested;

    public DownloadWorker(
        Queue<KeywordTarget> keywordQueue,
        String baseUrl,
        Path baseDownloadDir,
        int maxRetries,
        RunStats stats,
        AtomicBoolean stopRequested
    ) {
        this.keywordQueue = keywordQueue;
        this.baseUrl = baseUrl;
        this.baseDownloadDir = baseDownloadDir;
        this.maxRetries = maxRetries;
        this.stats = stats;
        this.stopRequested = stopRequested;
    }

    public static void prepareBidInfoOutput() {
        synchronized (BID_INFO_JSON_LOCK) {
            Utils.ensureDirectory(BID_INFO_DATA_DIR);
            BID_INFO_ROWS.clear();
            BID_INFO_ROWS.addAll(loadBidInfoRowsFromDisk());
            persistBidInfoRows();
        }
    }

    public static void clearBidInfoOutput() {
        synchronized (BID_INFO_JSON_LOCK) {
            Utils.ensureDirectory(BID_INFO_DATA_DIR);
            BID_INFO_ROWS.clear();
            persistBidInfoRows();
        }
    }

    private static List<BidSheetRow> loadBidInfoRowsFromDisk() {
        if (!Files.exists(BID_INFO_JSON_FILE)) {
            return List.of();
        }

        try {
            JsonNode root = OBJECT_MAPPER.readTree(Files.newBufferedReader(BID_INFO_JSON_FILE));
            JsonNode rowsNode = extractRowsNode(root);
            if (rowsNode == null || !rowsNode.isArray()) {
                return List.of();
            }

            List<BidSheetRow> rows = new ArrayList<>();
            for (JsonNode node : rowsNode) {
                rows.add(toBidSheetRow(node));
            }
            return rows;
        } catch (Exception ex) {
            return List.of();
        }
    }

    private static BidSheetRow toBidSheetRow(JsonNode node) {
        String statusRaw = textValue(node, "status");
        BidStatus status = null;
        if (statusRaw != null && !statusRaw.isBlank()) {
            try {
                status = BidStatus.valueOf(statusRaw);
            } catch (IllegalArgumentException ignored) {
                status = null;
            }
        }

        LocalDateTime bidClosingTime = null;
        String bidClosingRaw = textValue(node, "bidClosingTime");
        if (bidClosingRaw != null && !bidClosingRaw.isBlank()) {
            try {
                bidClosingTime = LocalDateTime.parse(bidClosingRaw);
            } catch (DateTimeParseException ignored) {
                bidClosingTime = null;
            }
        }

        Duration remaining = null;
        JsonNode remainingNode = node.get("remainingTimeSeconds");
        if (remainingNode != null && remainingNode.isNumber()) {
            remaining = Duration.ofSeconds(remainingNode.asLong());
        }

        return BidSheetRowBuilder.create()
            .tmbtNumber(textValue(node, "tmbtNumber"))
            .packageName(textValue(node, "packageName"))
            .procuringEntity(textValue(node, "procuringEntity"))
            .contractPerformanceFolder(textValue(node, "contractPerformanceFolder"))
            .parentFolderName(textValue(node, "parentFolderName"))
            .estimatedBudget(parseEstimatedBudgetValue(node.get("estimatedBudget")))
            .status(status)
            .bidClosingTime(bidClosingTime)
            .remainingTimeToClosing(remaining)
            .folderLink(textValue(node, "folderLink"))
            .build();
    }

    private static BigInteger parseEstimatedBudgetValue(JsonNode node) {
        if (node == null || node.isNull()) {
            return null;
        }
        String raw = node.asText();
        if (raw == null || raw.isBlank()) {
            return null;
        }
        String digits = raw.replaceAll("[^0-9]", "");
        if (digits.isBlank()) {
            return null;
        }
        try {
            return new BigInteger(digits);
        } catch (Exception ignored) {
            return null;
        }
    }

    private static String textValue(JsonNode node, String field) {
        JsonNode value = node == null ? null : node.get(field);
        if (value == null || value.isNull()) {
            return null;
        }
        String text = value.asText();
        return (text == null || text.isBlank()) ? null : text;
    }

    private static void persistBidInfoRows() {
        String payload = buildBidInfoListJson(BID_INFO_ROWS);
        try {
            Files.writeString(BID_INFO_JSON_FILE, payload, StandardCharsets.UTF_8);
        } catch (IOException ex) {
            throw new RuntimeException("Unable to write bid info output file.", ex);
        }
    }

    // ════════════════════════════════════════════════════════════════════════
    // Runnable
    // ════════════════════════════════════════════════════════════════════════

    @Override
    public void run() {
        WebDriver driver = null;
        boolean completedWithoutStop = false;
        long threadId = Thread.currentThread().threadId();
        Path threadDownloadDir = baseDownloadDir.resolve("_thread_tmp_" + threadId);
        int maxAttempts = maxRetries + 1;

        try {
            driver = SeleniumHelper.createDriver(threadDownloadDir);

            while (true) {
                if (shouldStop()) {
                    break;
                }

                KeywordTarget target = keywordQueue.poll();
                if (target == null) break;

                String keyword = target.keyword();
                boolean success = false;
                int successAttempt = 0;
                int downloadedFilesCount = 0;
                String lastErrorMessage = "Unknown error";
                Utils.logStatus(keyword, "START", 0,
                    "Begin processing keyword.");

                for (int attempt = 1; attempt <= maxAttempts; attempt++) {
                    if (shouldStop()) {
                        throw new CancellationException("Stop requested");
                    }
                    try {
                        SeleniumHelper.closeExtraWindows(driver);
                        if (SeleniumHelper.isDisposableCurrentUrl(driver)) {
                            Utils.logStatus(keyword, "WARN", attempt,
                                "Current window is disposable. Reloading base URL.");
                            SeleniumHelper.openUrlRobust(driver, baseUrl, DEFAULT_WAIT);
                        }
                        SeleniumHelper.normalizeWindows(driver,
                            url -> url.toLowerCase().contains("muasamcong.mpi.gov.vn"));
                        downloadedFilesCount = performDownloadFlow2(driver, threadDownloadDir, target);

                        Utils.logStatus(keyword, "SUCCESS", attempt,
                            "Finished.");
                        stats.markSuccess();
                        RunStateStore.markSuccess(target, attempt, downloadedFilesCount);
                        stats.addProcessedRecord(new RunStats.ProcessedRecord(
                            keyword,
                            target.folderPath() == null ? null : target.folderPath().toAbsolutePath().normalize().toString(),
                            "SUCCESS",
                            attempt,
                            null,
                            downloadedFilesCount
                        ));
                        success = true;
                        successAttempt = attempt;
                        break;
                    } catch (CancellationException ex) {
                        throw ex;
                    } catch (Exception ex) {
                        lastErrorMessage = rootMessage(ex);
                        Utils.logStatus(keyword, "FAIL", attempt, lastErrorMessage);

                        if (isSessionBroken(ex)) {
                            Utils.logStatus(keyword, "WARN", attempt,
                                "Browser session is broken. Recreating driver for next retry.");
                            driver = recreateDriver(driver, threadDownloadDir);
                        }
                    }
                }

                if (!success) {
                    if (shouldStop()) {
                        RunStateStore.markStopped(target, successAttempt, "Stopped by user", downloadedFilesCount);
                        stats.addProcessedRecord(new RunStats.ProcessedRecord(
                            keyword,
                            target.folderPath() == null ? null : target.folderPath().toAbsolutePath().normalize().toString(),
                            "STOPPED",
                            successAttempt,
                            "Stopped by user",
                            downloadedFilesCount
                        ));
                        Utils.logStatus(keyword, "STOP", 0, "Stopped by user.");
                        break;
                    }
                    stats.markFail();
                    stats.addFailure(keyword, maxAttempts, lastErrorMessage,
                        Thread.currentThread().threadId());
                    RunStateStore.markFailure(target, maxAttempts, lastErrorMessage, downloadedFilesCount);
                    stats.addProcessedRecord(new RunStats.ProcessedRecord(
                        keyword,
                        target.folderPath() == null ? null : target.folderPath().toAbsolutePath().normalize().toString(),
                        "FAILED",
                        maxAttempts,
                        lastErrorMessage,
                        downloadedFilesCount
                    ));
                    Utils.logStatus(keyword, "FAIL", maxAttempts,
                        "Exceeded retry limit, moving to next keyword.");
                } else {
                    Utils.logStatus(keyword, "DONE", successAttempt, "Finished keyword.");
                }
            }
            completedWithoutStop = !shouldStop();
        } catch (CancellationException ignored) {
            // stop requested by user
        } finally {
            if (completedWithoutStop) {
                Utils.logPlain("Download cycle completed. Chrome will remain open.");
            } else {
                SeleniumHelper.quitDriverQuietly(driver);
            }
            Utils.deleteDirectoryQuietly(threadDownloadDir);
        }
    }

    private boolean shouldStop() {
        return Thread.currentThread().isInterrupted() || (stopRequested != null && stopRequested.get());
    }

    private WebDriver recreateDriver(WebDriver currentDriver, Path downloadDir) {
        SeleniumHelper.quitDriverQuietly(currentDriver);
        return SeleniumHelper.createDriver(downloadDir);
    }

    private boolean isSessionBroken(Throwable throwable) {
        String message = rootMessage(throwable).toLowerCase();
        return message.contains("invalid session id")
            || message.contains("session not created")
            || message.contains("disconnected")
            || message.contains("no such window")
            || message.contains("chrome not reachable");
    }

    // ════════════════════════════════════════════════════════════════════════
    // Core flow
    // ════════════════════════════════════════════════════════════════════════

    private int performDownloadFlow2(WebDriver driver, Path downloadDir, KeywordTarget target) {
        String keyword = target.keyword();
        int expectedDownloadCount = 0;
        int downloadedCount = 0;
        Path autoDownloadFolder = resolveAutoDownloadFolder(target);

        // ── Step 1: Search → open detail page ───────────────────────────────
        navigateToDetailPage(driver, keyword);

        // ── Step 2: Trích xuất thông tin gói thầu (tab TBMT active mặc định) ─
        BidSheetRow bidInfo = extractBidInfo(driver, keyword);
        Utils.logStatus(keyword, "INFO", 0,
            "Bid info extracted.");

        boolean hasBbmtTab = hasTab(driver, TAB_BID_OPENING_MINUTES);
        boolean hasKqlcntTab = hasTab(driver, TAB_CONTRACTOR_SELECTION_RESULTS);
        boolean hasContractInfoTab = hasAnyTab(driver, TAB_CONTRACT_INFORMATION_ALIASES)
            || hasTabByHrefTargetId(driver, "contract");

        // ── Step 3: Tab "Biên bản mở thầu" (tuỳ chọn) ──────────────────────
        Path bbmtPdf = null;
        if (hasBbmtTab) {
            expectedDownloadCount++;
            Utils.logStatus(keyword, "INFO", 0, "Tab 'Biên bản mở thầu' found -> clicking...");
            clickTab(driver, TAB_BID_OPENING_MINUTES);

            String budgetFromBbmtRaw = safeReadInfoValueByLabels(driver, INFO_LABELS.get("estimatedBudget"));
            BigInteger budgetFromBbmt = parseEstimatedBudget(budgetFromBbmtRaw, keyword);
            if (budgetFromBbmt != null) {
                bidInfo = BidSheetRowBuilder.from(bidInfo)
                    .estimatedBudget(budgetFromBbmt)
                    .build();
                Utils.logStatus(keyword, "INFO", 0,
                    "Extracted estimatedBudget from tab 'Biên bản mở thầu': " + formatBudget(budgetFromBbmt));
            }

            bbmtPdf = downloadBbmtPdf(driver, downloadDir, target, keyword);
            downloadedCount++;
        } else {
            Utils.logStatus(keyword, "INFO", 0, "Tab 'Biên bản mở thầu' not present. Skipping.");
        }

        // ── Step 4: Tab "Kết quả lựa chọn nhà thầu" (tuỳ chọn) ─────────────
        Path kqlcntPdf = null;
        if (hasKqlcntTab) {
            expectedDownloadCount++;
            Utils.logStatus(keyword, "INFO", 0, "Tab 'Kết quả lựa chọn nhà thầu' found -> clicking...");
            clickTab(driver, TAB_CONTRACTOR_SELECTION_RESULTS);
            kqlcntPdf = downloadKqlcntDecision(driver, downloadDir, target, keyword);
            downloadedCount++;
        } else {
            Utils.logStatus(keyword, "INFO", 0, "Tab 'Kết quả lựa chọn nhà thầu' not present. Skipping.");
        }

        Utils.logStatus(keyword, "INFO", 0,
            "Downloaded files: " + downloadedCount + "/" + expectedDownloadCount);

        if (downloadedCount < expectedDownloadCount) {
            throw new IllegalStateException(
                "Downloaded files mismatch: " + downloadedCount + "/" + expectedDownloadCount);
        }

        Path selectedPdf = bbmtPdf != null ? bbmtPdf : kqlcntPdf;
        String contractFolderName = target.folderPath().getFileName() != null
            ? target.folderPath().getFileName().toString()
            : target.folderPath().toAbsolutePath().toString();
        String parentFolderName = target.folderPath().getParent() != null && target.folderPath().getParent().getFileName() != null
            ? target.folderPath().getParent().getFileName().toString()
            : null;
        String folderLink = selectedPdf != null && selectedPdf.getParent() != null
            ? selectedPdf.getParent().toAbsolutePath().toString()
            : autoDownloadFolder.toAbsolutePath().toString();
        Duration remainingTime = bidInfo.remainingTimeToClosing();
        BidStatus status;
        if (hasContractInfoTab) {
            status = BidStatus.CONTRACT_INFORMATION_AVAILABLE;
        } else if (hasKqlcntTab) {
            status = BidStatus.CONTRACTOR_SELECTION_RESULT_AVAILABLE;
        } else if (hasBbmtTab) {
            status = BidStatus.BID_OPENED;
        } else if (remainingTime != null && remainingTime.getSeconds() > 0) {
            status = BidStatus.INVITATION_OPEN;
        } else {
            status = BidStatus.BIDDING_CLOSED;
        }

        BidSheetRow rowToExport = BidSheetRowBuilder.from(bidInfo)
            .contractPerformanceFolder(contractFolderName)
            .parentFolderName(parentFolderName)
            .status(status)
            .folderLink(folderLink)
            .build();

        exportBidInfoJson(rowToExport, keyword);
        return downloadedCount;
    }

    // ────────────────────────────────────────────────────────────────────────
    // Step 1 helper: search + navigate
    // ────────────────────────────────────────────────────────────────────────

    private void navigateToDetailPage(WebDriver driver, String keyword) {
        SeleniumHelper.openUrlRobust(driver, baseUrl, DEFAULT_WAIT);
        SeleniumHelper.normalizeWindows(driver,
            url -> url.toLowerCase().contains("muasamcong.mpi.gov.vn"));
        Utils.logStatus(keyword, "INFO", 0, "Opened base page.");
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
        SeleniumHelper.normalizeWindows(driver,
            url -> url.toLowerCase().contains("render=detail-v2"));

        if (SeleniumHelper.isDisposableCurrentUrl(driver)
                || !SeleniumHelper.isRelevantCurrentUrl(driver)) {
            throw new IllegalStateException(
                "Unexpected page after opening detail.");
        }

        SeleniumHelper.dismissPopupIfPresent(driver);
        Utils.logStatus(keyword, "INFO", 0, "Detail page loaded.");
    }

    // ────────────────────────────────────────────────────────────────────────
    // Step 2 helper: extract bid info
    // ────────────────────────────────────────────────────────────────────────

    /**
     * Trích xuất thông tin từ tab TBMT (đang active mặc định).
     * Cấu trúc HTML: div.infomation__content > [div.title | div.value]
     * Không ném exception — parse lỗi trả về null và log WARN.
     */
    private BidSheetRow extractBidInfo(WebDriver driver, String keyword) {
        String packageName      = safeReadInfoValue(driver, "Tên gói thầu");
        String procuringEntity  = safeReadInfoValue(driver, "Chủ đầu tư");
        String bidClosingRaw    = safeReadInfoValue(driver, "Thời điểm đóng thầu");
        String remainingRaw     = safeGetRemainingTime(driver);

        LocalDateTime bidClosingTime = parseDateTime(bidClosingRaw, keyword);
        Duration remainingTime       = parseDuration(remainingRaw, keyword);

        return BidSheetRowBuilder.create()
            .tmbtNumber(keyword)
            .packageName(packageName)
            .procuringEntity(procuringEntity)
            .bidClosingTime(bidClosingTime)
            .remainingTimeToClosing(remainingTime)
            .build();
    }

    /**
     * Đọc value của ô trong bảng infomation__content có label khớp labelText.
     * XPath: tìm .infomation__content có title chứa label → lấy div con đầu tiên không phải title.
     */
    private String safeReadInfoValue(WebDriver driver, String labelText) {
        String normalizedTarget = normalizeComparableText(labelText);
        try {
            List<WebElement> rows = driver.findElements(
                By.xpath("//div[contains(@class,'infomation__content')]")
            );
            for (WebElement row : rows) {
                List<WebElement> titles = row.findElements(
                    By.xpath(".//div[contains(@class,'infomation__content__title')]")
                );
                if (titles.isEmpty()) {
                    continue;
                }
                String titleText = normalizeComparableText(titles.get(0).getText());
                if (!titleText.contains(normalizedTarget)) {
                    continue;
                }

                List<WebElement> values = row.findElements(
                    By.xpath("./div[not(contains(@class,'infomation__content__title'))]")
                );
                for (WebElement value : values) {
                    String text = value.getText();
                    if (text != null) {
                        String trimmed = text.trim();
                        if (!trimmed.isEmpty()) {
                            return trimmed;
                        }
                    }
                }
            }
        } catch (Exception ignored) {
            // fallback to XPath lookup below
        }

        String xpath =
            "//div[contains(@class,'infomation__content')]"
            + "[.//div[contains(@class,'infomation__content__title')]"
            + "[contains(normalize-space(),'" + labelText + "')]]"
            + "/div[not(contains(@class,'infomation__content__title'))][1]";
        return safeGetText(driver, By.xpath(xpath), Duration.ofSeconds(8));
    }

    private String safeReadInfoValueByLabels(WebDriver driver, List<String> labels) {
        for (String label : labels) {
            String value = safeReadInfoValue(driver, label);
            if (value != null && !value.isBlank()) {
                return value;
            }
        }
        return null;
    }

    /**
     * Lấy text "Còn lại: X ngày Y giờ Z phút" từ sidebar bên phải.
     * Thử selector trước, fallback quét tất cả <p>.
     */
    private String safeGetRemainingTime(WebDriver driver) {
        String result = safeGetText(driver, REMAINING_TIME, Duration.ofSeconds(5));
        if (result != null && !result.isBlank()) {
            return result;
        }
        try {
            for (WebElement p : driver.findElements(By.tagName("p"))) {
                String text = p.getText().trim();
                if (text.contains("Còn lại") || text.contains("Con lai")) {
                    return text;
                }
            }
        } catch (Exception ignored) { /* best effort */ }
        return null;
    }

    private String safeGetText(WebDriver driver, By locator, Duration timeout) {
        WebDriverWait wait = new WebDriverWait(driver, timeout);
        try {
            return wait.until(current -> {
                List<WebElement> els = current.findElements(locator);
                if (els.isEmpty()) {
                    return null;
                }
                for (WebElement el : els) {
                    String text = el.getText();
                    if (text == null) {
                        continue;
                    }
                    String trimmed = text.trim();
                    if (!trimmed.isEmpty()) {
                        return trimmed;
                    }
                }
                return null;
            });
        } catch (Exception ex) {
            return null;
        }
    }

    private String normalizeComparableText(String raw) {
        if (raw == null) {
            return "";
        }
        String value = raw.replace('"', ' ').replace('\'', ' ');
        String decomposed = Normalizer.normalize(value, Normalizer.Form.NFD);
        String ascii = decomposed.replaceAll("\\p{M}+", "").replace('đ', 'd').replace('Đ', 'D');
        return ascii.toLowerCase().replaceAll("\\s+", " ").trim();
    }

    /** Parse "dd/MM/yyyy HH:mm" (hoặc biến thể d/M) → LocalDateTime, null nếu lỗi. */
    private LocalDateTime parseDateTime(String raw, String keyword) {
        if (raw == null || raw.isBlank()) return null;
        String trimmed = raw.trim();
        DateTimeFormatter[] fmts = {
            DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm"),
            DateTimeFormatter.ofPattern("d/M/yyyy HH:mm"),
            DateTimeFormatter.ofPattern("d/M/yyyy H:mm"),
            DateTimeFormatter.ofPattern("dd/MM/yyyy H:mm"),
        };
        for (DateTimeFormatter fmt : fmts) {
            try { return LocalDateTime.parse(trimmed, fmt); }
            catch (Exception ignored) { /* try next */ }
        }
        Utils.logStatus(keyword, "WARN", 0, "Cannot parse bidClosingTime: '" + trimmed + "'");
        return null;
    }

    /** Parse "X ngày Y giờ Z phút" → Duration, null nếu lỗi. */
    private Duration parseDuration(String raw, String keyword) {
        if (raw == null || raw.isBlank()) return null;
        try {
            String cleaned = raw
                .replaceAll("(?i)còn\\s*lại\\s*:", "")
                .replaceAll("(?i)con\\s*lai\\s*:", "")
                .trim();
            long minutes = 0;
            boolean matched = false;
            Matcher d = Pattern.compile("(\\d+)\\s*(?:ngày|ngay)", Pattern.CASE_INSENSITIVE).matcher(cleaned);
            Matcher h = Pattern.compile("(\\d+)\\s*(?:giờ|gio)", Pattern.CASE_INSENSITIVE).matcher(cleaned);
            Matcher m = Pattern.compile("(\\d+)\\s*(?:phút|phut)", Pattern.CASE_INSENSITIVE).matcher(cleaned);
            if (d.find()) {
                minutes += Long.parseLong(d.group(1)) * 24 * 60;
                matched = true;
            }
            if (h.find()) {
                minutes += Long.parseLong(h.group(1)) * 60;
                matched = true;
            }
            if (m.find()) {
                minutes += Long.parseLong(m.group(1));
                matched = true;
            }
            if (!matched) {
                Utils.logStatus(keyword, "WARN", 0, "Cannot parse remainingTime: '" + raw + "'");
                return null;
            }
            return Duration.ofMinutes(minutes);
        } catch (Exception ex) {
            Utils.logStatus(keyword, "WARN", 0, "Cannot parse remainingTime: '" + raw + "'");
            return null;
        }
    }

    /** Normalize budget text from page, null nếu rỗng. */
    private BigInteger parseEstimatedBudget(String raw, String keyword) {
        if (raw == null || raw.isBlank()) return null;
        try {
            String digits = raw.replaceAll("[^0-9]", "");
            if (digits.isBlank()) {
                return null;
            }
            return new BigInteger(digits);
        } catch (Exception ex) {
            Utils.logStatus(keyword, "WARN", 0, "Cannot parse estimatedBudget: '" + raw + "'");
            return null;
        }
    }

    private String formatBudget(BigInteger value) {
        if (value == null) {
            return "";
        }
        String digits = value.toString();
        StringBuilder sb = new StringBuilder(digits.length() + (digits.length() / 3));
        int first = digits.length() % 3;
        if (first == 0) {
            first = 3;
        }
        sb.append(digits, 0, first);
        for (int i = first; i < digits.length(); i += 3) {
            sb.append('.').append(digits, i, i + 3);
        }
        return sb.toString();
    }

    // ────────────────────────────────────────────────────────────────────────
    // Step 3 helper: BBMT download
    // ────────────────────────────────────────────────────────────────────────

    private Path downloadBbmtPdf(
        WebDriver driver, Path downloadDir, KeywordTarget target, String keyword
    ) {
        waitForBbmtContentReady(driver);
        List<By> bbmtTriggers = List.of(
            BBMT_DOWNLOAD_ICON_EXACT,
            BBMT_DOWNLOAD_ICON,
            BBMT_DOWNLOAD_ICON_FALLBACK
        );

        int attempts = 3;
        for (int attempt = 1; attempt <= attempts; attempt++) {
            String detailUrlBeforeClick = driver.getCurrentUrl();
            Set<Path> before = Utils.snapshotPdfFiles(downloadDir);
            Utils.logStatus(keyword, "INFO", attempt,
                "BBMT attempt started: locating download trigger.");
            WebElement attachment = findFirstClickable(driver, bbmtTriggers, Duration.ofSeconds(15));
            long clickStartedAt = System.nanoTime();
            clickBbmtTrigger(driver, attachment);
            Utils.logStatus(keyword, "INFO", attempt,
                "BBMT trigger clicked. Waiting for PDF to appear...");
            SeleniumHelper.normalizeWindows(driver,
                url -> url.toLowerCase().contains("render=detail-v2"));

            if (SeleniumHelper.isDisposableCurrentUrl(driver)) {
                Utils.logStatus(keyword, "WARN", attempt,
                    "BBMT click navigated to disposable page. Restoring detail page and retrying.");
                if (detailUrlBeforeClick != null && !detailUrlBeforeClick.isBlank()) {
                    driver.navigate().to(detailUrlBeforeClick);
                    SeleniumHelper.dismissPopupIfPresent(driver);
                }
                continue;
            }

            Path downloaded = Utils.safeWaitForPdf(downloadDir, before, DOWNLOAD_TIMEOUT);
            if (downloaded != null) {
                Path finalFile = Utils.movePdfToTargetFolder(downloaded, resolveAutoDownloadFolder(target));
                long elapsedMs = (System.nanoTime() - clickStartedAt) / 1_000_000L;
                Utils.logStatus(keyword, "INFO", attempt,
                    "BBMT PDF saved: " + finalFile.getFileName() + " | waitMs=" + elapsedMs);
                return finalFile;
            }

            Utils.logStatus(keyword, "WARN", attempt,
                "BBMT download timed out — no new file appeared.");
        }

        throw new IllegalStateException("BBMT download failed after retry attempts.");
    }

    private void clickBbmtTrigger(WebDriver driver, WebElement attachment) {
        try {
            SeleniumHelper.safeClick(driver, attachment);
            return;
        } catch (Exception ignored) {
            try {
                ((JavascriptExecutor) driver).executeScript("arguments[0].click();", attachment);
                return;
            } catch (Exception ignoredJs) {
                // fallback to row/container click below
            }
        }

        List<WebElement> rows = attachment.findElements(
            By.xpath("./ancestor::div[contains(@class,'detail__children-2__view__item')][1]")
        );
        if (!rows.isEmpty() && rows.get(0).isDisplayed()) {
            SeleniumHelper.safeClick(driver, rows.get(0));
            return;
        }

        throw new IllegalStateException("Cannot click BBMT download trigger.");
    }

    private void waitForBbmtContentReady(WebDriver driver) {
        WebDriverWait wait = new WebDriverWait(driver, Duration.ofSeconds(12));
        wait.until(current -> hasAnyVisible(current,
            List.of(
                BBMT_DOWNLOAD_ICON_EXACT,
                BBMT_DOWNLOAD_ICON,
                BBMT_DOWNLOAD_ICON_FALLBACK
            )));
    }

    private boolean hasAnyVisible(WebDriver driver, List<By> locators) {
        for (By locator : locators) {
            List<WebElement> elements = driver.findElements(locator);
            for (WebElement element : elements) {
                if (element.isDisplayed()) {
                    return true;
                }
            }
        }
        return false;
    }

    // ────────────────────────────────────────────────────────────────────────
    // Step 4 helper: KQLCNT download
    // ────────────────────────────────────────────────────────────────────────

    private Path downloadKqlcntDecision(
        WebDriver driver, Path downloadDir, KeywordTarget target, String keyword
    ) {
        Utils.logStatus(keyword, "INFO", 0,
            "KQLCNT: locating decision attachment.");
        WebElement attachment = findFirstClickable(driver,
            List.of(KQLCNT_DECISION_PRIMARY, KQLCNT_DECISION_FALLBACK), Duration.ofSeconds(15));

        Set<Path> before = Utils.snapshotPdfFiles(downloadDir);
        long clickStartedAt = System.nanoTime();
        SeleniumHelper.safeClick(driver, attachment);
        Utils.logStatus(keyword, "INFO", 0,
            "KQLCNT trigger clicked. Waiting for PDF to appear...");
        SeleniumHelper.normalizeWindows(driver,
            url -> url.toLowerCase().contains("render=detail-v2"));

        if (SeleniumHelper.isDisposableCurrentUrl(driver)) {
            throw new IllegalStateException(
                "Browser navigated away after KQLCNT download click: "
                + driver.getCurrentUrl());
        }

        Path downloaded = Utils.safeWaitForPdf(downloadDir, before, DOWNLOAD_TIMEOUT);
        if (downloaded == null) {
            throw new TimeoutException(
                "Download timeout waiting for KQLCNT Quyết định phê duyệt PDF.");
        }
        if (!Utils.isNonEmptyPdf(downloaded)) {
            throw new IllegalStateException(
                "Downloaded KQLCNT file is invalid or empty: " + downloaded);
        }

        Path finalFile = Utils.movePdfToTargetFolder(downloaded, resolveAutoDownloadFolder(target));
        long elapsedMs = (System.nanoTime() - clickStartedAt) / 1_000_000L;
        Utils.logStatus(keyword, "INFO", 0,
            "KQLCNT decision PDF saved: " + finalFile.getFileName() + " | waitMs=" + elapsedMs);
        return finalFile;
    }

    private Path resolveAutoDownloadFolder(KeywordTarget target) {
        if (target == null || target.folderPath() == null) {
            throw new IllegalArgumentException("Target folder path is missing.");
        }
        return target.folderPath().resolve(AUTO_DOWNLOAD_FOLDER_NAME).toAbsolutePath().normalize();
    }

    // ────────────────────────────────────────────────────────────────────────
    // Tab helpers
    // ────────────────────────────────────────────────────────────────────────

    /** Trả về true nếu tồn tại tab có text chứa tabText. */
    private boolean hasTab(WebDriver driver, String tabText) {
        try {
            for (WebElement tab : driver.findElements(ALL_TABS)) {
                if (tab.getText().trim().contains(tabText)) return true;
            }
        } catch (Exception ignored) { /* best effort */ }
        return false;
    }

    private boolean hasAnyTab(WebDriver driver, List<String> tabTexts) {
        if (tabTexts == null || tabTexts.isEmpty()) {
            return false;
        }
        for (String tabText : tabTexts) {
            if (tabText != null && !tabText.isBlank() && hasTab(driver, tabText)) {
                return true;
            }
        }
        return false;
    }

    private boolean hasTabByHrefTargetId(WebDriver driver, String targetId) {
        if (targetId == null || targetId.isBlank()) {
            return false;
        }
        String expected = targetId.trim();
        try {
            for (WebElement tab : driver.findElements(ALL_TABS)) {
                String href = tab.getAttribute("href");
                String currentTargetId = extractTargetId(href);
                if (currentTargetId != null && currentTargetId.equalsIgnoreCase(expected)) {
                    return true;
                }
            }
        } catch (Exception ignored) {
            // best effort
        }
        return false;
    }

    /**
     * Click tab có text chứa tabText rồi chờ ngắn để content render.
     * Ném NoSuchElementException nếu không tìm thấy.
     */
    private void clickTab(WebDriver driver, String tabText) {
        By tabLocator = By.xpath(
            "//a[@data-toggle='tab' and contains(normalize-space(),\"" + tabText + "\")]"
        );

        List<WebElement> tabs = driver.findElements(tabLocator);
        if (!tabs.isEmpty()) {
            WebElement tab = tabs.get(0);
            String href = tab.getAttribute("href");
            String targetId = extractTargetId(href);
            SeleniumHelper.safeClick(driver, tab);
            waitForTabActivated(driver, tabLocator, targetId);
            return;
        }

        throw new NoSuchElementException("Tab not found: " + tabText);
    }

    // ────────────────────────────────────────────────────────────────────────
    // Generic helpers
    // ────────────────────────────────────────────────────────────────────────

    /**
     * Thử từng locator trong danh sách theo thứ tự, trả về element đầu tiên clickable.
     * Ném NoSuchElementException nếu tất cả đều thất bại.
     */
    private WebElement findFirstClickable(WebDriver driver, List<By> locators, Duration timeout) {
        boolean anyPresent = false;
        for (By locator : locators) {
            if (!driver.findElements(locator).isEmpty()) {
                anyPresent = true;
                break;
            }
        }

        if (!anyPresent) {
            try {
                new WebDriverWait(driver, timeout).until(current -> {
                    for (By locator : locators) {
                        if (!current.findElements(locator).isEmpty()) {
                            return true;
                        }
                    }
                    return false;
                });
            } catch (TimeoutException ignored) {
                throw new NoSuchElementException("No attachment element found in active tab.");
            }
        }

        for (By locator : locators) {
            if (!driver.findElements(locator).isEmpty()) {
                try {
                    return SeleniumHelper.waitClickable(driver, locator, QUICK_LOCATOR_WAIT);
                } catch (TimeoutException ignored) {
                    // try next locator
                }
            }
        }

        long perLocatorSeconds = Math.max(2, timeout.getSeconds() / Math.max(1, locators.size()));
        Duration perLocatorTimeout = Duration.ofSeconds(perLocatorSeconds);
        for (By locator : locators) {
            try {
                return SeleniumHelper.waitClickable(driver, locator, perLocatorTimeout);
            } catch (TimeoutException ignored) {
                // try next locator
            }
        }
        throw new NoSuchElementException(
            "None of the provided locators matched a clickable element.");
    }

    private String extractTargetId(String href) {
        if (href == null || href.isBlank()) {
            return null;
        }
        int hashPos = href.indexOf('#');
        if (hashPos < 0 || hashPos == href.length() - 1) {
            return null;
        }
        return href.substring(hashPos + 1);
    }

    private void waitForTabActivated(WebDriver driver, By tabLocator, String targetId) {
        WebDriverWait wait = new WebDriverWait(driver, TAB_SWITCH_WAIT);
        wait.until(current -> {
            List<WebElement> tabs = current.findElements(tabLocator);
            boolean tabActive = !tabs.isEmpty() && tabs.get(0).getAttribute("class").contains("active");

            if (!tabActive) {
                return false;
            }
            if (targetId == null) {
                return true;
            }

            List<WebElement> panes = current.findElements(By.id(targetId));
            if (panes.isEmpty()) {
                return true;
            }
            String paneClass = panes.get(0).getAttribute("class");
            return paneClass.contains("active") || paneClass.contains("show") || panes.get(0).isDisplayed();
        });
    }

    private String rootMessage(Throwable throwable) {
        Throwable root = throwable;
        while (root.getCause() != null) root = root.getCause();
        String message = root.getMessage();
        return (message == null || message.isBlank())
            ? root.getClass().getSimpleName() : message;
    }

    private void exportBidInfoJson(BidSheetRow bidInfo, String keyword) {
        synchronized (BID_INFO_JSON_LOCK) {
            Utils.ensureDirectory(BID_INFO_DATA_DIR);
            BID_INFO_ROWS.add(bidInfo);
            persistBidInfoRows();
            Utils.logStatus(keyword, "INFO", 0, "Bid info exported.");
        }
    }

    private static String buildBidInfoListJson(List<BidSheetRow> rows) {
        StringBuilder sb = new StringBuilder();
        sb.append("{\n");
        sb.append("  \"schemaVersion\": ").append(BID_INFO_SCHEMA_VERSION).append(",\n");
        sb.append("  \"updatedAt\": ").append(quoteJson(LocalDateTime.now().format(JSON_TIME_FORMAT))).append(",\n");
        sb.append("  \"rows\": [\n");
        for (int i = 0; i < rows.size(); i++) {
            sb.append(buildBidInfoItemJson(rows.get(i)));
            if (i < rows.size() - 1) {
                sb.append(",\n");
            } else {
                sb.append('\n');
            }
        }
        sb.append("  ]\n");
        sb.append('}');
        return sb.toString();
    }

    private static JsonNode extractRowsNode(JsonNode root) {
        if (root == null || root.isNull()) {
            return null;
        }
        if (root.isArray()) {
            return root;
        }
        JsonNode rowsNode = root.get("rows");
        return rowsNode != null && rowsNode.isArray() ? rowsNode : null;
    }

    private static String buildBidInfoItemJson(BidSheetRow row) {
        String bidClosing = row.bidClosingTime() == null ? null : row.bidClosingTime().format(JSON_TIME_FORMAT);
        Long remainingSeconds = row.remainingTimeToClosing() == null ? null : row.remainingTimeToClosing().getSeconds();
        String estimatedBudget = row.estimatedBudget() == null ? null : row.estimatedBudget().toString();

        return "{\n"
            + "  \"tmbtNumber\": " + quoteJson(row.tmbtNumber()) + ",\n"
            + "  \"packageName\": " + quoteJson(row.packageName()) + ",\n"
            + "  \"procuringEntity\": " + quoteJson(row.procuringEntity()) + ",\n"
            + "  \"contractPerformanceFolder\": " + quoteJson(row.contractPerformanceFolder()) + ",\n"
            + "  \"parentFolderName\": " + quoteJson(row.parentFolderName()) + ",\n"
            + "  \"estimatedBudget\": " + quoteJson(estimatedBudget) + ",\n"
            + "  \"status\": " + (row.status() == null ? "null" : quoteJson(row.status().name())) + ",\n"
            + "  \"bidClosingTime\": " + quoteJson(bidClosing) + ",\n"
            + "  \"remainingTimeSeconds\": " + (remainingSeconds == null ? "null" : remainingSeconds) + ",\n"
            + "  \"folderLink\": " + quoteJson(row.folderLink()) + "\n"
            + "}";
    }

    private static String quoteJson(String value) {
        if (value == null) {
            return "null";
        }
        String escaped = value
            .replace("\\", "\\\\")
            .replace("\"", "\\\"")
            .replace("\r", "\\r")
            .replace("\n", "\\n")
            .replace("\t", "\\t");
        return "\"" + escaped + "\"";
    }
}
