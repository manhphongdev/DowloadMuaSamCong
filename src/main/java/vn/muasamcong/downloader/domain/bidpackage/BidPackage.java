package vn.muasamcong.downloader.domain.bidpackage;

import java.util.Objects;
import vn.muasamcong.downloader.model.BidApiParams;
import vn.muasamcong.downloader.model.BidSheetRow;
import vn.muasamcong.downloader.model.BidTrackingRecord;
import vn.muasamcong.downloader.model.DownloadHints;

public record BidPackage(
    String id,
    String folderPath,
    String keyword,
    String parentFolderName,
    String detailUrl,
    BidApiParams apiParams,
    BidSheetRow sheetRow,
    String sheetRowHash,
    String contractorCsvHash,
    String goodsExcelHash,
    String downloadHintsHash,
    DownloadHints downloadHints,
    PackageSyncStatus syncStatus,
    String lastApiSyncAt,
    String lastFileCheckAt,
    String lastDownloadAt,
    String lastDownloadStatus,
    String lastDownloadError,
    String lastDownloadedHintsHash,
    String createdAt,
    String updatedAt,
    String lastSyncError
) {

    public static String buildId(String folderPath, String keyword) {
        if (folderPath == null || folderPath.isBlank() || keyword == null || keyword.isBlank()) {
            return "";
        }
        return folderPath.trim() + "|" + keyword.trim();
    }

    public boolean hasValidApiParams() {
        return apiParams != null
            && apiParams.notifyId() != null && !apiParams.notifyId().isBlank()
            && apiParams.notifyNo() != null && !apiParams.notifyNo().isBlank();
    }

    public boolean downloadHintsChangedSinceLastSuccess() {
        if (downloadHintsHash == null || downloadHintsHash.isBlank()) {
            return true;
        }
        return !Objects.equals(downloadHintsHash, lastDownloadedHintsHash);
    }

    public BidTrackingRecord toTrackingRecord() {
        return new BidTrackingRecord(
            id,
            folderPath,
            keyword,
            detailUrl,
            createdAt,
            updatedAt,
            apiParams
        );
    }

    public BidPackage withApiResolution(String detailUrl, BidApiParams apiParams, String updatedAt) {
        return new BidPackage(
            id,
            folderPath,
            keyword,
            parentFolderName,
            detailUrl,
            apiParams,
            sheetRow,
            sheetRowHash,
            contractorCsvHash,
            goodsExcelHash,
            downloadHintsHash,
            downloadHints,
            syncStatus,
            lastApiSyncAt,
            lastFileCheckAt,
            lastDownloadAt,
            lastDownloadStatus,
            lastDownloadError,
            lastDownloadedHintsHash,
            createdAt,
            updatedAt,
            lastSyncError
        );
    }

    public BidPackage withSyncResult(
        BidSheetRow sheetRow,
        String sheetRowHash,
        String contractorCsvHash,
        String goodsExcelHash,
        DownloadHints downloadHints,
        String downloadHintsHash,
        PackageSyncStatus syncStatus,
        String lastApiSyncAt,
        String updatedAt,
        String lastSyncError
    ) {
        return new BidPackage(
            id,
            folderPath,
            keyword,
            parentFolderName,
            detailUrl,
            apiParams,
            sheetRow,
            sheetRowHash,
            contractorCsvHash,
            goodsExcelHash,
            downloadHintsHash,
            downloadHints,
            syncStatus,
            lastApiSyncAt,
            lastFileCheckAt,
            lastDownloadAt,
            lastDownloadStatus,
            lastDownloadError,
            lastDownloadedHintsHash,
            createdAt,
            updatedAt,
            lastSyncError
        );
    }

    public BidPackage withDownloadResult(
        String lastDownloadAt,
        String lastDownloadStatus,
        String lastDownloadError,
        String lastDownloadedHintsHash,
        String updatedAt
    ) {
        return new BidPackage(
            id,
            folderPath,
            keyword,
            parentFolderName,
            detailUrl,
            apiParams,
            sheetRow,
            sheetRowHash,
            contractorCsvHash,
            goodsExcelHash,
            downloadHintsHash,
            downloadHints,
            syncStatus,
            lastApiSyncAt,
            lastFileCheckAt,
            lastDownloadAt,
            lastDownloadStatus,
            lastDownloadError,
            lastDownloadedHintsHash,
            createdAt,
            updatedAt,
            lastSyncError
        );
    }
}
