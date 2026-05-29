package vn.muasamcong.downloader.application.monitor;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import vn.muasamcong.downloader.export.BbmtFileSupport;
import vn.muasamcong.downloader.domain.bidpackage.BidPackage;
import vn.muasamcong.downloader.domain.bidpackage.PackageDownloadStatus;
import vn.muasamcong.downloader.domain.bidpackage.PackageSyncStatus;
import vn.muasamcong.downloader.export.AgentFileRef;
import vn.muasamcong.downloader.export.EgpAgentFileDownloadService;
import vn.muasamcong.downloader.model.DownloadHints;
import vn.muasamcong.downloader.model.KeywordTarget;

public final class PackageDownloadPlanner {

    private PackageDownloadPlanner() {
    }

    public static PackageDownloadPlan planBbmtOnly(List<BidPackage> packages, List<Path> selectedRoots) {
        List<KeywordTarget> targets = new ArrayList<>();
        Map<String, DownloadHints> hintsByKey = new LinkedHashMap<>();
        Map<String, String> detailUrlByKey = new LinkedHashMap<>();
        List<String> skipLines = new ArrayList<>();
        int skipped = 0;

        if (packages == null || packages.isEmpty()) {
            return PackageDownloadPlan.empty(0, List.of());
        }

        for (BidPackage pkg : packages) {
            if (pkg == null || !belongsToSelectedRoots(pkg, selectedRoots)) {
                continue;
            }
            String reason = skipBbmtReason(pkg);
            if (reason != null) {
                skipped++;
                skipLines.add(pkg.keyword() + ": " + reason);
                continue;
            }
            KeywordTarget target = toTarget(pkg);
            String key = targetKey(target);
            targets.add(target);
            hintsByKey.put(key, bbmtOnlyHints());
            if (pkg.detailUrl() != null && !pkg.detailUrl().isBlank()) {
                detailUrlByKey.put(key, pkg.detailUrl());
            }
        }

        return new PackageDownloadPlan(targets, hintsByKey, detailUrlByKey, skipped, skipLines);
    }

