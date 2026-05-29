package vn.muasamcong.downloader.application.monitor;

import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import vn.muasamcong.downloader.core.ExecutionGate;
import vn.muasamcong.downloader.core.RunStats;
import vn.muasamcong.downloader.application.monitor.PackageApiSyncService.SyncResult;
import vn.muasamcong.downloader.application.monitor.PackageDiscoveryService.DiscoveryResult;
import vn.muasamcong.downloader.application.sheet.SheetPublishService;
import vn.muasamcong.downloader.application.sheet.SheetRefreshResult;
import vn.muasamcong.downloader.domain.bidpackage.BidPackage;
import vn.muasamcong.downloader.domain.bidpackage.PackageRepository;
import vn.muasamcong.downloader.core.AppFeatures;
import vn.muasamcong.downloader.domain.monitor.MonitorCycleLog;
import vn.muasamcong.downloader.domain.monitor.MonitorCycleRepository;
import vn.muasamcong.downloader.export.BidSheetApiSyncService;
import vn.muasamcong.downloader.infrastructure.persistence.JsonMonitorCycleRepository;
import vn.muasamcong.downloader.infrastructure.persistence.JsonPackageRepository;
import vn.muasamcong.downloader.util.Utils;

public final class MonitorCycleService {

    private static final DateTimeFormatter TS = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss");
    private static int apiSyncParallelism() {
        return AppFeatures.isMonitorAgentFileDownloadEnabled()
            ? AppFeatures.MONITOR_API_SYNC_PARALLELISM_WITH_AGENT
            : AppFeatures.MONITOR_API_SYNC_PARALLELISM_DEFAULT;
    }
    private static final long API_SYNC_TIMEOUT_MINUTES = 60L;
    private static final AtomicReference<ExecutorService> ACTIVE_API_SYNC_POOL = new AtomicReference<>();

    private final Path bidRowsJsonFile;
    private final PackageRepository packageRepository;
    private final PackageDiscoveryService discoveryService;
    private final PackageApiSyncService apiSyncService;
    private final SheetAggregateService sheetAggregateService;
    private final SheetPublishService sheetPublishService;
    private final MonitorCycleRepository cycleRepository;

    public MonitorCycleService(Path bidRowsJsonFile) {
        this(
            bidRowsJsonFile,
            new JsonPackageRepository(),
            new JsonMonitorCycleRepository()
        );
    }

    public MonitorCycleService(
        Path bidRowsJsonFile,
        PackageRepository packageRepository,
        MonitorCycleRepository cycleRepository
    ) {
        this.bidRowsJsonFile = bidRowsJsonFile;
        this.packageRepository = packageRepository;
        this.discoveryService = new PackageDiscoveryService(packageRepository);
        this.apiSyncService = new PackageApiSyncService(packageRepository);
        this.sheetAggregateService = new SheetAggregateService(packageRepository);
        this.sheetPublishService = new SheetPublishService();
        this.cycleRepository = cycleRepository;
    }

    public static void cancelActiveWork() {
        BidSheetApiSyncService.setMonitorStopping(true);
        ExecutorService pool = ACTIVE_API_SYNC_POOL.get();
        if (pool != null) {
            pool.shutdownNow();
        }
        MonitorParallelBbmtRunner.cancelActive();
    }

