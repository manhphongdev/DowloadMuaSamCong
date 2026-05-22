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
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
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
import vn.muasamcong.downloader.model.DownloadHints;
import vn.muasamcong.downloader.model.KeywordTarget;
import vn.muasamcong.downloader.store.BidTrackingRecordStore;
import vn.muasamcong.downloader.store.RunStateStore;
import vn.muasamcong.downloader.util.SeleniumHelper;
import vn.muasamcong.downloader.util.Utils;
import org.openqa.selenium.By;
import org.openqa.selenium.JavascriptExecutor;
import org.openqa.selenium.NoSuchElementException;
import org.openqa.selenium.StaleElementReferenceException;
import org.openqa.selenium.TimeoutException;
import org.openqa.selenium.WebDriver;
import org.openqa.selenium.WebElement;
import org.openqa.selenium.support.ui.WebDriverWait;

public final class DownloadWorker implements Runnable {

    private static final String AUTO_DOWNLOAD_FOLDER_NAME = "auto-download";
    private static final String HSMT_CLARIFICATION_FOLDER_NAME = "lam-ro-hsmt";
    private static final String PETITION_FOLDER_NAME = "kien-nghi";
    private static final Duration DEFAULT_WAIT = Duration.ofSeconds(25);
    private static final Duration DOWNLOAD_TIMEOUT = Duration.ofSeconds(60);
    private static final Duration KQLCNT_TRIGGER_WAIT = Duration.ofSeconds(4);
    private static final Duration KQLCNT_DOWNLOAD_TIMEOUT = Duration.ofSeconds(20);
    private static final Duration TAB_SWITCH_WAIT = Duration.ofSeconds(6);
    private static final Duration QUICK_LOCATOR_WAIT = Duration.ofSeconds(2);
    private static final Set<String> EXCEL_EXTENSIONS = Set.of(".xlsx", ".xls", ".xlsm");
    private static final Set<String> ATTACHMENT_EXTENSIONS = Set.of(
        ".pdf", ".doc", ".docx", ".xls", ".xlsx", ".xlsm", ".zip", ".rar", ".7z"
    );
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
    private static final By TENDER_INFO_GENERAL_TAB = By.xpath(
        "//div[@id='tenderNotice']//a[@data-toggle='tab' and contains(@href,'#info-general')]"
    );
    private static final By TENDER_INFO_GENERAL_PANE = By.id("info-general");
    private static final By TENDER_HSMT_CLARIFICATION_TAB = By.xpath(
        "//div[@id='tenderNotice']//a[@data-toggle='tab' and contains(@href,'#clear-HSMT')]"
    );
    private static final By TENDER_HSMT_CLARIFICATION_PANE = By.id("clear-HSMT");
    private static final By TENDER_PETITION_TAB = By.xpath(
        "//div[@id='tenderNotice']//a[@data-toggle='tab' and contains(@href,'#kien-nghi')]"
    );
    private static final By TENDER_PETITION_PANE = By.id("kien-nghi");

    private static final Map<String, List<String>> INFO_LABELS = Map.of(
        "estimatedBudget", List.of(
            "Dự toán gói thầu",
            "Dự toán gói thầu được duyệt sau khi phê duyệt KHLCNT"
        ),
        "bidClosingTime", List.of(
            "Thời điểm đóng thầu",
            "Thời điểm kết thúc chào giá trực tuyến"
    ));

    private static final By REMAINING_TIME = By.xpath(
        "//*[contains(@class,'detail__children-2__text-center')]"
        + "[contains(normalize-space(),'Còn lại') "
        + " or contains(normalize-space(),'Con lai')]"
    );

    // ── Detail-page: file đính kèm trong tab BBMT ───────────────────────────
    private static final By BBMT_INFO_PANE = By.xpath(
        "//*[@id='bidOpeningMinutes' or @id='bbmtKqm'"
        + " or contains(@class,'bidOpeningMinutes') or contains(@class,'bbmtKqm')]"
    );
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
    private static final By KQLCNT_EHSDT_REPORT_PRIMARY = By.xpath(
        "//div[@id='contractorSelectionResults']"
        + "//div[contains(@class,'infomation__content')]"
        + "[.//div[contains(@class,'infomation__content__title')]"
        + "[contains(normalize-space(),'Báo cáo đánh giá tổng hợp E-HSDT')"
        + " or contains(normalize-space(),'Bao cao danh gia tong hop E-HSDT')"
        + " or contains(normalize-space(),'Báo cáo đánh giá HSDT')"
        + " or contains(normalize-space(),'Bao cao danh gia HSDT')]]"
        + "//*[contains(@class,'tags-fileAttach')]"
    );
    private static final By KQLCNT_WINNING_PRICE_EXCEL_BUTTON = By.xpath(
        "//div[@id='contractorSelectionResults']"
        + "//span[contains(normalize-space(),'Bảng giá trúng thầu')]"
        + "/following::button[contains(normalize-space(),'Xuất Excel')][1]"
    );

    // ────────────────────────────────────────────────────────────────────────

    private final Queue<KeywordTarget> keywordQueue;
    private final String baseUrl;
    private final Path baseDownloadDir;
    private final int maxRetries;
    private final RunStats stats;
    private final AtomicBoolean stopRequested;
    private final int workerSlot;
    private final Map<String, DownloadHints> downloadHintsByTargetKey;
    private final Map<String, String> detailUrlByTargetKey;