    public static PackageDownloadPlan plan(List<BidPackage> packages, List<Path> selectedRoots) {
        List<KeywordTarget> targets = new ArrayList<>();
        Map<String, DownloadHints> hintsByKey = new LinkedHashMap<>();
        Map<String, String> detailUrlByKey = new LinkedHashMap<>();
        List<String> skipLines = new ArrayList<>();
        int skipped = 0;

        if (packages == null || packages.isEmpty()) {
            return PackageDownloadPlan.empty(0, List.of());
        }

        for (BidPackage pkg : packages) {
            if (pkg == null || !belongsToSelectedRoots(pkg, selectedRoots)) {
                continue;
            }
            String reason = skipReason(pkg);
            if (reason != null) {
                skipped++;
                skipLines.add(pkg.keyword() + ": " + reason);
                continue;
            }
            KeywordTarget target = toTarget(pkg);
            String key = targetKey(target);
            targets.add(target);
            hintsByKey.put(key, pkg.downloadHints());
            if (pkg.detailUrl() != null && !pkg.detailUrl().isBlank()) {
                detailUrlByKey.put(key, pkg.detailUrl());
            }
        }

        return new PackageDownloadPlan(targets, hintsByKey, detailUrlByKey, skipped, skipLines);
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

    public static String bbmtSkipReason(BidPackage pkg, List<Path> selectedRoots) {
        if (pkg == null || !belongsToSelectedRoots(pkg, selectedRoots)) {
            return "outside selected folders";
        }
        return skipBbmtReason(pkg);
    }

    private static String skipBbmtReason(BidPackage pkg) {
        if (pkg.syncStatus() != PackageSyncStatus.SYNCED) {
            return "not SYNCED";
        }
        Path presentOnDisk = packageFolder(pkg) == null
            ? null
            : BbmtFileSupport.findPresentForPackage(packageFolder(pkg), pkg.keyword()).orElse(null);
        if (presentOnDisk != null) {
            return "BBMT already on disk: " + presentOnDisk.toAbsolutePath().normalize();
        }
        DownloadHints hints = pkg.downloadHints();
        if (hints == null || !hints.needBbmtPdf()) {
            return pkg.apiParams() == null || pkg.apiParams().bidOpenId() == null || pkg.apiParams().bidOpenId().isBlank()
                ? "BBMT not required: no bidOpenId"
                : "BBMT not required";
        }
        return null;
    }

    private static String skipReason(BidPackage pkg) {
        if (pkg.syncStatus() != PackageSyncStatus.SYNCED) {
            return "not SYNCED";
        }
        DownloadHints hints = pkg.downloadHints();
        if (hints == null) {
            return "no Selenium downloads in hints";
        }
        if (hasMissingAgentHintFiles(pkg)) {
            return null;
        }
        if (!hints.hasSeleniumDownloads()) {
            return "no Selenium downloads in hints";
        }
        if (shouldRetry(pkg)) {
            return null;
        }
        if (!pkg.downloadHintsChangedSinceLastSuccess()) {
            return "hints unchanged since last successful download";
        }
        return null;
    }

    private static boolean shouldRetry(BidPackage pkg) {
        String status = pkg.lastDownloadStatus();
        if (status == null || status.isBlank()) {
            return true;
        }
        if (PackageDownloadStatus.FAILED.name().equals(status)
            || PackageDownloadStatus.PARTIAL.name().equals(status)) {
            return true;
        }
        return !PackageDownloadStatus.SUCCESS.name().equals(status);
    }

    private static boolean hasMissingAgentHintFiles(BidPackage pkg) {
        if (pkg == null || pkg.downloadHints() == null || pkg.folderPath() == null || pkg.folderPath().isBlank()) {
            return false;
        }
        DownloadHints hints = pkg.downloadHints();
        List<AgentFileRef> refs = new ArrayList<>();
        if (hasText(hints.decisionFileId())) {
            refs.add(new AgentFileRef(hints.decisionFileId(), hints.decisionFileName()));
        }
        if (hasText(hints.reportFileId())) {
            refs.add(new AgentFileRef(hints.reportFileId(), hints.reportFileName()));
        }
        if (refs.isEmpty()) {
            return false;
        }
        Path folder = packageFolder(pkg);
        if (folder == null) {
            return false;
        }
        return EgpAgentFileDownloadService.anyRefMissingOnDisk(folder.resolve("auto-download"), refs);
    }

    private static boolean hasText(String value) {
        return value != null && !value.isBlank() && !"undefined".equalsIgnoreCase(value.trim());
    }

    private static Path packageFolder(BidPackage pkg) {
        if (pkg.folderPath() == null || pkg.folderPath().isBlank()) {
            return null;
        }
        return Path.of(pkg.folderPath());
    }

    private static boolean belongsToSelectedRoots(BidPackage pkg, List<Path> selectedRoots) {
        if (pkg.folderPath() == null || pkg.folderPath().isBlank() || selectedRoots == null || selectedRoots.isEmpty()) {
            return false;
        }
        Path folder;
        try {
            folder = Path.of(pkg.folderPath()).toAbsolutePath().normalize();
        } catch (Exception ex) {
            return false;
        }
        for (Path root : selectedRoots) {
            if (root == null) {
                continue;
            }
            Path normalizedRoot = root.toAbsolutePath().normalize();
            if (folder.startsWith(normalizedRoot)) {
                return true;
            }
        }
        return false;
    }

    private static KeywordTarget toTarget(BidPackage pkg) {
        return new KeywordTarget(pkg.keyword(), Path.of(pkg.folderPath()));
    }

    private static String targetKey(KeywordTarget target) {
        if (target == null || target.folderPath() == null || target.keyword() == null || target.keyword().isBlank()) {
            return "";
        }
        return target.folderPath().toAbsolutePath().normalize() + "|" + target.keyword().trim();
    }
}
