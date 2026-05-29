package vn.muasamcong.downloader.application.monitor;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Queue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import vn.muasamcong.downloader.core.DownloadCoordinator;
import vn.muasamcong.downloader.core.DownloadWorker;
import vn.muasamcong.downloader.core.RunStats;
import vn.muasamcong.downloader.domain.bidpackage.BidPackage;
import vn.muasamcong.downloader.domain.bidpackage.PackageRepository;
import vn.muasamcong.downloader.model.DownloadHints;
import vn.muasamcong.downloader.model.KeywordTarget;
import vn.muasamcong.downloader.util.SeleniumHelper;
import vn.muasamcong.downloader.util.Utils;

/**
 * BBMT Selenium workers fed dynamically while Monitor API sync (+ agent downloads) runs.
 */
public final class MonitorParallelBbmtRunner {

    private static final int MAX_RETRIES = 2;
    private static final long AWAIT_MINUTES = 45L;
    private static final String BASE_URL = "https://muasamcong.mpi.gov.vn/";
    private static final AtomicReference<MonitorParallelBbmtRunner> ACTIVE = new AtomicReference<>();

    private final PackageRepository packageRepository;
    private final List<Path> selectedRoots;
    private final Queue<KeywordTarget> targetQueue = new ConcurrentLinkedQueue<>();
    private final Map<String, DownloadHints> hintsByTargetKey = new ConcurrentHashMap<>();
    private final Map<String, String> detailUrlByTargetKey = new ConcurrentHashMap<>();
    private final List<KeywordTarget> queuedTargets = new ArrayList<>();
    private final AtomicBoolean apiSyncComplete = new AtomicBoolean(false);
    private final AtomicBoolean stopRequested = new AtomicBoolean(false);
    private final AtomicInteger skippedCount = new AtomicInteger();
    private final RunStats stats;
    private final ExecutorService pool;
    private final int concurrency;

    private MonitorParallelBbmtRunner(
        PackageRepository packageRepository,
        List<Path> selectedRoots,
        int concurrency
    ) {
        this.packageRepository = packageRepository;
        this.selectedRoots = selectedRoots == null ? List.of() : List.copyOf(selectedRoots);
        this.concurrency = Math.max(1, Math.min(10, concurrency));
        this.stats = new RunStats(0);
        this.pool = Executors.newFixedThreadPool(this.concurrency, runnable -> {
            Thread thread = new Thread(runnable, "Monitor-BbmtParallel");
            thread.setDaemon(true);
            return thread;
        });
    }

    public static MonitorParallelBbmtRunner start(
        PackageRepository packageRepository,
        List<Path> selectedRoots,
        int seleniumConcurrency
    ) {
        MonitorParallelBbmtRunner runner = new MonitorParallelBbmtRunner(
            packageRepository,
            selectedRoots,
            seleniumConcurrency
        );
        ACTIVE.set(runner);
        runner.startWorkers();
        return runner;
    }

    public static void cancelActive() {
        MonitorParallelBbmtRunner runner = ACTIVE.get();
        if (runner != null) {
            runner.requestStop();
        }
        DownloadCoordinator.requestStop();
    }

    private void startWorkers() {
        Path tempDownloadDir = Utils.dataDirectory().resolve("cache");
        DownloadCoordinator.ensureChromeProfilesForParallel(concurrency);
        SeleniumHelper.quitAllDriversNow();
        Utils.ensureDirectory(tempDownloadDir);
        DownloadWorker.prepareBidInfoOutput();
        Utils.logPlain("Monitor parallel BBMT: Chrome workers=" + concurrency);

        BooleanSupplier awaitMore = () -> !apiSyncComplete.get() && !stopRequested.get();
        for (int slot = 1; slot <= concurrency; slot++) {
            int workerSlot = slot;
            pool.submit(new DownloadWorker(
                targetQueue,
                BASE_URL,
                tempDownloadDir,
                MAX_RETRIES,
                stats,
                stopRequested,
                workerSlot,
                hintsByTargetKey,
                detailUrlByTargetKey,
                false,
                awaitMore
            ));
        }
    }

    public void offerAfterSync(String packageId) {
        if (packageId == null || packageId.isBlank() || stopRequested.get()) {
            return;
        }
        Optional<BidPackage> fresh = packageRepository.findById(packageId);
        if (fresh.isEmpty()) {
            return;
        }
        BidPackage pkg = fresh.get();
        String skip = PackageDownloadPlanner.bbmtSkipReason(pkg, selectedRoots);
        if (skip != null) {
            skippedCount.incrementAndGet();
            return;
        }
        KeywordTarget target = new KeywordTarget(pkg.keyword(), Path.of(pkg.folderPath()));
        String key = targetKey(target);
        hintsByTargetKey.put(key, bbmtOnlyHints());
        if (pkg.detailUrl() != null && !pkg.detailUrl().isBlank()) {
            detailUrlByTargetKey.put(key, pkg.detailUrl());
        }
        synchronized (queuedTargets) {
            queuedTargets.add(target);
        }
        targetQueue.offer(target);
    }

    public void markApiSyncComplete() {
        apiSyncComplete.set(true);
    }

    public void requestStop() {
        stopRequested.set(true);
        apiSyncComplete.set(true);
        pool.shutdownNow();
    }

    public RunStats awaitCompletion() throws InterruptedException {
        markApiSyncComplete();
        pool.shutdown();
        if (!pool.awaitTermination(AWAIT_MINUTES, TimeUnit.MINUTES)) {
            pool.shutdownNow();
            Utils.logPlain("Monitor parallel BBMT timed out after " + AWAIT_MINUTES + " minute(s).");
        }
        SeleniumHelper.quitAllDriversNow();
        ACTIVE.compareAndSet(this, null);
        int queued;
        synchronized (queuedTargets) {
            queued = queuedTargets.size();
        }
        Utils.logPlain("Monitor parallel BBMT done: queued=" + queued
            + ", success=" + stats.getSuccessCount()
            + ", failed=" + stats.getFailCount()
            + ", skipped=" + skippedCount.get());
        return stats;
    }

    public PackageDownloadPlan buildPlan() {
        List<KeywordTarget> targets;
        synchronized (queuedTargets) {
            targets = List.copyOf(queuedTargets);
        }
        return new PackageDownloadPlan(
            targets,
            Map.copyOf(hintsByTargetKey),
            Map.copyOf(detailUrlByTargetKey),
            skippedCount.get(),
            List.of()
        );
    }

    public int skippedCount() {
        return skippedCount.get();
    }

    private static DownloadHints bbmtOnlyHints() {
        return new DownloadHints(
            true,
            false,
            false,
            false,
            true,
            false,
            false,
            false,
            false,
            null,
            null,
            null,
            null,
            null,
            null
        );
    }

    private static String targetKey(KeywordTarget target) {
        if (target == null || target.folderPath() == null || target.keyword() == null || target.keyword().isBlank()) {
            return "";
        }
        return target.folderPath().toAbsolutePath().normalize() + "|" + target.keyword().trim();
    }
}
