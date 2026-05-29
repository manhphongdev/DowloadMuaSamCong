package vn.muasamcong.downloader.application.monitor;

import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Supplier;
import vn.muasamcong.downloader.core.AppFeatures;
import vn.muasamcong.downloader.core.ExecutionGate;
import vn.muasamcong.downloader.core.RunStats;
import vn.muasamcong.downloader.domain.bidpackage.BidPackage;
import vn.muasamcong.downloader.domain.monitor.MonitorCycleLog;
import vn.muasamcong.downloader.domain.monitor.MonitorCycleRepository;
import vn.muasamcong.downloader.domain.monitor.MonitorSettings;
import vn.muasamcong.downloader.domain.monitor.MonitorSettingsRepository;
import vn.muasamcong.downloader.application.sheet.SheetPublishService;
import vn.muasamcong.downloader.application.sheet.SheetRefreshResult;
import vn.muasamcong.downloader.infrastructure.persistence.JsonMonitorCycleRepository;
import vn.muasamcong.downloader.infrastructure.persistence.JsonMonitorSettingsRepository;
import vn.muasamcong.downloader.infrastructure.persistence.JsonPackageRepository;
import vn.muasamcong.downloader.util.Utils;

public final class MonitorScheduler {

    private final Path bidRowsJsonFile;
    private final MonitorSettingsRepository settingsRepository;
    private final MonitorCycleRepository cycleRepository;
    private final MonitorCycleService cycleService;
    private final JsonPackageRepository packageRepository;
    private final MonitorDownloadPhaseService downloadPhaseService;
    private final SheetAggregateService sheetAggregateService;
    private final SheetPublishService sheetPublishService;
    private final Supplier<List<java.nio.file.Path>> rootsSupplier;
    private final AtomicBoolean cycleInProgress = new AtomicBoolean(false);
    private volatile Thread activeCycleThread;
    private volatile Runnable onCycleIdle = () -> { };

    private ScheduledExecutorService executor;
    private ScheduledFuture<?> scheduledFuture;
    private ScheduledExecutorService sheetExportExecutor;
    private ScheduledFuture<?> sheetExportFuture;

    public MonitorScheduler(
        Path bidRowsJsonFile,
        Supplier<List<java.nio.file.Path>> rootsSupplier
    ) {
        this.bidRowsJsonFile = bidRowsJsonFile;
        this.settingsRepository = new JsonMonitorSettingsRepository();
        this.cycleRepository = new JsonMonitorCycleRepository();
        this.packageRepository = new JsonPackageRepository();
        this.cycleService = new MonitorCycleService(bidRowsJsonFile, packageRepository, cycleRepository);
        this.downloadPhaseService = new MonitorDownloadPhaseService(packageRepository);
        this.sheetAggregateService = new SheetAggregateService(packageRepository);
        this.sheetPublishService = new SheetPublishService();
        this.rootsSupplier = rootsSupplier;
    }

    public synchronized void start() {
        MonitorSettings settings = settingsRepository.load();
        if (!settings.enabled()) {
            stop();
            return;
        }
        if (executor == null || executor.isShutdown()) {
            executor = Executors.newSingleThreadScheduledExecutor(r -> {
                Thread thread = new Thread(r, "Monitor-Scheduler");
                thread.setDaemon(true);
                return thread;
            });
        }
        if (scheduledFuture != null && !scheduledFuture.isCancelled()) {
            scheduledFuture.cancel(false);
        }
        long intervalMinutes = settings.intervalMinutes();
        scheduledFuture = executor.scheduleWithFixedDelay(
            this::runScheduledCycle,
            intervalMinutes,
            intervalMinutes,
            TimeUnit.MINUTES
        );
        startPeriodicSheetExport();
        Utils.logPlain("Monitor scheduler started. Interval: " + intervalMinutes + " minute(s).");
    }

    public synchronized void stop() {
        if (scheduledFuture != null) {
            scheduledFuture.cancel(false);
            scheduledFuture = null;
        }
        if (executor != null) {
            executor.shutdown();
            executor = null;
        }
        stopPeriodicSheetExport();
    }

    public synchronized void restart() {
        stop();
        start();
    }

    public MonitorCycleResult runOnce() {
        MonitorSettings settings = settingsRepository.load().normalized();
        return runOnce(settings.seleniumDownloadConcurrency());
    }

    public void setOnCycleIdle(Runnable callback) {
        onCycleIdle = callback == null ? () -> { } : callback;
    }

    public boolean isCycleActive() {
        return cycleInProgress.get() || ExecutionGate.isMonitorBusy();
    }