    public MonitorCycleResult run(MonitorCycleRequest request) {
        if (request == null || request.selectedRoots().isEmpty()) {
            throw new IllegalArgumentException("No folders selected for monitor run.");
        }
        String startedAt = LocalDateTime.now().format(TS);
        MonitorParallelBbmtRunner parallelBbmtRunner = null;
        boolean parallelBbmtCompleted = false;
        boolean parallelAgentBbmt = false;
        BidSheetApiSyncService.setMonitorStopping(false);
        try {
            if (Thread.currentThread().isInterrupted()) {
                return interruptedResult(0, startedAt);
            }

            DiscoveryResult discovery = discoveryService.discover(request.selectedRoots());
            if (Thread.currentThread().isInterrupted()) {
                return interruptedResult(discovery.packageCount(), startedAt);
            }
            Utils.logPlain("Monitor discovered packages: " + discovery.packageCount()
                + " (folder targets: " + discovery.folderTargetCount() + ")");

            boolean bbmtSelenium = AppFeatures.isMonitorBbmtSeleniumEnabled() && request.bbmtSeleniumDownload();
            parallelAgentBbmt = request.downloadFilesAfterSheet() && bbmtSelenium;
            int downloadQueued = 0;
            int downloadSuccess = 0;
            int downloadFailed = 0;
            int downloadSkipped = 0;

            BidSheetApiSyncService.setAgentExportEnabled(request.downloadFilesAfterSheet());
            BidSheetApiSyncService.setBbmtSeleniumPreferred(bbmtSelenium);
            ApiSyncBatchResult apiBatch;
            try {
                if (parallelAgentBbmt) {
                    ExecutionGate.setAllowDownloadDuringMonitor(true);
                    Utils.logPlain("Monitor: agent + BBMT Selenium running in parallel.");
                    parallelBbmtRunner = MonitorParallelBbmtRunner.start(
                        packageRepository,
                        request.selectedRoots(),
                        request.downloadConcurrency()
                    );
                }
                MonitorParallelBbmtRunner bbmtEnqueue = parallelBbmtRunner;
                apiBatch = syncPackagesInParallel(
                    discovery.packages(),
                    pkg -> {
                        if (bbmtEnqueue != null) {
                            bbmtEnqueue.offerAfterSync(pkg.id());
                        }
                    }
                );
            } finally {
                BidSheetApiSyncService.setAgentExportEnabled(true);
                BidSheetApiSyncService.setBbmtSeleniumPreferred(false);
            }
            int synced = apiBatch.synced();
            int skipped = apiBatch.skipped();
            int failed = apiBatch.failed();
            Set<String> changedPackageIds = new LinkedHashSet<>(apiBatch.changedPackageIds());

            Utils.logPlain("Monitor API sync: synced=" + synced + ", skipped=" + skipped + ", failed=" + failed
                + ", parallelism=" + apiSyncParallelism());

            if (apiBatch.interrupted()) {
                return finishCycle(
                    discovery.packageCount(),
                    synced,
                    skipped,
                    failed,
                    changedPackageIds,
                    startedAt,
                    null,
                    true,
                    "interrupted during api sync",
                    downloadQueued,
                    downloadSuccess,
                    downloadFailed,
                    downloadSkipped,
                    parallelAgentBbmt
                );
            }

            if (Thread.currentThread().isInterrupted()) {
                return finishCycle(
                    discovery.packageCount(),
                    synced,
                    skipped,
                    failed,
                    changedPackageIds,
                    startedAt,
                    null,
                    true,
                    "interrupted before sheet aggregate",
                    downloadQueued,
                    downloadSuccess,
                    downloadFailed,
                    downloadSkipped,
                    parallelAgentBbmt
                );
            }

            SheetRefreshResult sheetRefresh = sheetAggregateService.aggregateToJson(bidRowsJsonFile, changedPackageIds);
            logSheetRefresh(sheetRefresh);
            if (sheetRefresh.interrupted()) {
                return finishCycle(
                    discovery.packageCount(),
                    synced,
                    skipped,
                    failed,
                    changedPackageIds,
                    startedAt,
                    sheetRefresh,
                    true,
                    "interrupted during sheet aggregate",
                    downloadQueued,
                    downloadSuccess,
                    downloadFailed,
                    downloadSkipped,
                    parallelAgentBbmt
                );
            }

            if (synced > 0 || sheetRefresh.sheetChanged()) {
                if (!sheetRefresh.sheetChanged() && synced > 0) {
                    sheetAggregateService.writeAllRowsToJson(bidRowsJsonFile);
                }
                boolean published = sheetPublishService.publishIfEnabled(bidRowsJsonFile);
                if (!published && synced > 0) {
                    Utils.logPlain("Monitor cycle: sheet rows updated locally (" + synced
                        + " packages synced). Enable Google Sheets auto sync to push online.");
                }
            } else {
                Utils.logPlain("Google Sheets sync skipped: no packages synced and sheet data unchanged.");
            }

            if (parallelBbmtRunner != null) {
                try {
                    RunStats bbmtStats = parallelBbmtRunner.awaitCompletion();
                    parallelBbmtCompleted = true;
                    PackageDownloadPlan bbmtPlan = parallelBbmtRunner.buildPlan();
                    new MonitorDownloadPhaseService(packageRepository).applyResults(
                        packageRepository.findAll(),
                        bbmtPlan,
                        bbmtStats
                    );
                    downloadQueued = bbmtPlan.targets().size();
                    downloadSuccess = bbmtStats.getSuccessCount();
                    downloadFailed = bbmtStats.getFailCount();
                    downloadSkipped = parallelBbmtRunner.skippedCount();
                } catch (InterruptedException ex) {
                    Thread.currentThread().interrupt();
                    parallelBbmtRunner.requestStop();
                    parallelBbmtCompleted = true;
                    return finishCycle(
                        discovery.packageCount(),
                        synced,
                        skipped,
                        failed,
                        changedPackageIds,
                        startedAt,
                        sheetRefresh,
                        true,
                        "interrupted during parallel BBMT",
                        downloadQueued,
                        downloadSuccess,
                        downloadFailed,
                        downloadSkipped,
                        true
                    );
                }
            }

            return finishCycle(
                discovery.packageCount(),
                synced,
                skipped,
                failed,
                changedPackageIds,
                startedAt,
                sheetRefresh,
                false,
                null,
                downloadQueued,
                downloadSuccess,
                downloadFailed,
                downloadSkipped,
                parallelAgentBbmt
            );
        } finally {
            if (parallelBbmtRunner != null && !parallelBbmtCompleted) {
                parallelBbmtRunner.requestStop();
            }
            if (parallelAgentBbmt) {
                ExecutionGate.setAllowDownloadDuringMonitor(false);
            }
            BidSheetApiSyncService.setMonitorStopping(false);
        }
    }

