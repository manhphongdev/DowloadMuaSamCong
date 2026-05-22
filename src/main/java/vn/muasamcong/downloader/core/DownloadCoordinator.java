package vn.muasamcong.downloader.core;

import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import vn.muasamcong.downloader.export.ArtifactFingerprintService;
import vn.muasamcong.downloader.export.BidSearchApiResolver;
import vn.muasamcong.downloader.export.BidSheetApiSyncService;
import vn.muasamcong.downloader.export.GoogleSheetsSyncService;
import vn.muasamcong.downloader.model.BidTrackingRecord;
import vn.muasamcong.downloader.model.DownloadHints;
import vn.muasamcong.downloader.model.KeywordTarget;
import vn.muasamcong.downloader.model.ResolvedBidDetail;
import vn.muasamcong.downloader.parser.FolderKeywordReader;
import vn.muasamcong.downloader.store.ArtifactFingerprintStore;
import vn.muasamcong.downloader.store.BidTrackingRecordStore;
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
            workers.add(new DownloadWorker(keywordQueue, BASE_URL, tempDownloadDir, MAX_RETRIES, stats, stopRequested, i + 1, Map.of(), Map.of()));
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

    public static RunStats runTargets(List<KeywordTarget> targets, int concurrency) {
        LocalDateTime startedAt = LocalDateTime.now();
        if (targets == null || targets.isEmpty()) {
            throw new IllegalArgumentException("No target provided for download.");
        }

        int normalizedConcurrency = Math.max(DEFAULT_CONCURRENCY, Math.min(MAX_CONCURRENCY, concurrency));
        Path tempDownloadDir = DEFAULT_DOWNLOAD_DIR;
        SeleniumHelper.quitAllDriversNow();
        Utils.logPlain("Pre-flight cleanup done: closed leftover Chrome/ChromeDriver sessions.");
        Utils.ensureDirectory(tempDownloadDir);
        DownloadWorker.prepareBidInfoOutput();

        Map<String, DownloadHints> hintsByTargetKey = resolveDownloadHintsByTargetKey(targets);
        List<KeywordTarget> seleniumTargets = new ArrayList<>();
        RunStats stats = new RunStats(targets.size());
        for (KeywordTarget target : targets) {
            String key = targetKey(target);
            DownloadHints hints = hintsByTargetKey.getOrDefault(key, DownloadHints.unknown());
            String hintsHash = ArtifactFingerprintService.hashDownloadHints(hints);
            if (hints.resolvedFromApi() && ArtifactFingerprintStore.isDownloadHintsUnchanged(key, hintsHash)) {
                Utils.logPlain("Download hints unchanged. Skipping Selenium: " + target.keyword() + " folder=" + target.folderPath().toAbsolutePath());
                stats.markSuccess();
                stats.addProcessedRecord(new RunStats.ProcessedRecord(
                    target.keyword(),
                    target.folderPath() == null ? null : target.folderPath().toAbsolutePath().normalize().toString(),
                    "SUCCESS",
                    0,
                    "Download hints unchanged",
                    0
                ));
                continue;
            }
            if (hints.resolvedFromApi() && !hints.hasSeleniumDownloads()) {
                Utils.logPlain("API hints skip Selenium: " + target.keyword() + " folder=" + target.folderPath().toAbsolutePath());
                ArtifactFingerprintStore.updateHashes(key, null, null, null, hintsHash);
                stats.markSuccess();
                stats.addProcessedRecord(new RunStats.ProcessedRecord(
                    target.keyword(),
                    target.folderPath() == null ? null : target.folderPath().toAbsolutePath().normalize().toString(),
                    "SUCCESS",
                    0,
                    "No Selenium downloads required by API hints",
                    0
                ));
                continue;
            }
            seleniumTargets.add(target);
        }

        Utils.logPlain("Loaded keywords: " + seleniumTargets.size());
        for (KeywordTarget target : seleniumTargets) {
            Utils.logPlain("Loaded keyword: " + target.keyword() + " folder=" + target.folderPath().toAbsolutePath());
        }
        Utils.logPlain("Temp download folder: " + tempDownloadDir.toAbsolutePath());
        Utils.logPlain("Concurrent browsers: " + normalizedConcurrency);

        if (seleniumTargets.isEmpty()) {
            int rowCount = BidSheetApiSyncService.refreshBidSheetRowsFromTracking();
            Utils.logPlain("Bid sheet rows refreshed from API. Rows: " + rowCount);
            GoogleSheetsSyncService.syncFromJsonIfEnabled(Utils.dataFile("bid_sheet_rows.json"));
            List<Path> rootFolders = targets.stream().map(KeywordTarget::folderPath).map(Path::getParent).filter(p -> p != null).distinct().toList();
            RunHistoryStore.appendRun(rootFolders, stats, false, startedAt, LocalDateTime.now());
            return stats;
        }

        int workerCount = Math.min(normalizedConcurrency, seleniumTargets.size());
        ExecutorService pool = Executors.newFixedThreadPool(workerCount);
        AtomicBoolean stopRequested = new AtomicBoolean(false);
        Queue<KeywordTarget> keywordQueue = new ConcurrentLinkedQueue<>(seleniumTargets);
        Map<String, String> detailUrlByTargetKey = detailUrlsByTargetKey(seleniumTargets);
        List<Runnable> workers = new ArrayList<>();

        synchronized (EXECUTION_LOCK) {
            activePool = pool;
            activeStopFlag = stopRequested;
        }

        for (int i = 0; i < workerCount; i++) {
            workers.add(new DownloadWorker(keywordQueue, BASE_URL, tempDownloadDir, MAX_RETRIES, stats, stopRequested, i + 1, hintsByTargetKey, detailUrlByTargetKey));
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
        } else if (!stats.getProcessedRecords().isEmpty()) {
            updateSuccessfulDownloadHintHashes(stats, hintsByTargetKey);
            int rowCount = BidSheetApiSyncService.refreshBidSheetRowsFromTracking();
            Utils.logPlain("Bid sheet rows refreshed from API. Rows: " + rowCount);
            GoogleSheetsSyncService.syncFromJsonIfEnabled(Utils.dataFile("bid_sheet_rows.json"));
        }

        List<Path> rootFolders = targets.stream().map(KeywordTarget::folderPath).map(Path::getParent).filter(p -> p != null).distinct().toList();
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

    private static Map<String, DownloadHints> resolveDownloadHintsByTargetKey(List<KeywordTarget> targets) {
        if (targets == null || targets.isEmpty()) {
            return Map.of();
        }
        Map<String, BidTrackingRecord> recordsByKey = new LinkedHashMap<>();
        for (BidTrackingRecord record : BidTrackingRecordStore.loadRecords()) {
            if (record != null && record.key() != null && !record.key().isBlank()) {
                recordsByKey.put(record.key(), record);
            }
        }

        Map<String, DownloadHints> result = new LinkedHashMap<>();
        for (KeywordTarget target : targets) {
            String key = targetKey(target);
            BidTrackingRecord record = recordsByKey.get(key);
            if (record == null || record.apiParams() == null) {
                BidSearchApiResolver.resolve(target.keyword()).ifPresent(resolved -> {
                    BidTrackingRecordStore.upsertFromDetailUrl(target, resolved.detailUrl());
                    result.put(key, BidSheetApiSyncService.resolveDownloadHints(resolved.apiParams()));
                    Utils.logPlain("Search API resolved detail URL: " + target.keyword());
                });
            } else {
                result.put(key, BidSheetApiSyncService.resolveDownloadHints(record.apiParams()));
            }
        }
        return result;
    }

    private static Map<String, String> detailUrlsByTargetKey(List<KeywordTarget> targets) {
        if (targets == null || targets.isEmpty()) {
            return Map.of();
        }
        Map<String, BidTrackingRecord> recordsByKey = new LinkedHashMap<>();
        for (BidTrackingRecord record : BidTrackingRecordStore.loadRecords()) {
            if (record != null && record.key() != null && !record.key().isBlank()) {
                recordsByKey.put(record.key(), record);
            }
        }
        Map<String, String> result = new LinkedHashMap<>();
        for (KeywordTarget target : targets) {
            String key = targetKey(target);
            BidTrackingRecord record = recordsByKey.get(key);
            if (record != null && record.detailUrl() != null && !record.detailUrl().isBlank()) {
                result.put(key, record.detailUrl());
            }
        }
        return result;
    }

    private static void updateSuccessfulDownloadHintHashes(
        RunStats stats,
        Map<String, DownloadHints> hintsByTargetKey
    ) {
        if (stats == null || hintsByTargetKey == null || hintsByTargetKey.isEmpty()) {
            return;
        }
        for (RunStats.ProcessedRecord record : stats.getProcessedRecords()) {
            if (record == null || !"SUCCESS".equalsIgnoreCase(record.status())) {
                continue;
            }
            String key = buildKey(record.folderPath(), record.keyword());
            DownloadHints hints = hintsByTargetKey.get(key);
            if (hints == null || !hints.resolvedFromApi()) {
                continue;
            }
            ArtifactFingerprintStore.updateHashes(
                key,
                null,
                null,
                null,
                ArtifactFingerprintService.hashDownloadHints(hints)
            );
        }
    }

    private static String buildKey(String folderPath, String keyword) {
        if (folderPath == null || folderPath.isBlank() || keyword == null || keyword.isBlank()) {
            return "";
        }
        return Path.of(folderPath).toAbsolutePath().normalize() + "|" + keyword.trim();
    }

    private static String targetKey(KeywordTarget target) {
        if (target == null || target.folderPath() == null || target.keyword() == null) {
            return "";
        }
        return target.folderPath().toAbsolutePath().normalize() + "|" + target.keyword().trim();
    }
}