    public DownloadWorker(
        Queue<KeywordTarget> keywordQueue,
        String baseUrl,
        Path baseDownloadDir,
        int maxRetries,
        RunStats stats,
        AtomicBoolean stopRequested,
        int workerSlot,
        Map<String, DownloadHints> downloadHintsByTargetKey,
        Map<String, String> detailUrlByTargetKey
    ) {
        this.keywordQueue = keywordQueue;
        this.baseUrl = baseUrl;
        this.baseDownloadDir = baseDownloadDir;
        this.maxRetries = maxRetries;
        this.stats = stats;
        this.stopRequested = stopRequested;
        this.workerSlot = Math.max(1, workerSlot);
        this.downloadHintsByTargetKey = downloadHintsByTargetKey == null ? Map.of() : downloadHintsByTargetKey;
        this.detailUrlByTargetKey = detailUrlByTargetKey == null ? Map.of() : detailUrlByTargetKey;
    }

    public static void prepareBidInfoOutput() {
        synchronized (BID_INFO_JSON_LOCK) {
            Utils.ensureDirectory(BID_INFO_DATA_DIR);
            BID_INFO_ROWS.clear();
            BID_INFO_ROWS.addAll(deduplicateRows(loadBidInfoRowsFromDisk()));
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

    public static void replaceBidInfoRows(Collection<BidSheetRow> rows) {
        synchronized (BID_INFO_JSON_LOCK) {
            Utils.ensureDirectory(BID_INFO_DATA_DIR);
            BID_INFO_ROWS.clear();
            if (rows != null) {
                BID_INFO_ROWS.addAll(deduplicateRows(new ArrayList<>(rows)));
            }
            persistBidInfoRows();
        }
    }

    public static int removeBidInfoRowsByStateRecords(Collection<RunStateStore.StateRecord> stateRecords) {
        if (stateRecords == null || stateRecords.isEmpty()) {
            return 0;
        }
        LinkedHashSet<String> keys = new LinkedHashSet<>();
        for (RunStateStore.StateRecord record : stateRecords) {
            String key = buildRowKeyFromStateRecord(record);
            if (key != null && !key.isBlank()) {
                keys.add(key);
            }
        }
        if (keys.isEmpty()) {
            return 0;
        }

        synchronized (BID_INFO_JSON_LOCK) {
            Utils.ensureDirectory(BID_INFO_DATA_DIR);
            BID_INFO_ROWS.clear();
            BID_INFO_ROWS.addAll(deduplicateRows(loadBidInfoRowsFromDisk()));
            int before = BID_INFO_ROWS.size();
            BID_INFO_ROWS.removeIf(row -> keys.contains(buildRowKey(row)));
            int removed = before - BID_INFO_ROWS.size();
            if (removed > 0) {
                persistBidInfoRows();
            }
            return removed;
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

        LocalDateTime postedDate = null;
        String postedDateRaw = textValue(node, "postedDate");
        if (postedDateRaw != null && !postedDateRaw.isBlank()) {
            try {
                postedDate = LocalDateTime.parse(postedDateRaw);
            } catch (DateTimeParseException ignored) {
                postedDate = null;
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
            .postedDate(postedDate)
            .packageExecutionTime(textValue(node, "packageExecutionTime"))
            .winningBidPrice(parseEstimatedBudgetValue(node.get("winningBidPrice")))
            .status(status)
            .bidClosingTime(bidClosingTime)
            .remainingTimeToClosing(remaining)
            .folderLink(textValue(node, "folderLink"))
            .tenderLink(textValue(node, "tenderLink"))
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
        KeywordTarget activeTarget = null;
        int activeDownloadedFilesCount = 0;
        Path threadDownloadDir = baseDownloadDir.resolve("_worker_tmp_" + workerSlot);
        String reusableDriverKey = "download-worker-" + workerSlot;
        int maxAttempts = maxRetries + 1;

        try {
            driver = SeleniumHelper.getOrCreateReusableDriver(reusableDriverKey, threadDownloadDir, workerSlot);

            while (true) {
                if (shouldStop()) {
                    break;
                }

                KeywordTarget target = keywordQueue.poll();
                if (target == null) break;

                activeTarget = target;

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
                        activeDownloadedFilesCount = downloadedFilesCount;

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
                            driver = recreateDriver(reusableDriverKey, driver, threadDownloadDir);
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
                activeTarget = null;
                activeDownloadedFilesCount = 0;

            }
            completedWithoutStop = !shouldStop();
        } catch (CancellationException ex) {
            if (activeTarget != null) {
                RunStateStore.markStopped(activeTarget, 0, "Stopped by user", activeDownloadedFilesCount);
                stats.addProcessedRecord(new RunStats.ProcessedRecord(
                    activeTarget.keyword(),
                    activeTarget.folderPath() == null ? null : activeTarget.folderPath().toAbsolutePath().normalize().toString(),
                    "STOPPED",
                    0,
                    "Stopped by user",
                    activeDownloadedFilesCount
                ));
                Utils.logStatus(activeTarget.keyword(), "STOP", 0, "Stopped by user.");
            }
        } catch (Throwable ex) {
            String message = rootMessage(ex);
            Utils.logPlain("Worker slot " + workerSlot + " stopped before processing keywords: " + message);
        } finally {
            if (completedWithoutStop) {
                Utils.logPlain("Download cycle completed. Chrome session closed.");
            } else {
                Utils.logPlain("Download cycle stopped. Chrome session closed.");
            }
            SeleniumHelper.discardReusableDriver(reusableDriverKey, driver);
            Utils.deleteDirectoryQuietly(threadDownloadDir);
        }
    }

    private boolean shouldStop() {
        return Thread.currentThread().isInterrupted() || (stopRequested != null && stopRequested.get());
    }

    private WebDriver recreateDriver(String reusableDriverKey, WebDriver currentDriver, Path downloadDir) {
        return SeleniumHelper.replaceReusableDriver(reusableDriverKey, currentDriver, downloadDir, workerSlot);
    }

    private boolean isSessionBroken(Throwable throwable) {
        String message = rootMessage(throwable).toLowerCase();
        return message.contains("invalid session id")
            || message.contains("session not created")
            || message.contains("disconnected")
            || message.contains("no such window")
            || message.contains("chrome not reachable")
            || message.contains("closedchannelexception")
            || message.contains("connection reset")
            || message.contains("connection refused");
    }

    // ════════════════════════════════════════════════════════════════════════
    // Core flow
    // ════════════════════════════════════════════════════════════════════════

    private int performDownloadFlow2(WebDriver driver, Path downloadDir, KeywordTarget target) {
        String keyword = target.keyword();
        DownloadHints hints = downloadHintsByTargetKey.getOrDefault(targetKey(target), DownloadHints.unknown());
        int expectedDownloadCount = 0;
        int downloadedCount = 0;

        if (hints.resolvedFromApi() && !hints.hasSeleniumDownloads()) {
            Utils.logStatus(keyword, "INFO", 0,
                "API hints show no Selenium downloads needed. Skipping browser download.");
            Utils.logStatus(keyword, "INFO", 0, "Downloaded files: 0/0");
            return 0;
        }

        // ── Step 1: Open detail page ────────────────────────────────────────
        String directDetailUrl = detailUrlByTargetKey.get(targetKey(target));
        if (directDetailUrl != null && !directDetailUrl.isBlank()) {
            navigateDirectToDetailPage(driver, directDetailUrl, keyword);
        } else {
            navigateToDetailPage(driver, keyword);
        }

        // Store stable API params for later monitor/API-based sheet sync.
        String detailUrl = driver.getCurrentUrl();
        BidTrackingRecordStore.upsertFromDetailUrl(target, detailUrl);
        Utils.logStatus(keyword, "INFO", 0,
            "Bid tracking record saved.");

        if (hints.needTenderNoticeAttachments()) {
            int tenderNoticeAttachmentCount = downloadTenderNoticeAttachments(driver, downloadDir, target, keyword, hints);
            expectedDownloadCount += tenderNoticeAttachmentCount;
            downloadedCount += tenderNoticeAttachmentCount;
        } else {
            Utils.logStatus(keyword, "INFO", 0, "API hints: TBMT attachments not required. Skipping.");
        }

        boolean needsKqlcntTab = hints.needKqlcntDecisionPdf() || hints.needKqlcntEvaluationReport();
        boolean hasBbmtTab = hints.needBbmtPdf() && hasTab(driver, TAB_BID_OPENING_MINUTES);
        boolean hasKqlcntTab = needsKqlcntTab && hasTab(driver, TAB_CONTRACTOR_SELECTION_RESULTS);

        // ── Step 2: Tab "Biên bản mở thầu" (tuỳ chọn) ──────────────────────
        if (hints.needBbmtPdf() && hasBbmtTab) {
            expectedDownloadCount++;
            Utils.logStatus(keyword, "INFO", 0, "Tab 'Biên bản mở thầu' found -> clicking...");
            clickTab(driver, TAB_BID_OPENING_MINUTES);
            waitForBbmtInfoReady(driver);
            downloadBbmtPdf(driver, downloadDir, target, keyword);
            downloadedCount++;
        } else if (!hints.needBbmtPdf()) {
            Utils.logStatus(keyword, "INFO", 0, "API hints: BBMT not required. Skipping.");
        } else {
            Utils.logStatus(keyword, "INFO", 0, "Tab 'Biên bản mở thầu' not present. Skipping.");
        }

        // ── Step 3: Tab "Kết quả lựa chọn nhà thầu" (tuỳ chọn) ─────────────
        if (needsKqlcntTab && hasKqlcntTab) {
            Utils.logStatus(keyword, "INFO", 0, "Tab 'Kết quả lựa chọn nhà thầu' found -> clicking...");
            clickTab(driver, TAB_CONTRACTOR_SELECTION_RESULTS);

            if (hints.needKqlcntDecisionPdf()) {
                expectedDownloadCount++;
                downloadKqlcntDecision(driver, downloadDir, target, keyword);
                downloadedCount++;
            }

            if (hints.needKqlcntEvaluationReport()) {
                expectedDownloadCount++;
                downloadKqlcntEhsdtReport(driver, downloadDir, target, keyword);
                downloadedCount++;
            }
        } else if (!needsKqlcntTab) {
            Utils.logStatus(keyword, "INFO", 0, "API hints: KQLCNT PDF downloads not required. Skipping.");
        } else {
            Utils.logStatus(keyword, "INFO", 0, "Tab 'Kết quả lựa chọn nhà thầu' not present. Skipping.");
        }

        Utils.logStatus(keyword, "INFO", 0,
            "Downloaded files: " + downloadedCount + "/" + expectedDownloadCount);

        if (downloadedCount < expectedDownloadCount) {
            throw new IllegalStateException(
                "Downloaded files mismatch: " + downloadedCount + "/" + expectedDownloadCount);
        }

        return downloadedCount;
    }

    private static String targetKey(KeywordTarget target) {
        if (target == null || target.folderPath() == null || target.keyword() == null) {
            return "";
        }
        return target.folderPath().toAbsolutePath().normalize() + "|" + target.keyword().trim();
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

    private void navigateDirectToDetailPage(WebDriver driver, String detailUrl, String keyword) {
        SeleniumHelper.openUrlRobust(driver, detailUrl, DEFAULT_WAIT);
        SeleniumHelper.switchToNewestWindowIfNeeded(driver, Duration.ofSeconds(3));
        SeleniumHelper.normalizeWindows(driver,
            url -> url.toLowerCase().contains("render=detail-v2"));

        if (SeleniumHelper.isDisposableCurrentUrl(driver)
                || !SeleniumHelper.isRelevantCurrentUrl(driver)) {
            throw new IllegalStateException(
                "Unexpected page after opening direct detail URL.");
        }

        SeleniumHelper.dismissPopupIfPresent(driver);
        Utils.logStatus(keyword, "INFO", 0, "Detail page loaded from API-resolved URL.");
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
        ensureTenderInfoGeneralActive(driver, keyword);

        String packageName      = safeReadInfoValue(driver, "Tên gói thầu");
        String procuringEntity  = safeReadInfoValue(driver, "Chủ đầu tư");
        String postedDateRaw    = safeReadInfoValue(driver, "Ngày đăng tải");
        String executionTime    = safeReadInfoValue(driver, "Thời gian thực hiện gói thầu");
        String bidClosingRaw    = safeReadInfoValueByLabels(driver, INFO_LABELS.get("bidClosingTime"));
        String remainingRaw     = safeGetRemainingTime(driver);

        LocalDateTime postedDate     = parseDateTime(postedDateRaw, keyword);
        LocalDateTime bidClosingTime = parseDateTime(bidClosingRaw, keyword);
        Duration remainingTime       = parseDuration(remainingRaw, keyword);

        return BidSheetRowBuilder.create()
            .tmbtNumber(keyword)
            .packageName(packageName)
            .procuringEntity(procuringEntity)
            .postedDate(postedDate)
            .packageExecutionTime(executionTime)
            .bidClosingTime(bidClosingTime)
            .remainingTimeToClosing(remainingTime)
            .tenderLink(safeCurrentUrl(driver))
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
                String rawTitle = titles.get(0).getText();
                if (rawTitle == null || rawTitle.trim().isEmpty()) {
                    rawTitle = titles.get(0).getAttribute("textContent");
                }
                String titleText = normalizeComparableText(rawTitle);
                if (!titleText.contains(normalizedTarget)) {
                    continue;
                }

                List<WebElement> values = row.findElements(
                    By.xpath("./div[not(contains(@class,'infomation__content__title'))]")
                );
                for (WebElement value : values) {
                    String text = value.getText();
                    if (text == null || text.trim().isEmpty()) {
                        text = value.getAttribute("textContent");
                    }
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

    private String readInfoValueByLabelsFromRenderedDom(WebDriver driver, List<String> labels) {
        return readInfoValueByLabelsFromRenderedDom(driver, null, labels);
    }

    private String readInfoValueByLabelsFromRenderedDom(WebDriver driver, By scopeLocator, List<String> labels) {
        if (labels == null || labels.isEmpty()) {
            return null;
        }
        WebDriverWait wait = new WebDriverWait(driver, Duration.ofSeconds(5));
        try {
            return wait.until(current -> {
                for (String label : labels) {
                    String normalizedTarget = normalizeComparableText(label);
                    try {
                        List<WebElement> scopes = scopeLocator == null ? List.of() : current.findElements(scopeLocator);
                        List<WebElement> rows = new ArrayList<>();
                        if (scopes.isEmpty()) {
                            rows.addAll(current.findElements(By.xpath("//div[contains(@class,'infomation__content')]")));
                        } else {
                            for (WebElement scope : scopes) {
                                rows.addAll(scope.findElements(By.xpath(".//div[contains(@class,'infomation__content')]")));
                            }
                        }

                        for (WebElement row : rows) {
                            List<WebElement> titles = row.findElements(
                                By.xpath(".//div[contains(@class,'infomation__content__title')]")
                            );
                            if (titles.isEmpty()) {
                                continue;
                            }
                            String rawTitle = titles.get(0).getText();
                            if (rawTitle == null || rawTitle.trim().isEmpty()) {
                                rawTitle = titles.get(0).getAttribute("textContent");
                            }
                            String titleText = normalizeComparableText(rawTitle);
                            if (!titleText.contains(normalizedTarget)) {
                                continue;
                            }
                            List<WebElement> values = row.findElements(
                                By.xpath("./div[not(contains(@class,'infomation__content__title'))]")
                            );
                            for (WebElement value : values) {
                                String text = value.getText();
                                if (text == null || text.trim().isEmpty()) {
                                    text = value.getAttribute("textContent");
                                }
                                if (text != null && !text.trim().isEmpty()) {
                                    return text.trim();
                                }
                            }
                        }
                    } catch (RuntimeException ignored) {
                        // ignore and try next label, or wait will retry
                    }
                }
                return null;
            });
        } catch (TimeoutException ex) {
            return null;
        }
    }

    private void logBbmtMoneyFields(WebDriver driver, String keyword) {
        try {
            List<String> rows = collectInfoRowsFromRenderedDom(driver, BBMT_INFO_PANE);
            List<String> moneyRows = new ArrayList<>();
            for (String row : rows) {
                String normalized = normalizeComparableText(row);
                if (normalized.contains("du toan") || normalized.contains("gia goi thau")) {
                    moneyRows.add(row);
                }
            }
            if (moneyRows.isEmpty()) {
                Utils.logStatus(keyword, "INFO", 0,
                    "BBMT money fields visible: <none>");
                return;
            }
            Utils.logStatus(keyword, "INFO", 0,
                "BBMT money fields visible: " + String.join(" | ", moneyRows));
        } catch (RuntimeException ex) {
            Utils.logStatus(keyword, "WARN", 0,
                "Cannot log BBMT money fields: " + rootMessage(ex));
        }
    }

    private List<String> collectInfoRowsFromRenderedDom(WebDriver driver, By scopeLocator) {
        List<WebElement> scopes = scopeLocator == null ? List.of() : driver.findElements(scopeLocator);
        List<WebElement> rows = new ArrayList<>();
        if (scopes.isEmpty()) {
            rows.addAll(driver.findElements(By.xpath("//div[contains(@class,'infomation__content')]")));
        } else {
            for (WebElement scope : scopes) {
                rows.addAll(scope.findElements(By.xpath(".//div[contains(@class,'infomation__content')]")));
            }
        }

        List<String> result = new ArrayList<>();
        for (WebElement row : rows) {
            String text = row.getText();
            if (text == null || text.trim().isEmpty()) {
                text = row.getAttribute("textContent");
            }
            if (text == null || text.isBlank()) {
                continue;
            }
            result.add(text.replaceAll("\\s+", " ").trim());
        }
        return result;
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
            List<WebElement> paragraphs = driver.findElements(By.tagName("p"));
            for (int i = 0; i < paragraphs.size() - 1; i++) {
                String label = paragraphs.get(i).getText().trim();
                if (label.contains("Thời gian còn lại")) {
                    String value = paragraphs.get(i + 1).getText().trim();
                    if (!value.isBlank()) {
                        return value;
                    }
                }
            }
            for (WebElement p : paragraphs) {
                String text = p.getText().trim();
                if ((text.contains("Còn lại") || text.contains("Con lai")) && text.matches(".*\\d+.*")) {
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
                    if (text == null || text.trim().isEmpty()) {
                        text = el.getAttribute("textContent");
                    }
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

    private int downloadTenderNoticeAttachments(
        WebDriver driver, Path downloadDir, KeywordTarget target, String keyword, DownloadHints hints
    ) {
        int downloaded = 0;
        if (hints.needHsmtClarificationAttachments()) {
            downloaded += downloadTenderSubtabAttachments(
                driver,
                downloadDir,
                target,
                keyword,
                TENDER_HSMT_CLARIFICATION_TAB,
                TENDER_HSMT_CLARIFICATION_PANE,
                "Làm rõ HSMT",
                "clear-HSMT",
                HSMT_CLARIFICATION_FOLDER_NAME
            );
        } else {
            Utils.logStatus(keyword, "INFO", 0, "API hints: Làm rõ HSMT not required. Skipping.");
        }
        if (hints.needPetitionAttachments()) {
            downloaded += downloadTenderSubtabAttachments(
                driver,
                downloadDir,
                target,
                keyword,
                TENDER_PETITION_TAB,
                TENDER_PETITION_PANE,
                "Kiến nghị",
                "kien-nghi",
                PETITION_FOLDER_NAME
            );
        } else {
            Utils.logStatus(keyword, "INFO", 0, "API hints: Kiến nghị not required. Skipping.");
        }
        return downloaded;
    }

    private int downloadTenderSubtabAttachments(
        WebDriver driver,
        Path downloadDir,
        KeywordTarget target,
        String keyword,
        By tabLocator,
        By paneLocator,
        String tabName,
        String paneId,
        String targetSubfolderName
    ) {
        if (!clickTenderSubtabIfPresent(driver, tabLocator, paneLocator, tabName, keyword)) {
            return 0;
        }

        List<String> attachmentNames = collectTenderAttachmentNames(driver, paneId);
        if (attachmentNames.isEmpty()) {
            Utils.logStatus(keyword, "INFO", 0,
                "No attachments found in tab '" + tabName + "'. Skipping.");
            return 0;
        }

        Utils.logStatus(keyword, "INFO", 0,
            "Found " + attachmentNames.size() + " attachment(s) in tab '" + tabName + "'.");

        int downloaded = 0;
        Path targetFolder = resolveAutoDownloadFolder(target).resolve(targetSubfolderName);
        for (String attachmentName : attachmentNames) {
            downloadTenderSubtabAttachmentByName(
                driver, downloadDir, targetFolder, keyword, tabName, paneId, attachmentName);
            downloaded++;
        }
        return downloaded;
    }

    private void downloadTenderSubtabAttachmentByName(
        WebDriver driver,
        Path downloadDir,
        Path targetFolder,
        String keyword,
        String tabName,
        String paneId,
        String attachmentName
    ) {
        int attempts = 1;
        RuntimeException lastError = null;

        for (int attempt = 1; attempt <= attempts; attempt++) {
            try {
                WebElement attachment = findTenderAttachmentByName(driver, paneId, attachmentName);
                Utils.logStatus(keyword, "INFO", attempt,
                    tabName + ": downloading attachment - " + attachmentName);

                Set<Path> before = Utils.snapshotCompletedFiles(downloadDir);
                long clickStartedAt = System.nanoTime();
                clickAttachmentRobust(driver, attachment);
                SeleniumHelper.normalizeWindows(driver,
                    url -> url.toLowerCase().contains("render=detail-v2"));

                if (SeleniumHelper.isDisposableCurrentUrl(driver)) {
                    throw new IllegalStateException(
                        "Browser navigated away after attachment click: " + driver.getCurrentUrl());
                }

                Path downloaded = Utils.safeWaitForDownloadedFile(
                    downloadDir, before, DOWNLOAD_TIMEOUT, ATTACHMENT_EXTENSIONS);
                if (downloaded == null) {
                    throw new TimeoutException("Download timeout waiting for attachment: " + attachmentName);
                }
                if (!Utils.isNonEmptyRegularFile(downloaded)) {
                    throw new IllegalStateException("Downloaded attachment is invalid or empty: " + downloaded);
                }

                Path finalFile = Utils.moveFileToTargetFolderUnique(downloaded, targetFolder);
                long elapsedMs = (System.nanoTime() - clickStartedAt) / 1_000_000L;
                Utils.logStatus(keyword, "INFO", attempt,
                    tabName + " attachment saved: " + finalFile.getFileName() + " | waitMs=" + elapsedMs);
                return;
            } catch (StaleElementReferenceException ex) {
                lastError = ex;
                Utils.logStatus(keyword, "WARN", attempt,
                    tabName + " attachment changed while clicking. Retrying...");
            } catch (RuntimeException ex) {
                lastError = ex;
                Utils.logStatus(keyword, "WARN", attempt,
                    tabName + " attachment download attempt failed: " + rootMessage(ex));
            }
        }

        throw lastError == null
            ? new IllegalStateException(tabName + " attachment download failed after retry attempts.")
            : lastError;
    }

    private void ensureTenderInfoGeneralActive(WebDriver driver, String keyword) {
        clickTenderSubtabIfPresent(driver, TENDER_INFO_GENERAL_TAB, TENDER_INFO_GENERAL_PANE, "Thông tin chung", keyword);
    }

    private boolean clickTenderSubtabIfPresent(WebDriver driver, By tabLocator, By paneLocator, String tabName, String keyword) {
        try {
            List<WebElement> tabs = driver.findElements(tabLocator);
            if (tabs.isEmpty()) {
                Utils.logStatus(keyword, "INFO", 0,
                    "Tender subtab '" + tabName + "' not present. Skipping.");
                return false;
            }
            WebElement tab = tabs.get(0);
            ((JavascriptExecutor) driver).executeScript("arguments[0].scrollIntoView({block:'center', inline:'center'});", tab);
            try {
                SeleniumHelper.safeClick(driver, tab);
            } catch (RuntimeException ex) {
                ((JavascriptExecutor) driver).executeScript("arguments[0].click();", tab);
            }
            waitForPaneActivated(driver, paneLocator);
            Utils.logStatus(keyword, "INFO", 0, "Tender subtab '" + tabName + "' activated.");
            return true;
        } catch (RuntimeException ex) {
            Utils.logStatus(keyword, "WARN", 0,
                "Cannot activate tender subtab '" + tabName + "': " + rootMessage(ex));
            return false;
        }
    }

    private void waitForPaneActivated(WebDriver driver, By paneLocator) {
        WebDriverWait wait = new WebDriverWait(driver, TAB_SWITCH_WAIT);
        wait.until(current -> {
            List<WebElement> panes = current.findElements(paneLocator);
            if (panes.isEmpty()) {
                return false;
            }
            WebElement pane = panes.get(0);
            String paneClass = pane.getAttribute("class");
            return pane.isDisplayed()
                || (paneClass != null && (paneClass.contains("active") || paneClass.contains("show")));
        });
    }

    private List<String> collectTenderAttachmentNames(WebDriver driver, String paneId) {
        LinkedHashSet<String> names = new LinkedHashSet<>();
        try {
            By locator = tenderAttachmentLocator(paneId);
            new WebDriverWait(driver, Duration.ofSeconds(3)).until(current -> !current.findElements(locator).isEmpty());
        } catch (TimeoutException ignored) {
            // No files in this tab.
        }
        try {
            for (WebElement element : driver.findElements(tenderAttachmentLocator(paneId))) {
                String text = element.getText();
                if (text != null && isAttachmentFileName(text.trim())) {
                    names.add(text.trim());
                }
            }
        } catch (RuntimeException ignored) {
            return List.of();
        }
        return new ArrayList<>(names);
    }

    private WebElement findTenderAttachmentByName(WebDriver driver, String paneId, String attachmentName) {
        String normalizedExpected = normalizeComparableText(attachmentName);
        for (WebElement element : driver.findElements(tenderAttachmentLocator(paneId))) {
            String text = element.getText();
            if (text != null && normalizeComparableText(text).equals(normalizedExpected)) {
                return element;
            }
        }
        throw new NoSuchElementException("Attachment not found: " + attachmentName);
    }

    private By tenderAttachmentLocator(String paneId) {
        return By.xpath("//div[@id='" + paneId + "']//span[contains(@class,'text-blue-4D7AE6')]");
    }

    private boolean isAttachmentFileName(String text) {
        if (text == null || text.isBlank()) {
            return false;
        }
        String lowered = text.trim().toLowerCase();
        for (String extension : ATTACHMENT_EXTENSIONS) {
            if (lowered.endsWith(extension)) {
                return true;
            }
        }
        return false;
    }

    private void clickAttachmentRobust(WebDriver driver, WebElement attachment) {
        ((JavascriptExecutor) driver).executeScript("arguments[0].scrollIntoView({block:'center', inline:'center'});", attachment);
        RuntimeException lastError = null;
        try {
            SeleniumHelper.safeClick(driver, attachment);
            return;
        } catch (RuntimeException ex) {
            lastError = ex;
        }
        try {
            ((JavascriptExecutor) driver).executeScript("arguments[0].click();", attachment);
            return;
        } catch (RuntimeException ex) {
            lastError = ex;
        }
        try {
            WebElement parent = attachment.findElement(By.xpath("./ancestor::div[1]"));
            SeleniumHelper.safeClick(driver, parent);
            return;
        } catch (RuntimeException ex) {
            lastError = ex;
        }
        throw lastError == null ? new IllegalStateException("Cannot click attachment.") : lastError;
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
            long clickStartedAt = System.nanoTime();
            clickFirstDownloadTrigger(driver, bbmtTriggers, Duration.ofSeconds(15));
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

    private void waitForBbmtContentReady(WebDriver driver) {
        WebDriverWait wait = new WebDriverWait(driver, Duration.ofSeconds(12));
        wait.until(current -> hasAnyVisible(current,
            List.of(
                BBMT_DOWNLOAD_ICON_EXACT,
                BBMT_DOWNLOAD_ICON,
                BBMT_DOWNLOAD_ICON_FALLBACK
            )));
    }

    private void waitForBbmtInfoReady(WebDriver driver) {
        WebDriverWait wait = new WebDriverWait(driver, Duration.ofSeconds(8));
        wait.until(current -> {
            List<WebElement> panes = current.findElements(BBMT_INFO_PANE);
            for (WebElement pane : panes) {
                String paneClass = pane.getAttribute("class");
                boolean active = pane.isDisplayed()
                    || (paneClass != null && (paneClass.contains("active") || paneClass.contains("show")));
                if (!active) {
                    continue;
                }
                if (!pane.findElements(By.xpath(".//div[contains(@class,'infomation__content')]")).isEmpty()) {
                    return true;
                }
            }
            return false;
        });
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
        List<By> kqlcntTriggers = List.of(KQLCNT_DECISION_PRIMARY, KQLCNT_DECISION_FALLBACK);
        int attempts = 3;
        RuntimeException lastError = null;

        for (int attempt = 1; attempt <= attempts; attempt++) {
            try {
                Utils.logStatus(keyword, "INFO", attempt,
                    "KQLCNT: locating decision attachment.");
                Set<Path> before = Utils.snapshotPdfFiles(downloadDir);
                long clickStartedAt = System.nanoTime();
                clickFirstDownloadTrigger(driver, kqlcntTriggers, KQLCNT_TRIGGER_WAIT);
                Utils.logStatus(keyword, "INFO", attempt,
                    "KQLCNT trigger clicked. Waiting for PDF to appear...");
                SeleniumHelper.normalizeWindows(driver,
                    url -> url.toLowerCase().contains("render=detail-v2"));

                if (SeleniumHelper.isDisposableCurrentUrl(driver)) {
                    throw new IllegalStateException(
                        "Browser navigated away after KQLCNT download click: "
                        + driver.getCurrentUrl());
                }

                Path downloaded = Utils.safeWaitForPdf(downloadDir, before, KQLCNT_DOWNLOAD_TIMEOUT);
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
                Utils.logStatus(keyword, "INFO", attempt,
                    "KQLCNT decision PDF saved: " + finalFile.getFileName() + " | waitMs=" + elapsedMs);
                return finalFile;
            } catch (StaleElementReferenceException ex) {
                lastError = ex;
                Utils.logStatus(keyword, "WARN", attempt,
                    "KQLCNT attachment changed while clicking. Retrying...");
            }
        }

        throw lastError == null
            ? new IllegalStateException("KQLCNT download failed after retry attempts.")
            : lastError;
    }

    private Path downloadKqlcntEhsdtReport(
        WebDriver driver, Path downloadDir, KeywordTarget target, String keyword
    ) {
        List<By> reportTriggers = List.of(KQLCNT_EHSDT_REPORT_PRIMARY);
        int attempts = 1;
        RuntimeException lastError = null;

        for (int attempt = 1; attempt <= attempts; attempt++) {
            try {
                Utils.logStatus(keyword, "INFO", attempt,
                    "KQLCNT: locating HSDT/E-HSDT evaluation report attachment.");
                Set<Path> before = Utils.snapshotPdfFiles(downloadDir);
                long clickStartedAt = System.nanoTime();
                long clickStartedAtMs = System.currentTimeMillis();
                clickFirstDownloadTrigger(driver, reportTriggers, KQLCNT_TRIGGER_WAIT);
                Utils.logStatus(keyword, "INFO", attempt,
                    "KQLCNT evaluation report trigger clicked. Waiting for file to appear...");
                SeleniumHelper.normalizeWindows(driver,
                    url -> url.toLowerCase().contains("render=detail-v2"));

                if (SeleniumHelper.isDisposableCurrentUrl(driver)) {
                    throw new IllegalStateException(
                        "Browser navigated away after KQLCNT evaluation report click: "
                        + driver.getCurrentUrl());
                }

                Path downloaded = Utils.safeWaitForDownloadedFile(
                    downloadDir, before, KQLCNT_DOWNLOAD_TIMEOUT, ATTACHMENT_EXTENSIONS
                );
                if (downloaded == null) {
                    Path fallback = Utils.findLatestNonEmptyFileSince(
                        downloadDir, clickStartedAtMs - 2_000L, ATTACHMENT_EXTENSIONS
                    );
                    if (fallback != null) {
                        downloaded = fallback;
                        Utils.logStatus(keyword, "WARN", attempt,
                            "KQLCNT evaluation report timeout fallback matched file: " + fallback.getFileName());
                    } else {
                        throw new TimeoutException(
                            "Download timeout waiting for KQLCNT evaluation report file.");
                    }
                }
                if (!Utils.isNonEmptyRegularFile(downloaded)) {
                    throw new IllegalStateException(
                        "Downloaded KQLCNT evaluation report file is invalid or empty: " + downloaded);
                }

                Path finalFile = Utils.moveFileToTargetFolder(downloaded, resolveAutoDownloadFolder(target));
                long elapsedMs = (System.nanoTime() - clickStartedAt) / 1_000_000L;
                Utils.logStatus(keyword, "INFO", attempt,
                    "KQLCNT evaluation report file saved: " + finalFile.getFileName() + " | waitMs=" + elapsedMs);
                return finalFile;
            } catch (StaleElementReferenceException ex) {
                lastError = ex;
                Utils.logStatus(keyword, "WARN", attempt,
                    "KQLCNT evaluation report attachment changed while clicking. Retrying...");
            } catch (RuntimeException ex) {
                lastError = ex;
                Utils.logStatus(keyword, "WARN", attempt,
                    "KQLCNT evaluation report download attempt failed: " + rootMessage(ex));
            }
        }

        throw lastError == null
            ? new IllegalStateException("KQLCNT evaluation report download failed after retry attempts.")
            : lastError;
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

    private void clickFirstDownloadTrigger(WebDriver driver, List<By> locators, Duration timeout) {
        RuntimeException lastError = null;
        for (int attempt = 1; attempt <= 3; attempt++) {
            for (By locator : locators) {
                try {
                    WebElement element = SeleniumHelper.waitClickable(driver, locator, timeout);
                    SeleniumHelper.safeClick(driver, element);
                    return;
                } catch (StaleElementReferenceException ex) {
                    lastError = ex;
                    // DOM re-rendered between locate and click; re-find the element.
                } catch (TimeoutException ex) {
                    lastError = ex;
                } catch (RuntimeException ex) {
                    lastError = ex;
                    try {
                        WebElement element = SeleniumHelper.waitClickable(driver, locator, QUICK_LOCATOR_WAIT);
                        ((JavascriptExecutor) driver).executeScript("arguments[0].click();", element);
                        return;
                    } catch (StaleElementReferenceException stale) {
                        lastError = stale;
                    } catch (RuntimeException ignored) {
                        // try next locator/attempt
                    }
                }
            }
        }

        throw lastError == null
            ? new NoSuchElementException("No clickable download trigger found.")
            : lastError;
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
        if (message == null || message.isBlank()) {
            return root.getClass().getSimpleName();
        }
        String oneLine = message.replaceAll("\\s+", " ").trim();
        if (oneLine.length() > 280) {
            return oneLine.substring(0, 280) + "...";
        }
        return oneLine;
    }

    private static List<BidSheetRow> deduplicateRows(List<BidSheetRow> rows) {
        LinkedHashMap<String, BidSheetRow> dedup = new LinkedHashMap<>();
        for (BidSheetRow row : rows) {
            String key = buildRowKey(row);
            dedup.remove(key);
            dedup.put(key, row);
        }
        return new ArrayList<>(dedup.values());
    }

    private static String buildRowKey(BidSheetRow row) {
        String tmbt = row == null || row.tmbtNumber() == null ? "" : row.tmbtNumber().trim();
        String parent = row == null || row.parentFolderName() == null ? "" : row.parentFolderName().trim();
        String contract = row == null || row.contractPerformanceFolder() == null ? "" : row.contractPerformanceFolder().trim();
        return tmbt + "|" + parent + "|" + contract;
    }

    private static String buildRowKeyFromStateRecord(RunStateStore.StateRecord record) {
        if (record == null || record.keyword() == null || record.keyword().isBlank()) {
            return null;
        }

        String parent = "";
        String contract = "";
        try {
            String folderPath = record.folderPath();
            if (folderPath != null && !folderPath.isBlank()) {
                Path folder = Path.of(folderPath).toAbsolutePath().normalize();
                if (folder.getFileName() != null) {
                    contract = folder.getFileName().toString().trim();
                }
                if (folder.getParent() != null && folder.getParent().getFileName() != null) {
                    parent = folder.getParent().getFileName().toString().trim();
                }
            }
        } catch (Exception ignored) {
            // best effort mapping only
        }

        return record.keyword().trim() + "|" + parent + "|" + contract;
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
        String postedDate = row.postedDate() == null ? null : row.postedDate().format(JSON_TIME_FORMAT);
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
            + "  \"postedDate\": " + quoteJson(postedDate) + ",\n"
            + "  \"packageExecutionTime\": " + quoteJson(row.packageExecutionTime()) + ",\n"
            + "  \"winningBidPrice\": " + quoteJson(row.winningBidPrice() == null ? null : row.winningBidPrice().toString()) + ",\n"
            + "  \"status\": " + (row.status() == null ? "null" : quoteJson(row.status().name())) + ",\n"
            + "  \"bidClosingTime\": " + quoteJson(bidClosing) + ",\n"
            + "  \"remainingTimeSeconds\": " + (remainingSeconds == null ? "null" : remainingSeconds) + ",\n"
            + "  \"folderLink\": " + quoteJson(row.folderLink()) + ",\n"
            + "  \"tenderLink\": " + quoteJson(row.tenderLink()) + "\n"
            + "}";
    }

    private String safeCurrentUrl(WebDriver driver) {
        try {
            String url = driver == null ? null : driver.getCurrentUrl();
            if (url == null || url.isBlank()) {
                return null;
            }
            return url;
        } catch (Exception ignored) {
            return null;
        }
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
