package vn.muasamcong.downloader.application.monitor;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Objects;
import java.util.Optional;
import vn.muasamcong.downloader.domain.bidpackage.BidPackage;
import vn.muasamcong.downloader.domain.bidpackage.PackageRepository;
import vn.muasamcong.downloader.domain.bidpackage.PackageSyncStatus;
import vn.muasamcong.downloader.export.ArtifactFingerprintService;
import vn.muasamcong.downloader.export.BidSearchApiResolver;
import vn.muasamcong.downloader.export.BidSheetApiSyncService;
import vn.muasamcong.downloader.export.BidSheetApiSyncService.PackageApiSyncResult;
import vn.muasamcong.downloader.model.ResolvedBidDetail;
import vn.muasamcong.downloader.store.ArtifactFingerprintStore;
import vn.muasamcong.downloader.store.BidTrackingRecordStore;
import vn.muasamcong.downloader.util.Utils;

public final class PackageApiSyncService {

    private static final DateTimeFormatter TS = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss");

    private final PackageRepository packageRepository;

    public PackageApiSyncService(PackageRepository packageRepository) {
        this.packageRepository = packageRepository;
    }

    public SyncResult sync(BidPackage pkg) {
        if (pkg == null || pkg.id() == null || pkg.id().isBlank()) {
            return SyncResult.skipped("invalid package");
        }
        if (Thread.currentThread().isInterrupted()) {
            return SyncResult.interrupted();
        }

        String now = LocalDateTime.now().format(TS);
        try {
            BidPackage current = resolveApiParams(pkg, now);
            if (!current.hasValidApiParams()) {
                current = current.withSyncResult(
                    null,
                    null,
                    null,
                    null,
                    null,
                    null,
                    PackageSyncStatus.FAILED,
                    null,
                    now,
                    "Search API did not resolve notify id/no"
                );
                packageRepository.save(current);
                return SyncResult.failed("search unresolved");
            }

            BidTrackingRecordStore.upsertFromResolved(
                current.folderPath(),
                current.keyword(),
                current.detailUrl(),
                current.apiParams(),
                current.createdAt()
            );

            PackageApiSyncResult apiResult = BidSheetApiSyncService.syncPackage(current.toTrackingRecord());
            if (apiResult == null || apiResult.row() == null) {
                current = current.withSyncResult(
                    null,
                    null,
                    null,
                    null,
                    null,
                    null,
                    PackageSyncStatus.FAILED,
                    null,
                    now,
                    "Sheet row build failed"
                );
                packageRepository.save(current);
                return SyncResult.failed("sheet build failed");
            }

            String hintsHash = ArtifactFingerprintService.hashDownloadHints(apiResult.hints());
            ArtifactFingerprintStore.updateHashes(
                current.id(),
                apiResult.sheetRowHash(),
                apiResult.contractorCsvHash(),
                apiResult.goodsExcelHash(),
                hintsHash
            );

            current = current.withSyncResult(
                apiResult.row(),
                apiResult.sheetRowHash(),
                apiResult.contractorCsvHash(),
                apiResult.goodsExcelHash(),
                apiResult.hints(),
                hintsHash,
                PackageSyncStatus.SYNCED,
                now,
                now,
                null
            );
            packageRepository.save(current);
            boolean sheetRowChanged = !Objects.equals(pkg.sheetRowHash(), apiResult.sheetRowHash());
            return SyncResult.synced(sheetRowChanged);
        } catch (Exception ex) {
            String message = ex.getMessage() == null ? ex.getClass().getSimpleName() : ex.getMessage();
            Utils.logPlain("Package API sync failed for " + pkg.keyword() + ": " + message);
            BidPackage failed = pkg.withSyncResult(
                pkg.sheetRow(),
                pkg.sheetRowHash(),
                pkg.contractorCsvHash(),
                pkg.goodsExcelHash(),
                pkg.downloadHints(),
                pkg.downloadHintsHash(),
                PackageSyncStatus.FAILED,
                pkg.lastApiSyncAt(),
                now,
                message
            );
            packageRepository.save(failed);
            return SyncResult.failed(message);
        }
    }

    private BidPackage resolveApiParams(BidPackage pkg, String now) {
        if (pkg.hasValidApiParams()) {
            return pkg;
        }
        Optional<ResolvedBidDetail> resolved = BidSearchApiResolver.resolve(pkg.keyword());
        if (resolved.isEmpty()) {
            return pkg;
        }
        ResolvedBidDetail detail = resolved.get();
        return pkg.withApiResolution(detail.detailUrl(), detail.apiParams(), now);
    }

    public enum SyncOutcome {
        SYNCED,
        SKIPPED,
        FAILED,
        INTERRUPTED
    }

    public record SyncResult(SyncOutcome outcome, String detail, boolean sheetRowChanged) {

        static SyncResult synced(boolean sheetRowChanged) {
            return new SyncResult(SyncOutcome.SYNCED, null, sheetRowChanged);
        }

        static SyncResult skipped(String detail) {
            return new SyncResult(SyncOutcome.SKIPPED, detail, false);
        }

        static SyncResult failed(String detail) {
            return new SyncResult(SyncOutcome.FAILED, detail, false);
        }

        static SyncResult interrupted() {
            return new SyncResult(SyncOutcome.INTERRUPTED, "interrupted", false);
        }
    }
}