    public void requestStop() {
        Utils.logPlain("Monitor: stop requested.");
        MonitorCycleService.cancelActiveWork();
        Thread cycleThread = activeCycleThread;
        if (cycleThread != null) {
            cycleThread.interrupt();
        }
    }

    public MonitorCycleResult runOnce(int downloadConcurrency) {
        if (!ExecutionGate.tryBeginMonitor()) {
            String reason = ExecutionGate.isDownloadBusy()
                ? "download (Selenium) is running"
                : "another monitor cycle is active";
            Utils.logPlain("Monitor cycle skipped: " + reason + ".");
            return skippedCycleResult(reason);
        }

        activeCycleThread = Thread.currentThread();
        boolean monitorReleased = false;
        try {
            List<java.nio.file.Path> roots = rootsSupplier.get();
            if (roots == null || roots.isEmpty()) {
                throw new IllegalArgumentException("No folders selected for monitor.");
            }

            MonitorSettings settings = settingsRepository.load().normalized();
            int seleniumConcurrency = downloadConcurrency > 0
                ? downloadConcurrency
                : settings.seleniumDownloadConcurrency();
            MonitorCycleRequest request = new MonitorCycleRequest(
                roots,
                settings.downloadFilesAfterSheet(),
                seleniumConcurrency,
                settings.bbmtSeleniumDownload()
            );

            MonitorCycleResult result = cycleService.run(request);
            if (result.interrupted()) {
                return result;
            }
            if (!request.downloadFilesAfterSheet()) {
                return result;
            }

            ExecutionGate.endMonitor();
            monitorReleased = true;

            if (!result.parallelAgentAndBbmt()
                && AppFeatures.isMonitorBbmtSeleniumEnabled()
                && request.bbmtSeleniumDownload()) {
                result = runGatedDownloadPhase(result, request, roots, true);
            }
            if (AppFeatures.isMonitorSeleniumFallbackEnabled()) {
                result = runGatedDownloadPhase(result, request, roots, false);
            }
            return result;
        } finally {
            activeCycleThread = null;
            if (!monitorReleased) {
                ExecutionGate.endMonitor();
            }
        }
    }

    private MonitorCycleResult runGatedDownloadPhase(
        MonitorCycleResult phase1,
        MonitorCycleRequest request,
        List<Path> roots,
        boolean bbmtOnly
    ) {
        if (!ExecutionGate.tryBeginDownload()) {
            String label = bbmtOnly ? "BBMT" : "Selenium fallback";
            Utils.logPlain("Monitor " + label + " download phase skipped: download gate busy.");
            return phase1;
        }
        try {
            return bbmtOnly
                ? runBbmtDownloadPhase(phase1, request, roots)
                : runDownloadPhase(phase1, request, roots);
        } finally {
            ExecutionGate.endDownload();
        }
    }

    private MonitorCycleResult runBbmtDownloadPhase(
        MonitorCycleResult phase1,
        MonitorCycleRequest request,
        List<Path> roots
    ) {
        Utils.logPlain("Monitor BBMT download phase starting...");
        List<BidPackage> packages = packageRepository.findAll();
        PackageDownloadPlan plan = downloadPhaseService.planBbmt(packages, roots);

        int queued = plan.targets().size();
        int skipped = plan.skippedCount();
        if (queued == 0) {
            Utils.logPlain("Monitor BBMT download phase: no targets queued.");
            return mergeDownloadStats(phase1, 0, 0, 0, skipped);
        }

        RunStats stats = downloadPhaseService.execute(plan, request.downloadConcurrency());
        downloadPhaseService.applyResults(packages, plan, stats);

        int success = stats == null ? 0 : stats.getSuccessCount();
        int failed = stats == null ? 0 : stats.getFailCount();
        Utils.logPlain("Monitor BBMT download phase done: queued=" + queued
            + ", success=" + success + ", failed=" + failed + ", skipped=" + skipped);
        return mergeDownloadStats(phase1, queued, success, failed, skipped);
    }

    private MonitorCycleResult runDownloadPhase(
        MonitorCycleResult phase1,
        MonitorCycleRequest request,
        List<Path> roots
    ) {
        Utils.logPlain("Monitor download phase starting...");
        List<BidPackage> packages = packageRepository.findAll();
        PackageDownloadPlan plan = downloadPhaseService.plan(packages, roots);

        int queued = plan.targets().size();
        int skipped = plan.skippedCount();
        if (queued == 0) {
            Utils.logPlain("Monitor download phase: no targets queued.");
            return mergeDownloadStats(phase1, 0, 0, 0, skipped);
        }

        RunStats stats = downloadPhaseService.execute(plan, request.downloadConcurrency());
        downloadPhaseService.applyResults(packages, plan, stats);

        int success = stats == null ? 0 : stats.getSuccessCount();
        int failed = stats == null ? 0 : stats.getFailCount();
        Utils.logPlain("Monitor download phase done: queued=" + queued
            + ", success=" + success + ", failed=" + failed + ", skipped=" + skipped);
        return mergeDownloadStats(phase1, queued, success, failed, skipped);
    }

