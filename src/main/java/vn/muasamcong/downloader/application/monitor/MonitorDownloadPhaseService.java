package vn.muasamcong.downloader.application.monitor;

import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import vn.muasamcong.downloader.core.DownloadCoordinator;
import vn.muasamcong.downloader.core.RunStats;
import vn.muasamcong.downloader.domain.bidpackage.BidPackage;
import vn.muasamcong.downloader.domain.bidpackage.PackageDownloadStatus;
import vn.muasamcong.downloader.domain.bidpackage.PackageRepository;
import vn.muasamcong.downloader.model.KeywordTarget;
import vn.muasamcong.downloader.util.Utils;

public final class MonitorDownloadPhaseService {

    private static final DateTimeFormatter TS = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss");

    private final PackageRepository packageRepository;

    public MonitorDownloadPhaseService(PackageRepository packageRepository) {
        this.packageRepository = packageRepository;
    }

    public PackageDownloadPlan plan(List<BidPackage> packages, List<Path> selectedRoots) {
        PackageDownloadPlan plan = PackageDownloadPlanner.plan(packages, selectedRoots);
        for (String line : plan.skipLogLines()) {
            Utils.logPlain("Monitor download skip: " + line);
        }
        Utils.logPlain("Monitor download plan: queued=" + plan.targets().size() + ", skipped=" + plan.skippedCount());
        return plan;
    }

    public PackageDownloadPlan planBbmt(List<BidPackage> packages, List<Path> selectedRoots) {
        PackageDownloadPlan plan = PackageDownloadPlanner.planBbmtOnly(packages, selectedRoots);
        for (String line : plan.skipLogLines()) {
            Utils.logPlain("Monitor BBMT download skip: " + line);
        }
        Utils.logPlain("Monitor BBMT download plan: queued=" + plan.targets().size()
            + ", skipped=" + plan.skippedCount());
        return plan;
    }

    public RunStats execute(PackageDownloadPlan plan, int concurrency) {
        if (plan == null || plan.targets().isEmpty()) {
            return null;
        }
        return DownloadCoordinator.runTargetsDownloadOnly(
            plan.targets(),
            concurrency,
            plan.hintsByTargetKey(),
            plan.detailUrlByTargetKey()
        );
    }

    public void applyResults(List<BidPackage> packages, PackageDownloadPlan plan, RunStats stats) {
        if (packages == null || plan == null || stats == null) {
            return;
        }
        Map<String, BidPackage> byId = new HashMap<>();
        for (BidPackage pkg : packages) {
            if (pkg != null && pkg.id() != null) {
                byId.put(pkg.id(), pkg);
            }
        }

        String now = LocalDateTime.now().format(TS);
        Map<String, RunStats.ProcessedRecord> processedByFolderKeyword = indexProcessed(stats);

        for (KeywordTarget target : plan.targets()) {
            String id = BidPackage.buildId(
                target.folderPath() == null ? null : target.folderPath().toAbsolutePath().normalize().toString(),
                target.keyword()
            );
            BidPackage pkg = byId.get(id);
            if (pkg == null) {
                continue;
            }
            RunStats.ProcessedRecord record = processedByFolderKeyword.get(targetKey(target));
            BidPackage updated = applyRecord(pkg, record, now);
            packageRepository.save(updated);
        }
    }

    private static BidPackage applyRecord(BidPackage pkg, RunStats.ProcessedRecord record, String now) {
        if (record == null) {
            return pkg.withDownloadResult(
                now,
                PackageDownloadStatus.FAILED.name(),
                "No download result recorded",
                pkg.lastDownloadedHintsHash(),
                now
            );
        }
        String status = record.status() == null ? "" : record.status().trim().toUpperCase();
        if ("SUCCESS".equals(status)) {
            return pkg.withDownloadResult(
                now,
                PackageDownloadStatus.SUCCESS.name(),
                null,
                pkg.downloadHintsHash(),
                now
            );
        }
        if ("STOPPED".equals(status)) {
            return pkg.withDownloadResult(
                pkg.lastDownloadAt(),
                PackageDownloadStatus.PARTIAL.name(),
                record.error(),
                pkg.lastDownloadedHintsHash(),
                now
            );
        }
        return pkg.withDownloadResult(
            now,
            PackageDownloadStatus.FAILED.name(),
            record.error() == null ? "Download failed" : record.error(),
            pkg.lastDownloadedHintsHash(),
            now
        );
    }

    private static Map<String, RunStats.ProcessedRecord> indexProcessed(RunStats stats) {
        Map<String, RunStats.ProcessedRecord> map = new HashMap<>();
        for (RunStats.ProcessedRecord record : stats.getProcessedRecords()) {
            if (record == null || record.keyword() == null) {
                continue;
            }
            String key = record.folderPath() == null
                ? record.keyword().trim()
                : record.folderPath() + "|" + record.keyword().trim();
            map.put(key, record);
        }
        return map;
    }

    private static String targetKey(KeywordTarget target) {
        if (target == null || target.folderPath() == null || target.keyword() == null || target.keyword().isBlank()) {
            return "";
        }
        return target.folderPath().toAbsolutePath().normalize() + "|" + target.keyword().trim();
    }
}
