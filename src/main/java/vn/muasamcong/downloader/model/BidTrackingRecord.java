package vn.muasamcong.downloader.model;

public record BidTrackingRecord(
    String key,
    String folderPath,
    String keyword,
    String detailUrl,
    String firstSeenAt,
    String lastSeenAt,
    BidApiParams apiParams
) {
}