    private MonitorCycleResult finishCycle(
        int discoveredCount,
        int synced,
        int skipped,
        int failed,
        Set<String> changedPackageIds,
        String startedAt,
        SheetRefreshResult sheetRefresh,
        boolean interrupted,
        String errorSummary,
        int downloadQueued,
        int downloadSuccess,
        int downloadFailed,
        int downloadSkipped,
        boolean parallelAgentAndBbmt
    ) {
        int rowCount = sheetRefresh == null ? countRows() : sheetRefresh.rowCount();
        boolean sheetChanged = sheetRefresh != null && sheetRefresh.sheetChanged();

        MonitorCycleLog log = new MonitorCycleLog(
            startedAt,
            LocalDateTime.now().format(TS),
            discoveredCount,
            synced,
            skipped,
            failed,
            rowCount,
            !interrupted && sheetChanged,
            interrupted,
            errorSummary
        );
        cycleRepository.append(log);

        return new MonitorCycleResult(
            discoveredCount,
            synced,
            skipped,
            failed,
            rowCount,
            changedPackageIds,
            !interrupted && sheetChanged,
            interrupted,
            errorSummary,
            downloadQueued,
            downloadSuccess,
            downloadFailed,
            downloadSkipped,
            parallelAgentAndBbmt
        );
    }

    private MonitorCycleResult interruptedResult(int discoveredCount, String startedAt) {
        Utils.logPlain("Monitor cycle interrupted before completion.");
        cycleRepository.append(new MonitorCycleLog(
            startedAt,
            LocalDateTime.now().format(TS),
            discoveredCount,
            0,
            0,
            0,
            0,
            false,
            true,
            "interrupted"
        ));
        return new MonitorCycleResult(
            discoveredCount,
            0,
            0,
            0,
            0,
            Set.of(),
            false,
            true,
            "interrupted"
        );
    }

    private int countRows() {
        return (int) packageRepository.findAll().stream()
            .filter(pkg -> pkg != null && pkg.sheetRow() != null)
            .count();
    }

    private void logSheetRefresh(SheetRefreshResult sheetRefresh) {
        if (sheetRefresh == null) {
            return;
        }
        Utils.logPlain("Monitor sheet aggregate: rows=" + sheetRefresh.rowCount()
            + ", sheetDataChanged=" + sheetRefresh.sheetDataChangedKeys().size()
            + ", sheetChanged=" + sheetRefresh.sheetChanged()
            + ", interrupted=" + sheetRefresh.interrupted());
    }

