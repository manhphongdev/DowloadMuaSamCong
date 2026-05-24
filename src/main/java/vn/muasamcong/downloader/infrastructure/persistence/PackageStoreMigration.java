package vn.muasamcong.downloader.infrastructure.persistence;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import vn.muasamcong.downloader.domain.bidpackage.BidPackage;
import vn.muasamcong.downloader.domain.bidpackage.PackageSyncStatus;
import vn.muasamcong.downloader.model.ArtifactFingerprints;
import vn.muasamcong.downloader.model.BidTrackingRecord;
import vn.muasamcong.downloader.store.ArtifactFingerprintStore;
import vn.muasamcong.downloader.store.BidTrackingRecordStore;

final class PackageStoreMigration {

    private PackageStoreMigration() {
    }

    static LinkedHashMap<String, BidPackage> migrateFromLegacyStores() {
        LinkedHashMap<String, BidPackage> packages = new LinkedHashMap<>();
        for (BidTrackingRecord record : BidTrackingRecordStore.loadRecords()) {
            if (record == null || record.key() == null || record.key().isBlank()) {
                continue;
            }
            ArtifactFingerprints fingerprints = ArtifactFingerprintStore.find(record.key());
            PackageSyncStatus status = PackageSyncStatus.PENDING;
            String lastSync = record.lastSeenAt() != null ? record.lastSeenAt() : record.firstSeenAt();
            packages.put(record.key(), new BidPackage(
                record.key(),
                record.folderPath(),
                record.keyword(),
                parentFolderName(record.folderPath()),
                record.detailUrl(),
                record.apiParams(),
                null,
                fingerprints == null ? null : fingerprints.sheetRowHash(),
                fingerprints == null ? null : fingerprints.contractorCsvHash(),
                fingerprints == null ? null : fingerprints.goodsExcelHash(),
                fingerprints == null ? null : fingerprints.downloadHintsHash(),
                null,
                status,
                lastSync,
                null,
                null,
                null,
                null,
                null,
                record.firstSeenAt() != null ? record.firstSeenAt() : lastSync,
                record.lastSeenAt() != null ? record.lastSeenAt() : lastSync,
                null
            ));
        }
        return packages;
    }

    private static String parentFolderName(String folderPath) {
        if (folderPath == null || folderPath.isBlank()) {
            return null;
        }
        try {
            var parent = java.nio.file.Path.of(folderPath).getParent();
            if (parent == null || parent.getFileName() == null) {
                return null;
            }
            return parent.getFileName().toString();
        } catch (Exception ex) {
            return null;
        }
    }
}
