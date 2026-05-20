package vn.muasamcong.downloader.core;

import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import vn.muasamcong.downloader.export.BidSheetApiSyncService;
import vn.muasamcong.downloader.export.GoogleSheetsSyncService;
import vn.muasamcong.downloader.model.KeywordTarget;
import vn.muasamcong.downloader.parser.FolderKeywordReader;
import vn.muasamcong.downloader.store.RunHistoryStore;
import vn.muasamcong.downloader.store.RunStateStore;
import vn.muasamcong.downloader.util.SeleniumHelper;
import vn.muasamcong.downloader.util.Utils;

public final class DownloadCoordinator {

    private static final String BASE_URL = "https://muasamcong.mpi.gov.vn/";
    private static final int MAX_RETRIES = 2;
    private static final int DEFAULT_CONCURRENCY = 1;
    private static final int MAX_CONCURRENCY = 10;
    private static final Path DEFAULT_DOWNLOAD_DIR = Utils.dataDirectory().resolve("cache");
    private static final Object EXECUTION_LOCK = new Object();
    private static volatile ExecutorService activePool;
    private static volatile AtomicBoolean activeStopFlag;

    private DownloadCoordinator() {
    }

    public static RunStats run(Path rootFolder, int concurrency) {
        return run(List.of(rootFolder), concurrency, false);
    }

    public static RunStats run(List<Path> rootFolders, int concurrency, boolean forceRedownload) {
        LocalDateTime startedAt = LocalDateTime.now();
        List<KeywordTarget> allTargets = FolderKeywordReader.readKeywords(rootFolders);
        List<KeywordTarget> targets = forceRedownload
            ? allTargets
            : RunStateStore.filterPending(allTargets);

        if (targets.isEmpty()) {
            if (allTargets.isEmpty()) {
                throw new IllegalArgumentException(
                    "No valid keywords found in selected folders."
                );
            }
            throw new IllegalArgumentException(
                "All selected keywords are already completed. Enable Force re-download to run again."
            );
        }

        int normalizedConcurrency = Math.max(DEFAULT_CONCURRENCY, Math.min(MAX_CONCURRENCY, concurrency));
        Path tempDownloadDir = DEFAULT_DOWNLOAD_DIR;
        SeleniumHelper.quitAllDriversNow();
        Utils.logPlain("Pre-flight cleanup done: closed leftover Chrome/ChromeDriver sessions.");
        Utils.ensureDirectory(tempDownloadDir);
        DownloadWorker.prepareBidInfoOutput();

        Utils.logPlain("Loaded keywords: " + targets.size());
        for (KeywordTarget target : targets) {
            Utils.logPlain("Loaded keyword: " + target.keyword() + " folder=" + target.folderPath().toAbsolutePath());
        }
        if (!forceRedownload) {
            int skipped = allTargets.size() - targets.size();
            Utils.logPlain("Skipped completed keywords: " + Math.max(0, skipped));
        }
        Utils.logPlain("Selected root folders: " + rootFolders.size());
        for (Path rootFolder : rootFolders) {
            Utils.logPlain("- " + rootFolder.toAbsolutePath());
        }
        Utils.logPlain("Temp download folder: " + tempDownloadDir.toAbsolutePath());
        Utils.logPlain("Concurrent browsers: " + normalizedConcurrency);

        RunStats stats = new RunStats(targets.size());
        int workerCount = Math.min(normalizedConcurrency, targets.size());
        ExecutorService pool = Executors.newFixedThreadPool(workerCount);
        AtomicBoolean stopRequested = new AtomicBoolean(false);
        Queue<KeywordTarget> keywordQueue = new ConcurrentLinkedQueue<>(targets);
        List<Runnable> workers = new ArrayList<>();

        synchronized (EXECUTION_LOCK) {
            activePool = pool;
            activeStopFlag = stopRequested;
        }

        for (int i = 0; i < workerCount; i++) {
            workers.add(new DownloadWorker(keywordQueue, BASE_URL, tempDownloadDir, MAX_RETRIES, stats, stopRequested, i + 1));
        }

        workers.forEach(pool::submit);
        pool.shutdown();

        try {
            if (!pool.awaitTermination(45, TimeUnit.MINUTES)) {
                pool.shutdownNow();
                Utils.logPlain("Execution timed out and unfinished workers were interrupted.");
            }
        } catch (InterruptedException ex) {
            pool.shutdownNow();
            Thread.currentThread().interrupt();
            Utils.logPlain("Main thread interrupted while waiting for workers.");
        }

        synchronized (EXECUTION_LOCK) {
            if (activePool == pool) {
                activePool = null;
                activeStopFlag = null;
            }
        }

        if (stopRequested.get()) {
            Utils.logPlain("Execution stopped by user.");
        } else {
            if (stats.getProcessedRecords().isEmpty()) {
                throw new IllegalStateException("No keyword was processed. Chrome profile may be locked by existing processes.");
            }
            int rowCount = BidSheetApiSyncService.refreshBidSheetRowsFromTracking();
            Utils.logPlain("Bid sheet rows refreshed from API. Rows: " + rowCount);
            GoogleSheetsSyncService.syncFromJsonIfEnabled(Utils.dataFile("bid_sheet_rows.json"));
        }

        RunHistoryStore.appendRun(rootFolders, stats, stopRequested.get(), startedAt, LocalDateTime.now());
        return stats;
    }

    public static void requestStop() {
        ExecutorService poolRef;
        AtomicBoolean stopFlagRef;
        synchronized (EXECUTION_LOCK) {
            poolRef = activePool;
            stopFlagRef = activeStopFlag;
        }

        if (stopFlagRef != null) {
            stopFlagRef.set(true);
        }
        if (poolRef != null) {
            poolRef.shutdownNow();
        }
        SeleniumHelper.quitAllDriversNow();
    }
}