    private ApiSyncBatchResult syncPackagesInParallel(List<BidPackage> packages) {
        return syncPackagesInParallel(packages, null);
    }

    private ApiSyncBatchResult syncPackagesInParallel(List<BidPackage> packages, Consumer<BidPackage> afterSynced) {
        if (packages == null || packages.isEmpty()) {
            return new ApiSyncBatchResult(0, 0, 0, Set.of(), false);
        }
        if (Thread.currentThread().isInterrupted()) {
            return new ApiSyncBatchResult(0, 0, 0, Set.of(), true);
        }

        int workerCount = Math.min(apiSyncParallelism(), packages.size());
        Utils.logPlain("Monitor API sync starting with " + workerCount + " parallel worker(s).");
        ExecutorService pool = Executors.newFixedThreadPool(workerCount, runnable -> {
            Thread thread = new Thread(runnable, "Monitor-ApiSync");
            thread.setDaemon(true);
            return thread;
        });
        ACTIVE_API_SYNC_POOL.set(pool);

        AtomicInteger synced = new AtomicInteger();
        AtomicInteger skipped = new AtomicInteger();
        AtomicInteger failed = new AtomicInteger();
        Set<String> changedPackageIds = ConcurrentHashMap.newKeySet();
        AtomicBoolean interrupted = new AtomicBoolean();
        List<Future<?>> futures = new ArrayList<>();

        try {
            for (BidPackage pkg : packages) {
                if (Thread.currentThread().isInterrupted() || pool.isShutdown()) {
                    interrupted.set(true);
                    break;
                }
                try {
                    futures.add(pool.submit(() -> {
                        if (Thread.currentThread().isInterrupted()) {
                            interrupted.set(true);
                            return;
                        }
                        SyncResult result = apiSyncService.sync(pkg);
                        switch (result.outcome()) {
                            case SYNCED -> {
                                synced.incrementAndGet();
                                if (result.sheetRowChanged()) {
                                    changedPackageIds.add(pkg.id());
                                }
                                if (afterSynced != null) {
                                    afterSynced.accept(pkg);
                                }
                            }
                            case SKIPPED -> skipped.incrementAndGet();
                            case FAILED -> failed.incrementAndGet();
                            case INTERRUPTED -> interrupted.set(true);
                            default -> {
                            }
                        }
                    }));
                } catch (RejectedExecutionException ex) {
                    interrupted.set(true);
                    break;
                }
            }

            for (Future<?> future : futures) {
                if (Thread.currentThread().isInterrupted()) {
                    interrupted.set(true);
                    break;
                }
                try {
                    future.get();
                } catch (ExecutionException ex) {
                    Throwable cause = ex.getCause();
                    if (pool.isShutdown()
                        || cause instanceof CancellationException
                        || cause instanceof InterruptedException) {
                        interrupted.set(true);
                    } else {
                        failed.incrementAndGet();
                        Utils.logPlain("Monitor API sync worker failed: "
                            + (cause == null ? ex.getMessage() : cause.getMessage()));
                    }
                } catch (InterruptedException ex) {
                    interrupted.set(true);
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        } finally {
            ACTIVE_API_SYNC_POOL.compareAndSet(pool, null);
            pool.shutdown();
            if (interrupted.get() || Thread.currentThread().isInterrupted()) {
                pool.shutdownNow();
            }
            try {
                if (!pool.awaitTermination(API_SYNC_TIMEOUT_MINUTES, TimeUnit.MINUTES)) {
                    pool.shutdownNow();
                    Utils.logPlain("Monitor API sync timed out after " + API_SYNC_TIMEOUT_MINUTES + " minute(s).");
                }
            } catch (InterruptedException ex) {
                pool.shutdownNow();
                interrupted.set(true);
                Thread.currentThread().interrupt();
            }
        }

        return new ApiSyncBatchResult(
            synced.get(),
            skipped.get(),
            failed.get(),
            Set.copyOf(changedPackageIds),
            interrupted.get() || Thread.currentThread().isInterrupted()
        );
    }

    private record ApiSyncBatchResult(
        int synced,
        int skipped,
        int failed,
        Set<String> changedPackageIds,
        boolean interrupted
    ) {
    }
}
