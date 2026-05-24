package vn.muasamcong.downloader.application.monitor;

import java.util.Set;

public record MonitorCycleResult(
    int discoveredCount,
    int apiSyncedCount,
    int apiSkippedCount,
    int apiFailedCount,
    int sheetRowCount,
    Set<String> sheetChangedPackageIds,
    boolean sheetChanged,
    boolean interrupted,
    String errorSummary,
    int downloadQueued,
    int downloadSuccess,
    int downloadFailed,
    int downloadSkipped
) {

    public MonitorCycleResult(
        int discoveredCount,
        int apiSyncedCount,
        int apiSkippedCount,
        int apiFailedCount,
        int sheetRowCount,
        Set<String> sheetChangedPackageIds,
        boolean sheetChanged,
        boolean interrupted,
        String errorSummary
    ) {
        this(
            discoveredCount,
            apiSyncedCount,
            apiSkippedCount,
            apiFailedCount,
            sheetRowCount,
            sheetChangedPackageIds,
            sheetChanged,
            interrupted,
            errorSummary,
            0,
            0,
            0,
            0
        );
    }

    public MonitorCycleResult {
        sheetChangedPackageIds = sheetChangedPackageIds == null ? Set.of() : Set.copyOf(sheetChangedPackageIds);
    }

    public int sheetDataChangedCount() {
        return sheetChangedPackageIds.size();
    }

    public MonitorCycleResult withDownloadStats(int queued, int success, int failed, int skipped) {
        return new MonitorCycleResult(
            discoveredCount,
            apiSyncedCount,
            apiSkippedCount,
            apiFailedCount,
            sheetRowCount,
            sheetChangedPackageIds,
            sheetChanged,
            interrupted,
            errorSummary,
            queued,
            success,
            failed,
            skipped
        );
    }
}
