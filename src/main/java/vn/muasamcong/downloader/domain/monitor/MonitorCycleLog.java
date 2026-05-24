package vn.muasamcong.downloader.domain.monitor;

public record MonitorCycleLog(
    String startedAt,
    String finishedAt,
    int discoveredCount,
    int apiSyncedCount,
    int apiSkippedCount,
    int apiFailedCount,
    int sheetRowCount,
    boolean sheetChanged,
    boolean interrupted,
    String errorSummary
) {
}