    private static MonitorCycleResult mergeDownloadStats(
        MonitorCycleResult phase1,
        int queued,
        int success,
        int failed,
        int skipped
    ) {
        return phase1.withDownloadStats(
            phase1.downloadQueued() + queued,
            phase1.downloadSuccess() + success,
            phase1.downloadFailed() + failed,
            phase1.downloadSkipped() + skipped
        );
    }

    private static MonitorCycleResult skippedCycleResult(String reason) {
        return new MonitorCycleResult(
            0,
            0,
            0,
            0,
            0,
            java.util.Set.of(),
            false,
            false,
            reason
        );
    }

    public MonitorSettings loadSettings() {
        return settingsRepository.load();
    }

    public Optional<MonitorCycleLog> latestCycle() {
        return cycleRepository.latest();
    }

    public void saveSettings(MonitorSettings settings) {
        settingsRepository.save(settings);
        restart();
    }

    private void runScheduledCycle() {
        if (!cycleInProgress.compareAndSet(false, true)) {
            Utils.logPlain("Monitor: cycle skipped because previous monitor cycle is still running.");
            return;
        }
        try {
            MonitorSettings settings = settingsRepository.load();
            if (!settings.enabled()) {
                return;
            }
            runOnce();
        } catch (Exception ex) {
            Utils.logPlain("Monitor scheduled cycle failed: " + ex.getMessage());
        } finally {
            cycleInProgress.set(false);
            notifyCycleIdle();
        }
    }

    private void notifyCycleIdle() {
        try {
            onCycleIdle.run();
        } catch (RuntimeException ignored) {
            // UI callback must not break scheduler
        }
    }

    private void startPeriodicSheetExport() {
        stopPeriodicSheetExport();
        if (sheetExportExecutor == null || sheetExportExecutor.isShutdown()) {
            sheetExportExecutor = Executors.newSingleThreadScheduledExecutor(r -> {
                Thread thread = new Thread(r, "Monitor-SheetExport");
                thread.setDaemon(true);
                return thread;
            });
        }
        long intervalMinutes = AppFeatures.MONITOR_SHEET_EXPORT_INTERVAL_MINUTES;
        sheetExportFuture = sheetExportExecutor.scheduleAtFixedRate(
            this::runPeriodicSheetExport,
            1,
            intervalMinutes,
            TimeUnit.MINUTES
        );
        Utils.logPlain("Monitor periodic sheet export started. First run in 1 minute, then every "
            + intervalMinutes + " minute(s).");
        if (!sheetPublishService.isAutoSyncEnabled()) {
            Utils.logPlain("Google Sheets auto sync is OFF — periodic export will only refresh bid_sheet_rows.json locally.");
        }
    }

    private void stopPeriodicSheetExport() {
        if (sheetExportFuture != null) {
            sheetExportFuture.cancel(false);
            sheetExportFuture = null;
        }
        if (sheetExportExecutor != null) {
            sheetExportExecutor.shutdown();
            sheetExportExecutor = null;
        }
    }

    private void runPeriodicSheetExport() {
        MonitorSettings settings = settingsRepository.load();
        if (!settings.enabled()) {
            return;
        }
        if (ExecutionGate.isDownloadBusy()) {
            Utils.logPlain("Periodic sheet export skipped: Selenium download in progress.");
            return;
        }
        try {
            SheetRefreshResult refresh = sheetAggregateService.writeAllRowsToJson(bidRowsJsonFile);
            if (refresh.interrupted()) {
                Utils.logPlain("Periodic sheet export interrupted.");
                return;
            }
            if (refresh.rowCount() == 0) {
                Utils.logPlain("Periodic sheet export skipped: no sheet rows (run monitor sync first).");
                return;
            }
            boolean published = sheetPublishService.publishIfEnabled(bidRowsJsonFile);
            Utils.logPlain("Periodic sheet export finished. rows=" + refresh.rowCount()
                + (published ? ", pushed to Google Sheets" : ", local JSON only"));
            notifyCycleIdle();
        } catch (Exception ex) {
            Utils.logPlain("Periodic sheet export failed: " + ex.getMessage());
        }
    }
}
