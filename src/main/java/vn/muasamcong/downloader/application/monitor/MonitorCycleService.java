package vn.muasamcong.downloader.application.monitor;

import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import vn.muasamcong.downloader.application.monitor.PackageApiSyncService.SyncResult;
import vn.muasamcong.downloader.application.monitor.PackageDiscoveryService.DiscoveryResult;
import vn.muasamcong.downloader.application.sheet.SheetPublishService;
import vn.muasamcong.downloader.application.sheet.SheetRefreshResult;
import vn.muasamcong.downloader.domain.bidpackage.BidPackage;
import vn.muasamcong.downloader.domain.bidpackage.PackageRepository;
import vn.muasamcong.downloader.domain.monitor.MonitorCycleLog;
import vn.muasamcong.downloader.domain.monitor.MonitorCycleRepository;
import vn.muasamcong.downloader.infrastructure.persistence.JsonMonitorCycleRepository;
import vn.muasamcong.downloader.infrastructure.persistence.JsonPackageRepository;
import vn.muasamcong.downloader.util.Utils;

public final class MonitorCycleService {

    private static final DateTimeFormatter TS = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss");
    private static final int API_SYNC_PARALLELISM = 3;
    private static final long API_SYNC_TIMEOUT_MINUTES = 60L;

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

    public MonitorCycleResult run(MonitorCycleRequest request) {
        if (request == null || request.selectedRoots().isEmpty()) {
            throw new IllegalArgumentException("No folders selected for monitor run.");
        }
        String startedAt = LocalDateTime.now().format(TS);
        if (Thread.currentThread().isInterrupted()) {
            return interruptedResult(0, startedAt);
        }

        DiscoveryResult discovery = discoveryService.discover(request.selectedRoots());
        Utils.logPlain("Monitor discovered packages: " + discovery.packageCount()
            + " (folder targets: " + discovery.folderTargetCount() + ")");

        ApiSyncBatchResult apiBatch = syncPackagesInParallel(discovery.packages());
        int synced = apiBatch.synced();
        int skipped = apiBatch.skipped();
        int failed = apiBatch.failed();
        Set<String> changedPackageIds = new LinkedHashSet<>(apiBatch.changedPackageIds());

        Utils.logPlain("Monitor API sync: synced=" + synced + ", skipped=" + skipped + ", failed=" + failed
            + ", parallelism=" + API_SYNC_PARALLELISM);

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
                "interrupted during api sync"
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
                "interrupted before sheet aggregate"
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
                "interrupted during sheet aggregate"
            );
        }

        if (sheetRefresh.sheetChanged()) {
            sheetPublishService.publishIfEnabled(bidRowsJsonFile);
        } else {
            Utils.logPlain("Google Sheets sync skipped because sheet data is unchanged.");
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
            null
        );
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
        String errorSummary
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
            errorSummary
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
        if (packages == null || packages.isEmpty()) {
            return new ApiSyncBatchResult(0, 0, 0, Set.of(), false);
        }
        if (Thread.currentThread().isInterrupted()) {
            return new ApiSyncBatchResult(0, 0, 0, Set.of(), true);
        }

        int workerCount = Math.min(API_SYNC_PARALLELISM, packages.size());
        Utils.logPlain("Monitor API sync starting with " + workerCount + " parallel worker(s).");
        ExecutorService pool = Executors.newFixedThreadPool(workerCount, runnable -> {
            Thread thread = new Thread(runnable, "Monitor-ApiSync");
            thread.setDaemon(true);
            return thread;
        });

        AtomicInteger synced = new AtomicInteger();
        AtomicInteger skipped = new AtomicInteger();
        AtomicInteger failed = new AtomicInteger();
        Set<String> changedPackageIds = ConcurrentHashMap.newKeySet();
        AtomicBoolean interrupted = new AtomicBoolean();
        List<Future<?>> futures = new ArrayList<>();

        try {
            for (BidPackage pkg : packages) {
                if (Thread.currentThread().isInterrupted()) {
                    interrupted.set(true);
                    break;
                }
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
                        }
                        case SKIPPED -> skipped.incrementAndGet();
                        case FAILED -> failed.incrementAndGet();
                        case INTERRUPTED -> interrupted.set(true);
                        default -> {
                        }
                    }
                }));
            }

            for (Future<?> future : futures) {
                if (Thread.currentThread().isInterrupted()) {
                    interrupted.set(true);
                    break;
                }
                try {
                    future.get();
                } catch (ExecutionException ex) {
                    failed.incrementAndGet();
                    Utils.logPlain("Monitor API sync worker failed: "
                        + (ex.getCause() == null ? ex.getMessage() : ex.getCause().getMessage()));
                } catch (InterruptedException ex) {
                    interrupted.set(true);
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        } finally {
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
