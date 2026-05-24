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
    private final Supplier<List<java.nio.file.Path>> rootsSupplier;
    private final AtomicBoolean cycleInProgress = new AtomicBoolean(false);

    private ScheduledExecutorService executor;
    private ScheduledFuture<?> scheduledFuture;

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
            0,
            intervalMinutes,
            TimeUnit.MINUTES
        );
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
    }

    public synchronized void restart() {
        stop();
        start();
    }

    public MonitorCycleResult runOnce() {
        return runOnce(1);
    }

    public MonitorCycleResult runOnce(int downloadConcurrency) {
        if (!ExecutionGate.tryBeginMonitor()) {
            String reason = ExecutionGate.isDownloadBusy()
                ? "download (Selenium) is running"
                : "another monitor cycle is active";
            Utils.logPlain("Monitor cycle skipped: " + reason + ".");
            return skippedCycleResult(reason);
        }

        boolean monitorReleased = false;
        try {
            List<java.nio.file.Path> roots = rootsSupplier.get();
            if (roots == null || roots.isEmpty()) {
                throw new IllegalArgumentException("No folders selected for monitor.");
            }

            MonitorSettings settings = settingsRepository.load();
            int concurrency = Math.max(1, Math.min(10, downloadConcurrency));
            MonitorCycleRequest request = new MonitorCycleRequest(
                roots,
                settings.downloadFilesAfterSheet(),
                concurrency
            );

            MonitorCycleResult phase1 = cycleService.run(request);
            if (!shouldRunDownloadPhase(request, phase1)) {
                return phase1;
            }

            ExecutionGate.endMonitor();
            monitorReleased = true;

            if (!ExecutionGate.tryBeginDownload()) {
                Utils.logPlain("Monitor download phase skipped: download gate busy.");
                return phase1.withDownloadStats(0, 0, 0, 0);
            }

            try {
                return runDownloadPhase(phase1, request, roots);
            } finally {
                ExecutionGate.endDownload();
            }
        } finally {
            if (!monitorReleased) {
                ExecutionGate.endMonitor();
            }
        }
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
            return phase1.withDownloadStats(0, 0, 0, skipped);
        }

        RunStats stats = downloadPhaseService.execute(plan, request.downloadConcurrency());
        downloadPhaseService.applyResults(packages, plan, stats);

        int success = stats == null ? 0 : stats.getSuccessCount();
        int failed = stats == null ? 0 : stats.getFailCount();
        Utils.logPlain("Monitor download phase done: queued=" + queued
            + ", success=" + success + ", failed=" + failed + ", skipped=" + skipped);
        return phase1.withDownloadStats(queued, success, failed, skipped);
    }

    private static boolean shouldRunDownloadPhase(MonitorCycleRequest request, MonitorCycleResult phase1) {
        if (request == null || phase1 == null) {
            return false;
        }
        if (!request.downloadFilesAfterSheet()) {
            return false;
        }
        if (!AppFeatures.isMonitorSeleniumAfterSheetEnabled()) {
            return false;
        }
        return !phase1.interrupted();
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
        }
    }
}
