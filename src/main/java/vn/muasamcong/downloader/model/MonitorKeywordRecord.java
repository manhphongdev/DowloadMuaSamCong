package vn.muasamcong.downloader.model;

public record MonitorKeywordRecord(
    String id,
    String key,
    String folderPath,
    String keyword,
    String normalizedKeyword,
    boolean enabled,
    String firstSeenAt,
    String lastSeenAt,
    String createdAt,
    String updatedAt,
    String lastMonitorAt,
    MonitorStatus lastStatus,
    String lastMessage,
    String lastMatchedNotifyNo,
    String lastMatchedDetailUrl,
    BidApiParams apiParams,
    MonitorDownloadStatus downloadStatus,
    String lastDownloadAt,
    String lastDownloadMessage,
    int lastDownloadedFiles,
    int downloadAttempts,
    String lastDownloadSuccessAt,
    int runCount,
    int successCount,
    int notFoundCount,
    int errorCount
) {
}
